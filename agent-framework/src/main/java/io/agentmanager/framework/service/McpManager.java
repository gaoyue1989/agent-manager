package io.agentmanager.framework.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.model.OafConfig.McpServerConfig;

@Service
public class McpManager {
    private static final Logger log = LoggerFactory.getLogger(McpManager.class);

    private final Path mcpConfigsDir;

    public McpManager(java.nio.file.Path configDir) {
        this.mcpConfigsDir = configDir;
    }

    public List<Map<String, Object>> loadConfigs(List<McpServerConfig> mcpServers) {
        var configs = new ArrayList<Map<String, Object>>();
        for (var ms : mcpServers) {
            var config = loadSingleConfig(ms);
            if (config != null) {
                configs.add(config);
            }
        }
        return configs;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadSingleConfig(McpServerConfig ms) {
        var mcpDir = mcpConfigsDir.resolve(ms.configDir().isEmpty() ? ms.server() : ms.configDir());
        if (!mcpDir.toFile().exists()) {
            mcpDir = mcpConfigsDir.resolve(ms.server());
        }
        if (!mcpDir.toFile().exists()) {
            return null;
        }

        var config = new java.util.LinkedHashMap<String, Object>();
        config.put("vendor", ms.vendor());
        config.put("server", ms.server());
        config.put("version", ms.version());
        config.put("required", ms.required());

        var activeMcp = mcpDir.resolve("ActiveMCP.json");
        if (activeMcp.toFile().exists()) {
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                config.put("tools", mapper.readTree(activeMcp.toFile()));
            } catch (Exception e) {
                log.warn("Failed to load ActiveMCP.json for {}: {}", ms.server(), e.getMessage());
            }
        }

        var configYaml = mcpDir.resolve("config.yaml");
        if (configYaml.toFile().exists()) {
            try {
                var yaml = new org.yaml.snakeyaml.Yaml();
                var yamlData = (Map<String, Object>) yaml.load(configYaml.toFile().toURI().toURL().openStream());
                if (yamlData != null && yamlData.containsKey("connection")) {
                    config.put("connection", yamlData.get("connection"));
                }
            } catch (Exception e) {
                log.warn("Failed to load config.yaml for {}: {}", ms.server(), e.getMessage());
            }
        }

        return config;
    }

    public List<Map<String, Object>> getMcpSummaries(List<Map<String, Object>> mcpConfigs) {
        var summaries = new ArrayList<Map<String, Object>>();
        for (var mc : mcpConfigs) {
            @SuppressWarnings("unchecked")
            var conn = (Map<String, Object>) mc.getOrDefault("connection", Map.of());
            @SuppressWarnings("unchecked")
            var tools = (Map<String, Object>) mc.getOrDefault("tools", Map.of());
            summaries.add(Map.of(
                "server", mc.getOrDefault("server", "unknown"),
                "vendor", mc.getOrDefault("vendor", ""),
                "connection_type", conn.getOrDefault("type", "N/A"),
                "url", conn.getOrDefault("url", "N/A"),
                "tool_count", ((List<?>) tools.getOrDefault("selectedTools", List.of())).size()
            ));
        }
        return summaries;
    }
}
