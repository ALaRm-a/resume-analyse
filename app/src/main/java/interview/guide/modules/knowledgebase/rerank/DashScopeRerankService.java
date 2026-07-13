package interview.guide.modules.knowledgebase.rerank;

import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * 阿里云 DashScope qwen3-rerank 精排服务
 *
 * <p>封装 qwen3-rerank API 调用（OpenAI 兼容端点 {@code /compatible-api/v1/reranks}），
 * 输入 query + 候选文档列表，输出按 relevance_score 降序排列的文档列表。</p>
 *
 * <h3>API 格式</h3>
 * <pre>
 * 请求体（扁平结构，无 input/parameters 嵌套）：
 * { "model": "qwen3-rerank", "query": "...", "documents": [...], "top_n": 5,
 *   "return_documents": false, "instruct": "..." }
 *
 * 响应体（results 在顶层，无 output 包裹）：
 * { "object": "list", "results": [{"index": 0, "relevance_score": 0.98}],
 *   "model": "qwen3-rerank", "id": "...", "usage": {"total_tokens": 79} }
 * </pre>
 *
 * <h3>降级策略</h3>
 * <p>API 调用失败/超时<b>不重试</b>，直接返回候选列表的前 topN 条（按原始顺序），
 * 保证主流程可用。rerank 是锦上添花，不值得为它叠加重试延迟。</p>
 *
 * <h3>注意</h3>
 * <p>endpoint 前缀是 {@code compatible-api}，与 Chat/Embedding 的 {@code compatible-mode} 不同。
 * API key 复用 Spring AI 的 {@code spring.ai.openai.api-key}（同一个 DashScope key）。</p>
 */
@Slf4j
@Service
public class DashScopeRerankService {

    private final RerankConfigProperties config;
    private final RestClient restClient;

    public DashScopeRerankService(
            RerankConfigProperties config,
            @Value("${spring.ai.openai.api-key}") String apiKey) {
        this.config = config;

        // JDK 21 内置 HttpClient，配置连接超时
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getConnectTimeout()))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(config.getReadTimeout()));

        this.restClient = RestClient.builder()
                .baseUrl(config.getEndpoint())
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("DashScopeRerankService 初始化: model={}, endpoint={}",
                config.getModel(), config.getEndpoint());
    }

    /**
     * 对候选文档列表进行精排
     *
     * @param query      用户原始问题
     * @param candidates 候选文档列表（RRF 融合后的候选）
     * @param topN       最终返回条数
     * @return 按 qwen3-rerank relevance_score 降序排列的文档列表；
     *         API 失败时返回候选前 topN 条作为降级（保留原始 metadata）
     */
    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            log.info("Rerank 候选为空，跳过: query='{}'", query);
            return List.of();
        }

        // 截断超过上限的候选
        List<Document> effectiveCandidates = candidates.size() > config.getMaxDocumentsPerCall()
                ? candidates.subList(0, config.getMaxDocumentsPerCall())
                : candidates;

        // 构建请求体（扁平结构）
        RerankRequest request = new RerankRequest(
                config.getModel(),
                query,
                effectiveCandidates.stream().map(Document::getText).collect(Collectors.toList()),
                topN,
                false,
                config.getInstruct()
        );

        try {
            log.info("调用 qwen3-rerank: query='{}', candidates={}, topN={}",
                    query, effectiveCandidates.size(), topN);

            RerankResponse response = restClient.post()
                    .body(request)
                    .retrieve()
                    .body(RerankResponse.class);

            if (response == null || response.results() == null || response.results().isEmpty()) {
                log.warn("Rerank 返回空结果，降级为候选前 {} 条", topN);
                return effectiveCandidates.stream().limit(topN).toList();
            }

            // 按 index 映射回原始 Document，保持 relevance_score 降序（API 已排序）
            List<Document> ranked = response.results().stream()
                    .map(r -> {
                        int idx = r.index();
                        return (idx >= 0 && idx < effectiveCandidates.size())
                                ? effectiveCandidates.get(idx)
                                : null;
                    })
                    .filter(Objects::nonNull)
                    .toList();

            log.info("Rerank 完成: 输入 {} 条, 输出 {} 条, usage={}",
                    effectiveCandidates.size(), ranked.size(),
                    response.usage() != null ? response.usage().get("total_tokens") : "N/A");
            return ranked;

        } catch (Exception e) {
            log.warn("Rerank API 调用失败，降级为候选前 {} 条: {}", topN, e.getMessage());
            return effectiveCandidates.stream().limit(topN).toList();
        }
    }

    // ==================== API 请求/响应 DTO ====================

    /**
     * qwen3-rerank 请求体（扁平结构，无 input/parameters 嵌套）
     *
     * @param model           模型名
     * @param query           查询文本
     * @param documents       候选文档文本列表
     * @param topN            返回前 N 条
     * @param returnDocuments 是否返回文档原文（false 省带宽）
     * @param instruct        排序任务指令
     */
    private record RerankRequest(
            String model,
            String query,
            List<String> documents,
            @JsonProperty("top_n") int topN,
            @JsonProperty("return_documents") boolean returnDocuments,
            String instruct
    ) {}

    /**
     * qwen3-rerank 响应体（results 在顶层，无 output 包裹）
     */
    private record RerankResponse(
            String object,
            List<RerankResult> results,
            String model,
            String id,
            Map<String, Object> usage
    ) {}

    /**
     * 单条重排结果
     *
     * @param index           原文档在 documents 数组中的下标
     * @param relevanceScore  相关性得分（0~1，越大越相关，API 已按降序排列）
     */
    private record RerankResult(
            int index,
            @JsonProperty("relevance_score") double relevanceScore
    ) {}
}
