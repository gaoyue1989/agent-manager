package io.agentmanager.framework.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.model.OafConfig.*;

@Component
public class OafConfigLoader {

    private final Path configDir;

    public OafConfigLoader(AgentManagerProperties props) {
        this.configDir = Path.of(props.configDir());
    }

    public OafConfig load() {
        var agentsMd = configDir.resolve("AGENTS.md");
        if (!Files.exists(agentsMd)) {
            throw new IllegalStateException("AGENTS.md not found at " + agentsMd);
        }
        try {
            var content = Files.readString(agentsMd);
            var parsed = parseFrontmatter(content);
            var fm = parsed.frontmatter();
            var body = parsed.body();

            @SuppressWarnings("unchecked")
            var name = (String) fm.getOrDefault("name", configDir.getFileName().toString());
            @SuppressWarnings("unchecked")
            var vendorKey = (String) fm.getOrDefault("vendorKey", "local");
            @SuppressWarnings("unchecked")
            var agentKey = (String) fm.getOrDefault("agentKey", configDir.getFileName().toString());
            @SuppressWarnings("unchecked")
            var version = (String) fm.getOrDefault("version", "1.0.0");
            @SuppressWarnings("unchecked")
            var slug = (String) fm.getOrDefault("slug", vendorKey + "/" + agentKey);
            @SuppressWarnings("unchecked")
            var description = (String) fm.getOrDefault("description", "");
            @SuppressWarnings("unchecked")
            var author = (String) fm.getOrDefault("author", "@local");
            @SuppressWarnings("unchecked")
            var license = (String) fm.getOrDefault("license", "MIT");
            @SuppressWarnings("unchecked")
            var rawTags = (List<String>) fm.getOrDefault("tags", Collections.emptyList());
            @SuppressWarnings("unchecked")
            var rawTools = (List<String>) fm.getOrDefault("tools", Collections.emptyList());

            var skills = parseSkills(fm);
            var mcpServers = parseMcpServers(fm);
            var subAgents = parseSubAgents(fm);

            var model = parseModel(fm);
            var runtimeConfig = parseRuntimeConfig(fm);
            var memory = parseMemory(fm);

            return new OafConfig(
                name, vendorKey, agentKey, version, slug, description,
                author, license, rawTags, body, skills, mcpServers,
                subAgents, rawTools, model, runtimeConfig, memory,
                fm
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to load OAF config", e);
        }
    }

    private FrontmatterResult parseFrontmatter(String content) {
        if (!content.startsWith("---")) {
            return new FrontmatterResult(Collections.emptyMap(), content);
        }
        var parts = content.split("---", 3);
        if (parts.length < 3) {
            return new FrontmatterResult(Collections.emptyMap(), content);
        }
        var yaml = new Yaml();
        @SuppressWarnings("unchecked")
        var fm = (Map<String, Object>) yaml.load(parts[1].trim());
        if (fm == null) fm = Collections.emptyMap();
        return new FrontmatterResult(fm, parts[2].trim());
    }

    private record FrontmatterResult(Map<String, Object> frontmatter, String body) {}

    @SuppressWarnings("unchecked")
    private List<SkillConfig> parseSkills(Map<String, Object> fm) {
        var raw = (List<Map<String, Object>>) fm.getOrDefault("skills", Collections.emptyList());
        if (raw == null) return Collections.emptyList();
        var skills = new ArrayList<SkillConfig>();
        for (var m : raw) {
            var name = (String) m.getOrDefault("name", "");
            var source = (String) m.getOrDefault("source", "local");
            var version = (String) m.getOrDefault("version", "1.0.0");
            var required = (boolean) m.getOrDefault("required", false);
            var desc = loadSkillDescription(name);
            skills.add(new SkillConfig(name, source, version, required, desc, List.of("bash", "python")));
        }
        return skills;
    }

    private String loadSkillDescription(String skillName) {
        var skillMd = configDir.resolve("skills").resolve(skillName).resolve("SKILL.md");
        if (Files.exists(skillMd)) {
            try {
                var content = Files.readString(skillMd);
                var parsed = parseFrontmatter(content);
                var text = parsed.body();
                return text.length() > 500 ? text.substring(0, 500) : text;
            } catch (IOException e) {
                return "";
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private List<McpServerConfig> parseMcpServers(Map<String, Object> fm) {
        var raw = (List<Map<String, Object>>) fm.getOrDefault("mcpServers", Collections.emptyList());
        if (raw == null) return Collections.emptyList();
        var servers = new ArrayList<McpServerConfig>();
        for (var m : raw) {
            servers.add(new McpServerConfig(
                (String) m.getOrDefault("vendor", ""),
                (String) m.getOrDefault("server", ""),
                (String) m.getOrDefault("version", "1.0.0"),
                (String) m.getOrDefault("configDir", ""),
                (boolean) m.getOrDefault("required", false)
            ));
        }
        return servers;
    }

    @SuppressWarnings("unchecked")
    private List<SubAgentConfig> parseSubAgents(Map<String, Object> fm) {
        var raw = (List<Map<String, Object>>) fm.getOrDefault("agents", Collections.emptyList());
        if (raw == null) return Collections.emptyList();
        var agents = new ArrayList<SubAgentConfig>();
        for (var m : raw) {
            agents.add(new SubAgentConfig(
                (String) m.getOrDefault("vendor", ""),
                (String) m.getOrDefault("agent", ""),
                (String) m.getOrDefault("version", "1.0.0"),
                (String) m.getOrDefault("role", ""),
                (List<String>) m.getOrDefault("delegations", Collections.emptyList()),
                (boolean) m.getOrDefault("required", false),
                (String) m.getOrDefault("endpoint", "")
            ));
        }
        return agents;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ModelConfig parseModel(Map<String, Object> fm) {
        var raw = fm.get("model");
        if (raw instanceof String s) {
            return new ModelConfig("openai", s, "");
        }
        if (raw instanceof Map) {
            var m = (Map<String, Object>) raw;
            return new ModelConfig(
                (String) m.getOrDefault("provider", "openai"),
                (String) m.getOrDefault("name", ""),
                (String) m.getOrDefault("embedding", "")
            );
        }
        return new ModelConfig("openai", "", "");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RuntimeConfig parseRuntimeConfig(Map<String, Object> fm) {
        var raw = fm.get("config");
        if (raw instanceof Map) {
            var m = (Map<String, Object>) raw;
            return new RuntimeConfig(
                ((Number) m.getOrDefault("temperature", 0.7)).doubleValue(),
                ((Number) m.getOrDefault("max_tokens", 4096)).intValue(),
                (boolean) m.getOrDefault("require_confirmation", false)
            );
        }
        return new RuntimeConfig(0.7, 4096, false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private MemoryConfig parseMemory(Map<String, Object> fm) {
        var raw = fm.get("memory");
        if (raw instanceof Map) {
            var m = (Map<String, Object>) raw;
            var blocksRaw = m.get("blocks");
            var blocks = blocksRaw instanceof Map ? (Map<String, String>) blocksRaw : Collections.<String, String>emptyMap();
            return new MemoryConfig(
                (String) m.getOrDefault("type", "editable"),
                blocks
            );
        }
        return new MemoryConfig("editable", Collections.emptyMap());
    }
}
