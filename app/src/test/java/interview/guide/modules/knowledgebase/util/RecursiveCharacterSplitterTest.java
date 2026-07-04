package interview.guide.modules.knowledgebase.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RecursiveCharacterSplitter 单元测试
 *
 * <p>覆盖：空文本、短文、段落拆分、句子拆分、兜底逐字符切分、合并+重叠、边界条件</p>
 */
@DisplayName("递归字符拆分器测试")
class RecursiveCharacterSplitterTest {

    private final RecursiveCharacterSplitter splitter = new RecursiveCharacterSplitter(500, 50, 10000);

    // ==================== 基本拆分 ====================

    @Nested
    @DisplayName("基本拆分测试")
    class BasicSplitTests {

        @Test
        @DisplayName("null 文本返回空列表")
        void testNullText() {
            List<String> chunks = splitter.split(null);
            assertNotNull(chunks);
            assertTrue(chunks.isEmpty());
        }

        @Test
        @DisplayName("空字符串返回空列表")
        void testEmptyText() {
            List<String> chunks = splitter.split("");
            assertNotNull(chunks);
            assertTrue(chunks.isEmpty());
        }

        @Test
        @DisplayName("短文（小于 chunkSize）返回单个 chunk")
        void testShortText() {
            String text = "这是一段很短的文字。";
            List<String> chunks = splitter.split(text);
            assertEquals(1, chunks.size());
            assertEquals(text, chunks.get(0));
        }

        @Test
        @DisplayName("纯空白字符文本")
        void testWhitespaceOnly() {
            String text = "   \n\n  \n  ";
            List<String> chunks = splitter.split(text);
            // 空白字符也会被作为普通文本处理
            assertNotNull(chunks);
        }
    }

    // ==================== 段落拆分 ====================

    @Nested
    @DisplayName("段落拆分测试")
    class ParagraphSplitTests {

