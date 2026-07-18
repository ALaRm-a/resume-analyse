CREATE EXTENSION IF NOT EXISTS vector;

-- conversation_summaries: agent 记忆模块的摘要表，独立于业务表
CREATE TABLE IF NOT EXISTS conversation_summaries (
    id              BIGSERIAL PRIMARY KEY,
    source          VARCHAR(32) NOT NULL,
    session_id      BIGINT NOT NULL,
    summary_text    TEXT NOT NULL,
    start_msg_order INTEGER NOT NULL,
    end_msg_order   INTEGER NOT NULL,
    version         INTEGER NOT NULL DEFAULT 1,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 联合唯一索引：同一业务来源下，每个 session 只有一条摘要
CREATE UNIQUE INDEX IF NOT EXISTS idx_summary_source_session ON conversation_summaries(source, session_id);
