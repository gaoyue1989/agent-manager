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
    OpenSandboxConfig opensandbox
) {
    public record OpenSandboxConfig(
        String serverUrl,
        String apiKey
    ) {}
}
