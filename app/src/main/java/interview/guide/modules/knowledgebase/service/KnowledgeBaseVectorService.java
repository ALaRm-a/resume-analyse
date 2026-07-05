package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.bm25.BM25IndexService;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.util.RecursiveCharacterSplitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库向量存储服务
 * 负责文档分块、向量化和检索
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {
    
    /**
     * 阿里云 DashScope Embedding API 批量大小限制
     */
    private static final int MAX_BATCH_SIZE = 10;

    /**
     * 每个chunk的目标字符数（约等价于原 300 token）
     */
    static final int CHUNK_SIZE_CHARS = 500;

    /**
     * 相邻chunk之间的重叠字符数，防止语义在切分边界处中断
     */
    static final int CHUNK_OVERLAP_CHARS = 50;

    /**
     * 单次拆分允许生成的最大chunk数（防止无限制循环）
     */
    static final int MAX_NUM_CHUNKS = 10000;

    private final VectorStore vectorStore;
    private final VectorRepository vectorRepository;
    private final BM25IndexService bm25IndexService;
    private final RecursiveCharacterSplitter splitter;

    public KnowledgeBaseVectorService(VectorStore vectorStore,
                                       VectorRepository vectorRepository,
                                       BM25IndexService bm25IndexService) {
        this.vectorStore = vectorStore;
        this.vectorRepository = vectorRepository;
        this.bm25IndexService = bm25IndexService;
        this.splitter = new RecursiveCharacterSplitter(CHUNK_SIZE_CHARS, CHUNK_OVERLAP_CHARS, MAX_NUM_CHUNKS);
    }
    /**
     * 将知识库内容向量化并存储
     * @param knowledgeBaseId 知识库ID
     * @param content 知识库文本内容
     */
    @Transactional
    public void vectorizeAndStore(Long knowledgeBaseId, String content) {
        log.info("开始向量化知识库: kbId={}, contentLength={}", knowledgeBaseId, content.length());
        try {
            // 1. 先删除该知识库的旧向量数据
            deleteByKnowledgeBaseId(knowledgeBaseId);
            
            // 2. 递归字符拆分 + 预分配 UUID + metadata（一步到位）
            // Document 没有 setId()，只能通过 Builder 创建时就带上 id，
            // 确保 BM25 索引和向量数据共用同一个 chunk_id
            List<String> splitTexts = splitter.split(content);
            List<Document> chunks = splitTexts.stream()
                    .map(text -> {
                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("kb_id", knowledgeBaseId.toString());
                        return Document.builder()
                                .id(UUID.randomUUID().toString())
                                .text(text)
                                .metadata(metadata)
                                .build();
                    })
                    .collect(Collectors.toList());

            log.info("文本分块完成: {} 个chunks (chunkSize={}字符, overlap={}字符)",
                    chunks.size(), CHUNK_SIZE_CHARS, CHUNK_OVERLAP_CHARS);

            // 4. 构建 BM25 倒排索引（基于已分配 UUID 的 chunk）
            try {
                bm25IndexService.indexChunks(knowledgeBaseId, chunks);
            } catch (Exception e) {
                log.error("BM25 索引构建失败，不影响向量化继续: kbId={}, error={}",
                    knowledgeBaseId, e.getMessage(), e);
                // BM25 失败不阻塞向量化——降级运行，后续可补建
            }

            // 5. 分批向量化并存储（阿里云 DashScope API 限制 batch size <= 10）
            int totalChunks = chunks.size();
            int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE; // 向上取整
            log.info("开始分批向量化: 总共 {} 个chunks，分 {} 批处理，每批最多 {} 个",
                    totalChunks, batchCount, MAX_BATCH_SIZE);
            for (int i = 0; i < batchCount; i++) {
                int start = i * MAX_BATCH_SIZE;
                int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
                List<Document> batch = chunks.subList(start, end);
                log.debug("处理第 {}/{} 批: chunks {}-{}", i + 1, batchCount, start + 1, end);
                vectorStore.add(batch);
            }
            log.info("知识库向量化完成: kbId={}, chunks={}, batches={}",
                    knowledgeBaseId, totalChunks, batchCount);
        } catch (Exception e) {
            log.error("向量化知识库失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            throw new RuntimeException("向量化知识库失败: " + e.getMessage(), e);
        }
    }
    /**
     * 基于多个知识库进行相似度搜索
     * 
     * @param query 查询文本
     * @param knowledgeBaseIds 知识库ID列表（如果为空则搜索所有）
     * @param topK 返回top K个结果
     * @return 相关文档列表
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("向量相似度搜索: query={}, kbIds={}, topK={}, minScore={}",
            query, knowledgeBaseIds, topK, minScore);
        
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1));

            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) {
                return List.of();
            }
            
            log.info("搜索完成: 找到 {} 个相关文档", results.size());
            return results;
            
        } catch (Exception e) {
            log.warn("向量搜索前置过滤失败，回退到本地过滤: {}", e.getMessage());
            return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore);
        }
    }

    private List<Document> similaritySearchFallback(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        try {
            // 回退检索仍保留 topK/minScore，避免兜底路径引入过多弱相关命中
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK * 3, topK));
            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            List<Document> allResults = vectorStore.similaritySearch(builder.build());
            if (allResults == null || allResults.isEmpty()) {
                return List.of();
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                allResults = allResults.stream()
                    .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
                    .collect(Collectors.toList());
            }

            List<Document> results = allResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("回退检索完成: 找到 {} 个相关文档", results.size());
            return results;
        } catch (Exception e) {
            log.error("向量搜索失败: {}", e.getMessage(), e);
            throw new RuntimeException("向量搜索失败: " + e.getMessage(), e);
        }
    }

    private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
        Object kbId = doc.getMetadata().get("kb_id");
        if (kbId == null) {
            return false;
        }
        try {
            Long kbIdLong = kbId instanceof Long
                ? (Long) kbId
                : Long.parseLong(kbId.toString());
            return knowledgeBaseIds.contains(kbIdLong);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
        String values = knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .map(id -> "'" + id + "'")
            .collect(Collectors.joining(", "));
        return "kb_id in [" + values + "]";
    }
    
    /**
     * 删除指定知识库的所有向量数据和 BM25 索引
     * 
     * @param knowledgeBaseId 知识库ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        // 1. 删除向量数据
        try {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除向量数据失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
        }

        // 2. 删除 BM25 索引数据（df 同步扣减 + kb_stats 清理）
        try {
            bm25IndexService.deleteAllByKbId(knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除 BM25 索引失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
        }
    }
}

