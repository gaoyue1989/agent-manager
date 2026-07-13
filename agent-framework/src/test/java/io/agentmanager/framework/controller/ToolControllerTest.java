package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.*;
import io.agentmanager.framework.service.SkillManager.SkillInfo;
import io.agentmanager.framework.service.SkillManager.SkillMetadata;

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
    private List<SkillInfo> loadedSkills;

    @MockBean
    private SkillManager skillManager;

    @MockBean
    private List<Map<String, Object>> mcpConfigs;

    @MockBean
    private McpManager mcpManager;

    @Test
    void listSkillsShouldReturnSummaries() throws Exception {
        when(skillManager.getSkillSummaries(loadedSkills))
            .thenReturn(List.of(Map.of("name", "bash-tool", "description", "Execute bash commands")));

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
    void listToolsShouldReturnBuiltinAndCustom() throws Exception {
        when(oafConfig.tools()).thenReturn(List.of("Read", "Bash", "Edit"));

        mockMvc.perform(get("/tools"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.builtin").isArray())
            .andExpect(jsonPath("$.builtin[0]").value("Read"))
            .andExpect(jsonPath("$.custom").isArray());
    }
}
