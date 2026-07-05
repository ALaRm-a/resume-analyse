package interview.guide.modules.knowledgebase.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * BM25 倒排索引表初始化器
 *
 * <p>应用启动时自动创建三张 BM25 索引表（幂等建表，多次启动不会报错）。
 * 与 JPA Entity / Spring AI 自动建表并行运行，互不干扰。</p>
 *
 * <h3>表结构说明</h3>
 * <table>
 *   <tr><td>bm25_term_freq</td><td>倒排索引：每个 chunk 中每个 term 出现了几次</td></tr>
 *   <tr><td>bm25_doc_freq</td><td>文档频率：某个 term 在多少个 chunk 中出现过</td></tr>
 *   <tr><td>bm25_kb_stats</td><td>知识库级别统计：总 chunk 数、总 term 数、平均 chunk 长度</td></tr>
 * </table>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BM25TableInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        log.info("开始初始化 BM25 索引表...");

        createTermFreqTable();
        createDocFreqTable();
        createKbStatsTable();
        createIndexes();

        log.info("BM25 索引表初始化完成");
    }

    private void createTermFreqTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS bm25_term_freq (
                kb_id       BIGINT      NOT NULL,
                chunk_id    VARCHAR(36) NOT NULL,
                term        VARCHAR(128) NOT NULL,
                tf          INT         NOT NULL DEFAULT 0,
                PRIMARY KEY (kb_id, chunk_id, term)
            )
            """);
        log.debug("bm25_term_freq 表就绪");
    }

    private void createDocFreqTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS bm25_doc_freq (
                kb_id  BIGINT       NOT NULL,
                term   VARCHAR(128) NOT NULL,
                df     INT          NOT NULL DEFAULT 0,
                PRIMARY KEY (kb_id, term)
            )
            """);
        log.debug("bm25_doc_freq 表就绪");
    }

    private void createKbStatsTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS bm25_kb_stats (
                kb_id         BIGINT PRIMARY KEY,
                total_chunks  INT          NOT NULL DEFAULT 0,
                total_length  BIGINT       NOT NULL DEFAULT 0,
                avgdl         NUMERIC(10,4) NOT NULL DEFAULT 0
            )
            """);
        log.debug("bm25_kb_stats 表就绪");
    }

    private void createIndexes() {
        jdbcTemplate.execute("""
            CREATE INDEX IF NOT EXISTS idx_bm25_tf_lookup
            ON bm25_term_freq (kb_id, term)
            """);
        log.debug("idx_bm25_tf_lookup 索引就绪");
    }
}
