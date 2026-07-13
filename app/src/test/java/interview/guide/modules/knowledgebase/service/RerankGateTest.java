package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.bm25.HybridConfigProperties;
import interview.guide.modules.knowledgebase.bm25.HybridSearchService;
import interview.guide.modules.knowledgebase.rerank.DashScopeRerankService;
import interview.guide.modules.knowledgebase.rerank.RerankConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Rerank 闸门 + RRF 排序 + fetchDocumentTextsByIds 纯逻辑单测
 *
 * <p>验证 {@link KnowledgeBaseQueryService} 中三个核心方法的逻辑正确性：
 * <ul>
 *   <li>{@code shouldRerank} — 三道闸门判断（接口级开关 > 全局开关 > 场景自动跳过）</li>
 *   <li>{@code orderByRrf} — 按 RRF 分数显式降序排序（不依赖 SQL 返回顺序）</li>
 *   <li>{@code fetchDocumentTextsByIds} — 从 HybridHit 提取 chunkId 并批量查询</li>
 * </ul>
 *
 * <p>纯 Mockito 单测，不依赖数据库 / 向量库 / AI API。
 */
@DisplayName("Rerank 闸门 + RRF 排序 + fetchDocumentTextsByIds 单测")
class RerankGateTest {

    private KnowledgeBaseQueryService queryService;

    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private KnowledgeBaseVectorService vectorService;
    @Mock private KnowledgeBaseListService listService;
    @Mock private KnowledgeBaseCountService countService;
    @Mock private HybridSearchService hybridSearchService;
    @Mock private DashScopeRerankService rerankService;

    private HybridConfigProperties hybridConfig;
    private RerankConfigProperties rerankConfig;

    @BeforeEach
    void setUp() throws IOException {
        MockitoAnnotations.openMocks(this);

        hybridConfig = new HybridConfigProperties();
        rerankConfig = new RerankConfigProperties();

        // Mock Resource 返回简单模板
        Resource systemPrompt = mock(Resource.class);
        when(systemPrompt.getContentAsString(StandardCharsets.UTF_8)).thenReturn("system");
        Resource userPrompt = mock(Resource.class);
        when(userPrompt.getContentAsString(StandardCharsets.UTF_8)).thenReturn("user {context} {question}");
        Resource rewritePrompt = mock(Resource.class);
        when(rewritePrompt.getContentAsString(StandardCharsets.UTF_8)).thenReturn("rewrite {question}");

        when(chatClientBuilder.build()).thenReturn(chatClient);

        queryService = new KnowledgeBaseQueryService(
            chatClientBuilder, vectorService, listService, countService,
            systemPrompt, userPrompt, rewritePrompt,
            true, 4, 20, 12, 8, 0.18, 0.28,
            hybridSearchService, rerankService, hybridConfig, rerankConfig
        );

        // 手动调用 @PostConstruct 方法初始化 compiledSkipPatterns + 配置校验
        queryService.validateRerankConfig();
    }

    // ==================== shouldRerank 闸门测试 ====================

    @Nested
    @DisplayName("shouldRerank 三道闸门判断")
    class ShouldRerankTests {

        @Test
        @DisplayName("Case 1: 全部通过 → true（长问题 + 候选充足 + 开关开启）")
        void testAllPass() {
            // "JVM 调优参数详解" 去空格后 8 字 > 6，含中文不匹配 skipPattern
            assertTrue(queryService.shouldRerank("JVM 调优参数详解", 20, null));
        }

        @Test
        @DisplayName("Case 2: 闸门1 — 全局开关关闭 → false")
        void testGate1GlobalDisabled() {
            rerankConfig.setEnabled(false);
            assertFalse(queryService.shouldRerank("操作系统进程调度算法详解", 20, null));
        }

        @Test
        @DisplayName("Case 3: 闸门2 — 短问题 'GC'（2字 ≤ 6）→ false")
        void testGate2ShortQueryGC() {
            assertFalse(queryService.shouldRerank("GC", 20, null));
        }

