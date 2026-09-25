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
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.SkillCatalogService;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ToolController.class)
class ToolControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OafConfigHolder oafConfigHolder;

    @org.mockito.Mock
    private OafConfig oafConfig;

    @MockBean
    private AgentRuntimeService agentRuntime;

    @MockBean
    private List<Map<String, Object>> mcpConfigs;

    @MockBean
    private McpManager mcpManager;

    @MockBean
    private McpToolRegistrar mcpToolRegistrar;

    @MockBean
    private SkillCatalogService skillCatalog;

    @MockBean
    private io.agentmanager.framework.service.InternalToolRegistry internalToolRegistry;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(oafConfigHolder.get()).thenReturn(oafConfig);
        // mcpConfigs 是 mock List，stub iterator 返回空迭代器，模拟无 MCP 配置
        // InfoController/ToolController 已切动态配置：controller 调 mcpManager.loadConfigs(holder 配置)
        when(mcpManager.loadConfigs(org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(java.util.Collections.emptyList());
    }

    @Test
    void listSkillsShouldReturnOafSkills() throws Exception {
        when(skillCatalog.list()).thenReturn(List.of(
            Map.of("name", "bash-tool", "description", "Execute bash commands",
                "version", "1.0.0", "source", "local", "required", false,
                "dynamic", true, "declaredButMissing", false)
        ));

        mockMvc.perform(get("/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("bash-tool"))
            .andExpect(jsonPath("$[0].description").value("Execute bash commands"));
    }

    @Test
    void listMcpShouldReturnConfigs() throws Exception {
        when(mcpManager.getMcpSummaries(org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(List.of(Map.of("server", "weather-service", "tools", List.of())));

        mockMvc.perform(get("/mcp"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].server").value("weather-service"));
    }

    @Test
    void listToolsShouldReturnMcpToolsByDefault() throws Exception {
        when(mcpToolRegistrar.getToolsByServer("weather-service"))
            .thenReturn(List.of(
                new McpToolRegistrar.ToolInfo("get_weather", "mcp__weather-service__get_weather", "Get weather", "weather-service")
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
        // issue #28：内置清单以运行时注册集为准（registry），declared 标注 OAF 声明意图
        when(internalToolRegistry.listInternalTools()).thenReturn(List.of(
            Map.of("name", "get_current_time", "category", "internal", "source", "builtin", "declared", true),
            Map.of("name", "present_url", "category", "internal", "source", "builtin", "declared", false)
        ));

        mockMvc.perform(get("/tools?includeInternal=true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tools").isArray())
            .andExpect(jsonPath("$.tools[0].name").value("get_current_time"))
            .andExpect(jsonPath("$.tools[0].category").value("internal"))
            .andExpect(jsonPath("$.tools[0].declared").value(true))
            .andExpect(jsonPath("$.tools[1].name").value("present_url"))
            .andExpect(jsonPath("$.tools[1].declared").value(false))
            .andExpect(jsonPath("$.internalCount").value(2))
            .andExpect(jsonPath("$.totalCount").value(2));
    }

    @Test
    void listToolsInternalShouldFallBackToRegistryEvenWhenOafToolsEmpty() throws Exception {
        // 回归 issue #28 失真场景：OAF tools:[] 但运行时注册存在 → 清单不得为空
        when(internalToolRegistry.listInternalTools()).thenReturn(List.of(
            Map.of("name", "echo", "category", "internal", "source", "builtin", "declared", false)
        ));

        mockMvc.perform(get("/tools?includeInternal=true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tools[0].name").value("echo"))
            .andExpect(jsonPath("$.totalCount").value(1));
    }
}
