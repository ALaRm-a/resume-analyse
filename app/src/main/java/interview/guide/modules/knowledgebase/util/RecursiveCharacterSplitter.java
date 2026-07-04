package interview.guide.modules.knowledgebase.util;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 递归字符拆分器（Recursive Character Splitter）
 *
 * <p>模仿 LangChain RecursiveCharacterTextSplitter 的两阶段拆分策略：</p>
 * <ol>
 *   <li><b>递归拆分阶段</b>：按分隔符优先级（段落 → 换行 → 句子结束 → 子句分隔 → 空格 → 逐字符兜底）
 *       递归切分文本，对超长片段按下一优先级继续切分，保证每个片段在语义边界处断开</li>
 *   <li><b>合并阶段</b>：将递归拆分得到的零散片段按目标 chunk 大小重新拼接，
 *       同时实现相邻 chunk 间的重叠，保证输出 chunk 大小均匀</li>
 * </ol>
 *
 * <p>适用场景：Tika 提取的纯文本（保留 \\n\\n 和 \\n），配合 pgvector 做 RAG 检索</p>
 */
@Slf4j
public class RecursiveCharacterSplitter {

    /**
     * 分隔符优先级列表（从高到低）：
     * <ul>
     *   <li>"\n\n"  — 段落边界（最强语义分割）</li>
     *   <li>"\n"    — 换行</li>
     *   <li>"[。！？]" — 中文句子结束标点</li>
     *   <li>"[；，、]" — 中文子句分隔标点</li>
     *   <li>" "     — 空格</li>
     *   <li>""      — 兜底：逐字符硬切</li>
     * </ul>
     * 以 {@code [xxx]} 包裹的项表示字符集（正则模式），按其中任意字符切分。
     */
    private static final String[] SEPARATORS = {
        "\n\n",           // 段落
        "\n",             // 换行
        "[。！？]",        // 句子结束
        "[；，、]",        // 子句分隔
        " ",              // 空格
        ""                // 兜底：逐字符切分
    };

    /** 默认 chunk 目标大小（字符数），约等价于原 300 token */
    static final int DEFAULT_CHUNK_SIZE = 500;
    /** 默认相邻 chunk 重叠字符数 */
    static final int DEFAULT_CHUNK_OVERLAP = 50;
    /** 默认最大 chunk 数 */
    static final int DEFAULT_MAX_NUM_CHUNKS = 10000;

    private final int chunkSize;
    private final int chunkOverlap;
    private final int maxNumChunks;

