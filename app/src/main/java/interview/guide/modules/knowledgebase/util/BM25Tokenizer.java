package interview.guide.modules.knowledgebase.util;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.dictionary.CustomDictionary;
import com.hankcs.hanlp.seg.Segment;
import com.hankcs.hanlp.seg.common.Term;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * BM25 分词器（入库和查询共用单例）
 *
 * <p>基于 HanLP portable 实现中文分词，加载自定义技术词典确保专业术语不被错误切分。
 * 例如："轻量级锁" 不会被切成 ["轻量级", "锁"]，而是作为一个完整术语。</p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * @Autowired
 * private BM25Tokenizer bm25Tokenizer;
 *
 * List<String> terms = bm25Tokenizer.tokenize("ConcurrentHashMap 的 put 方法如何保证线程安全？");
 * // → ["ConcurrentHashMap", "put", "方法", "如何", "保证", "线程安全"]
 * }</pre>
 *
 * <h3>分词规则</h3>
 * <ol>
 *   <li>自定义技术词典优先（enableCustomDictionaryForcing=true）</li>
 *   <li>过滤停用词和纯标点</li>
 *   <li>过滤单字符（无法提供有效 BM25 权重）</li>
 *   <li>启用人名识别和地名识别</li>
 * </ol>
 */
@Component
@Slf4j
public class BM25Tokenizer {

    /** 自定义技术词典路径（classpath 相对路径） */
    private static final String DICT_PATH = "dict/tech-terms.txt";

    /** 纯数字模式（过滤无意义的纯数字 token） */
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^\\d+(\\.\\d+)?$");

    /** 纯标点/空白模式 */
    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile("^[\\p{P}\\p{S}\\s]+$");

    /** 英文/数字/下划线混合词模式（保留如 ForwardingNode、ConsumeQueue） */
    private static final Pattern CODE_IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    /**
     * 常见中文停用词（BM25 场景精简版，仅过滤极高频无意义词）
     * <p>注意：不包含 "的"、"是"、"在" 等词，因为它们在中文中承载重要语法信息，
     * 且逆向文档频率（IDF）自然会降低它们的权重</p>
     */
    private static final Set<String> STOP_WORDS = Set.of(
        "的", "了", "在", "是", "我", "有", "和", "就",
        "不", "人", "都", "一", "一个", "上", "也", "很",
        "到", "说", "要", "去", "你", "会", "着", "没有",
        "看", "好", "自己", "这", "他", "她", "它", "们",
        "那", "哪", "什么", "怎么", "为什么", "可以",
        "这个", "那个", "这些", "那些", "这样", "那样",
        "啊", "吧", "吗", "呢", "哦", "嗯"
    );

    private Segment segment;

    @PostConstruct
    public void init() {
        log.info("开始初始化 BM25Tokenizer...");

        // 1. 加载自定义技术词典
        loadCustomDictionary();

        // 2. 创建分词器实例（启用自定义词典强制分词模式 + 全模式）
        segment = HanLP.newSegment()
            .enableCustomDictionaryForcing(true)  // 自定义词典优先级最高
            .enableNameRecognize(true)            // 启用人名识别
            .enablePlaceRecognize(false)          // 技术文档不需要地名识别
            .enableOrganizationRecognize(false)   // 技术文档不需要机构名识别
            .enableTranslatedNameRecognize(true); // 启用音译名识别（如 epoll、Netty）

        log.info("BM25Tokenizer 初始化完成，分词器已就绪");
    }

    /**
     * 对文本进行分词（入库和查询共用）
     *
     * @param text 待分词的文本，null 返回空列表
     * @return 过滤后的词项列表（去除停用词、纯数字、纯标点、单字符）
     */
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // 开启分词
        List<Term> terms = segment.seg(text);

        // 过滤筛选结果
        return terms.stream()
            .map(term -> term.word.trim())
            .filter(this::isValidTerm)
            .collect(Collectors.toList());
    }

    // ==================== 私有方法 ====================

    /**
     * 从 classpath 加载自定义技术词典
     * <p>词典格式：每行一个词，支持 # 开头的注释行和空行</p>
     */
    private void loadCustomDictionary() {
        int count = 0;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(DICT_PATH)) {
            if (is == null) {
                log.warn("技术词典文件不存在: {}（classpath 中未找到），将使用 HanLP 默认词典", DICT_PATH);
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    // 跳过空行和注释行
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    // 去除行内注释（# 后面的内容）
                    int commentIndex = line.indexOf('#');
                    if (commentIndex > 0) {
                        line = line.substring(0, commentIndex).trim();
                    }
                    if (line.isEmpty()) {
                        continue;
                    }

                    CustomDictionary.insert(line);
                    count++;
                }
            }

            log.info("成功加载技术词典，词条数量: {}（路径: {}）", count, DICT_PATH);
        } catch (IOException e) {
            log.error("加载技术词典失败: {}", DICT_PATH, e);
        }
    }

    /**
     * 判断 token 是否为有效词项
     * <p>过滤条件：
     * <ul>
     *   <li>空字符串 → 无效</li>
     *   <li>停用词 → 无效</li>
     *   <li>纯数字 → 无效</li>
     *   <li>纯标点/空白 → 无效</li>
     *   <li>单字符中文 → 无效（单个中文字符检索价值低）</li>
     *   <li>代码标识符（英文/数字）→ 保留</li>
     * </ul>
     */
    private boolean isValidTerm(String word) {
        if (word.isEmpty()) {
            return false;
        }
        if (STOP_WORDS.contains(word)) {
            return false;
        }
        if (NUMERIC_PATTERN.matcher(word).matches()) {
            return false;
        }
        if (PUNCTUATION_PATTERN.matcher(word).matches()) {
            return false;
        }
        // 代码标识符（如 ConcurrentHashMap、epoll）始终保留
        if (CODE_IDENTIFIER_PATTERN.matcher(word).matches()) {
            return true;
        }
        // 单字符过滤：非代码标识符的单字符（通常是中文单字）检索价值低
        if (word.length() == 1) {
            return false;
        }

        return true;
    }
}