        @Test
        @DisplayName("Case 4: 闸门2 — 短问题 '什么是进程'（5字 ≤ 6）→ false")
        void testGate2ShortQueryChinese() {
            assertFalse(queryService.shouldRerank("什么是进程", 20, null));
        }

        @Test
        @DisplayName("Case 5: 闸门2 — skipPattern 正则命中 'GARBAGE'（7字 > 6，匹配 ^[A-Za-z0-9]{1,10}$）→ false")
        void testGate2SkipPattern() {
            // "GARBAGE" 7 字 > shortQueryThreshold=6，不是短问题
            // 但匹配 skipPattern ^[A-Za-z0-9]{1,10}$ → 跳过
            assertFalse(queryService.shouldRerank("GARBAGE", 20, null));
        }

        @Test
        @DisplayName("Case 6: 闸门2 — 候选数不足（3 ≤ 8）→ false")
        void testGate2InsufficientCandidates() {
            assertFalse(queryService.shouldRerank("操作系统进程调度算法详解", 3, null));
        }

        @Test
        @DisplayName("Case 7: 闸门3 — 接口级强制关闭 → false")
        void testGate3ForceDisable() {
            assertFalse(queryService.shouldRerank("操作系统进程调度算法详解", 20, Boolean.FALSE));
        }

        @Test
        @DisplayName("Case 8: 闸门3 — 强制开启，跳过场景判断（短问题 'GC' 也能精排）→ true")
        void testGate3ForceEnableSkipScene() {
            // "GC" 是短问题，但 userOverride=true 跳过闸门2 的场景判断
            assertTrue(queryService.shouldRerank("GC", 20, Boolean.TRUE));
        }

        @Test
        @DisplayName("Case 9: 闸门3 — 强制开启但候选不足 → false（候选门槛是硬性条件）")
        void testGate3ForceEnableButInsufficient() {
            assertFalse(queryService.shouldRerank("GC", 3, Boolean.TRUE));
        }

        @Test
        @DisplayName("Case 10: 闸门3 — 强制开启，跳过 skipPattern（'GARBAGE' 也能精排）→ true")
        void testGate3ForceEnableSkipPattern() {
            assertTrue(queryService.shouldRerank("GARBAGE", 20, Boolean.TRUE));
        }
    }

    // ==================== orderByRrf 排序测试 ====================

    @Nested
    @DisplayName("orderByRrf 按 RRF 分数显式排序")
    class OrderByRrfTests {

        @Test
        @DisplayName("Case 1: 3 个 HybridHit（rrfScore 乱序）→ 输出按 rrfScore 降序")
        void testOrderByRrfDesc() {
            List<HybridSearchService.HybridHit> hits = List.of(
                new HybridSearchService.HybridHit("chunk-1", 0.015),
                new HybridSearchService.HybridHit("chunk-2", 0.032),
                new HybridSearchService.HybridHit("chunk-3", 0.008)
            );
            List<Document> docs = List.of(
                createDoc("chunk-1", "文档1"),
                createDoc("chunk-2", "文档2"),
                createDoc("chunk-3", "文档3")
            );

            List<Document> result = queryService.orderByRrf(docs, hits, 5);

            assertEquals(3, result.size());
            // rrfScore 降序: chunk-2(0.032) > chunk-1(0.015) > chunk-3(0.008)
            assertEquals("chunk-2", result.get(0).getId());
            assertEquals("chunk-1", result.get(1).getId());
            assertEquals("chunk-3", result.get(2).getId());
        }

