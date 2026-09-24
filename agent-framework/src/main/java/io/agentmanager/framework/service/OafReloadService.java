package io.agentmanager.framework.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.OafConfigHolder;
import io.agentmanager.framework.config.OafConfigLoader;
import io.agentmanager.framework.model.OafConfig;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.DistributedStore;
import io.agentmanager.framework.sandbox.opensandbox.OpenSandboxFilesystemSpec;

/**
 * OAF 配置 reload 编排（docs/oaf-dynamic-reload-plan.md §4.1）：
 * PVC /config 原位更新后无需重启 Pod，经触发通道（POST /admin/reload 等）收敛到本服务，
 * 按指纹比对自动分流两条路径：
 *
 * <ul>
 *   <li><b>A：MCP 原地 reload</b>——重解析 frontmatter 取最新 mcpServers 声明，逐 server
 *       clearServer（toolkit.removeMcpClient 关连接摘工具 + 缓存清理）→ registerOne 重建；</li>
 *   <li><b>B：整包重建 agent</b>——AGENTS.md 变化（sysPrompt/model/权限/mcpServers 声明）
 *       时，重新解析 OafConfig → WorkspaceInitializer.reinitialize 覆盖 workspace 生成文件
 *       → HarnessAgentFactory.build 全新 agent → 原子切换引用 → 旧 agent 的 MCP 连接关闭。</li>
 * </ul>
 *
 * 失败语义：任何异常保持旧 agent/旧连接继续服务（reload 永不比启动更严格：
 * startup.required=true 的 server 失败 → 整次 reload 拒绝回滚）。
 * 生效语义：下一轮对话生效（进行中 turn 持有旧引用跑完）；会话记忆在 MySQL 不丢。
 * 并发防抖：AtomicBoolean，进行中重复触发直接返回。
 */
@Service
public class OafReloadService {
    private static final Logger log = LoggerFactory.getLogger(OafReloadService.class);

    private final OafConfigLoader oafConfigLoader;
    private final OafConfigHolder oafConfigHolder;
    private final AgentManagerProperties props;
    private final HarnessAgentFactory harnessAgentFactory;
    private final McpToolRegistrar mcpToolRegistrar;
    private final McpResourceProxy mcpResourceProxy;
    private final WorkspaceInitializer workspaceInitializer;
    private final AgentRuntimeService agentRuntimeService;
    private final A2aAgentRefHolder a2aAgentRefHolder;
    private final DistributedStore distributedStore;
    private final LLMLogger llmLogger;
    private final UiContextStore uiContextStore;
    private final SessionUserStore sessionUserStore;
    private final ObjectProvider<OpenSandboxFilesystemSpec> sandboxSpecProvider;

    /** 当前指纹快照（AGENTS.md + mcp 配置目录的 mtime+size）；null=尚未记录 */
    private final AtomicReference<Fingerprint> fingerprint = new AtomicReference<>();

    /** reload 进行中标记（并发防抖） */
    private final AtomicBoolean reloading = new AtomicBoolean(false);

    public OafReloadService(
        OafConfigLoader oafConfigLoader,
        OafConfigHolder oafConfigHolder,
        AgentManagerProperties props,
        HarnessAgentFactory harnessAgentFactory,
        McpToolRegistrar mcpToolRegistrar,
        McpResourceProxy mcpResourceProxy,
        WorkspaceInitializer workspaceInitializer,
        AgentRuntimeService agentRuntimeService,
        A2aAgentRefHolder a2aAgentRefHolder,
        DistributedStore distributedStore,
        LLMLogger llmLogger,
        UiContextStore uiContextStore,
        SessionUserStore sessionUserStore,
        ObjectProvider<OpenSandboxFilesystemSpec> sandboxSpecProvider
    ) {
        this.oafConfigLoader = oafConfigLoader;
        this.oafConfigHolder = oafConfigHolder;
        this.props = props;
        this.harnessAgentFactory = harnessAgentFactory;
        this.mcpToolRegistrar = mcpToolRegistrar;
        this.mcpResourceProxy = mcpResourceProxy;
        this.workspaceInitializer = workspaceInitializer;
        this.agentRuntimeService = agentRuntimeService;
        this.a2aAgentRefHolder = a2aAgentRefHolder;
        this.distributedStore = distributedStore;
        this.llmLogger = llmLogger;
        this.uiContextStore = uiContextStore;
        this.sessionUserStore = sessionUserStore;
        this.sandboxSpecProvider = sandboxSpecProvider;
        this.fingerprint.set(scanFingerprint());
    }

    // ==================== 对外入口 ====================

