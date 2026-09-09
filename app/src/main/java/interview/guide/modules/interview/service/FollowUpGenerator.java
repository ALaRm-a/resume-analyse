package interview.guide.modules.interview.service;

import interview.guide.modules.interview.model.FollowUpDecision;
import interview.guide.modules.interview.model.InterviewQuestionDTO;

/**
 * 动态追问生成器接口（阶段1 动态追问改造）
 * 根据主问题 + 候选人回答 + 简历，决定是否追问并生成追问问题。
 * 任何实现都必须遵循兜底总原则：失败即降级为"不追问"，绝不阻塞主答题流。
 */
public interface FollowUpGenerator {

    /**
     * 决策并生成追问
     *
     * @param question   已作答的主问题
     * @param answer     候选人回答
     * @param resumeText 简历全文
     * @param sessionId  会话ID（日志/上下文用）
     * @return 追问决策结果；shouldFollowUp=false 表示不追问
     */
    FollowUpDecision decide(InterviewQuestionDTO question, String answer,
                            String resumeText, String sessionId);
}