    /**
     * 使用默认参数构造拆分器（chunkSize=500, overlap=50, maxChunks=10000）
     */
    public RecursiveCharacterSplitter() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP, DEFAULT_MAX_NUM_CHUNKS);
    }

    /**
     * @param chunkSize    目标 chunk 大小（字符数）
     * @param chunkOverlap 相邻 chunk 重叠字符数
     * @param maxNumChunks 最大允许的 chunk 数量
     */
    public RecursiveCharacterSplitter(int chunkSize, int chunkOverlap, int maxNumChunks) {
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize must be positive");
        if (chunkOverlap < 0) throw new IllegalArgumentException("chunkOverlap must be non-negative");
        if (chunkOverlap >= chunkSize) throw new IllegalArgumentException("chunkOverlap must be less than chunkSize");
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.maxNumChunks = maxNumChunks;
    }

    // ==================== 公开方法 ====================

    /**
     * 入口方法：拆分文本并合并
     *
     * @param text 原始文本（null 或空字符串返回空列表）
     * @return 拆分后的 chunk 列表
     */
    public List<String> split(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        // 第一阶段：递归按分隔符拆碎
        List<String> rawSplits = splitRecursive(text, 0);
        log.debug("递归拆分完成，片段数: {}", rawSplits.size());
        // 第二阶段：合并小片段 + 加重叠
        List<String> chunks = mergeSplits(rawSplits);
        log.debug("合并完成，chunk 数: {}", chunks.size());
        return chunks;
    }

    // ==================== 递归拆分 ====================

    /**
     * 递归拆分：按分隔符优先级逐级切分
     * <p>
     * 对每个片段：
     * <ul>
     *   <li>若长度 ≤ chunkSize → 直接保留</li>
     *   <li>若长度 > chunkSize → 用下一优先级分隔符递归继续切分</li>
     *   <li>全部优先级试完后仍超长 → 按 chunkSize 逐字符硬切</li>
     * </ul>
     */
    private List<String> splitRecursive(String text, int separatorIndex) {
        // 所有分隔符都已尝试，逐字符兜底
        if (separatorIndex >= SEPARATORS.length) {
            return splitByFixedSize(text);
        }

        String separator = SEPARATORS[separatorIndex];
        List<String> splits = doSplit(text, separator);

        List<String> result = new ArrayList<>();
        for (String s : splits) {
            if (s.isEmpty()) {
                continue;
            }
            if (s.length() <= chunkSize) {
                result.add(s);
            } else {
                // 超长片段，用下一优先级继续递归
                // addALL 把列表里面的元素都取出来，然后再添加到result中，不是返回集合添加
                result.addAll(splitRecursive(s, separatorIndex + 1));
            }
        }
        return result;
    }

    // ==================== 合并阶段 ====================

    /**
     * 合并片段：将递归拆分得到的零散片段按 chunkSize 重新拼接，同时实现重叠
     *
     * <p>与 LangChain 不同之处：拼接后通过 while 循环统一处理溢出，
     * 避免 overlap + 大片段时形成超大 chunk 或产生过小的碎片 chunk</p>
     */
    private List<String> mergeSplits(List<String> splits) {
        if (splits.isEmpty()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String split : splits) {
            current.append(split);

            // 统一处理溢出：将超出部分递归拆分为合法大小
            // 每次取前 chunkSize 字符保存，剩余部分与新 chunk 开头的 overlap 拼接后继续
            while (current.length() > chunkSize) {
                String saved = current.substring(0, chunkSize);
                chunks.add(saved);
                if (chunks.size() >= maxNumChunks) {
                    return chunks;
                }
                // 重叠取自刚保存 chunk 的末尾，与剩余部分拼接构成新 chunk 的开头
                String overlapPart = saved.substring(Math.max(0, chunkSize - chunkOverlap));
                String remainder = current.substring(chunkSize);
                current = new StringBuilder(overlapPart).append(remainder);
            }
        }

        // 最后一个 chunk（可能不足 chunkSize）
        if (current.length() > 0) {
            chunks.add(current.toString());
        }

        return chunks;
    }

    // ==================== 底层切分 ====================

    /**
     * 按指定分隔符切分文本
     *
     * @param text      待切分文本
     * @param separator 分隔符：空字符串→逐字符切，[xxx]→正则字符集，其他→字面量
     */
    private List<String> doSplit(String text, String separator) {
        if (text.isEmpty()) {
            return List.of();
        }
        // 兜底：逐字符切分
        if (separator.isEmpty()) {
            return splitByFixedSize(text);
        }

        String[] parts;
        if (separator.startsWith("[") && separator.endsWith("]")) {
            // 字符集分隔符（如 [。！？]）—— 使用正向后顾在每个标点后切分，保留标点
            // 直接使用split方法的话，对于(".","|")具有特殊含义，表示任意字符或者，需要当作普通字符处理pattern.quote
            String chars = separator.substring(1, separator.length() - 1);
            parts = text.split("(?<=[" + Pattern.quote(chars) + "])");
        } else {
            // 字面量分隔符（如 \n\n、\n、空格）
            parts = text.split(Pattern.quote(separator), -1);
        }
        return new ArrayList<>(List.of(parts));
    }

    /**
     * 按固定大小逐字符切分（兜底手段，无任何分隔符时使用）
     */
    private List<String> splitByFixedSize(String text) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, text.length());
            result.add(text.substring(i, end));
        }
        return result;
    }
}
