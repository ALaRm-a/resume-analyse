package interview.guide.modules.agent.memory.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 对话摘要实体 — agent 记忆模块独立表，不依赖任何业务表。
 *
 * <p>每个 (source, sessionId) 组合只保留一条记录，增量压缩时通过 {@code @Version}
 * 实现乐观锁控制，避免并发更新导致摘要覆盖丢失。</p>
 */
@Entity
@Table(name = "conversation_summaries")
@Getter
@Setter
@NoArgsConstructor
public class ConversationSummaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务来源标识（如 'rag_chat'、'interview'），与 sessionId 组成联合唯一 */
    @Column(name = "source", nullable = false, length = 32)
    private String source;

    /** 关联的会话ID（不设外键约束，解耦业务表） */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 摘要内容 */
    @Column(name = "summary_text", nullable = false, columnDefinition = "TEXT")
    private String summaryText;

    /** 摘要覆盖的起始消息序号 */
    @Column(name = "start_msg_order", nullable = false)
    private Integer startMsgOrder;

    /** 摘要覆盖的结束消息序号 */
    @Column(name = "end_msg_order", nullable = false)
    private Integer endMsgOrder;

    /** 版本号，由 JPA 乐观锁管理 */
    @Version
    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
