package interview.guide.modules.knowledgebase.evaluation;

import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 检索评测 — 跑 20 条测试集，输出 Recall@5 / Precision@5 / NDCG@5 / MRR
 *
 * <p>前提条件:
 * <ul>
 *   <li>Docker 中间件已启动: postgres(5432), redis(6379), minio(9000)</li>
 *   <li>Docker 后端容器已停止: docker compose stop app</li>
 *   <li>知识库 ID=1 已上传 CAS无锁实现并发安全.md（递归字符拆分），向量化完成</li>
 *   <li>环境变量 AI_BAILIAN_API_KEY 已设置</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@DisplayName("RAG 检索评测")
class RetrievalEvaluationTest {

    private static final int K = 5;
    private static final double MIN_SCORE = 0.0; // 评测时不设相似度阈值
    private static final List<Long> KB_IDS = List.of(1L);

    @Autowired
    private KnowledgeBaseVectorService vectorService;

    // ==================== 20 条测试用例 ====================
    // 递归字符拆分策略的 chunk ID
    private static final List<TestCase> TEST_CASES = List.of(
        tc("Q1", "CAS的全称是什么？它的核心思路是什么？",
            "63d36f30-f60b-4935-af13-bbfeaa1b95f5"),
        tc("Q2", "CAS为什么是无锁的？它不会阻塞线程的原因是什么？",
            "63d36f30-f60b-4935-af13-bbfeaa1b95f5"),
        tc("Q3", "CAS和synchronized的区别是什么？",
            "63d36f30-f60b-4935-af13-bbfeaa1b95f5",
            "b31660e5-1327-4003-902f-1219dcc4c95d"),
        tc("Q4", "AtomicInteger的updateAndSet方法接收什么类型的参数？",
            "b31660e5-1327-4003-902f-1219dcc4c95d"),
        tc("Q5", "AtomicReference为什么只能维护不可变类？",
            "b31660e5-1327-4003-902f-1219dcc4c95d",
            "75a4e97f-651d-49eb-8fe4-aa58a88cf527"),
        tc("Q6", "什么是ABA问题？",
            "75a4e97f-651d-49eb-8fe4-aa58a88cf527"),
        tc("Q7", "ABA问题为什么在字符串场景下容易出现？",
            "75a4e97f-651d-49eb-8fe4-aa58a88cf527"),
        tc("Q8", "如何解决ABA问题？",
            "75a4e97f-651d-49eb-8fe4-aa58a88cf527",
            "8c2a74e9-9e6a-42b1-8172-662677826174"),
        tc("Q9", "AtomicMarkableReference和AtomicStampedReference的使用场景有什么区别？",
            "8c2a74e9-9e6a-42b1-8172-662677826174"),
        tc("Q10", "AtomicIntegerArray保护的是什么？",
            "8c2a74e9-9e6a-42b1-8172-662677826174",
            "0cfb957d-5ef8-486f-ac74-255fdbfdc145"),
        tc("Q11", "AtomicReferenceFieldUpdater使用时有什么要求？",
            "0cfb957d-5ef8-486f-ac74-255fdbfdc145"),
        tc("Q12", "LongAdder为什么比AtomicInteger的incrementAndGet性能高？",
            "0cfb957d-5ef8-486f-ac74-255fdbfdc145",
            "d366a734-5579-4777-8dbc-3b90c67c41c9"),
        tc("Q13", "LongAdder的cell机制是如何工作的？",
            "d366a734-5579-4777-8dbc-3b90c67c41c9"),
        tc("Q14", "什么是伪共享？",
            "d366a734-5579-4777-8dbc-3b90c67c41c9",
            "6f2f180d-34da-48c7-8169-ca7d34290053"),
        tc("Q15", "如何避免伪共享？",
            "6f2f180d-34da-48c7-8169-ca7d34290053"),
        tc("Q16", "Unsafe类如何获取？",
            "6f2f180d-34da-48c7-8169-ca7d34290053"),
        tc("Q17", "Unsafe执行CAS操作需要哪些步骤和参数？",
            "6f2f180d-34da-48c7-8169-ca7d34290053",
            "f64e45bc-a3c0-4198-a3bf-99b2a721b836"),
        tc("Q18", "SimpleDateFormat有什么线程安全问题？",
            "f64e45bc-a3c0-4198-a3bf-99b2a721b836"),
        tc("Q19", "什么是保护性拷贝？",
            "f64e45bc-a3c0-4198-a3bf-99b2a721b836",
            "eeae0384-69a8-4c95-9885-621a385674fe"),
        tc("Q20", "final关键字对字节码执行效率有什么影响？",
            "eeae0384-69a8-4c95-9885-621a385674fe")
    );

    // ==================== 评测入口 ====================

