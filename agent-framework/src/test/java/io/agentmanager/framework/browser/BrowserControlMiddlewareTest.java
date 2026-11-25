package io.agentmanager.framework.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.service.SessionKeyResolver;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Flux;

class BrowserControlMiddlewareTest {

    private static final String SESSION = "s1";

    private record Run(AtomicReference<ActingInput> passed,
                       AtomicReference<List<io.agentscope.core.message.Msg>> injected,
                       List<AgentEvent> events) {
    }

    private Run run(boolean human, List<ToolUseBlock> calls) {
        var control = mock(BrowserControlService.class);
        when(control.isHumanControlled(SESSION)).thenReturn(human);

        var ctx = RuntimeContext.builder().sessionId(SESSION).userId("u1").build();
        var defaultState = mock(AgentState.class);
        when(defaultState.contextMutable()).thenReturn(new java.util.ArrayList<>());
        var agent = mock(Agent.class);
        when(agent.getName()).thenReturn("test-agent");
        when(agent.getAgentState()).thenReturn(defaultState);
        var perCall = new java.util.ArrayList<io.agentscope.core.message.Msg>();
        var perCallState = mock(AgentState.class);
        when(perCallState.contextMutable()).thenReturn(perCall);
        ctx.setAgentState(perCallState);

        var passed = new AtomicReference<ActingInput>();
        Function<ActingInput, Flux<AgentEvent>> next = in -> {
            passed.set(in);
            return Flux.empty();
        };
        var events = new BrowserControlMiddleware(control, new SessionKeyResolver(null))
            .onActing(agent, ctx, new ActingInput(calls), next)
            .collectList()
            .block();
        return new Run(passed, new AtomicReference<>(perCall), events);
    }

    @Test
    void humanTakeoverShouldDenySandboxCommandExecution() {
        var run = run(true, List.of(
            new ToolUseBlock("t1", "execute", Map.of("command", "chrome-devtools list_pages")),
            new ToolUseBlock("t2", "read_file", Map.of())));

        assertNotNull(run.passed(), "非命令调用放行到 next");
        assertEquals(List.of("read_file"),
            run.passed().get().toolCalls().stream().map(ToolUseBlock::getName).toList());
        assertEquals(5, run.events().size(), "DENY 合成事件 5 帧");
        var last = (ToolResultEndEvent) run.events().get(run.events().size() - 1);
        assertEquals(ToolResultState.DENIED, last.getState());
        assertEquals(1, run.injected().get().size(), "DENIED 结果注入 per-call state");
    }

    @Test
    void humanTakeoverShouldDenyAllWhenOnlyCommands() {
        var run = run(true, List.of(
            new ToolUseBlock("t1", "execute", Map.of("command", "echo hi"))));

        assertNull(run.passed().get(), "全部被拒时不调用 next");
        assertEquals(5, run.events().size());
    }

    @Test
    void agentControlShouldPassThroughOriginalInput() {
        var calls = List.of(new ToolUseBlock("t1", "execute", Map.of("command", "echo hi")));
        var run = run(false, calls);

        assertNotNull(run.passed());
        assertSame(calls.get(0), run.passed().get().toolCalls().get(0));
        assertEquals(0, run.events().size());
        assertEquals(0, run.injected().get().size());
    }

    @Test
    void humanTakeoverShouldNotTouchNonCommandTurns() {
        var run = run(true, List.of(new ToolUseBlock("t1", "read_file", Map.of())));
        assertNotNull(run.passed(), "无命令调用时直通");
        assertEquals(0, run.events().size());
    }

    @Test
    void onAgentBridgeShouldDeliverDeniedResultEventsEvenWhenOnActingReturnIsDropped() {
        var control = mock(BrowserControlService.class);
        when(control.isHumanControlled("s1")).thenReturn(true);
        var resolver = mock(SessionKeyResolver.class);
        when(resolver.canonicalSessionId(org.mockito.ArgumentMatchers.any())).thenReturn("s1");
        var mw = new BrowserControlMiddleware(control, resolver);

        var ctx = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        var state = mock(AgentState.class);
        when(state.contextMutable()).thenReturn(new java.util.ArrayList<>());
        ctx.setAgentState(state);
        var agent = mock(Agent.class);
        when(agent.getName()).thenReturn("a1");

        var events = mw.onAgent(agent, ctx, mock(io.agentscope.core.middleware.AgentInput.class), input -> {
            var calls = List.of(new ToolUseBlock("t1", "execute", Map.of("command", "echo hi")));
            mw.onActing(agent, ctx, new ActingInput(calls), next -> Flux.empty()).then().block();
            return Flux.empty();
        }).collectList().block();

        assertNotNull(events);
        org.junit.jupiter.api.Assertions.assertTrue(events.stream().anyMatch(e -> e instanceof ToolResultEndEvent end
            && end.getState() == ToolResultState.DENIED && "t1".equals(end.getToolCallId())));
    }

    @Test
    void parallelBatchWithRequestBrowserTakeoverShouldDenyExecuteEvenBeforeLockFlips() {
        var run = run(false, List.of(
            new ToolUseBlock("t1", "request_browser_takeover", Map.of("reason", "login")),
            new ToolUseBlock("t2", "execute", Map.of("command", "echo sneaky"))));

        assertNotNull(run.passed().get());
        assertEquals(List.of("request_browser_takeover"),
            run.passed().get().toolCalls().stream().map(ToolUseBlock::getName).toList());
        assertEquals(5, run.events().size());
        var last = (ToolResultEndEvent) run.events().get(run.events().size() - 1);
        assertEquals(ToolResultState.DENIED, last.getState());
        assertEquals("t2", last.getToolCallId());
    }
}
