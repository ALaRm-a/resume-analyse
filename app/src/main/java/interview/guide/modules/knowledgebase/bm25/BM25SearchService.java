package interview.guide.modules.knowledgebase.bm25;

import interview.guide.modules.knowledgebase.util.BM25Tokenizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * BM25 关键词检索服务（读路径）
 *
 * <p>三步完成检索：
 * <ol>
 *   <li>对 query 调用 {@link BM25Tokenizer#tokenize(String)} 分词</li>
 *   <li>SQL 联表计算 BM25 分数（k1=1.5, b=0.75）</li>
 *   <li>按 chunk_id 分组求和排序 → 返回 top-K</li>
 * </ol>
 *
 * <h3>BM25 公式</h3>
 * <pre>
 * IDF(qi)   = LN((N - df + 0.5) / (df + 0.5) + 1)
 * TF_norm   = (tf * 2.5) / (tf + 1.5 * (0.25 + 0.75 * chunkLen / avgdl))
 * BM25(d,Q) = Σ IDF(qi) * TF_norm(qi, d)   (over all query terms qi)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BM25SearchService {

    /** BM25 调参：词频饱和度 */
    private static final double K1 = 1.5;
    /** BM25 调参：长度归一化强度 */
    private static final double B = 0.75;

    private final BM25Tokenizer tokenizer;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 对 query 执行 BM25 检索并返回 top-K 结果
     *
     * @param query           用户查询文本
     * @param knowledgeBaseIds 搜索范围（知识库 ID 列表）
     * @param topK            返回条数
     * @return 按 BM25 分数降序排列的 chunk 命中列表
     */
    public List<Bm25Hit> search(String query, List<Long> knowledgeBaseIds, int topK) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            return List.of();
        }

        // 1. 分词
        List<String> terms = tokenizer.tokenize(query);
        if (terms.isEmpty()) {
            log.warn("query 分词后无有效 term: {}", query);
            return List.of();
        }

        log.info("BM25 检索: kbIds={}, topK={}, terms={}", knowledgeBaseIds, topK, terms);

        // 2. 构建 SQL + 参数
        SqlAndParams sqlAndParams = buildSearchSql(knowledgeBaseIds, terms, topK);

        // 3. 执行查询
        List<Bm25Hit> hits = jdbcTemplate.query(sqlAndParams.sql(), sqlAndParams.params(),
            (rs, rowNum) -> new Bm25Hit(
                rs.getString("chunk_id"),
                rs.getDouble("bm25_score")
            )
        );

        log.info("BM25 检索完成: {} 个命中", hits.size());
        return hits;
    }

    // ==================== SQL 构建 ====================

    /**
     * 构建 BM25 检索 SQL，参数占位符用 ?，参数值放入 Object[]。
     *
     * <h3>SQL 结构</h3>
     * <pre>
     * WITH stats AS (       -- 计算搜索范围统计 N、avgdl
     *   SELECT SUM(total_chunks), SUM(total_length)/SUM(total_chunks)
     *   FROM bm25_kb_stats WHERE kb_id IN (...)
     * ),
     * chunk_lens AS (       -- 计算每个 chunk 的长度（总 term 数）
     *   SELECT chunk_id, SUM(tf)
     *   FROM bm25_term_freq WHERE kb_id IN (...) GROUP BY chunk_id
     * )
     * SELECT t.chunk_id, SUM(IDF * TF_norm) AS bm25_score
     * FROM bm25_term_freq t
     * JOIN bm25_doc_freq d ON t.kb_id=d.kb_id AND t.term=d.term
     * CROSS JOIN stats s
     * LEFT JOIN chunk_lens l ON t.chunk_id=l.chunk_id
     * WHERE t.kb_id IN (...) AND t.term IN (...)
     * GROUP BY t.chunk_id ORDER BY 2 DESC LIMIT ?
     * </pre>
     */
    private SqlAndParams buildSearchSql(List<Long> kbIds, List<String> terms, int topK) {
        String kbPlaceholders = kbIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String termPlaceholders = terms.stream().map(t -> "?").collect(Collectors.joining(","));

        // SQL 模板中用 String.format 插入占位符个数，实际值用参数数组绑定
        String sql = """
            WITH stats AS (
                SELECT
                    SUM(total_chunks)::numeric             AS N,
                    SUM(total_length)::numeric
                        / NULLIF(SUM(total_chunks), 0)     AS avgdl
                FROM bm25_kb_stats
                WHERE kb_id IN (%s)
            ),
            chunk_lens AS (
                SELECT chunk_id, SUM(tf) AS chunk_len
                FROM bm25_term_freq
                WHERE kb_id IN (%s)
                GROUP BY chunk_id
            )
            SELECT
                t.chunk_id,
                SUM(
                    LN((s.N - d.df + 0.5) / NULLIF(d.df + 0.5, 0) + 1)
                    * (%s * t.tf)
                    / (t.tf + %s * (%s + %s * COALESCE(l.chunk_len, 0) / NULLIF(s.avgdl, 0)))
                ) AS bm25_score
            FROM bm25_term_freq t
            JOIN bm25_doc_freq d ON t.kb_id = d.kb_id AND t.term = d.term
            CROSS JOIN stats s
            LEFT JOIN chunk_lens l ON t.chunk_id = l.chunk_id
            WHERE t.kb_id IN (%s)
              AND t.term IN (%s)
            GROUP BY t.chunk_id
            ORDER BY bm25_score DESC
            LIMIT ?
            """.formatted(
                kbPlaceholders,       // stats CTE
                kbPlaceholders,       // chunk_lens CTE
                K1 + 1,               // TF 分子系数: k1+1
                K1,                   // TF 分母 k1
                1 - B,                // 1-b
                B,                    // b
                kbPlaceholders,       // main WHERE kb_id
                termPlaceholders      // main WHERE term
            );

        // 组装参数数组：stats(kbIds) + chunk_lens(kbIds) + main kbIds + main terms + topK
        List<Object> params = new ArrayList<>();
        params.addAll(kbIds);       // stats CTE
        params.addAll(kbIds);       // chunk_lens CTE
        params.addAll(kbIds);       // main WHERE kb_id
        for (String term : terms) {
            params.add(term);       // main WHERE term
        }
        params.add(topK);           // LIMIT

        return new SqlAndParams(sql, params.toArray());
    }

    // ==================== 内嵌类 ====================

    /** SQL + 参数封装 */
    private record SqlAndParams(String sql, Object[] params) {}

    /** BM25 检索命中结果 */
    public record Bm25Hit(String chunkId, double score) {}
}
