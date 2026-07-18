package interview.guide.modules.agent.memory.service;

import interview.guide.modules.agent.memory.config.AgentMemoryProperties;
import interview.guide.modules.agent.memory.dto.CompressibleMessage;
import interview.guide.modules.agent.memory.model.ConversationSummaryEntity;
import interview.guide.modules.agent.memory.repository.ConversationSummaryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆压缩服务 — 负责将过长的对话历史增量压缩为摘要。
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li>不依赖任何业务模块的 Entity/Repository</li>
 *   <li>消息内容由调用方以 {@link CompressibleMessage} 列表传入</li>
 *   <li>基于上次摘要的 endMsgOrder 做增量压缩，避免重复处理旧消息</li>
 *   <li>真正的 LLM 调用通过 Spring AOP 代理走 {@code @Async}，不阻塞主流程</li>
 *   <li>压缩提示词从外部 .st 文件加载，便于调优迭代</li>
 * </ul>
 */
@Slf4j
@Service
public class MemoryCompressService {

    private final ConversationSummaryRepository summaryRepository;
    private final ChatClient chatClient;
    private final AgentMemoryProperties properties;

    /** 首次压缩提示词模板（无旧摘要） */
    private final PromptTemplate initialPromptTemplate;

    /** 增量压缩提示词模板（合并旧摘要） */
    private final PromptTemplate mergePromptTemplate;

    /** 通过 @Lazy 自注入，确保类内调用也走 Spring AOP 代理，@Async 生效 */
    @Lazy
    @Autowired
    private MemoryCompressService self;

