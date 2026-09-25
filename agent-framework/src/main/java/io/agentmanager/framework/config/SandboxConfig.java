package io.agentmanager.framework.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 沙箱配置：SANDBOX_* / OPENSANDBOX_* 环境变量绑定。
 * 未启用（enabled=false）时走默认 RemoteFilesystemSpec 模式。
 * entrypoint: SANDBOX_ENTRYPOINT 逗号分隔（如 "python,main.py"），
 * 默认 /opt/code-interpreter/code-interpreter.sh（镜像默认启动脚本）。
 * execdGraceShutdown: 注入沙箱容器 EXECD_API_GRACE_SHUTDOWN 环境变量，
 * 控制 execd 每条命令 SSE 结束后的尾窗保持时间（默认 1s 过慢，配 100ms 显著提速）。
 * projectionEnabled: 工作区投影开关（issue #27 缓解项，默认 true；关闭后 harness
 * 每次 sandbox start 不再 hydrate 投影目录，skills 依赖强的包不要关）。
 * guardEnabled/guardLeaseSeconds: 并发执行守卫（官方 §9 对 USER 范围的建议，
 * Redis SET NX 串行化同 userId 的沙箱获取；lease 为崩溃自愈 TTL）。
 * enabled 的运行时生效值另经 {@link io.agentmanager.framework.service.SandboxRuntime}
 * 叠加 OAF 包级声明裁决——本字段的绑定值仅是第三优先级（yml 默认）。
 */
@ConfigurationProperties(prefix = "agent.sandbox")
public record SandboxConfig(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("opensandbox/code-interpreter:v1.1.0") String image,
    @DefaultValue("60") int timeoutMinutes,
    @DefaultValue("1024") int memoryMb,
    @DefaultValue("1") int cpuCount,
    @DefaultValue("/opt/code-interpreter/code-interpreter.sh") List<String> entrypoint,
    @DefaultValue("100ms") Duration execdGraceShutdown,
    @DefaultValue("true") boolean projectionEnabled,
    @DefaultValue("true") boolean guardEnabled,
    @DefaultValue("900") int guardLeaseSeconds,
    OpenSandboxConfig opensandbox
) {
    public record OpenSandboxConfig(
        String serverUrl,
        String apiKey
    ) {}
}