    /**
     * 总入口：指纹比对自动分流（scope=auto 的默认路径）。
     *
     * @return 结构化结果（scope/fingerprintChanged/agentRebuilt/mcpServers 明细）
     */
    public synchronized ReloadResult reload() throws IOException {
        var old = fingerprint.get();
        var current = scanFingerprint();
        if (old != null && current.equals(old)) {
            return ReloadResult.noop("fingerprint unchanged");
        }
        // B 路径覆盖 A 路径（整包重建包含 MCP 全量重注册）；仅 mcp 配置变化走 A
        boolean agentsMdChanged = old == null || !old.agentsMd().equals(current.agentsMd());
        ReloadResult result;
        if (agentsMdChanged) {
            result = reloadAgent();
        } else {
            result = reloadMcpAll();
        }
        fingerprint.set(current);
        return result;
    }

    /**
     * A 路径：全部 MCP server 原地 reload。
     * 重解析 frontmatter 取最新 mcpServers 声明（声明增删即时生效），与已注册集合做差异：
     * 声明中已删除 → clearServer 摘除；声明中的 server → 先摘后建（重连+重列工具）。
     * 成功后同步刷新 OafConfigHolder（/metadata、McpResourceProxy 声明视图一致）。
     */
    public synchronized ReloadResult reloadMcpAll() {
        OafConfig freshConfig;
        try {
            freshConfig = oafConfigLoader.load();
        } catch (Exception e) {
            log.error("[Reload] frontmatter re-parse failed, MCP reload aborted: {}", e.getMessage());
            return new ReloadResult("mcp", false, false, List.of(),
                "frontmatter re-parse failed: " + e.getMessage());
        }
        var detail = new ArrayList<Map<String, Object>>();
        var declaredNames = freshConfig.mcpServers().stream().map(OafConfig.McpServerConfig::server).toList();

        // ① 声明中已删除的 server：摘除（工具下线 + 连接关闭）
        for (var registered : mcpToolRegistrar.getRegisteredServerNames()) {
            if (!declaredNames.contains(registered)) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("server", registered);
                boolean removed = mcpToolRegistrar.clearServer(currentToolkitOrNull(), registered);
                mcpResourceProxy.evictClient(registered);
                entry.put("action", "removed");
                entry.put("ok", removed);
                detail.add(entry);
            }
        }

        // ② 声明中的 server：先摘后建（重连+重列工具即一次完整 reload）
        for (var mcp : freshConfig.mcpServers()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("server", mcp.server());
            mcpToolRegistrar.clearServer(currentToolkitOrNull(), mcp.server());
            mcpResourceProxy.evictClient(mcp.server());
            String registered = registerSafely(mcp);
            entry.put("action", registered != null ? "reloaded" : "skipped");
            entry.put("ok", registered != null);
            var wrapper = mcpToolRegistrar.getRegisteredWrapper(mcp.server());
            entry.put("tool_count", wrapper != null ? safeToolCount(wrapper) : 0);
            detail.add(entry);
        }

