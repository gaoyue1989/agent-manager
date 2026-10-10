package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import io.agentmanager.framework.config.OafConfigHolder;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpManager;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InfoController.class)
class InfoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OafConfigHolder oafConfigHolder;

    @org.mockito.Mock
    private OafConfig oafConfig;

    @BeforeEach
    void stubOafConfigHolder() {
        org.mockito.Mockito.when(oafConfigHolder.get()).thenReturn(oafConfig);
    }

    @MockBean
    private AgentRuntimeService agentRuntime;

    @MockBean
    private McpManager mcpManager;

    @MockBean
    private io.agentmanager.framework.service.McpToolRegistrar mcpToolRegistrar;

    @MockBean
    private List<Map<String, Object>> mcpConfigs;

    @Test
    void rootShouldReturnServiceInfo() throws Exception {
        when(oafConfig.name()).thenReturn("test-agent");
        when(oafConfig.slug()).thenReturn("acme/test-agent");
        when(oafConfig.description()).thenReturn("A test agent");
        when(oafConfig.version()).thenReturn("1.0.0");
        when(oafConfig.tools()).thenReturn(List.of("Read", "Bash"));
        when(oafConfig.skills()).thenReturn(List.of());
        when(oafConfig.mcpServers()).thenReturn(List.of());
        when(oafConfig.subAgents()).thenReturn(List.of());

        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agent").value("test-agent"))
            .andExpect(jsonPath("$.description").value("A test agent"))
            .andExpect(jsonPath("$.version").value("1.0.0"))
            .andExpect(jsonPath("$.protocols.a2a").value("1.0.0"))
            .andExpect(jsonPath("$.protocols.a2ui").value("v0.8"))
            .andExpect(jsonPath("$.protocols.oaf").value("v0.8.0"))
            .andExpect(jsonPath("$.engine").value("AgentScope Java 2.0"))
            .andExpect(jsonPath("$.endpoints.agent_card").value("/.well-known/agent-card.json"))
            .andExpect(jsonPath("$.endpoints.jsonrpc").value("/"))
            .andExpect(jsonPath("$.endpoints.health").value("/health"))
            .andExpect(jsonPath("$.endpoints.debug").value("/debug"));
    }

    @Test
    void metadataShouldReturnFullAgentInfo() throws Exception {
        when(oafConfig.name()).thenReturn("test-agent");
        when(oafConfig.slug()).thenReturn("acme/test-agent");
        when(oafConfig.version()).thenReturn("1.0.0");
        when(oafConfig.description()).thenReturn("A test agent");
        when(oafConfig.skills()).thenReturn(List.of(
            new OafConfig.SkillConfig("code-review", "local", "1.0.0", false,
                "Code review skill", List.of("bash", "python"),
                "", "", java.util.Map.of())
        ));
                when(mcpManager.getMcpSummaries(org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(List.of(Map.of("server", "weather-service", "tool_count", 3)));

        mockMvc.perform(get("/metadata"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("test-agent"))
            .andExpect(jsonPath("$.slug").value("acme/test-agent"))
            .andExpect(jsonPath("$.skills[0].name").value("code-review"))
            .andExpect(jsonPath("$.skills[0].description").value("Code review skill"))
            .andExpect(jsonPath("$.mcp[0].tool_count").value(3));
    }

    @Test
    void systemPromptShouldReturnPrompts() throws Exception {
        when(agentRuntime.buildSystemPrompt()).thenReturn("Full system prompt with skills");
        when(oafConfig.systemPrompt()).thenReturn("Base prompt");

        mockMvc.perform(get("/system-prompt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.system_prompt").value("Full system prompt with skills"))
            .andExpect(jsonPath("$.base_prompt").value("Base prompt"));
    }

    /** 直连（无代理头）：endpoints 根路径 + 无 base_url 键（响应体与变更前一致） */
    @Test
    void rootAndMetadataShouldStayUnprefixedWithoutForwardedHeader() throws Exception {
        when(oafConfig.name()).thenReturn("test-agent");
        when(oafConfig.slug()).thenReturn("acme/test-agent");
        when(oafConfig.version()).thenReturn("1.0.0");
        when(oafConfig.description()).thenReturn("A test agent");
        when(oafConfig.tools()).thenReturn(List.of());
        when(oafConfig.skills()).thenReturn(List.of());
        when(oafConfig.mcpServers()).thenReturn(List.of());
        when(oafConfig.subAgents()).thenReturn(List.of());
        when(mcpManager.getMcpSummaries(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of());

        mockMvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.endpoints.agent_card").value("/.well-known/agent-card.json"))
            .andExpect(jsonPath("$.base_url").doesNotExist());

        mockMvc.perform(get("/metadata").param("includeDetails", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.endpoints.threads").value("/threads"))
            .andExpect(jsonPath("$.base_url").doesNotExist());
    }

    /** 经代理（X-Forwarded-Prefix/Host/Proto）：endpoints 逐项带前缀 + base_url 填外部基址 */
    @Test
    void rootAndMetadataShouldHonorForwardedPrefix() throws Exception {
        when(oafConfig.name()).thenReturn("test-agent");
        when(oafConfig.slug()).thenReturn("acme/test-agent");
        when(oafConfig.version()).thenReturn("1.0.0");
        when(oafConfig.description()).thenReturn("A test agent");
        when(oafConfig.tools()).thenReturn(List.of());
        when(oafConfig.skills()).thenReturn(List.of());
        when(oafConfig.mcpServers()).thenReturn(List.of());
        when(oafConfig.subAgents()).thenReturn(List.of());
        when(mcpManager.getMcpSummaries(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of());

        mockMvc.perform(get("/")
                .header("X-Forwarded-Prefix", "/agent/demo")
                .header("X-Forwarded-Host", "entry.example:30080")
                .header("X-Forwarded-Proto", "https"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.endpoints.agent_card").value("/agent/demo/.well-known/agent-card.json"))
            .andExpect(jsonPath("$.endpoints.jsonrpc").value("/agent/demo/"))
            .andExpect(jsonPath("$.endpoints.threads").value("/agent/demo/threads"))
            .andExpect(jsonPath("$.endpoints.health").value("/agent/demo/health"))
            .andExpect(jsonPath("$.base_url").value("https://entry.example:30080/agent/demo"));

        mockMvc.perform(get("/metadata").param("includeDetails", "true")
                .header("X-Forwarded-Prefix", "/agent/demo")
                .header("X-Forwarded-Host", "entry.example:30080"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.endpoints.agent_card").value("/agent/demo/.well-known/agent-card.json"))
            .andExpect(jsonPath("$.base_url").value("http://entry.example:30080/agent/demo"));
    }
}
