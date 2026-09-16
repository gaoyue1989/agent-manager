package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.HookEventType;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import reactor.core.publisher.Mono;

class EmptyCompletionRecoveryHookTest {

    private EmptyCompletionRecoveryHook hook;
    private Agent agent;

    @BeforeEach
    void setUp() {
        hook = new EmptyCompletionRecoveryHook(3);
        agent = mock(Agent.class);
    }

    // ---- 辅助方法 ----

    private PostReasoningEvent postReasoning(Msg reasoningMsg) {
        return new PostReasoningEvent(agent, "test-model", null, reasoningMsg);
    }

    private Msg onlyThinking(String thought) {
        return AssistantMessage.builder()
                .name("test-agent")
                .content(ThinkingBlock.builder().thinking(thought).build())
                .build();
    }

    private Msg onlyText(String text) {
        return AssistantMessage.builder()
                .name("test-agent")
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private Msg thinkingAndText(String thought, String text) {
        return AssistantMessage.builder()
                .name("test-agent")
                .content(List.of(
                        ThinkingBlock.builder().thinking(thought).build(),
                        TextBlock.builder().text(text).build()))
                .build();
    }

    private Msg thinkingAndToolCall(String thought, String toolId, String toolName) {
        return AssistantMessage.builder()
                .name("test-agent")
                .content(List.of(
                        ThinkingBlock.builder().thinking(thought).build(),
                        ToolUseBlock.builder().id(toolId).name(toolName).input(Map.of()).content("{}").build()))
                .build();
    }

    private Msg onlyToolCall(String toolId, String toolName) {
        return AssistantMessage.builder()
                .name("test-agent")
                .content(ToolUseBlock.builder().id(toolId).name(toolName).input(Map.of()).content("{}").build())
                .build();
    }

    // ---- 测试用例 ----

    @Nested
    @DisplayName("非空完成场景")
    class NonEmptyCompletion {

        @Test
        @DisplayName("有文本输出时不触发恢复")
        void textOutput_noRecovery() {
            var event = postReasoning(onlyText("Hello world"));
            hook.onEvent(event).block();

            assertFalse(event.isGotoReasoningRequested());
            assertEquals(0, hook.getRecoveryCount());
        }

        @Test
        @DisplayName("有思维+文本时不触发恢复")
        void thinkingAndText_noRecovery() {
            var event = postReasoning(thinkingAndText("thinking...", "answer"));
            hook.onEvent(event).block();

            assertFalse(event.isGotoReasoningRequested());
            assertEquals(0, hook.getRecoveryCount());
        }

        @Test
        @DisplayName("有工具调用时不触发恢复")
        void toolCall_noRecovery() {
            var event = postReasoning(onlyToolCall("tc1", "read_file"));
            hook.onEvent(event).block();

            assertFalse(event.isGotoReasoningRequested());
            assertEquals(0, hook.getRecoveryCount());
        }

        @Test
        @DisplayName("有思维+工具调用时不触发恢复")
        void thinkingAndToolCall_noRecovery() {
            var event = postReasoning(thinkingAndToolCall("planning...", "tc1", "read_file"));
            hook.onEvent(event).block();

            assertFalse(event.isGotoReasoningRequested());
            assertEquals(0, hook.getRecoveryCount());
        }
    }

    @Nested
    @DisplayName("空完成场景")
    class EmptyCompletion {

        @Test
        @DisplayName("只有 ThinkingBlock 时触发 gotoReasoning")
        void onlyThinking_triggersRecovery() {
            var event = postReasoning(onlyThinking("I need to analyze this..."));
            hook.onEvent(event).block();

            assertTrue(event.isGotoReasoningRequested());
            assertEquals(1, hook.getRecoveryCount());
            // 检查注入的 continuation 消息
            assertNotNull(event.getGotoReasoningMsgs());
            assertEquals(1, event.getGotoReasoningMsgs().size());
        }

        @Test
        @DisplayName("null reasoning message 也触发恢复")
        void nullMessage_triggersRecovery() {
            var event = postReasoning(null);
            hook.onEvent(event).block();

            assertTrue(event.isGotoReasoningRequested());
            assertEquals(1, hook.getRecoveryCount());
        }

        @Test
        @DisplayName("空内容消息触发恢复")
        void emptyContent_triggersRecovery() {
            Msg emptyMsg = AssistantMessage.builder()
                    .name("test-agent")
                    .content(List.of())
                    .build();
            var event = postReasoning(emptyMsg);
            hook.onEvent(event).block();

            assertTrue(event.isGotoReasoningRequested());
            assertEquals(1, hook.getRecoveryCount());
        }

        @Test
        @DisplayName("连续多次空完成递增恢复计数")
        void multipleEmptyCompletions_incrementCount() {
            var event1 = postReasoning(onlyThinking("think 1"));
            hook.onEvent(event1).block();
            assertEquals(1, hook.getRecoveryCount());

            var event2 = postReasoning(onlyThinking("think 2"));
            hook.onEvent(event2).block();
            assertEquals(2, hook.getRecoveryCount());
        }

        @Test
        @DisplayName("达到最大恢复次数后放弃，追加提示文本")
        void maxRecoveriesReached_appendsFallbackText() {
            // 前三次触发恢复
            for (int i = 0; i < 3; i++) {
                var event = postReasoning(onlyThinking("thinking " + i));
                hook.onEvent(event).block();
            }
            assertEquals(3, hook.getRecoveryCount());

            // 第四次应放弃
            Msg msg = onlyThinking("final thinking");
            var event = postReasoning(msg);
            hook.onEvent(event).block();

            assertFalse(event.isGotoReasoningRequested());
            // 应该在 reasoningMessage 中追加了 fallback 文本
            Msg patched = event.getReasoningMessage();
            assertNotNull(patched);
            List<TextBlock> textBlocks = patched.getContentBlocks(TextBlock.class);
            assertFalse(textBlocks.isEmpty(), "应该追加了 fallback 文本");
            assertTrue(textBlocks.get(0).getText().contains("截断"),
                    "fallback 文本应包含'截断'，实际：" + textBlocks.get(0).getText());
        }

        @Test
        @DisplayName("恢复成功后重置计数")
        void successfulOutput_resetsCount() {
            // 先触发一次空完成
            var event1 = postReasoning(onlyThinking("think 1"));
            hook.onEvent(event1).block();
            assertEquals(1, hook.getRecoveryCount());

            // 然后有正常输出
            var event2 = postReasoning(onlyText("answer"));
            hook.onEvent(event2).block();
            assertEquals(0, hook.getRecoveryCount());
        }
    }

    @Nested
    @DisplayName("非 POST_REASONING 事件")
    class OtherEvents {

        @Test
        @DisplayName("非 POST_REASONING 事件直接透传")
        void otherEvent_passThrough() {
            var event = new PreReasoningEvent(agent, "test-model", null, List.of());
            var result = hook.onEvent(event).block();

            assertSame(event, result);
            assertEquals(0, hook.getRecoveryCount());
        }
    }

    @Nested
    @DisplayName("resetRecoveryCount")
    class Reset {

        @Test
        @DisplayName("手动重置恢复计数")
        void manualReset() {
            var event = postReasoning(onlyThinking("think"));
            hook.onEvent(event).block();
            assertEquals(1, hook.getRecoveryCount());

            hook.resetRecoveryCount();
            assertEquals(0, hook.getRecoveryCount());
        }
    }
}
