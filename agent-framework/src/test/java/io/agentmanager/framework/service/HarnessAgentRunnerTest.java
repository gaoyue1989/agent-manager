package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;

import java.util.List;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;

class HarnessAgentRunnerTest {

    private final HarnessAgent agent = mock(HarnessAgent.class);
    private final HarnessAgentRunner runner = new HarnessAgentRunner(agent);

    @Test
    void getAgentNameShouldDelegate() {
        when(agent.getName()).thenReturn("test-agent");
        assertEquals("test-agent", runner.getAgentName());
    }

    @Test
    void getAgentDescriptionShouldDelegate() {
        when(agent.getDescription()).thenReturn("desc");
        assertEquals("desc", runner.getAgentDescription());
    }

    @Test
    void streamShouldUseSessionIdAndUserIdFromOptions() {
        when(agent.stream(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());

        var options = new AgentRequestOptions();
        options.setTaskId("task-1");
        options.setSessionId("session-1");
        options.setUserId("u1");
        var msg = mock(Msg.class);

        var flux = runner.stream(List.of(msg), options);
        assertNotNull(flux);
        flux.subscribe();

        var captor = org.mockito.ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).stream(eq(List.of(msg)), captor.capture());
        assertEquals("session-1", captor.getValue().getSessionId());
        assertEquals("u1", captor.getValue().getUserId());
    }

    @Test
    void streamShouldFallbackToAnonymousUser() {
        when(agent.stream(anyList(), any(RuntimeContext.class))).thenReturn(Flux.empty());

        var options = new AgentRequestOptions();
        options.setTaskId("task-2");
        options.setSessionId("session-2");
        var msg = mock(Msg.class);

        runner.stream(List.of(msg), options).blockLast();

        var captor = org.mockito.ArgumentCaptor.forClass(RuntimeContext.class);
        verify(agent).stream(eq(List.of(msg)), captor.capture());
        assertEquals("session-2", captor.getValue().getSessionId());
        assertEquals("anonymous", captor.getValue().getUserId());
    }

    @Test
    void streamShouldCleanupTaskMapOnTerminal() {
        when(agent.stream(anyList(), any(RuntimeContext.class)))
            .thenReturn(Flux.just(mock(io.agentscope.core.agent.Event.class)));

        var options = new AgentRequestOptions();
        options.setTaskId("task-3");
        options.setSessionId("session-3");

        assertNotNull(runner.stream(List.of(mock(Msg.class)), options).blockFirst());

        // doFinally 已触发：再次 stop 不应 interrupt（task map 已清空）
        runner.stop("task-3");
        verify(agent, never()).interrupt();
    }

    @Test
    void stopShouldInterruptWhenSessionKnown() {
        when(agent.stream(anyList(), any(RuntimeContext.class))).thenReturn(Flux.never());

        var options = new AgentRequestOptions();
        options.setTaskId("task-4");
        options.setSessionId("session-4");

        runner.stream(List.of(mock(Msg.class)), options).subscribe();
        runner.stop("task-4");
        verify(agent).interrupt();
    }

    @Test
    void stopShouldIgnoreUnknownTask() {
        runner.stop("unknown-task");
        verify(agent, never()).interrupt();
    }
}