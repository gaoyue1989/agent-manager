package io.agentmanager.framework.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.model.OafConfig.McpServerConfig;

@Service
public class McpManager {
    private static final Logger log = LoggerFactory.getLogger(McpManager.class);

    private final Path mcpConfigsDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpToolRegistrar mcpToolRegistrar;

    public McpManager(java.nio.file.Path configDir, McpToolRegistrar mcpToolRegistrar) {
        this.mcpConfigsDir = configDir;
        this.mcpToolRegistrar = mcpToolRegistrar;
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

        var config = new LinkedHashMap<String, Object>();
        config.put("vendor", ms.vendor());
        config.put("server", ms.server());
        config.put("version", ms.version());
        config.put("required", ms.required());

        // 修复: ActiveMCP.json 安全加载（JsonNode → Map 转换，避免 ClassCastException）
        var activeMcp = mcpDir.resolve("ActiveMCP.json");
        if (activeMcp.toFile().exists()) {
            try {
                var jsonNode = mapper.readTree(activeMcp.toFile());
                config.put("tools", safeJsonNodeToMap(jsonNode));
            } catch (Exception e) {
                log.warn("Failed to load ActiveMCP.json for {}: {}", ms.server(), e.getMessage());
                config.put("tools", Map.of());
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

    /**
     * 安全地将 JsonNode 转换为 Map<String, Object>，避免类型强转异常。
     */
    private Map<String, Object> safeJsonNodeToMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            return Map.of("value", safeJsonNodeToValue(node));
        }

        var result = new LinkedHashMap<String, Object>();
        node.fields().forEachRemaining(e -> result.put(e.getKey(), safeJsonNodeToValue(e.getValue())));
        return result;
    }

    private Object safeJsonNodeToValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.isIntegralNumber() ? node.asLong() : node.asDouble();
        }
        if (node.isArray()) {
            var list = new ArrayList<>();
            for (var item : node) {
                list.add(safeJsonNodeToValue(item));
            }
            return list;
        }
        if (node.isObject()) {
            return safeJsonNodeToMap(node);
        }
        return node.toString();
    }

    public List<Map<String, Object>> getMcpSummaries(List<Map<String, Object>> mcpConfigs) {
        var summaries = new ArrayList<Map<String, Object>>();
        for (var mc : mcpConfigs) {
            try {
                @SuppressWarnings("unchecked")
                var conn = (Map<String, Object>) mc.getOrDefault("connection", Map.of());

                // 使用 McpToolRegistrar 注册缓存获取真实 tool_count
                var serverName = (String) mc.getOrDefault("server", "unknown");
                int toolCount = mcpToolRegistrar.getToolsByServer(serverName).size();

                summaries.add(Map.of(
                    "server", serverName,
                    "vendor", mc.getOrDefault("vendor", ""),
                    "connection_type", conn.getOrDefault("type", "N/A"),
                    "url", conn.getOrDefault("url", "N/A"),
                    "tool_count", toolCount
                ));
            } catch (Exception e) {
                log.warn("Failed to get summary for server: {}", mc.get("server"), e);
                summaries.add(Map.of(
                    "server", mc.getOrDefault("server", "unknown"),
                    "vendor", mc.getOrDefault("vendor", ""),
                    "connection_type", "N/A",
                    "url", "N/A",
                    "tool_count", 0,
                    "error", e.getMessage()
                ));
            }
        }
        return summaries;
    }
}
