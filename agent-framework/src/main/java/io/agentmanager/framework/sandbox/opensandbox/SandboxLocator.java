package io.agentmanager.framework.sandbox.opensandbox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;

/**
 * 按用户/会话定位沙箱容器（docs/sandbox-env-update-design.md §5）。
 *
 * <p>定位链路与 harness 完全同源：
 * <ul>
 *   <li>槽位键用 {@link SandboxIsolationKey#resolve} 按<b>配置的隔离范围</b>生成
 *       （当前 {@code IsolationScope.USER}），不手拼 {@code sandbox/user/...} 字符串；</li>
 *   <li>状态读取用 {@link SessionSandboxStateStore}（同一 AgentStateStore + 同一 agentId，
 *       即 {@code OafConfig.name()}，与 {@code HarnessAgentFactory} 一致）；</li>
 *   <li>连接用 SDK {@code Sandbox.connector()}，经 Server 代理。</li>
 * </ul>
 *
 * <p>USER 范围下的两个候选是固定链路映射：Channel 的 {@code ctx.userId} 就是前端 sid
 * （候选 {@code USER(sessionId)}），A2A 的 {@code ctx.userId} 就是真实用户
 * （候选 {@code USER(userId)}）；按顺序尝试，全部失败 → 404 {@code sandbox_not_found}。
 */
@Component
public class SandboxLocator {

    private static final Logger log = LoggerFactory.getLogger(SandboxLocator.class);

    private final SessionSandboxStateStore stateStore;
    private final String agentId;
    private final SandboxConfig sandboxConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 沙箱生命周期簿记（§4.9，lifecycle 开启时装配；可空=旁路）：locate 直连后的
     * 「归属校验+占位」合一条件写入口（终审 d1/cold-R1-1，与 OpenSandboxClient.resume 同规则）。
     */
    private final org.springframework.beans.factory.ObjectProvider<SandboxBindingStore> bindingStoreProvider;
    private final org.springframework.beans.factory.ObjectProvider<SandboxLifecycleManager> lifecycleProvider;

    /**
     * SDK 连接配置惰性构建（volatile + 单次重检）：apiKey 为空时 SDK {@code ConnectionConfig.Builder}
     * 直接拒绝——若在构造器急切构建，沙箱未启用（无需 apiKey）的部署（如工具插件冒烟环境）
     * 会因 Bean 创建失败而整服务起不来。控制器在 {@code sandboxRuntime.enabled()} 为假时先返回 409，
     * 永不触发本构建；真正调用 {@link #locate} 时 apiKey 必然已配置。
     */
    private volatile ConnectionConfig connectionConfig;

    public SandboxLocator(SessionSandboxStateStore stateStore, OafConfig oafConfig, SandboxConfig sandboxConfig,
                          org.springframework.beans.factory.ObjectProvider<SandboxBindingStore> bindingStoreProvider,
                          org.springframework.beans.factory.ObjectProvider<SandboxLifecycleManager> lifecycleProvider) {
        this.stateStore = stateStore;
        this.agentId = oafConfig.name();
        this.sandboxConfig = sandboxConfig;
        this.bindingStoreProvider = bindingStoreProvider;
        this.lifecycleProvider = lifecycleProvider;
    }

    private ConnectionConfig connectionConfig() {
        ConnectionConfig config = this.connectionConfig;
        if (config == null) {
            config = ConnectionConfig.builder()
                .domain(sandboxConfig.opensandbox().serverUrl())
                .apiKey(sandboxConfig.opensandbox().apiKey())
                .protocol("http")
                // 经 Server 代理转发 execd 请求（与 OpenSandboxClient 同模式）
                .useServerProxy(true)
                .build();
            this.connectionConfig = config;
        }
        return config;
    }

