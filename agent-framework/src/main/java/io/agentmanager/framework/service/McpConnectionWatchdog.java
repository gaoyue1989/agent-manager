package io.agentmanager.framework.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.config.OafConfigHolder;
import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.tool.mcp.McpClientWrapper;

/**
 * MCP 长连接健康看门狗（PR #62 遗留 P0-1）：biz-mcp 重启后 member 侧 SSE/streamable
 * 长连接静默失效，后续工具调用抛 ConnectException 且模型重试无效，此前仅重启 member
 * 恢复（order-fulfillment demo 实证）。本服务周期性对每个已注册连接做 listTools 探测
 * （短超时），失联即按 OAF reload 的 swap-on-success 语义原地重建：build 新客户端 →
 * clearServer 摘旧 → evict 资源代理缓存 → registerBuiltClient 登记新连接。
 *
 * <p>探活走 {@code listTools()}（发现通道走静态凭据，与用户级 header 注入无关，
 * 单连接覆盖全部用户调用）；重建期间该 server 跳过后续探测（per-server 在途标记），
 * OAF reload 进行中整体让位（换连语义同源，避免双 swap 竞态）。全部动作 fail-soft：
 * 单 server 失败只告警，下一周期重试，不影响其他 server 与主流程。
 *
 * <p>调度参数在 {@link McpHealthTiming}（独立 bean——@Scheduled SpEL 不能自引用宿主）。
 */
@Service
public class McpConnectionWatchdog {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionWatchdog.class);

    private final McpToolRegistrar mcpToolRegistrar;
    private final McpResourceProxy mcpResourceProxy;
    private final AgentRuntimeService agentRuntimeService;
    private final OafConfigHolder oafConfigHolder;
    private final OafReloadService oafReloadService;
    private final McpHealthTiming timing;

    /** per-server 重建在途标记（防同 server 重入；fixedDelay 已保证周期间不重叠） */
    private final Map<String, AtomicBoolean> rebuilding = new ConcurrentHashMap<>();

    private final AtomicLong probeCount = new AtomicLong();
    private final AtomicLong rebuildCount = new AtomicLong();

    public McpConnectionWatchdog(
            McpToolRegistrar mcpToolRegistrar,
            McpResourceProxy mcpResourceProxy,
            AgentRuntimeService agentRuntimeService,
            OafConfigHolder oafConfigHolder,
            OafReloadService oafReloadService,
            McpHealthTiming timing) {
        this.mcpToolRegistrar = mcpToolRegistrar;
        this.mcpResourceProxy = mcpResourceProxy;
        this.agentRuntimeService = agentRuntimeService;
        this.oafConfigHolder = oafConfigHolder;
        this.oafReloadService = oafReloadService;
        this.timing = timing;
    }

    /** 周期探测入口（Spring 调度）。disabled 或 OAF reload 进行中整体让位。 */
    @Scheduled(fixedDelayString = "#{@mcpHealthTiming.probeFixedDelayMs}", initialDelayString = "60000")
    public void probeAll() {
        if (!timing.enabled() || oafReloadService.isReloadInProgress()) {
            return;
        }
        for (var server : mcpToolRegistrar.getRegisteredServerNames()) {
            probeOne(server);
        }
    }

    /** 单 server 探测 + 失联重建（测试可直接调用） */
    void probeOne(String server) {
        var wrapper = mcpToolRegistrar.getRegisteredWrapper(server);
        if (wrapper == null) {
            return;
        }
        var flag = rebuilding.computeIfAbsent(server, k -> new AtomicBoolean());
        if (!flag.compareAndSet(false, true)) {
            return; // 该 server 正在重建
        }
        try {
            probeCount.incrementAndGet();
            if (probe(wrapper)) {
                return;
            }
            log.warn("[MCP-watchdog] server '{}' unhealthy, rebuilding connection", server);
            rebuild(server);
            rebuildCount.incrementAndGet();
        } catch (Exception e) {
            log.warn("[MCP-watchdog] probe/rebuild failed for '{}': {}", server, e.getMessage());
        } finally {
            flag.set(false);
        }
    }

    /** 探活：listTools 短超时内返回即视为健康 */
    private boolean probe(McpClientWrapper wrapper) {
        try {
            var tools = wrapper.listTools()
                .block(java.time.Duration.ofSeconds(Math.max(1, timing.probeTimeoutSeconds())));
            return tools != null;
        } catch (Exception e) {
            log.info("[MCP-watchdog] probe failed ({}): {}", wrapper.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * 失联重建：与 {@code OafReloadService.reloadServerSwapOnSuccess} 同序——
     * 先 build（不触碰旧注册），成功才摘旧/清代理缓存/登记新连接；build 失败保留旧注册，
     * 注册失败关闭新连接防泄漏。
     */
    private void rebuild(String server) {
        var mcp = currentServerConfig(server);
        if (mcp == null) {
            log.warn("[MCP-watchdog] server '{}' has no active config, skip rebuild", server);
            return;
        }
        var agent = agentRuntimeService.getAgent();
        if (agent == null || agent.getToolkit() == null) {
            log.warn("[MCP-watchdog] no live agent/toolkit, skip rebuild for '{}'", server);
            return;
        }
        var toolkit = agent.getToolkit();

        var newWrapper = mcpToolRegistrar.buildClientForReload(mcp);
        if (newWrapper == null) {
            log.warn("[MCP-watchdog] server '{}' client build failed, keeping old registration", server);
            return;
        }
        mcpToolRegistrar.clearServer(toolkit, server);
        mcpResourceProxy.evictClient(server);
        try {
            mcpToolRegistrar.registerBuiltClient(toolkit, newWrapper, mcp);
            log.info("[MCP-watchdog] server '{}' connection rebuilt ({})", server, newWrapper);
        } catch (Exception e) {
            mcpToolRegistrar.closeWrapperQuietly(newWrapper);
            log.warn("[MCP-watchdog] server '{}' registration failed after rebuild: {}", server, e.getMessage());
        }
    }

    /** 从当前 OAF 配置找 server 声明（reload 换包后以新配置为准） */
    private OafConfig.McpServerConfig currentServerConfig(String server) {
        var oaf = oafConfigHolder.get();
        if (oaf == null || oaf.mcpServers() == null) {
            return null;
        }
        return oaf.mcpServers().stream()
            .filter(m -> server.equals(m.server()))
            .findFirst()
            .orElse(null);
    }

    /** [累计探测次数, 累计重建次数]（观测用） */
    public long[] stats() {
        return new long[]{probeCount.get(), rebuildCount.get()};
    }
}
