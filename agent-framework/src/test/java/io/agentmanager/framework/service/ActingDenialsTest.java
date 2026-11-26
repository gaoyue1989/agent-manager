package io.agentmanager.framework.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActingDenialsTest {

    private static Agent mockAgent() {
        var agent = mock(Agent.class);
        when(agent.getName()).thenReturn("test-agent");
        return agent;
    }

    private static RuntimeContext newContext() {
        return RuntimeContext.builder()
            .agentState(AgentState.builder().build())
            .build();
    }

    @Test
    void bridgeShouldEmitDeniedEventsBeforeAgentEnd() {
        var agent = mockAgent();
        var ctx = newContext();
        var deniedCall = ToolUseBlock.builder().id("c-1").name("execute").input(Map.of()).build();
        var endMsg = Msg.builder().name("test-agent").role(MsgRole.ASSISTANT).textContent("paused").build();

        var events = ActingDenials.bridge(ctx, new AgentInput(List.of()), in -> Flux.defer(() -> {
            ActingDenials.deny(agent, ctx, new ActingInput(List.of(deniedCall)),
                ActingDenials.identitySet(List.of(deniedCall)), c -> "blocked", a -> Flux.empty())
                .collectList().block();
            return Flux.just(new AgentEndEvent("r1"));
        })).collectList().block(Duration.ofSeconds(5));

        assertNotNull(events);
        int deniedIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof ToolResultEndEvent end && end.getState() == ToolResultState.DENIED) {
                deniedIdx = i;
            }
            if (events.get(i) instanceof AgentEndEvent) {
                endIdx = i;
            }
        }
        assertTrue(deniedIdx >= 0, "DENIED ToolResultEndEvent must be emitted");
        assertTrue(endIdx > deniedIdx, "DENIED ToolResultEndEvent must precede AgentEndEvent");
    }

    @Test
    void bridgeShouldWorkAcrossReusedRuntimeContextTurns() {
        var agent = mockAgent();
        var ctx = newContext();
        for (int turn = 1; turn <= 2; turn++) {
            var call = ToolUseBlock.builder().id("c-" + turn).name("execute").input(Map.of()).build();
            var events = ActingDenials.bridge(ctx, new AgentInput(List.of()), in -> Flux.defer(() -> {
                ActingDenials.deny(agent, ctx, new ActingInput(List.of(call)),
                    ActingDenials.identitySet(List.of(call)), c -> "denied-" + c.getId(), a -> Flux.empty())
                    .collectList().block();
                return Flux.empty();
            })).collectList().block(Duration.ofSeconds(5));

            assertNotNull(events);
            long deniedCount = events.stream()
                .filter(e -> e instanceof ToolResultEndEvent end && end.getState() == ToolResultState.DENIED)
                .count();
            assertEquals(1, deniedCount, "Turn " + turn + " must receive DENIED event");
        }
    }

    @Test
    void bridgeShouldNotHangOrDropUnderConcurrentDenyAndComplete() throws Exception {
        var agent = mockAgent();
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 100; i++) {
                var ctx = newContext();
                var call = ToolUseBlock.builder().id("c-" + i).name("execute").input(Map.of()).build();
                Sinks.Many<AgentEvent> nextSink = Sinks.many().unicast().onBackpressureBuffer();
                var startLatch = new CountDownLatch(1);
                var denyDone = new CountDownLatch(1);

                var flux = ActingDenials.bridge(ctx, new AgentInput(List.of()), in -> nextSink.asFlux());
                var f1 = pool.submit(() -> {
                    try {
                        startLatch.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    ActingDenials.deny(agent, ctx, new ActingInput(List.of(call)),
                        ActingDenials.identitySet(List.of(call)), c -> "denied", a -> Flux.empty())
                        .collectList().block();
                    denyDone.countDown();
                });
                var f2 = pool.submit(() -> {
                    try {
                        startLatch.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    nextSink.tryEmitComplete();
                });

                var sub = flux.collectList().toFuture();
                startLatch.countDown();
                var events = sub.get(5, TimeUnit.SECONDS);
                f1.get(5, TimeUnit.SECONDS);
                f2.get(5, TimeUnit.SECONDS);

                int endIdx = -1;
                int lastDeniedIdx = -1;
                for (int idx = 0; idx < events.size(); idx++) {
                    if (events.get(idx) instanceof io.agentscope.core.event.AgentEndEvent) {
                        endIdx = idx;
                    }
                    if (events.get(idx) instanceof ToolResultEndEvent end && end.getState() == ToolResultState.DENIED) {
                        lastDeniedIdx = idx;
                    }
                }
                if (endIdx >= 0 && lastDeniedIdx >= 0) {
                    org.junit.jupiter.api.Assertions.assertTrue(lastDeniedIdx < endIdx,
                        "Iteration " + i + " emitted DENIED after AgentEndEvent");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void partialDenyWithDirectReturnShouldDelegateToNextAndSuppressShortCircuit() {
        var agent = mock(io.agentscope.core.ReActAgent.class);
        when(agent.getName()).thenReturn("test-agent");
        var toolkit = mock(io.agentscope.core.tool.Toolkit.class);
        when(agent.getToolkit()).thenReturn(toolkit);

        var client = mock(io.agentscope.core.tool.mcp.McpClientWrapper.class);
        when(client.callTool(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(reactor.core.publisher.Mono.just(
                new io.modelcontextprotocol.spec.McpSchema.CallToolResult(
                    List.of(new io.modelcontextprotocol.spec.McpSchema.TextContent("rendered card")), false)));
        var delegateTool = new io.agentscope.core.tool.mcp.McpTool(
            "render_chart", "render_chart", "desc", Map.of("type", "object"), null, client, null, "srv", true);
        var directTool = new io.agentmanager.framework.mcp.DirectReturnMcpTool(delegateTool);

        var ctx = newContext();
        var allowedCall = ToolUseBlock.builder().id("c-allow").name("render_chart").input(Map.of()).build();
        var deniedCall = ToolUseBlock.builder().id("c-deny").name("blocked_tool").input(Map.of()).build();

        var nextCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        var passedCalls = new java.util.concurrent.atomic.AtomicReference<List<ToolUseBlock>>();
        var ctxFlagDuringNext = new java.util.concurrent.atomic.AtomicReference<Object>();
        var toolResultDuringNext = new java.util.concurrent.atomic.AtomicReference<io.agentscope.core.message.ToolResultBlock>();

        ActingDenials.deny(
            agent, ctx, new ActingInput(List.of(allowedCall, deniedCall)),
            ActingDenials.identitySet(List.of(deniedCall)),
            c -> "denied",
            in -> {
                nextCalled.set(true);
                passedCalls.set(in.toolCalls());
                ctxFlagDuringNext.set(ctx.get(ActingDenials.SUPPRESS_DIRECT_RETURN_KEY));
                var param = io.agentscope.core.tool.ToolCallParam.builder()
                    .toolUseBlock(allowedCall)
                    .input(Map.of())
                    .runtimeContext(ctx)
                    .build();
                toolResultDuringNext.set(directTool.callAsync(param).block());
                return Flux.just(new ToolResultEndEvent("r1", "c-allow", "render_chart", ToolResultState.SUCCESS));
            }
        ).collectList().block(Duration.ofSeconds(5));

        assertTrue(nextCalled.get());
        assertEquals(List.of(allowedCall), passedCalls.get());
        assertEquals(Boolean.TRUE, ctxFlagDuringNext.get());
        assertNotNull(toolResultDuringNext.get());
        assertEquals(ToolResultState.ERROR, toolResultDuringNext.get().getState());
        org.junit.jupiter.api.Assertions.assertNull(ctx.get(ActingDenials.SUPPRESS_DIRECT_RETURN_KEY));
        org.mockito.Mockito.verify(toolkit, org.mockito.Mockito.never()).callTool(org.mockito.ArgumentMatchers.any());
    }
}
