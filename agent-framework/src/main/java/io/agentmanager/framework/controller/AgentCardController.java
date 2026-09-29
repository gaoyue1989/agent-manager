package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.config.OafConfigHolder;
import io.agentmanager.framework.model.AgentCardNotes;
import io.agentmanager.framework.service.A2uiService;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.SkillCatalogService;

@RestController
public class AgentCardController {

    private final OafConfigHolder oafConfigHolder;
    private final A2uiService a2uiService;
    private final AgentRuntimeService agentRuntime;
    private final SkillCatalogService skillCatalog;
    /** Agent Protocol 状态来源：本服务配置 + SDK 扩展属性（仅启用时装配，见 InfoController 词表） */
    private final io.agentmanager.framework.config.AgentManagerProperties props;
    private final org.springframework.beans.factory.ObjectProvider<io.agentscope.extensions.agentprotocol.AgentProtocolProperties> agentProtocolProperties;

    public AgentCardController(OafConfigHolder oafConfigHolder, A2uiService a2uiService,
                               AgentRuntimeService agentRuntime, SkillCatalogService skillCatalog,
                               io.agentmanager.framework.config.AgentManagerProperties props,
                               org.springframework.beans.factory.ObjectProvider<io.agentscope.extensions.agentprotocol.AgentProtocolProperties> agentProtocolProperties) {
        this.oafConfigHolder = oafConfigHolder;
        this.a2uiService = a2uiService;
        this.agentRuntime = agentRuntime;
        this.skillCatalog = skillCatalog;
        this.props = props;
        this.agentProtocolProperties = agentProtocolProperties;
    }

    @GetMapping("/.well-known/agent-card.json")
    public Map<String, Object> agentCard() {
        // holder 动态读取：reload 后新 name/version/description 即时反映到卡片
        var oafConfig = oafConfigHolder.get();
        // 动态技能目录：运行中新增/删除的技能即时反映到 A2A 卡片
        var skills = skillCatalog.list().stream()
            .map(s -> Map.of(
                "id", s.get("name"),
                "name", s.get("name"),
                "description", s.get("description"),
                "inputModes", List.of("text"),
                "outputModes", List.of("text", "text/plain")
            ))
            .toList();

        var card = new java.util.LinkedHashMap<String, Object>();
        card.put("name", oafConfig.name());
        // A2A 通道 HITL 限制声明（ask 工具挂起无法经 A2A 批准，见 AgentCardNotes）
        card.put("description", AgentCardNotes.withA2aLimitation(oafConfig.description()));
        card.put("url", "");
        card.put("version", oafConfig.version());
        card.put("provider", Map.of("organization", oafConfig.vendorKey()));
        card.put("capabilities", Map.of(
            "streaming", true,
            "pushNotifications", false,
            "stateTransitionHistory", true
        ));
        card.put("defaultInputModes", List.of("text", "text/plain"));
        card.put("defaultOutputModes", List.of("text", "text/plain", "a2ui/v0.8"));
        card.put("skills", skills.isEmpty() ? List.of(Map.of(
            "id", "default", "name", "General",
            "description", oafConfig.description(),
            "tags", oafConfig.tags(),
            "inputModes", List.of("text"),
            "outputModes", List.of("text", "text/plain", "a2ui/v0.8")
        )) : skills);
        card.put("extensions", List.of(a2uiService.getExtensionDeclaration()));
        // Agent Protocol（远程子 agent 服务端）状态：与 InfoController /metadata 同一词表
        //（additive 字段，A2A schema 外扩展，消费方不识别即忽略——travel-fulfillment §8 member-6）
        card.put("agent_protocol", InfoController.agentProtocolStatus(props, agentProtocolProperties));
        card.put("securitySchemes", Map.of(
            "bearer", Map.of("scheme", "bearer", "description", "Bearer token authentication")
        ));
        return card;
    }
}