        @Test
        @DisplayName("Case 2: 5 个 HybridHit，topN=3 → 只返回 rrfScore 最高的 3 条")
        void testOrderByRrfTopN() {
            List<HybridSearchService.HybridHit> hits = List.of(
                new HybridSearchService.HybridHit("c1", 0.01),
                new HybridSearchService.HybridHit("c2", 0.05),
                new HybridSearchService.HybridHit("c3", 0.03),
                new HybridSearchService.HybridHit("c4", 0.02),
                new HybridSearchService.HybridHit("c5", 0.04)
            );
            List<Document> docs = List.of(
                createDoc("c1", "d1"), createDoc("c2", "d2"), createDoc("c3", "d3"),
                createDoc("c4", "d4"), createDoc("c5", "d5")
            );

            List<Document> result = queryService.orderByRrf(docs, hits, 3);

            assertEquals(3, result.size());
            // 降序 top3: c2(0.05) > c5(0.04) > c3(0.03)
            assertEquals("c2", result.get(0).getId());
            assertEquals("c5", result.get(1).getId());
            assertEquals("c3", result.get(2).getId());
        }

        @Test
        @DisplayName("Case 3: Document 列表顺序与 HybridHit 顺序打乱 → 输出仍按 rrfScore 正确映射")
        void testOrderByRrfShuffledDocs() {
            // hits 顺序: c1(高分), c2(中), c3(低)
            List<HybridSearchService.HybridHit> hits = List.of(
                new HybridSearchService.HybridHit("c1", 0.05),
                new HybridSearchService.HybridHit("c2", 0.03),
                new HybridSearchService.HybridHit("c3", 0.01)
            );
            // docs 顺序故意打乱: c3, c1, c2（模拟 SQL WHERE IN 不保证顺序）
            List<Document> docs = List.of(
                createDoc("c3", "文档3"),
                createDoc("c1", "文档1"),
                createDoc("c2", "文档2")
            );

            List<Document> result = queryService.orderByRrf(docs, hits, 5);

            assertEquals(3, result.size());
            // 按 rrfScore 降序映射，不依赖 docs 的传入顺序
            assertEquals("c1", result.get(0).getId());
            assertEquals("c2", result.get(1).getId());
            assertEquals("c3", result.get(2).getId());
        }

        @Test
        @DisplayName("Case 4: HybridHit 中有 chunkId 在 Document 列表找不到 → 被跳过")
        void testOrderByRrfMissingDoc() {
            List<HybridSearchService.HybridHit> hits = List.of(
                new HybridSearchService.HybridHit("c1", 0.05),
                new HybridSearchService.HybridHit("c-missing", 0.03),
                new HybridSearchService.HybridHit("c2", 0.01)
            );
            // docs 只有 c1 和 c2，没有 c-missing
            List<Document> docs = List.of(
                createDoc("c1", "文档1"),
                createDoc("c2", "文档2")
            );

            List<Document> result = queryService.orderByRrf(docs, hits, 5);

            assertEquals(2, result.size());
            assertEquals("c1", result.get(0).getId());
            assertEquals("c2", result.get(1).getId());
        }

        @Test
        @DisplayName("Case 5: 空 hits → 返回空列表")
        void testOrderByRrfEmptyHits() {
            List<Document> docs = List.of(createDoc("c1", "d1"));
            assertTrue(queryService.orderByRrf(docs, List.of(), 5).isEmpty());
        }

        @Test
        @DisplayName("Case 6: 空 docs → 返回空列表")
        void testOrderByRrfEmptyDocs() {
            List<HybridSearchService.HybridHit> hits = List.of(
                new HybridSearchService.HybridHit("c1", 0.05)
            );
            assertTrue(queryService.orderByRrf(List.of(), hits, 5).isEmpty());
        }
    }

    // ==================== fetchDocumentTextsByIds 测试 ====================

    @Nested
    @DisplayName("fetchDocumentTextsByIds 从 HybridHit 提取 chunkId 并批量查询")
    class FetchDocumentTextsByIdsTests {

