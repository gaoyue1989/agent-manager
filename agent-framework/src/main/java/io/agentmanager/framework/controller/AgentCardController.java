package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.A2uiService;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.SkillCatalogService;

@RestController
public class AgentCardController {

    private final OafConfig oafConfig;
    private final A2uiService a2uiService;
    private final AgentRuntimeService agentRuntime;
    private final SkillCatalogService skillCatalog;

    public AgentCardController(OafConfig oafConfig, A2uiService a2uiService,
                               AgentRuntimeService agentRuntime, SkillCatalogService skillCatalog) {
        this.oafConfig = oafConfig;
        this.a2uiService = a2uiService;
        this.agentRuntime = agentRuntime;
        this.skillCatalog = skillCatalog;
    }

    @GetMapping("/.well-known/agent-card.json")
    public Map<String, Object> agentCard() {
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
        card.put("description", oafConfig.description());
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
        card.put("securitySchemes", Map.of(
            "bearer", Map.of("scheme", "bearer", "description", "Bearer token authentication")
        ));
        return card;
    }
}
