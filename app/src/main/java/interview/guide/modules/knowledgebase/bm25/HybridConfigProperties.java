package interview.guide.modules.knowledgebase.bm25;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 混合检索（BM25 + 向量 + RRF 融合）配置属性
 *
 * <p>与 {@link interview.guide.modules.knowledgebase.rerank.RerankConfigProperties} 解耦：
 * Level 1（纯向量）和 Level 2（混合+RRF 不精排）路径只依赖此配置，
 * 不读取 rerank 的任何字段。</p>
 *
 * <p>配置前缀：{@code app.ai.rag.hybrid}</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag.hybrid")
public class HybridConfigProperties {

    /** 混合检索总开关（Level 2 总闸），false 则走 Level 1 纯向量检索 */
    private boolean enabled = true;

    /** 每路召回量（BM25 和向量各召回这么多条） */
    private int recallPerPath = 30;

    /** RRF 融合后截取的候选数 */
    private int recallTopK = 30;

    /** 非精排路径（Level 1/2）的最终输出条数，独立于 rerank.finalTopN */
    private int finalTopN = 5;

    /** RRF 公式中的 k 常数（论文推荐值 60） */
    private int rrfK = 60;
}
