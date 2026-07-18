package interview.guide.modules.agent.memory.repository;

import interview.guide.modules.agent.memory.model.ConversationSummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 对话摘要持久化接口。
 */
@Repository
public interface ConversationSummaryRepository
        extends JpaRepository<ConversationSummaryEntity, Long> {

    /**
     * 按业务来源和会话ID查询摘要。
     */
    Optional<ConversationSummaryEntity> findBySourceAndSessionId(String source, Long sessionId);

    /**
     * 按业务来源和会话ID删除摘要。
     */
    void deleteBySourceAndSessionId(String source, Long sessionId);
}
