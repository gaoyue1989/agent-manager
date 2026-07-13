package io.agentmanager.framework.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.model.OafConfig.SkillConfig;

@Service
public class SkillManager {
    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    private final Path skillsDir;

    public SkillManager(OafConfig oafConfig, java.nio.file.Path configDir) {
        this.skillsDir = configDir.resolve("skills");
    }

    public List<SkillInfo> loadAll(List<SkillConfig> skillConfigs) {
        var loaded = new ArrayList<SkillInfo>();
        for (var sc : skillConfigs) {
            var info = loadSkill(sc);
            if (info != null) {
                loaded.add(info);
            }
        }
        return loaded;
    }

    private SkillInfo loadSkill(SkillConfig config) {
        var skillDir = skillsDir.resolve(config.name());
        if (!skillDir.toFile().exists() || !skillDir.toFile().isDirectory()) {
            return null;
        }
        var metadata = loadMetadata(config, skillDir);
        return new SkillInfo(config.name(), skillDir.toString(), metadata, config);
    }

    private SkillMetadata loadMetadata(SkillConfig config, Path skillDir) {
        var skillMd = skillDir.resolve("SKILL.md");
        var meta = new SkillMetadata(config.name(), config.description(), config.version(), List.of());
        if (skillMd.toFile().exists()) {
            try {
                var content = java.nio.file.Files.readString(skillMd);
                if (content.startsWith("---")) {
                    var parts = content.split("---", 3);
                    if (parts.length >= 3) {
                        var yaml = new org.yaml.snakeyaml.Yaml();
                        @SuppressWarnings("unchecked")
                        var fm = (java.util.Map<String, Object>) yaml.load(parts[1].trim());
                        if (fm != null) {
                            @SuppressWarnings("unchecked")
                            var allowed = (java.util.List<String>) fm.getOrDefault("allowed-tools", List.of());
                            meta = new SkillMetadata(
                                (String) fm.getOrDefault("name", meta.name()),
                                (String) fm.getOrDefault("description", meta.description()),
                                (String) fm.getOrDefault("version", meta.version()),
                                allowed
                            );
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load SKILL.md for {}: {}", config.name(), e.getMessage());
            }
        }
        return meta;
    }

    public List<java.util.Map<String, Object>> getSkillSummaries(List<SkillInfo> loaded) {
        var summaries = new ArrayList<java.util.Map<String, Object>>();
        for (var skill : loaded) {
            summaries.add(java.util.Map.of(
                "name", skill.name(),
                "description", skill.metadata().description(),
                "version", skill.metadata().version()
            ));
        }
        return summaries;
    }

    public record SkillInfo(String name, String path, SkillMetadata metadata, SkillConfig config) {}
    public record SkillMetadata(String name, String description, String version, List<String> allowedTools) {}
}
