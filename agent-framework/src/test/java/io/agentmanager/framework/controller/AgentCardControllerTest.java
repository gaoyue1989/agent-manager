package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import io.agentmanager.framework.model.AgentCardNotes;
import io.agentmanager.framework.config.OafConfigHolder;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.A2uiService;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.SkillCatalogService;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AgentCardController.class)
class AgentCardControllerTest {

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
    private A2uiService a2uiService;

    @MockBean
    private AgentRuntimeService agentRuntime;

    @MockBean
    private SkillCatalogService skillCatalog;

    @MockBean
    private io.agentmanager.framework.config.AgentManagerProperties agentManagerProperties;

    /**
     * SDK 协议属性以 bean 形式入 context：Spring 对 ObjectProvider 注入点是容器生成的
     * 依赖解析代理（@MockBean ObjectProvider 不会进构造器），真实 provider 会从这里
     * 解析到本 mock；未启用语义 = mock 默认 false。
     */
    @MockBean
    private io.agentscope.extensions.agentprotocol.AgentProtocolProperties sdkAgentProtocolProperties;

    @Test
    void agentCardShouldReturnCard() throws Exception {
        when(oafConfig.name()).thenReturn("test-agent");
        when(oafConfig.description()).thenReturn("A test agent");
        when(oafConfig.version()).thenReturn("1.0.0");
        when(oafConfig.vendorKey()).thenReturn("acme");
        when(skillCatalog.list()).thenReturn(List.of());
        when(oafConfig.tags()).thenReturn(List.of("test"));
        when(a2uiService.getExtensionDeclaration())
            .thenReturn(Map.of("uri", "https://a2ui.org/a2a-extension/a2ui/v0.8", "params", Map.of()));

        mockMvc.perform(get("/.well-known/agent-card.json"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("test-agent"))
            // description = 包描述 + A2A 通道 HITL 限制声明（ask 工具无法经 A2A 批准）
            .andExpect(jsonPath("$.description").value(
                "A test agent " + AgentCardNotes.A2A_CHANNEL_LIMITATION))
            .andExpect(jsonPath("$.version").value("1.0.0"))
            .andExpect(jsonPath("$.provider.organization").value("acme"))
            .andExpect(jsonPath("$.capabilities.streaming").value(true))
            .andExpect(jsonPath("$.defaultInputModes[0]").value("text"))
            .andExpect(jsonPath("$.defaultOutputModes[0]").value("text"))
            .andExpect(jsonPath("$.securitySchemes.bearer").exists());
    }

    /** 协议未启用（mock 默认：props.agentProtocol()=null、SDK 属性缺位）→ enabled=false 恒存在 */
    @Test
    void agentCardShouldExposeDisabledAgentProtocolByDefault() throws Exception {
        when(oafConfig.name()).thenReturn("test-agent");
        when(oafConfig.description()).thenReturn("A test agent");
        when(oafConfig.version()).thenReturn("1.0.0");
        when(oafConfig.vendorKey()).thenReturn("acme");
        when(skillCatalog.list()).thenReturn(List.of());
        when(oafConfig.tags()).thenReturn(List.of("test"));
        when(a2uiService.getExtensionDeclaration()).thenReturn(Map.of());

        mockMvc.perform(get("/.well-known/agent-card.json"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agent_protocol.enabled").value(false))
            .andExpect(jsonPath("$.agent_protocol.task_store").value(""));
    }

    /** 协议启用 → 卡片透出 enabled/streaming/hitl（lead 侧据此发现远程子 agent 能力） */
    @Test
    void agentCardShouldExposeEnabledAgentProtocol() throws Exception {
        when(oafConfig.name()).thenReturn("test-agent");
        when(oafConfig.description()).thenReturn("A test agent");
        when(oafConfig.version()).thenReturn("1.0.0");
        when(oafConfig.vendorKey()).thenReturn("acme");
        when(skillCatalog.list()).thenReturn(List.of());
        when(oafConfig.tags()).thenReturn(List.of("test"));
        when(a2uiService.getExtensionDeclaration()).thenReturn(Map.of());
        var settings = new io.agentmanager.framework.config.AgentManagerProperties.AgentProtocolSettings(
            true, "tok", "", 7, 24, 5, "", true, 120, "memory");
        when(agentManagerProperties.agentProtocol()).thenReturn(settings);
        when(sdkAgentProtocolProperties.isStreamingEnabled()).thenReturn(true);
        when(sdkAgentProtocolProperties.isHitlEnabled()).thenReturn(true);

        mockMvc.perform(get("/.well-known/agent-card.json"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agent_protocol.enabled").value(true))
            .andExpect(jsonPath("$.agent_protocol.streaming").value(true))
            .andExpect(jsonPath("$.agent_protocol.hitl").value(true));
    }
}
