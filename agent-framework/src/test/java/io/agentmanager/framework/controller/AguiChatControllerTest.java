package io.agentmanager.framework.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentmanager.framework.model.AguiRunProps;
import io.agentmanager.framework.service.AguiInterruptStore.AguiInterruptCorruptedException;
import io.agentmanager.framework.service.AguiRunService;
import io.agentmanager.framework.service.AguiRunService.AguiRequestException;
import io.agentmanager.framework.service.AguiRunService.PreparedRun;
import io.agentmanager.framework.service.ToolAuditStore;
import io.agentmanager.framework.service.TurnLeaseStore;
import io.agentmanager.framework.service.UploadWorkspaceInjector;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AguiChatController 单测（agui-migration-plan Phase 1）：
 * 双路由注册 + agentId 校验映射 + 预检失败 HTTP 语义（4xx JSON / 损坏 500）。
 * SSE 正常路径为 service 透传（帧断言在 AguiRunServiceTest 覆盖）。
 */
class AguiChatControllerTest {

    private MockMvc mvc;
    private AguiRunService runService;

    private void setUpWith(AguiRunService service) {
        runService = service;
        mvc = MockMvcBuilders.standaloneSetup(new AguiChatController(service)).build();
    }

    private static AguiRunService newService() {
        // controller 单测只验证路由与错误映射：直接 mock service（SSE 帧断言在 AguiRunServiceTest）
        return mock(AguiRunService.class);
    }

    private static String body() {
        return """
            {"threadId":"t-1","runId":"r-1","messages":[{"id":"m1","role":"user","content":"hi"}]}
            """;
    }

    @Test
    void runShouldAcceptPrimaryRoute() throws Exception {
        setUpWith(newService());
        when(runService.prepare(any(), any())).thenReturn(prepared());
        when(runService.stream(any())).thenReturn(Flux.empty());

        mvc.perform(post("/agui/run").contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isOk())
            .andExpect(result -> assertEquals(
                MediaType.TEXT_EVENT_STREAM_VALUE,
                result.getResponse().getContentType() != null
                    && result.getResponse().getContentType().contains("event-stream")
                    ? MediaType.TEXT_EVENT_STREAM_VALUE
                    : result.getResponse().getContentType()));
    }

    @Test
    void runShouldAcceptAgentIdRoute() throws Exception {
        setUpWith(newService());
        when(runService.prepare(any(), any())).thenReturn(prepared());
        when(runService.stream(any())).thenReturn(Flux.empty());

        mvc.perform(post("/agui/run/agent/release-agent/run")
                .contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isOk());
    }

    @Test
    void runShouldRejectUnknownAgentIdWith404() throws Exception {
        setUpWith(newService());
        when(runService.prepare(any(), any()))
            .thenThrow(new AguiRequestException(404, "unknown agent 'x'"));

        mvc.perform(post("/agui/run/agent/x/run")
                .contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("unknown agent 'x'"));
    }

    @Test
    void runShouldMapRequestErrorsToStatusCode() throws Exception {
        setUpWith(newService());
        when(runService.prepare(any(), any()))
            .thenThrow(new AguiRequestException(409, "no pending interrupt"));

        mvc.perform(post("/agui/run").contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value("no pending interrupt"));
    }

    @Test
    void runShouldMapCorruptInterruptTo500() throws Exception {
        setUpWith(newService());
        when(runService.prepare(any(), any()))
            .thenThrow(new AguiInterruptCorruptedException("metadata missing"));

        mvc.perform(post("/agui/run").contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("metadata missing"));
    }

    @Test
    void stopShouldDelegateToService() throws Exception {
        setUpWith(newService());
        when(runService.stop("t-9")).thenReturn(Map.of("threadId", "t-9", "stopped", true));

        mvc.perform(post("/agui/run/agent/release-agent/stop/t-9"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stopped").value(true));
    }

    private static PreparedRun prepared() {
        var ctx = RuntimeContext.builder().sessionId("t-1").userId("webui").build();
        var in = io.agentscope.core.agui.model.RunAgentInput.builder()
            .threadId("t-1").runId("r-1")
            .messages(List.of(io.agentscope.core.agui.model.AguiMessage.userMessage("m1", "hi")))
            .build();
        return new PreparedRun("t-1", "r-1", in, ctx, new AguiRunProps("webui", List.of()));
    }
}
