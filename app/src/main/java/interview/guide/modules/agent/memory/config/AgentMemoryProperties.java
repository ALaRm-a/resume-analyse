package interview.guide.modules.agent.memory.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 记忆模块配置属性。
 *
 * <p>配置前缀：{@code agent.memory}</p>
 */
@Slf4j
@Data
@ConfigurationProperties(prefix = "agent.memory")
public class AgentMemoryProperties {

    /** 保留最近 N 条原始消息 */
    private int windowSize = 50;

    /** 每次压缩时合并的新消息数量上限 */
    private int compressBatch = 30;

    /** 消息总数超过此阈值才触发压缩 */
    private int triggerThreshold = 60;

    /**
     * 参数约束校验：防止误配置导致压缩行为异常。
     */
    @PostConstruct
    public void validate() {
        if (compressBatch > windowSize) {
            throw new IllegalStateException(
                "compressBatch(" + compressBatch + ") 不能大于 windowSize(" + windowSize + ")");
        }
        if (triggerThreshold <= windowSize) {
            throw new IllegalStateException(
                "triggerThreshold(" + triggerThreshold + ") 必须大于 windowSize(" + windowSize + ")");
        }
        if (triggerThreshold > windowSize + compressBatch) {
            log.warn("triggerThreshold({}) 大于 windowSize({}) + compressBatch({})，" +
                    "缓冲区可能过大，建议检查配置",
                    triggerThreshold, windowSize, compressBatch);
        }
        int firstCompressCount = triggerThreshold - windowSize;
        if (firstCompressCount < compressBatch / 3) {
            log.warn("triggerThreshold({}) - windowSize({}) = {}，首次压缩仅 {} 条消息，" +
                    "LLM 调用性价比低，建议 triggerThreshold >= windowSize + compressBatch/3 = {}",
                    triggerThreshold, windowSize, firstCompressCount,
                    firstCompressCount, windowSize + compressBatch / 3);
        }
    }
}