    public MemoryCompressService(
            ConversationSummaryRepository summaryRepository,
            ChatClient.Builder chatClientBuilder,
            AgentMemoryProperties properties,
            @Value("classpath:prompts/memory-compress-initial.st") Resource initialPromptResource,
            @Value("classpath:prompts/memory-compress-merge.st") Resource mergePromptResource)
            throws IOException {
        this.summaryRepository = summaryRepository;
        this.chatClient = chatClientBuilder.build();
        this.properties = properties;
        this.initialPromptTemplate = new PromptTemplate(
                initialPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.mergePromptTemplate = new PromptTemplate(
                mergePromptResource.getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 检查是否需要压缩，并通过代理异步触发真正的压缩任务。
     *
     * @param source         业务来源标识
     * @param sessionId      会话ID
     * @param totalMessages  当前会话消息总数
     * @param messages       当前会话消息（按 order 正序）
     */
    public void compressIfNeeded(String source, Long sessionId, int totalMessages,
            List<CompressibleMessage> messages) {
        if (totalMessages <= properties.getWindowSize()) {
            return;
        }
        if (totalMessages <= properties.getTriggerThreshold()) {
            return;
        }
        if (messages == null || messages.isEmpty()) {
            return;
        }

        int firstOrder = messages.get(0).order();
        int lastOrder = messages.get(messages.size() - 1).order();
        int rawOverflowEnd = lastOrder - properties.getWindowSize();

        // 边界对齐到"问+答"完整对：右边界必须是奇数（ASSISTANT 收尾）
        int overflowEnd = (rawOverflowEnd % 2 == 0)
                ? rawOverflowEnd - 1
                : rawOverflowEnd;
        if (overflowEnd < firstOrder) {
            return;
        }

        // 从上一次摘要的结束位置 + 1 开始
        ConversationSummaryEntity existing = summaryRepository
                .findBySourceAndSessionId(source, sessionId).orElse(null);
        int rawStartOrder = (existing != null)
                ? Math.max(existing.getEndMsgOrder() + 1, firstOrder)
                : firstOrder;
        // 左边界必须是偶数（USER 起步）
        int startOrder = (rawStartOrder % 2 != 0) ? rawStartOrder + 1 : rawStartOrder;

        if (startOrder > overflowEnd) {
            return;
        }

        int overflowCount = overflowEnd - startOrder + 1;
        if (existing != null) {
            // 增量压缩：攒批下限 + 强制兜底
            if (overflowCount < properties.getCompressBatch()
                    && overflowCount < properties.getWindowSize()) {
                return;
            }
        }
        // 首次压缩：existing == null 时跳过攒批门槛，进阈值即压

        List<CompressibleMessage> overflowMessages = messages.stream()
                .filter(m -> m.order() >= startOrder && m.order() <= overflowEnd)
                .limit(properties.getCompressBatch())
                .toList();

        // limit 截断后末尾可能是 USER（偶数），需再次对齐到 ASSISTANT
        int limitedEnd = overflowMessages.get(overflowMessages.size() - 1).order();
        if (limitedEnd % 2 == 0) {
            int alignedEnd = limitedEnd - 1;
            final int fe = alignedEnd;
            overflowMessages = overflowMessages.stream()
                    .filter(m -> m.order() <= fe)
                    .toList();
            if (overflowMessages.isEmpty()) {
                return;
            }
        }

        if (overflowMessages.isEmpty()) {
            return;
        }

        // 通过代理调用，@Async 与 @Transactional 才会生效
        self.triggerCompress(source, sessionId, overflowMessages, startOrder);
    }

    /**
     * 异步执行压缩（不阻塞回答流）。
     *
     * <p>本方法必须由外部通过 Spring AOP 代理调用，或经由 {@code self}
     * 自注入调用。禁止在类内直接调用，否则 @Async 会失效。</p>
     */
    @Async("memoryCompressExecutor")
    @Transactional
    public void triggerCompress(String source, Long sessionId,
            List<CompressibleMessage> messages, int startOrder) {
        log.info("开始异步压缩: source={}, sessionId={}, messages={}, startOrder={}",
                source, sessionId, messages.size(), startOrder);

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ConversationSummaryEntity existing = summaryRepository
                        .findBySourceAndSessionId(source, sessionId).orElse(null);

                int endOrder = messages.get(messages.size() - 1).order();
                // 防御性二次校验：endOrder 必须是奇数（ASSISTANT）
                if (endOrder % 2 == 0) {
                    endOrder = Math.max(startOrder, endOrder - 1);
                    final int fe = endOrder;
                    messages = messages.stream()
                            .filter(m -> m.order() <= fe)
                            .toList();
                    if (messages.isEmpty()) {
                        log.warn("对齐后待压缩消息为空: source={}, sessionId={}", source, sessionId);
                        return;
                    }
                }
                List<String> newTexts = messages.stream()
                        .map(CompressibleMessage::text)
                        .toList();
                String newSummary = generateSummary(existing, newTexts);

                if (existing != null) {
                    existing.setSummaryText(newSummary);
                    existing.setEndMsgOrder(endOrder);
                    summaryRepository.save(existing);
                } else {
                    ConversationSummaryEntity entity = new ConversationSummaryEntity();
                    entity.setSource(source);
                    entity.setSessionId(sessionId);
                    entity.setSummaryText(newSummary);
                    entity.setStartMsgOrder(startOrder);
                    entity.setEndMsgOrder(endOrder);
                    summaryRepository.save(entity);
                }

                log.info("压缩完成: source={}, sessionId={}, 覆盖 msg {}~{}",
                        source, sessionId, startOrder, endOrder);
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt == 0) {
                    log.warn("压缩乐观锁冲突，重试一次: source={}, sessionId={}", source, sessionId);
                    continue;
                }
                log.error("压缩乐观锁冲突，重试仍失败: source={}, sessionId={}", source, sessionId, e);
            } catch (Exception e) {
                log.error("压缩失败: source={}, sessionId={}", source, sessionId, e);
                return;
            }
        }
    }

    /**
     * 调用 AI 生成/合并摘要。
     */
    private String generateSummary(ConversationSummaryEntity existing,
            List<String> newMessages) {

        String newText = String.join("\n", newMessages);

        Map<String, Object> variables = new HashMap<>();
        String prompt;
        if (existing != null) {
            variables.put("oldSummary", existing.getSummaryText());
            variables.put("newMessages", newText);
            prompt = mergePromptTemplate.render(variables);
        } else {
            variables.put("newMessages", newText);
            prompt = initialPromptTemplate.render(variables);
        }

        return chatClient.prompt().user(prompt).call().content();
    }

    // 注意：本方法仅用于测试时直接调用 prompt 生成逻辑，不对外暴露
    String renderPromptForTest(ConversationSummaryEntity existing, List<String> newMessages) {
        return generateSummary(existing, newMessages);
    }
}
