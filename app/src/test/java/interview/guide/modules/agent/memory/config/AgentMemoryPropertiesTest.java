package interview.guide.modules.agent.memory.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentMemoryProperties} 配置校验测试。
 */
class AgentMemoryPropertiesTest {

    @Test
    @DisplayName("默认配置应当合法")
    void defaults_shouldBeValid() {
        AgentMemoryProperties props = new AgentMemoryProperties();

        assertThat(props.getWindowSize()).isEqualTo(50);
        assertThat(props.getCompressBatch()).isEqualTo(30);
        assertThat(props.getTriggerThreshold()).isEqualTo(60);

        // 默认配置不抛异常
        props.validate();
    }

    @Test
    @DisplayName("compressBatch 大于 windowSize 应抛异常")
    void compressBatchGreaterThanWindowSize_shouldThrow() {
        AgentMemoryProperties props = new AgentMemoryProperties();
        props.setWindowSize(20);
        props.setCompressBatch(30);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("compressBatch");
    }

    @Test
    @DisplayName("triggerThreshold 小于等于 windowSize 应抛异常")
    void triggerThresholdNotGreaterThanWindowSize_shouldThrow() {
        AgentMemoryProperties props = new AgentMemoryProperties();
        props.setWindowSize(50);
        props.setTriggerThreshold(50);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("triggerThreshold");
    }

    @Test
    @DisplayName("triggerThreshold 等于 windowSize + 1 是合法的最小值")
    void triggerThresholdEqualsWindowSizePlusOne_shouldBeValid() {
        AgentMemoryProperties props = new AgentMemoryProperties();
        props.setWindowSize(50);
        props.setTriggerThreshold(51);
        props.setCompressBatch(30);

        // 首次压缩量 = 1，会触发 warn，但不会抛异常
        props.validate();
    }
}
