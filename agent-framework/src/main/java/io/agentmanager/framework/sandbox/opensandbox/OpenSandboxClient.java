package io.agentmanager.framework.sandbox.opensandbox;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.service.WorkspaceReader;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * OpenSandbox 沙箱客户端：实现 AgentScope SandboxClient 契约。
 * create() 创建沙箱并注入 KV 运行时文件；resume() 按 sandboxId 恢复。
 */
public class OpenSandboxClient implements SandboxClient<OpenSandboxClientOptions> {

    private final OpenSandboxClientOptions options;
    private final WorkspaceReader workspaceReader;
    private final WorkspaceSyncService workspaceSyncService;
    private final OpenSandboxFilesystemSpec filesystemSpec;
    private final com.alibaba.opensandbox.sandbox.config.ConnectionConfig connectionConfig;
    private final ObjectMapper objectMapper;

    public OpenSandboxClient(OpenSandboxClientOptions options) {
        this(options, null, null, null);
    }

    public OpenSandboxClient(OpenSandboxClientOptions options, WorkspaceReader workspaceReader) {
        this(options, workspaceReader, null, null);
    }

    public OpenSandboxClient(OpenSandboxClientOptions options, WorkspaceReader workspaceReader,
                             WorkspaceSyncService workspaceSyncService) {
        this(options, workspaceReader, workspaceSyncService, null);
    }

    public OpenSandboxClient(OpenSandboxClientOptions options, WorkspaceReader workspaceReader,
                             WorkspaceSyncService workspaceSyncService,
                             OpenSandboxFilesystemSpec filesystemSpec) {
        this.options = options;
        this.workspaceReader = workspaceReader;
        this.workspaceSyncService = workspaceSyncService;
        this.filesystemSpec = filesystemSpec;
        this.objectMapper = new ObjectMapper();
        this.connectionConfig = com.alibaba.opensandbox.sandbox.config.ConnectionConfig.builder()
            .domain(options.getServerUrl())
            .apiKey(options.getApiKey())
            .protocol("http")
            .build();
    }

    @Override
    public Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec,
                          OpenSandboxClientOptions options) {
        // 1. 通过 OpenSandbox SDK 创建沙箱
        var builder = com.alibaba.opensandbox.sandbox.Sandbox.builder()
            .connectionConfig(connectionConfig)
            .image(options.getImage())
            .timeout(options.getTimeout())
            .resource(options.getResource())
            .env(options.getEnvironment());
        // entrypoint 仅在配置非空时设置，避免覆盖镜像默认启动命令
        if (options.getEntrypoint() != null && !options.getEntrypoint().isEmpty()) {
            builder.entrypoint(options.getEntrypoint());
        }
        var osbSandbox = builder.build();

        // 2. 构建状态
        var info = osbSandbox.getInfo();
        OpenSandboxState state = new OpenSandboxState();
        state.setSandboxId(info.getId());
        try {
            state.setSandboxEndpoint(osbSandbox.getEndpoint(44772).getEndpoint());
        } catch (Exception e) {
            // execd 端点获取失败不阻塞：SDK 命令/文件操作自行解析端点
            state.setSandboxEndpoint(null);
        }
        state.setWorkspaceSpec(workspaceSpec);
        state.setImage(options.getImage());
        state.setCreatedAt(System.currentTimeMillis());

        // 3. 包装为 AgentScope Sandbox 并启动
        //    KV 运行时文件（MEMORY.md/memory/）由 OpenSandbox 首次 exec 时延迟注入
        //    （create() 无 RuntimeContext 参数拿不到 userId，见 OpenSandbox 注释）
        //    userId 由 SandboxUserKeyMiddleware 注入（filesystemSpec.pendingUserKey）
        OpenSandbox sandbox = new OpenSandbox(state, osbSandbox, options, workspaceReader, workspaceSyncService, filesystemSpec);
        bindUserKey(sandbox);
        try {
            sandbox.start();
        } catch (Exception e) {
            throw new SandboxException(SandboxErrorCode.WORKSPACE_START_ERROR,
                "create", "Failed to start OpenSandbox: " + e.getMessage(), e);
        }

        return sandbox;
    }

    /** 从请求级 ThreadLocal 读取 userId 绑定到沙箱（middleware 注入） */
    private void bindUserKey(OpenSandbox sandbox) {
        if (filesystemSpec != null) {
            var key = filesystemSpec.takePendingUserKey();
            if (key != null && !key.isBlank()) {
                sandbox.setUserKey(key);
            }
        }
    }

    @Override
    public Sandbox resume(SandboxState state) {
        OpenSandboxState osbState = (OpenSandboxState) state;

        // 通过 OpenSandbox SDK 连接已有沙箱；沙箱已销毁（404）时抛异常 →
        // SandboxManager.acquire() 捕获后降级 create（框架行为）。
        // 注意：用 connector().connect() 而非 resumer().resume()——
        // resume 端点仅对 Paused 沙箱有效，Running 沙箱会 409 Conflict。
        var osbSandbox = com.alibaba.opensandbox.sandbox.Sandbox.connector()
            .sandboxId(osbState.getSandboxId())
            .connectionConfig(connectionConfig)
            .skipHealthCheck(true)
            .connect();

        var sandbox = new OpenSandbox(osbState, osbSandbox, options, workspaceReader, workspaceSyncService, filesystemSpec);
        bindUserKey(sandbox);
        return sandbox;
    }

    @Override
    public void delete(Sandbox sandbox) {
        OpenSandbox osb = (OpenSandbox) sandbox;
        try {
            osb.getOsbSandbox().kill();
            osb.getOsbSandbox().close();
        } catch (Exception e) {
            throw new SandboxException(
                SandboxErrorCode.WORKSPACE_STOP_ERROR,
                "delete",
                "Failed to delete OpenSandbox: " + e.getMessage(),
                e
            );
        }
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new SandboxException(
                SandboxErrorCode.CONFIGURATION_ERROR,
                "serializeState",
                "Failed to serialize sandbox state",
                e
            );
        }
    }

    @Override
    public SandboxState deserializeState(String serialized) {
        try {
            return objectMapper.readValue(serialized, OpenSandboxState.class);
        } catch (Exception e) {
            throw new SandboxException(
                SandboxErrorCode.CONFIGURATION_ERROR,
                "deserializeState",
                "Failed to deserialize sandbox state",
                e
            );
        }
    }
}
