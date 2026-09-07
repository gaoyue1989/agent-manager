package io.agentmanager.framework.sandbox.opensandbox;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
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
    private List<String> entrypoint = List.of("/opt/code-interpreter/code-interpreter.sh");
    private Map<String, String> resource = Map.of("cpu", "1", "memory", "1024Mi");
    private Map<String, String> environment = new HashMap<>();
    private String workspaceRoot = "/workspace";
    private WorkspaceReader workspaceReader;
    private WorkspaceSyncService workspaceSyncService;
    private SandboxUserKeyMiddleware userKeyMiddleware;
    private io.agentmanager.framework.service.FileAssetStore fileAssetStore;
    private io.agentmanager.framework.service.storage.FileStorage fileStorage;

    /** 请求级 userId 传递：SandboxUserKeyMiddleware.onAgent 设置，OpenSandboxClient.create/resume 读取 */
    private final ThreadLocal<String> pendingUserKey = new ThreadLocal<>();

    /** 最近创建/恢复的沙箱实例（middleware.onAgent 注入兜底，见 SandboxUserKeyMiddleware） */
    private final java.util.concurrent.atomic.AtomicReference<OpenSandbox> latestSandbox =
        new java.util.concurrent.atomic.AtomicReference<>();

    /** userKey → 已注入沙箱 id（reset 仅在新沙箱代执行，防多实例循环 reset/inject） */
    private final java.util.concurrent.ConcurrentHashMap<String, String> userInjectedSandbox =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** 该用户当前是否已在新沙箱代 reset+inject 过 */
    public boolean isInjectedOnSandbox(String userKey, String sandboxId) {
        return sandboxId != null && sandboxId.equals(userInjectedSandbox.get(userKey));
    }

    /** 记录该用户已注入的沙箱 id */
    public void markInjectedOnSandbox(String userKey, String sandboxId) {
        if (userKey != null && sandboxId != null) {
            userInjectedSandbox.put(userKey, sandboxId);
        }
    }

    /** 注册沙箱实例（OpenSandboxClient.create/resume 调用） */
    public void registerSandbox(OpenSandbox sandbox) {
        latestSandbox.set(sandbox);
    }

    /** 最近沙箱实例（middleware 注入用；可能为 null） */
    public OpenSandbox getLatestSandbox() {
        return latestSandbox.get();
    }

    /** 上传文件元数据存储（middleware 注入回滚状态用） */
    public io.agentmanager.framework.service.FileAssetStore getFileAssetStore() {
        return fileAssetStore;
    }

    @Override
    protected SandboxClient<?> createClient() {
        // 包装 TracingSandboxClient：沙箱 create/resume/delete 操作创建 OTel span
        // （sandbox 操作在 middleware 链外执行，OtelTracingMiddleware 无法覆盖）。
        // OTEL_TRACES_EXPORTER=none 时 GlobalOpenTelemetry 返回 no-op tracer，零开销。
        return new TracingSandboxClient(
            new OpenSandboxClient(clientOptions(), workspaceReader, workspaceSyncService, this,
                fileAssetStore, fileStorage));
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

    /** 读取但不清除待绑定用户 key（注入兜底：doExec 时 middleware 已设置） */
    public String peekPendingUserKey() {
        var key = pendingUserKey.get();
        return (key == null || key.isBlank()) ? null : key;
    }

    @Override
    protected OpenSandboxClientOptions clientOptions() {
        return new OpenSandboxClientOptions()
            .serverUrl(serverUrl)
            .apiKey(apiKey)
            .image(image)
            .timeout(timeout)
            .entrypoint(entrypoint)
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
    public OpenSandboxFilesystemSpec entrypoint(List<String> entrypoint) { this.entrypoint = entrypoint; return this; }
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

    /** 上传文件元数据存储（沙箱 pending 注入状态机；可空=不启用上传注入） */
    public OpenSandboxFilesystemSpec fileAssetStore(io.agentmanager.framework.service.FileAssetStore fileAssetStore) {
        this.fileAssetStore = fileAssetStore;
        return this;
    }

    /** 文件存储后端（沙箱注入读字节；可空=不启用上传注入） */
    public OpenSandboxFilesystemSpec fileStorage(io.agentmanager.framework.service.storage.FileStorage fileStorage) {
        this.fileStorage = fileStorage;
        return this;
    }
}
