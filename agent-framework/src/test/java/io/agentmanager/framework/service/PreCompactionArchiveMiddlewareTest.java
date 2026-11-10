package io.agentmanager.framework.service;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Flux;

/**
 * PreCompactionArchiveMiddleware 单测（oa-1110-repro-fixes-design §2.2）：
 * 压缩前差量归档、幂等去重、压缩后仅归档新增、fail-soft、空 id 跳过。
 */
class PreCompactionArchiveMiddlewareTest {

    private static Agent mockAgent(List<Msg> context) {
        var state = mock(AgentState.class);
        when(state.getContext()).thenReturn(context);
        var agent = mock(Agent.class);
        when(agent.getAgentState()).thenReturn(state);
        return agent;
    }

    private static RuntimeContext mockCtx() {
        var ctx = mock(RuntimeContext.class);
        when(ctx.getSessionId()).thenReturn("sid-1");
        when(ctx.getUserId()).thenReturn("u1");
        return ctx;
    }

    /** 记录型 ReasoningInput：仅作链路占位（本中间件不读内容） */
    private static ReasoningInput input() {
        return mock(ReasoningInput.class);
    }

    private static Function<ReasoningInput, Flux<AgentEvent>> next() {
        return in -> Flux.empty();
    }

    private static List<String> archivedIds(SessionMessageStore store, int invocationIndex) {
        var captor = ArgumentCaptor.forClass(List.class);
        verify(store, times(invocationIndex + 1)).archiveBatch(anyString(), captor.capture());
        var records = (List<SessionMessageStore.MessageRecord>) captor.getAllValues().get(invocationIndex);
        return records.stream().map(SessionMessageStore.MessageRecord::msgId).toList();
    }

    @Test
    void shouldArchiveContextIncludingToolMessagesBeforeCompaction() {
        var context = List.of(
            Msg.builder().id("m1").role(MsgRole.USER).textContent("帮我生成PPT").build(),
            Msg.builder().id("m2").role(MsgRole.ASSISTANT).textContent("调用工具").build(),
            Msg.builder().id("m3").role(MsgRole.TOOL).textContent("工具结果").build());
        var store = mock(SessionMessageStore.class);
        var middleware = new PreCompactionArchiveMiddleware(store);

        middleware.onReasoning(mockAgent(context), mockCtx(), input(), next()).blockLast();

        verify(store, times(1)).archiveBatch(eq(SessionMessageArchiveStateStore.slotKey("u1", "sid-1")), anyList());
        var ids = archivedIds(store, 0);
        org.junit.jupiter.api.Assertions.assertEquals(List.of("m1", "m2", "m3"), ids,
            "压缩发生前，含 tool 消息的完整上下文应已归档");
    }

    @Test
    void unchangedContextShouldNotRearchive() {
        var context = List.of(
            Msg.builder().id("m1").role(MsgRole.USER).textContent("q").build(),
            Msg.builder().id("m2").role(MsgRole.ASSISTANT).textContent("a").build());
        var store = mock(SessionMessageStore.class);
        var middleware = new PreCompactionArchiveMiddleware(store);
        var ctx = mockCtx();

        middleware.onReasoning(mockAgent(context), ctx, input(), next()).blockLast();
        middleware.onReasoning(mockAgent(context), ctx, input(), next()).blockLast();

        verify(store, times(1)).archiveBatch(anyString(), anyList());
    }

    @Test
    void afterCompactionShouldArchiveOnlyDelta() {
        var preCompaction = List.of(
            Msg.builder().id("m1").role(MsgRole.USER).textContent("q").build(),
            Msg.builder().id("m2").role(MsgRole.ASSISTANT).textContent("tool_use").build(),
            Msg.builder().id("m3").role(MsgRole.TOOL).textContent("tool_result").build());
        var store = mock(SessionMessageStore.class);
        var middleware = new PreCompactionArchiveMiddleware(store);
        var ctx = mockCtx();

        middleware.onReasoning(mockAgent(preCompaction), ctx, input(), next()).blockLast();

        // 模拟压缩：clear + [摘要 + 尾部]，摘要为新 id（真实场景 __compaction_summary__）
        var postCompaction = List.of(
            Msg.builder().id("c1").name("__compaction_summary__").role(MsgRole.SYSTEM)
                .textContent("summarized").build(),
            Msg.builder().id("m3").role(MsgRole.TOOL).textContent("tool_result").build());
        middleware.onReasoning(mockAgent(postCompaction), ctx, input(), next()).blockLast();

        var delta = archivedIds(store, 1);
        org.junit.jupiter.api.Assertions.assertEquals(List.of("c1"), delta,
            "压缩后只应差量归档新增的摘要消息，被驱逐消息不重写、保留消息不重写");
    }

    @Test
    void archiveFailureShouldNotBreakChain() {
        var context = List.of(Msg.builder().id("m1").role(MsgRole.USER).textContent("q").build());
        var store = mock(SessionMessageStore.class);
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
            .when(store).archiveBatch(anyString(), anyList());
        var middleware = new PreCompactionArchiveMiddleware(store);

        var events = new ArrayList<AgentEvent>();
        middleware.onReasoning(mockAgent(context), mockCtx(), input(), next())
            .doOnNext(events::add)
            .blockLast();

        verify(store, times(1)).archiveBatch(anyString(), anyList());
        org.junit.jupiter.api.Assertions.assertTrue(events.isEmpty(),
            "fail-soft：归档异常只告警，不向事件流传播");
    }

    @Test
    void blankIdAndEmptyContextShouldSkip() {
        var store = mock(SessionMessageStore.class);
        var middleware = new PreCompactionArchiveMiddleware(store);

        middleware.onReasoning(mockAgent(List.of()), mockCtx(), input(), next()).blockLast();
        // 显式空 id（SDK 默认自动生成 UUID，这里构造边界形态验证幂等键缺失即跳过）
        middleware.onReasoning(mockAgent(List.of(
            Msg.builder().id(" ").role(MsgRole.USER).textContent("no-id").build())),
            mockCtx(), input(), next()).blockLast();

        verify(store, never()).archiveBatch(anyString(), anyList());
    }

    @Test
    void nullSessionOrStoreShouldSkip() {
        var store = mock(SessionMessageStore.class);
        var middleware = new PreCompactionArchiveMiddleware(store);
        var ctxNoSid = mock(RuntimeContext.class);
        when(ctxNoSid.getSessionId()).thenReturn(" ");

        middleware.onReasoning(mockAgent(List.of(Msg.builder().id("m1").role(MsgRole.USER)
            .textContent("q").build())), ctxNoSid, input(), next()).blockLast();
        middleware.onReasoning(mockAgent(List.of(Msg.builder().id("m1").role(MsgRole.USER)
            .textContent("q").build())), null, input(), next()).blockLast();

        verify(store, never()).archiveBatch(anyString(), anyList());
    }
}
