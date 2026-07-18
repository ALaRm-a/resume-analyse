package interview.guide.modules.agent.memory.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConversationMemory} DTO 测试。
 */
class ConversationMemoryTest {

    @Test
    @DisplayName("summary 和 recentHistory 都为空时 isEmpty 为 true")
    void isEmpty_whenBothBlank_shouldBeTrue() {
        assertThat(new ConversationMemory(null, null).isEmpty()).isTrue();
        assertThat(new ConversationMemory("", "").isEmpty()).isTrue();
        assertThat(new ConversationMemory("   ", null).isEmpty()).isTrue();
        assertThat(new ConversationMemory(null, "   ").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("summary 或 recentHistory 非空时 isEmpty 为 false")
    void isEmpty_whenEitherPresent_shouldBeFalse() {
        assertThat(new ConversationMemory("summary", null).isEmpty()).isFalse();
        assertThat(new ConversationMemory(null, "history").isEmpty()).isFalse();
    }

    @Test
    @DisplayName("toPromptText 应正确拼接摘要和最近历史")
    void toPromptText_shouldCombineSummaryAndHistory() {
        ConversationMemory memory = new ConversationMemory(
                "之前讨论了 CAS",
                "用户：解释 CAS\n助手：CAS 是无锁原子操作");

        String text = memory.toPromptText();

        assertThat(text).contains("## 之前的对话摘要");
        assertThat(text).contains("之前讨论了 CAS");
        assertThat(text).contains("## 最近的对话历史");
        assertThat(text).contains("用户：解释 CAS");
    }

    @Test
    @DisplayName("toPromptText 在只有摘要时不包含最近历史标题")
    void toPromptText_withOnlySummary_shouldNotContainHistoryHeader() {
        ConversationMemory memory = new ConversationMemory("摘要内容", null);

        String text = memory.toPromptText();

        assertThat(text).contains("摘要内容");
        assertThat(text).doesNotContain("## 最近的对话历史");
    }

    @Test
    @DisplayName("toPromptText 在只有历史时不包含摘要标题")
    void toPromptText_withOnlyHistory_shouldNotContainSummaryHeader() {
        ConversationMemory memory = new ConversationMemory(null, "历史内容");

        String text = memory.toPromptText();

        assertThat(text).contains("历史内容");
        assertThat(text).doesNotContain("## 之前的对话摘要");
    }

    @Test
    @DisplayName("toPromptText 在空记忆时返回空字符串")
    void toPromptText_whenEmpty_shouldReturnEmptyString() {
        ConversationMemory memory = new ConversationMemory(null, null);

        assertThat(memory.toPromptText()).isEmpty();
    }
}
