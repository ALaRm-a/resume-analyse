package interview.guide.modules.knowledgebase.bm25;

import interview.guide.modules.knowledgebase.util.BM25Tokenizer;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BM25 写入链路冒烟测试
 *
 * <p>验证四件事：
 * <ol>
 *   <li>分词器正确识别技术词典中的术语（不被错误切开）</li>
 *   <li>indexChunks 写入选通后，三张表都有数据且数量正确</li>
 *   <li>幂等保护：重复调用 indexChunks 不会重复计数</li>
 *   <li>deleteAllByKbId 后三张表全部清空</li>
 *   <li>【额外】回填已有 vector_store 数据到 BM25 索引（为评测准备数据）</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("BM25 写入链路冒烟测试")
class BM25WritePathTest {

    private static final Logger log = LoggerFactory.getLogger(BM25WritePathTest.class);

    /** 测试专用知识库 ID，确保不与已有数据冲突 */
    private static final Long TEST_KB_ID = 99999L;

    @Autowired
    private BM25Tokenizer tokenizer;

    @Autowired
    private BM25IndexService indexService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        if (indexService != null) {
            try {
                indexService.deleteAllByKbId(TEST_KB_ID);
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 测试1：分词正确性 ====================

    @Test
    @DisplayName("技术词典强制分词：复合术语不应被切开")
    void testTokenizerHandlesTechTerms() {
        log.info("========== 测试1: 技术词典强制分词 ==========");

        // "轻量级锁" 在 tech-terms.txt 中，不应被切成 "轻量级/锁"
        String text1 = "轻量级锁不实际上锁，自旋尝试获取锁";
        List<String> terms = tokenizer.tokenize(text1);
        log.info("输入: {}", text1);
        log.info("分词结果: {}", terms);
        assertThat(terms).as("复合词 '轻量级锁' 不应被切开").contains("轻量级锁");
        log.info("✅ '轻量级锁' 作为一个完整术语被保留");

        // "ConcurrentHashMap" 作为驼峰标识符应该保留
        String text2 = "ConcurrentHashMap的put方法";
        List<String> terms2 = tokenizer.tokenize(text2);
        log.info("输入: {}", text2);
        log.info("分词结果: {}", terms2);
        assertThat(terms2).as("驼峰标识符应保留").contains("ConcurrentHashMap");
        assertThat(terms2).as("英文方法名应保留").contains("put");
        log.info("✅ 'ConcurrentHashMap' 和 'put' 均正确保留");

        // "双亲委派机制" 不应被切散
        String text3 = "双亲委派机制保证类的唯一性";
        List<String> terms3 = tokenizer.tokenize(text3);
        log.info("输入: {}", text3);
        log.info("分词结果: {}", terms3);
        assertThat(terms3).as("复合词 '双亲委派机制' 不应被切散").contains("双亲委派机制");
        log.info("✅ '双亲委派机制' 作为一个完整术语被保留");

        log.info("========== 测试1 通过 ✓ ==========\n");
    }

    @Test
    @DisplayName("分词过滤：停用词/数字/单字应被过滤")
    void testTokenizerFiltersNoiseTokens() {
        log.info("========== 测试1b: 分词过滤 ==========");

        String text = "我的123个线程ABC";
        List<String> terms = tokenizer.tokenize(text);
        log.info("输入: {}", text);
        log.info("分词结果: {}", terms);

        // 应被过滤：的、我、123、个
        assertThat(terms).as("停用词和纯数字应被过滤")
            .doesNotContain("的", "我", "123", "个");
        log.info("✅ 停用词('的','我')、纯数字('123')、单字('个')已过滤");

        // 应保留
        assertThat(terms).as("有意义的词应保留").contains("线程");
        log.info("✅ '线程' 作为有效词被保留");

        log.info("========== 测试1b 通过 ✓ ==========\n");
    }

    // ==================== 测试2：三表写入正确性 ====================

    @Test
    @DisplayName("写入后三张表数据完整且数值正确")
    void testIndexChunksWritesAllThreeTables() {
        log.info("========== 测试2: 三表写入正确性 ==========");

        // 准备：2 个 chunk，其中 "轻量级锁" 在两个 chunk 都出现
        List<Document> chunks = List.of(
            createChunk("chunk-1", "轻量级锁不实际上锁，自旋尝试获取锁"),
            createChunk("chunk-2", "偏向锁撤销后变为轻量级锁，锁膨胀为重量级锁")
        );
        log.info("准备 {} 个测试 chunk, kbId={}", chunks.size(), TEST_KB_ID);

        // 写入
        indexService.indexChunks(TEST_KB_ID, chunks);
        log.info("📝 indexChunks 调用完成");

        // --- 验证 bm25_term_freq ---
        Integer tfRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM bm25_term_freq WHERE kb_id = ?", Integer.class, TEST_KB_ID);
        log.info("bm25_term_freq: 本 kb 共有 {} 行词频记录", tfRows);
        assertThat(tfRows).as("term_freq 应有数据").isGreaterThan(0);

        // 查询所有 term_freq 详情
        List<Map<String, Object>> tfAll = jdbcTemplate.queryForList(
            "SELECT chunk_id, term, tf FROM bm25_term_freq WHERE kb_id = ? ORDER BY term",
            TEST_KB_ID);
        log.info("词频详情:");
        tfAll.forEach(row -> log.info("  chunk={} | term={} | tf={}",
            row.get("chunk_id"), row.get("term"), row.get("tf")));

        // "轻量级锁" 在两个 chunk 都出现
        Integer tfTerm = jdbcTemplate.queryForObject(
            "SELECT SUM(tf) FROM bm25_term_freq WHERE kb_id = ? AND term = ?",
            Integer.class, TEST_KB_ID, "轻量级锁");
        log.info("'轻量级锁' 的 tf 总和: {} (预期 >= 2，两个 chunk 各出现)", tfTerm);
        assertThat(tfTerm).as("轻量级锁的 tf 总和").isGreaterThanOrEqualTo(2);

        // --- 验证 bm25_doc_freq ---
        Integer dfCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM bm25_doc_freq WHERE kb_id = ?", Integer.class, TEST_KB_ID);
        log.info("bm25_doc_freq: 本 kb 共有 {} 个不重复词条", dfCount);
        assertThat(dfCount).as("doc_freq 应有数据").isGreaterThan(0);

        // 查询所有 doc_freq 详情
        List<Map<String, Object>> dfAll = jdbcTemplate.queryForList(
            "SELECT term, df FROM bm25_doc_freq WHERE kb_id = ? ORDER BY term",
            TEST_KB_ID);
        log.info("文档频率详情:");
        dfAll.forEach(row -> log.info("  term={} | df={}", row.get("term"), row.get("df")));

        // "轻量级锁" 在两个 chunk 都出现，df 应为 2
        Integer dfValue = jdbcTemplate.queryForObject(
            "SELECT df FROM bm25_doc_freq WHERE kb_id = ? AND term = ?",
            Integer.class, TEST_KB_ID, "轻量级锁");
        log.info("'轻量级锁' 的 df: {} (预期=2，出现在2个chunk中)", dfValue);
        assertThat(dfValue).as("轻量级锁的 df（出现在几个 chunk 中）").isEqualTo(2);

        // --- 验证 bm25_kb_stats ---
        Map<String, Object> stats = jdbcTemplate.queryForMap(
            "SELECT total_chunks, total_length, avgdl FROM bm25_kb_stats WHERE kb_id = ?",
            TEST_KB_ID);
        log.info("bm25_kb_stats: total_chunks={}, total_length={}, avgdl={}",
            stats.get("total_chunks"), stats.get("total_length"), stats.get("avgdl"));
        assertThat((Integer) stats.get("total_chunks")).as("total_chunks").isEqualTo(2);
        assertThat(((Number) stats.get("total_length")).longValue()).as("total_length").isGreaterThan(0);
        assertThat(((Number) stats.get("avgdl")).doubleValue()).as("avgdl").isGreaterThan(0);

        log.info("========== 测试2 通过 ✓ ==========\n");
    }

    // ==================== 测试3：幂等保护 ====================

    @Test
    @DisplayName("重复调用 indexChunks → 自动先清再建，df 不翻倍")
    void testIdempotencyDoesNotDoubleCount() {
        log.info("========== 测试3: 幂等保护 ==========");

        List<Document> chunks = List.of(
            createChunk("chunk-3", "JVM的垃圾回收机制基于分代回收算法")
        );
        log.info("准备 1 个测试 chunk, kbId={}", TEST_KB_ID);

        // 第一次写入
        indexService.indexChunks(TEST_KB_ID, chunks);
        log.info("📝 第一次 indexChunks 调用完成");

        // 记录第一次写入后的 df
        Integer dfFirst = jdbcTemplate.queryForObject(
            "SELECT df FROM bm25_doc_freq WHERE kb_id = ? AND term = ?",
            Integer.class, TEST_KB_ID, "分代回收");
        log.info("第一次写入后: '分代回收' df = {}", dfFirst);

        // 第二次写入（模拟重试）
        indexService.indexChunks(TEST_KB_ID, chunks);
        log.info("📝 第二次 indexChunks 调用完成（模拟重试）");

        // 第二次写入后 df 应与第一次相同（先清再建，不是累加）
        Integer dfSecond = jdbcTemplate.queryForObject(
            "SELECT df FROM bm25_doc_freq WHERE kb_id = ? AND term = ?",
            Integer.class, TEST_KB_ID, "分代回收");
        log.info("第二次写入后: '分代回收' df = {} (第一次={})", dfSecond, dfFirst);

        assertThat(dfSecond).as("重试后 df 不应翻倍").isEqualTo(dfFirst);
        if (dfSecond.equals(dfFirst)) {
            log.info("✅ df 值未翻倍（幂等保护生效：先清再建）");
        }

        // kb_stats 也不应翻倍
        Map<String, Object> stats = jdbcTemplate.queryForMap(
            "SELECT total_chunks FROM bm25_kb_stats WHERE kb_id = ?", TEST_KB_ID);
        Integer totalChunks = (Integer) stats.get("total_chunks");
        log.info("kb_stats.total_chunks = {} (预期=1，未翻倍)", totalChunks);
        assertThat(totalChunks).isEqualTo(1);

        log.info("========== 测试3 通过 ✓ ==========\n");
    }

    // ==================== 测试4：删除后三表清空 ====================

    @Test
    @DisplayName("deleteAllByKbId 后三张表均应清空")
    void testDeleteClearsAllTables() {
        log.info("========== 测试4: 删除同步清理 ==========");

        List<Document> chunks = List.of(
            createChunk("chunk-4", "RocketMQ采用顺序写提高磁盘读写效率")
        );
        log.info("准备 1 个测试 chunk, kbId={}", TEST_KB_ID);

        // 写入
        indexService.indexChunks(TEST_KB_ID, chunks);
        log.info("📝 indexChunks 调用完成");

        // 确认数据已写入
        Integer beforeDelete = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM bm25_term_freq WHERE kb_id = ?", Integer.class, TEST_KB_ID);
        log.info("删除前: bm25_term_freq 有 {} 行", beforeDelete);
        assertThat(beforeDelete).as("删除前应有数据").isGreaterThan(0);

        // 删除
        indexService.deleteAllByKbId(TEST_KB_ID);
        log.info("📝 deleteAllByKbId 调用完成");

        // 三张表均不应再有此 kb_id 的数据
        Integer tfAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM bm25_term_freq WHERE kb_id = ?", Integer.class, TEST_KB_ID);
        Integer dfAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM bm25_doc_freq WHERE kb_id = ?", Integer.class, TEST_KB_ID);
        Integer statsAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM bm25_kb_stats WHERE kb_id = ?", Integer.class, TEST_KB_ID);

        log.info("删除后: term_freq={}, doc_freq={}, kb_stats={} (预期全部=0)",
            tfAfter, dfAfter, statsAfter);

        assertThat(tfAfter).as("term_freq 应已清空").isEqualTo(0);
        assertThat(dfAfter).as("doc_freq 应已清空").isEqualTo(0);
        assertThat(statsAfter).as("kb_stats 应已清空").isEqualTo(0);

        log.info("✅ 三张表全部清空");
        log.info("========== 测试4 通过 ✓ ==========\n");
    }

    // ==================== 测试5：回填已有知识库数据 ====================

    /**
     * 从 vector_store 回填 kb_id=2~5 的数据到 BM25 索引
     *
     * <p>这是评测前置步骤——不跑这个测试，FullEvaluationTest 里的 BM25 评测全为 0。
     * 建议跑法：
     * <pre>
     *   ./gradlew test --tests "BM25WritePathTest.backfillExistingKbData"
     *   ./gradlew test --tests "FullEvaluationTest.evaluateComparison"
     * </pre>
     *
     * <p>幂等：重复跑不会翻倍（内部 hasIndexData → 先清再建）</p>
     */
    @Test
    @DisplayName("回填 kb_id=2~5 的已有数据到 BM25 索引")
    void backfillExistingKbData() {
        log.info("========== 回填已有知识库数据到 BM25 索引 ==========");

        // kb_id=2: JUC (24 chunks), kb_id=3: JVM (22 chunks)
        // kb_id=4: Redis (19 chunks), kb_id=5: RocketMQ (16 chunks)
        List<Long> kbIds = List.of(2L, 3L, 4L, 5L);

        Map<Long, Integer> result = indexService.backfillFromVectorStore(kbIds);

        log.info("========== 回填结果汇总 ==========");
        result.forEach((kbId, count) ->
            log.info("  kb_id={}: 回填 {} 个 chunk", kbId, count));

        // 验证每个 kb_id 的三张表都有数据
        for (Long kbId : kbIds) {
            Integer tfCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bm25_term_freq WHERE kb_id = ?", Integer.class, kbId);
            Integer dfCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bm25_doc_freq WHERE kb_id = ?", Integer.class, kbId);
            Map<String, Object> stats = jdbcTemplate.queryForMap(
                "SELECT total_chunks, total_length, avgdl FROM bm25_kb_stats WHERE kb_id = ?", kbId);

            log.info("  kb_id={}: term_freq={} 行, doc_freq={} 个不重复词, stats={}",
                kbId, tfCount, dfCount, stats);
        }

        // 验证 kb_id=2 (JUC) 有 24 个 chunk
        Map<String, Object> jucStats = jdbcTemplate.queryForMap(
            "SELECT total_chunks FROM bm25_kb_stats WHERE kb_id = ?", 2L);
        log.info("JUC (kb_id=2) total_chunks: {}", jucStats.get("total_chunks"));
        assertThat((Integer) jucStats.get("total_chunks"))
            .as("JUC 应有 24 个 chunk").isEqualTo(24);

        // 验证 kb_id=5 (RocketMQ) 有 16 个 chunk
        Map<String, Object> mqStats = jdbcTemplate.queryForMap(
            "SELECT total_chunks FROM bm25_kb_stats WHERE kb_id = ?", 5L);
        log.info("RocketMQ (kb_id=5) total_chunks: {}", mqStats.get("total_chunks"));
        assertThat((Integer) mqStats.get("total_chunks"))
            .as("RocketMQ 应有 16 个 chunk").isEqualTo(16);

        log.info("========== 回填测试通过 ✓ ==========\n");
    }

    // ==================== 测试6：分词器诊断——验证复合技术词是否被切碎 ====================

    /**
     * 选取测试集中包含精确专有名词的典型 query，打印分词结果，验证技术术语是否被完整保留。
     *
     * <p><b>诊断目的</b>：纯BM25的Recall@5(0.81) 低于纯向量(0.88)，
     * 且大部分单chunk题的query本身就带精确专有名词，应该是BM25强项。
     * 需要排查 BM25Tokenizer 是否把 "ForwardingNode"、"批量重偏向" 等词切碎了。</p>
     *
     * <p>运行：{@code ./gradlew test --tests "BM25WritePathTest.diagnoseTokenization"}</p>
     */
    @Test
    @DisplayName("分词器诊断：验证复合技术词是否完整保留")
    void diagnoseTokenization() {
        log.info("========== BM25 分词器诊断 ==========\n");

        // 从120题测试集精选的典型query（覆盖英文驼峰、中文复合词、混合中英文）
        record TestQuery(String id, String query, List<String> mustContainTerms) {}
        List<TestQuery> cases = List.of(
            // === 英文驼峰类名 ===
            new TestQuery("QJUC-15", "JDK8 ConcurrentHashMap 的 get 方法中 ForwardingNode 的作用是什么",
                List.of("ConcurrentHashMap", "ForwardingNode", "JDK8")),
            new TestQuery("QJUC-17", "JDK7 ConcurrentHashMap 的 Segment 机制是怎样的",
                List.of("ConcurrentHashMap", "Segment", "JDK7")),
            new TestQuery("QJUC-14", "ConcurrentHashMap 的 computeIfAbsent 方法有什么作用",
                List.of("ConcurrentHashMap", "computeIfAbsent")),
            new TestQuery("QJUC-?", "ThreadLocalMap 的 Entry 为什么用弱引用",
                List.of("ThreadLocalMap", "Entry")),
            new TestQuery("QMQ-09", "RocketMQ 中 CommitLog 和 ConsumeQueue 的关系是什么",
                List.of("RocketMQ", "CommitLog", "ConsumeQueue")),
            new TestQuery("QMQ-?", "RocketMQ DefaultMQPushConsumer 的回调函数机制",
                List.of("RocketMQ", "DefaultMQPushConsumer")),

            // === 中文复合技术词 ===
            new TestQuery("QJUC-?", "批量重偏向和批量撤销有什么区别",
                List.of("批量重偏向", "批量撤销")),
            new TestQuery("QJUC-?", "轻量级锁的实现原理",
                List.of("轻量级锁")),
            new TestQuery("QJUC-?", "锁膨胀的过程是怎样的",
                List.of("锁膨胀")),
            new TestQuery("QJVM-?", "双亲委派机制是什么",
                List.of("双亲委派", "双亲委派机制")),
            new TestQuery("QJVM-?", "双亲委派机制被破坏的场景有哪些",
                List.of("双亲委派", "双亲委派机制")),

            // === Redis 精确类型名 ===
            new TestQuery("QRedis-02", "Redis 字符串类型中 raw 和 embstr 有什么区别",
                List.of("Redis", "raw", "embstr")),
            new TestQuery("QRedis-?", "Redis ziplist 和 quicklist 的区别",
                List.of("Redis", "ziplist", "quicklist")),

            // === 混合中英文 ===
            new TestQuery("QJUC-?", "CAS 操作在 Unsafe 类中如何实现",
                List.of("CAS", "Unsafe")),
            new TestQuery("QJUC-?", "AQS 和 ReentrantLock 的关系",
                List.of("AQS", "ReentrantLock"))
        );

        int totalChecks = 0;
        int failedChecks = 0;

        for (TestQuery tc : cases) {
            List<String> tokens = tokenizer.tokenize(tc.query);

            log.info("--- {}", tc.id);
            log.info("  Query: {}", tc.query);
            log.info("  Tokens ({}): [{}]", tokens.size(),
                String.join(", ", tokens));

            // 检查每个必须出现的关键词
            for (String must : tc.mustContainTerms) {
                totalChecks++;
                boolean found = tokens.contains(must);
                if (!found) {
                    failedChecks++;
                    log.warn("  ✗ 缺失关键词: \"{}\"", must);

                    // 尝试找出被切成什么了
                    List<String> parts = findPartialMatches(tokens, must);
                    if (!parts.isEmpty()) {
                        log.warn("    → 可能被切碎为: {}", parts);
                    }
                }
            }
            log.info("");
        }

        // 汇总
        log.info("═══════════════════════════════════════════════");
        log.info("诊断结果: {}/{} 个必须词通过, {}/{} 失败",
            totalChecks - failedChecks, totalChecks, failedChecks, totalChecks);
        if (failedChecks > 0) {
            log.warn("⚠ 存在技术词被切碎的情况——这是 BM25 Recall 偏低的可验证根因");
        } else {
            log.info("✅ 所有技术词完整保留，BM25分数低的原因不在分词器");
        }
        log.info("═══════════════════════════════════════════════\n");
    }

    /** 在 token 列表中查找与目标词有重叠子串的 token（用于诊断切碎） */
    private static List<String> findPartialMatches(List<String> tokens, String target) {
        List<String> parts = new ArrayList<>();
        String lower = target.toLowerCase();
        for (String t : tokens) {
            if (!t.equals(target) && (t.contains(lower.substring(0, Math.min(3, lower.length())))
                    || lower.contains(t.toLowerCase()))) {
                parts.add(t);
            }
        }
        return parts;
    }

    // ==================== 辅助方法 ====================

    private static Document createChunk(String id, String text) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("kb_id", TEST_KB_ID.toString());
        return Document.builder()
            .id(id)
            .text(text)
            .metadata(metadata)
            .build();
    }
}
