package interview.guide.modules.agent.memory.service;

import interview.guide.modules.agent.memory.config.AgentMemoryProperties;
import interview.guide.modules.agent.memory.dto.CompressibleMessage;
import interview.guide.modules.agent.memory.model.ConversationSummaryEntity;
import interview.guide.modules.agent.memory.repository.ConversationSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MemoryCompressService} 单元测试。
 *
 * <p>覆盖：正常数据请求下的参数校验、异常数据输入、异常处理（乐观锁重试）生效情况。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class MemoryCompressServiceTest {

    private static final String SOURCE = "rag_chat";
    private static final Long SESSION_ID = 100L;

    @Mock
    private ConversationSummaryRepository summaryRepository;

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private AgentMemoryProperties properties;
    private MemoryCompressService service;
    private MemoryCompressService selfSpy;

    @BeforeEach
    void setUp() throws IOException {
        properties = new AgentMemoryProperties();
        properties.setWindowSize(50);
        properties.setCompressBatch(30);
        properties.setTriggerThreshold(60);
        properties.validate();

        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("摘要");

        service = new MemoryCompressService(
                summaryRepository,
                chatClientBuilder,
                properties,
                new ClassPathResource("prompts/memory-compress-initial.st"),
                new ClassPathResource("prompts/memory-compress-merge.st"));

        // 用 spy 替代 self，使 compressIfNeeded 调用可被验证，同时不真正执行异步逻辑
        selfSpy = mock(MemoryCompressService.class);
        ReflectionTestUtils.setField(service, "self", selfSpy);
    }

    // ===== 正常数据：不触发压缩 =====

    @Test
    @DisplayName("totalMessages <= windowSize 时不触发压缩")
    void compressIfNeeded_totalMessagesNotExceedWindowSize_shouldNotTrigger() {
        List<CompressibleMessage> messages = buildMessages(0, 49);

        service.compressIfNeeded(SOURCE, SESSION_ID, 50, messages);

        verify(selfSpy, never()).triggerCompress(anyString(), any(), any(), any(Integer.class));
    }

    @Test
    @DisplayName("totalMessages <= triggerThreshold 时不触发压缩")
    void compressIfNeeded_totalMessagesNotExceedThreshold_shouldNotTrigger() {
        List<CompressibleMessage> messages = buildMessages(0, 59);

        service.compressIfNeeded(SOURCE, SESSION_ID, 60, messages);

        verify(selfSpy, never()).triggerCompress(anyString(), any(), any(), any(Integer.class));
    }

    // ===== 异常数据 =====

    @Test
    @DisplayName("messages 为 null 时不触发压缩")
    void compressIfNeeded_nullMessages_shouldNotTrigger() {
        service.compressIfNeeded(SOURCE, SESSION_ID, 61, null);

        verify(selfSpy, never()).triggerCompress(anyString(), any(), any(), any(Integer.class));
    }

    @Test
    @DisplayName("messages 为空时不触发压缩")
    void compressIfNeeded_emptyMessages_shouldNotTrigger() {
        service.compressIfNeeded(SOURCE, SESSION_ID, 61, List.of());

        verify(selfSpy, never()).triggerCompress(anyString(), any(), any(), any(Integer.class));
    }

    // ===== 正常数据：首次压缩特例 =====

    @Test
    @DisplayName("首次压缩：61 条消息时触发，覆盖 0~9（10 条），endOrder=9")
    void compressIfNeeded_firstCompressionAt61_shouldCover0To9() {
        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.empty());

        List<CompressibleMessage> messages = buildMessages(0, 60);

        service.compressIfNeeded(SOURCE, SESSION_ID, 61, messages);

        ArgumentCaptor<List<CompressibleMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(selfSpy).triggerCompress(eq(SOURCE), eq(SESSION_ID), captor.capture(), eq(0));

        List<CompressibleMessage> captured = captor.getValue();
        assertThat(captured).hasSize(10);
        assertThat(captured.get(0).order()).isEqualTo(0);
        assertThat(captured.get(captured.size() - 1).order()).isEqualTo(9);
    }

    @Test
    @DisplayName("首次压缩：overflowEnd 落在偶数时应对齐到前一个奇数")
    void compressIfNeeded_firstCompressionWithEvenOverflowEnd_shouldAlignToOdd() {
        // 让 totalMessages=61 但 lastOrder=60，windowSize=50 -> rawOverflowEnd=10（偶）-> 9
        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.empty());

        List<CompressibleMessage> messages = buildMessages(0, 60);
        service.compressIfNeeded(SOURCE, SESSION_ID, 61, messages);

        ArgumentCaptor<List<CompressibleMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(selfSpy).triggerCompress(anyString(), any(), captor.capture(), any(Integer.class));

        assertThat(captor.getValue().get(captor.getValue().size() - 1).order()).isEqualTo(9);
    }

    // ===== 正常数据：增量压缩攒批 =====

    @Test
    @DisplayName("增量压缩：71 条不触发（攒批不足 30）")
    void compressIfNeeded_incrementalAt71_shouldNotTrigger() {
        ConversationSummaryEntity existing = new ConversationSummaryEntity();
        existing.setSource(SOURCE);
        existing.setSessionId(SESSION_ID);
        existing.setEndMsgOrder(9);
        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.of(existing));

        // 71 条 -> order 0..70, existing 已覆盖 0..9，溢出部分只有 10..19 共 10 条
        List<CompressibleMessage> messages = buildMessages(0, 70);
        service.compressIfNeeded(SOURCE, SESSION_ID, 71, messages);

        verify(selfSpy, never()).triggerCompress(anyString(), any(), any(), any(Integer.class));
    }

    @Test
    @DisplayName("增量压缩：90 条触发，覆盖 10~39（30 条）")
    void compressIfNeeded_incrementalAt90_shouldCover10To39() {
        ConversationSummaryEntity existing = new ConversationSummaryEntity();
        existing.setEndMsgOrder(9);
        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.of(existing));

        // 90 条 -> order 0..89, overflowCount = 30
        List<CompressibleMessage> messages = buildMessages(0, 89);
        service.compressIfNeeded(SOURCE, SESSION_ID, 90, messages);

        ArgumentCaptor<List<CompressibleMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(selfSpy).triggerCompress(eq(SOURCE), eq(SESSION_ID), captor.capture(), eq(10));

        List<CompressibleMessage> captured = captor.getValue();
        assertThat(captured).hasSize(30);
        assertThat(captured.get(0).order()).isEqualTo(10);
        assertThat(captured.get(captured.size() - 1).order()).isEqualTo(39);
    }

    @Test
    @DisplayName("增量压缩：startOrder 由 existing.endMsgOrder+1 决定，且对齐为偶数")
    void compressIfNeeded_incrementalStartOrder_shouldAlignToEven() {
        // existing.endMsgOrder 为偶数 8，+1 为奇数 9，应被对齐为 10
        ConversationSummaryEntity existing = new ConversationSummaryEntity();
        existing.setEndMsgOrder(8);
        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.of(existing));

        List<CompressibleMessage> messages = buildMessages(0, 89);
        service.compressIfNeeded(SOURCE, SESSION_ID, 90, messages);

        verify(selfSpy).triggerCompress(eq(SOURCE), eq(SESSION_ID), any(), eq(10));
    }

    // ===== 边界对齐：limit 截断后二次对齐 =====

    @Test
    @DisplayName("limit 截断后末尾为偶数时，应二次对齐到前一个奇数")
    void compressIfNeeded_limitEndIsEven_shouldAlignDownToOdd() throws IOException {
        // 首次压缩特例：61 条，但把 compressBatch 调小使 limit 落在偶数
        properties.setCompressBatch(4);
        properties.validate();
        service = new MemoryCompressService(
                summaryRepository,
                chatClientBuilder,
                properties,
                new ClassPathResource("prompts/memory-compress-initial.st"),
                new ClassPathResource("prompts/memory-compress-merge.st"));
        selfSpy = mock(MemoryCompressService.class);
        ReflectionTestUtils.setField(service, "self", selfSpy);

        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.empty());

        List<CompressibleMessage> messages = buildMessages(0, 60);
        service.compressIfNeeded(SOURCE, SESSION_ID, 61, messages);

        ArgumentCaptor<List<CompressibleMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(selfSpy).triggerCompress(eq(SOURCE), eq(SESSION_ID), captor.capture(), eq(0));

        // limit(4) -> order 0,1,2,3，limit 后 end=3（奇数），无需二次对齐
        assertThat(captor.getValue().get(captor.getValue().size() - 1).order()).isEqualTo(3);
    }

    // ===== triggerCompress 执行逻辑 =====

    @Test
    @DisplayName("triggerCompress 首次压缩应保存新摘要记录")
    void triggerCompress_firstTime_shouldSaveNewEntity() {
        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.empty());

        List<CompressibleMessage> messages = buildMessages(0, 9);
        service.triggerCompress(SOURCE, SESSION_ID, messages, 0);

        ArgumentCaptor<ConversationSummaryEntity> captor =
                ArgumentCaptor.forClass(ConversationSummaryEntity.class);
        verify(summaryRepository).save(captor.capture());

        ConversationSummaryEntity saved = captor.getValue();
        assertThat(saved.getSource()).isEqualTo(SOURCE);
        assertThat(saved.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(saved.getStartMsgOrder()).isEqualTo(0);
        assertThat(saved.getEndMsgOrder()).isEqualTo(9);
        assertThat(saved.getSummaryText()).isEqualTo("摘要");
    }

    @Test
    @DisplayName("triggerCompress 增量压缩应更新 existing 摘要")
    void triggerCompress_incremental_shouldUpdateExisting() {
        ConversationSummaryEntity existing = new ConversationSummaryEntity();
        existing.setSource(SOURCE);
        existing.setSessionId(SESSION_ID);
        existing.setSummaryText("旧摘要");
        existing.setStartMsgOrder(0);
        existing.setEndMsgOrder(9);
        existing.setVersion(1);
        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.of(existing));

        List<CompressibleMessage> messages = buildMessages(10, 39);
        service.triggerCompress(SOURCE, SESSION_ID, messages, 10);

        assertThat(existing.getEndMsgOrder()).isEqualTo(39);
        assertThat(existing.getSummaryText()).isEqualTo("摘要");
        verify(summaryRepository).save(existing);
    }

    @Test
    @DisplayName("triggerCompress 中 endOrder 为偶数时应对齐到前一个奇数")
    void triggerCompress_endOrderIsEven_shouldAlignDownToOdd() {
        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.empty());

        // 传入 0..10（11 条），末尾 order=10 为偶数，应被对齐为 9
        List<CompressibleMessage> messages = buildMessages(0, 10);
        service.triggerCompress(SOURCE, SESSION_ID, messages, 0);

        ArgumentCaptor<ConversationSummaryEntity> captor =
                ArgumentCaptor.forClass(ConversationSummaryEntity.class);
        verify(summaryRepository).save(captor.capture());

        assertThat(captor.getValue().getEndMsgOrder()).isEqualTo(9);
    }

    // ===== 异常处理：乐观锁重试 =====

    @Test
    @DisplayName("OptimisticLockingFailureException 第一次失败后重试成功")
    void triggerCompress_optimisticLockRetryOnce_shouldSucceed() {
        ConversationSummaryEntity existing = new ConversationSummaryEntity();
        existing.setSource(SOURCE);
        existing.setSessionId(SESSION_ID);
        existing.setSummaryText("旧摘要");
        existing.setStartMsgOrder(0);
        existing.setEndMsgOrder(9);
        existing.setVersion(1);

        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.of(existing))
                .thenReturn(Optional.of(existing));

        // 第一次 save 抛异常，第二次成功
        when(summaryRepository.save(existing))
                .thenThrow(new OptimisticLockingFailureException("conflict"))
                .thenReturn(existing);

        List<CompressibleMessage> messages = buildMessages(10, 39);
        service.triggerCompress(SOURCE, SESSION_ID, messages, 10);

        verify(summaryRepository, times(2)).findBySourceAndSessionId(SOURCE, SESSION_ID);
        verify(summaryRepository, times(2)).save(existing);
    }

    @Test
    @DisplayName("OptimisticLockingFailureException 连续两次失败应放弃")
    void triggerCompress_optimisticLockTwice_shouldGiveUp() {
        ConversationSummaryEntity existing = new ConversationSummaryEntity();
        existing.setSource(SOURCE);
        existing.setSessionId(SESSION_ID);
        existing.setSummaryText("旧摘要");
        existing.setStartMsgOrder(0);
        existing.setEndMsgOrder(9);
        existing.setVersion(1);

        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.of(existing));

        when(summaryRepository.save(existing))
                .thenThrow(new OptimisticLockingFailureException("conflict"));

        List<CompressibleMessage> messages = buildMessages(10, 39);
        service.triggerCompress(SOURCE, SESSION_ID, messages, 10);

        verify(summaryRepository, times(2)).findBySourceAndSessionId(SOURCE, SESSION_ID);
        verify(summaryRepository, times(2)).save(existing);
    }

    // ===== 工具方法 =====

    /**
     * 生成从 from 到 to（包含）的连续消息，偶数=用户，奇数=助手。
     */
    private List<CompressibleMessage> buildMessages(int from, int to) {
        List<CompressibleMessage> messages = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            String prefix = (i % 2 == 0) ? "用户：" : "助手：";
            messages.add(new CompressibleMessage(i, prefix + "消息" + i));
        }
        return messages;
    }
}
