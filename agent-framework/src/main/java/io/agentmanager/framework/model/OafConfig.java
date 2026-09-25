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

    /**
     * 包级沙箱开关（frontmatter {@code config.sandbox.enabled}，issue #27b）。
     *
     * @return Boolean.TRUE/FALSE = 包作者显式声明（参与 SandboxRuntime 三层裁决的第二优先级）；
     *         null = 未声明（回落部署级 env / yml 默认）
     */
    public Boolean packageSandboxEnabled() {
        if (rawFrontmatter == null) {
            return null;
        }
        if (rawFrontmatter.get("config") instanceof Map<?, ?> cfg) {
            if (cfg.get("sandbox") instanceof Map<?, ?> sandbox) {
                var v = sandbox.get("enabled");
                if (v instanceof Boolean b) return b;
                if (v instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s.trim());
            }
        }
        return null;
    }

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
        boolean required, String description, List<String> allowedTools,
        String license, String compatibility, Map<String, String> metadata
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

    /**
     * @param permissionMode  权限模式（frontmatter config.permission.mode），缺省 "default"
     *                        （default | accept_edits | explore | bypass | dont_ask）
     * @param permissionTools 自定义/内置工具级三态权限（frontmatter config.permission.tools，
     *                        注册名 → allow|ask|deny）；仅 MCP 规则走 mcp-configs 的
     *                        permissions.tools，此处不承载 MCP 工具
     */
    public record RuntimeConfig(double temperature, int maxTokens, boolean requireConfirmation,
                                String permissionMode, Map<String, String> permissionTools) {
        /** 兼容构造：未声明工具级权限时视为空 Map */
        public RuntimeConfig(double temperature, int maxTokens, boolean requireConfirmation,
                             String permissionMode) {
            this(temperature, maxTokens, requireConfirmation, permissionMode, Map.of());
        }

        public boolean hasPermissionTools() {
            return permissionTools != null && !permissionTools.isEmpty();
        }
    }

    public record MemoryConfig(String type, Map<String, String> blocks) {}
}
