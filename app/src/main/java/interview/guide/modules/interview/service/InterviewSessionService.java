package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.*;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 面试会话管理服务
 * 管理面试会话的生命周期，使用 Redis 缓存会话状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionService {

    private final InterviewQuestionService questionService;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSessionCache sessionCache;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;
    private final EvaluateStreamProducer evaluateStreamProducer;
    private final FollowUpGenerator followUpGenerator;

    /** 每个主问题最多允许的追问数（阶段1 动态追问硬上限常量） */
    private static final int MAX_FOLLOW_UP_PER_QUESTION = 1;

    /** 会话级分布式锁前缀（阶段2 并发一致性：同一会话的提交/暂存串行化） */
    private static final String SESSION_LOCK_KEY_PREFIX = "interview:lock:";
    /** 会话锁获取等待时间（毫秒） */
    private static final long LOCK_WAIT_MS = 3000;
    /** 会话锁持有时间（毫秒）：锁内仅做毫秒级读改写，10s 足够兜底异常场景 */
    private static final long LOCK_LEASE_MS = 10000;

    /**
     * 提交快照（阶段2）：锁1（快读）捕获的上下文，供锁外 LLM 决策与锁2（写回）校验使用。
     */
    private record SubmitSnapshot(
        int version,
        boolean canFollowUp,
        InterviewQuestionDTO question,
        String resumeText
    ) {}

    /**
     * 创建新的面试会话
     * 注意：如果已有未完成的会话，不会创建新的，而是返回现有会话
     * 前端应该先调用 findUnfinishedSession 检查，或者使用 forceCreate 参数强制创建
     */
    public InterviewSessionDTO createSession(CreateInterviewRequest request) {
        // 如果指定了resumeId且未强制创建，检查是否有未完成的会话
        if (request.resumeId() != null && !Boolean.TRUE.equals(request.forceCreate())) {
            Optional<InterviewSessionDTO> unfinishedOpt = findUnfinishedSession(request.resumeId());
            if (unfinishedOpt.isPresent()) {
                log.info("检测到未完成的面试会话，返回现有会话: resumeId={}, sessionId={}",
                    request.resumeId(), unfinishedOpt.get().sessionId());
                return unfinishedOpt.get();
            }
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        log.info("创建新面试会话: {}, 题目数量: {}, resumeId: {}",
            sessionId, request.questionCount(), request.resumeId());

        // 获取历史问题
        List<String> historicalQuestions = null;
        if (request.resumeId() != null) {
            historicalQuestions = persistenceService.getHistoricalQuestionsByResumeId(request.resumeId());
        }

        // 生成面试问题
        List<InterviewQuestionDTO> questions = questionService.generateQuestions(
            request.resumeText(),
            request.questionCount(),
            historicalQuestions
        );

        // 动态追问硬上限：用户选择的主问题数 × 2（每主问题最多 MAX_FOLLOW_UP_PER_QUESTION 个追问，阶段1）
        // 基于用户输入 questionCount 而非实际生成数：LLM/兜底生成可能少于用户选择题数
        int maxTotalQuestions = request.questionCount() * (MAX_FOLLOW_UP_PER_QUESTION + 1);

        // 保存到 Redis 缓存
        sessionCache.saveSession(
            sessionId,
            request.resumeText(),
            request.resumeId(),
            questions,
            0,
            SessionStatus.CREATED,
            maxTotalQuestions
        );

        // 保存到数据库
        if (request.resumeId() != null) {
            try {
                persistenceService.saveSession(sessionId, request.resumeId(),
                    questions.size(), maxTotalQuestions, questions);
            } catch (Exception e) {
                log.warn("保存面试会话到数据库失败: {}", e.getMessage());
            }
        }

        return new InterviewSessionDTO(
            sessionId,
            request.resumeText(),
            questions.size(),
            0,
            questions,
            SessionStatus.CREATED
        );
    }

    /**
     * 获取会话信息（优先从缓存获取，缓存未命中则从数据库恢复）
     */
    public InterviewSessionDTO getSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            // 刷新 TTL，实现滑动窗口过期
            sessionCache.refreshSessionTTL(sessionId);
            return toDTO(cachedOpt.get());
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return toDTO(restoredSession);
    }

    /**
     * 查找并恢复未完成的面试会话
     */
    public Optional<InterviewSessionDTO> findUnfinishedSession(Long resumeId) {
        try {
            // 1. 先从 Redis 缓存查找
            Optional<String> cachedSessionIdOpt = sessionCache.findUnfinishedSessionId(resumeId);
            if (cachedSessionIdOpt.isPresent()) {
                String sessionId = cachedSessionIdOpt.get();
                Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
                if (cachedOpt.isPresent()) {
                    log.debug("从 Redis 缓存找到未完成会话: resumeId={}, sessionId={}", resumeId, sessionId);
                    // 刷新 TTL
                    sessionCache.refreshSessionTTL(sessionId);
                    return Optional.of(toDTO(cachedOpt.get()));
                }
            }

            // 2. 缓存未命中，从数据库查找
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findUnfinishedSession(resumeId);
            if (entityOpt.isEmpty()) {
                return Optional.empty();
            }

            InterviewSessionEntity entity = entityOpt.get();
            CachedSession restoredSession = restoreSessionFromEntity(entity);
            if (restoredSession != null) {
                return Optional.of(toDTO(restoredSession));
            }
        } catch (Exception e) {
            log.error("恢复未完成会话失败: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * 查找并恢复未完成的面试会话，如果不存在则抛出异常
     */
    public InterviewSessionDTO findUnfinishedSessionOrThrow(Long resumeId) {
        return findUnfinishedSession(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "未找到未完成的面试会话"));
    }

    /**
     * 从数据库恢复会话并缓存到 Redis
     */
    private CachedSession restoreSessionFromDatabase(String sessionId) {
        try {
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
            return entityOpt.map(this::restoreSessionFromEntity).orElse(null);
        } catch (Exception e) {
            log.error("从数据库恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从实体恢复会话并缓存到 Redis
     */
    private CachedSession restoreSessionFromEntity(InterviewSessionEntity entity) {
        try {
            // 解析问题列表
            List<InterviewQuestionDTO> questions = objectMapper.readValue(
                entity.getQuestionsJson(),
                new TypeReference<>() {}
            );

            // 恢复已保存的答案（按问题 ID 回填，而非列表下标；插入追问后 ID ≠ 下标）
            List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(entity.getSessionId());
            persistenceService.mergeAnswersIntoQuestions(questions, answers);

            SessionStatus status = convertStatus(entity.getStatus());

            // 恢复硬上限；存量数据（maxTotalQuestions 为 null）兜底为当前题目数（不再插追问）
            int maxTotalQuestions = entity.getMaxTotalQuestions() != null
                ? entity.getMaxTotalQuestions()
                : questions.size();

            // 保存到 Redis 缓存
            sessionCache.saveSession(
                entity.getSessionId(),
                entity.getResume().getResumeText(),
                entity.getResume().getId(),
                questions,
                entity.getCurrentQuestionIndex(),
                status,
                maxTotalQuestions
            );

            log.info("从数据库恢复会话到 Redis: sessionId={}, currentIndex={}, status={}",
                entity.getSessionId(), entity.getCurrentQuestionIndex(), entity.getStatus());

            // 返回缓存的会话
            return sessionCache.getSession(entity.getSessionId()).orElse(null);
        } catch (Exception e) {
            log.error("恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private SessionStatus convertStatus(InterviewSessionEntity.SessionStatus status) {
        return switch (status) {
            case CREATED -> SessionStatus.CREATED;
            case IN_PROGRESS -> SessionStatus.IN_PROGRESS;
            case COMPLETED -> SessionStatus.COMPLETED;
            case EVALUATED -> SessionStatus.EVALUATED;
        };
    }

    /**
     * 获取当前问题的响应（包含完成状态）
     */
    public Map<String, Object> getCurrentQuestionResponse(String sessionId) {
        InterviewQuestionDTO question = getCurrentQuestion(sessionId);
        if (question == null) {
            return Map.of(
                "completed", true,
                "message", "所有问题已回答完毕"
            );
        }
        return Map.of(
            "completed", false,
            "question", question
        );
    }

    /**
     * 获取当前问题
     */
    public InterviewQuestionDTO getCurrentQuestion(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        if (session.getCurrentIndex() >= questions.size()) {
            return null; // 所有问题已回答完
        }

        // 更新状态为进行中
        if (session.getStatus() == SessionStatus.CREATED) {
            session.setStatus(SessionStatus.IN_PROGRESS);
            sessionCache.updateSessionStatus(sessionId, SessionStatus.IN_PROGRESS);

            // 同步到数据库
            try {
                persistenceService.updateSessionStatus(sessionId,
                    InterviewSessionEntity.SessionStatus.IN_PROGRESS);
            } catch (Exception e) {
                log.warn("更新会话状态失败: {}", e.getMessage());
            }
        }

        return questions.get(session.getCurrentIndex());
    }

    /**
     * 提交答案（并进入下一题）
     * 如果是最后一题，自动触发异步评估
     *
     * 阶段0（动态追问改造）：request.questionIndex() 是唯一 ID 而非列表下标，
     * 必须先通过 ID 定位列表位置 pos，再执行依赖位置的操作（更新答案、游标推进）。
     * 答案落库锚定 ID，游标推进用位置。
     *
     * 阶段2（并发一致性）：两段锁——锁内快读（毫秒级）+ 锁外 LLM 决策（3~10s 不持锁）
     * + 再进锁校验版本号后写回。LLM 决策期间他人提交使版本号变化时，放弃本次追问插入
     * （不产生重复题），答案幂等落库。
     */
    public SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request) {
        String sessionId = request.sessionId();
        int questionId = request.questionIndex();
        String lockKey = sessionLockKey(sessionId);

        // ① 锁内快读：定位问题、判断是否可能追问、记录版本快照（毫秒级，不阻塞他人提交）
        SubmitSnapshot snapshot = redisService.executeWithLock(
            lockKey, LOCK_WAIT_MS, LOCK_LEASE_MS, TimeUnit.MILLISECONDS,
            () -> {
                CachedSession session = getOrRestoreSession(sessionId);
                List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);
                int pos = indexOfQuestionById(questions, questionId);
                if (pos < 0) {
                    throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题ID: " + questionId);
                }
                InterviewQuestionDTO question = questions.get(pos);
                int maxTotal = maxTotalQuestions(session, questions);
                boolean canFollowUp = !question.isFollowUp()
                    && questions.size() < maxTotal
                    && !hasFollowUpFor(questions, questionId);
                return new SubmitSnapshot(
                    session.getVersion(), canFollowUp, question, session.getResumeText());
            });

        // ② 锁外 LLM 决策（3~10s 不持锁；生成器内部异常 + 服务层双重降级为不追问）
        FollowUpDecision decision = snapshot.canFollowUp()
            ? decideFollowUpSafely(snapshot, sessionId, request.answer())
            : FollowUpDecision.skip();
        boolean shouldInsert = decision.shouldFollowUp();

        // ③ 再进锁：基于最新列表校验版本后提交（写答案/插追问/推进游标/写回/落库）
        return redisService.executeWithLock(
            lockKey, LOCK_WAIT_MS, LOCK_LEASE_MS, TimeUnit.MILLISECONDS,
            () -> {
                CachedSession session = sessionCache.getSession(sessionId)
                    .orElseGet(() -> getOrRestoreSession(sessionId));
                List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);
                int pos = indexOfQuestionById(questions, questionId);
                if (pos < 0) {
                    throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题ID: " + questionId);
                }

                // 并发下他人已提交该题答案（如双击）：幂等跳过写入，返回当前最新状态
                boolean versionChanged = session.getVersion() != snapshot.version();
                boolean alreadyAnswered = questions.get(pos).userAnswer() != null;
                if (versionChanged && alreadyAnswered) {
                    log.info("会话 {} 问题ID{} 已在并发提交中被作答，本次幂等跳过", sessionId, questionId);
                    return buildResponse(session, questions);
                }

                // 更新问题答案（按最新列表中的位置写回）
                InterviewQuestionDTO question = questions.get(pos);
                questions.set(pos, question.withAnswer(request.answer()));

                // 游标默认推进到列表下一位置
                int newIndex = pos + 1;
                int maxTotal = maxTotalQuestions(session, questions);

                // 追问插入主问题位置 + 1；newIndex 保持 pos+1 不变，
                // 因为追问恰好占住该位置，游标天然指向追问（答完追问后游标再 +1 跳到下一主问题）
                if (shouldInsert && !versionChanged && questions.size() < maxTotal) {
                    int maxId = maxQuestionId(questions);
                    InterviewQuestionDTO followUp = InterviewQuestionDTO.buildFollowUp(
                        maxId,
                        decision.followUpQuestion(),
                        question.type(),
                        decision.category() != null && !decision.category().isBlank()
                            ? decision.category() : question.category(),
                        questionId);
                    questions.add(pos + 1, followUp);
                    log.info("会话 {} 为主问题ID{} 生成追问ID{}，已插入位置{}",
                        sessionId, questionId, followUp.questionIndex(), pos + 1);
                } else if (shouldInsert) {
                    // 决策判定要追问，但写回前被拦下：显式记录原因，避免"追问被静默吞掉"后无从排查
                    log.warn("会话 {} 放弃插入追问（主问题ID{}）：版本已变更={}，已达上限={}",
                        sessionId, questionId, versionChanged, questions.size() >= maxTotal);
                }

                // 检查是否全部完成
                boolean hasNextQuestion = newIndex < questions.size();
                SessionStatus newStatus = hasNextQuestion ? SessionStatus.IN_PROGRESS : SessionStatus.COMPLETED;

                // ① 先落库（数据库是持久源）：答案 + 问题列表快照 + 游标 + 状态，一次事务原子提交。
                //    这里不再 catch 吞异常：落库失败必须让本次提交失败、缓存保持旧状态，
                //    否则会出现"缓存里有追问、库里没有"的不一致（Redis 一过期追问就永久丢失）。
                //    错误场景区分：业务异常（会话不存在 3001 / 序列化失败）原样穿透保留原错误码；
                //    其余（DB 连接失败/超时/约束冲突）按"提交答案"动作包装为 3008，
                //    与全局兜底的"系统繁忙"区分开，前端可据此提示用户重试。
                try {
                    persistenceService.saveAnswerAndUpdateSession(
                        sessionId, questionId,
                        question.question(), question.category(),
                        request.answer(), newIndex, questions,
                        newStatus == SessionStatus.COMPLETED
                            ? InterviewSessionEntity.SessionStatus.COMPLETED
                            : InterviewSessionEntity.SessionStatus.IN_PROGRESS);
                } catch (BusinessException e) {
                    throw e;
                } catch (Exception e) {
                    throw persistenceFailure("提交答案", sessionId, questionId, e);
                }

                // ② 评估只依赖库里的数据，落库成功后即可投递
                if (!hasNextQuestion) {
                    persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
                    evaluateStreamProducer.sendEvaluateTask(sessionId);
                    log.info("会话 {} 已完成所有问题，评估任务已入队", sessionId);
                }

                // ③ 最后更新缓存（Redis 是加速层，放在持久化成功之后；版本号 +1）
                sessionCache.applySessionState(session, questions, newIndex, newStatus);
                sessionCache.writeBack(sessionId, session);

                log.info("会话 {} 提交答案: 问题ID{}, 剩余{}题",
                    sessionId, questionId, questions.size() - newIndex);

                return new SubmitAnswerResponse(
                    hasNextQuestion,
                    questions.size() > newIndex ? questions.get(newIndex) : null,
                    newIndex,
                    questions.size(),
                    newIndex
                );
            });
    }

    /**
     * 暂存答案（不进入下一题）
     * 阶段0（动态追问改造）：与 submitAnswer 一致，按 ID 定位列表位置，落库锚定 ID。
     * 阶段2（并发一致性）：暂存同样走会话锁 + 锁内一次写回（版本号 +1）。
     */
    public void saveAnswer(SubmitAnswerRequest request) {
        String sessionId = request.sessionId();
        int questionId = request.questionIndex();
        String lockKey = sessionLockKey(sessionId);

        redisService.executeWithLock(lockKey, LOCK_WAIT_MS, LOCK_LEASE_MS, TimeUnit.MILLISECONDS, () -> {
            CachedSession session = getOrRestoreSession(sessionId);
            List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

            int pos = indexOfQuestionById(questions, questionId);
            if (pos < 0) {
                throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题ID: " + questionId);
            }

            // 更新问题答案（按位置写回）
            InterviewQuestionDTO question = questions.get(pos);
            questions.set(pos, question.withAnswer(request.answer()));

            // 更新状态为进行中
            SessionStatus newStatus = session.getStatus() == SessionStatus.CREATED
                ? SessionStatus.IN_PROGRESS : session.getStatus();

            // ① 先落库（不更新 currentIndex，落库锚定问题 ID）
            //    错误场景区分同 submitAnswer：业务异常原样穿透，其余按"暂存答案"动作包装为 3008
            try {
                persistenceService.saveAnswer(
                    sessionId, questionId,
                    question.question(), question.category(),
                    request.answer(), 0, null
                );
                persistenceService.updateSessionStatus(sessionId,
                    InterviewSessionEntity.SessionStatus.IN_PROGRESS);
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw persistenceFailure("暂存答案", sessionId, questionId, e);
            }

            // ② 落库成功后再更新缓存（Redis 是加速层；版本号 +1）
            sessionCache.applySessionState(session, questions, session.getCurrentIndex(), newStatus);
            sessionCache.writeBack(sessionId, session);

            log.info("会话 {} 暂存答案: 问题ID{}", sessionId, questionId);
            return null;
        });
    }

    /**
     * 提前交卷（触发异步评估）
     */
    public void completeInterview(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        if (session.getStatus() == SessionStatus.COMPLETED || session.getStatus() == SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
        }

        // 更新 Redis 缓存
        sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);

        // 更新数据库状态
        try {
            persistenceService.updateSessionStatus(sessionId,
                InterviewSessionEntity.SessionStatus.COMPLETED);
            // 设置评估状态为 PENDING
            persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        } catch (Exception e) {
            log.warn("更新会话状态失败: {}", e.getMessage());
        }

        // 发送评估任务到 Redis Stream
        evaluateStreamProducer.sendEvaluateTask(sessionId);

        log.info("会话 {} 提前交卷，评估任务已入队", sessionId);
    }

    /**
     * 通过问题 ID 定位其在列表中的实际位置（pos）。
     * 阶段0（动态追问改造）：questionIndex 是唯一 ID 而非下标，插入追问后 ID ≠ 位置，
     * 所有"按问题操作"的入口必须先经此方法换算位置。题量 ≤ 30，线性扫描即可。
     *
     * @return 列表位置（0 起），未找到返回 -1
     */
    private int indexOfQuestionById(List<InterviewQuestionDTO> questions, int questionId) {
        for (int i = 0; i < questions.size(); i++) {
            if (questions.get(i).questionIndex() == questionId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 判断指定主问题是否已存在追问（每主问题最多 MAX_FOLLOW_UP_PER_QUESTION 个追问）
     */
    private boolean hasFollowUpFor(List<InterviewQuestionDTO> questions, int parentId) {
        return questions.stream().anyMatch(q ->
            q.isFollowUp() && q.parentQuestionIndex() != null && q.parentQuestionIndex() == parentId);
    }

    /**
     * 当前问题列表中的最大问题 ID（追问 ID = max + 1，大号区间）
     */
    private int maxQuestionId(List<InterviewQuestionDTO> questions) {
        return questions.stream().mapToInt(InterviewQuestionDTO::questionIndex).max().orElse(0);
    }

    /**
     * 会话级分布式锁 key（阶段2 并发一致性）
     */
    private String sessionLockKey(String sessionId) {
        return SESSION_LOCK_KEY_PREFIX + sessionId;
    }

    /**
     * 锁外安全决策：生成器异常时降级为不追问（服务层兜底，绝不阻塞主答题流）
     */
    private FollowUpDecision decideFollowUpSafely(SubmitSnapshot snapshot, String sessionId, String answer) {
        try {
            return followUpGenerator.decide(
                snapshot.question(), answer, snapshot.resumeText(), sessionId);
        } catch (Exception e) {
            log.warn("动态追问决策失败，降级为不追问: sessionId={}, error={}", sessionId, e.getMessage());
            return FollowUpDecision.skip();
        }
    }

    /**
     * 落库失败统一包装（错误场景清晰化）。
     *
     * 场景区分：
     * - BusinessException（会话不存在 3001、序列化失败等）由调用方原样穿透，保留原有明确错误码；
     * - 其余异常（DB 连接失败、查询超时、约束冲突等 DataAccessException）在此按动作包装为
     *   INTERVIEW_ANSWER_SAVE_FAILED(3008)，日志带动作/sessionId/questionId，避免与全局兜底的
     *   "系统繁忙，请稍后重试"混为一谈，前端可据此提示用户重试。
     *
     * 安全性：落库发生在缓存写回之前，抛异常时 Redis 仍是旧状态，用户重试不会产生脏数据。
     *
     * @param action 动作名（"提交答案" / "暂存答案"），用于区分失败场景
     */
    private BusinessException persistenceFailure(String action, String sessionId,
                                                 Integer questionId, Exception e) {
        log.error("{}落库失败: sessionId={}, questionId={}", action, sessionId, questionId, e);
        return new BusinessException(ErrorCode.INTERVIEW_ANSWER_SAVE_FAILED, action + "失败，请重试");
    }

    /**
     * 动态追问硬上限；存量/旧缓存无该字段时按当前题数处理（size < max 恒为 false，不追问）
     */
    private int maxTotalQuestions(CachedSession session, List<InterviewQuestionDTO> questions) {
        return session.getMaxTotalQuestions() != null ? session.getMaxTotalQuestions() : questions.size();
    }

    /**
     * 由缓存会话当前状态构造响应（阶段2 并发幂等跳过路径使用）
     */
    private SubmitAnswerResponse buildResponse(CachedSession session, List<InterviewQuestionDTO> questions) {
        int currentIndex = session.getCurrentIndex();
        boolean hasNext = currentIndex < questions.size();
        return new SubmitAnswerResponse(
            hasNext,
            hasNext ? questions.get(currentIndex) : null,
            currentIndex,
            questions.size(),
            currentIndex
        );
    }

    /**
     * 获取或恢复会话（优先从缓存获取）
     */
    private CachedSession getOrRestoreSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            // 刷新 TTL
            sessionCache.refreshSessionTTL(sessionId);
            return cachedOpt.get();
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return restoredSession;
    }

    /**
     * 生成评估报告
     */
    public InterviewReportDTO generateReport(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        if (session.getStatus() != SessionStatus.COMPLETED && session.getStatus() != SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED, "面试尚未完成，无法生成报告");
        }

        log.info("生成面试报告: {}", sessionId);

        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        InterviewReportDTO report = evaluationService.evaluateInterview(
            sessionId,
            session.getResumeText(),
            questions
        );

        // 更新 Redis 缓存状态
        sessionCache.updateSessionStatus(sessionId, SessionStatus.EVALUATED);

        // 保存报告到数据库
        try {
            persistenceService.saveReport(sessionId, report);
        } catch (Exception e) {
            log.warn("保存报告到数据库失败: {}", e.getMessage());
        }

        return report;
    }

    /**
     * 将缓存会话转换为 DTO
     */
    private InterviewSessionDTO toDTO(CachedSession session) {
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);
        return new InterviewSessionDTO(
            session.getSessionId(),
            session.getResumeText(),
            questions.size(),
            session.getCurrentIndex(),
            questions,
            session.getStatus()
        );
    }
}