    /**
     * 定位并连接沙箱；全部候选失败 → 404 {@code sandbox_not_found}（不做 pending/兜底）。
     * 调用方负责 {@link LocatedSandbox#close()}（仅释放 HTTP 客户端，不 kill 沙箱）。
     */
    public LocatedSandbox locate(String userId, String sessionId, IsolationScope scope) {
        for (SandboxIsolationKey key : candidates(userId, sessionId, scope)) {
            String stateJson;
            try {
                stateJson = stateStore.load(key).orElse(null);
            } catch (IOException e) {
                throw new SandboxEnvException("state_unavailable", 500,
                    "failed to load sandbox state for " + key + ": " + e.getMessage());
            }
            if (stateJson == null) {
                continue;
            }
            String sandboxId = extractSandboxId(stateJson);
            if (sandboxId == null || sandboxId.isBlank()) {
                continue;
            }
            try {
                Sandbox sandbox = Sandbox.connector()
                    .sandboxId(sandboxId)
                    .connectionConfig(connectionConfig())
                    .skipHealthCheck(true)
                    .connect();
                // 归属校验+占位（§4.2 两道防线第 2 条，终审 d1/cold-R1-1）：env-update 链路与
                // P3 resume 同规则——connect 仅建句柄，句柄交付前条件写占位；affected=0（无行/
                // 已改绑/漂移）→ close + 视同候选失败（尝试下一候选 / 最终 404）
                if (!occupyOwnership(sandboxId, userId, sessionId)) {
                    log.info("[sandbox-locator] ownership check failed, skip candidate (user={}, session={}, sandbox={})",
                        userId, sessionId, sandboxId);
                    try {
                        sandbox.close();
                    } catch (Exception ignored) {
                        // 仅释放 HTTP 客户端
                    }
                    continue;
                }
                return new LocatedSandbox(sandboxId, sandbox);
            } catch (Exception e) {
                // 当前候选不可用（TTL 已销毁/被回收）→ 尝试下一候选
            }
        }
        throw new SandboxEnvException("sandbox_not_found", 404,
            "no live sandbox for this user/session");
    }

    /**
     * locate 直连后的「归属校验+占位」：候选会话键 = 本方法两个候选原值（Channel 的
     * USER(sessionId) 与 A2A 的 USER(userId)，binding.session_id 与守卫捕获点同源）。
     * lifecycle 未装配（旁路）→ 直接放行（基线行为）；校验写异常 → fail-open 放行 + WARN
     * （互踩窗口仅存在于存储故障期，不因校验失败打断 env-update 链路）。
     */
    private boolean occupyOwnership(String sandboxId, String userId, String sessionId) {
        var lifecycle = lifecycleProvider != null ? lifecycleProvider.getIfAvailable() : null;
        var store = bindingStoreProvider != null ? bindingStoreProvider.getIfAvailable() : null;
        if (lifecycle == null || store == null) {
            return true;
        }
        List<String> candidates = new ArrayList<>();
        if (sessionId != null && !sessionId.isBlank()) {
            candidates.add(sessionId);
        }
        if (userId != null && !userId.isBlank() && !candidates.contains(userId)) {
            candidates.add(userId);
        }
        if (candidates.isEmpty()) {
            return true;
        }
        try {
            return store.occupyWrite(sandboxId, candidates);
        } catch (SandboxBindingStore.DataAccessException e) {
            lifecycle.recordOwnershipFailOpen(sandboxId, candidates.get(0), e);
            return true;
        }
    }

    /** 按配置的隔离范围生成固定候选键（docs/sandbox-env-update-design.md §5.2） */
    private List<SandboxIsolationKey> candidates(String userId, String sessionId, IsolationScope scope) {
        IsolationScope effective = scope == null ? IsolationScope.USER : scope;
        switch (effective) {
            case AGENT, GLOBAL -> throw new SandboxEnvException("unsupported_isolation_scope", 409,
                "isolation scope " + effective + " cannot address a per-user/session sandbox");
            default -> {
            }
        }
        List<SandboxIsolationKey> keys = new ArrayList<>();
        if (effective == IsolationScope.SESSION) {
            keys.add(resolve(scope, null, sessionId));
            return keys;
        }
        // USER（当前固定配置）：Channel 的 ctx.userId = 前端 sid → USER(sessionId) 优先；
        // A2A/direct 的 ctx.userId = 真实用户/vendorKey → USER(userId)
        keys.add(resolve(IsolationScope.USER, sessionId, null));
        if (!sessionId.equals(userId)) {
            keys.add(resolve(IsolationScope.USER, userId, null));
        }
        return keys;
    }

    /** 用合成 RuntimeContext 走 harness 同一解析算法，保证槽位键格式一致 */
    private SandboxIsolationKey resolve(IsolationScope scope, String userId, String sessionId) {
        RuntimeContext.Builder builder = RuntimeContext.builder();
        if (userId != null) {
            builder.userId(userId);
        }
        if (sessionId != null) {
            builder.sessionId(sessionId);
        }
        return SandboxIsolationKey.resolve(scope, builder.build(), agentId)
            .orElseThrow(() -> new IllegalStateException("empty sandbox isolation key (scope=" + scope + ")"));
    }

    /** 沙箱状态 JSON 顶层含 {@code sandboxId}（OpenSandboxState 序列化形态），无需按子类型反序列化 */
    private String extractSandboxId(String stateJson) {
        try {
            var root = objectMapper.readTree(stateJson);
            var id = root.path("sandboxId");
            return id.isMissingNode() || id.isNull() ? null : id.asText(null);
        } catch (SandboxEnvException e) {
            throw e;
        } catch (Exception e) {
            throw new SandboxEnvException("state_unavailable", 500,
                "failed to parse sandbox state: " + e.getMessage());
        }
    }
}