        @Test
        @DisplayName("正常: 3 个 HybridHit → 提取 chunkId 调用 vectorService.findByIds")
        void testFetchBasic() {
            List<HybridSearchService.HybridHit> hits = List.of(
                new HybridSearchService.HybridHit("uuid-1", 0.05),
                new HybridSearchService.HybridHit("uuid-2", 0.03),
                new HybridSearchService.HybridHit("uuid-3", 0.01)
            );
            List<Document> mockDocs = List.of(
                createDoc("uuid-1", "进程是程序的执行实例"),
                createDoc("uuid-2", "线程是进程内的执行单元"),
                createDoc("uuid-3", "协程是用户态轻量级线程")
            );
            when(vectorService.findByIds(anyList())).thenReturn(mockDocs);

            List<Document> result = queryService.fetchDocumentTextsByIds(hits);

            assertEquals(3, result.size());
            verify(vectorService, times(1)).findByIds(List.of("uuid-1", "uuid-2", "uuid-3"));
        }

        @Test
        @DisplayName("空 hits → 返回空列表，不调 vectorService")
        void testFetchEmptyHits() {
            assertTrue(queryService.fetchDocumentTextsByIds(List.of()).isEmpty());
            verify(vectorService, never()).findByIds(anyList());
        }

        @Test
        @DisplayName("null hits → 返回空列表")
        void testFetchNullHits() {
            assertTrue(queryService.fetchDocumentTextsByIds(null).isEmpty());
            verify(vectorService, never()).findByIds(anyList());
        }
    }

    // ==================== @PostConstruct 配置校验测试 ====================

    @Nested
    @DisplayName("@PostConstruct 配置校验")
    class ConfigValidationTests {

        @Test
        @DisplayName("非法组合 hybrid=false && rerank=true → 抛异常拒绝启动")
        void testIllegalConfigThrows() throws IOException {
            hybridConfig.setEnabled(false);
            rerankConfig.setEnabled(true);

            // 重新构造 Service（不调用 validateRerankConfig）
            Resource sp = mock(Resource.class);
            when(sp.getContentAsString(StandardCharsets.UTF_8)).thenReturn("s");
            Resource up = mock(Resource.class);
            when(up.getContentAsString(StandardCharsets.UTF_8)).thenReturn("u");
            Resource rp = mock(Resource.class);
            when(rp.getContentAsString(StandardCharsets.UTF_8)).thenReturn("r");

            KnowledgeBaseQueryService service = new KnowledgeBaseQueryService(
                chatClientBuilder, vectorService, listService, countService,
                sp, up, rp, true, 4, 20, 12, 8, 0.18, 0.28,
                hybridSearchService, rerankService, hybridConfig, rerankConfig
            );

            assertThrows(IllegalStateException.class, service::validateRerankConfig);
        }

        @Test
        @DisplayName("合法组合 hybrid=false && rerank=false → 正常启动")
        void testLegalConfigBothDisabled() throws IOException {
            hybridConfig.setEnabled(false);
            rerankConfig.setEnabled(false);

            Resource sp = mock(Resource.class);
            when(sp.getContentAsString(StandardCharsets.UTF_8)).thenReturn("s");
            Resource up = mock(Resource.class);
            when(up.getContentAsString(StandardCharsets.UTF_8)).thenReturn("u");
            Resource rp = mock(Resource.class);
            when(rp.getContentAsString(StandardCharsets.UTF_8)).thenReturn("r");

            KnowledgeBaseQueryService service = new KnowledgeBaseQueryService(
                chatClientBuilder, vectorService, listService, countService,
                sp, up, rp, true, 4, 20, 12, 8, 0.18, 0.28,
                hybridSearchService, rerankService, hybridConfig, rerankConfig
            );

            assertDoesNotThrow(service::validateRerankConfig);
        }

        @Test
        @DisplayName("合法组合 hybrid=true && rerank=true → 正常启动")
        void testLegalConfigBothEnabled() {
            // setUp 中已默认 hybrid=true, rerank=true，validateRerankConfig 已调用成功
            // 如果能走到这里说明没抛异常
            assertNotNull(queryService);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建带指定 id 和 text 的 Document
     */
    private Document createDoc(String id, String text) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kb_id", "1");
        return Document.builder().id(id).text(text).metadata(metadata).build();
    }
}
