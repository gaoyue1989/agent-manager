package io.agentmanager.framework.service;

import org.junit.jupiter.api.Test;

import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.model.ChatUsage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * turn 级模型用量累积器单测（chat-trace-observability-design §2.3）。
 */
class TurnUsageTrackerTest {

    private static ModelCallEndEvent end(int in, int out, int total) {
        return new ModelCallEndEvent("reply-1", new ChatUsage(in, out, total, 0.0));
    }

    @Test
    void accumulatesAcrossModelCalls() {
        // ReAct 多轮 = 多次 MODEL_CALL_END，逐次求和
        var tracker = new TurnUsageTracker();
        tracker.onModelCallEnd(end(100, 10, 110));
        tracker.onModelCallEnd(end(200, 20, 220));
        assertEquals(300, tracker.inputTokens());
        assertEquals(30, tracker.outputTokens());
        assertEquals(330, tracker.totalTokens());
        assertEquals(2, tracker.modelCalls());
    }

    @Test
    void nullUsageCountsCallButNotTokens() {
        // provider 不回 usage：按 0 计总量、model_calls 独立计数——区分「无调用」与
        // 「不回用量」，不伪造数据（设计 §2.1）
        var tracker = new TurnUsageTracker();
        tracker.onModelCallEnd(new ModelCallEndEvent("reply-1", null));
        tracker.onModelCallEnd(end(50, 5, 55));
        assertEquals(50, tracker.inputTokens());
        assertEquals(55, tracker.totalTokens());
        assertEquals(2, tracker.modelCalls());
    }

    @Test
    void emptyTrackerIsAllZero() {
        var tracker = new TurnUsageTracker();
        assertEquals(0, tracker.inputTokens());
        assertEquals(0, tracker.outputTokens());
        assertEquals(0, tracker.totalTokens());
        assertEquals(0, tracker.modelCalls());
    }
}
