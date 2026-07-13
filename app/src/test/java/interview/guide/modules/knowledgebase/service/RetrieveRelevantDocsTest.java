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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * retrieveRelevantDocs 三级路径选择测试
 *
 * <p>验证三级分层检索的路径选择逻辑：
 * <ul>
 *   <li>Level 1：hybrid=false → 纯向量检索</li>
 *   <li>Level 2：hybrid=true, rerank 关闭/跳过 → 混合检索 + RRF</li>
 *   <li>Level 3：hybrid=true, rerank 开启且闸门通过 → 混合检索 + RRF + Rerank</li>
 * </ul>
 *
 * <p>纯 Mockito 单测，通过 verify 验证各依赖的调用情况来判断走了哪条路径。
 */
@DisplayName("retrieveRelevantDocs 三级路径选择测试")
class RetrieveRelevantDocsTest {

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
        // 初始化 Mockito 注解，否则 @Mock 注解的字段不会被 Mockito 注入
        MockitoAnnotations.openMocks(this);

        // 初始化配置
        hybridConfig = new HybridConfigProperties();
        rerankConfig = new RerankConfigProperties();

        // 初始化模板文件，当触发什么函数是返回什么内容，这里简化了提示词的加载
        Resource sp = mock(Resource.class);
        when(sp.getContentAsString(StandardCharsets.UTF_8)).thenReturn("s");
        Resource up = mock(Resource.class);
        when(up.getContentAsString(StandardCharsets.UTF_8)).thenReturn("u");
        Resource rp = mock(Resource.class);
        when(rp.getContentAsString(StandardCharsets.UTF_8)).thenReturn("r");

        when(chatClientBuilder.build()).thenReturn(chatClient);

