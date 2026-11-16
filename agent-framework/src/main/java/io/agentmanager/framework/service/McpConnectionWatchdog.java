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
 * MCP 长连接健康看门狗（PR #62 遗留 P0-1，issue #348 对齐 per-session 共享缓存架构）：
 * biz-mcp 重启后 SSE/streamable 长连接静默失效，此前重建目标错在单例 agent 的 toolkit
 * 注册，而会话实际经由 McpToolRegistrar 共享连接缓存取连接——重建后新会话依然命中死
 * 缓存（2026-11-16 发布助手断联故障实证）。现探测与恢复都对准共享缓存句柄：
 * 失联即对句柄原地换芯（{@code rebuildSharedWrapper}），所有 toolkit 注册的是同一句柄
 * 实例，换芯后存量会话/单例/新会话全部自愈，无需任何重注册。
 *
 * <p>探活走 {@code listTools()}（发现通道走静态凭据，与用户级 header 注入无关）；
 * 重建期间该 server 跳过后续探测（per-server 在途标记），OAF reload 进行中整体让位
 * （换连语义同源，避免双 swap 竞态）。缓存无句柄（典型：启动期注册失败被 P0 驱逐）
 * 走 compensate：复用 {@code registerOne} 补建——会话隔离模式下新会话经共享缓存自然
 * 取到；单例 agent 存在时顺带补登记（隔离关闭模式的会话入口）。
 * 全部动作 fail-soft：单 server 失败只告警，下一周期重试。
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

    /** per-server 探测/补偿在途标记（防同 server 重入；fixedDelay 已保证周期间不重叠） */
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
        var oaf = oafConfigHolder.get();
        if (oaf == null || oaf.mcpServers() == null) {
            return;
        }
        for (var m : oaf.mcpServers()) {
            probeOne(m);
        }
    }

    /**
     * 单 server 探测 + 失联换芯（测试可直接调用）。共享缓存无句柄走 compensate 补建，
     * 有句柄则探活、失联即原地换芯。
     */
    void probeOne(OafConfig.McpServerConfig m) {
        var wrapper = mcpToolRegistrar.sharedWrapper(m.server());
        if (wrapper == null) {
            compensate(m);
            return;
        }
        var flag = rebuilding.computeIfAbsent(m.server(), k -> new AtomicBoolean());
        if (!flag.compareAndSet(false, true)) {
            return; // 该 server 正在探测/换芯
        }
        try {
            probeCount.incrementAndGet();
            if (probe(wrapper)) {
                return;
            }
            log.warn("[MCP-watchdog] server '{}' unhealthy, rebuilding shared connection in place", m.server());
            if (mcpToolRegistrar.rebuildSharedWrapper(m.server(), m)) {
                rebuildCount.incrementAndGet();
                // 独立懒连接（资源代理自建 McpSyncClient）不随句柄换芯，联动失效重建
                mcpResourceProxy.evictClient(m.server());
            }
        } catch (Exception e) {
            log.warn("[MCP-watchdog] probe/rebuild failed for '{}': {}", m.server(), e.getMessage());
        } finally {
            flag.set(false);
        }
    }

    /**
     * 补偿「有 OAF 配置但共享缓存无句柄」的 server（启动期注册失败被 P0 驱逐后的现场）。
     * 复用 {@code registerOne} 完整语义（构建/缓存/注册/P0 驱逐）：会话隔离模式下
     * 句柄入缓存后新会话自然取用；单例 agent 存在时补登记其 toolkit（隔离关闭模式
     * 的会话入口）。失败只告警，下一周期重试。
     */
    private void compensate(OafConfig.McpServerConfig m) {
        var flag = rebuilding.computeIfAbsent(m.server(), k -> new AtomicBoolean());
        if (!flag.compareAndSet(false, true)) {
            return;
        }
        try {
            var agent = agentRuntimeService.getAgent();
            if (agent != null && agent.getToolkit() != null) {
                log.info("[MCP-watchdog] server '{}' configured but no shared connection, compensating via registerOne",
                    m.server());
                mcpToolRegistrar.registerOne(agent.getToolkit(), m);
            } else {
                log.info("[MCP-watchdog] server '{}' no shared connection and no live agent, caching client only",
                    m.server());
                mcpToolRegistrar.sharedClient(m);
            }
            rebuildCount.incrementAndGet();
        } catch (Exception e) {
            log.warn("[MCP-watchdog] compensate failed for '{}': {}", m.server(), e.getMessage());
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

    /** [累计探测次数, 累计重建次数]（观测用） */
    public long[] stats() {
        return new long[]{probeCount.get(), rebuildCount.get()};
    }
}
