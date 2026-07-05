package interview.guide.modules.knowledgebase.bm25;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BM25 倒排索引数据访问层
 *
 * <p>使用 JdbcTemplate 直接操作三张 BM25 表，与 {@code VectorRepository} 风格一致。</p>
 *
 * <h3>表操作概览</h3>
 * <table>
 *   <tr><td>bm25_term_freq</td><td>批量写入/按 kb_id 删除/查询 term 对应的 chunk 列表</td></tr>
 *   <tr><td>bm25_doc_freq</td><td>df 增减、按 kb_id 删除、查询统计信息</td></tr>
 *   <tr><td>bm25_kb_stats</td><td>total_chunks/total_length 增减、avgdl 自动重算</td></tr>
 * </table>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class BM25Repository {

    private final JdbcTemplate jdbcTemplate;

    // ==================== 通用检查 ====================

    /**
     * 判断指定知识库是否已有 BM25 索引数据
     * <p>用于 indexChunks 入口的幂等保护——若已存在旧索引，先清再建，防止 df/stats 重复累加</p>
     */
    public boolean hasIndexData(Long kbId) {
        String sql = "SELECT COUNT(*) FROM bm25_term_freq WHERE kb_id = ? LIMIT 1";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, kbId);
        return count != null && count > 0;
    }

    // ==================== bm25_term_freq 操作 ====================

    /**
     * 批量写入词频数据（INSERT ... ON CONFLICT DO UPDATE 幂等）
     *
     * @param entries 词频条目列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchInsertTermFreq(List<TermFreqEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        // 原本创建的是联合主键（唯一+非空),在做重复插入的时候选择覆盖更新
        String sql = """
            INSERT INTO bm25_term_freq (kb_id, chunk_id, term, tf)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (kb_id, chunk_id, term)
            DO UPDATE SET tf = EXCLUDED.tf
            """;

        List<Object[]> batchArgs = new ArrayList<>();
        for (TermFreqEntry e : entries) {
            batchArgs.add(new Object[]{e.kbId(), e.chunkId(), e.term(), e.tf()});
        }

        jdbcTemplate.batchUpdate(sql, batchArgs);
        log.debug("批量写入 term_freq 完成，条目数: {}", entries.size());
    }

    /**
     * 删除指定知识库的所有词频数据，并返回被删除的 (term, 包含该 term 的 chunk 数) 列表
     * <p>用于后续同步减少 doc_freq</p>
     *
     * @param kbId 知识库 ID
     * @return 每个 term 在多少 chunk 中出现过（即 df 需要减少的量）
     */
    @Transactional(rollbackFor = Exception.class)
    public List<TermDfDelta> deleteTermFreqByKbId(Long kbId) {
        // 先查出每个 term 涉及的 chunk 数（df 减量）
        String querySql = """
            SELECT term, COUNT(DISTINCT chunk_id) AS chunk_count
            FROM bm25_term_freq
            WHERE kb_id = ?
            GROUP BY term
            """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(querySql, kbId);
        List<TermDfDelta> deltas = rows.stream()
            .map(row -> new TermDfDelta(
                (String) row.get("term"),
                ((Number) row.get("chunk_count")).intValue()
            ))
            .toList();

        // 再删除数据
        String deleteSql = "DELETE FROM bm25_term_freq WHERE kb_id = ?";
        int deleted = jdbcTemplate.update(deleteSql, kbId);
        log.info("删除 bm25_term_freq: kbId={}, 删除行数={}, 涉及 term 数={}",
            kbId, deleted, deltas.size());

        return deltas;
    }

    // ==================== bm25_doc_freq 操作 ====================

    /**
     * 对一批 term 的 df 各加 1（每个 term 在该 chunk 中出现过，不论出现几次，df 只加 1）
     * <p>使用 ON CONFLICT DO UPDATE 实现幂等：新词 INSERT，已有词 UPDATE</p>
     *
     * @param kbId  知识库 ID
     * @param terms 在该 chunk 中出现的所有不重复 term
     */
    @Transactional(rollbackFor = Exception.class)
    public void incrementDfBatch(Long kbId, List<String> terms) {
        if (terms.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO bm25_doc_freq (kb_id, term, df) VALUES (?, ?, 1)
            ON CONFLICT (kb_id, term) DO UPDATE SET df = bm25_doc_freq.df + 1
            """;

        List<Object[]> batchArgs = terms.stream()
            .map(term -> new Object[]{kbId, term})
            .toList();

        jdbcTemplate.batchUpdate(sql, batchArgs);
    }

    /**
     * 批量更新 df：key=term, value=该 term 在多少个新 chunk 中出现过
     * <p>一次数据库往返，替代 per-chunk 逐个 term 调 incrementDfBatch 的 N+1 问题</p>
     *
     * @param kbId       知识库 ID
     * @param termCounts term → 出现的 chunk 数（每个 chunk 中无论 tf 多少，该 term 只算 1 次）
     */
    @Transactional(rollbackFor = Exception.class)
    public void incrementDfBatchWithCounts(Long kbId, Map<String, Integer> termCounts) {
        if (termCounts.isEmpty()) {
            return;
        }

        // 把 <term, chunkCount> 展平为 <kbId, term, chunkCount> 三元组
        List<Object[]> batchArgs = new ArrayList<>(termCounts.size());
        termCounts.forEach((term, chunkCount) ->
            batchArgs.add(new Object[]{kbId, term, chunkCount})
        );

        String sql = """
            INSERT INTO bm25_doc_freq (kb_id, term, df) VALUES (?, ?, ?)
            ON CONFLICT (kb_id, term) DO UPDATE SET df = bm25_doc_freq.df + EXCLUDED.df
            """;

        jdbcTemplate.batchUpdate(sql, batchArgs);
        log.debug("批量更新 df 完成: kbId={}, term 数={}", kbId, termCounts.size());
    }

    /**
     * 对一批 term 的 df 各减指定数量
     * <p>df 减到 0 或以下时自动删除该行</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void decrementDfBatch(Long kbId, List<TermDfDelta> deltas) {
        if (deltas.isEmpty()) {
            return;
        }

        // 先更新 df
        String updateSql = """
            UPDATE bm25_doc_freq
            SET df = df - ?
            WHERE kb_id = ? AND term = ?
            """;

        List<Object[]> updateArgs = deltas.stream()
            .map(d -> new Object[]{d.delta(), kbId, d.term()})
            .toList();
        jdbcTemplate.batchUpdate(updateSql, updateArgs);

        // 删除 df <= 0 的行
        String deleteSql = "DELETE FROM bm25_doc_freq WHERE kb_id = ? AND df <= 0";
        int deleted = jdbcTemplate.update(deleteSql, kbId);
        log.debug("清理 bm25_doc_freq 中 df<=0 的行: kbId={}, 删除数={}", kbId, deleted);
    }

    // ==================== bm25_kb_stats 操作 ====================

    /**
     * 增加一个 chunk 的统计信息（chunk 新增时调用）
     *
     * @param kbId        知识库 ID
     * @param termCount   该 chunk 分词后的 term 总数（即 chunk 长度）
     */
    @Transactional(rollbackFor = Exception.class)
    public void incrementKbStats(Long kbId, int termCount) {
        String sql = """
            INSERT INTO bm25_kb_stats (kb_id, total_chunks, total_length, avgdl)
            VALUES (?, 1, ?, ?)
            ON CONFLICT (kb_id) DO UPDATE SET
                total_chunks = bm25_kb_stats.total_chunks + 1,
                total_length = bm25_kb_stats.total_length + EXCLUDED.total_length,
                avgdl = (bm25_kb_stats.total_length + EXCLUDED.total_length)::numeric
                      / (bm25_kb_stats.total_chunks + 1)
            """;
        jdbcTemplate.update(sql, kbId, termCount, (double) termCount);
    }

    /**
     * 批量更新 kb_stats（聚合所有 chunk 后一次写入）
     *
     * @param kbId        知识库 ID
     * @param chunksAdded 新增 chunk 数
     * @param termsAdded  新增 term 总数（所有 chunk 分词后的 term 数之和）
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateKbStats(Long kbId, int chunksAdded, long termsAdded) {
        String sql = """
            INSERT INTO bm25_kb_stats (kb_id, total_chunks, total_length, avgdl)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (kb_id) DO UPDATE SET
                total_chunks = bm25_kb_stats.total_chunks + EXCLUDED.total_chunks,
                total_length = bm25_kb_stats.total_length + EXCLUDED.total_length,
                avgdl = (bm25_kb_stats.total_length + EXCLUDED.total_length)::numeric
                      / (bm25_kb_stats.total_chunks + EXCLUDED.total_chunks)
            """;
        double avgdl = chunksAdded > 0 ? (double) termsAdded / chunksAdded : 0;
        jdbcTemplate.update(sql, kbId, chunksAdded, termsAdded, avgdl);
        log.debug("批量更新 kb_stats 完成: kbId={}, chunksAdded={}, termsAdded={}",
            kbId, chunksAdded, termsAdded);
    }

    /**
     * 删除知识库的统计信息（知识库完全删除时调用）
     *
     * @return 被删除的统计行数（0 或 1）
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteKbStats(Long kbId) {
        String sql = "DELETE FROM bm25_kb_stats WHERE kb_id = ?";
        int deleted = jdbcTemplate.update(sql, kbId);
        log.debug("删除 bm25_kb_stats: kbId={}, 删除行数={}", kbId, deleted);
        return deleted;
    }

    // ==================== 内嵌记录类 ====================

    /**
     * 词频条目（用于批量写入 bm25_term_freq）
     */
    public record TermFreqEntry(Long kbId, String chunkId, String term, int tf) {}

    /**
     * df 变化量（删除时记录每个 term 的 df 需要减少多少）
     */
    public record TermDfDelta(String term, int delta) {}
}
