package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.agent.memory.config.AgentMemoryProperties;
import interview.guide.modules.agent.memory.dto.CompressibleMessage;
import interview.guide.modules.agent.memory.service.MemoryCompressService;
import interview.guide.modules.agent.memory.service.MemoryService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.List;

/**
 * RAG 聊天会话服务
 * 提供RAG聊天会话的创建、获取、更新、删除等操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatSessionService {

    private final RagChatSessionRepository sessionRepository;
    private final RagChatMessageRepository messageRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseQueryService queryService;
    private final RagChatMapper ragChatMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final MemoryService memoryService;
    private final MemoryCompressService compressService;
    private final AgentMemoryProperties memoryProperties;

    private static final String MEMORY_SOURCE = "rag_chat";

    /**
     * 创建新会话
     */
    @Transactional
    public SessionDTO createSession(CreateSessionRequest request) {
        // 验证知识库存在
        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository
            .findAllById(request.knowledgeBaseIds());

        if (knowledgeBases.size() != request.knowledgeBaseIds().size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分知识库不存在");
        }

        // 创建会话
        RagChatSessionEntity session = new RagChatSessionEntity();
        session.setTitle(request.title() != null && !request.title().isBlank()
            ? request.title()
            : generateTitle(knowledgeBases));
        session.setKnowledgeBases(new HashSet<>(knowledgeBases));

        session = sessionRepository.save(session);

        log.info("创建 RAG 聊天会话: id={}, title={}", session.getId(), session.getTitle());

        return ragChatMapper.toSessionDTO(session);
    }

    /**
     * 获取会话列表
     */
    public List<SessionListItemDTO> listSessions() {
        return sessionRepository.findAllOrderByPinnedAndUpdatedAtDesc()
            .stream()
            .map(ragChatMapper::toSessionListItemDTO)
            .toList();
    }

    /**
     * 获取会话详情（包含消息）
     * 分两次查询避免笛卡尔积问题
     */
    public SessionDetailDTO getSessionDetail(Long sessionId) {
        // 先加载会话和知识库
        RagChatSessionEntity session = sessionRepository
            .findByIdWithKnowledgeBases(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 再单独加载消息（避免笛卡尔积）
        List<RagChatMessageEntity> messages = messageRepository
            .findBySessionIdOrderByMessageOrderAsc(sessionId);

        // 转换知识库列表
        List<KnowledgeBaseListItemDTO> kbDTOs = knowledgeBaseMapper.toListItemDTOList(
            new java.util.ArrayList<>(session.getKnowledgeBases())
        );

        return ragChatMapper.toSessionDetailDTO(session, messages, kbDTOs);
    }

    /**
     * 准备流式消息（保存用户消息，创建 AI 消息占位）
     *
     * @return AI 消息的 ID
     */
    @Transactional
    public Long prepareStreamMessage(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByIdWithKnowledgeBases(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 获取当前消息数量作为起始顺序
        int nextOrder = session.getMessageCount();

        // 保存用户消息
        RagChatMessageEntity userMessage = new RagChatMessageEntity();
        userMessage.setSession(session);
        userMessage.setType(RagChatMessageEntity.MessageType.USER);
        userMessage.setContent(question);
        userMessage.setMessageOrder(nextOrder);
        userMessage.setCompleted(true);
        messageRepository.save(userMessage);

        // 创建 AI 消息占位（未完成）
        RagChatMessageEntity assistantMessage = new RagChatMessageEntity();
        assistantMessage.setSession(session);
        assistantMessage.setType(RagChatMessageEntity.MessageType.ASSISTANT);
        assistantMessage.setContent("");
        assistantMessage.setMessageOrder(nextOrder + 1);
        assistantMessage.setCompleted(false);
        assistantMessage = messageRepository.save(assistantMessage);

        // 更新会话消息数量
        session.setMessageCount(nextOrder + 2);
        sessionRepository.save(session);

        log.info("准备流式消息: sessionId={}, messageId={}", sessionId, assistantMessage.getId());

        return assistantMessage.getId();
    }

    /**
     * 流式响应完成后更新消息，并触发记忆压缩（如满足条件）。
     */
    @Transactional
    public void completeStreamMessage(Long messageId, String content) {
        RagChatMessageEntity message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "消息不存在"));

        message.setContent(content);
        message.setCompleted(true);
        messageRepository.save(message);

        Long sessionId = message.getSession().getId();

        // 超过阈值时，按范围查询消息并交给压缩服务判断
        Integer lastOrder = messageRepository.findMaxOrderBySessionId(sessionId);
        if (lastOrder == null) {
            log.warn("完成消息后未找到会话消息: sessionId={}", sessionId);
            return;
        }
        int totalMessages = lastOrder + 1;

        if (totalMessages > memoryProperties.getTriggerThreshold()) {
            int fetchFrom = Math.max(0, lastOrder - 2 * memoryProperties.getWindowSize());
            List<RagChatMessageEntity> rangeMessages = messageRepository
                    .findBySessionIdAndMessageOrderBetween(sessionId, fetchFrom, lastOrder);

            List<CompressibleMessage> compressibleMessages = rangeMessages.stream()
                    .map(m -> new CompressibleMessage(
                            m.getMessageOrder(),
                            (m.getType() == RagChatMessageEntity.MessageType.USER ? "用户：" : "助手：")
                                    + m.getContent()))
                    .toList();

            compressService.compressIfNeeded(MEMORY_SOURCE, sessionId, totalMessages, compressibleMessages);
        }

        log.info("完成流式消息: messageId={}, contentLength={}", messageId, content.length());
    }


    /**
     * 获取流式回答（默认 rerank 策略，向后兼容）
     */
    public Flux<String> getStreamAnswer(Long sessionId, String question) {
        return getStreamAnswer(sessionId, question, null);
    }

    /**
     * 获取流式回答，支持接口级 rerank 开关
     *
     * @param rerankOverride null=默认策略（闸门自动判断），true=强制精排，false=强制跳过精排
     */
    public Flux<String> getStreamAnswer(Long sessionId, String question, Boolean rerankOverride) {
        RagChatSessionEntity session = sessionRepository.findByIdWithKnowledgeBases(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<Long> kbIds = session.getKnowledgeBaseIds();

        // 从上次压缩结束位置 + 1 开始取消息，跟随压缩进度走
        int rawReadFrom = memoryService.getEndMsgOrder(MEMORY_SOURCE, sessionId).orElse(-1) + 1;
        // 边界对齐：readFromOrder 必须是偶数（USER 起步）
        int readFromOrder = (rawReadFrom % 2 != 0) ? rawReadFrom + 1 : rawReadFrom;

        List<RagChatMessageEntity> recentMessages = messageRepository
                .findBySessionIdAndMessageOrderGreaterThanEqual(sessionId, readFromOrder);

        List<String> messageTexts = recentMessages.stream()
                .map(m -> (m.getType() == RagChatMessageEntity.MessageType.USER ? "用户：" : "助手：")
                        + m.getContent())
                .toList();

        String conversationHistory = memoryService.getMemory(MEMORY_SOURCE, sessionId, messageTexts)
                .toPromptText();

        return queryService.answerQuestionStream(kbIds, question, rerankOverride, conversationHistory);
    }

    /**
     * 更新会话标题
     */
    @Transactional
    public void updateSessionTitle(Long sessionId, String title) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        session.setTitle(title);
        sessionRepository.save(session);

        log.info("更新会话标题: sessionId={}, title={}", sessionId, title);
    }

    /**
     * 切换会话置顶状态
     */
    @Transactional
    public void togglePin(Long sessionId) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 处理 null 值（兼容旧数据）
        Boolean currentPinned = session.getIsPinned() != null ? session.getIsPinned() : false;
        session.setIsPinned(!currentPinned);
        sessionRepository.save(session);

        log.info("切换会话置顶状态: sessionId={}, isPinned={}", sessionId, session.getIsPinned());
    }

    /**
     * 更新会话的知识库关联
     */
    @Transactional
    public void updateSessionKnowledgeBases(Long sessionId, List<Long> knowledgeBaseIds) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository
            .findAllById(knowledgeBaseIds);

        session.setKnowledgeBases(new HashSet<>(knowledgeBases));
        sessionRepository.save(session);

        log.info("更新会话知识库: sessionId={}, kbIds={}", sessionId, knowledgeBaseIds);
    }

    /**
     * 删除会话
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        // 先清理摘要记忆，即使会话不存在也要确保残留摘要被删除
        memoryService.deleteBySourceAndSessionId(MEMORY_SOURCE, sessionId);
        sessionRepository.deleteById(sessionId);

        log.info("删除会话: sessionId={}", sessionId);
    }

    // ========== 私有方法 ==========

    private String generateTitle(List<KnowledgeBaseEntity> knowledgeBases) {
        if (knowledgeBases.isEmpty()) {
            return "新对话";
        }
        if (knowledgeBases.size() == 1) {
            return knowledgeBases.getFirst().getName();
        }
        return knowledgeBases.size() + " 个知识库对话";
    }
}
