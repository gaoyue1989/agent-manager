package io.agentmanager.framework.controller;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import io.modelcontextprotocol.spec.McpSchema;

import io.agentmanager.framework.service.McpResourceProxy;

/**
 * MCP 代理端点 HTTP 编排测试（MCP Apps 扩展阶段一）。
 * 服务层安全规则见 McpResourceProxyTest；此处验证参数绑定、结果映射与错误→状态码透传。
 */
@WebMvcTest(McpProxyController.class)
class McpProxyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private McpResourceProxy resourceProxy;

    @Test
    void readUiResourceShouldReturnHtmlWithCsp() throws Exception {
        when(resourceProxy.readUiResource("weather", "ui://weather/card.html"))
            .thenReturn(new McpResourceProxy.UiResource("<html>card</html>", "text/html",
                Map.of("default-src", "'self'")));

        mockMvc.perform(get("/mcp/weather/resources/ui").param("uri", "ui://weather/card.html"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.html").value("<html>card</html>"))
            .andExpect(jsonPath("$.mimeType").value("text/html"))
            .andExpect(jsonPath("$.csp.default-src").value("'self'"));
    }

    @Test
    void listUiResourcesShouldReturnServerAndUris() throws Exception {
        when(resourceProxy.listUiResources("weather"))
            .thenReturn(List.of("ui://weather/card.html"));

        mockMvc.perform(get("/mcp/weather/resources"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.server").value("weather"))
            .andExpect(jsonPath("$.resources[0]").value("ui://weather/card.html"));
    }

    @Test
    void callToolShouldPassThroughResult() throws Exception {
        var content = new McpSchema.TextContent("72");
        when(resourceProxy.callTool(eq("weather"), eq("calc"), anyMap(), anyBoolean()))
            .thenReturn(new McpResourceProxy.CallToolResult(List.of(content), false,
                Map.of("answer", 72)));

        mockMvc.perform(post("/mcp/weather/tools/calc")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"arguments\":{\"a\":40,\"b\":2},\"confirmed\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].text").value("72"))
            .andExpect(jsonPath("$.isError").value(false))
            .andExpect(jsonPath("$.structuredContent.answer").value(72));
    }

    /** structuredContent 为 null 时必须归一为空对象（前端解构不炸） */
    @Test
    void callToolShouldDefaultNullStructuredContent() throws Exception {
        when(resourceProxy.callTool(eq("weather"), eq("calc"), anyMap(), anyBoolean()))
            .thenReturn(new McpResourceProxy.CallToolResult(List.of(), false, null));

        mockMvc.perform(post("/mcp/weather/tools/calc")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"arguments\":{}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.structuredContent").isEmpty());
    }

    /** ask 工具未确认 → 403 + needsConfirm + 确认卡片数据（复用 HITL 格式） */
    @Test
    void callToolAskWithoutConfirmShouldReturn403NeedsConfirm() throws Exception {
        when(resourceProxy.callTool(eq("weather"), eq("delete-city"), anyMap(), eq(false)))
            .thenThrow(new McpResourceProxy.NeedsConfirmException("delete-city",
                Map.of("city", "Beijing")));

        mockMvc.perform(post("/mcp/weather/tools/delete-city")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"arguments\":{\"city\":\"Beijing\"}}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.needsConfirm").value(true))
            .andExpect(jsonPath("$.toolCalls[0].name").value("delete-city"))
            .andExpect(jsonPath("$.toolCalls[0].input.city").value("Beijing"))
            .andExpect(jsonPath("$.toolCalls[0].tool_call_id").isNotEmpty());
    }

    /** 服务层 McpProxyException 的状态码与错误信息原样透传（未知 server → 404） */
    @Test
    void proxyExceptionStatusShouldBePreserved() throws Exception {
        when(resourceProxy.readUiResource("nope", "ui://x"))
            .thenThrow(new McpResourceProxy.McpProxyException(404, "unknown mcp server: nope"));

        mockMvc.perform(get("/mcp/nope/resources/ui").param("uri", "ui://x"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("unknown mcp server: nope"));
    }
}
