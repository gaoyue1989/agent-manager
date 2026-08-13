package io.agentmanager.framework.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 沙箱配置：SANDBOX_* / OPENSANDBOX_* 环境变量绑定。
 * 未启用（enabled=false）时走默认 RemoteFilesystemSpec 模式。
 * entrypoint: SANDBOX_ENTRYPOINT 逗号分隔（如 "python,main.py"），
 * 默认 /opt/code-interpreter/code-interpreter.sh（镜像默认启动脚本）。
 */
@ConfigurationProperties(prefix = "agent.sandbox")
public record SandboxConfig(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("opensandbox/code-interpreter:v1.1.0") String image,
    @DefaultValue("60") int timeoutMinutes,
    @DefaultValue("1024") int memoryMb,
    @DefaultValue("1") int cpuCount,
    @DefaultValue("/opt/code-interpreter/code-interpreter.sh") List<String> entrypoint,
    OpenSandboxConfig opensandbox
) {
    public record OpenSandboxConfig(
        String serverUrl,
        String apiKey
    ) {}
}
