package interview.guide.modules.knowledgebase.rerank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DashScopeRerankService 测试
 *
 * <p>分两类：
 * <ul>
 *   <li>纯单元测试（不需要真实 API）：验证降级、空候选、截断等逻辑</li>
 *   <li>连通性测试（需要 AI_BAILIAN_API_KEY 环境变量）：验证 qwen3-rerank API 真实调用</li>
 * </ul>
 *
 * <p>连通性测试默认跳过，设置环境变量 {@code AI_BAILIAN_API_KEY} 后才会执行：
 * <pre>
 * # Windows
 * set AI_BAILIAN_API_KEY=sk-xxxxx
 * gradlew.bat test --tests "*.DashScopeRerankServiceTest"
 * </pre>
 * </p>
 */
@DisplayName("DashScopeRerankService 精排服务测试")
class DashScopeRerankServiceTest {

    // ==================== 纯单元测试（不需要真实 API） ====================

    @Test
    @DisplayName("空候选列表 → 返回空列表，不调 API")
    void emptyCandidates() {
        DashScopeRerankService service = buildServiceWithInvalidEndpoint();
        List<Document> result = service.rerank("test", List.of(), 5);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("null 候选列表 → 返回空列表")
    void nullCandidates() {
        DashScopeRerankService service = buildServiceWithInvalidEndpoint();
        List<Document> result = service.rerank("test", null, 5);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("API 调用失败 → 降级返回候选前 topN 条（不抛异常）")
    void fallbackOnApiFailure() {
        DashScopeRerankService service = buildServiceWithInvalidEndpoint();
        List<Document> candidates = buildDocuments("doc1", "doc2", "doc3", "doc4", "doc5");

        List<Document> result = service.rerank("test query", candidates, 3);

        assertThat(result).as("降级应返回候选前 3 条").hasSize(3);
        assertThat(result.get(0).getText()).isEqualTo("doc1");
        assertThat(result.get(1).getText()).isEqualTo("doc2");
        assertThat(result.get(2).getText()).isEqualTo("doc3");
    }

    @Test
    @DisplayName("API 失败时 topN > 候选数 → 返回全部候选")
    void fallbackTopNExceedsCandidates() {
        DashScopeRerankService service = buildServiceWithInvalidEndpoint();
        List<Document> candidates = buildDocuments("doc1", "doc2");

        List<Document> result = service.rerank("test", candidates, 10);

        assertThat(result).as("候选只有 2 条，降级返回全部").hasSize(2);
    }

    @Test
    @DisplayName("候选数超过 maxDocumentsPerCall → 截断后再调 API（降级验证）")
    void candidatesTruncated() {
        RerankConfigProperties config = new RerankConfigProperties();
        config.setEndpoint("http://localhost:1/invalid"); // 必然失败
        config.setMaxDocumentsPerCall(3);
        config.setConnectTimeout(500);
        config.setReadTimeout(500);
        DashScopeRerankService service = new DashScopeRerankService(config, "fake-key");

        // 传 5 条候选，maxDocumentsPerCall=3，降级应只返回截断后的前 topN 条
        List<Document> candidates = buildDocuments("d1", "d2", "d3", "d4", "d5");
        List<Document> result = service.rerank("test", candidates, 3);

        assertThat(result).hasSize(3);
        // 截断后只剩 d1/d2/d3，降级返回前 3 条
        assertThat(result).extracting(Document::getText).containsExactly("d1", "d2", "d3");
    }

    // ==================== 连通性测试（需要真实 API key） ====================

    @Test
    @EnabledIfEnvironmentVariable(named = "AI_BAILIAN_API_KEY", matches = ".+")
    @DisplayName("[连通性] 真实调用 qwen3-rerank：3 条文档取 top 2，验证返回和排序")
    void realApiCall() {
        DashScopeRerankService service = buildServiceWithRealKey();

        List<Document> candidates = List.of(
                Document.builder().text("云计算是一种通过互联网按需提供计算资源的服务模式").build(),
                Document.builder().text("进程是程序在执行过程中的实例，拥有独立的内存空间和系统资源").build(),
                Document.builder().text("线程是进程内的执行单元，共享进程的内存空间").build()
        );

        List<Document> result = service.rerank("什么是进程", candidates, 2);

        assertThat(result).as("top_n=2 应返回 2 条").hasSize(2);
        // 最相关的应该是关于"进程"的文档
        assertThat(result.get(0).getText()).as("排名第一应与'进程'最相关").contains("进程");
        logResult("realApiCall", candidates, result);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AI_BAILIAN_API_KEY", matches = ".+")
    @DisplayName("[连通性] 验证响应解析：index 正确映射回原始 Document")
    void realApiIndexMapping() {
        DashScopeRerankService service = buildServiceWithRealKey();

        List<Document> candidates = List.of(
                Document.builder().text("Java 是一种面向对象的编程语言").build(),       // index 0
                Document.builder().text("Python 是一种解释型高级编程语言").build(),     // index 1
                Document.builder().text("Spring Boot 是 Java 生态的 Web 框架").build()  // index 2
        );

        List<Document> result = service.rerank("Java Spring 框架", candidates, 3);

        assertThat(result).isNotEmpty();
        // 返回的文档应该都是原始候选中的（不是 null 或错位）
        assertThat(result).allMatch(doc -> candidates.contains(doc));
        logResult("realApiIndexMapping", candidates, result);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AI_BAILIAN_API_KEY", matches = ".+")
    @DisplayName("[连通性] top_n=1 只返回 1 条最相关文档")
    void realApiTopN1() {
        DashScopeRerankService service = buildServiceWithRealKey();

        List<Document> candidates = List.of(
                Document.builder().text("Redis 是内存键值数据库").build(),
                Document.builder().text("PostgreSQL 是关系型数据库").build(),
                Document.builder().text("Kafka 是分布式消息队列").build()
        );

        List<Document> result = service.rerank("什么是 Redis", candidates, 1);

        assertThat(result).as("top_n=1 应只返回 1 条").hasSize(1);
        assertThat(result.get(0).getText()).contains("Redis");
        logResult("realApiTopN1", candidates, result);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AI_BAILIAN_API_KEY", matches = ".+")
    @DisplayName("[连通性] 中文长文档精排：验证 120K token 上限内正常工作")
    void realApiLongDocuments() {
        DashScopeRerankService service = buildServiceWithRealKey();

        // 模拟 RAG 场景：30 条候选文档（每条约 200 字）
        List<Document> candidates = IntStream.range(0, 30)
                .mapToObj(i -> Document.builder()
                        .text("这是第 " + i + " 段技术文档内容。"
                                + "操作系统是管理计算机硬件与软件资源的程序，"
                                + "进程调度是操作系统的核心功能之一，"
                                + "负责决定哪个进程在何时获得 CPU 执行权。"
                                + "常见的调度算法包括先来先服务、短作业优先、时间片轮转等。")
                        .build())
                .toList();

        List<Document> result = service.rerank("操作系统进程调度算法", candidates, 5);

        assertThat(result).as("30 条候选取 top 5").hasSize(5);
        logResult("realApiLongDocuments", candidates, result);
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建指向无效端点的 Service（用于降级测试，API 调用必然失败）
     */
    private DashScopeRerankService buildServiceWithInvalidEndpoint() {
        RerankConfigProperties config = new RerankConfigProperties();
        config.setEndpoint("http://localhost:1/invalid-endpoint");
        config.setConnectTimeout(500);
        config.setReadTimeout(500);
        return new DashScopeRerankService(config, "fake-key");
    }

    /**
     * 构建使用真实 API key 的 Service（用于连通性测试）
     */
    private DashScopeRerankService buildServiceWithRealKey() {
        String apiKey = System.getenv("AI_BAILIAN_API_KEY");
        return new DashScopeRerankService(new RerankConfigProperties(), apiKey);
    }

    /**
     * 构建测试用 Document 列表
     */
    private List<Document> buildDocuments(String... texts) {
        return java.util.Arrays.stream(texts)
                .map(text -> Document.builder().text(text).build())
                .toList();
    }

    private void logResult(String tag, List<Document> input, List<Document> output) {
        System.out.println("========== " + tag + " ==========");
        System.out.println("输入 " + input.size() + " 条候选:");
        for (int i = 0; i < input.size(); i++) {
            System.out.printf("  [%d] %s%n", i, truncate(input.get(i).getText(), 60));
        }
        System.out.println("输出 " + output.size() + " 条精排结果:");
        for (int i = 0; i < output.size(); i++) {
            System.out.printf("  [%d] %s%n", i, truncate(output.get(i).getText(), 60));
        }
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
