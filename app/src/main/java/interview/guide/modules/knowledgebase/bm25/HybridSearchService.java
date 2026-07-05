package interview.guide.modules.knowledgebase.bm25;

import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * RRF（Reciprocal Rank Fusion）混合检索服务
 *
 * <p>编排 BM25 关键词检索 + pgvector 向量检索两路召回，用 RRF 公式融合排序。
 * RRF 只用排名、不用原始分数，天然解决两种分数量纲不一致的问题。</p>
 *
 * <h3>公式</h3>
 * <pre>
 * RRF(d) = Σ 1 / (k + rank_i(d))
 * </pre>
 *
 * <h3>流程</h3>
 * <ol>
 *   <li>BM25 和向量分别召回 recallPerPath 条（默认 30），得到排名列表</li>
 *   <li>按 chunk_id 合并，计算 RRF 分数</li>
 *   <li>按融合分数降序排序，截取 topK</li>
 * </ol>
 *
 * <h3>配置</h3>
 * <p>k=60（经典值），可通过 {@link #search(String, List, int, int, int)} 传入做消融实验</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    /** RRF 公式中的 k 常数（论文推荐值 60） */
    public static final int DEFAULT_RRF_K = 60;

    /** 默认每路召回数量（给足才能让排名靠后的位置有区分度） */
    public static final int DEFAULT_RECALL_PER_PATH = 30;

    private final BM25SearchService bm25SearchService;
    private final KnowledgeBaseVectorService vectorService;

    /**
     * RRF 混合检索（使用默认 k=60, recallPerPath=30）
     */
    public List<HybridHit> search(String query, List<Long> kbIds, int finalTopK) {
        return search(query, kbIds, finalTopK, DEFAULT_RECALL_PER_PATH, DEFAULT_RRF_K);
    }

    /**
     * RRF 混合检索（可自定义参数，用于消融实验）
     *
     * @param query          查询文本
     * @param kbIds          知识库 ID 列表
     * @param finalTopK      最终返回条数
     * @param recallPerPath  每路召回条数（建议 20~50）
     * @param rrfK           RRF 公式中的 k 值
     * @return 按 RRF 分数降序排列的命中列表
     */
    public List<HybridHit> search(String query, List<Long> kbIds, int finalTopK,
                                   int recallPerPath, int rrfK) {
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }

        log.info("RRF 混合检索: query={}, kbIds={}, finalTopK={}, recallPerPath={}, k={}",
            query, kbIds, finalTopK, recallPerPath, rrfK);

        // ========== 1. 两路独立召回 ==========

        List<BM25SearchService.Bm25Hit> bm25Hits;
        try {
            bm25Hits = bm25SearchService.search(query, kbIds, recallPerPath);
        } catch (Exception e) {
            log.warn("BM25 检索失败，降级为纯向量检索: {}", e.getMessage());
            bm25Hits = List.of();
        }

        List<Document> vectorDocs;
        try {
            vectorDocs = vectorService.similaritySearch(query, kbIds, recallPerPath, 0.0);
        } catch (Exception e) {
            log.warn("向量检索失败，降级为纯 BM25 检索: {}", e.getMessage());
            vectorDocs = List.of();
        }

        log.info("两路召回: BM25={} 条, 向量={} 条", bm25Hits.size(), vectorDocs.size());

        // ========== 2. RRF 融合 ==========

        // chunk_id → RRF 累积分数
        Map<String, Double> rrfScores = new LinkedHashMap<>();

        // BM25 路径排名贡献
        for (int i = 0; i < bm25Hits.size(); i++) {
            String chunkId = bm25Hits.get(i).chunkId();
            double contribution = 1.0 / (rrfK + i + 1);
            rrfScores.merge(chunkId, contribution, Double::sum);
        }

        // 向量路径排名贡献
        for (int i = 0; i < vectorDocs.size(); i++) {
            String chunkId = vectorDocs.get(i).getId();
            if (chunkId == null || chunkId.isBlank()) {
                continue;
            }
            double contribution = 1.0 / (rrfK + i + 1);
            rrfScores.merge(chunkId, contribution, Double::sum);
        }

        // ========== 3. 排序截断 ==========

        List<HybridHit> results = rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(finalTopK)
            .map(e -> new HybridHit(e.getKey(), e.getValue()))
            .toList();

        log.info("RRF 融合完成: 融合后 {} 个候选, 最终返回 {} 条",
            rrfScores.size(), results.size());

        return results;
    }

    // ==================== 内嵌类 ====================

    /**
     * RRF 混合检索命中结果
     *
     * @param chunkId  知识库 chunk 唯一标识（与 vector_store.id 一致）
     * @param rrfScore RRF 融合分数（越高越相关）
     */
    public record HybridHit(String chunkId, double rrfScore) {}
}
