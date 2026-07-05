package interview.guide.modules.knowledgebase.evaluation;

import interview.guide.modules.knowledgebase.bm25.BM25SearchService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import org.junit.jupiter.api.*;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

/**
 * RAG 全量检索评测 — 4 主题 102 正例 + 21 负例，输出 Recall@5 / Precision@5 / NDCG@5 / MRR
 *
 * <p>主题与 kb_id 对照:
 * <ul>
 *   <li>kb_id=2: JUC (24 chunks, 26 题)</li>
 *   <li>kb_id=3: JVM (22 chunks, 25 题)</li>
 *   <li>kb_id=4: Redis (19 chunks, 26 题)</li>
 *   <li>kb_id=5: RocketMQ (16 chunks, 25 题)</li>
 * </ul>
 *
 * <p><b>BM25 评测前置条件</b>：
 * 跑 BM25 评测前必须先回填数据，否则 BM25 三张表为空，全部指标为 0：
 * <pre>
 *   # 第一步：回填已有 vector_store 数据到 BM25 索引
 *   ./gradlew test --tests "BM25WritePathTest.backfillExistingKbData"
 *
 *   # 第二步：跑 BM25 评测
 *   ./gradlew test --tests "FullEvaluationTest.evaluateComparison"
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("RAG 全量检索评测 (102 正例 + 21 负例)")
class FullEvaluationTest {

    private static final int K = 5;
    private static final int NEG_K = 3; // 负例题检索条数（看是否触发幻觉）
    private static final double MIN_SCORE = 0.0;

    @Autowired
    private KnowledgeBaseVectorService vectorService;

    @Autowired
    private BM25SearchService bm25SearchService;

    // ==================== 正例题 ====================

    private static final List<KbTestCase> JUC = buildJUC();
    private static final List<KbTestCase> JVM = buildJVM();
    private static final List<KbTestCase> REDIS = buildRedis();
    private static final List<KbTestCase> ROCKETMQ = buildRocketMQ();

    // ==================== 负例题（远域 13 + 近域 8） ====================

    private static final List<String> NEG_FAR = List.of(
        "Spring 的事务传播机制有哪几种？",
        "Kafka 的 ISR 机制是什么？",
        "Docker 镜像的分层存储原理是怎样的？",
        "HTTPS 的 TLS 握手过程是怎样的？",
        "Git rebase 和 merge 的区别和使用场景？",
        "Zookeeper 的 ZAB 协议和 Raft 协议有什么不同？",
        "Nginx 的负载均衡策略有哪些？",
        "Elasticsearch 的倒排索引原理是什么？",
        "Python 的 GIL 是什么？为什么会影响多线程性能？",
        "Spring Cloud Gateway 的路由过滤器链机制是怎样的？",
        "TCP 三次握手和四次挥手的过程是怎样的？",
        "设计模式中的工厂模式和策略模式有什么区别？",
        "MyBatis 的一级缓存和二级缓存有什么区别？"
    );

    private static final List<String> NEG_NEAR = List.of(
        "AQS 除了 Semaphore 和 CountDownLatch 还有哪些实现类？",
        "G1 垃圾回收器的分区回收机制是怎样的？",
        "Redis Cluster 的 Gossip 协议如何实现节点间通信？",
        "RocketMQ 事务消息的两阶段提交实现原理",
        "Go 语言的 goroutine 和 Java 线程在调度模型上有什么本质区别？",
        "Redis 缓存的过期删除策略具体怎么实现？",
        "JVM 的逃逸分析和栈上分配如何优化对象创建？",
        "RocketMQ 消费者组发生重平衡时消息消费会暂停多久？"
    );

    // 近域负例对应的邻接 kb_id（防止检索后瞎编）
    private static final List<Long> NEG_NEAR_KB = List.of(2L, 3L, 4L, 5L, 2L, 4L, 3L, 5L);

    // ==================== 正例题评测 ====================

    @Test
    @DisplayName("JUC 主题评测 (26 题, kb_id=2)")
    void evaluateJUC() {
        evaluateTopic("JUC", JUC);
    }

    @Test
    @DisplayName("JVM 主题评测 (25 题, kb_id=3)")
    void evaluateJVM() {
        evaluateTopic("JVM", JVM);
    }

    @Test
    @DisplayName("Redis 主题评测 (26 题, kb_id=4)")
    void evaluateRedis() {
        evaluateTopic("Redis", REDIS);
    }

    @Test
    @DisplayName("RocketMQ 主题评测 (25 题, kb_id=5)")
    void evaluateRocketMQ() {
        evaluateTopic("RocketMQ", ROCKETMQ);
    }

    @Test
    @DisplayName("全主题汇总报告")
    void evaluateAllTopics() {
        System.out.println("\n╔════════════════════════════════════════════════════════╗");
        System.out.println("║          RAG 全量检索评测 — 四主题汇总                  ║");
        System.out.println("╚════════════════════════════════════════════════════════╝\n");

        List<TopicSummary> summaries = new ArrayList<>();

        for (var entry : Map.of("JUC", JUC, "JVM", JVM, "Redis", REDIS, "RocketMQ", ROCKETMQ).entrySet()) {
            TopicSummary s = runTopic(entry.getKey(), entry.getValue());
            summaries.add(s);
            printTopicSummary(s);
        }

        // 全量汇总
        double avgRecall = summaries.stream().mapToDouble(s -> s.avgRecall).average().orElse(0);
        double avgPrec = summaries.stream().mapToDouble(s -> s.avgPrecision).average().orElse(0);
        double avgNdcg = summaries.stream().mapToDouble(s -> s.avgNdcg).average().orElse(0);
        double avgMrr = summaries.stream().mapToDouble(s -> s.avgMrr).average().orElse(0);
        int totalHits = summaries.stream().mapToInt(s -> s.totalHits).sum();
        int totalExpected = summaries.stream().mapToInt(s -> s.totalExpected).sum();

        System.out.println("\n══════════════ 全量汇总 ══════════════");
        System.out.printf("Recall@%d:    %.2f\n", K, avgRecall);
        System.out.printf("Precision@%d: %.2f\n", K, avgPrec);
        System.out.printf("NDCG@%d:      %.2f\n", K, avgNdcg);
        System.out.printf("MRR:          %.2f\n", avgMrr);
        System.out.printf("总命中:        %d / %d\n", totalHits, totalExpected);
        System.out.println("═══════════════════════════════════════\n");
    }

    // ==================== 负例题评测 ====================

    @Test
    @DisplayName("远域负例评测 (13 题)")
    void evaluateNegativeFar() {
        System.out.println("\n===== 远域负例评测 (13 题) =====");
        System.out.println("期望: 所有检索结果相似度应极低 (< 0.5)，不应匹配知识库内容\n");

        int passed = 0;
        for (String q : NEG_FAR) {
            // 跨所有 kb 检索
            List<Document> docs = vectorService.similaritySearch(q, List.of(2L, 3L, 4L, 5L), NEG_K, MIN_SCORE);
            double maxScore = docs.stream().mapToDouble(d -> {
                //distance=0.82 → maxScore=0.18（基本不相关） distance=0.05 → maxScore=0.95（高度相关）
                // 距离越小，相似度越高，总和为1
                try { return d.getMetadata().containsKey("distance") ? 1 - (double) d.getMetadata().get("distance") : 0; }
                catch (Exception e) { return 0; }
            }).max().orElse(0);

            boolean ok = maxScore < 0.5;
            if (ok) passed++;
            System.out.printf("  %s  maxScore=%.3f | %s | 首次chunk: %s\n",
                ok ? "✓" : "✗", maxScore,
                trunc(q, 40),
                docs.isEmpty() ? "无结果" : trunc(docs.get(0).getText(), 40));
        }
        System.out.printf("\n远域负例通过率: %d/%d (%.0f%%)\n", passed, NEG_FAR.size(), 100.0 * passed / NEG_FAR.size());
    }

    @Test
    @DisplayName("近域负例评测 (8 题)")
    void evaluateNegativeNear() {
        System.out.println("\n===== 近域负例评测 (8 题) =====");
        System.out.println("期望: 可返回邻接内容，但不应编造知识库中没有的细节\n");
        System.out.println("(近域负例无法自动判定，请人工检查以下输出)\n");

        for (int i = 0; i < NEG_NEAR.size(); i++) {
            String q = NEG_NEAR.get(i);
            Long kb = NEG_NEAR_KB.get(i);
            List<Document> docs = vectorService.similaritySearch(q, List.of(kb), NEG_K, MIN_SCORE);
            System.out.printf("  Q%d [kb_id=%d]: %s\n", i + 1, kb, q);
            for (int j = 0; j < docs.size(); j++) {
                System.out.printf("    [%d] %s\n", j + 1, trunc(docs.get(j).getText(), 80));
            }
            System.out.println();
        }
    }

    // ==================== BM25 正例题评测 ====================

    @Test
    @DisplayName("BM25 JUC 主题评测")
    void evaluateJUC_BM25() {
        evaluateTopicBm25("JUC", JUC);
    }

    @Test
    @DisplayName("BM25 JVM 主题评测")
    void evaluateJVM_BM25() {
        evaluateTopicBm25("JVM", JVM);
    }

    @Test
    @DisplayName("BM25 Redis 主题评测")
    void evaluateRedis_BM25() {
        evaluateTopicBm25("Redis", REDIS);
    }

    @Test
    @DisplayName("BM25 RocketMQ 主题评测")
    void evaluateRocketMQ_BM25() {
        evaluateTopicBm25("RocketMQ", ROCKETMQ);
    }

    @Test
    @DisplayName("BM25 全主题汇总")
    void evaluateAllTopicsBm25() {
        System.out.println("\n╔════════════════════════════════════════════════════════╗");
        System.out.println("║          BM25 关键词检索评测 — 四主题汇总               ║");
        System.out.println("╚════════════════════════════════════════════════════════╝\n");

        List<TopicSummary> summaries = new ArrayList<>();
        for (var entry : Map.of("JUC", JUC, "JVM", JVM, "Redis", REDIS, "RocketMQ", ROCKETMQ).entrySet()) {
            TopicSummary s = runTopicBm25(entry.getKey(), entry.getValue());
            summaries.add(s);
            printTopicSummary(s);
        }

        double avgRecall = summaries.stream().mapToDouble(s -> s.avgRecall).average().orElse(0);
        double avgPrec = summaries.stream().mapToDouble(s -> s.avgPrecision).average().orElse(0);
        double avgNdcg = summaries.stream().mapToDouble(s -> s.avgNdcg).average().orElse(0);
        double avgMrr = summaries.stream().mapToDouble(s -> s.avgMrr).average().orElse(0);
        int totalHits = summaries.stream().mapToInt(s -> s.totalHits).sum();
        int totalExpected = summaries.stream().mapToInt(s -> s.totalExpected).sum();

        System.out.println("\n══════════════ BM25 全量汇总 ══════════════");
        System.out.printf("Recall@%d:    %.2f\n", K, avgRecall);
        System.out.printf("Precision@%d: %.2f\n", K, avgPrec);
        System.out.printf("NDCG@%d:      %.2f\n", K, avgNdcg);
        System.out.printf("MRR:          %.2f\n", avgMrr);
        System.out.printf("总命中:        %d / %d\n", totalHits, totalExpected);
        System.out.println("═══════════════════════════════════════════\n");
    }

    // ==================== 向量 vs BM25 对比 ====================

    @Test
    @DisplayName("向量检索 vs BM25 关键词检索 对比报告")
    void evaluateComparison() {
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║        向量检索 vs BM25 关键词检索 对比报告                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");

        System.out.printf("%-10s | %8s | %8s | %8s | %8s | %8s | %8s | %8s | %8s\n",
            "主题", "V-Recall", "B-Recall", "V-Prec", "B-Prec", "V-NDCG", "B-NDCG", "V-MRR", "B-MRR");
        System.out.println("-".repeat(105));

        double[] totals = {0, 0, 0, 0, 0, 0, 0, 0}; // V-R, B-R, V-P, B-P, V-N, B-N, V-M, B-M
        int topicCount = 0;

        for (var entry : Map.of("JUC", JUC, "JVM", JVM, "Redis", REDIS, "RocketMQ", ROCKETMQ).entrySet()) {
            TopicSummary vs = runTopic(entry.getKey(), entry.getValue());
            TopicSummary bs = runTopicBm25(entry.getKey(), entry.getValue());

            System.out.printf("%-10s | %8.2f | %8.2f | %8.2f | %8.2f | %8.2f | %8.2f | %8.2f | %8.2f\n",
                entry.getKey(),
                vs.avgRecall, bs.avgRecall,
                vs.avgPrecision, bs.avgPrecision,
                vs.avgNdcg, bs.avgNdcg,
                vs.avgMrr, bs.avgMrr);

            totals[0] += vs.avgRecall; totals[1] += bs.avgRecall;
            totals[2] += vs.avgPrecision; totals[3] += bs.avgPrecision;
            totals[4] += vs.avgNdcg; totals[5] += bs.avgNdcg;
            totals[6] += vs.avgMrr; totals[7] += bs.avgMrr;
            topicCount++;
        }

        System.out.println("-".repeat(105));
        System.out.printf("%-10s | %8.2f | %8.2f | %8.2f | %8.2f | %8.2f | %8.2f | %8.2f | %8.2f\n",
            "平均",
            totals[0] / topicCount, totals[1] / topicCount,
            totals[2] / topicCount, totals[3] / topicCount,
            totals[4] / topicCount, totals[5] / topicCount,
            totals[6] / topicCount, totals[7] / topicCount);
        System.out.println();
    }

    // ==================== BM25 内部方法 ====================

    private void evaluateTopicBm25(String name, List<KbTestCase> cases) {
        TopicSummary s = runTopicBm25(name, cases);
        printDetailTable(name, s, cases);
    }

    private TopicSummary runTopicBm25(String name, List<KbTestCase> cases) {
        List<EvalRow> rows = new ArrayList<>();
        for (KbTestCase tc : cases) {
            List<BM25SearchService.Bm25Hit> hits = bm25SearchService.search(tc.question, tc.kbIds, K);
            List<String> retrieved = hits.stream()
                .map(BM25SearchService.Bm25Hit::chunkId)
                .limit(K)
                .toList();
            Set<String> gt = new HashSet<>(tc.groundTruthIds);
            Set<String> topKSet = new HashSet<>(retrieved);
            topKSet.retainAll(gt);
            int matched = topKSet.size();
            rows.add(new EvalRow(tc.id, tc.question, matched, gt.size(),
                computeRecall(gt, matched), computePrecision(matched),
                computeNDCG(retrieved, gt), computeMRR(retrieved, gt),
                retrieved, tc.groundTruthIds));
        }
        double rec = rows.stream().mapToDouble(r -> r.recall).average().orElse(0);
        double prec = rows.stream().mapToDouble(r -> r.precision).average().orElse(0);
        double ndcg = rows.stream().mapToDouble(r -> r.ndcg).average().orElse(0);
        double mrr = rows.stream().mapToDouble(r -> r.mrr).average().orElse(0);
        int hits = rows.stream().mapToInt(r -> r.hits).sum();
        int expected = rows.stream().mapToInt(r -> r.expectedTotal).sum();
        return new TopicSummary(name, rec, prec, ndcg, mrr, hits, expected, rows);
    }

    // ==================== 内部方法 ====================

    private void evaluateTopic(String name, List<KbTestCase> cases) {
        TopicSummary s = runTopic(name, cases);
        printDetailTable(name, s, cases);
    }

    private TopicSummary runTopic(String name, List<KbTestCase> cases) {
        List<EvalRow> rows = new ArrayList<>();
        for (KbTestCase tc : cases) {
            List<Document> docs = vectorService.similaritySearch(tc.question, tc.kbIds, K, MIN_SCORE);
            List<String> retrieved = docs.stream().map(Document::getId).toList();
            Set<String> gt = new HashSet<>(tc.groundTruthIds);
            Set<String> topK = new HashSet<>(retrieved.subList(0, Math.min(K, retrieved.size())));
            topK.retainAll(gt);
            int hits = topK.size();
            rows.add(new EvalRow(tc.id, tc.question, hits, gt.size(),
                computeRecall(gt, hits), computePrecision(hits),
                computeNDCG(retrieved, gt), computeMRR(retrieved, gt),
                retrieved, tc.groundTruthIds));
        }
        double rec = rows.stream().mapToDouble(r -> r.recall).average().orElse(0);
        double prec = rows.stream().mapToDouble(r -> r.precision).average().orElse(0);
        double ndcg = rows.stream().mapToDouble(r -> r.ndcg).average().orElse(0);
        double mrr = rows.stream().mapToDouble(r -> r.mrr).average().orElse(0);
        int hits = rows.stream().mapToInt(r -> r.hits).sum();
        int expected = rows.stream().mapToInt(r -> r.expectedTotal).sum();
        return new TopicSummary(name, rec, prec, ndcg, mrr, hits, expected, rows);
    }

    private void printDetailTable(String name, TopicSummary s, List<KbTestCase> cases) {
        System.out.printf("\n===== %s (%d 题) =====\n", name, cases.size());
        System.out.printf("%-6s %-30s %7s %7s %7s %7s %s\n", "ID", "问题", "Recall", "Prec", "NDCG", "MRR", "命中");
        System.out.println("-".repeat(85));
        for (EvalRow r : s.rows) {
            System.out.printf("%-6s %-30s %7.2f %7.2f %7.2f %7.2f %s%d/%d\n",
                r.id, trunc(r.question, 28), r.recall, r.precision, r.ndcg, r.mrr,
                r.recall >= 0.5 ? "✓" : "✗", r.hits, r.expectedTotal);
        }
    }

    private void printTopicSummary(TopicSummary s) {
        System.out.printf("  %-10s | Recall: %.2f | Prec: %.2f | NDCG: %.2f | MRR: %.2f | 命中: %d/%d\n",
            s.name, s.avgRecall, s.avgPrecision, s.avgNdcg, s.avgMrr, s.totalHits, s.totalExpected);
        // 失败题
        List<EvalRow> bad = s.rows.stream().filter(r -> r.recall < 0.5).toList();
        if (!bad.isEmpty()) {
            for (EvalRow r : bad) {
                System.out.printf("    ✗ %s: %s 期望=%s 实际=%s\n",
                    r.id, trunc(r.question, 30), truncIds(r.groundTruthIds), truncIds(r.retrievedIds));
            }
        }
    }

    // ==================== 指标计算 ====================

    static double computeRecall(Set<String> gt, int hits) {
        return gt.isEmpty() ? 0 : (double) hits / gt.size();
    }

    static double computePrecision(int hits) {
        return (double) hits / K;
    }

    static double computeNDCG(List<String> retrieved, Set<String> groundTruth) {
        double dcg = 0;
        for (int i = 0; i < Math.min(K, retrieved.size()); i++) {
            if (groundTruth.contains(retrieved.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        int idealCount = Math.min(K, groundTruth.size());
        double idcg = 0;
        for (int i = 0; i < idealCount; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    static double computeMRR(List<String> retrieved, Set<String> groundTruth) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (groundTruth.contains(retrieved.get(i))) return 1.0 / (i + 1);
        }
        return 0;
    }

    // ==================== 辅助方法 ====================

    static String trunc(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    static String truncIds(List<String> ids) {
        return ids.stream().map(id -> id.substring(0, 8)).reduce((a, b) -> a + "," + b).orElse("[]");
    }

    static KbTestCase q(String id, String question, List<Long> kbIds, String... gtIds) {
        return new KbTestCase(id, question, kbIds, Arrays.asList(gtIds));
    }

    // ==================== 数据类 ====================
    // record语法糖快速定义类的属性和其他的构造方法
    record KbTestCase(String id, String question, List<Long> kbIds, List<String> groundTruthIds) {}

    record TopicSummary(String name, double avgRecall, double avgPrecision, double avgNdcg, double avgMrr,
                        int totalHits, int totalExpected, List<EvalRow> rows) {}

    static class EvalRow {
        String id, question;
        int hits, expectedTotal;
        double recall, precision, ndcg, mrr;
        List<String> retrievedIds, groundTruthIds;
        EvalRow(String id, String q, int hits, int expected, double r, double p, double n, double m,
                List<String> ret, List<String> gt) {
            this.id = id; this.question = q; this.hits = hits; this.expectedTotal = expected;
            this.recall = r; this.precision = p; this.ndcg = n; this.mrr = m;
            this.retrievedIds = ret; this.groundTruthIds = gt;
        }
    }

    // ==================== 测试数据 ====================

    static List<KbTestCase> buildJUC() {
        List<Long> kb = List.of(2L);
        return List.of(
            q("Q01", "临界区和竞态条件是什么？", kb, "525c649f-6886-4399-b43c-8e360876cbe7"),
            q("Q02", "synchronized 如何保证临界区代码的原子性？", kb, "5316dd59-f3b6-4054-b12b-842792905204"),
            q("Q03", "多个线程使用 synchronized 时必须注意哪两点？", kb, "2aa8affc-3eaa-4560-b98e-9eea8b30462a"),
            q("Q04", "普通方法和静态方法上的 synchronized 锁的对象分别是什么？", kb, "2aa8affc-3eaa-4560-b98e-9eea8b30462a"),
            q("Q05", "monitor 锁的工作原理是什么？", kb, "86dbdcf9-2a6c-41b6-b375-3ff0056506ae"),
            q("Q06", "轻量级锁的实现原理是什么？", kb, "a8cc8bf3-5b5f-4f7c-afe9-f0b818ee786b"),
            q("Q07", "锁膨胀的过程是怎样的？", kb, "e264ea28-6994-4a12-9743-512a5b8796bf"),
            q("Q08", "偏向锁的作用和实现方式是什么？", kb, "d2a91f80-cacb-40e4-a249-11bbbbe0768b"),
            q("Q09", "markword 的状态变化流程是怎样的？", kb, "fe247a71-ec01-4828-bd26-03d40b9153da"),
            q("Q10", "批量重偏向和批量撤销的触发阈值分别是多少？", kb, "fe247a71-ec01-4828-bd26-03d40b9153da"),
            q("Q11", "sleep 和 wait 的区别是什么？", kb, "54616bfb-288b-4721-8816-5d6b52d45cab"),
            q("Q12", "如何使用 wait/notify 避免虚假唤醒？", kb, "054bb42f-bce9-40af-95fa-907fd5a01fdf"),
            q("Q13", "Semaphore 的 acquire 和 release 原理是什么？", kb, "bc142bc8-0baf-4fe2-ab8f-cc5f2fb0b475"),
            q("Q14", "ConcurrentHashMap computeIfAbsent 有什么作用？", kb, "32c89a1e-b972-40d3-8005-9bad2252c25a"),
            q("Q15", "ForwardingNode 的作用是什么？", kb, "593630c1-6d82-4639-904e-ea302d78fe53"),
            q("Q16", "JDK7 和 JDK8 HashMap 的区别？", kb, "3974b399-0932-4ad8-9f4b-79d419118675"),
            q("Q17", "JDK7 ConcurrentHashMap Segment 机制？", kb, "3c6cd339-0f64-4b9c-97b8-ebdc983727c0"),
            q("Q18", "局部变量逃逸问题是什么？如何解决？", kb, "ac1854da-6657-4079-acb0-7ff93915eaf2"),
            // 多chunk
            q("M1", "synchronized 锁升级全过程？", kb,
                "d2a91f80-cacb-40e4-a249-11bbbbe0768b",
                "a8cc8bf3-5b5f-4f7c-afe9-f0b818ee786b",
                "e264ea28-6994-4a12-9743-512a5b8796bf"),
            q("M2", "JDK7/JDK8 ConcurrentHashMap 数据结构+并发控制区别？", kb,
                "3c6cd339-0f64-4b9c-97b8-ebdc983727c0",
                "3974b399-0932-4ad8-9f4b-79d419118675",
                "874a79bd-4606-423d-bc97-103af99d4695"),
            q("M3", "wait/notify 和 Semaphore 在线程协作上的区别？", kb,
                "881c2586-da04-47e0-9aa0-a38b252dc7a6",
                "bc142bc8-0baf-4fe2-ab8f-cc5f2fb0b475"),
            q("M4", "CountDownLatch 和 CyclicBarrier 的区别？", kb,
                "bc142bc8-0baf-4fe2-ab8f-cc5f2fb0b475",
                "881c2586-da04-47e0-9aa0-a38b252dc7a6"),
            q("M5", "CHM put/get 在扩容期间如何保证正确性？", kb,
                "593630c1-6d82-4639-904e-ea302d78fe53",
                "3974b399-0932-4ad8-9f4b-79d419118675",
                "3c6cd339-0f64-4b9c-97b8-ebdc983727c0"),
            // 改述
            q("R1", "有没有不用互斥锁也能实现线程安全的并发控制方式？", kb,
                "d2a91f80-cacb-40e4-a249-11bbbbe0768b"),
            q("R2", "除了加 synchronized 还有什么更高效的并发容器？", kb,
                "32c89a1e-b972-40d3-8005-9bad2252c25a"),
            q("R3", "局部变量传到子类重写方法为什么会线程不安全？", kb,
                "ac1854da-6657-4079-acb0-7ff93915eaf2")
        );
    }

    static List<KbTestCase> buildJVM() {
        List<Long> kb = List.of(3L);
        return List.of(
            q("Q01", "JVM 堆内存分为哪几个区域？", kb, "42c63418-4a69-4318-9a14-5154db9d0346"),
            q("Q02", "新生代为什么要划分两个 Survivor？", kb, "42c63418-4a69-4318-9a14-5154db9d0346"),
            q("Q03", "内存泄漏和内存溢出的区别？", kb, "dbd8c10d-5a69-425e-a791-d5887b5a4e45"),
            q("Q04", "堆溢出怎么排查和解决？", kb, "daab8052-4d35-4c3c-9c6c-0b248b8cbd89"),
            q("Q05", "ThreadLocal 为什么可能导致内存泄漏？", kb, "1780323e-2079-48b4-a3fd-2cfb4a3c1e82"),
            q("Q06", "四大引用类型各自的 GC 回收条件？", kb, "8ea86c53-8911-4a45-b0d0-8e53ad04387d"),
            q("Q07", "可达性分析算法如何判断对象为垃圾？", kb, "f034ed1b-36eb-4cb4-957b-3f2db50316c9"),
            q("Q08", "标记-清除和标记-整理各有什么优缺点？", kb, "4125a01d-c2fb-4027-a873-f624f8fd018f"),
            q("Q09", "分代回收算法的核心思想？", kb, "4125a01d-c2fb-4027-a873-f624f8fd018f"),
            q("Q10", "类加载器有哪几种？层级关系怎样？", kb, "92a56a93-7802-435e-9747-7024182bbbab"),
            q("Q11", "双亲委派机制的作用？", kb, "1ee6770a-1149-4b76-bc94-afa6ca3fad3e"),
            q("Q12", "类加载的完整流程？", kb, "99b0f1b3-78df-4b13-802e-bc4095c6744a"),
            q("Q13", "创建对象的过程包含哪些步骤？", kb, "9566e696-ae2e-49cc-9a0d-eda7ca01cba7"),
            q("Q14", "什么情况下会触发垃圾回收？", kb, "a416ca99-fac1-4022-9386-4b90214d8f2d"),
            q("Q15", "方法区的方法调用过程？", kb, "a416ca99-fac1-4022-9386-4b90214d8f2d"),
            q("Q16", "程序计数器为什么是线程私有的？", kb, "aa817ff3-cd32-47f6-85d6-d93f44088ece"),
            q("Q17", "什么是符号引用？为什么需要替换为直接引用？", kb, "259b9326-e047-484f-bc65-99e26317f43d"),
            q("Q18", "栈溢出如何排查和解决？", kb, "daab8052-4d35-4c3c-9c6c-0b248b8cbd89"),
            // 多chunk
            q("M1", "Java 对象完整生命周期？", kb,
                "9566e696-ae2e-49cc-9a0d-eda7ca01cba7",
                "99b0f1b3-78df-4b13-802e-bc4095c6744a",
                "f034ed1b-36eb-4cb4-957b-3f2db50316c9",
                "a416ca99-fac1-4022-9386-4b90214d8f2d"),
            q("M2", "ThreadLocal 底层结构？key 弱引用 value 强引用各自问题？", kb,
                "39b3f5aa-2fb0-4c59-b1eb-ba916705c404",
                "103b1f28-cead-472d-96ba-fc9818ad532f",
                "134935a3-a273-4686-b7a2-565f2c686797",
                "1780323e-2079-48b4-a3fd-2cfb4a3c1e82"),
            q("M3", "内存泄漏常见原因？怎么排查？", kb,
                "103b1f28-cead-472d-96ba-fc9818ad532f",
                "dbd8c10d-5a69-425e-a791-d5887b5a4e45",
                "8ea86c53-8911-4a45-b0d0-8e53ad04387d",
                "134935a3-a273-4686-b7a2-565f2c686797"),
            q("M4", "引用计数法和可达性分析各有什么优缺点？", kb,
                "f034ed1b-36eb-4cb4-957b-3f2db50316c9",
                "39b3f5aa-2fb0-4c59-b1eb-ba916705c404",
                "8ea86c53-8911-4a45-b0d0-8e53ad04387d"),
            // 改述
            q("R1", "OutOfMemoryError 从哪些方面排查？", kb, "daab8052-4d35-4c3c-9c6c-0b248b8cbd89"),
            q("R2", "为什么 Java 需要多个类加载器？", kb, "1ee6770a-1149-4b76-bc94-afa6ca3fad3e"),
            q("R3", "Tomcat 线程池复用 ThreadLocal 为什么会内存撑爆？", kb, "1780323e-2079-48b4-a3fd-2cfb4a3c1e82")
        );
    }

    static List<KbTestCase> buildRedis() {
        List<Long> kb = List.of(4L);
        return List.of(
            q("Q01", "Redis 为什么快？它是什么类型的数据库？", kb, "4c29d49e-3502-42ec-b0ff-9023b28e166e"),
            q("Q02", "Redis 字符串 raw 和 embstr 有什么区别？", kb, "ffbb829f-75b3-457c-bff9-b0fe0c260b8b"),
            q("Q03", "Hash 类型的底层结构？切换条件？", kb, "59b1e8ee-5fad-4e21-bdd2-09877500fd87"),
            q("Q04", "List 内部结构在 3.2 前后有什么变化？", kb, "2f23c9ee-a8b2-4ccf-b20c-e8c7f4bc6929"),
            q("Q05", "Set 的底层实现？超过阈值会怎样？", kb, "48685999-c57b-4c93-9fbc-cb045a636b47"),
            q("Q06", "ZSet 的内部结构？切换条件？", kb, "446fcd60-7945-4940-98ea-4469ce1fde50"),
            q("Q07", "Redis 单点部署存在哪些问题？", kb, "c1abae56-7e6d-4e8e-8b15-2bfdb9e5a929"),
            q("Q08", "RDB bgsave fork 原理？写时复制如何工作？", kb, "25048242-05c3-47ef-a27f-5c9008758040"),
            q("Q09", "AOF bgrewriteaof 的作用？", kb, "a609a6dd-23a1-4b98-9528-02385d2e39b9"),
            q("Q10", "为什么 RDB fork 和 AOF rewrite 同时进行会阻塞主线程？", kb, "440627f8-7ad0-48a6-a952-974cb6bc3c88"),
            q("Q11", "select 和 epoll 的核心区别？", kb, "1590df2c-4a7a-4c74-9099-bf6213bb9c29"),
            q("Q12", "epoll ET 和 LT 有什么区别？", kb, "6b89792b-4c8d-4705-86dd-6d691a9d4a79"),
            q("Q13", "IO 多路复用的核心思想？", kb, "4ff5435e-1434-435f-a1c7-fae6c1e5a937"),
            q("Q14", "BitMap 的应用场景？", kb, "67f83af0-b492-4b18-8273-1238e7e8e8b9"),
            q("Q15", "Redis 字符串类型应用场景？", kb, "da701e02-618b-4000-b7d8-98432c37fa92"),
            q("Q16", "ZSet 适合什么场景？", kb, "446fcd60-7945-4940-98ea-4469ce1fde50"),
            q("Q17", "Redis 序列化器有什么作用？", kb, "67f83af0-b492-4b18-8273-1238e7e8e8b9"),
            q("Q18", "阻塞 IO 和非阻塞 IO 各有什么优缺点？", kb, "e0bc49e5-7229-4cd2-beee-5119695438f4"),
            // 多chunk
            q("M1", "RDB 和 AOF 各有什么优缺点？生产怎么搭配？", kb,
                "25048242-05c3-47ef-a27f-5c9008758040",
                "1f3bfbc5-100b-44c3-9874-8c6442be3a69",
                "a609a6dd-23a1-4b98-9528-02385d2e39b9"),
            q("M2", "五种基本数据类型底层结构和适用场景？", kb,
                "ffbb829f-75b3-457c-bff9-b0fe0c260b8b",
                "59b1e8ee-5fad-4e21-bdd2-09877500fd87",
                "2f23c9ee-a8b2-4ccf-b20c-e8c7f4bc6929",
                "48685999-c57b-4c93-9fbc-cb045a636b47",
                "446fcd60-7945-4940-98ea-4469ce1fde50"),
            q("M3", "select→poll→epoll 经历了哪些改进？", kb,
                "4ff5435e-1434-435f-a1c7-fae6c1e5a937",
                "1590df2c-4a7a-4c74-9099-bf6213bb9c29",
                "957b4375-fb8e-46bf-9ede-003de36c836c"),
            q("M4", "Redis 如何通过集群方案解决单点四个问题？", kb,
                "c1abae56-7e6d-4e8e-8b15-2bfdb9e5a929",
                "25048242-05c3-47ef-a27f-5c9008758040",
                "a609a6dd-23a1-4b98-9528-02385d2e39b9"),
            q("M5", "用户空间/内核空间概念？和 Redis IO 模型关系？", kb,
                "440627f8-7ad0-48a6-a952-974cb6bc3c88",
                "e0bc49e5-7229-4cd2-beee-5119695438f4",
                "4ff5435e-1434-435f-a1c7-fae6c1e5a937"),
            // 改述
            q("R1", "Redis 宕机重启怎么保证数据不丢？", kb, "a609a6dd-23a1-4b98-9528-02385d2e39b9"),
            q("R2", "高并发下单线程 Redis 怎么处理几万连接？", kb, "1590df2c-4a7a-4c74-9099-bf6213bb9c29"),
            q("R3", "List 底层为什么用 quicklist 而不是单纯链表或数组？", kb, "2f23c9ee-a8b2-4ccf-b20c-e8c7f4bc6929")
        );
    }

    static List<KbTestCase> buildRocketMQ() {
        List<Long> kb = List.of(5L);
        return List.of(
            q("Q01", "RocketMQ 集群模式和广播模式区别？", kb, "c9c18c72-b654-4a6e-9cc2-c0fcddb5893f"),
            q("Q02", "RocketMQ 支持哪几种消息发送类型？", kb,
                "c9c18c72-b654-4a6e-9cc2-c0fcddb5893f",
                "03681cc5-f152-4298-89ac-4c5855e30b4f"),
            q("Q03", "批量发送消息有什么限制和优势？", kb,
                "03681cc5-f152-4298-89ac-4c5855e30b4f",
                "aef0715f-c071-4b4a-ae82-a936d8d46297"),
            q("Q04", "RocketMQ 如何实现消息过滤？", kb,
                "aef0715f-c071-4b4a-ae82-a936d8d46297",
                "dbce720b-ee4d-4248-bd7e-95e2e821312b"),
            q("Q05", "消息幂等是什么意思？常见实现方案？", kb,
                "dbce720b-ee4d-4248-bd7e-95e2e821312b",
                "6b966ca0-3b45-4d3e-b51e-7aa212f08e55",
                "e7dbce5e-aa74-41eb-a87f-8685fd6c354f"),
            q("Q06", "为什么 MQ 采用文件读写而不是数据库持久化？", kb, "e7dbce5e-aa74-41eb-a87f-8685fd6c354f"),
            q("Q07", "RocketMQ 高效磁盘读写的原因？", kb,
                "e7dbce5e-aa74-41eb-a87f-8685fd6c354f",
                "faed3c53-0eb5-4192-be18-39ac0ae2da60"),
            q("Q08", "零拷贝相比传统 IO 有什么优势？", kb,
                "faed3c53-0eb5-4192-be18-39ac0ae2da60",
                "97b1d416-8f68-4425-92ac-7e94da2d0bea"),
            q("Q09", "CommitLog 和 ConsumeQueue 的关系？", kb, "97b1d416-8f68-4425-92ac-7e94da2d0bea"),
            q("Q10", "同步刷盘和异步刷盘的区别？", kb,
                "97b1d416-8f68-4425-92ac-7e94da2d0bea",
                "c25b01cd-abb3-4bb3-8c37-54ed5a64e5bb"),
            q("Q11", "NameServer 高可用如何实现？", kb, "c25b01cd-abb3-4bb3-8c37-54ed5a64e5bb"),
            q("Q12", "消息队列有哪些优势和劣势？", kb,
                "aff17fba-8a77-4652-95e3-dd38bb7e2058",
                "a4f477ee-99ef-4401-9625-dbfedac74efe",
                "849c1e7a-7c0c-4348-be43-0b67488e2da9"),
            q("Q13", "RocketMQ 消息执行整体流程？", kb,
                "849c1e7a-7c0c-4348-be43-0b67488e2da9",
                "bbf6f90c-bbc8-447d-9c16-0b44cb3c8477"),
            q("Q14", "顺序消息和无序消息重试机制区别？", kb,
                "a9d4c62f-74fd-46a4-a8b8-db3c7e8ae2d6",
                "aff17fba-8a77-4652-95e3-dd38bb7e2058"),
            q("Q15", "死信队列的作用？", kb, "aff17fba-8a77-4652-95e3-dd38bb7e2058"),
            q("Q16", "生产者发送消息的负载均衡策略？", kb,
                "c25b01cd-abb3-4bb3-8c37-54ed5a64e5bb",
                "ed2c6fbf-b033-4d66-84c4-d355ba8c23d6"),
            q("Q17", "监听器模式和拉取模式的关系？", kb,
                "bbf6f90c-bbc8-447d-9c16-0b44cb3c8477",
                "e1bddbe3-0e4f-4cf8-8f0d-75399aa00132"),
            q("Q18", "消息队列为什么能实现削峰填谷？", kb, "a4f477ee-99ef-4401-9625-dbfedac74efe"),
            // 多chunk
            q("M1", "RocketMQ 消息发送到消费完整链路？", kb,
                "c9c18c72-b654-4a6e-9cc2-c0fcddb5893f",
                "03681cc5-f152-4298-89ac-4c5855e30b4f",
                "97b1d416-8f68-4425-92ac-7e94da2d0bea",
                "c25b01cd-abb3-4bb3-8c37-54ed5a64e5bb",
                "ed2c6fbf-b033-4d66-84c4-d355ba8c23d6",
                "849c1e7a-7c0c-4348-be43-0b67488e2da9",
                "bbf6f90c-bbc8-447d-9c16-0b44cb3c8477"),
            q("M2", "ROCKETMQ 如何通过顺序写+零拷贝实现高性能磁盘读写？", kb,
                "e7dbce5e-aa74-41eb-a87f-8685fd6c354f",
                "faed3c53-0eb5-4192-be18-39ac0ae2da60",
                "97b1d416-8f68-4425-92ac-7e94da2d0bea"),
            q("M3", "消息重试和死信队列如何配合？", kb,
                "a9d4c62f-74fd-46a4-a8b8-db3c7e8ae2d6",
                "aff17fba-8a77-4652-95e3-dd38bb7e2058"),
            q("M4", "异步消息和单向消息区别？", kb,
                "c9c18c72-b654-4a6e-9cc2-c0fcddb5893f",
                "03681cc5-f152-4298-89ac-4c5855e30b4f"),
            // 改述
            q("R1", "RocketMQ 高并发下消息不丢又不写磁盘太慢？", kb,
                "97b1d416-8f68-4425-92ac-7e94da2d0bea",
                "c25b01cd-abb3-4bb3-8c37-54ed5a64e5bb",
                "e7dbce5e-aa74-41eb-a87f-8685fd6c354f",
                "faed3c53-0eb5-4192-be18-39ac0ae2da60"),
            q("R2", "双十一高峰期怎么避免流量打崩数据库？", kb, "a4f477ee-99ef-4401-9625-dbfedac74efe"),
            q("R3", "同一条消息被处理两次怎么避免重复扣款？", kb,
                "dbce720b-ee4d-4248-bd7e-95e2e821312b",
                "6b966ca0-3b45-4d3e-b51e-7aa212f08e55",
                "e7dbce5e-aa74-41eb-a87f-8685fd6c354f")
        );
    }
}
