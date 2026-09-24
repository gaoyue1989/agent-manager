package io.agentmanager.framework.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.model.OafConfig;

/**
 * 将 OAF 配置转换为 AgentScope Workspace 目录结构。
 * 返回 Workspace 根路径，可直接传给 HarnessAgent.builder().workspace(path)。
 *
 * <p>skills 不在此复制：OAF 包内 skills 目录由 AgentScopeConfig 注册为市场层
 * 仓库（FileSystemSkillRepository 动态重扫），运行中目录变化无需重启即可生效。
 */
@Service
public class WorkspaceInitializer {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceInitializer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 将 OAF 配置转换为 AgentScope Workspace 目录结构。 */
    public WorkspaceInitializer(io.agentmanager.framework.config.AgentManagerProperties props) {
    }

    /**
     * 将 OAF 配置转换为 AgentScope Workspace 目录结构。
     */
    public Path initialize(Path baseDir, OafConfig oafConfig) throws IOException {
        var workspace = baseDir.resolve(".agentscope").resolve("workspace");
        Files.createDirectories(workspace);

        writeAgentsMd(workspace, oafConfig, false);
        writeToolsJson(workspace, oafConfig, false);
        writeSubagents(workspace, oafConfig, false);

        log.info("Workspace initialized at: {}", workspace);
        return workspace;
    }

    /**
     * reload 场景的强制重写：与 {@link #initialize} 相同的目标文件，但一律覆盖——
     * "存在即跳过"会让新配置被旧 workspace 文件挡住。只覆盖三个生成文件，
     * 不触碰用户运行时文件与 per-user 技能（L4）。
     */
    public Path reinitialize(Path baseDir, OafConfig oafConfig) throws IOException {
        var workspace = baseDir.resolve(".agentscope").resolve("workspace");
        Files.createDirectories(workspace);

        writeAgentsMd(workspace, oafConfig, true);
        writeToolsJson(workspace, oafConfig, true);
        writeSubagents(workspace, oafConfig, true);

        log.info("Workspace reinitialized (force overwrite) at: {}", workspace);
        return workspace;
    }

    /**
     * 生成 AGENTS.md：OAF frontmatter + body 写入 Workspace。
     *
     * @param force true=覆盖已有文件（reload）；false=存在即跳过（启动期容忍手改）
     */
    private void writeAgentsMd(Path workspace, OafConfig oafConfig, boolean force) throws IOException {
        var agentsMd = workspace.resolve("AGENTS.md");
        if (!force && Files.exists(agentsMd)) {
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
    private void writeToolsJson(Path workspace, OafConfig oafConfig, boolean force) throws IOException {
        var toolsJson = workspace.resolve("tools.json");
        if (!force && Files.exists(toolsJson)) {
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
     * 生成 subagents：OAF agents 转换为 AgentScope subagents/*.md 格式。
     *
     * <p>force=false 时与旧行为一致（存在即跳过）；force=true（reload）时按当前
     * 声明全量重写，并删除目录中已不在新声明里的陈旧 subagent 文件。
     */
    private void writeSubagents(Path workspace, OafConfig oafConfig, boolean force) throws IOException {
        var subagentsDir = workspace.resolve("subagents");
        Files.createDirectories(subagentsDir);

        var declared = new ArrayList<String>();
        for (var agent : oafConfig.subAgents()) {
            var agentFile = subagentsDir.resolve(agent.agent() + ".md");
            declared.add(agentFile.getFileName().toString());
            if (!force && Files.exists(agentFile)) {
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

        // force：清理新声明中不存在的陈旧 subagent 文件
        if (force) {
            try (var files = Files.list(subagentsDir)) {
                for (var file : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                    if (!declared.contains(file.getFileName().toString())) {
                        Files.deleteIfExists(file);
                        log.info("Removed stale subagent: {}", file.getFileName());
                    }
                }
            }
        }
    }
}