        // 初始化 queryService
        queryService = new KnowledgeBaseQueryService(
            chatClientBuilder, vectorService, listService, countService,
            sp, up, rp, true, 4, 20, 12, 8, 0.18, 0.28,
            hybridSearchService, rerankService, hybridConfig, rerankConfig
        );
        queryService.validateRerankConfig();
    }

    @Nested
    @DisplayName("Level 1：纯向量检索（hybrid=false）")
    class Level1Tests {

        @Test
        @DisplayName("hybrid=false → 调用 vectorService.similaritySearch，不调 hybridSearchService")
        void testLevel1PureVector() {
            hybridConfig.setEnabled(false);
            rerankConfig.setEnabled(false);
            queryService.validateRerankConfig();

            when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(List.of(createDoc("c1", "进程是程序的执行实例")));

            var ctx = new KnowledgeBaseQueryService.QueryContext("test",
                List.of("操作系统进程调度算法详解"),
                new KnowledgeBaseQueryService.SearchParams(5, 0.28));

            List<Document> result = queryService.retrieveRelevantDocs(ctx, List.of(1L), null);

            // assertFalse 判断结果和对象的内容是否相等
            assertFalse(result.isEmpty());
            // verify判断的是某个方法的调用次数
            verify(vectorService).similaritySearch(anyString(), anyList(), anyInt(), anyDouble());
            verify(hybridSearchService, never()).search(any(), any(), anyInt(), anyInt(), anyInt());
            verify(rerankService, never()).rerank(anyString(), anyList(), anyInt());
        }

        @Test
        @DisplayName("Level 1: 候选 query 为空 → 返回空列表")
        void testLevel1EmptyQuery() {
            hybridConfig.setEnabled(false);
            rerankConfig.setEnabled(false);
            queryService.validateRerankConfig();

            var ctx = new KnowledgeBaseQueryService.QueryContext("test",
                List.of(""),
                new KnowledgeBaseQueryService.SearchParams(5, 0.28));

            List<Document> result = queryService.retrieveRelevantDocs(ctx, List.of(1L), null);

            assertTrue(result.isEmpty());
            verify(vectorService, never()).similaritySearch(anyString(), anyList(), anyInt(), anyDouble());
        }
    }

    @Nested
    @DisplayName("Level 2：混合检索 + RRF（rerank 关闭或闸门跳过）")
    class Level2Tests {

        @Test
        @DisplayName("hybrid=true, rerank=false → 调用 hybridSearch + findByIds，不调 rerankService")
        void testLevel2RerankDisabled() {
            rerankConfig.setEnabled(false);
            queryService.validateRerankConfig();

            List<HybridSearchService.HybridHit> hits = List.of(
                new HybridSearchService.HybridHit("c1", 0.05),
                new HybridSearchService.HybridHit("c2", 0.03)
            );
            when(hybridSearchService.search(anyString(), anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(hits);
            when(vectorService.findByIds(anyList()))
                .thenReturn(List.of(createDoc("c1", "d1"), createDoc("c2", "d2")));

            var ctx = new KnowledgeBaseQueryService.QueryContext("test",
                List.of("操作系统进程调度算法详解"),
                new KnowledgeBaseQueryService.SearchParams(5, 0.28));

            List<Document> result = queryService.retrieveRelevantDocs(ctx, List.of(1L), null);

            assertFalse(result.isEmpty());
            // 验证走 Level 2：调了 hybridSearch + findByIds，没调 rerankService
            verify(hybridSearchService).search(anyString(), anyList(), anyInt(), anyInt(), anyInt());
            verify(vectorService).findByIds(anyList());
            verify(rerankService, never()).rerank(anyString(), anyList(), anyInt());
        }

        @Test
        @DisplayName("hybrid=true, rerank=true, 短问题 → 闸门跳过 rerank，走 Level 2")
        void testLevel2ShortQuerySkipRerank() {
            // "GC" 是短问题（2字 ≤ 6），闸门2 跳过 rerank
            List<HybridSearchService.HybridHit> hits = List.of(
                new HybridSearchService.HybridHit("c1", 0.05)
            );
            when(hybridSearchService.search(anyString(), anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(hits);
            when(vectorService.findByIds(anyList()))
                .thenReturn(List.of(createDoc("c1", "GC 是垃圾回收")));

            var ctx = new KnowledgeBaseQueryService.QueryContext("test",
                List.of("GC"),
                new KnowledgeBaseQueryService.SearchParams(5, 0.28));

            List<Document> result = queryService.retrieveRelevantDocs(ctx, List.of(1L), null);

            assertFalse(result.isEmpty());
            verify(hybridSearchService).search(anyString(), anyList(), anyInt(), anyInt(), anyInt());
            verify(rerankService, never()).rerank(anyString(), anyList(), anyInt());
        }

        @Test
        @DisplayName("hybrid=true, rerank=true, 候选不足 → 闸门跳过 rerank，走 Level 2")
        void testLevel2InsufficientCandidates() {
            // 候选数 3 ≤ minCandidatesForRerank=8
            List<HybridSearchService.HybridHit> hits = List.of(
                new HybridSearchService.HybridHit("c1", 0.05),
                new HybridSearchService.HybridHit("c2", 0.03),
                new HybridSearchService.HybridHit("c3", 0.01)
            );
            when(hybridSearchService.search(anyString(), anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(hits);
            when(vectorService.findByIds(anyList()))
                .thenReturn(List.of(createDoc("c1", "d1"), createDoc("c2", "d2"), createDoc("c3", "d3")));

            var ctx = new KnowledgeBaseQueryService.QueryContext("test",
                List.of("操作系统进程调度算法详解"),
                new KnowledgeBaseQueryService.SearchParams(5, 0.28));

            List<Document> result = queryService.retrieveRelevantDocs(ctx, List.of(1L), null);

            assertFalse(result.isEmpty());
            verify(rerankService, never()).rerank(anyString(), anyList(), anyInt());
        }

        @Test
        @DisplayName("hybrid=true, userOverride=false → 强制跳过 rerank，走 Level 2")
        void testLevel2ForceDisableRerank() {
            List<HybridSearchService.HybridHit> hits = List.of(
                new HybridSearchService.HybridHit("c1", 0.05)
            );
            //  调用以下方法的时候设置默认的返回值，主要是测试方法能不能走通
            when(hybridSearchService.search(anyString(), anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(hits);
            when(vectorService.findByIds(anyList()))
                .thenReturn(List.of(createDoc("c1", "d1")));

            var ctx = new KnowledgeBaseQueryService.QueryContext("test",
                List.of("操作系统进程调度算法详解"),
                new KnowledgeBaseQueryService.SearchParams(5, 0.28));

            List<Document> result = queryService.retrieveRelevantDocs(ctx, List.of(1L), Boolean.FALSE);

            assertFalse(result.isEmpty());
            verify(rerankService, never()).rerank(anyString(), anyList(), anyInt());
        }

        @Test
        @DisplayName("hybrid=true, 混合检索返回空 → 返回空列表")
        void testLevel2EmptyHits() {
            when(hybridSearchService.search(anyString(), anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of());

            var ctx = new KnowledgeBaseQueryService.QueryContext("test",
                List.of("操作系统进程调度算法详解"),
                new KnowledgeBaseQueryService.SearchParams(5, 0.28));

            List<Document> result = queryService.retrieveRelevantDocs(ctx, List.of(1L), null);

            assertTrue(result.isEmpty());
            verify(rerankService, never()).rerank(anyString(), anyList(), anyInt());
        }
    }

    @Nested
    @DisplayName("Level 3：混合检索 + RRF + Rerank")
    class Level3Tests {

        @Test
        @DisplayName("hybrid=true, rerank=true, 候选充足, 长问题 → 调用 rerankService.rerank")
        void testLevel3FullPipeline() {
            // 10 个候选 > minCandidatesForRerank=8，长问题不跳过
            List<HybridSearchService.HybridHit> hits = createHits(10);
            when(hybridSearchService.search(anyString(), anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(hits);
            when(vectorService.findByIds(anyList()))
                .thenReturn(createDocs(10));
            when(rerankService.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(createDoc("c5", "最相关")));

            var ctx = new KnowledgeBaseQueryService.QueryContext("test",
                List.of("操作系统进程调度算法详解"),
                new KnowledgeBaseQueryService.SearchParams(5, 0.28));

            List<Document> result = queryService.retrieveRelevantDocs(ctx, List.of(1L), null);

            assertFalse(result.isEmpty());
            // 验证走 Level 3：三个都调了
            verify(hybridSearchService).search(anyString(), anyList(), anyInt(), anyInt(), anyInt());
            verify(vectorService).findByIds(anyList());
            verify(rerankService).rerank(anyString(), anyList(), anyInt());
        }

        @Test
        @DisplayName("hybrid=true, userOverride=true, 短问题 → 强制精排，跳过场景判断")
        void testLevel3ForceEnableShortQuery() {
            // "GC" 是短问题，但 userOverride=true 跳过闸门2
            // 候选 10 > 8 满足门槛
            List<HybridSearchService.HybridHit> hits = createHits(10);
            when(hybridSearchService.search(anyString(), anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(hits);
            when(vectorService.findByIds(anyList()))
                .thenReturn(createDocs(10));
            when(rerankService.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(createDoc("c1", "GC 相关")));

            var ctx = new KnowledgeBaseQueryService.QueryContext("test",
                List.of("GC"),
                new KnowledgeBaseQueryService.SearchParams(5, 0.28));

            List<Document> result = queryService.retrieveRelevantDocs(ctx, List.of(1L), Boolean.TRUE);

            assertFalse(result.isEmpty());
            verify(rerankService).rerank(anyString(), anyList(), anyInt());
        }

        @Test
        @DisplayName("hybrid=true, userOverride=true, 候选不足 → 不调 rerank（硬性条件）")
        void testLevel3ForceEnableButInsufficient() {
            // 候选 3 ≤ 8，即使强制开启也不走 rerank
            List<HybridSearchService.HybridHit> hits = createHits(3);
            when(hybridSearchService.search(anyString(), anyList(), anyInt(), anyInt(), anyInt()))
                .thenReturn(hits);
            when(vectorService.findByIds(anyList()))
                .thenReturn(createDocs(3));

            var ctx = new KnowledgeBaseQueryService.QueryContext("test",
                List.of("GC"),
                new KnowledgeBaseQueryService.SearchParams(5, 0.28));

            List<Document> result = queryService.retrieveRelevantDocs(ctx, List.of(1L), Boolean.TRUE);

            assertFalse(result.isEmpty());
            verify(rerankService, never()).rerank(anyString(), anyList(), anyInt());
        }
    }

    // ==================== 辅助方法 ====================

    private Document createDoc(String id, String text) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kb_id", "1");
        return Document.builder().id(id).text(text).metadata(metadata).build();
    }

    private List<HybridSearchService.HybridHit> createHits(int count) {
        java.util.List<HybridSearchService.HybridHit> list = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new HybridSearchService.HybridHit("c" + i, 0.1 - i * 0.01));
        }
        return list;
    }

    private List<Document> createDocs(int count) {
        java.util.List<Document> list = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(createDoc("c" + i, "文档内容 " + i));
        }
        return list;
    }
}
