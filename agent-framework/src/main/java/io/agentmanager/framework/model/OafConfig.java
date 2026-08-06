package io.agentmanager.framework.model;

import java.util.List;
import java.util.Map;

public record OafConfig(
    String name,
    String vendorKey,
    String agentKey,
    String version,
    String slug,
    String description,
    String author,
    String license,
    List<String> tags,
    String systemPrompt,
    List<SkillConfig> skills,
    List<McpServerConfig> mcpServers,
    List<SubAgentConfig> subAgents,
    List<String> tools,
    List<String> deniedTools,
    ModelConfig model,
    RuntimeConfig runtimeConfig,
    MemoryConfig memory,
    Map<String, Object> rawFrontmatter
) {
    public List<SkillConfig> localSkills() {
        return skills.stream().filter(s -> "local".equals(s.source())).toList();
    }

    public List<SkillConfig> remoteSkills() {
        return skills.stream().filter(s -> !"local".equals(s.source())).toList();
    }

    public boolean hasSkills() { return !skills.isEmpty(); }
    public boolean hasMcp() { return !mcpServers.isEmpty(); }
    public boolean hasSubAgents() { return !subAgents.isEmpty(); }
    public boolean hasDeniedTools() { return deniedTools != null && !deniedTools.isEmpty(); }

    public String getCatalogId() {
        var hc = rawFrontmatter != null ? rawFrontmatter.get("harnessConfig") : null;
        if (hc instanceof Map<?, ?> harness) {
            var da = harness.get("deep-agents");
            if (da instanceof Map<?, ?> deepAgents) {
                var a2ui = deepAgents.get("a2ui");
                if (a2ui instanceof Map<?, ?> a2uiMap) {
                    var cid = a2uiMap.get("catalog_id");
                    if (cid instanceof String s) return s;
                }
            }
        }
        return "https://a2ui.org/specification/v0_8/standard_catalog_definition.json";
    }

    public record SkillConfig(
        String name, String source, String version,
        boolean required, String description, List<String> allowedTools
    ) {}

    public record McpServerConfig(
        String vendor, String server, String version,
        String configDir, boolean required
    ) {}

    public record SubAgentConfig(
        String vendor, String agent, String version,
        String role, List<String> delegations, boolean required, String endpoint
    ) {}

    public record ModelConfig(String provider, String name, String embedding) {}

    public record RuntimeConfig(double temperature, int maxTokens, boolean requireConfirmation) {}

    public record MemoryConfig(String type, Map<String, String> blocks) {}
}
