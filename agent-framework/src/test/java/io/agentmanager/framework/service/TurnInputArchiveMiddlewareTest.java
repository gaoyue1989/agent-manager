package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import reactor.core.publisher.Flux;

/**
 * 回合起始用户输入归档中间件单测（2026-10-12）：修复「发送消息后立即刷新，从历史列表进入
 * 会话详情为空」——SDK 整轮结束才落库，turn 执行中 history 无任何消息。
 */
class TurnInputArchiveMiddlewareTest {

    private static final String PEER = "webui-sid-1";
    private static final String GW_HASH = "gw-abc123";

    private static RuntimeContext ctx(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    /** 放行 next 并回一帧（避免空 Flux 语义干扰），捕获入参 */
    private static Flux<AgentEvent> passThrough(AgentInput in) {
        return Flux.empty();
    }

    @Test
    void shouldArchiveUserInputUnderSdkSlotKeyAndDelegate() {
        var store = mock(SessionMessageStore.class);
        var mw = new TurnInputArchiveMiddleware(store);
        var userMsg = Msg.builder().role(MsgRole.USER).name("repro-user").textContent("你好").build();
        var input = new AgentInput(List.of(userMsg));

        Function<AgentInput, Flux<AgentEvent>> next = TurnInputArchiveMiddlewareTest::passThrough;
        mw.onAgent(mock(Agent.class), ctx(PEER, GW_HASH), input, next).blockLast();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SessionMessageStore.MessageRecord>> captor =
            ArgumentCaptor.forClass(List.class);
        verify(store).archiveBatch(eq(PEER + ":" + GW_HASH), captor.capture());
        assertEquals(1, captor.getValue().size());
        var record = captor.getValue().get(0);
        assertEquals(userMsg.getId(), record.msgId(), "归档 id 必须与 SDK 落库 id 同源，否则重复");
        assertEquals("USER", record.role());
        assertTrue(record.msgJson().contains("你好"));
    }

    @Test
    void shouldNotArchiveConfirmResultsControlMessage() {
        var store = mock(SessionMessageStore.class);
        var mw = new TurnInputArchiveMiddleware(store);
        var confirm = Msg.builder().role(MsgRole.USER)
            .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, List.of()))
            .textContent("").build();
        var input = new AgentInput(List.of(confirm));

        mw.onAgent(mock(Agent.class), ctx(PEER, GW_HASH), input,
            TurnInputArchiveMiddlewareTest::passThrough).blockLast();

        verify(store, never()).archiveBatch(anyString(), any());
    }

    @Test
    void shouldIgnoreNonUserMessages() {
        var store = mock(SessionMessageStore.class);
        var mw = new TurnInputArchiveMiddleware(store);
        var assistant = Msg.builder().role(MsgRole.ASSISTANT).textContent("hi").build();
        var input = new AgentInput(List.of(assistant));

        mw.onAgent(mock(Agent.class), ctx(PEER, GW_HASH), input,
            TurnInputArchiveMiddlewareTest::passThrough).blockLast();

        verify(store, never()).archiveBatch(anyString(), any());
    }

    @Test
    void nullStoreShouldBeNoOpAndStillDelegate() {
        var mw = new TurnInputArchiveMiddleware(null);
        var userMsg = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        var called = new boolean[1];
        // 无 store：不得抛异常，且必须继续调用 next
        mw.onAgent(mock(Agent.class), ctx(PEER, GW_HASH), new AgentInput(List.of(userMsg)),
            in -> { called[0] = true; return Flux.empty(); }).blockLast();
        assertTrue(called[0], "无 store 也必须放行 next");
    }

    @Test
    void blankSessionIdShouldSkip() {
        var store = mock(SessionMessageStore.class);
        var mw = new TurnInputArchiveMiddleware(store);
        var userMsg = Msg.builder().role(MsgRole.USER).textContent("hi").build();

        mw.onAgent(mock(Agent.class), ctx(PEER, ""), new AgentInput(List.of(userMsg)),
            TurnInputArchiveMiddlewareTest::passThrough).blockLast();

        verify(store, never()).archiveBatch(anyString(), any());
    }

    @Test
    void storeFailureShouldNotBreakDelegation() {
        var store = mock(SessionMessageStore.class);
        // archiveBatch 抛异常也必须被吞掉，链路继续放行 next（fail-soft 契约）
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
            .when(store).archiveBatch(anyString(), any());
        var mw = new TurnInputArchiveMiddleware(store);
        var userMsg = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        var called = new boolean[1];

        mw.onAgent(mock(Agent.class), ctx(PEER, GW_HASH), new AgentInput(List.of(userMsg)),
            in -> { called[0] = true; return Flux.empty(); }).blockLast();

        assertTrue(called[0], "归档失败也必须继续放行 next");
    }
}
