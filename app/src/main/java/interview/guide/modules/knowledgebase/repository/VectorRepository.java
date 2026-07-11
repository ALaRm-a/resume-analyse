package interview.guide.modules.knowledgebase.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 向量存储Repository
 * 负责向量数据的增删改查操作
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class VectorRepository {
    
    private final JdbcTemplate jdbcTemplate;

    /**
     * 从 vector_store 读取指定知识库的所有 chunk 数据
     * <p>用于 BM25 回填：在已有向量数据的前提下，补建倒排索引</p>
     *
     * @param kbId 知识库 ID
     * @return (chunkId, content) 列表，与 vector_store.id 一致
     */
    public List<VectorChunk> readChunksByKbId(Long kbId) {
        String sql = """
            SELECT id, content
            FROM vector_store
            WHERE metadata->>'kb_id' = ?
            ORDER BY id
            """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, kbId.toString());
        List<VectorChunk> chunks = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            // vector_store.id 在 PostgreSQL 中是 UUID 类型，JDBC 返回 java.util.UUID
            Object idObj = row.get("id");
            String id = idObj != null ? idObj.toString() : null;
            String content = (String) row.get("content");
            if (id != null && content != null && !content.isBlank()) {
                chunks.add(new VectorChunk(id, content));
            }
        }
        log.info("从 vector_store 读取 chunk: kbId={}, 有效 chunk 数={}", kbId, chunks.size());
        return chunks;
    }

    /**
     * vector_store 中的 chunk 记录（仅 id + content，不包含 embedding）
     */
    public record VectorChunk(String id, String content) {}
    
    /**
     * 删除指定知识库的所有向量数据
     * 使用 SQL 直接删除，利用数据库索引和删除能力
     * <p>
     * Spring AI PgVectorStore 默认表名为 vector_store，元数据存储在 metadata 字段（JSONB类型）
     * 
     * @param knowledgeBaseId 知识库ID
     * @return 删除的行数
     */
    /**
     * 批量根据 chunk ID 查询文本内容
     *
     * @param chunkIds UUID 格式的 chunk ID 列表
     * @return id → content 的映射（保持插入顺序，不存在的 ID 不会出现在 map 中）
     */
    public Map<String, String> findByIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = chunkIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT id, content FROM vector_store WHERE CAST(id AS text) IN (" + placeholders + ")";

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, chunkIds.toArray());
        Map<String, String> result = new LinkedHashMap<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object idObj = row.get("id");
            String id = idObj != null ? idObj.toString() : null;
            String content = (String) row.get("content");
            if (id != null && content != null) {
                result.put(id, content);
            }
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public int deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        log.info("开始删除知识库向量数据: kbId={}", knowledgeBaseId);
        
        /* 
         * 注意：
         * 1. metadata 字段是 json 类型，不支持 jsonb_exists 函数。
         * 2. 使用 metadata->>'key' IS NOT NULL 来替代键存在性检查，这在 json/jsonb 下都有效。
         * 3. 这种写法完全避开了 PostgreSQL 的 '?' 操作符，不会引起 JDBC 占位符冲突。
         */
        String sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_id' = ?
               OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
            """;
        
        try {
            // 第一个参数转为 String 匹配 kb_id，第二个参数保持 Long 匹配 kb_id_long
            int deletedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), knowledgeBaseId);
            
            if (deletedRows > 0) {
                log.info("成功删除知识库向量数据: kbId={}, 删除行数={}", knowledgeBaseId, deletedRows);
            } else {
                log.info("未找到相关向量数据，无需删除: kbId={}", knowledgeBaseId);
            }
            
            return deletedRows;
            
        } catch (Exception e) {
            log.error("执行删除向量 SQL 失败: kbId={}, error={}", knowledgeBaseId, e.getMessage());
            // 抛出异常以触发事务回滚
            throw new RuntimeException("删除向量数据失败", e);
        }
    }    
}