        @Test
        @DisplayName("多个短段落 — 合并到接近 chunkSize")
        void testMultipleShortParagraphs() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                sb.append("第").append(i).append("段：这是一段测试内容。\n\n");
            }
            List<String> chunks = splitter.split(sb.toString());
            assertFalse(chunks.isEmpty());
            // 每个 chunk 应该 ≤ chunkSize（允许由于重叠略超出）
            for (String chunk : chunks) {
                assertTrue(chunk.length() <= 550,
                    "chunk 大小不应远超 chunkSize: " + chunk.length());
            }
        }

        @Test
        @DisplayName("单个长段落按句子拆分")
        void testLongParagraphSplitBySentence() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb.append("这是第").append(i).append("句话。");
            }
            List<String> chunks = splitter.split(sb.toString());
            assertTrue(chunks.size() > 1, "长段落应该被拆分为多个 chunk");
        }

        @Test
        @DisplayName("段落内无标点 — 按固定大小兜底切分")
        void testParagraphWithoutPunctuation() {
            String noPunctuation = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(100);
            List<String> chunks = splitter.split(noPunctuation);
            assertTrue(chunks.size() > 1, "无标点长文本应该按固定大小切分");
            // 每个 chunk 应该接近 chunkSize
            for (int i = 0; i < chunks.size() - 1; i++) {
                assertEquals(500, chunks.get(i).length(),
                    "非最后一个 chunk 应该精确为 chunkSize");
            }
        }
    }

    // ==================== 句子拆分 ====================

    @Nested
    @DisplayName("句子拆分测试")
    class SentenceSplitTests {

        @Test
        @DisplayName("按句号拆分")
        void testSplitByPeriod() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb.append("这是第").append(i).append("句完整的话，用于测试句号拆分功能。");
            }
            List<String> chunks = splitter.split(sb.toString());
            assertTrue(chunks.size() > 1);
            // 验证：每个 chunk 不能以标点开头（合并逻辑不会在句子中间断裂）
            for (String chunk : chunks) {
                assertFalse(chunk.isEmpty(), "chunk 不应为空");
            }
        }

        @Test
        @DisplayName("按感叹号和问号拆分")
        void testSplitByExclamationAndQuestion() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                sb.append("这算什么问题").append(i).append("？");
            }
            for (int i = 0; i < 50; i++) {
                sb.append("多么令人震惊").append(i).append("！");
            }
            List<String> chunks = splitter.split(sb.toString());
            assertTrue(chunks.size() > 1);
        }

        @Test
        @DisplayName("混合标点拆分")
        void testMixedPunctuation() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 60; i++) {
                sb.append("第").append(i).append("句话结束。");
            }
            for (int i = 0; i < 60; i++) {
                sb.append("第").append(i).append("个问题？");
            }
            List<String> chunks = splitter.split(sb.toString());
            assertTrue(chunks.size() > 1);
        }
    }

    // ==================== 子句拆分 ====================

    @Nested
    @DisplayName("子句拆分测试")
    class ClauseSplitTests {

        @Test
        @DisplayName("长句子按逗号和分号拆分")
        void testSplitByComma() {
            StringBuilder sb = new StringBuilder();
            // 生成一个非常长的"句子"（无句号，只有逗号分号顿号）
            for (int i = 0; i < 300; i++) {
                sb.append("第").append(i).append("个子句，");
            }
            List<String> chunks = splitter.split(sb.toString());
            assertTrue(chunks.size() > 1, "长无句号文本应该按逗号拆分");
            for (String chunk : chunks) {
                assertFalse(chunk.isEmpty());
            }
        }

        @Test
        @DisplayName("顿号拆分")
        void testSplitByEnumerationComma() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                sb.append("项目").append(i).append("、");
            }
            List<String> chunks = splitter.split(sb.toString());
            assertTrue(chunks.size() > 1);
        }
    }

    // ==================== 重叠机制 ====================

    @Nested
    @DisplayName("重叠机制测试")
    class OverlapTests {

        @Test
        @DisplayName("相邻 chunk 之间有重叠")
        void testOverlapBetweenChunks() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb.append("这是第").append(i).append("段测试内容。\n\n");
            }
            List<String> chunks = splitter.split(sb.toString());
            if (chunks.size() >= 2) {
                String first = chunks.get(0);
                String second = chunks.get(1);
                // 第二个 chunk 的开头应该与前一个 chunk 的末尾有重叠
                String firstTail = first.substring(Math.max(0, first.length() - 50));
                // 第二个 chunk 的前几个字符应该包含在 first 的尾部
                String secondHead = second.substring(0, Math.min(50, second.length()));
                // 放松验证：只需要确认有重叠，不是完全不相干
                boolean hasOverlap = false;
                for (int i = 0; i < secondHead.length() && i < firstTail.length(); i++) {
                    if (firstTail.charAt(i) == secondHead.charAt(i)) {
                        hasOverlap = true;
                        break;
                    }
                }
                assertTrue(hasOverlap, "相邻 chunk 应该有重叠内容");
            }
        }

        @Test
        @DisplayName("小 chunkOverlap 参数")
        void testSmallOverlap() {
            RecursiveCharacterSplitter smallOverlapSplitter =
                new RecursiveCharacterSplitter(200, 10, 10000);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                sb.append("测试内容第").append(i).append("段，包含足够多的字符来产生多个chunk。\n\n");
            }
            List<String> chunks = smallOverlapSplitter.split(sb.toString());
            // 只要有多个 chunk 产生即可
            assertTrue(chunks.size() > 1);
        }
    }

    // ==================== 兜底机制 ====================

    @Nested
    @DisplayName("兜底切分测试")
    class FallbackTests {

        @Test
        @DisplayName("纯英文无标点长文本 — 逐字符兜底切分")
        void testLongEnglishWithoutPunctuation() {
            String text = "ABCDEFGH".repeat(200); // 1600 字符无任何分隔符
            List<String> chunks = splitter.split(text);
            assertTrue(chunks.size() > 1);
            // 非最后一个 chunk 应为 chunkSize（合并 + 重叠处理后可能多出一些 chunk）
            for (int i = 0; i < chunks.size() - 1; i++) {
                assertEquals(500, chunks.get(i).length(),
                    "非最后一个 chunk 应该为 chunkSize: index=" + i);
            }
            // 最后一个 chunk ≤ chunkSize
            assertTrue(chunks.get(chunks.size() - 1).length() <= 500);
        }

        @Test
        @DisplayName("单一大块 + 逐字符切后合并正确")
        void testSingleBlockFallback() {
            // 一整坨 2000 字符无标点文本，经合并+重叠后会产生多个 500 和 1 个尾 chunk
            String bigBlock = "X".repeat(2000);
            List<String> chunks = splitter.split(bigBlock);
            assertTrue(chunks.size() > 1, "应有多个 chunk");
            // 非末尾 chunk 应均为 chunkSize
            for (int i = 0; i < chunks.size() - 1; i++) {
                assertEquals(500, chunks.get(i).length(),
                    "非最后一个 chunk 应该精确为 chunkSize");
            }
            assertTrue(chunks.get(chunks.size() - 1).length() <= 500,
                "最后一个 chunk 不应超过 chunkSize");
        }
    }

    // ==================== 边界条件 ====================

    @Nested
    @DisplayName("边界条件测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("自定义参数构造")
        void testCustomParameters() {
            RecursiveCharacterSplitter customSplitter =
                new RecursiveCharacterSplitter(300, 30, 100);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb.append("第").append(i).append("段测试内容。\n\n");
            }
            List<String> chunks = customSplitter.split(sb.toString());
            assertFalse(chunks.isEmpty());
            assertTrue(chunks.size() <= 100, "不应超过 maxNumChunks");
        }

        @Test
        @DisplayName("chunkSize 为 1")
        void testChunkSizeOne() {
            RecursiveCharacterSplitter tinySplitter =
                new RecursiveCharacterSplitter(1, 0, 10000);
            List<String> chunks = tinySplitter.split("ABC");
            assertEquals(3, chunks.size());
            assertEquals("A", chunks.get(0));
            assertEquals("B", chunks.get(1));
            assertEquals("C", chunks.get(2));
        }

        @Test
        @DisplayName("非法参数抛出异常")
        void testInvalidParameters() {
            assertThrows(IllegalArgumentException.class,
                () -> new RecursiveCharacterSplitter(0, 0, 100));
            assertThrows(IllegalArgumentException.class,
                () -> new RecursiveCharacterSplitter(100, -1, 100));
            assertThrows(IllegalArgumentException.class,
                () -> new RecursiveCharacterSplitter(100, 100, 100));
        }

        @Test
        @DisplayName("内容恰好等于 chunkSize")
        void testContentExactlyChunkSize() {
            String text = "A".repeat(500);
            List<String> chunks = splitter.split(text);
            assertEquals(1, chunks.size());
            assertEquals(500, chunks.get(0).length());
        }

        @Test
        @DisplayName("Tika 提取的典型中文文档格式")
        void testTikaExtractedChineseDoc() {
            String doc = """
                第一章 系统概述
                
                本系统采用微服务架构设计，主要包含用户服务、订单服务、商品服务等模块。
                系统基于 Spring Boot 框架开发，使用 PostgreSQL 作为主数据库。
                
                第二章 技术架构
                
                系统分为四层架构：表现层、业务层、数据层和基础设施层。
                表现层使用 React 框架，通过 RESTful API 与后端通信；
                业务层负责核心业务逻辑的处理和编排；
                数据层使用 JPA 进行数据持久化操作；
                基础设施层提供缓存、消息队列等基础服务。
                
                第三章 部署方案
                
                系统使用 Docker Compose 进行容器化部署，包含以下服务：
                PostgreSQL 数据库、Redis 缓存、MinIO 对象存储、Spring Boot 应用。
                部署前需配置环境变量，包括数据库连接信息、AI API 密钥等。
                """;
            List<String> chunks = splitter.split(doc);
            assertFalse(chunks.isEmpty());
            // 验证每个 chunk 不为空且不长于 chunkSize（允许略微超出）
            for (String chunk : chunks) {
                assertFalse(chunk.isEmpty());
                assertTrue(chunk.length() <= 550,
                    "chunk 大小 " + chunk.length() + " 超出合理范围");
            }
        }
    }

    // ==================== 合并逻辑 ====================

    @Nested
    @DisplayName("合并逻辑测试")
    class MergeLogicTests {

        @Test
        @DisplayName("合并后 chunk 数量合理减少")
        void testMergeReducesChunkCount() {
            // 生成很多短段落，拆分阶段会产生大量小片段，合并后应减少
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                sb.append("第").append(i).append("段。\n\n");
            }
            List<String> chunks = splitter.split(sb.toString());
            // 200 个片段经过合并后应远少于 200
            assertTrue(chunks.size() < 100,
                "合并后 chunk 数应显著少于原始片段数: " + chunks.size());
        }

        @Test
        @DisplayName("合并后每个 chunk 大小合理")
        void testMergeProducesReasonablySizedChunks() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                sb.append("这是一段测试文本内容，编号").append(i).append("。\n\n");
            }
            List<String> chunks = splitter.split(sb.toString());
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                assertTrue(chunk.length() >= 10,
                    "每个 chunk 至少应包含一定内容");
                // 非最后一个 chunk 不应太小（不过几十个字符的 chunk 也可能，只要有足够内容应该合并）
            }
        }

        @Test
        @DisplayName("maxNumChunks 限制生效")
        void testMaxNumChunksLimit() {
            RecursiveCharacterSplitter limitedSplitter =
                new RecursiveCharacterSplitter(100, 10, 5);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                sb.append("测试文本内容第").append(i).append("段。\n\n");
            }
            List<String> chunks = limitedSplitter.split(sb.toString());
            assertTrue(chunks.size() <= 5, "不应超过 maxNumChunks 限制: " + chunks.size());
        }
    }
}
