package io.agentmanager.framework.service.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Flux;
/**
 * 动态 DENY 中间件单测（F16 自实现第二环）：命中工具合成 DENIED 结果且不进执行链、
 * 未命中直通、无规则直通、全部命中只回合成事件。上下文注入断言走 AgentState.contextMutable。
 */
class ProtocolDenyRulesMiddlewareTest {

    private final Agent agent = mock(Agent.class);
    private final AgentState agentState = mock(AgentState.class);
    private final ProtocolDenyRulesMiddleware middleware = new ProtocolDenyRulesMiddleware();

    private static RuntimeContext ctxWithDeny(Set<String> names) {
        return RuntimeContext.builder()
            .sessionId("task-1").userId("user-1")
            .put(ProtocolDenyRules.RUNTIME_ATTRIBUTE_KEY, names)
            .build();
    }

    private static ToolUseBlock call(String id, String name) {
        return ToolUseBlock.builder().id(id).name(name)
            .input(java.util.Map.of("path", "x")).build();
    }

    @Test
    void deniedCallShouldSynthesizeDeniedResultAndSkipExecution() {
        when(agent.getAgentState()).thenReturn(agentState);
        when(agent.getName()).thenReturn("member-agent");
        var mutable = new java.util.ArrayList<Msg>();
        when(agentState.contextMutable()).thenReturn(mutable);

        var out = middleware.onActing(agent, ctxWithDeny(Set.of("write_file")),
                new ActingInput(List.of(call("tc-1", "write_file"), call("tc-2", "read_file"))),
                in -> {
                    assertEquals(1, in.toolCalls().size());
                    assertEquals("read_file", in.toolCalls().get(0).getName());
                    return Flux.just(new ToolResultEndEvent("r", "tc-2", "read_file",
                        ToolResultState.SUCCESS));
                })
            .collectList().block();

        // 合成 DENIED 帧（concat 前段）+ 执行链只含 read_file 的正常结果（concat 后段）
        var deniedEnd = out.stream()
            .filter(e -> e instanceof ToolResultEndEvent
                && ((ToolResultEndEvent) e).getState() == ToolResultState.DENIED)
            .map(ToolResultEndEvent.class::cast)
            .findFirst().orElseThrow();
        assertEquals("tc-1", deniedEnd.getToolCallId());
        assertEquals(1, out.stream().filter(e -> e instanceof ToolResultEndEvent
            && ((ToolResultEndEvent) e).getState() == ToolResultState.SUCCESS).count());
        // 上下文注入了 DENIED ToolResult Msg（模型可感知）
        verify(agentState).contextMutable();
        assertEquals(1, mutable.size());
    }

    @Test
    void noDenyRulesShouldPassThrough() {
        var ctx = RuntimeContext.builder().sessionId("task-1").build();
        var input = new ActingInput(List.of(call("tc-1", "write_file")));

        var out = middleware.onActing(agent, ctx, input,
                in -> Flux.just(new ToolResultEndEvent("r", "tc-1", "write_file",
                    ToolResultState.SUCCESS)))
            .collectList().block();

        assertEquals(1, out.size());
        verify(agent, never()).getAgentState();
    }

    @Test
    void allCallsDeniedShouldReturnSyntheticOnly() {
        when(agent.getAgentState()).thenReturn(agentState);
        when(agent.getName()).thenReturn("member-agent");
        when(agentState.contextMutable()).thenReturn(new java.util.ArrayList<Msg>());

        var out = middleware.onActing(agent, ctxWithDeny(Set.of("write_file")),
                new ActingInput(List.of(call("tc-1", "write_file"))),
                in -> {
                    throw new AssertionError("denied-only turn must not reach execution");
                })
            .collectList().block();

        assertTrue(out.size() >= 5);
        assertEquals(ToolResultState.DENIED,
            ((ToolResultEndEvent) out.get(out.size() - 1)).getState());
    }

    @Test
    void unrelatedCallsShouldPassThroughUntouched() {
        var ctx = ctxWithDeny(Set.of("execute"));
        var input = new ActingInput(List.of(call("tc-1", "read_file")));

        var out = middleware.onActing(agent, ctx, input,
                in -> Flux.just(new ToolResultEndEvent("r", "tc-1", "read_file",
                    ToolResultState.SUCCESS)))
            .collectList().block();

        assertEquals(1, out.size());
        verify(agent, never()).getAgentState();
    }
}
