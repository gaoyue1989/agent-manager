package io.agentmanager.framework.sandbox.opensandbox;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import io.agentmanager.framework.service.WorkspaceReader;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;

/**
 * OpenSandbox 文件系统配置：用于 HarnessAgent.Builder.filesystem()。
 * 由 AgentScopeConfig 在 SANDBOX_ENABLED=true 时装配。
 */
public class OpenSandboxFilesystemSpec extends SandboxFilesystemSpec {

    private String serverUrl;
    private String apiKey;
    private String image = "opensandbox/code-interpreter:v1.1.0";
    private Duration timeout = Duration.ofMinutes(60);
    private Map<String, String> resource = Map.of("cpu", "1", "memory", "1024Mi");
    private Map<String, String> environment = new HashMap<>();
    private String workspaceRoot = "/workspace";
    private WorkspaceReader workspaceReader;
    private WorkspaceSyncService workspaceSyncService;
    private SandboxUserKeyMiddleware userKeyMiddleware;

    /** 请求级 userId 传递：SandboxUserKeyMiddleware.onAgent 设置，OpenSandboxClient.create/resume 读取 */
    private final ThreadLocal<String> pendingUserKey = new ThreadLocal<>();

    @Override
    protected SandboxClient<?> createClient() {
        return new OpenSandboxClient(clientOptions(), workspaceReader, workspaceSyncService, this);
    }

    /** userId 注入 middleware（AgentScopeConfig 注册到 HarnessAgent.Builder.middleware） */
    public SandboxUserKeyMiddleware getUserKeyMiddleware() {
        return userKeyMiddleware;
    }

    public OpenSandboxFilesystemSpec setUserKeyMiddleware(SandboxUserKeyMiddleware middleware) {
        this.userKeyMiddleware = middleware;
        return this;
    }

    /**
     * 设置待绑定的用户 key（由 SandboxUserKeyMiddleware 在 agent 调用链上调用，
     * 与 acquire 在同一订阅线程顺序执行，ThreadLocal 天然按请求隔离）。
     */
    public void setPendingUserKey(String userKey) {
        pendingUserKey.set(userKey);
    }

    /** 读取并清除待绑定用户 key（OpenSandboxClient.create/resume 时调用） */
    public String takePendingUserKey() {
        var key = pendingUserKey.get();
        pendingUserKey.remove();
        return key;
    }

    @Override
    protected OpenSandboxClientOptions clientOptions() {
        return new OpenSandboxClientOptions()
            .serverUrl(serverUrl)
            .apiKey(apiKey)
            .image(image)
            .timeout(timeout)
            .resource(resource)
            .environment(environment)
            .workspaceRoot(workspaceRoot);
    }

    @Override
    public OpenSandboxFilesystemSpec isolationScope(io.agentscope.harness.agent.IsolationScope scope) {
        super.isolationScope(scope);
        return this;
    }

    @Override
    protected SandboxSnapshotSpec snapshotSpec() {
        return getSnapshotSpecOverride() != null ? getSnapshotSpecOverride() : new NoopSnapshotSpec();
    }

    @Override
    protected WorkspaceSpec workspaceSpec() {
        var spec = new WorkspaceSpec();
        spec.setRoot(workspaceRoot);
        return spec;
    }

    // ---- fluent builder methods ----
    public OpenSandboxFilesystemSpec serverUrl(String serverUrl) { this.serverUrl = serverUrl; return this; }
    public OpenSandboxFilesystemSpec apiKey(String apiKey) { this.apiKey = apiKey; return this; }
    public OpenSandboxFilesystemSpec image(String image) { this.image = image; return this; }
    public OpenSandboxFilesystemSpec timeout(Duration timeout) { this.timeout = timeout; return this; }
    public OpenSandboxFilesystemSpec resource(Map<String, String> resource) { this.resource = resource; return this; }
    public OpenSandboxFilesystemSpec environment(Map<String, String> env) { this.environment = env; return this; }
    public OpenSandboxFilesystemSpec workspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; return this; }
    public OpenSandboxFilesystemSpec workspaceReader(WorkspaceReader workspaceReader) {
        this.workspaceReader = workspaceReader;
        return this;
    }

    public OpenSandboxFilesystemSpec workspaceSyncService(WorkspaceSyncService workspaceSyncService) {
        this.workspaceSyncService = workspaceSyncService;
        return this;
    }
}
