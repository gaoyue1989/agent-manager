package io.agentmanager.framework.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.model.OafConfig;

/**
 * 将 OAF 配置转换为 AgentScope Workspace 目录结构。
 * 返回 Workspace 根路径，可直接传给 HarnessAgent.builder().workspace(path)。
 */
@Service
public class WorkspaceInitializer {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceInitializer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path configDir;

    public WorkspaceInitializer(io.agentmanager.framework.config.AgentManagerProperties props) {
        this.configDir = Path.of(props.configDir());
    }

    /**
     * 将 OAF 配置转换为 AgentScope Workspace 目录结构。
     */
    public Path initialize(Path baseDir, OafConfig oafConfig) throws IOException {
        var workspace = baseDir.resolve(".agentscope").resolve("workspace");
        Files.createDirectories(workspace);

        writeAgentsMd(workspace, oafConfig);
        writeToolsJson(workspace, oafConfig);
        copySkills(workspace, oafConfig);
        writeSubagents(workspace, oafConfig);

        log.info("Workspace initialized at: {}", workspace);
        return workspace;
    }

    /**
     * 生成 AGENTS.md：OAF frontmatter + body 写入 Workspace。
     */
    private void writeAgentsMd(Path workspace, OafConfig oafConfig) throws IOException {
        var agentsMd = workspace.resolve("AGENTS.md");
        if (Files.exists(agentsMd)) {
            log.info("AGENTS.md already exists, skipping generation");
            return;
        }

        var sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(oafConfig.name()).append("\n");
        sb.append("description: ").append(oafConfig.description() != null
                ? oafConfig.description() : "").append("\n");
        if (oafConfig.model() != null) {
            sb.append("model:\n");
            sb.append("  provider: ").append(oafConfig.model().provider()).append("\n");
            sb.append("  name: ").append(oafConfig.model().name()).append("\n");
        }
        if (oafConfig.runtimeConfig() != null) {
            sb.append("config:\n");
            sb.append("  temperature: ").append(oafConfig.runtimeConfig().temperature()).append("\n");
            sb.append("  max_tokens: ").append(oafConfig.runtimeConfig().maxTokens()).append("\n");
        }
        sb.append("---\n\n");
        sb.append(oafConfig.systemPrompt());

        Files.writeString(agentsMd, sb.toString());
        log.info("Generated AGENTS.md");
    }

    /**
     * 生成 tools.json：只写 deny 排除列表（可选）。
     * 不写 allow → 保留所有 Harness 内置工具。
     * MCP 服务器由 McpClientBuilder 原生注册（见 AgentScopeConfig），不再写入 tools.json。
     */
    private void writeToolsJson(Path workspace, OafConfig oafConfig) throws IOException {
        var toolsJson = workspace.resolve("tools.json");
        if (Files.exists(toolsJson)) {
            log.info("tools.json already exists, skipping generation");
            return;
        }

        var root = MAPPER.createObjectNode();

        if (oafConfig.hasDeniedTools()) {
            var denyNode = MAPPER.createArrayNode();
            for (var tool : oafConfig.deniedTools()) {
                denyNode.add(tool);
            }
            root.set("deny", denyNode);
            log.info("Generated tools.json with deny list: {}", oafConfig.deniedTools());
        }

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(toolsJson.toFile(), root);
        log.info("Generated tools.json (allow 模式已移除，保留全部内置工具)");
    }

    /**
     * 复制 skills：OAF skills/{name}/SKILL.md 复制到 Workspace。
     */
    private void copySkills(Path workspace, OafConfig oafConfig) throws IOException {
        var skillsDir = workspace.resolve("skills");
        Files.createDirectories(skillsDir);

        for (var skill : oafConfig.skills()) {
            if ("local".equals(skill.source())) {
                var sourceDir = configDir.resolve("skills").resolve(skill.name());
                var targetDir = skillsDir.resolve(skill.name());
                if (Files.exists(sourceDir) && !Files.exists(targetDir)) {
                    copyDirectory(sourceDir, targetDir);
                    log.info("Copied skill: {}", skill.name());
                }
            }
        }
    }

    /**
     * 生成 subagents：OAF agents 转换为 AgentScope subagents/*.md 格式。
     */
    private void writeSubagents(Path workspace, OafConfig oafConfig) throws IOException {
        var subagentsDir = workspace.resolve("subagents");
        Files.createDirectories(subagentsDir);

        for (var agent : oafConfig.subAgents()) {
            var agentFile = subagentsDir.resolve(agent.agent() + ".md");
            if (Files.exists(agentFile)) {
                continue;
            }

            var sb = new StringBuilder();
            sb.append("---\n");
            sb.append("description: ").append(agent.role()).append("\n");
            if (!agent.delegations().isEmpty()) {
                sb.append("delegations:\n");
                for (var d : agent.delegations()) {
                    sb.append("  - ").append(d).append("\n");
                }
            }
            sb.append("---\n\n");
            sb.append("你是").append(agent.role()).append("。\n");

            Files.writeString(agentFile, sb.toString());
            log.info("Generated subagent: {}", agent.agent());
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            stream.forEach(src -> {
                try {
                    var dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(src, dest);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
