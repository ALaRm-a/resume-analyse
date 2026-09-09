package interview.guide.modules.interview.service;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.FollowUpDecision;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 显式两步追问生成器（阶段1 动态追问改造）
 *
 * 流程：
 * ① 第一次 LLM 调用：结构化输出 {shouldFollowUp, weakness}（判断是否追问 + 指出回答弱点）
 * ② 仅当应追问时第二次调用：{question, answer, weakness, 简历} → {followUpQuestion, category}
 *
 * 简历采用全量塞 prompt（阶段1 重心调整：简历 RAG 检索降级为可选增强），超长时截断。
 * 任何一步异常 → FollowUpDecision.skip()（不追问，绝不阻塞答题）。
 */
@Slf4j
@Component
public class ExplicitFollowUpGenerator implements FollowUpGenerator {

    /** 简历文本最大长度（超长截断，防止 token 溢出） */
    private static final int MAX_RESUME_CHARS = 8000;

    private final ChatClient chatClient;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final PromptTemplate decisionSystemTemplate;
    private final PromptTemplate decisionUserTemplate;
    private final PromptTemplate questionSystemTemplate;
    private final PromptTemplate questionUserTemplate;
    private final BeanOutputConverter<DecisionDTO> decisionConverter;
    private final BeanOutputConverter<QuestionDTO> questionConverter;

    // 中间DTO用于接收AI响应（包内可见，便于测试构造）
    record DecisionDTO(boolean shouldFollowUp, String weakness) {}
    record QuestionDTO(String question, String category) {}

    public ExplicitFollowUpGenerator(
            ChatClient.Builder chatClientBuilder,
            StructuredOutputInvoker structuredOutputInvoker,
            @Value("classpath:prompts/interview-followup-decision.st") Resource decisionSystemResource,
            @Value("classpath:prompts/interview-followup-decision-user.st") Resource decisionUserResource,
            @Value("classpath:prompts/interview-followup-question.st") Resource questionSystemResource,
            @Value("classpath:prompts/interview-followup-question-user.st") Resource questionUserResource) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.decisionSystemTemplate = new PromptTemplate(decisionSystemResource.getContentAsString(StandardCharsets.UTF_8));
        this.decisionUserTemplate = new PromptTemplate(decisionUserResource.getContentAsString(StandardCharsets.UTF_8));
        this.questionSystemTemplate = new PromptTemplate(questionSystemResource.getContentAsString(StandardCharsets.UTF_8));
        this.questionUserTemplate = new PromptTemplate(questionUserResource.getContentAsString(StandardCharsets.UTF_8));
        this.decisionConverter = new BeanOutputConverter<>(DecisionDTO.class);
        this.questionConverter = new BeanOutputConverter<>(QuestionDTO.class);
    }

    @Override
    public FollowUpDecision decide(InterviewQuestionDTO question, String answer,
                                   String resumeText, String sessionId) {
        try {
            String resume = truncateResume(resumeText);

            // 第一步：决策是否追问
            Map<String, Object> decisionVars = new HashMap<>(Map.of(
                "question", safe(question.question()),
                "category", safe(question.category()),
                "answer", safe(answer),
                "resume", safe(resume)
            ));
            DecisionDTO decision = structuredOutputInvoker.invoke(
                chatClient,
                decisionSystemTemplate.render() + "\n\n" + decisionConverter.getFormat(),
                decisionUserTemplate.render(decisionVars),
                decisionConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "追问决策失败：",
                "动态追问决策",
                log
            );
            if (!decision.shouldFollowUp()) {
                return FollowUpDecision.skip();
            }

            // 第二步：生成追问问题
            decisionVars.put("weakness", safe(decision.weakness()));
            QuestionDTO generated = structuredOutputInvoker.invoke(
                chatClient,
                questionSystemTemplate.render() + "\n\n" + questionConverter.getFormat(),
                questionUserTemplate.render(decisionVars),
                questionConverter,
                ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                "追问生成失败：",
                "动态追问生成",
                log
            );
            if (generated.question() == null || generated.question().isBlank()) {
                return FollowUpDecision.skip();
            }
            return new FollowUpDecision(true, generated.question(), decision.weakness(), generated.category());
        } catch (Exception e) {
            // 兜底总原则：任何失败都降级为不追问
            log.warn("动态追问决策失败，降级为不追问: sessionId={}, error={}", sessionId, e.getMessage());
            return FollowUpDecision.skip();
        }
    }

    private String truncateResume(String resumeText) {
        if (resumeText == null) {
            return "";
        }
        return resumeText.length() <= MAX_RESUME_CHARS
            ? resumeText
            : resumeText.substring(0, MAX_RESUME_CHARS);
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}
