package interview.guide.modules.agent.memory.dto;

/**
 * 供压缩服务使用的最小化消息 DTO。
 *
 * <p>只保留 messageOrder 和格式化后的文本，避免依赖业务模块的 Entity。</p>
 */
public record CompressibleMessage(
    int order,
    String text
) {
}
