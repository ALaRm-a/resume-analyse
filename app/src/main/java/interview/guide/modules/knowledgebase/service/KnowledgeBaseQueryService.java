package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.bm25.HybridConfigProperties;
import interview.guide.modules.knowledgebase.bm25.HybridSearchService;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.rerank.DashScopeRerankService;
import interview.guide.modules.knowledgebase.rerank.RerankConfigProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final Pattern SHORT_TOKEN_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_-]{2,20}$");
    // 中文疑问前缀："什么是X" / "如何X" → 提取 X
    private static final Pattern ZH_QUESTION_PREFIX = Pattern.compile(
            "^(?:什么是|如何|怎么|怎样|为什么|什么叫|什么叫做|讲一下|解释一下|介绍一下|说一下|谈谈|描述)(.+)$");
    // 中文疑问后缀："X是什么" / "X有哪些" → 提取 X
    private static final Pattern ZH_QUESTION_SUFFIX = Pattern.compile(
            "^(.+?)(?:是什么|怎么样|如何|有哪些|有什么|是啥|是干什么的).*$");
    private static final int STREAM_PROBE_CHARS = 120;

    private final ChatClient chatClient;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final int shortQueryLength;
    private final int topkShort;
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;
    private final double minScoreDefault;
    // ===== Reranker + 混合检索新增依赖 =====
    private final HybridSearchService hybridSearchService;
    private final DashScopeRerankService rerankService;
    private final HybridConfigProperties hybridConfig;
    private final RerankConfigProperties rerankConfig;
    /** skipPatterns 预编译缓存，@PostConstruct 时初始化 */
    private List<Pattern> compiledSkipPatterns;

    public KnowledgeBaseQueryService(
            ChatClient.Builder chatClientBuilder,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            @Value("classpath:prompts/knowledgebase-query-system.st") Resource systemPromptResource,
            @Value("classpath:prompts/knowledgebase-query-user.st") Resource userPromptResource,
            @Value("classpath:prompts/knowledgebase-query-rewrite.st") Resource rewritePromptResource,
            @Value("${app.ai.rag.rewrite.enabled:true}") boolean rewriteEnabled,
            @Value("${app.ai.rag.search.short-query-length:4}") int shortQueryLength,
            @Value("${app.ai.rag.search.topk-short:20}") int topkShort,
            @Value("${app.ai.rag.search.topk-medium:12}") int topkMedium,
            @Value("${app.ai.rag.search.topk-long:8}") int topkLong,
            @Value("${app.ai.rag.search.min-score-short:0.18}") double minScoreShort,
            @Value("${app.ai.rag.search.min-score-default:0.28}") double minScoreDefault,
            HybridSearchService hybridSearchService,
            DashScopeRerankService rerankService,
            HybridConfigProperties hybridConfig,
            RerankConfigProperties rerankConfig) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.vectorService = vectorService;
        this.listService = listService;
        this.countService = countService;
        this.systemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.rewritePromptTemplate = new PromptTemplate(rewritePromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.rewriteEnabled = rewriteEnabled;
        this.shortQueryLength = shortQueryLength;
        this.topkShort = topkShort;
        this.topkMedium = topkMedium;
        this.topkLong = topkLong;
        this.minScoreShort = minScoreShort;
        this.minScoreDefault = minScoreDefault;
        this.hybridSearchService = hybridSearchService;
        this.rerankService = rerankService;
        this.hybridConfig = hybridConfig;
        this.rerankConfig = rerankConfig;
    }

    /**
     * 启动时校验配置合法性 + 预编译 skipPatterns
     *
     * <p>非法组合 {@code hybrid=false && rerank=true} 直接抛异常拒绝启动，
     * 不做静默降级（避免运维困惑）。</p>
     */
    @PostConstruct
    void validateRerankConfig() {
        // 预编译 skipPatterns 正则
        List<String> patterns = rerankConfig.getSkipPatterns();
        compiledSkipPatterns = (patterns == null || patterns.isEmpty())
                ? List.of()
                : patterns.stream().map(Pattern::compile).toList();

        // 校验非法组合
        if (!hybridConfig.isEnabled() && rerankConfig.isEnabled()) {
            throw new IllegalStateException(
                "非法配置组合：hybrid.enabled=false 且 rerank.enabled=true。"
                + "Rerank 依赖混合检索的候选结果，必须先开启 hybrid.enabled");
        }

        log.info("RAG 检索配置加载完成: hybrid={}, rerank={}, recallPerPath={}, recallTopK={}, "
                + "hybridFinalTopN={}, rerankFinalTopN={}, minCandidates={}, skipPatterns={}",
            hybridConfig.isEnabled(), rerankConfig.isEnabled(),
            hybridConfig.getRecallPerPath(), hybridConfig.getRecallTopK(),
            hybridConfig.getFinalTopN(), rerankConfig.getFinalTopN(),
            rerankConfig.getMinCandidatesForRerank(), compiledSkipPatterns.size());
    }

    /**
     * 基于单个知识库回答用户问题
     *
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题（RAG）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        return answerQuestion(knowledgeBaseIds, question, null);
    }

    /**
     * 基于多个知识库回答用户问题（RAG），支持接口级 rerank 开关
     *
     * @param rerankOverride null=默认策略（闸门自动判断），true=强制精排，false=强制跳过精排
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question, Boolean rerankOverride) {
        log.info("收到知识库提问: kbIds={}, question={}, rerankOverride={}", knowledgeBaseIds, question, rerankOverride);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return NO_RESULT_RESPONSE;
        }

        // 1. 验证知识库是否存在并更新问题计数（合并数据库操作）
        countService.updateQuestionCounts(knowledgeBaseIds);

        // 2. Query rewrite + 三级分层检索（RAG）
        QueryContext queryContext = buildQueryContext(question);
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds, rerankOverride);

        if (!hasEffectiveHit(question, relevantDocs)) {
            return NO_RESULT_RESPONSE;
        }

        // 3. 构建上下文（合并检索到的文档）
        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

        // 4. 构建提示词
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context, question);

        try {
            // 5. 调用AI生成回答
            String answer = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            answer = normalizeAnswer(answer);

            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds);
            return answer;

        } catch (Exception e) {
            log.error("知识库问答失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return systemPromptTemplate.render();
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    /**
     * 查询知识库并返回完整响应
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        String answer = answerQuestion(request.knowledgeBaseIds(), request.question());

        // 获取知识库名称（多个知识库用逗号分隔）
        List<String> kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds());
        String kbNamesStr = String.join("、", kbNames);

        // 使用第一个知识库ID作为主要标识（兼容前端）
        Long primaryKbId = request.knowledgeBaseIds().getFirst();

        return new QueryResponse(answer, primaryKbId, kbNamesStr);
    }

    /**
     * 流式查询知识库（SSE）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, null);
    }

    /**
     * 流式查询知识库（SSE），支持接口级 rerank 开关
     *
     * @param rerankOverride null=默认策略（闸门自动判断），true=强制精排，false=强制跳过精排
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, Boolean rerankOverride) {
        log.info("收到知识库流式提问: kbIds={}, question={}, rerankOverride={}", knowledgeBaseIds, question, rerankOverride);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return Flux.just(NO_RESULT_RESPONSE);
        }

        try {
            // 1. 验证知识库是否存在并更新问题计数
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 2. Query rewrite + 三级分层检索
            QueryContext queryContext = buildQueryContext(question);
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds, rerankOverride);

            if (!hasEffectiveHit(question, relevantDocs)) {
                return Flux.just(NO_RESULT_RESPONSE);
            }

            // 3. 构建上下文
            String context = relevantDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 5. 流式调用 + 探测窗口归一化：既保留流式速度，又避免无信息长文
            Flux<String> responseFlux = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .stream()
                    .content();

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);
            return normalizeStreamOutput(responseFlux)
                .doOnComplete(() -> log.info("流式输出完成: kbIds={}", knowledgeBaseIds))
                .onErrorResume(e -> {
                    log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
                    return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                });

        } catch (Exception e) {
            log.error("知识库流式问答失败: {}", e.getMessage(), e);
            return Flux.just("【错误】知识库查询失败：" + e.getMessage());
        }
    }

    private QueryContext buildQueryContext(String originalQuestion) {
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        String rewrittenQuestion = rewriteQuestion(normalizedQuestion);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams);
    }

//       清洗
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

//    三级分层检索：Level 1 纯向量 / Level 2 混合+RRF / Level 3 混合+RRF+Rerank
    List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds,
                                         Boolean userRerankOverride) {
        // 只取第一个候选 query，不再循环多次尝试（见计划 2.5 节）
        List<String> queries = queryContext.candidateQueries();
        if (queries.isEmpty() || queries.get(0).isBlank()) {
            return List.of();
        }
        String query = queries.get(0);

        List<Document> docs;
        if (hybridConfig.isEnabled()) {
            // ===== Level 2/3：混合检索 + RRF =====
            List<HybridSearchService.HybridHit> hybridHits = hybridSearchService.search(
                query, knowledgeBaseIds,
                hybridConfig.getRecallTopK(),
                hybridConfig.getRecallPerPath(),
                hybridConfig.getRrfK()
            );
            if (hybridHits.isEmpty()) {
                docs = List.of();
            } else {
                List<Document> candidates = fetchDocumentTextsByIds(hybridHits);
                if (shouldRerank(query, candidates.size(), userRerankOverride)) {
                    // Level 3：精排，用 rerankConfig.finalTopN
                    log.info("Level 3 精排: query='{}', 候选={} 条, topN={}",
                        query, candidates.size(), rerankConfig.getFinalTopN());
                    docs = rerankService.rerank(query, candidates, rerankConfig.getFinalTopN());
                } else {
                    // Level 2：RRF 排序结果，用 hybridConfig.finalTopN（不依赖 rerank 配置）
                    log.info("Level 2 混合+RRF: query='{}', 候选={} 条, topN={}",
                        query, candidates.size(), hybridConfig.getFinalTopN());
                    docs = orderByRrf(candidates, hybridHits, hybridConfig.getFinalTopN());
                }
            }
        } else {
            // ===== Level 1：纯向量（兜底），固定条数，废弃动态 topK =====
            log.info("Level 1 纯向量检索: query='{}', topK={}", query, hybridConfig.getFinalTopN());
            docs = vectorService.similaritySearch(
                query, knowledgeBaseIds,
                hybridConfig.getFinalTopN(),
                queryContext.searchParams().minScore()
            );
        }

        log.info("检索 query='{}'，命中 {} 条", query, docs.size());
        return docs;
    }

    // ==================== Reranker 闸门 + 辅助方法 ====================

    /**
     * 场景化三道闸门判断是否走精排
     *
     * <p>闸门优先级：接口级显式开关 > 全局总开关 > 场景自动跳过</p>
     *
     * @param query          用户原始问题
     * @param candidateCount 候选文档数
     * @param userOverride   接口级开关：null=默认策略，true=强制开启，false=强制关闭
     * @return true=走精排，false=跳过精排
     */
    boolean shouldRerank(String query, int candidateCount, Boolean userOverride) {
        // 闸门 3：接口级显式开关优先
        if (userOverride != null) {
            if (!userOverride) {
                log.info("rerank 跳过：接口级强制关闭");
                return false;
            }
            // userOverride=true：跳过闸门 1（全局开关）和闸门 2 的场景判断，
            // 但候选数门槛是硬性条件，必须检查
            if (candidateCount <= rerankConfig.getMinCandidatesForRerank()) {
                log.info("rerank 跳过：强制开启但候选数不足 ({}<={})",
                    candidateCount, rerankConfig.getMinCandidatesForRerank());
                return false;
            }
            return true;
        }
        // 闸门 1：全局总开关
        if (!rerankConfig.isEnabled()) {
            log.info("rerank 跳过：全局开关关闭");
            return false;
        }
        // 闸门 2：场景自动跳过
        if (candidateCount <= rerankConfig.getMinCandidatesForRerank()) {
            log.info("rerank 跳过：候选数不足 ({}<={})",
                candidateCount, rerankConfig.getMinCandidatesForRerank());
            return false;
        }
        if (rerankConfig.isSkipShortQuery() && isShortQuery(query)) {
            log.info("rerank 跳过：短问题 '{}'", query);
            return false;
        }
        if (matchesSkipPattern(query)) {
            log.info("rerank 跳过：命中 skipPattern '{}'", query);
            return false;
        }
        return true;
    }

    /**
     * 按 RRF 分数显式降序排序候选文档
     *
     * <p>⚠️ {@code fetchDocumentTextsByIds} 内部 SQL {@code WHERE id IN (...)} 不保证返回顺序，
     * 本方法必须显式按 {@code hybridHits} 中的 {@code rrfScore} 降序重新映射。</p>
     */
    List<Document> orderByRrf(List<Document> docs, List<HybridSearchService.HybridHit> hits, int topN) {
        if (docs == null || docs.isEmpty() || hits == null || hits.isEmpty()) {
            return List.of();
        }
        // 构建 chunkId → Document 映射
        Map<String, Document> docMap = docs.stream()
            .collect(Collectors.toMap(Document::getId, d -> d, (a, b) -> a));
        // 按 hybridHits 的 rrfScore 降序取 topN
        return hits.stream()
            .sorted(Comparator.comparingDouble(HybridSearchService.HybridHit::rrfScore).reversed())
            .limit(topN)
            .map(h -> docMap.get(h.chunkId()))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * 根据 HybridHit 列表批量查原始 Document（含文本 + metadata）
     *
     * <p>供 Reranker 和 orderByRrf 使用：混合检索只返回 chunkId + rrfScore，
     * 需要通过本方法查出完整文本和 metadata 才能送给 rerank API 和拼接 Prompt。</p>
     */
    List<Document> fetchDocumentTextsByIds(List<HybridSearchService.HybridHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<String> chunkIds = hits.stream()
            .map(HybridSearchService.HybridHit::chunkId)
            .toList();
        return vectorService.findByIds(chunkIds);
    }

    /**
     * 判断是否为短问题（去空格后字符数 ≤ shortQueryThreshold）
     */
    private boolean isShortQuery(String query) {
        if (query == null) {
            return false;
        }
        String compact = query.replaceAll("\\s+", "");
        return compact.length() <= rerankConfig.getShortQueryThreshold();
    }

    /**
     * 判断 query 是否命中 skipPatterns 正则（如纯英文缩写 JVM、GC、OOM）
     */
    private boolean matchesSkipPattern(String query) {
        if (query == null || compiledSkipPatterns == null || compiledSkipPatterns.isEmpty()) {
            return false;
        }
        return compiledSkipPatterns.stream().anyMatch(p -> p.matcher(query).matches());
    }

    private SearchParams resolveSearchParams(String question) {
        int compactLength = question.replaceAll("\\s+", "").length();
        if (compactLength <= shortQueryLength) {
            return new SearchParams(topkShort, minScoreShort);
        }
        if (compactLength <= 12) {
            return new SearchParams(topkMedium, minScoreDefault);
        }
        return new SearchParams(topkLong, minScoreDefault);
    }

//    改写
    private String rewriteQuestion(String question) {
        if (!rewriteEnabled || question.isBlank()) {
            return question;
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            String rewritePrompt = rewritePromptTemplate.render(variables);
            String rewritten = chatClient.prompt()
                .user(rewritePrompt)
                .call()
                .content();
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String normalized = rewritten.trim();
            log.info("Query rewrite: origin='{}', rewritten='{}'", question, normalized);
            return normalized;
        } catch (Exception e) {
            log.warn("Query rewrite 失败，使用原问题继续检索: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 检索命中不等于可回答。
     * 对短 token 场景增加一次命中确认，避免把弱相关片段交给模型后生成大段“信息不足说明”。
     */
    private boolean hasEffectiveHit(String question, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return false;
        }

        String normalized = normalizeQuestion(question);
        if (!isShortTokenQuery(normalized)) {
            return true;
        }

        // 对中文问句提取核心词再做字面匹配，如 "什么是进程" → "进程"
        String coreTerm = extractCoreTerm(normalized).toLowerCase();
        for (Document doc : docs) {
            String text = doc.getText();
            if (text != null && text.toLowerCase().contains(coreTerm)) {
                return true;
            }
        }

        log.info("短 query 命中确认失败，视为无有效结果: question='{}', docs={}", normalized, docs.size());
        return false;
    }

    private boolean isShortTokenQuery(String question) {
        if (question == null) {
            return false;
        }
        String compact = question.trim();
        if (!SHORT_TOKEN_PATTERN.matcher(compact).matches()) {
            return false;
        }
        // 中文无空格分词，"操作系统中进程的定义与基本概念"(15字)也会匹配模式。
        // 对含 CJK 字符的文本额外限制长度：超过6字的视为短语而非短 token，跳过字面确认。
        boolean hasCjk = compact.chars().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF);
        return !hasCjk || compact.length() <= 6;
    }

    /**
     * 从中文问句中提取核心检索词。
     * "什么是进程" → "进程"，"进程是什么" → "进程"，无法识别则原样返回。
     */
    private String extractCoreTerm(String question) {
        Matcher m = ZH_QUESTION_PREFIX.matcher(question);
        if (m.matches()) {
            return m.group(1).trim();
        }
        m = ZH_QUESTION_SUFFIX.matcher(question);
        if (m.matches()) {
            return m.group(1).trim();
        }
        return question;
    }

    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
            || text.contains("未检索到相关信息")
            || text.contains("信息不足")
            || text.contains("超出知识库范围")
            || text.contains("无法根据提供内容回答");
    }

    /**
     * 先观察前一小段流式内容，快速识别“无信息”模板。
     * - 命中无信息：立即输出固定模板并结束，防止长篇拒答
     * - 非无信息：尽快释放缓冲并继续实时透传
     */
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            StringBuilder probeBuffer = new StringBuilder();
            AtomicBoolean passthrough = new AtomicBoolean(false);
            AtomicBoolean completed = new AtomicBoolean(false);
            final Disposable[] disposableRef = new Disposable[1];

            disposableRef[0] = rawFlux.subscribe(
                chunk -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (passthrough.get()) {
                        sink.next(chunk);
                        return;
                    }

                    probeBuffer.append(chunk);
                    String probeText = probeBuffer.toString();
                    if (isNoResultLike(probeText)) {
                        completed.set(true);
                        sink.next(NO_RESULT_RESPONSE);
                        sink.complete();
                        if (disposableRef[0] != null) {
                            disposableRef[0].dispose();
                        }
                        return;
                    }

                    if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                        passthrough.set(true);
                        sink.next(probeText);
                        probeBuffer.setLength(0);
                    }
                },
                sink::error,
                () -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (!passthrough.get()) {
                        sink.next(normalizeAnswer(probeBuffer.toString()));
                    }
                    sink.complete();
                }
            );

            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    record SearchParams(int topK, double minScore) {
    }

    record QueryContext(String originalQuestion, List<String> candidateQueries, SearchParams searchParams) {
    }
}

