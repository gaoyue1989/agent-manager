package io.agentmanager.framework.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.Agent;import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * agent_spawn 结果抓取中间件单测（RemoteConfirmBridge 登记入口）：
 * 规范 sid 解析（Channel 链路 ctx 是 gw-hash/peer，不翻译会落错桶）、
 * 结果文本按 toolCallId 累积、非 spawn 工具零开销直通、抓取异常 fail-soft 不打断父流。
 */
class RemoteSpawnCaptureMiddlewareTest {

    private RemoteConfirmBridge bridge;
    private SessionUserStore sessionUserStore;
    private RemoteSpawnCaptureMiddleware middleware;
    private Agent agent = mock(Agent.class);

    @BeforeEach
    void setUp() {
        bridge = mock(RemoteConfirmBridge.class);
        sessionUserStore = mock(SessionUserStore.class);
        middleware = new RemoteSpawnCaptureMiddleware(bridge, sessionUserStore);
    }

    /** Channel 链路形态：sessionId=gw-hash（共享）、userId=peer=前端 sid */
    private static RuntimeContext channelCtx() {
        return RuntimeContext.builder()
            .sessionId("gw-3f20f08c5499")
            .userId("webui-1")
            .build();
    }

    private static ToolUseBlock spawnCall(String id, Map<String, Object> input) {
        return ToolUseBlock.builder().id(id).name("agent_spawn").input(input).build();
    }

    @Test
    void spawnResultShouldBeCapturedWithCanonicalSid() {
        // 规范键解析：peer=webui-1 在 session_user 登记 → canonicalSessionId = webui-1（非 gw-hash）
        when(sessionUserStore.findUserIdBySession("webui-1")).thenReturn("user-42");
        var events = List.<AgentEvent>of(
            new ToolResultTextDeltaEvent("reply-1", "tc-1", "agent_spawn", "status: submitted\n"),
            new ToolResultTextDeltaEvent("reply-1", "tc-1", "agent_spawn", "task_id: t-9\n"),
            new ToolResultEndEvent("reply-1", "tc-1", "agent_spawn", ToolResultState.SUCCESS));

        var out = middleware.onActing(agent, channelCtx(),
                new ActingInput(List.of(spawnCall("tc-1", Map.of("agent_key", "booking")))),
                in -> Flux.fromIterable(events))
            .collectList().block();

        assertEquals(3, out.size(), "事件流原样透传");
        verify(bridge).onAgentSpawnResult(eq("webui-1"),
            eq(Map.of("agent_key", "booking")), eq("status: submitted\ntask_id: t-9\n"));
    }

    @Test
    void nonSpawnActingShouldPassThroughWithoutCapture() {
        var toolCall = ToolUseBlock.builder().id("tc-2").name("read_file")
            .input(Map.of("path", "/a")).build();
        var out = middleware.onActing(agent, channelCtx(),
                new ActingInput(List.of(toolCall)),
                in -> Flux.just(new ToolResultEndEvent("reply-1", "tc-2", "read_file",
                    ToolResultState.SUCCESS)))
            .collectList().block();

        assertEquals(1, out.size());
        verify(bridge, never()).onAgentSpawnResult(anyString(), anyMap(), anyString());
    }

    @Test
    void captureFailureMustNotBreakParentFlow() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
            .when(bridge).onAgentSpawnResult(anyString(), anyMap(), anyString());

        var out = middleware.onActing(agent, channelCtx(),
                new ActingInput(List.of(spawnCall("tc-1", Map.of("agent_key", "booking")))),
                in -> Flux.just(new ToolResultEndEvent("reply-1", "tc-1", "agent_spawn",
                    ToolResultState.SUCCESS)))
            .collectList().block();

        assertEquals(1, out.size(), "登记异常只降级可观测性，不打断父流");
    }
}
