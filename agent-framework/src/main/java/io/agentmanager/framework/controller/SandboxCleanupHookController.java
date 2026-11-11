package io.agentmanager.framework.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.sandbox.opensandbox.SandboxLifecycleManager;

/**
 * 沙箱绑定清理手动触发（e2e L11 测试钩子，sandbox-pool-opensandbox-design §9.3：
 * 「短保留窗口 + 触发清理任务（测试钩子）」——@Scheduled cron 凌晨 3 点无法在 e2e 等待）。
 *
 * <p><b>条件注册</b>（issue #293）：仅 {@code SANDBOX_LIFECYCLE_E2E_HOOKS=true} 时装配
 * （默认 false 不注册，POST /debug/sandbox-cleanup 得 404）——本端点无独立鉴权
 * （/debug 面约定，见 AdminReloadController），生产路径不暴露写端点。
 * 语义与 SessionCleanupService.cleanupSandboxBindings 完全一致（retentionDays 取
 * query 参数，e2e 传短窗口）；lifecycle 未装配时 fail-soft 返回 enabled=false
 * （与 GET /debug/sandbox-bindings 同口径）。
 */
@RestController
@ConditionalOnProperty(prefix = "agent.sandbox.lifecycle", name = "e2e-hooks", havingValue = "true")
public class SandboxCleanupHookController {

    /** retentionDays 合法区间下限（0 不再视为「立即清空」合法值，e2e 与定时任务均用 7） */
    private static final int RETENTION_DAYS_MIN = 1;
    /** retentionDays 合法区间上限（一年封顶，防误传超大值把保留窗清空语义放大成全删） */
    private static final int RETENTION_DAYS_MAX = 365;

    /** lifecycle 惰性解析：lifecycle 未装配（SANDBOX_LIFECYCLE_ENABLED=false/沙箱未启用）时 getIfAvailable() 为 null */
    private final ObjectProvider<SandboxLifecycleManager> lifecycleProvider;

    public SandboxCleanupHookController(ObjectProvider<SandboxLifecycleManager> lifecycleProvider) {
        this.lifecycleProvider = lifecycleProvider;
    }

    /**
     * 清理过期绑定行 + 空计数锚行。
     * IllegalArgumentException（retentionDays 超界）经 GlobalExceptionHandler 转 400 bad_request；
     * 清理链路自身异常（DataAccessException 等）不在此包装，保持原语义上抛。
     */
    @PostMapping("/debug/sandbox-cleanup")
    public Map<String, Object> sandboxCleanup(
            @RequestParam(defaultValue = "7") int retentionDays) {
        // 参数校验先行（区间校验是本钩子新增的护栏，超界一律 400）
        if (retentionDays < RETENTION_DAYS_MIN || retentionDays > RETENTION_DAYS_MAX) {
            throw new IllegalArgumentException(
                "retentionDays 必须在 " + RETENTION_DAYS_MIN + "~" + RETENTION_DAYS_MAX
                    + " 区间内，实际值: " + retentionDays);
        }
        var m = new LinkedHashMap<String, Object>();
        var lifecycle = lifecycleProvider.getIfAvailable();
        if (lifecycle == null) {
            m.put("enabled", false);
            return m;
        }
        var cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int bindings = lifecycle.bindingStore().cleanupExpired(cutoff);
        int anchors = lifecycle.bindingStore().cleanupEmptyAnchors(cutoff);
        m.put("enabled", true);
        m.put("retention_days", retentionDays);
        m.put("bindings_deleted", bindings);
        m.put("anchors_deleted", anchors);
        return m;
    }
}
