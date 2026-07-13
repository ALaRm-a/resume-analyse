package interview.guide.modules.knowledgebase.rerank;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Reranker 精排配置属性
 *
 * <p>与 {@link interview.guide.modules.knowledgebase.bm25.HybridConfigProperties} 解耦：
 * Level 3（混合+RRF+Rerank）路径依赖此配置。
 * 场景化三道闸门的相关参数也在此配置。</p>
 *
 * <p>配置前缀：{@code app.ai.rag.rerank}</p>
 *
 * <h3>三道闸门</h3>
 * <ol>
 *   <li>闸门1：{@link #enabled} 全局总开关</li>
 *   <li>闸门2：场景自动跳过（{@link #minCandidatesForRerank} / {@link #skipShortQuery} / {@link #skipPatterns}）</li>
 *   <li>闸门3：接口级显式开关（Controller 传入的 Boolean rerank 参数）</li>
 * </ol>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag.rerank")
public class RerankConfigProperties {

    /** 精排功能总开关（Level 3 总闸），依赖 hybrid.enabled=true */
    private boolean enabled = true;

    /** 模型名（gte-rerank 系列已下线，使用 qwen3-rerank） */
    private String model = "qwen3-rerank";

    /**
     * API 端点（OpenAI 兼容模式）
     * <p>注意：与 Chat/Embedding 的 {@code compatible-mode} 前缀不同，
     * rerank 用的是 {@code compatible-api}</p>
     */
    private String endpoint = "https://dashscope.aliyuncs.com/compatible-api/v1/reranks";

    /** 排序任务指令，默认问答检索；FAQ 场景可改为 "Retrieve semantically similar text." */
    private String instruct = "Given a web search query, retrieve relevant passages that answer the query.";

    /** 精排后最终输出条数（Level 3 专用，Level 1/2 用 hybrid.finalTopN） */
    private int finalTopN = 5;

    /** 单次 rerank 调用最大文档数（qwen3-rerank 上限 500） */
    private int maxDocumentsPerCall = 500;

    /** HTTP 连接超时 ms */
    private int connectTimeout = 5000;

    /** HTTP 读取超时 ms，超时直接降级到 Level 2，不重试 */
    private int readTimeout = 30000;

    /** 候选数 <= 此值时跳过 rerank（rerank 只重排不增量，候选不足无意义），默认 2×finalTopN */
    private int minCandidatesForRerank = 8;

    /** 短 query 自动跳过 rerank（省 100~300ms 延迟） */
    private boolean skipShortQuery = true;

    /** 短 query 字符数阈值（去空格后 <= 此值视为短问题） */
    private int shortQueryThreshold = 6;

    /** query 命中以下正则时跳过 rerank（如纯英文缩写 JVM、GC、OOM） */
    private List<String> skipPatterns = new ArrayList<>(List.of("^[A-Za-z0-9]{1,10}$"));
}
