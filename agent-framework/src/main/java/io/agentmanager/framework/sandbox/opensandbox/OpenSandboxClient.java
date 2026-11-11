package io.agentmanager.framework.sandbox.opensandbox;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <p>sandbox-pool-opensandbox-design §4.2/§4.9（PR-D）：
 * <ul>
 *   <li><b>create</b>：lifecycle 开启且身份捕获齐备时改走 {@link SandboxLifecycleManager#acquire}
 *       （① 复用/② 占额/③ create/⑤ LRU 全忙异常）；create 透传 {@code extensions.poolRef} /
 *       {@code metadata}（service+session_id，§5.3 console 过滤口径）+ {@code timeout=TTL}；
 *       poolRef 非空时抑制 resourceLimits（评审 d17：由 Pool 模板定义；image 受 SDK 客户端
 *       「image XOR snapshotId」强约束必须传，上游实测忽略），entrypoint/env 保留供
 *       taskTemplate 下发。</li>
 *   <li><b>resume</b>：connect 成功后执行「归属校验+占位」合一条件写（终审 d1/cold-R1-1，
 *       取代 v4 槽位墓碑——SessionSandboxStateStore 底层 MysqlAgentStateStore 自管连接
 *       无法并入锚行事务、slot 键为 harness 私有格式）；affected=0 → close 句柄 + 抛降级
 *       异常 → harness 降级 create → 路由本决策重新记账/决策（先例：PR #276 reconcileResume）。</li>
 * </ul>
 */
public class OpenSandboxClient implements SandboxClient<OpenSandboxClientOptions> {

    private static final Logger log = LoggerFactory.getLogger(OpenSandboxClient.class);

    private final OpenSandboxClientOptions options;
    private final WorkspaceReader workspaceReader;
    private final WorkspaceSyncService workspaceSyncService;
    private final OpenSandboxFilesystemSpec filesystemSpec;
    private final io.agentmanager.framework.service.FileAssetStore fileAssetStore;
    private final io.agentmanager.framework.service.storage.FileStorage fileStorage;
    private final com.alibaba.opensandbox.sandbox.config.ConnectionConfig connectionConfig;
    private final ObjectMapper objectMapper;

    /**
     * metadata.service 的本服务 k8s 名（§5.3 console 过滤口径 = 含 oaf- 前缀的 K8sName）：
     * 优先 env {@code AGENT_SERVICE_K8S_NAME}（平台显式注入）；回退由 HOST_NAME（Pod 名 =
     * {deployment}-{rs-hash}-{suffix}）剥末两段推导（评审 d4：框架无本服务 k8s 名保留键的
     * 现实约束下的框架侧取值）。两者皆空 → 不注入（宁可缺失不注错值，错误值会让 §5.3
     * 过滤静默错分）。进程内只算一次（Pod 名不变）。
     */
    private static volatile String cachedServiceName;
    private static final java.util.regex.Pattern POD_NAME_SUFFIX =
        java.util.regex.Pattern.compile("^(.+)-[a-z0-9]{5,10}-[a-z0-9]{5}$");

    static String resolveServiceName() {
        String cached = cachedServiceName;
        if (cached != null) {
            return cached;
        }
        String name = System.getenv("AGENT_SERVICE_K8S_NAME");
        if (name == null || name.isBlank()) {
            String pod = System.getenv("HOST_NAME");
            if (pod != null && pod.isBlank() == false) {
                var m = POD_NAME_SUFFIX.matcher(pod);
                name = m.matches() ? m.group(1) : null;
            }
        }
        name = name == null || name.isBlank() ? "" : name;
        cachedServiceName = name;
        return name;
    }

    public OpenSandboxClient(OpenSandboxClientOptions options) {
        this(options, null, null, null, null, null);
    }

    public OpenSandboxClient(OpenSandboxClientOptions options, WorkspaceReader workspaceReader) {
        this(options, workspaceReader, null, null, null, null);
    }

    public OpenSandboxClient(OpenSandboxClientOptions options, WorkspaceReader workspaceReader,
                             WorkspaceSyncService workspaceSyncService) {
        this(options, workspaceReader, workspaceSyncService, null, null, null);
    }

    public OpenSandboxClient(OpenSandboxClientOptions options, WorkspaceReader workspaceReader,
                             WorkspaceSyncService workspaceSyncService,
                             OpenSandboxFilesystemSpec filesystemSpec) {
        this(options, workspaceReader, workspaceSyncService, filesystemSpec, null, null);
    }

    public OpenSandboxClient(OpenSandboxClientOptions options, WorkspaceReader workspaceReader,
                             WorkspaceSyncService workspaceSyncService,
                             OpenSandboxFilesystemSpec filesystemSpec,
                             io.agentmanager.framework.service.FileAssetStore fileAssetStore,
                             io.agentmanager.framework.service.storage.FileStorage fileStorage) {
        this.options = options;
        this.workspaceReader = workspaceReader;
        this.workspaceSyncService = workspaceSyncService;
        this.filesystemSpec = filesystemSpec;
        this.fileAssetStore = fileAssetStore;
        this.fileStorage = fileStorage;
        this.objectMapper = new ObjectMapper();
        var connectionBuilder = com.alibaba.opensandbox.sandbox.config.ConnectionConfig.builder()
            .domain(options.getServerUrl())
            .apiKey(options.getApiKey())
            .protocol("http")
            // 经 Server 代理转发 execd/endpoint 请求（客户端无需直连沙箱容器）
            .useServerProxy(true);
        if (options.getRequestTimeout() != null) {
            // 建箱/管理面 HTTP 请求超时（oa-1110-repro-fixes-design §2.3b）：并发建箱高峰
            // 上游排队放大时调大单请求超时，与仓库侧 create 重试（SANDBOX_CREATE_MAX_ATTEMPTS）兜底
            connectionBuilder.requestTimeout(options.getRequestTimeout());
        }
        this.connectionConfig = connectionBuilder.build();
    }

    /** 请求级身份快照（守卫 tryEnter 同线程注入，lifecycle 记账键） */
    private record AcquireIdentity(String sessionId, String userKey) {}

    /** 从 spec ThreadLocal 读取身份（不消费——bindUserKey 仍负责 take+绑定实例） */
    private AcquireIdentity peekIdentity() {
        if (filesystemSpec == null) {
            return null;
        }
        String sid = filesystemSpec.takePendingSessionId();
        String userKey = filesystemSpec.peekPendingUserKey();
        if (sid == null || sid.isBlank()) {
            // 守卫未装配（不应发生：lifecycle 开启时装配处对 guard 关闭 fail-fast）→ 兜底
            return null;
        }
        return new AcquireIdentity(sid, userKey == null || userKey.isBlank() ? sid : userKey);
    }

    @Override
    public Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec,
                          OpenSandboxClientOptions options) {
        var lifecycle = filesystemSpec != null ? filesystemSpec.getLifecycleManager() : null;
        var identity = lifecycle != null ? peekIdentity() : null;
        if (lifecycle != null && identity != null) {
            // ===== lifecycle 决策路径（§4.2 ①~⑤）：① 复用 / ② 占额 + ③ create / ⑤ LRU =====
            try {
                return lifecycle.acquire(identity.sessionId(), identity.userKey(),
                    this::probeAlive,
                    () -> createNew(workspaceSpec, options, identity.sessionId()),
                    (handle, sandboxId) -> wrapReused(handle, sandboxId, workspaceSpec));
            } catch (UserQuotaExhaustedException e) {
                throw e;   // §4.6：SSE error 帧载荷（错误回调检测类型，§6.3）
            } catch (SandboxException e) {
                throw e;
            } catch (Exception e) {
                throw new SandboxException(SandboxErrorCode.WORKSPACE_START_ERROR,
                    "create", "Lifecycle acquire failed: " + e.getMessage(), e);
            }
        }
        // ===== 基线路径（lifecycle 关闭 / 身份缺失）：完全回滚后基线行为 =====
        try {
            return createNew(workspaceSpec, options, null).sandbox();
        } catch (SandboxException e) {
            throw e;
        } catch (Exception e) {
            throw new SandboxException(SandboxErrorCode.WORKSPACE_START_ERROR,
                "create", "Failed to create OpenSandbox: " + e.getMessage(), e);
        }
    }

    /** ③ 真实新建（SDK create + OpenSandbox 包装 + start），返回生命周期记账所需快照 */
    private SandboxLifecycleManager.CreatedSandbox createNew(WorkspaceSpec workspaceSpec,
                                                             OpenSandboxClientOptions options,
                                                             String capturedSessionId)
            throws Exception {
        // 1. 通过 OpenSandbox SDK 创建沙箱
        //    poolRef 非空：认领 warm Pod。image 必传——SDK Sandbox.Builder.build() 客户端强约束
        //    「image XOR snapshotId」（1.0.18 实证），无法在 poolRef 路径抑制；上游对 image+poolRef
        //    组合不校验（实测忽略，镜像以 Pool 模板为准）。resourceLimits 抑制（评审 d17 部分保留：
        //    由 Pool 模板定义，传了无效果且掩盖配置语义）；entrypoint/env 保留（taskTemplate 下发通路 §2.2）
        boolean pooled = lifecyclePoolRef() != null;
        var builder = com.alibaba.opensandbox.sandbox.Sandbox.builder()
            .connectionConfig(connectionConfig)
            .timeout(options.getTimeout())
            .image(options.getImage())
            .env(options.getEnvironment());
        if (pooled) {
            builder.extensions(Map.of("poolRef", lifecyclePoolRef()));
        } else {
            builder.resource(options.getResource());
        }
        // entrypoint 仅在配置非空时设置，避免覆盖镜像默认启动命令
        if (options.getEntrypoint() != null && !options.getEntrypoint().isEmpty()) {
            builder.entrypoint(options.getEntrypoint());
        }
        // metadata（§4.2 ③/§5.3）：service=本服务 k8s 名（console 只读页过滤口径），
        // session_id=记账会话键；service 缺失（本地部署无 k8s 名来源）时只注入 session_id
        String sid = capturedSessionId;
        if ((sid == null || sid.isBlank()) && filesystemSpec != null) {
            sid = filesystemSpec.takePendingSessionId();
        }
        var serviceName = resolveServiceName();
        if (serviceName != null && !serviceName.isBlank()) {
            builder.metadata(Map.of("service", serviceName,
                "session_id", sid == null ? "" : sid));
        } else if (sid != null && !sid.isBlank()) {
            builder.metadata(Map.of("session_id", sid));
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
        OpenSandbox sandbox = new OpenSandbox(state, osbSandbox, options, workspaceReader, workspaceSyncService,
            filesystemSpec, fileAssetStore, fileStorage);
        bindUserKey(sandbox);
        // 被动兜底（§6.2.4 双保险）：新容器启动时，该用户 injected 上传文件回滚 pending
        // → 本 turn 注入。防 delete 崩溃导致状态 stuck 在新容器漏注入。
        if (sandbox.getUserKey() != null && fileAssetStore != null) {
            fileAssetStore.resetInjectedToPending(sandbox.getUserKey());
        }
        // 上传文件注入：沙箱就绪后立即执行（沙箱文件操作走 execd 原生 API，不保证经 doExec）
        sandbox.injectPendingUploads();
        try {
            sandbox.start();
        } catch (Exception e) {
            throw new SandboxException(SandboxErrorCode.WORKSPACE_START_ERROR,
                "create", "Failed to start OpenSandbox: " + e.getMessage(), e);
        }
        // 服务端绝对 expiresAt（§4.2 要点：一律回填服务端响应值，不用本地 now+TTL 推算）
        String expiresAt = info.getExpiresAt() != null ? info.getExpiresAt().toString() : null;
        return new SandboxLifecycleManager.CreatedSandbox(info.getId(), expiresAt, sandbox, osbSandbox);
    }

    /** 配置的 poolRef（空串归一 null；lifecycle 关闭时恒 null） */
    private String lifecyclePoolRef() {
        var lifecycle = filesystemSpec != null ? filesystemSpec.getLifecycleManager() : null;
        if (lifecycle == null) {
            return null;
        }
        String ref = lifecycle.config().poolRef();
        return ref == null || ref.isBlank() ? null : ref;
    }

    /** ①/⑤c 复用包装：连接的 SDK 句柄 → OpenSandbox（resume 语义：不 start，同 client.resume） */
    private OpenSandbox wrapReused(com.alibaba.opensandbox.sandbox.Sandbox osbSandbox,
                                   String sandboxId, WorkspaceSpec workspaceSpec) throws Exception {
        OpenSandboxState state = new OpenSandboxState();
        state.setSandboxId(sandboxId);
        try {
            state.setSandboxEndpoint(osbSandbox.getEndpoint(44772).getEndpoint());
        } catch (Exception e) {
            state.setSandboxEndpoint(null);
        }
        state.setWorkspaceSpec(workspaceSpec);
        state.setImage(options.getImage());
        state.setCreatedAt(System.currentTimeMillis());
        OpenSandbox sandbox = new OpenSandbox(state, osbSandbox, options, workspaceReader,
            workspaceSyncService, filesystemSpec, fileAssetStore, fileStorage);
        bindUserKey(sandbox);
        sandbox.injectPendingUploads();
        return sandbox;
    }

    /**
     * ①/⑤a 探活：连接 + getInfo + execd 数据面 ping。404/410 → DEAD；execd ping 失败 → DEAD
     * （oa-1110 #1：控制面/数据面状态不一致——服务端存续但 execd 端点不可达时 getInfo 仍 200，
     * 复用即整轮 502 重试风暴；一条 {@code true} 命令兜底判定，DEAD 走懒删重建，
     * 复用前一次性成本 ≈100-300ms）；其余网络异常 → UNKNOWN（保守视为活）。
     * 可经 {@code SANDBOX_PROBE_EXECD_PING=false} 关闭（回退 getInfo-only 行为）。
     */
    private static final boolean PROBE_EXECD_PING = !"false".equals(
        System.getenv("SANDBOX_PROBE_EXECD_PING"));

    private SandboxLifecycleManager.ProbeResult probeAlive(String sandboxId) {
        try {
            var osbSandbox = com.alibaba.opensandbox.sandbox.Sandbox.connector()
                .sandboxId(sandboxId)
                .connectionConfig(connectionConfig)
                .skipHealthCheck(true)
                .connect();
            osbSandbox.getInfo();   // getInfo 200 = 控制面活（HTTP 404/410 在此抛 SandboxApiException）
            if (PROBE_EXECD_PING && !execdPingAlive(osbSandbox)) {
                return SandboxLifecycleManager.ProbeResult.dead();
            }
            return SandboxLifecycleManager.ProbeResult.alive(osbSandbox);
        } catch (com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException api) {
            int status = api.getStatusCode() != null ? api.getStatusCode() : 0;
            if (status == 404 || status == 410) {
                return SandboxLifecycleManager.ProbeResult.dead();
            }
            return SandboxLifecycleManager.ProbeResult.unknown();
        } catch (Exception e) {
            return SandboxLifecycleManager.ProbeResult.unknown();
        }
    }

    /**
     * execd 数据面 ping（复用探活的兜底判定，包私有供单测）：{@code true} 命令可执行 = 数据面活。
     * 任何失败（连接/超时/5xx）均视为数据面不可达 → 上层按 DEAD 懒删重建——失败的沙箱
     * 重建一个新的是确定正确的结果，保守复用则整轮 502。
     */
    boolean execdPingAlive(com.alibaba.opensandbox.sandbox.Sandbox connected) {
        try {
            connected.commands().run("true");
            return true;
        } catch (Exception e) {
            log.warn("[sandbox-open] probe execd ping failed, treat as dead (control plane alive): {}",
                e.getMessage());
            return false;
        }
    }

    /** 从请求级 ThreadLocal 读取 userId 绑定到沙箱（middleware 注入） */
    private void bindUserKey(OpenSandbox sandbox) {
        if (filesystemSpec != null) {
            var key = filesystemSpec.takePendingUserKey();
            if (key != null && !key.isBlank()) {
                sandbox.setUserKey(key);
            }
            var scope = filesystemSpec.takePendingSkillScope();
            if (scope != null && !scope.isBlank()) {
                sandbox.setSkillCacheScope(scope);
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
        var lifecycle = filesystemSpec != null ? filesystemSpec.getLifecycleManager() : null;
        final com.alibaba.opensandbox.sandbox.Sandbox osbSandbox;
        try {
            osbSandbox = com.alibaba.opensandbox.sandbox.Sandbox.connector()
                .sandboxId(osbState.getSandboxId())
                .connectionConfig(connectionConfig)
                .skipHealthCheck(true)
                .connect();
        } catch (com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException gone) {
            // 沙箱已被服务端 GC/删除（404/410）：P3 无法恢复，harness 将降级 create 重建。
            // 登记换代（V6⑧ live 实锤缺失路径：绑定行被 renew-404 sweep 懒剔除后，confirm/
            // chat 恢复不经归属拦截直接 connect 404 → 设计 §4.4「① miss → 重建 + 换代提示帧」
            // 的分支此前漏发）。ThreadLocal sid 未被 take（归属块在 connect 之后），降级
            // create 的 peekIdentity 仍能拿到 → 重建记账不受影响。
            int sc = gone.getStatusCode() != null ? gone.getStatusCode() : 0;
            if ((sc == 404 || sc == 410) && lifecycle != null) {
                String lostSid = filesystemSpec != null ? filesystemSpec.peekPendingSessionId() : null;
                if (lostSid != null && !lostSid.isBlank()) {
                    log.info("[sandbox-open] resume hit destroyed sandbox, register rebuild notice (sid={}, sandbox={})",
                        lostSid, osbState.getSandboxId());
                    lifecycle.markSandboxRebuilt(lostSid);
                }
            }
            throw gone;
        }

        // ===== 归属校验+占位（终审 d1/cold-R1-1，§4.2 两道防线第 2 条）=====
        // connect 仅建立句柄（skipHealthCheck(true) 下无探活也无副作用）、句柄返回供 exec
        // 之前执行「校验+占位」合一条件写——acquire 即占位，封「P3 通过后、守护线程首拍
        // 续期前」的 grace 空窗（A2A 闲置重入的互踩窗口）。affected=0 → 无行/已改绑他
        // 会话/slot↔binding 漂移 → close 句柄 + 抛降级异常 → harness 降级 create →
        // OpenSandboxClient.create 路由本决策重新记账/决策。
        if (lifecycle != null) {
            // 捕获的记账会话键在此一次消费：先校验+占位，通过后 call 引用计数 +1
            String sid = filesystemSpec != null ? filesystemSpec.takePendingSessionId() : null;
            if (sid != null && !sid.isBlank()) {
                try {
                    boolean owned = lifecycle.bindingStore().occupyWrite(osbState.getSandboxId(), List.of(sid));
                    if (!owned) {
                        // 归属失效：本会话绑定被 LRU 改绑/懒剔除/漂移 → close 句柄 + 降级重走决策
                        //（先例：被回滚 PR #276 reconcileResume「resume 后校验归属 → close + 降级」）
                        log.info("[sandbox-open] resume ownership check failed, close + degrade (sid={}, sandbox={})",
                            sid, osbState.getSandboxId());
                        lifecycle.markSandboxRebuilt(sid);   // 换代提示帧登记（§4.4 T4：归属被抢或沙箱销毁均为换代）
                        // 归还身份 ThreadLocal：harness 捕获本异常后降级 create 重走决策（同线程），
                        // 此刻身份已被上方 take 消费——不还回去，create 的生命周期记账拿不到 sid，
                        // 重建沙箱将脱簿记（2026-11-08 lifecycle 组本地实跑实锤：L18b 重建无绑定行）
                        filesystemSpec.setPendingSessionId(sid);
                        try {
                            osbSandbox.close();   // 仅释放 HTTP 客户端，未执行任何命令，无实害
                        } catch (Exception ignored) {
                            // close 失败无害（句柄无服务端副作用）
                        }
                        throw new SandboxException(SandboxErrorCode.WORKSPACE_START_ERROR, "resume",
                            "sandbox ownership lost (sid=" + sid + ", sandbox=" + osbState.getSandboxId() + ")", null);
                    }
                } catch (SandboxBindingStore.DataAccessException e) {
                    // 校验写异常 fail-open：放行 + WARN（互踩窗口仅存在于存储故障期）；
                    // 身份归还（与上方失效路径同理：后续可能的降级 create 仍可记账）
                    filesystemSpec.setPendingSessionId(sid);
                    lifecycle.recordOwnershipFailOpen(osbState.getSandboxId(), sid, e);
                }
                lifecycle.onResumeAcquired(osbState.getSandboxId(), sid, osbSandbox);
            } else {
                // 守卫未装配的防御分支：无身份无从记账（lifecycle 开启时装配处对 guard 关闭 fail-fast）
                log.debug("[sandbox-open] resume lifecycle skip (no captured session id, sandbox={})",
                    osbState.getSandboxId());
            }
        }

        var sandbox = new OpenSandbox(osbState, osbSandbox, options, workspaceReader, workspaceSyncService,
            filesystemSpec, fileAssetStore, fileStorage);
        bindUserKey(sandbox);
        // 上传文件注入：恢复会话后立即执行（同 create）
        sandbox.injectPendingUploads();
        return sandbox;
    }


    @Override
    public void delete(Sandbox sandbox) {
        OpenSandbox osb = (OpenSandbox) sandbox;
        // 主动回滚（§6.2.4 双保险 ①）：容器销毁前将该用户 injected 上传文件回滚 pending，
        // 新容器首 exec 重新注入（防容器 GC 后文件丢失）。
        // userKey 兜底：delete 可能在 acquire/middleware 时序外调用，
        // 实例 userKey 可能为 null（acquire 先于 middleware）→ 从 spec ThreadLocal 补充。
        String key = osb.getUserKey();
        if ((key == null || key.isBlank()) && filesystemSpec != null) {
            key = filesystemSpec.peekPendingUserKey();
        }
        if (key != null && !key.isBlank() && fileAssetStore != null) {
            fileAssetStore.resetInjectedToPending(key, key);
        }
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
