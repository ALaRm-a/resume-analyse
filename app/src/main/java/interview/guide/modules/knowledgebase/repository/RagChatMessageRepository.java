package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity.MessageType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * RAG聊天消息Repository
 */
@Repository
public interface RagChatMessageRepository extends JpaRepository<RagChatMessageEntity, Long> {

    /**
     * 获取会话的所有消息（按顺序）
     */
    List<RagChatMessageEntity> findBySessionIdOrderByMessageOrderAsc(Long sessionId);

    /**
     * 取 messageOrder >= readFromOrder 的全部消息（正序）。
     *
     * <p>读取端跟随压缩进度：从 endMsgOrder + 1 开始取，与压缩端共用同一个进度锚点，
     * 不会出现覆盖空洞。必须用 >= 而非 >，否则 readFromOrder=0 时会漏掉 order=0 的首条消息。</p>
     */
    @Query("SELECT m FROM RagChatMessageEntity m WHERE m.session.id = :sessionId AND m.messageOrder >= :readFromOrder ORDER BY m.messageOrder ASC")
    List<RagChatMessageEntity> findBySessionIdAndMessageOrderGreaterThanEqual(
            @Param("sessionId") Long sessionId, @Param("readFromOrder") int readFromOrder);

    /**
     * 取 messageOrder 在 [from, to] 闭区间内的消息（正序）。
     *
     * <p>供 completeStreamMessage 触发压缩时使用，只查"溢出区间 + 最近窗口"范围内的消息，
     * 避免全量加载整个会话历史。</p>
     */
    @Query("SELECT m FROM RagChatMessageEntity m WHERE m.session.id = :sessionId AND m.messageOrder BETWEEN :from AND :to ORDER BY m.messageOrder ASC")
    List<RagChatMessageEntity> findBySessionIdAndMessageOrderBetween(
            @Param("sessionId") Long sessionId,
            @Param("from") int from, @Param("to") int to);

    /**
     * 取指定会话当前最大的 messageOrder。
     *
     * <p>用于 completeStreamMessage 计算 totalMessages = lastOrder + 1。</p>
     */
    @Query("SELECT MAX(m.messageOrder) FROM RagChatMessageEntity m WHERE m.session.id = :sessionId")
    Integer findMaxOrderBySessionId(@Param("sessionId") Long sessionId);

    /**
     * 获取会话的最后一条消息
     */
    Optional<RagChatMessageEntity> findTopBySessionIdOrderByMessageOrderDesc(Long sessionId);

    /**
     * 获取会话消息数量
     */
    @Query("SELECT COUNT(m) FROM RagChatMessageEntity m WHERE m.session.id = :sessionId")
    Integer countBySessionId(@Param("sessionId") Long sessionId);

    /**
     * 查找未完成的消息（流式响应中断时清理用）
     */
    List<RagChatMessageEntity> findBySessionIdAndCompletedFalse(Long sessionId);

    /**
     * 删除会话的所有消息
     */
    void deleteBySessionId(Long sessionId);

    /**
     * 统计所有用户消息数（即总提问次数）
     */
    long countByType(MessageType type);
}
