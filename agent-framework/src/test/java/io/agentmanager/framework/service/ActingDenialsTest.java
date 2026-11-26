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
    void mixedDirectReturnAllowAndDenyShouldEmitImageDataDelta() {
        var agent = mock(io.agentscope.core.ReActAgent.class);
        when(agent.getName()).thenReturn("test-agent");
        var toolkit = mock(io.agentscope.core.tool.Toolkit.class);
        var tool = mock(io.agentscope.core.tool.AgentTool.class);
        when(tool.isReturnDirect()).thenReturn(true);
        when(toolkit.getTool("render_chart")).thenReturn(tool);
        var img = io.agentscope.core.message.ImageBlock.builder()
            .source(io.agentscope.core.message.URLSource.builder().url("https://example.com/chart.png").build())
            .build();
        var resultBlock = io.agentscope.core.message.ToolResultBlock.of(
            List.of(io.agentscope.core.message.TextBlock.builder().text("chart caption").build(), img));
        when(toolkit.callTool(org.mockito.ArgumentMatchers.any()))
            .thenReturn(reactor.core.publisher.Mono.just(resultBlock));
        when(agent.getToolkit()).thenReturn(toolkit);

        var ctx = newContext();
        var allowedCall = ToolUseBlock.builder().id("c-allow").name("render_chart").input(Map.of()).build();
        var deniedCall = ToolUseBlock.builder().id("c-deny").name("blocked_tool").input(Map.of()).build();

        var events = ActingDenials.deny(
            agent, ctx, new ActingInput(List.of(allowedCall, deniedCall)),
            ActingDenials.identitySet(List.of(deniedCall)),
            c -> "denied",
            in -> Flux.empty()
        ).collectList().block();

        org.junit.jupiter.api.Assertions.assertNotNull(events);
        int textIdx = -1;
        int dataIdx = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof io.agentscope.core.event.ToolResultTextDeltaEvent td
                    && "c-allow".equals(td.getToolCallId())) {
                textIdx = i;
            }
            if (events.get(i) instanceof io.agentscope.core.event.ToolResultDataDeltaEvent dd
                    && "c-allow".equals(dd.getToolCallId())) {
                dataIdx = i;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(textIdx >= 0, "must emit ToolResultTextDeltaEvent");
        org.junit.jupiter.api.Assertions.assertTrue(dataIdx > textIdx, "must emit ToolResultDataDeltaEvent after text");
    }
}