    @Test
    @DisplayName("跑 20 题检索评测，输出指标")
    void evaluateAll() {
        System.out.println("\n========== RAG 检索评测 ==========");
        System.out.printf("测试题目: %d 题 | K=%d | kbIds=%s\n\n", TEST_CASES.size(), K, KB_IDS);

        List<EvalResult> results = new ArrayList<>();

        for (TestCase tc : TEST_CASES) {
            // 调用向量检索
            List<Document> docs = vectorService.similaritySearch(tc.question, KB_IDS, K, MIN_SCORE);
            List<String> retrievedIds = docs.stream().map(Document::getId).toList();

            // 算命中
            Set<String> gt = new HashSet<>(tc.groundTruthIds);
            Set<String> topK = new HashSet<>(retrievedIds.subList(0, Math.min(K, retrievedIds.size())));
            topK.retainAll(gt);
            int hits = topK.size();

            double recall = gt.isEmpty() ? 0 : (double) hits / gt.size();
            double precision = (double) hits / K;
            double ndcg = computeNDCG(retrievedIds, gt, K);
            double mrr = computeMRR(retrievedIds, gt);

            results.add(new EvalResult(tc.id, tc.question, recall, precision, ndcg, mrr, hits, gt.size(), retrievedIds));
        }

        // 打印每题详情
        System.out.printf("%-4s %-30s %8s %8s %8s %8s %8s\n", "ID", "问题(摘要)", "Recall", "Prec.", "NDCG", "MRR", "命中/期望");
        System.out.println("-".repeat(90));

        for (EvalResult r : results) {
            System.out.printf("%-4s %-30s %8.2f %8.2f %8.2f %8.2f %s%d/%d\n",
                r.id,
                truncate(r.question, 28),
                r.recall, r.precision, r.ndcg, r.mrr,
                r.recall >= 0.5 ? "✓" : "✗",
                r.hits, r.expectedTotal);
        }

        // 汇总指标
        double avgRecall = results.stream().mapToDouble(r -> r.recall).average().orElse(0);
        double avgPrecision = results.stream().mapToDouble(r -> r.precision).average().orElse(0);
        double avgNDCG = results.stream().mapToDouble(r -> r.ndcg).average().orElse(0);
        double avgMRR = results.stream().mapToDouble(r -> r.mrr).average().orElse(0);

        int totalHits = results.stream().mapToInt(r -> r.hits).sum();
        int totalExpected = results.stream().mapToInt(r -> r.expectedTotal).sum();

        System.out.println("\n========== 汇总 ==========");
        System.out.printf("Recall@%-4d:  %.2f\n", K, avgRecall);
        System.out.printf("Precision@%-1d:  %.2f\n", K, avgPrecision);
        System.out.printf("NDCG@%-6d:  %.2f\n", K, avgNDCG);
        System.out.printf("MRR:          %.2f\n", avgMRR);
        System.out.printf("总命中:        %d / %d\n", totalHits, totalExpected);
        System.out.println("==========================\n");

        // 失败题目明细
        List<EvalResult> failures = results.stream().filter(r -> r.recall < 0.5).toList();
        if (!failures.isEmpty()) {
            System.out.println("⚠ 以下题目 Recall < 0.5，需要关注:");
            for (EvalResult f : failures) {
                System.out.printf("  %s: %s\n", f.id, f.question);
                System.out.printf("    期望: %s\n", f.groundTruthIds);
                System.out.printf("    实际: %s\n", f.retrievedIds);
            }
        }
    }

    // ==================== 指标计算 ====================

    /** Recall@K = TopK 中命中的 ground truth 数 / ground truth 总数 */
    static double recallAtK(List<String> retrieved, Set<String> groundTruth, int k) {
        Set<String> topK = new HashSet<>(retrieved.subList(0, Math.min(k, retrieved.size())));
        topK.retainAll(groundTruth);
        return groundTruth.isEmpty() ? 0 : (double) topK.size() / groundTruth.size();
    }

    /** Precision@K = TopK 中命中的 ground truth 数 / K */
    static double precisionAtK(List<String> retrieved, Set<String> groundTruth, int k) {
        Set<String> topK = new HashSet<>(retrieved.subList(0, Math.min(k, retrieved.size())));
        topK.retainAll(groundTruth);
        return (double) topK.size() / k;
    }

    /** NDCG@K */
    static double computeNDCG(List<String> retrieved, Set<String> groundTruth, int k) {
        double dcg = 0;
        for (int i = 0; i < Math.min(k, retrieved.size()); i++) {
            if (groundTruth.contains(retrieved.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2)); // log2(i+2), i 从 1 开始
            }
        }
        // 理想 DCG: 所有 ground truth 排在最前面
        int idealCount = Math.min(k, groundTruth.size());
        double idcg = 0;
        for (int i = 0; i < idealCount; i++) {
            idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    /** MRR = 第一个命中排名位置的倒数 */
    static double computeMRR(List<String> retrieved, Set<String> groundTruth) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (groundTruth.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0;
    }

    // ==================== 辅助 ====================

    static TestCase tc(String id, String question, String... groundTruthIds) {
        return new TestCase(id, question, Arrays.asList(groundTruthIds));
    }

    static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }

    // ==================== 数据类 ====================

    record TestCase(String id, String question, List<String> groundTruthIds) {}

    static class EvalResult {
        String id, question;
        double recall, precision, ndcg, mrr;
        int hits, expectedTotal;
        List<String> retrievedIds;
        List<String> groundTruthIds;

        EvalResult(String id, String question, double recall, double precision,
                   double ndcg, double mrr, int hits, int expectedTotal, List<String> retrievedIds) {
            this.id = id;
            this.question = question;
            this.recall = recall;
            this.precision = precision;
            this.ndcg = ndcg;
            this.mrr = mrr;
            this.hits = hits;
            this.expectedTotal = expectedTotal;
            this.retrievedIds = retrievedIds;
        }
    }
}
