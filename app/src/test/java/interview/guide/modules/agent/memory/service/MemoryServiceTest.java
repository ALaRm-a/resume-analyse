package interview.guide.modules.agent.memory.service;

import interview.guide.modules.agent.memory.dto.ConversationMemory;
import interview.guide.modules.agent.memory.model.ConversationSummaryEntity;
import interview.guide.modules.agent.memory.repository.ConversationSummaryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MemoryService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {

    @Mock
    private ConversationSummaryRepository summaryRepository;

    @InjectMocks
    private MemoryService memoryService;

    private static final String SOURCE = "rag_chat";
    private static final Long SESSION_ID = 42L;

    @Test
    @DisplayName("getMemory 应返回摘要和最近历史拼接结果")
    void getMemory_shouldReturnSummaryAndHistory() {
        ConversationSummaryEntity summary = new ConversationSummaryEntity();
        summary.setSource(SOURCE);
        summary.setSessionId(SESSION_ID);
        summary.setSummaryText("历史摘要");
        summary.setStartMsgOrder(0);
        summary.setEndMsgOrder(9);

        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.of(summary));

        ConversationMemory memory = memoryService.getMemory(SOURCE, SESSION_ID,
                Arrays.asList("用户：问题", "助手：回答"));

        assertThat(memory.isEmpty()).isFalse();
        assertThat(memory.summary()).isEqualTo("历史摘要");
        assertThat(memory.recentHistory()).isEqualTo("用户：问题\n助手：回答");
    }

    @Test
    @DisplayName("getMemory 在无摘要时只返回最近历史")
    void getMemory_withoutSummary_shouldReturnOnlyHistory() {
        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.empty());

        ConversationMemory memory = memoryService.getMemory(SOURCE, SESSION_ID,
                Arrays.asList("用户：问题"));

        assertThat(memory.summary()).isNull();
        assertThat(memory.recentHistory()).isEqualTo("用户：问题");
        assertThat(memory.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("getMemory 在空消息时只返回摘要")
    void getMemory_withEmptyMessages_shouldReturnOnlySummary() {
        ConversationSummaryEntity summary = new ConversationSummaryEntity();
        summary.setSummaryText("历史摘要");
        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.of(summary));

        ConversationMemory memory = memoryService.getMemory(SOURCE, SESSION_ID, null);

        assertThat(memory.summary()).isEqualTo("历史摘要");
        assertThat(memory.recentHistory()).isNull();
    }

    @Test
    @DisplayName("getMemory 在空消息且无摘要时为空")
    void getMemory_withEmptyMessagesAndNoSummary_shouldBeEmpty() {
        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.empty());

        ConversationMemory memory = memoryService.getMemory(SOURCE, SESSION_ID, null);

        assertThat(memory.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("getSummary 应返回摘要文本")
    void getSummary_shouldReturnText() {
        ConversationSummaryEntity summary = new ConversationSummaryEntity();
        summary.setSummaryText("历史摘要");
        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.of(summary));

        assertThat(memoryService.getSummary(SOURCE, SESSION_ID)).isEqualTo("历史摘要");
    }

    @Test
    @DisplayName("getSummary 在无摘要时返回 null")
    void getSummary_withoutSummary_shouldReturnNull() {
        when(summaryRepository.findBySourceAndSessionId(SOURCE, SESSION_ID))
                .thenReturn(Optional.empty());

        assertThat(memoryService.getSummary(SOURCE, SESSION_ID)).isNull();
    }

    @Test
    @DisplayName("deleteBySourceAndSessionId 应调用 repository 删除")
    void deleteBySourceAndSessionId_shouldCallRepository() {
        memoryService.deleteBySourceAndSessionId(SOURCE, SESSION_ID);

        verify(summaryRepository).deleteBySourceAndSessionId(SOURCE, SESSION_ID);
    }
}
