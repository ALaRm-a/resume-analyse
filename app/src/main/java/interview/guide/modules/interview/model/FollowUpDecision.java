package interview.guide.modules.interview.model;

/**
 * 动态追问决策结果（阶段1 动态追问改造）
 * shouldFollowUp=false 时，其余字段可为 null（降级为不追问）。
 */
public record FollowUpDecision(
    boolean shouldFollowUp,
    String followUpQuestion,
    String weakness,
    String category
) {
    /**
     * 不追问（默认降级）
     */
    public static FollowUpDecision skip() {
        return new FollowUpDecision(false, null, null, null);
    }
}
