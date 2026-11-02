package io.agentmanager.framework.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.sandbox.opensandbox.LocatedSandbox;
import io.agentmanager.framework.sandbox.opensandbox.SandboxEnvException;
import io.agentmanager.framework.sandbox.opensandbox.SandboxEnvFile;
import io.agentmanager.framework.sandbox.opensandbox.SandboxLocator;
import io.agentmanager.framework.sandbox.opensandbox.OpenSandboxFilesystemSpec;
import io.agentmanager.framework.service.SandboxRuntime;
import io.agentscope.harness.agent.IsolationScope;

/**
 * 沙箱环境变量设置接口（docs/sandbox-env-update-design.md）。
 *
 * <p>{@code PUT /sandbox/env}：按 {@code userId + sessionId} 定位当前沙箱容器，把 env
 * 写入 execd {@code EXECD_ENVS} 指向的文件（bootstrap 默认 {@code /opt/opensandbox/.env}）；
 * 后续命令由 execd 自动加载，{@code doExec} 零改动。沙箱不存在直接返回错误，不做兜底。
 *
 * <p>鉴权：与 {@code /admin/*} 同级（集群内网入口后），仅允许平台后端/可信调用方调用；
 * 值不落盘、不落日志、不出现在响应。
 */
@RestController
public class SandboxEnvController {

    private final SandboxRuntime sandboxRuntime;
    private final SandboxLocator locator;
    private final SandboxEnvFile envFile;
    private final ObjectProvider<OpenSandboxFilesystemSpec> specProvider;

    public SandboxEnvController(SandboxRuntime sandboxRuntime,
                                SandboxLocator locator,
                                SandboxEnvFile envFile,
                                ObjectProvider<OpenSandboxFilesystemSpec> specProvider) {
        this.sandboxRuntime = sandboxRuntime;
        this.locator = locator;
        this.envFile = envFile;
        this.specProvider = specProvider;
    }

    /** PUT /sandbox/env 请求体：userId/sessionId 必填，env 至少一个键 */
    public record UpdateRequest(String userId, String sessionId, Map<String, String> env) {
    }

    @PutMapping("/sandbox/env")
    public ResponseEntity<Map<String, Object>> update(@RequestBody UpdateRequest request) {
        validateIdentity(request);
        if (!sandboxRuntime.enabled()) {
            throw new SandboxEnvException("sandbox_disabled", 409, "sandbox is not enabled for this agent");
        }
        try (LocatedSandbox located = locator.locate(request.userId(), request.sessionId(), resolveScope())) {
            String envPath = envFile.discoverEnvPath(located.sandbox());
            SandboxEnvFile.MergeResult result = envFile.mergeAndWrite(located.sandbox(), envPath, request.env());
            return ResponseEntity.ok(response(request, located.sandboxId(), result));
        }
    }

    /** 统一映射结构化错误：{@code {"error": ..., "message": ...}} + 对应 HTTP 状态 */
    @ExceptionHandler(SandboxEnvException.class)
    public ResponseEntity<Map<String, Object>> onSandboxEnvError(SandboxEnvException e) {
        return ResponseEntity.status(e.getHttpStatus())
            .body(Map.of("error", e.getErrorCode(), "message", String.valueOf(e.getMessage())));
    }

    // ==================== 校验 ====================

    private static void validateIdentity(UpdateRequest request) {
        if (request == null || invalid(request.userId())) {
            throw new SandboxEnvException("invalid_user_id", 400, "userId is required (<=256 chars, no control chars)");
        }
        if (invalid(request.sessionId())) {
            throw new SandboxEnvException("invalid_session_id", 400, "sessionId is required (<=256 chars, no control chars)");
        }
        if (request.env() == null || request.env().isEmpty()) {
            throw new SandboxEnvException("invalid_env", 400, "env must contain at least one key");
        }
    }

    private static boolean invalid(String value) {
        if (value == null || value.isBlank() || value.length() > 256) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    /** 隔离范围来自配置（当前固定 USER；null 按 USER 处理，与 SandboxIsolationKey 默认语义一致） */
    private IsolationScope resolveScope() {
        OpenSandboxFilesystemSpec spec = specProvider.getIfAvailable();
        return spec == null ? null : spec.getIsolationScope();
    }

    // ==================== 响应 ====================

    private static Map<String, Object> response(UpdateRequest request, String sandboxId,
                                                SandboxEnvFile.MergeResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", request.userId());
        body.put("sessionId", request.sessionId());
        body.put("sandboxId", sandboxId);
        body.put("updatedKeys", new ArrayList<>(result.updatedKeys()));
        body.put("keys", new ArrayList<>(result.managed().keySet()));
        body.put("count", result.managed().size());
        body.put("applied", "next_exec");
        return body;
    }
}
