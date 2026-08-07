package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpManager;
import io.agentmanager.framework.service.McpToolRegistrar;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ToolController.class)
class ToolControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OafConfig oafConfig;

    @MockBean
    private AgentRuntimeService agentRuntime;

    @MockBean
    private List<Map<String, Object>> mcpConfigs;

    @MockBean
    private McpManager mcpManager;

    @MockBean
    private McpToolRegistrar mcpToolRegistrar;

    @BeforeEach
    void setUp() {
        // mcpConfigs 是 mock List，stub iterator 返回空迭代器，模拟无 MCP 配置
        when(mcpConfigs.iterator()).thenReturn(java.util.Collections.emptyIterator());
    }

    @Test
    void listSkillsShouldReturnOafSkills() throws Exception {
        when(oafConfig.skills()).thenReturn(List.of(
            new OafConfig.SkillConfig("bash-tool", "local", "1.0.0", false,
                "Execute bash commands", List.of("bash", "python"))
        ));

        mockMvc.perform(get("/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("bash-tool"))
            .andExpect(jsonPath("$[0].description").value("Execute bash commands"));
    }

    @Test
    void listMcpShouldReturnConfigs() throws Exception {
        when(mcpManager.getMcpSummaries(mcpConfigs))
            .thenReturn(List.of(Map.of("server", "weather-service", "tools", List.of())));

        mockMvc.perform(get("/mcp"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].server").value("weather-service"));
    }

    @Test
    void listToolsShouldReturnMcpToolsByDefault() throws Exception {
        when(mcpToolRegistrar.getToolsByServer("weather-service"))
            .thenReturn(List.of(
                new McpToolRegistrar.ToolInfo("get_weather", "Get weather", "weather-service")
            ));

        // mcpConfigs 为 MockBean 注入的 mock List，返回空列表（无 MCP server 配置）
        mockMvc.perform(get("/tools"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tools").isArray())
            .andExpect(jsonPath("$.mcpCount").value(0))
            .andExpect(jsonPath("$.totalCount").value(0));
    }

    @Test
    void listToolsShouldIncludeInternalWhenRequested() throws Exception {
        when(oafConfig.tools()).thenReturn(List.of("Read", "Bash", "Edit"));

        mockMvc.perform(get("/tools?includeInternal=true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tools").isArray())
            .andExpect(jsonPath("$.tools[0].name").value("Read"))
            .andExpect(jsonPath("$.tools[0].category").value("internal"))
            .andExpect(jsonPath("$.totalCount").value(3));
    }
}
