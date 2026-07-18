package interview.guide.modules.agent.memory.service;

import interview.guide.modules.agent.memory.dto.ConversationMemory;
import interview.guide.modules.agent.memory.model.ConversationSummaryEntity;
import interview.guide.modules.agent.memory.repository.ConversationSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 记忆查询服务 — Agent 记忆模块的对外接口。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>核心入参是 source + sessionId + 消息列表，不依赖任何业务模块</li>
 *   <li>source 区分业务来源（如 "rag_chat"、"interview"），解决不同业务自增主键冲突</li>
 *   <li>调用方（knowledgebase/interview）负责传入消息列表</li>
 *   <li>本服务只负责查摘要 + 组装文本，不做业务表查询</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final ConversationSummaryRepository summaryRepository;

    /**
     * 获取指定会话的记忆上下文。
     *
     * <p>调用方必须只传入最近窗口大小的原始消息，更早的历史应已保存在摘要表中，
     * 由本服务通过 {@code summary} 字段返回，避免每次都把全量原始历史塞进 prompt。</p>
     *
     * @param source         业务来源标识（如 "rag_chat"、"interview"）
     * @param sessionId      会话ID
     * @param recentMessages 最近 N 条消息（已由调用方查询并限制数量）
     * @return 记忆上下文（摘要 + 最近消息文本）
     */
    public ConversationMemory getMemory(String source, Long sessionId,
            List<String> recentMessages) {

        String summary = summaryRepository.findBySourceAndSessionId(source, sessionId)
                .map(ConversationSummaryEntity::getSummaryText)
                .orElse(null);

        String historyText = recentMessages != null && !recentMessages.isEmpty()
                ? String.join("\n", recentMessages)
                : null;

        return new ConversationMemory(summary, historyText);
    }

    /**
     * 仅获取摘要文本（不包含最近消息）。
     */
    public String getSummary(String source, Long sessionId) {
        return summaryRepository.findBySourceAndSessionId(source, sessionId)
                .map(ConversationSummaryEntity::getSummaryText)
                .orElse(null);
    }

    /**
     * 删除会话的所有记忆（业务模块删除会话时调用）。
     */
    @Transactional
    public void deleteBySourceAndSessionId(String source, Long sessionId) {
        summaryRepository.deleteBySourceAndSessionId(source, sessionId);
        log.info("删除会话记忆: source={}, sessionId={}", source, sessionId);
    }
}