        oafConfigHolder.update(freshConfig);
        fingerprint.set(scanFingerprint());
        log.info("[Reload] MCP reloaded: {} servers processed", detail.size());
        return new ReloadResult("mcp", true, false, detail, null);
    }

    /**
     * A 路径单 server 版（显式指定 server 的运维场景）。
     */
    public synchronized ReloadResult reloadMcpServer(String serverName) {
        var oafConfig = oafConfigHolder.get();
        var mcpOpt = oafConfig.mcpServers().stream()
            .filter(m -> m.server().equals(serverName))
            .findFirst();
        var detail = new ArrayList<Map<String, Object>>();
        var entry = new LinkedHashMap<String, Object>();
        entry.put("server", serverName);
        if (mcpOpt.isEmpty()) {
            entry.put("action", "not-declared");
            entry.put("ok", false);
            detail.add(entry);
            return new ReloadResult("mcp", true, false, detail,
                "server '" + serverName + "' not declared in frontmatter mcpServers");
        }
        mcpToolRegistrar.clearServer(currentToolkitOrNull(), serverName);
        mcpResourceProxy.evictClient(serverName);
        String registered = registerSafely(mcpOpt.get());
        entry.put("action", registered != null ? "reloaded" : "skipped");
        entry.put("ok", registered != null);
        var wrapper = mcpToolRegistrar.getRegisteredWrapper(serverName);
        entry.put("tool_count", wrapper != null ? safeToolCount(wrapper) : 0);
        detail.add(entry);
        return new ReloadResult("mcp", true, false, detail, null);
    }

    /**
     * B 路径：整包重建 HarnessAgent（官方 dataagent 模式）。
     * 重新解析 frontmatter → 覆盖 workspace 生成文件 → 全新 Toolkit + MCP 全量注册 →
     * 原子切换 AgentRuntimeService / A2A holder / OafConfigHolder → 旧 agent 的 MCP 连接关闭。
     * 任何失败保持旧 agent 服务（新 toolkit 的连接由本方法兜底清理）。
     */
    public synchronized ReloadResult reloadAgent() throws IOException {
        // 1. 重新解析（语法错误等在此抛出，旧 agent 不受影响）
        var newConfig = oafConfigLoader.load();

        // 2. 覆盖 workspace 生成文件（AGENTS.md/tools.json/subagents）
        var workspacePath = workspaceInitializer.reinitialize(
            Path.of(props.resolvedWorkspaceBaseDir()), newConfig);
        log.info("[Reload] workspace reinitialized at {}", workspacePath);

        // 3. 构建全新 agent（内含新 Toolkit + MCP 全量注册，fail-soft/required 语义与启动一致）
        HarnessAgent newAgent;
        try {
            newAgent = harnessAgentFactory.build(newConfig, distributedStore, llmLogger,
                uiContextStore, sessionUserStore, sandboxSpecProvider.getIfAvailable());
        } catch (Exception e) {
            log.error("[Reload] agent rebuild failed, keeping old agent: {}", e.getMessage(), e);
            throw new IOException("agent rebuild failed: " + e.getMessage(), e);
        }

        // 4. 原子切换引用（下一轮对话生效；进行中 turn 持有旧引用不受影响）
        var oldAgent = agentRuntimeService.swapAgent(newAgent);
        a2aAgentRefHolder.update(newAgent);
        oafConfigHolder.update(newConfig);

        // 5. 旧 agent 的 MCP 连接收尾（旧 toolkit 无公开 closeMcpClients，逐 server remove）
        if (oldAgent != null) {
            try {
                var oldToolkit = oldAgent.getToolkit();
                for (var serverName : mcpToolRegistrar.getRegisteredServerNames()) {
                    try {
                        oldToolkit.removeMcpClient(serverName).block();
                    } catch (Exception ignore) {
                        // 旧 server 可能本就未注册（fail-soft），尽力而为
                    }
                }
            } catch (Exception e) {
                log.warn("[Reload] old agent MCP cleanup incomplete: {}", e.getMessage());
            }
        }

        // 6. 独立懒连接全部失效（下次访问按新配置重建）
        mcpResourceProxy.evictAllClients();

        // 7. 指纹以新配置为准刷新
        fingerprint.set(scanFingerprint());

        var detail = new ArrayList<Map<String, Object>>();
        for (var name : mcpToolRegistrar.getRegisteredServerNames()) {
            var wrapper = mcpToolRegistrar.getRegisteredWrapper(name);
            var entry = new LinkedHashMap<String, Object>();
            entry.put("server", name);
            entry.put("action", "registered");
            entry.put("ok", wrapper != null);
            entry.put("tool_count", wrapper != null ? safeToolCount(wrapper) : 0);
            detail.add(entry);
        }
        log.info("[Reload] agent rebuilt: {} ({} MCP servers)", newConfig.name(), detail.size());
        return new ReloadResult("agent", true, true, detail, null);
    }

    /**
     * 防抖包装：并发触发时直接返回"进行中"（供 SIGHUP/定时扫描等多通道调用）。
     */
    public ReloadResult reloadDebounced() throws IOException {
        if (!reloading.compareAndSet(false, true)) {
            return ReloadResult.noop("reload in progress");
        }
        try {
            return reload();
        } finally {
            reloading.set(false);
        }
    }

    /**
     * 只读状态：当前已注册的 MCP server（供 GET /admin/reload）。
     */
    public List<Map<String, Object>> registeredServerStatus() {
        var status = new ArrayList<Map<String, Object>>();
        for (var name : mcpToolRegistrar.getRegisteredServerNames()) {
            var wrapper = mcpToolRegistrar.getRegisteredWrapper(name);
            var entry = new LinkedHashMap<String, Object>();
            entry.put("server", name);
            entry.put("connected", wrapper != null);
            entry.put("tool_count", wrapper != null ? safeToolCount(wrapper) : 0);
            status.add(entry);
        }
        return status;
    }

    // ==================== 测试桥接 ====================

    /** 测试桥接：手动占住防抖标记（验证并发触发被拒）。 */
    boolean debounceAcquireForTest() {
        return reloading.compareAndSet(false, true);
    }

    /** 测试桥接：释放防抖标记。 */
    void debounceReleaseForTest() {
        reloading.set(false);
    }

    // ==================== 内部工具 ====================

    /** 当前 agent 的 Toolkit（A 路径原地 reload 落在当前 agent 上） */
    private io.agentscope.core.tool.Toolkit currentToolkitOrNull() {
        var agent = agentRuntimeService.getAgent();
        return agent != null ? agent.getToolkit() : null;
    }

    /** fail-soft 注册：失败返回 null 不中断其余 server（required 语义在 registerOne 内部抛出） */
    private String registerSafely(OafConfig.McpServerConfig mcp) {
        try {
            var toolkit = currentToolkitOrNull();
            if (toolkit == null) {
                return null;
            }
            return mcpToolRegistrar.registerOne(toolkit, mcp);
        } catch (Exception e) {
            log.warn("[Reload] server '{}' registration failed: {}", mcp.server(), e.getMessage());
            return null;
        }
    }

    private int safeToolCount(io.agentscope.core.tool.mcp.McpClientWrapper wrapper) {
        try {
            var tools = wrapper.listTools().block();
            return tools != null ? tools.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 指纹扫描：AGENTS.md + mcp 配置目录（configDir 下各 server 子目录，即
     * McpToolRegistrar.resolveMcpDir 的查找位置）逐文件 mtime+size，对齐
     * FileSystemSkillRepository 快照口径；删除的文件因条目消失自然改变指纹。
     * agentsMd 单独记录：决定分流 B（整包重建）或 A（仅 MCP 原地 reload）。
     */
    Fingerprint scanFingerprint() {
        var configDir = Path.of(props.configDir());
        var files = new LinkedHashMap<String, long[]>();
        var agentsMdPath = configDir.resolve("AGENTS.md");
        addFile(files, agentsMdPath);
        String agentsMdState = files.containsKey(agentsMdPath.toString())
            ? files.get(agentsMdPath.toString())[0] + ":" + files.get(agentsMdPath.toString())[1]
            : "missing";
        try (var dirs = Files.list(configDir)) {
            for (var serverDir : dirs.filter(Files::isDirectory).sorted().toList()) {
                // 跳过 skills（skills 动态加载有自己的每轮重扫，指纹不含它避免高频触发）
                if (serverDir.getFileName().toString().equals("skills")) {
                    continue;
                }
                try (var walk = Files.walk(serverDir)) {
                    walk.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(p -> addFile(files, p));
                }
            }
        } catch (IOException e) {
            log.warn("[Reload] fingerprint scan failed: {}", e.getMessage());
        }
        return new Fingerprint(agentsMdState, files);
    }

    private void addFile(Map<String, long[]> files, Path p) {
        try {
            if (Files.isRegularFile(p)) {
                files.put(p.toString(), new long[]{Files.getLastModifiedTime(p).toMillis(), Files.size(p)});
            }
        } catch (IOException e) {
            log.warn("[Reload] fingerprint stat failed on {}: {}", p, e.getMessage());
        }
    }

    // ==================== 结果结构 ====================

    /** reload 结果（结构化返回给 /admin/reload 调用方）。 */
    public record ReloadResult(
        String scope,           // mcp | agent | noop
        boolean fingerprintChanged,
        boolean agentRebuilt,
        List<Map<String, Object>> mcpServers,
        String error
    ) {
        static ReloadResult noop(String reason) {
            return new ReloadResult("noop", false, false, List.of(), reason);
        }
    }

    /** 配置文件指纹快照（AGENTS.md 状态 + 路径 → {mtime, size} 树）。 */
    record Fingerprint(String agentsMd, Map<String, long[]> files) {

        /** 指纹相同 = 全部条目的路径/mtime/size 均一致（long[] 按值比较）。 */
        boolean sameEntries(Fingerprint other) {
            if (files.size() != other.files.size()) {
                return false;
            }
            for (var e : files.entrySet()) {
                var otherVal = other.files.get(e.getKey());
                if (otherVal == null || otherVal.length != e.getValue().length) {
                    return false;
                }
                for (int i = 0; i < e.getValue().length; i++) {
                    if (e.getValue()[i] != otherVal[i]) {
                        return false;
                    }
                }
            }
            return true;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Fingerprint other)) {
                return false;
            }
            return agentsMd.equals(other.agentsMd) && sameEntries(other);
        }

        @Override
        public int hashCode() {
            // files 含可变数组，hashCode 仅基于 agentsMd（equals 保证同 agentsMd 再逐条比对）
            return agentsMd.hashCode();
        }
    }
}
