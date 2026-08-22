package interview.guide.modules.interview.model;

/**
 * 提交答案响应
 * answeredCount：已作答题数（展示用，阶段0 起 questionIndex 是唯一 ID 而非序号，
 * 前端进度条依赖该字段而非 questionIndex 数值）
 */
public record SubmitAnswerResponse(
    boolean hasNextQuestion,
    InterviewQuestionDTO nextQuestion,
    int currentIndex,
    int totalQuestions,
    int answeredCount
) {}
