package interview.guide.modules.knowledgebase.bm25;

import interview.guide.modules.knowledgebase.util.BM25Tokenizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * BM25 索引写入/删除服务
 *
 * <p>负责 chunk 分词 → tf 统计 → 写入三张倒排索引表的完整写路径。
 * 与向量写入（{@code KnowledgeBaseVectorService}）并行执行，互不影响。</p>
 *
 * <h3>写入流程</h3>
 * <ol>
 *   <li>对每个 chunk 调用 {@link BM25Tokenizer#tokenize(String)} 分词</li>
 *   <li>统计每个 term 在该 chunk 中的出现次数（tf）</li>
 *   <li>批量写入 {@code bm25_term_freq}</li>
 *   <li>每个 term 的 df += 1（无论 tf 是多少，同一 chunk 内只看"是否出现过"）</li>
 *   <li>更新 {@code bm25_kb_stats}（total_chunks +1，total_length +term 总数，重算 avgdl）</li>
 * </ol>
 *
 * <h3>删除流程</h3>
 * <ol>
 *   <li>查出该 kb 下所有 (term, 包含该 term 的 chunk 数)</li>
 *   <li>对每个 term，df 减去对应的 chunk 数</li>
 *   <li>删除该 kb 的所有 term_freq 行</li>
 *   <li>删除该 kb 的 kb_stats 行</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BM25IndexService {

    private final BM25Tokenizer tokenizer;
    private final BM25Repository repository;

    /**
     * 为一批 chunk 建立 BM25 倒排索引（幂等：如已有旧索引则先清再建）
     * <p>应在 chunk UUID 已设置好、且 vector_store 写入之前调用，
     * 确保 BM25 索引中的 chunk_id 与 vector_store.id 一致。</p>
     *
     * <h3>幂等保护</h3>
     * <p>检查 kbId 是否已有索引数据：若存在说明是重试/重复触发，
     * 先走 {@link #deleteAllByKbId(Long)} 清空旧数据再建新索引，
     * 避免 df 和 kb_stats 重复累加导致统计失真。</p>
     *
     * <h3>两阶段写入</h3>
     * <ol>
     *   <li><b>内存聚合</b>：遍历所有 chunk，分词→累加 tf/df/stats 到内存 Map，不做任何 DB 调用</li>
     *   <li><b>批量落库</b>：term_freq + doc_freq + kb_stats 各一次批量 SQL，共 3 次数据库往返</li>
     * </ol>
     *
     * @param kbId   知识库 ID
     * @param chunks 文档 chunk 列表（每个 chunk 必须已设置唯一 ID）
     */
    @Transactional(rollbackFor = Exception.class)
    public void indexChunks(Long kbId, List<Document> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            log.warn("chunk 列表为空，跳过 BM25 索引构建: kbId={}", kbId);
            return;
        }

        // ========== 幂等保护：已有旧索引 → 先清再建 ==========

        if (repository.hasIndexData(kbId)) {
            log.warn("kbId={} 已存在 BM25 索引数据（疑似重试/重复触发），先清除再重建", kbId);
            deleteAllByKbId(kbId);
        }

        log.info("开始构建 BM25 索引: kbId={}, chunk 数={}", kbId, chunks.size());

        // ========== 阶段1：内存聚合（0 次 DB 调用） ==========

        List<BM25Repository.TermFreqEntry> allEntries = new ArrayList<>();

        // term → 出现在多少个 chunk 中（用于 df 批量更新）
        Map<String, Integer> termChunkCount = new HashMap<>();

        int validChunks = 0;    // 成功分词的非空 chunk 数
        long totalTerms = 0;    // 所有 chunk 的 term 总数（用于 avgdl）
        int emptyChunks = 0;    // 空内容/无有效 term 的 chunk 数

        for (Document chunk : chunks) {
            String chunkId = chunk.getId();
            if (chunkId == null || chunkId.isBlank()) {
                log.warn("chunk ID 为空，跳过: kbId={}", kbId);
                continue;
            }

            String content = chunk.getText();
            if (content == null || content.isBlank()) {
                log.debug("chunk 内容为空: chunkId={}", chunkId);
                emptyChunks++;
                continue;
            }

            // 1. 分词
            List<String> terms = tokenizer.tokenize(content);
            if (terms.isEmpty()) {
                log.debug("分词后无有效 term: chunkId={}", chunkId);
                emptyChunks++;
                continue;
            }

            // 2. 统计 tf（词频）
            Map<String, Long> tfMap = terms.stream()
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()));

            // 3. 收集 term_freq 条目
            tfMap.forEach((term, tf) ->
                allEntries.add(new BM25Repository.TermFreqEntry(kbId, chunkId, term, tf.intValue()))
            );

            // 4. 累加 df 计数：每个 unique term 在当前 chunk 出现过 → chunk 计数 +1
            tfMap.keySet().forEach(term ->
                termChunkCount.merge(term, 1, Integer::sum)
            );

            // 5. 累加 stats
            validChunks++;
            totalTerms += terms.size();
        }

        int totalChunksProcessed = validChunks + emptyChunks;

        // ========== 阶段2：一次性批量落库（最多 3 次 DB 往返） ==========

        if (!allEntries.isEmpty()) {
            repository.batchInsertTermFreq(allEntries);       // 1 次
        }
        if (!termChunkCount.isEmpty()) {
            repository.incrementDfBatchWithCounts(kbId, termChunkCount); // 1 次
        }
        if (totalChunksProcessed > 0) {
            // avgdl = totalTerms / totalChunksProcessed（空 chunk 算进总 chunk 数，term=0 拉低均值）
            repository.batchUpdateKbStats(kbId, totalChunksProcessed, totalTerms);// 1 次
        }

        log.info("BM25 索引构建完成: kbId={}, 总 chunk={}, 有效={}, 空={}, 总词条={}",
            kbId, chunks.size(), validChunks, emptyChunks, allEntries.size());
    }

    /**
     * 删除指定知识库的所有 BM25 索引数据
     * <p>与 {@link interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService#deleteByKnowledgeBaseId(Long)}
     * 配合使用，确保向量数据和 BM25 索引同步删除。</p>
     *
     * @param kbId 知识库 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteAllByKbId(Long kbId) {
        log.info("开始删除 BM25 索引: kbId={}", kbId);

        // 1. 先查出被删除的数据涉及哪些 term，各需要减多少 df
        List<BM25Repository.TermDfDelta> deltas = repository.deleteTermFreqByKbId(kbId);

        // 2. 同步减少 df
        repository.decrementDfBatch(kbId, deltas);

        // 3. 删除 kb_stats
        repository.deleteKbStats(kbId);

        log.info("BM25 索引删除完成: kbId={}, 涉及 term 数={}", kbId, deltas.size());
    }
}
