package interview.guide.modules.agent.memory.dto;

/**
 * 记忆查询结果 — 由 MemoryService 返回给业务模块。
 *
 * <p>业务模块拿到这个 DTO 后，自行决定如何注入 Prompt。</p>
 */
public record ConversationMemory(
    /** 压缩后的对话摘要（可能为 null） */
    String summary,
    /** 最近 N 条原始消息文本（格式化为 "用户：xxx\n助手：xxx"） */
    String recentHistory
) {
    /** 是否没有任何记忆 */
    public boolean isEmpty() {
        return (summary == null || summary.isBlank())
            && (recentHistory == null || recentHistory.isBlank());
    }

    /** 拼接为可直接注入 Prompt 的文本 */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            sb.append("## 之前的对话摘要\n").append(summary).append("\n\n");
        }
        if (recentHistory != null && !recentHistory.isBlank()) {
            sb.append("## 最近的对话历史\n").append(recentHistory);
        }
        return sb.toString();
    }
}
