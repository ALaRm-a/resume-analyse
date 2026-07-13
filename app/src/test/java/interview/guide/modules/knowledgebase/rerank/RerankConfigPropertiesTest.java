package interview.guide.modules.knowledgebase.rerank;

import interview.guide.modules.knowledgebase.bm25.HybridConfigProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置属性类默认值验证
 *
 * <p>纯单元测试，不需要 Spring 容器。
 * 配置绑定由 Spring Boot 框架保证，这里验证 Java 对象的默认值和字段完整性。</p>
 */
@DisplayName("Reranker 配置属性默认值验证")
class RerankConfigPropertiesTest {

    // ==================== HybridConfigProperties ====================

    @Test
    @DisplayName("HybridConfigProperties 默认值正确")
    void hybridDefaults() {
        HybridConfigProperties props = new HybridConfigProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getRecallPerPath()).isEqualTo(30);
        assertThat(props.getRecallTopK()).isEqualTo(30);
        assertThat(props.getFinalTopN()).isEqualTo(5);
        assertThat(props.getRrfK()).isEqualTo(60);
    }

    @Test
    @DisplayName("HybridConfigProperties setter 可正常修改")
    void hybridSetter() {
        HybridConfigProperties props = new HybridConfigProperties();
        props.setEnabled(false);
        props.setFinalTopN(8);
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getFinalTopN()).isEqualTo(8);
    }

    // ==================== RerankConfigProperties ====================

    @Test
    @DisplayName("RerankConfigProperties 默认值正确")
    void rerankDefaults() {
        RerankConfigProperties props = new RerankConfigProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getModel()).isEqualTo("qwen3-rerank");
        assertThat(props.getEndpoint()).contains("/compatible-api/v1/reranks");
        assertThat(props.getFinalTopN()).isEqualTo(5);
        assertThat(props.getMaxDocumentsPerCall()).isEqualTo(500);
        assertThat(props.getConnectTimeout()).isEqualTo(5000);
        assertThat(props.getReadTimeout()).isEqualTo(30000);
        assertThat(props.getMinCandidatesForRerank()).isEqualTo(8);
        assertThat(props.isSkipShortQuery()).isTrue();
        assertThat(props.getShortQueryThreshold()).isEqualTo(6);
    }

    @Test
    @DisplayName("RerankConfigProperties instruct 默认为问答检索指令")
    void rerankInstructDefault() {
        RerankConfigProperties props = new RerankConfigProperties();
        assertThat(props.getInstruct()).contains("retrieve relevant passages");
    }

    @Test
    @DisplayName("RerankConfigProperties skipPatterns 默认含纯英文缩写正则")
    void rerankSkipPatternsDefault() {
        RerankConfigProperties props = new RerankConfigProperties();
        assertThat(props.getSkipPatterns())
            .as("默认应包含纯英文缩写跳过规则")
            .anyMatch(pattern -> pattern.contains("A-Za-z"));
    }

    @Test
    @DisplayName("RerankConfigProperties endpoint 不应使用旧的 compatible-mode 前缀")
    void rerankEndpointNotCompatibleMode() {
        RerankConfigProperties props = new RerankConfigProperties();
        assertThat(props.getEndpoint())
            .as("rerank 用 compatible-api，不是 Chat/Embedding 的 compatible-mode")
            .doesNotContain("compatible-mode");
    }

    @Test
    @DisplayName("RerankConfigProperties minCandidatesForRerank 应为 finalTopN 的 2 倍")
    void rerankMinCandidatesIsTwiceFinalTopN() {
        RerankConfigProperties props = new RerankConfigProperties();
        assertThat(props.getMinCandidatesForRerank())
            .as("默认 minCandidatesForRerank = 2 × finalTopN")
            .isEqualTo(props.getFinalTopN() * 2);
    }
}
