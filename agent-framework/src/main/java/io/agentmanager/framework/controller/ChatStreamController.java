package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.ChatUiChannelProvider;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentmanager.framework.service.McpToolRegistrar;
import io.agentmanager.framework.service.ModelCatalog;
import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionTitleService;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.service.SkillInjectionService;
import io.agentmanager.framework.service.ToolAuditStore;
import io.agentmanager.framework.service.TurnLeaseGuard;
import io.agentmanager.framework.service.TurnLeaseStore;
import io.agentmanager.framework.service.TurnToolSummaryTracker;
import io.agentmanager.framework.service.UiContextStore;
import io.agentmanager.framework.service.UploadWorkspaceInjector;
import io.agentmanager.framework.service.WorkspaceReader;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * 对话入口端点 &mdash; sessionId 在请求体中，可选。
 *
 * <p>本工程唯一对话入口：
 * <ul>
 *   <li>{@code POST /threads/chat} &mdash; sessionId 在请求体中，可选</li>
 *   <li>不传 sessionId 时自动生成 UUID，首个 SSE 事件为 {@code session_created}</li>
 *   <li>传了 sessionId 则续接已有会话</li>
 * </ul>
 *
 * <p>首次调用无需预知 sessionId：
 * <pre>{@code
 * // 第一次：不传 sessionId
 * POST /threads/chat  { "message": "你好" }
 * <- SSE: {"type":"session_created","session_id":"abc-123"}
 * <- SSE: {"type":"text_delta",...}
 * <- SSE: {"type":"done"}
 *
 * // 后续：传 sessionId 续接
 * POST /threads/chat  { "message": "继续", "sessionId": "abc-123" }
 * }</pre>
 *
 * <p>其余端点（subscribe / status / confirm / history）仍需 sessionId 在路径中
 * （见 {@link SessionStreamController}），因为它们是面向已知会话的操作。
 */
@RestController
@RequestMapping("/threads")
public class ChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);

    static Duration WAITING_FRAME_INTERVAL = Duration.ofSeconds(15);
    static Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(120);
    /** 排队意图有效期：覆盖整个租约等待窗口 + 余量（崩溃遗留行由过期过滤兜底） */
    static Duration TURN_INTENT_TTL = ACQUIRE_TIMEOUT.plusSeconds(60);
    /** 失败意图保留期：刷新后仍能看到失败原因；新 turn 登记会覆盖 */
    static Duration TURN_INTENT_FAILED_TTL = Duration.ofMinutes(10);

    /** 首事件延迟告警阈值（issue #87）：超过即 WARN（排队/静默类问题的直接日志证据） */
    static final long FIRST_EVENT_WARN_MS = 10_000;

    /**
     * 跨层排队 waiting 心跳调度器（issue #87 兜底）：租约等待期已有 waiting 帧循环，
     * 但租约**之后**的排队点（SDK turn 闸门、模型连接池等）期间客户端零反馈——用户
     * 静默、评测误报超时。此处每 WAITING_FRAME_INTERVAL 向 sink 直发一帧 waiting
     * （不进 EventBus、不落库），首个 SDK 事件或 turn 终态即停。
     * 独立单线程 daemon：绝不在 Reactor 回调线程上阻塞（2026-09-19 教训）。
     */
    private static final java.util.concurrent.ScheduledExecutorService WAIT_HEARTBEAT =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "chat-wait-heartbeat");
            t.setDaemon(true);
            return t;
        });

    /** 当前 Agent 对应的 Channel 工厂：reload 后必须从 AgentRuntimeService 取引用，不能复用旧 Agent 的 Channel */
    private final Supplier<ChatUiChannel> chatChannelSupplier;
    /** per-session channel：Spring 装配路径置 true——chat turn 按 sessionId 取会话级 agent（#2849 隔离） */
    private final boolean perSessionChannel;
    private final AgentRuntimeService runtimeService;
    private final TurnLeaseStore turnLeaseStore;
    private final ToolAuditStore toolAuditStore;
    private final UploadWorkspaceInjector workspaceInjector;
    private final SandboxConfig sandboxConfig;
    private final SessionEventBus eventBus;
    private final SessionEventStore eventStore;
    private final SessionUserStore sessionUserStore;
    private final WorkspaceReader workspaceReader;
    private final AgentManagerProperties props;
    private final SkillInjectionService skillInjectionService;
    private final io.agentmanager.framework.service.FileAssetStore fileAssetStore;
    private final McpToolRegistrar mcpToolRegistrar;
    private final ModelCatalog modelCatalog;
    private final SessionTitleService sessionTitleService;

    /**
     * 会话消息轨存储：turn 失败留痕（{@link #recordTurnError}）用。字段注入（required=false）
     * 而非构造参数——避免改动全部构造调用点（单测直接 new）；归档关闭时为 null，留痕跳过。
     */
    @Autowired(required = false)
    private io.agentmanager.framework.service.SessionMessageStore sessionMessageStore;

    /**
     * 排队 turn 意图存储（字段注入，同 sessionMessageStore：避免改动全部构造调用点）。
     * 排队期间把本轮 turn id 提前暴露给 /status，使「排队中刷新」仍能续传。
     */
    @Autowired(required = false)
    private io.agentmanager.framework.service.TurnIntentStore turnIntentStore;

    /**
     * 单轮停止（POST /threads/{sid}/stop）：字段注入（required=false）避免改动全部
     * 构造调用点（单测直接 new）。null = 停止能力不装配（与旧行为一致：只能断流）。
     */
    @Autowired(required = false)
    private io.agentmanager.framework.service.TurnStopService turnStopService;

    @Autowired(required = false)
    private io.agentmanager.framework.service.TurnStopWatcher turnStopWatcher;

    @Autowired(required = false)
    private io.agentmanager.framework.config.TurnStopSettings turnStopSettings;

    /**
     * 沙箱生命周期管理器（sandbox-pool-opensandbox-design §4.3 预检 + §4.4 turn 挂钩；
     * 字段注入 required=false 避免改动全部构造调用点，单测直接 new）。null = lifecycle
     * 旁路（SANDBOX_LIFECYCLE_ENABLED=false 或沙箱未启用），行为完全回滚后基线。
     */
    @Autowired(required = false)
    private io.agentmanager.framework.sandbox.opensandbox.SandboxLifecycleManager sandboxLifecycle;

    /** 孤儿 turn 看护（独立调度线程：绝不在 Reactor 回调线程上阻塞） */
    private final OrphanTurnWatchdog orphanTurnWatchdog = new OrphanTurnWatchdog();

    /** 关闭时释放看护线程池 */
    @jakarta.annotation.PreDestroy
    void shutdownWatchdog() {
        orphanTurnWatchdog.shutdown();
    }

    /** 产出文件工具（present_file/present_url）结果文本累积（toolCallId &rarr; 文本桶，64KB 上限防内存膨胀） */
    private final java.util.concurrent.ConcurrentHashMap<String, StringBuilder> presentFileBuffers =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** write_file 工具输入参数累积（toolCallId &rarr; JSON 片段桶），用于截获 path+content 后同步 KV */
    private final java.util.concurrent.ConcurrentHashMap<String, StringBuilder> writeFileInputBuffers =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * toolCallId &rarr; 真实工具名（turn 内有效）。
     *
     * <p>SDK 只在 {@link ToolCallStartEvent} 上携带真实工具名——后续的
     * {@code ToolCallDeltaEvent} 虽然也有 getToolCallName()，实测恒为占位符
     * {@code "__fragment__"}（参数分片帧，见 e2e-ci-plan §11.3 D3）。
     * 因此凡需按工具名分派的逻辑，必须先在此登记、后续查表，不能直接读 delta 的名字。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> toolCallNames =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static final int PRESENT_FILE_BUFFER_MAX = 64 * 1024;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 会话级 Channel：从 {@code AgentRuntimeService.getAgent(sessionId)}（per-session 隔离
     * 开启时为该会话专属实例，关闭时为共享单例）创建 ChatUiChannel。与
     * {@code ChatUiChannelProvider.current(sessionId)} 同一语义，直接走 runtimeService
     * 引用而非 provider 接口——provider 的 {@code current()} 方法引用会被折叠成无参
     * Supplier，丢失 sessionId 维度（实测踩坑：instanceof 判 lambda 恒 false）。
     */
    private ChatUiChannel sessionChannel(String sessionId) {
        var agent = runtimeService.getAgent(sessionId);
        return agent == null ? null
            : agent.channel(io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel.perPeer());
    }

    /** 兼容单测与嵌入式调用：固定使用传入的 Channel。 */
    public ChatStreamController(ChatUiChannel chatChannel,
                                AgentRuntimeService runtimeService,
                                TurnLeaseStore turnLeaseStore,
                                ToolAuditStore toolAuditStore,
                                UploadWorkspaceInjector workspaceInjector,
                                SandboxConfig sandboxConfig,
                                SessionEventBus eventBus,
                                SessionEventStore eventStore,
                                SessionUserStore sessionUserStore,
                                WorkspaceReader workspaceReader,
                                AgentManagerProperties props,
                                SkillInjectionService skillInjectionService,
                                io.agentmanager.framework.service.FileAssetStore fileAssetStore,
                                McpToolRegistrar mcpToolRegistrar,
                                ModelCatalog modelCatalog,
                                SessionTitleService sessionTitleService) {
        this((Supplier<ChatUiChannel>) () -> chatChannel, runtimeService, turnLeaseStore, toolAuditStore, workspaceInjector,
            sandboxConfig, eventBus, eventStore, sessionUserStore, workspaceReader, props,
            skillInjectionService, fileAssetStore, mcpToolRegistrar, modelCatalog, sessionTitleService, false);
    }

    /** Spring 装配入口：每轮对话从当前 Agent 创建 Channel，reload 后不会继续使用旧 Agent 的引用。 */
    @Autowired
    public ChatStreamController(ChatUiChannelProvider channelProvider,
                                AgentRuntimeService runtimeService,
                                TurnLeaseStore turnLeaseStore,
                                ToolAuditStore toolAuditStore,
                                UploadWorkspaceInjector workspaceInjector,
                                SandboxConfig sandboxConfig,
                                SessionEventBus eventBus,
                                SessionEventStore eventStore,
                                SessionUserStore sessionUserStore,
                                WorkspaceReader workspaceReader,
                                AgentManagerProperties props,
                                SkillInjectionService skillInjectionService,
                                io.agentmanager.framework.service.FileAssetStore fileAssetStore,
                                McpToolRegistrar mcpToolRegistrar,
                                ModelCatalog modelCatalog,
                                SessionTitleService sessionTitleService) {
        this((Supplier<ChatUiChannel>) channelProvider::current, runtimeService, turnLeaseStore, toolAuditStore, workspaceInjector,
            sandboxConfig, eventBus, eventStore, sessionUserStore, workspaceReader, props,
            skillInjectionService, fileAssetStore, mcpToolRegistrar, modelCatalog, sessionTitleService, true);
    }

    private ChatStreamController(Supplier<ChatUiChannel> chatChannelSupplier,
                                 AgentRuntimeService runtimeService,
                                 TurnLeaseStore turnLeaseStore,
                                 ToolAuditStore toolAuditStore,
                                 UploadWorkspaceInjector workspaceInjector,
                                 SandboxConfig sandboxConfig,
                                 SessionEventBus eventBus,
                                 SessionEventStore eventStore,
                                 SessionUserStore sessionUserStore,
                                 WorkspaceReader workspaceReader,
                                 AgentManagerProperties props,
                                 SkillInjectionService skillInjectionService,
                                 io.agentmanager.framework.service.FileAssetStore fileAssetStore,
                                 McpToolRegistrar mcpToolRegistrar,
                                 ModelCatalog modelCatalog,
                                 SessionTitleService sessionTitleService,
                                 boolean perSessionChannel) {
        this.chatChannelSupplier = chatChannelSupplier;
        this.runtimeService = runtimeService;
        this.perSessionChannel = perSessionChannel;
        this.turnLeaseStore = turnLeaseStore;
        this.toolAuditStore = toolAuditStore;
        this.workspaceInjector = workspaceInjector;
        this.sandboxConfig = sandboxConfig;
        this.eventBus = eventBus;
        this.eventStore = eventStore;
        this.sessionUserStore = sessionUserStore;
        this.workspaceReader = workspaceReader;
        this.props = props;
        this.skillInjectionService = skillInjectionService;
        this.fileAssetStore = fileAssetStore;
        this.mcpToolRegistrar = mcpToolRegistrar;
        this.modelCatalog = modelCatalog;
        this.sessionTitleService = sessionTitleService;
    }

    /**
     * 发起对话（sessionId 在请求体，可选）。
     *
     * <p>请求体示例：
     * <pre>{@code
     * {
     *   "message": "你好",
     *   "userId": "user-123",          // 可选
     *   "sessionId": "my-session-1",   // 可选，不传则自动生成
     *   "fileIds": [],                 // 可选
     *   "model": "01J8XK...",          // 可选，会话模型（GET /models 的 id；""/system = 回默认模型）
     *   "skills": ["refund", "order"], // 可选，本轮可用技能列表（缺省 = 全部；[] = 无技能）
     *   "tools": ["query", "get_order"] // 可选，本轮可用工具列表（缺省 = 全部；[] = 仅内置）
     * }
     * }</pre>
     *
     * <p>{@code skills} 仅对本轮生效（不落库、不跨轮沿用），白名单外的技能不出现在模型侧
     * {@code <available_skills>}、不可经 {@code load_skill_through_path} 加载、{@code /Skill}
     * 不注入；未知技能名忽略、已禁用技能剔除（禁用优先）。见 docs/design/session-skill-list-design.md。
     *
     * <p>{@code tools} 仅对本轮生效（不落库、不跨轮沿用），把模型侧 <b>MCP + 自定义</b>工具
     * 可见集收敛为该列表（内置工具恒可见，不受会话级控制）；白名单外调用在 acting 层兜底
     * DENY。名单可用裸名（主口径，= GET /tools 的 name/sessionTools.tools）或 MCP 全名
     * {@code mcp__{server}__{tool}}（别名）。可传清单见 GET /tools 的 {@code sessionTools}。
     * 见 docs/design/session-tool-list-design.md。
     *
     * <p>不传 sessionId 时，首个 SSE 事件为：
     * <pre>{@code {"type":"session_created","session_id":"uuid-xxx"}}</pre>
     */
    // 无 produces 声明（SSE 输出由 Flux<ServerSentEvent> 元素类型驱动，Accept: text/event-stream
    // 仍得 SSE 流）：预检配额满时全局异常处理器的 409 JSON 需经内容协商——produces 声明会把
    // PRODUCIBLE_MEDIA_TYPES 钉死为 [text/event-stream]，任何 JSON body（Map/String 均）在
    // 「acceptable×producible 交集」下无可写 converter → HttpMediaTypeNotAcceptableException →
    // 500（黑盒第 2 轮实锤，栈 AbstractMessageConverterMethodProcessor:256 producibleTypes.isEmpty）
    @PostMapping("/chat")
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest body,
                                              @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
                                              @RequestHeader(value = "X-Forwarded-Prefix", required = false) String forwardedPrefixHeader,
                                              @RequestHeader(required = false) org.springframework.http.HttpHeaders requestHeaders) {
        // ★ 核心：sessionId 在请求体中，可选；不传则自动生成 UUID。
        // 生成提前到入口校验之前（chat-trace-observability-design §3.2）：message 校验失败、
        // model_reject、thread_not_found 等前置 error 帧也带 session_id——调用方拿到的任何
        // 一帧都能归属会话。UUID 生成为纯内存操作，无副作用、不影响后续语义。
        boolean isNewSession = body.sessionId() == null || body.sessionId().isBlank();
        var sessionId = isNewSession
            ? UUID.randomUUID().toString()
            : body.sessionId();
        sessionId = io.agentmanager.framework.util.PathSafe.sanitize(sessionId);

        var message = body.message();
        if ((message == null || message.isBlank()) && (body.fileIds() == null || body.fileIds().isEmpty())) {
            return Flux.just(errorSSE(sessionId, "message or fileIds is required"));
        }

        // 网关 Header 优先 > 请求体 userId > 默认 debug-user
        var userId = (headerUserId != null && !headerUserId.isBlank())
            ? headerUserId
            : (body.userId() != null && !body.userId().isBlank()) ? body.userId() : "debug-user";
        userId = io.agentmanager.framework.util.PathSafe.sanitize(userId);

        log.info("[chat] start: sessionId={}, userId={}, isNew={}, messageLen={}, fileCount={}",
            sessionId, userId, isNewSession,
            message != null ? message.length() : 0,
            body.fileIds() != null ? body.fileIds().size() : 0);
        // 本轮可用技能列表（仅本轮生效，不落库）：缺省 = 全部；[] = 无技能（§3）
        if (body.skills() != null) {
            log.info("[chat] skills: sid={}, count={}, names={}",
                sessionId, body.skills().size(), body.skills());
        }
        // 本轮可用工具列表（仅本轮生效，作用域限 MCP+自定义）：缺省 = 全部；[] = 仅内置
        if (body.tools() != null) {
            log.info("[chat] tools: sid={}, count={}, names={}",
                sessionId, body.tools().size(), body.tools());
        }

        String finalSessionId = sessionId;
        String finalUserId = userId;
        boolean emitSessionCreated = isNewSession;

        // 对外前缀（subpath-routing-design §3.1）：file_ready 合成发生在异步事件回调线程，
        // RequestContextHolder 不可靠——入口解析一次，经闭包/参数显式传到合成点；
        // 直连（无该头）为 null，download_url 维持服务根相对路径（契约不变）
        var forwardedPrefix = io.agentmanager.framework.util.ExternalUrlSupport.normalizePrefix(forwardedPrefixHeader);

        // MCP 请求级透传头（mcp-chat-header-forward-design §6.2）：配置驱动捕获（未声明不捕获），
        // 入口解析一次经闭包随行；名单来自 registrar 内存缓存（reload 语义=下一轮对话），
        // registrar 为 null（单测桩）时按空名单
        var forwardHeaders = io.agentmanager.framework.mcp.ChatRequestHeaders.capture(
            requestHeaders, mcpToolRegistrar != null ? mcpToolRegistrar.getForwardHeaderNames() : null);

        // 新会话的首帧 session_created（issue #87 二级症状兜底）：不再在 create 回调里发——
        // 该回调经 subscribeOn(boundedElastic) 执行，排队 waiter 占满线程池时首帧被推迟
        // （实测 2.6 分钟、整 turn 零帧）。Flux.just 首元素在订阅线程同步写出，不受调度器排队影响。
        var head = emitSessionCreated
            ? Flux.just(sessionCreatedSSE(finalSessionId))
            : Flux.<ServerSentEvent<String>>empty();

        // 会话模型切换（model 可选）：字段缺省 = 不改变会话绑定；传值即绑定到本会话（本 turn 生效）；
        // 空串/system = 清除覆盖回默认模型；未知/禁用 → 拒绝（不改变会话既有绑定）
        var modelProvided = body.model() != null;
        var requestedModel = modelProvided ? body.model().trim() : "";
        if (modelProvided && !ModelCatalog.isSystemSelection(requestedModel)) {
            var reject = modelCatalog != null ? modelCatalog.validateSelectable(requestedModel) : null;
            if (reject != null) {
                log.info("[chat] reject model selection: sessionId={}, model={}, reason={}",
                    finalSessionId, requestedModel, reject);
                return Flux.just(errorSSE(finalSessionId, reject + ": " + requestedModel));
            }
        }

        // 归属校验（issue #158 防夺主）：已存在的会话只允许归属用户继续对话——
        // 下方 upsert 的 ON DUPLICATE KEY UPDATE 会把他人会话改写到请求者名下；
        // 无映射行（新会话/存量）放行。拒绝以 error SSE 帧返回（与 message 校验
        // 同通道），不写任何映射/模型绑定/标题。
        if (!isNewSession && !io.agentmanager.framework.service.SessionAccessGuard.ownedBy(
                sessionUserStore, finalSessionId, finalUserId)) {
            log.info("[chat] reject foreign session: sessionId={}, userId={}", finalSessionId, finalUserId);
            return Flux.just(errorSSE(finalSessionId, "thread_not_found: session '" + finalSessionId
                + "' not found"));
        }

        // 记录会话-用户映射
        sessionUserStore.upsert(finalSessionId, finalUserId);

        // 沙箱配额预检（sandbox-pool-opensandbox-design §4.3）：在 session_user.upsert 之后
        // 取 userKey（finalUserId = 网关解析后的真实用户，与 binding.user_key 同源）。
        // 该会话无存活绑定时执行 ②⑤ 的只读版本；配额满且全部绑定有执行中迹象 → 抛
        // UserQuotaExhaustedException → GlobalExceptionHandler 转 HTTP 409 + 结构化载荷
        // （前端配额错误卡）。预检 fail-open（存储异常放行）；绑定仍惰性（首次 exec 才
        // create/复用），exec 时的全量裁决是最终裁决。
        if (sandboxLifecycle != null) {
            var quotaReject = sandboxLifecycle.precheck(finalUserId, finalSessionId);
            if (quotaReject != null) {
                log.info("[chat] quota exhausted precheck reject: sessionId={}, user={}, bindings={}",
                    finalSessionId, finalUserId, quotaReject.bindings().size());
                throw quotaReject;
            }
        }

        // 会话模型绑定（在用户映射之后写：新会话不产生 user_id=unknown 的过渡行）
        if (modelProvided) {
            sessionUserStore.upsertModel(finalSessionId,
                ModelCatalog.isSystemSelection(requestedModel) ? "" : requestedModel);
        }

        // 会话标题：首条用户消息后异步生成（master 版实现：已有标题/空消息/生成中时内部跳过，
        // 空结果回退首条消息截断），不阻塞本次对话流
        sessionTitleService.generateAsync(finalSessionId, message, finalUserId);

        var stream = Flux.<ServerSentEvent<String>>create(sink -> {
            // 本轮 turn id 在排队前就确定（事件统一使用同一个信封 id）。每轮都先登记意图再
            // 抢锁：既覆盖「排队中刷新」，也顺手清掉上一轮遗留的 FAILED 行（后到的 turn 必须
            // 让 /status 从旧失败态恢复）；租约到手立即清除。两次都是 PK 级轻量写。
            String replyId = UUID.randomUUID().toString();
            if (turnIntentStore != null) {
                turnIntentStore.register(finalSessionId, replyId, TURN_INTENT_TTL);
            }
            // 排队态的停止登记：与执行态共用属主副本的全局轮询（排队期间不额外打 DB）
            if (turnStopWatcher != null
                    && (turnStopSettings == null || turnStopSettings.enabled())) {
                turnStopWatcher.registerQueued(finalSessionId, replyId);
            }
            var token = turnLeaseStore.tryAcquire(finalSessionId);

            // ===== 1. 抢 Turn 租约 =====
            // 等待期内每拍（默认 1s）检查一次停止标记：排队 turn 登记在 TurnStopWatcher 的
            // queued 态，命中停止行后 watcher 只置内存标记（行 = 唯一权威，留给取消动作
            // 消费，issue #228），这里读到标记即直接收流。
            long deadline = System.currentTimeMillis() + ACQUIRE_TIMEOUT.toMillis();
            long nextWaitingFrameAt = System.currentTimeMillis();
            while (token == null && System.currentTimeMillis() < deadline) {
                long now = System.currentTimeMillis();
                if (now >= nextWaitingFrameAt) {
                    sink.next(waitingSSE(finalSessionId));
                    nextWaitingFrameAt = now + WAITING_FRAME_INTERVAL.toMillis();
                }
                boolean queuedStop = turnStopWatcher != null
                    ? turnStopWatcher.isStopRequested(replyId)
                    : (turnStopService != null && turnStopService.matches(finalSessionId, replyId));
                if (queuedStop) {
                    if (turnStopWatcher != null) {
                        turnStopWatcher.unregister(finalSessionId, replyId);
                    } else {
                        turnStopService.clear(finalSessionId, replyId);
                    }
                    clearTurnIntent(finalSessionId, replyId);
                    log.info("[chat] queued turn cancelled by user (sid={}, rid={})",
                        finalSessionId, replyId);
                    sink.next(cancelledSSE(finalSessionId, "user_stopped"));
                    sink.complete();
                    return;
                }
                try {
                    Thread.sleep(stopPollIntervalMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (turnStopWatcher != null) {
                        turnStopWatcher.unregister(finalSessionId, replyId);
                    }
                    clearTurnIntent(finalSessionId, replyId);
                    sink.complete();
                    return;
                }
                token = turnLeaseStore.tryAcquire(finalSessionId);
            }
            if (token == null) {
                String err = "turn_in_progress: session '" + finalSessionId
                    + "' has an active turn and queue timeout reached";
                failTurnIntent(finalSessionId, replyId, err);
                if (turnStopWatcher != null) {
                    turnStopWatcher.unregister(finalSessionId, replyId);
                }
                log.warn("[chat] turn_in_progress: sessionId={}, waiting timeout", finalSessionId);
                sink.next(errorSSE(finalSessionId, err));
                sink.complete();
                return;
            }

            // 租约到手后意图行**先保留**：本轮首个 session_event 落库前，事件侧还查不到
            // 本 turn 的 replyId，跨副本 stop 会因此落进 409 turn_not_ready 窗口（租约在、
            // 无事件可定位）。保留意图行让 TurnStopService 在「held 且无事件」时仍能拿到
            // 正确 replyId；终态/交接取消/ASKING 预检三处负责清理（replyId 栅栏，误删不了
            // 下一轮）。长轮次若意图行 TTL 过期也无碍——彼时事件早已落库，走事件路径。
            // ===== 1.2 停止状态收敛（租约交接点，issue #228）=====
            // 原子取回排队期停止标记：置位 = 停止请求落在「末次 loop 检查之后～本拍
            // tryAcquire 得手」的竞态窗口内——此处不取消，turn 就会在用户已点停止的
            // 情况下照常跑完（无人再兜底投递）。取消收尾与排队 loop 命中路径（上方
            // queuedStop 分支）同构：unregister 消费 turn_stop 行 → release 刚到手的
            // 租约（必须用 tryAcquire 同一 token；此时无续租线程，先例 = 下方 ASKING
            // 预检拒绝路径；release 失败由租约 TTL 自然过期自愈，不锁死）→ cancelled
            // 帧收流，turn 不起跑、零副作用。未置位 = 行留表，交给执行态派发（register
            // 之后的全局轮询 ≤1 个周期内 interrupt，AGENT_END 前补 cancelled 帧）。
            if (turnStopWatcher != null && turnStopWatcher.unregisterRegistrationOnly(replyId)) {
                turnStopWatcher.unregister(finalSessionId, replyId);
                turnLeaseStore.release(finalSessionId, token);
                clearTurnIntent(finalSessionId, replyId);
                log.info("[chat] queued turn cancelled at lease handoff (sid={}, rid={})",
                    finalSessionId, replyId);
                sink.next(cancelledSSE(finalSessionId, "user_stopped"));
                sink.complete();
                return;
            }

            // ===== 1.5 ASKING 预检（issue #47/#48）：挂起会话不接受新 turn =====
            // ASKING 态下的普通消息会被 SDK 在 turn 入口拒绝（doCallInner 抛 "ASKING state
            // ... cannot proceed"），但拒绝原因的 error 帧与 AGENT_END 正常收尾存在时序竞态：
            // AGENT_END 先经 endTurn→closeSession 关流，迟到的 error 帧落在已关闭 session 上
            // 被丢弃，客户端拿到零帧空响应（本地稳定复现、CI 时序未触发）。入口直接拒绝后
            // SDK 路径不再触达，error 帧恒达。预检放在抢到租约之后：与 confirm 恢复 turn
            // 互斥——恢复完成前本 turn 排队等待，恢复后 state 已更新，不会误拒。
            if (runtimeService.hasPendingConfirm(finalSessionId)) {
                log.info("[chat] reject new turn: session pending confirmation (sid={})", finalSessionId);
                turnLeaseStore.release(finalSessionId, token);
                clearTurnIntent(finalSessionId, replyId);
                sink.next(errorSSE(finalSessionId, "turn_pending_confirm: session '" + finalSessionId
                    + "' is in ASKING state (waiting for tool approval); approve or deny the "
                    + "pending tool call before sending new messages"));
                sink.complete();
                return;
            }

            // ===== 2. 启动续租 =====
            // 构造失败必须显式释放：guard 的构造里若在起线程之前就抛了（判据计算、线程池创建），
            // 没有任何人持有 token，而下面那段的 catch 也覆盖不到它。没有续租线程时租约会在
            // TTL 后自然过期，不会永久锁死，但那是「靠超时自愈」，不该作为正常回滚手段。
            TurnLeaseGuard lease;
            try {
                lease = new TurnLeaseGuard(turnLeaseStore, finalSessionId, token);
            } catch (Exception e) {
                log.error("[chat] failed to start lease renewer, releasing lease (sid={}): {}",
                    finalSessionId, e.getMessage());
                turnLeaseStore.release(finalSessionId, token);
                var err = "turn_setup_failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                failTurnIntent(finalSessionId, replyId, err);
                sink.next(errorSSE(finalSessionId, err));
                sink.complete();
                return;
            }

            // ===== 2.4 登记停止监视 =====
            // 进入执行段（租约已到手）后，属主副本的全局轮询按 replyId 命中本 turn 时
            // 调用 SDK 按会话 interrupt；收尾时注销并清理本轮停止行。
            if (turnStopWatcher != null
                    && (turnStopSettings == null || turnStopSettings.enabled())) {
                turnStopWatcher.register(finalSessionId, replyId);
            }

            // ===== 2.45 沙箱生命周期 turn 注册（§4.4 T3 双信号之一）=====
            // turn 开始注册（守卫线程/续期线程并发安全）：沙箱未 acquire 时注册先行落位，
            // 覆盖「LLM-only 长轮次在首次 exec 前无 call 引用」的续期盲区；终态注销只挂
            // endTurnAndCleanupBuckets（TurnFinalizer 终态），不挂 per-call stop()。
            if (sandboxLifecycle != null) {
                sandboxLifecycle.onTurnStart(finalSessionId);
            }

            // ===== 2.5 跨层排队 waiting 心跳（issue #87）=====
            // 租约等待期由上面循环发 waiting；此处覆盖租约**之后**的排队点（SDK turn 闸门/
            // 模型连接池等）：每 15s 向 sink 直发一帧 waiting，首个 SDK 事件或任一终态即停。
            // 心跳帧不进 EventBus、不落库（与租约等待帧同词表，前端零改动、durable 续传不含）。
            var firstFeedbackSeen = new java.util.concurrent.atomic.AtomicBoolean(false);
            var heartbeat = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>>(
                WAIT_HEARTBEAT.scheduleAtFixedRate(() -> {
                    if (!firstFeedbackSeen.get()) {
                        sink.next(waitingSSE(finalSessionId));
                    }
                }, WAITING_FRAME_INTERVAL.toMillis(), WAITING_FRAME_INTERVAL.toMillis(),
                   java.util.concurrent.TimeUnit.MILLISECONDS));
            // 首事件延迟打点（issue #87 可观测性）：从租约到手到首个事件帧的延迟，
            // 超 FIRST_EVENT_WARN_MS 升 WARN——排队/静默类问题从此有直接日志证据
            long turnStartMs = System.currentTimeMillis();
            var firstEventLogged = new java.util.concurrent.atomic.AtomicBoolean(false);

            // ===== 3. 准备 EventBus Sinks =====
            // 为何本 turn 只收尾一次（迟到的 complete/error 不得再拆 sink）：
            // 见 TurnFinalizer#endTurn 的教训注释
            var turnEnded = new java.util.concurrent.atomic.AtomicBoolean(false);
            // 沙箱换代提示（§4.4 T4）：turn 起始时刻 + 一次性已提示标记（逐事件探测归属失效登记）
            var turnStartedAt = new java.util.concurrent.atomic.AtomicLong(turnStartMs);
            var sandboxRebuildNotified = new java.util.concurrent.atomic.AtomicBoolean(false);
            // 本 turn 触及过的桶 key（toolCallId）；turn 收尾时统一清桶，防异常路径残留。
            // 按 turn 粒度隔离：清理只删本 turn 登记过的 key，toolCallId 跨 session 不可能
            // 撞 key，并行 turn 的桶不受影响（A4）
            var turnBucketKeys = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();

            // 工具摘要累积器（turn 级）：累积 delta 参数/结果，在 END 事件合成人可读摘要帧。
            // 与 turn 同生命周期——放在订阅回调之外创建，避免被 Reactor 的取消回调连带清理。
            var toolSummary = new TurnToolSummaryTracker(finalSessionId, replyId,
                (type, payload) -> eventBus.emitSynthetic(finalSessionId, replyId, type, payload),
                mcpToolRegistrar::displayNameFor);

            // 本轮模型用量累积器（chat-trace-observability-design §2.3）：MODEL_CALL_END 逐次
            // 累加，turn 收尾合成 turn_finished 汇总帧。turn 闭包局部对象，随 turn 丢弃。
            var usage = new io.agentmanager.framework.service.TurnUsageTracker();
            // 本轮是否见过 HITL 挂起：AGENT_END 收尾时据此把 turn_finished 分叉为
            // waiting_confirm（确认恢复轮是独立 turn、另发自己的帧）
            var confirmAsked = new java.util.concurrent.atomic.AtomicBoolean(false);

            // 准备段异常的回滚（error 帧 → closeSession → release → complete）为何必须做：
            // 见 TurnFinalizer#abortSetup 的教训注释。
            // 该路径 replyId 尚未产出任何事件、无本 turn 桶可清，turnBucketKeys 必为空
            List<Msg> messages;
            try {
                eventBus.beginTurn(finalSessionId);

                // ===== 4. 构造消息 =====
                // 注入 /Skill 引用（按 session userId 合并该用户 L4 个人技能；本轮白名单裁剪）
                var processedMessage = skillInjectionService.injectSkillReferences(
                    message, finalUserId, body.skills());

                if (body.fileIds() != null && !(sandboxConfig != null && sandboxConfig.enabled())) {
                    for (var fileId : body.fileIds()) {
                        workspaceInjector.injectToWorkspace(fileId, finalSessionId);
                    }
                }
                var blocks = workspaceInjector.buildContentBlocks(body.fileIds(), processedMessage, finalUserId);
                // 记录本轮上传文件 id（随消息持久化），history 读取时据此把上传卡片挂回本用户消息
                var msgMeta = new java.util.HashMap<String, Object>();
                msgMeta.put(UiContextStore.METADATA_SESSION_KEY, finalSessionId);
                // 逐轮归属锚点：history 按此把本轮（可能多条）assistant 消息归到同一 reply_id，
                // 不依赖 Redis 事件索引 TTL（见 StateDataParser.METADATA_TURN_REPLY_ID）
                msgMeta.put(io.agentmanager.framework.service.StateDataParser.METADATA_TURN_REPLY_ID, replyId);
                // 本轮技能白名单随本条用户消息下传（SkillFilterMiddleware 读取；仅本轮生效）。
                // 显式空列表也要写入（= 无技能），因此只判 null 不判空
                if (body.skills() != null) {
                    msgMeta.put(io.agentmanager.framework.service.SkillFilterMiddleware.METADATA_SKILLS_KEY,
                        body.skills());
                }
                // 本轮工具白名单随本条用户消息下传（ToolFilterMiddleware 读取，作用域限
                // MCP+自定义、内置恒可见；session-tool-list-design §2）。仅本轮生效：
                // 显式空列表也要写入（= 仅内置），因此只判 null 不判空
                if (body.tools() != null) {
                    msgMeta.put(io.agentmanager.framework.service.ToolFilterMiddleware.METADATA_TOOLS_KEY,
                        body.tools());
                }
                if (body.fileIds() != null && !body.fileIds().isEmpty()) {
                    msgMeta.put(UploadWorkspaceInjector.METADATA_FILE_IDS, body.fileIds());
                }
                var msg = Msg.builder().role(MsgRole.USER).name(finalUserId)
                    .metadata(msgMeta)
                    .content(blocks).build();
                messages = new ArrayList<>();
                messages.add(msg);

                // ===== 5. 先订阅 EventBus =====
                eventBus.subscribe(finalSessionId, 0, replyId)
                    .subscribe(
                        sse -> {
                            // 首个**带 data** 的帧（SDK 事件/合成摘要/error）才算反馈：
                            // EventBus 的 comment 心跳帧（data=null，重置代理超时用）不算，
                            // 否则排队静默期会被它提前停掉 waiting 心跳
                            boolean hasData = sse.data() != null;
                            if (hasData && firstFeedbackSeen.compareAndSet(false, true)) {
                                cancelHeartbeat(heartbeat);
                            }
                            if (hasData && firstEventLogged.compareAndSet(false, true)) {
                                long delayMs = System.currentTimeMillis() - turnStartMs;
                                if (delayMs > FIRST_EVENT_WARN_MS) {
                                    log.warn("[chat] turn first-event after {}ms (sid={}, rid={}) — "
                                        + "排队/静默嫌疑，排查 turn 闸门与线程池", delayMs, finalSessionId, replyId);
                                } else {
                                    log.debug("[chat] turn first-event after {}ms (sid={}, rid={})",
                                        delayMs, finalSessionId, replyId);
                                }
                            }
                            sink.next(sse);
                        },
                        e -> {
                            log.warn("EventBus subscription error (sid={}): {}", finalSessionId, e.getMessage());
                            cancelHeartbeat(heartbeat);
                            sink.error(e);
                        },
                        () -> {
                            cancelHeartbeat(heartbeat);
                            sink.complete();
                        });

                // ===== 6. 启动 agent 执行 =====
                // sendStream 的**同步**异常也必须落在这个 try 里：它抛之前租约已经到手，
                // 而续租线程不看本段是否还活着——漏放租约就是该 session 被永久锁死
                // （后续每个请求都拿不到租约，观察者也会一直 probe 到 RUNNING）。
                // per-session 路径：channel 绑定到会话级 agent 实例（#2849 单槽隔离）；
                // 兼容路径（单测/嵌入式，固定 channel）：走原 supplier
                var currentChatChannel = perSessionChannel
                    ? sessionChannel(finalSessionId)
                    : chatChannelSupplier.get();
                if (currentChatChannel == null) {
                    throw new IllegalStateException("no active agent channel");
                }
                // in-band 上下文随行（mcp-chat-header-forward-design §6.2）：SDK 2.0.3 的
                // sendStream(ChatUiRequest) 内部即 dispatchStream(buildInbound(request))，对
                // withPeer 产出的正是 InboundMessage.dm(channelId, peerId.trim(), messages)——
                // 同一条路由路径，仅多带 runtimeContext（网关身份字段仍由网关覆盖生效）。
                // 透传头经 typed attribute 随行（turn 级存活、不持久化），middleware 合并进 McpMeta
                var inbound = io.agentscope.harness.agent.gateway.channel.InboundMessage
                    .dm(currentChatChannel.channelId(), finalSessionId, messages)
                    .withRuntimeContext(forwardHeaders.toRuntimeContext());
                currentChatChannel.dispatchStream(inbound)
                    .subscribe(
                        event -> handleEventAndEmit(event, finalSessionId, replyId, lease,
                            finalUserId, sink, turnEnded, turnStartedAt, sandboxRebuildNotified,
                            turnBucketKeys, toolSummary, forwardedPrefix, body.skills(),
                            usage, confirmAsked),
                        e -> {
                            log.warn("session chat stream error (sid={}): {}", finalSessionId, e.getMessage());
                            cancelHeartbeat(heartbeat);
                            // 沙箱配额满（§4.6 exec 撞配额：A2A/预检放行后竞态兜底路径）：
                            // 发结构化 error 帧（code=user_quota_exhausted + bindings 明细，
                            // 前端 quotaFromSseError 识别 → 配额错误卡），替代纯文本报错
                            var quotaEx = findQuotaExhausted(e);
                            if (quotaEx != null && !lease.isLost()) {
                                eventBus.emitSynthetic(finalSessionId, replyId, "error",
                                    quotaEx.sseFrameJson());
                                recordTurnError(finalSessionId, replyId, quotaEx.getMessage());
                            } else if (isTrailingSandboxTeardownError(e)) {
                                log.info("ignore trailing sandbox teardown error (sid={})", finalSessionId);
                            } else if (!lease.isLost()) {
                                // 沙箱类瞬时故障（建箱超时/连接失败/网关不可达，oa-1110 #1c）：
                                // 结构化可读 error 帧（code=sandbox_create_failed + retryable +
                                // 中文文案），替代 "Lifecycle acquire failed: Request timed out:
                                // timeout" 生硬透出；非沙箱类保持原样透出（后向兼容）
                                var sandboxFrame = io.agentmanager.framework.sandbox.opensandbox
                                    .SandboxFailureClassifier.sseErrorFrame(e);
                                if (sandboxFrame != null) {
                                    eventBus.emitSynthetic(finalSessionId, replyId, "error", sandboxFrame);
                                    recordTurnError(finalSessionId, replyId,
                                        "sandbox_create_failed: " + e.getMessage());
                                } else {
                                    // 丢锁后这场 error 多半是丢锁的后果，再落一条只会占用新 owner 的 seq
                                    var errText = e.getMessage() != null
                                        ? e.getMessage() : e.getClass().getSimpleName();
                                    eventBus.emitSynthetic(finalSessionId, replyId, "error",
                                        "{\"type\":\"error\",\"error\":" + AgentEventSseSerializer.jsonEsc(errText) + "}");
                                    recordTurnError(finalSessionId, replyId, errText);
                                }
                            }
                            endTurnAndCleanupBuckets(lease, finalSessionId, replyId, turnEnded,
                                turnBucketKeys, usage, turnStartedAt, TurnFinalizer.TurnOutcome.ERROR);
                        },
                        () -> {
                            cancelHeartbeat(heartbeat);
                            // 正常完成：幂等兜底（AGENT_END 已在 handleEventAndEmit 中收尾）；
                            // 源流无 AGENT_END 直接收尾的旁路也在此拿到 turn_finished
                            endTurnAndCleanupBuckets(lease, finalSessionId, replyId, turnEnded,
                                turnBucketKeys, usage, turnStartedAt, TurnFinalizer.TurnOutcome.COMPLETED);
                        });
            } catch (Exception e) {
                log.warn("[chat] turn setup failed, rolling back (sid={}): {}",
                    finalSessionId, e.getMessage());
                cancelHeartbeat(heartbeat);
                var err = "turn_setup_failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                failTurnIntent(finalSessionId, replyId, err);
                if (turnStopWatcher != null) {
                    turnStopWatcher.unregister(finalSessionId, replyId);
                }
                // 沙箱生命周期 turn 注销：onTurnStart 已在本段注册（§4.4 T3），回滚同样必须
                // 注销——否则 activeTurns 条目无 TTL 泄漏，sweep 持续按 turn 注册续期
                // （与 endTurnAndCleanupBuckets 的「终态必注销」对称）
                if (sandboxLifecycle != null) {
                    sandboxLifecycle.onTurnEnd(finalSessionId);
                }
                TurnFinalizer.abortSetup(eventBus, lease, sink, finalSessionId, errorSSE(finalSessionId, err));
                return;
            }

            // ===== 7. onCancel =====
            // 断连不杀任务（durable-sse 不变量）：agent 继续跑、租约保留、事件照常落库，
            // 客户端可用 /subscribe 续传；turn 终态由源 flux 的 complete/error 回调收尾。
            //
            // 注意（2026-09-19 教训）：此处**绝不能做阻塞操作**——onCancel 跑在 Reactor 的
            // 取消回调线程（boundedElastic）上，任何 sleep/同步等待都会占死该线程并连带
            // 卡住 SDK 会话闸门（LocalSessionTurnGate）的释放路径，导致后续所有 turn 全堵。
            // 需要兜底看护时只能用独立调度线程，见 OrphanTurnWatchdog。
            sink.onCancel(() -> {
                log.info("[chat] SSE disconnected, agent execution continues (sid={}, rid={})",
                    finalSessionId, replyId);
                cancelHeartbeat(heartbeat);
                orphanTurnWatchdog.schedule(() -> {
                    if (!turnEnded.get()) {
                        log.warn("[chat] orphan turn grace expired ({}ms), force-releasing lease "
                            + "(sid={}, rid={}) —— 防租约/闸门许可泄漏",
                            OrphanTurnWatchdog.GRACE_MS, finalSessionId, replyId);
                        endTurnAndCleanupBuckets(lease, finalSessionId, replyId, turnEnded,
                            turnBucketKeys, usage, turnStartedAt, TurnFinalizer.TurnOutcome.INTERRUPTED);
                    }
                }, finalSessionId, replyId);
            });

        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
        // 首帧在订阅线程同步直发（见上方 head 注释），正文经调度器排队执行
        return head.concatWith(stream);
    }

    /** 停掉 waiting 心跳（幂等；false=不中断已在执行的任务） */
    private static void cancelHeartbeat(java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> heartbeat) {
        var f = heartbeat.get();
        if (f != null) {
            f.cancel(false);
        }
    }

    // ===== 事件处理 =====

    /**
     * TOOL_CALL_START 命中 MCP App ui 映射时，序列化携带 ui 元数据
     * （mcp-apps-extension-plan §4：发源地查 {@link McpToolRegistrar} 后传入；
     * 前端 chat.js 据此把工具行渲染为 iframe 内嵌的 MCP App 卡片）。
     * 非 ui 工具（含内置工具）返回 null，payload 保持原词表。
     */
    private String payloadForEvent(AgentEvent event, String sessionId) {
        if (event instanceof ToolCallStartEvent tc) {
            var uiRef = mcpToolRegistrar.resolveUiRef(tc.getToolCallName());
            if (uiRef != null) {
                return AgentEventSseSerializer.payload(event, sessionId,
                    uiRef.resourceUri(), uiRef.serverName());
            }
        }
        return null;
    }

    private void handleEventAndEmit(AgentEvent event, String sessionId,
                                    String replyId, TurnLeaseGuard lease, String userId,
                                    FluxSink<ServerSentEvent<String>> sink,
                                    java.util.concurrent.atomic.AtomicBoolean turnEnded,
                                    java.util.concurrent.atomic.AtomicLong turnStartedAt,
                                    java.util.concurrent.atomic.AtomicBoolean sandboxRebuildNotified,
                                    java.util.Set<String> turnBucketKeys,
                                    TurnToolSummaryTracker toolSummary,
                                    String forwardedPrefix,
                                    java.util.List<String> turnSkills,
                                    io.agentmanager.framework.service.TurnUsageTracker usage,
                                    java.util.concurrent.atomic.AtomicBoolean confirmAsked) {
        // 为何丢租约必须立刻停写、终态帧为何不落库：见 TurnFinalizer#stopIfLeaseLost
        if (TurnFinalizer.stopIfLeaseLost(eventBus, lease, sessionId, sink, "[chat]")) {
            if (turnStopWatcher != null) {
                turnStopWatcher.unregister(sessionId, replyId);
            }
            return;
        }

        // 沙箱换代提示帧（§4.4 T4 恢复路径）：LRU 改绑/懒剔除后本会话重入被归属校验拦截
        // 降级重建时，在首个事件前一次性提示「沙箱已重建，未保存的工作区文件可能丢失」
        if (!sandboxRebuildNotified.get() && sandboxLifecycle != null
                && sandboxLifecycle.popSandboxRebuiltSince(sessionId, turnStartedAt.get())) {
            sandboxRebuildNotified.set(true);
            eventBus.emitSynthetic(sessionId, replyId, "sandbox_rebuilt",
                "{\"type\":\"sandbox_rebuilt\",\"message\":\"沙箱已重建，未保存的工作区文件可能丢失\"}");
        }

        // 用户停止（POST /threads/{sid}/stop）：
        // ① SDK 的 interrupt 恢复消息（"I noticed that you have interrupted me…"，
        //    generateReason=INTERRUPTED）不上屏——它不是模型输出，只是 SDK 的恢复话术；
        // ② AGENT_END 之前落一条 cancelled 终态帧，客户端据此展示「已停止」。
        // 该帧是合成帧（进 Redis 事件流，可回放），也是 SessionEventTailer 的终态之一。
        boolean stopRequested = turnStopWatcher != null
            && turnStopWatcher.isStopRequested(replyId);
        if (stopRequested
                && event instanceof io.agentscope.core.event.AgentResultEvent resultEvent
                && resultEvent.getResult() != null
                && resultEvent.getResult().getGenerateReason()
                    == io.agentscope.core.message.GenerateReason.INTERRUPTED) {
            log.info("[chat] suppress SDK interrupt recovery message (sid={}, rid={})",
                sessionId, replyId);
            return;
        }

        audit(event, sessionId);

        // 工具名登记：ToolCallStartEvent 是唯一携带真实工具名的事件（delta 帧的名字是占位符）。
        // 同点登记 turnBucketKeys（A4）：新增桶 put 点时必须在此同步登记，否则该类残留
        // 回到「异常路径跨 turn 累积」的现状
        if (event instanceof ToolCallStartEvent start
                && start.getToolCallId() != null && start.getToolCallName() != null) {
            turnBucketKeys.add(start.getToolCallId());
            toolCallNames.put(start.getToolCallId(), start.getToolCallName());
        }

        // write_file 输入参数截获（按登记的真实工具名判定，不能读 delta.getToolCallName()）
        if (!sandboxConfig.enabled()) {
            if (event instanceof ToolCallDeltaEvent delta
                    && isTool(delta.getToolCallId(), "write_file")) {
                turnBucketKeys.add(delta.getToolCallId());
                accumulateWriteFileInput(delta.getToolCallId(), String.valueOf(delta.getDelta()));
            }
            if (event instanceof ToolCallEndEvent end && isTool(end.getToolCallId(), "write_file")) {
                syncWriteFileToKv(end.getToolCallId(), userId);
                toolCallNames.remove(end.getToolCallId());
            }
        }

        // 产出文件工具（present_file/present_url）结果累积（同样按登记名判定）
        if (event instanceof ToolResultTextDeltaEvent trd
                && (isTool(trd.getToolCallId(), "present_file", trd.getToolCallName())
                    || isTool(trd.getToolCallId(), "present_url", trd.getToolCallName()))) {
            turnBucketKeys.add(trd.getToolCallId());
            accumulatePresentFile(trd.getToolCallId(), String.valueOf(trd.getDelta()));
        }

        // 工具摘要累积（tool_call_summary/tool_result_preview 帧的数据源）。
        // ToolCallStartEvent 是唯一携带真实工具名的事件（delta 帧名字恒为 __fragment__），
        // 故先登记再按 id 累积，到 END 事件时才能合成出「创建 x.js 2行」这样的摘要。
        if (event instanceof ToolCallStartEvent start) {
            toolSummary.onToolCallStart(start);
        } else if (event instanceof ToolCallDeltaEvent delta) {
            toolSummary.onToolCallDelta(delta);
        } else if (event instanceof ToolResultTextDeltaEvent trd) {
            toolSummary.onToolResultDelta(trd);
        }

        // 本轮模型用量累积（chat-trace-observability-design §2.3）：ReAct 多轮 = 多次
        // MODEL_CALL_END，收尾时 turn_finished 汇总
        if (event instanceof io.agentscope.core.event.ModelCallEndEvent mce) {
            usage.onModelCallEnd(mce);
        }

        // HITL：挂起点只落确认上下文与广播 permission_ask 帧，**不在这里释放租约/关流**。
        // SDK 的 AgentState（ASKING 快照，跨副本恢复的依据）在 turn 收尾（AGENT_END 处理）才落库，
        // 此处提前 release 会让 confirm 在快照落地前抢到锁：恢复侧读不到 state 身份 →
        // 回落 fullThreadId 兜底槽 → 空会话恢复丢全部上下文（e2e R5/工具插件 ask 批准 实测）。
        // 收尾统一走 AGENT_END 的 endTurn（closeSession → release，顺序保证先刷后放锁）。
        if (event instanceof RequireUserConfirmEvent) {
            log.info("[chat] HITL permission_ask: sessionId={}, tools={}", sessionId,
                ((RequireUserConfirmEvent) event).getToolCalls().stream()
                    .map(tc -> tc.getName()).toList());
            runtimeService.storeConfirmContext(sessionId, event, turnSkills);
            // turn_finished.state 分叉依据（§2.2）：本轮以 waiting_confirm 收尾
            confirmAsked.set(true);
            // T4 挂起保留窗（§4.4）：ask 挂起后沙箱持续续期（同沙箱恢复的保障），
            // 每次 ask 重新计窗；窗口过后停续 → 可被 LRU 调度/自然到期
            if (sandboxLifecycle != null) {
                sandboxLifecycle.onConfirmHold(sessionId);
            }
        }

        // 停止终态帧：排在 AGENT_END 之前（同一 turn 内 seq 有序），客户端看到
        // cancelled 即可展示「已停止」；AGENT_END 紧随其后完成既有收尾。
        if (stopRequested && event.getType() == AgentEventType.AGENT_END
                && !AgentEventSseSerializer.isRemoteForwarded(event)) {
            eventBus.emitSynthetic(sessionId, replyId, "cancelled",
                "{\"type\":\"cancelled\",\"reason\":\"user_stopped\"}");
        }

        eventBus.emit(sessionId, event, replyId, payloadForEvent(event, sessionId));

        // ★ 工具摘要帧（可观测性）：参数/结果到齐后追加人可读摘要，让轻量客户端无需
        // 自己拼 delta、解析工具参数即可展示「创建 create-ai-ppt.js 408行」。
        // 必须在原事件之后发射——前端按 toolCallId 认领工具行，先到的是原始事件。
        emitToolSummary(toolSummary, event);

        // HITL 挂起后不再在此关流：permission_ask 帧已广播，订阅者随后收到 AGENT_END 帧
        // （前端忽略该帧、e2e 终态归一仍取 permission_ask），closeSession 由紧随的
        // endTurn 统一执行——先刷缓冲/落库再放锁，confirm 排队抢锁时快照必然已可见。

        // 产出文件工具完成合成 file_ready（present_url 与 present_file 返回同构 JSON，
        // 复用同一条下载卡片链路；外部交付物经 /files/{id} 代理下载）
        if (event instanceof ToolResultEndEvent tre
                && ("present_file".equals(tre.getToolCallName())
                    || "present_url".equals(tre.getToolCallName()))) {
            emitFileReadyViaEventBus(sessionId, replyId, tre.getToolCallId(), forwardedPrefix);
        }

        // AGENT_END（仅 lead 自身事件）：远程子 agent 转发的 AGENT_END（source 非空）只是
        // spawn 调用进行中「子任务运行终点」，若当作 turn 终点会提前释放租约并关流——
        // SSE 在 spawn 结果/lead 收尾汇报返回前被截断（isRemoteForwarded javadoc 有实测记录）
        if (event.getType() == AgentEventType.AGENT_END
                && !AgentEventSseSerializer.isRemoteForwarded(event)) {
            log.info("[chat] agent completed: sessionId={}", sessionId);
            // HITL 挂起轮同样以 AGENT_END 收尾（ASKING 快照随收尾落库后租约才让出）：
            // turn_finished.state 分叉为 waiting_confirm，客户端据此区分「完成」与「等确认」
            endTurnAndCleanupBuckets(lease, sessionId, replyId, turnEnded, turnBucketKeys,
                usage, turnStartedAt,
                confirmAsked.get() ? TurnFinalizer.TurnOutcome.WAITING_CONFIRM
                    : TurnFinalizer.TurnOutcome.COMPLETED);
        }
    }

    /**
     * turn 收尾 + 本 turn 桶清理（A4）：只有抢到收尾权（{@link TurnFinalizer#endTurn}
     * 3 参的 CAS 首胜返回 true）才清桶，且只清本 turn 在 {@code turnBucketKeys}
     * 里登记过的 key——并行 turn（其他 session）的桶条目不可达于本清理循环；
     * {@code remove} 天然幂等，与正常路径（End/合成时）的 remove 双删无冲突。
     *
     * <p>清桶发生在终态动作（closeSession/abandonSession）之后：桶纯内存、不产生
     * 任何 SSE 字节，先后不影响行为。
     *
     * <p>覆盖的终态：AGENT_END、源流 error/complete 回调、onCancel 看护强收（均走本方法）。
     * <b>HITL 与普通终态同路</b>：permission_ask 只落确认上下文 + 广播帧，不再提前
     * release/closeSession——ASKING 快照随 AGENT_END 收尾落库后才放锁，confirm 排队
     * 抢到锁时恢复要素必然齐备（见 handleEventAndEmit 的 HITL 注释）。终态后理论上仍可能
     * 有极晚事件回调 re-put（既有边界，桶上限 64KB/2MB 兜底）。
     */
    private void endTurnAndCleanupBuckets(TurnLeaseGuard lease, String sessionId, String replyId,
                                          java.util.concurrent.atomic.AtomicBoolean turnEnded,
                                          java.util.Set<String> turnBucketKeys,
                                          io.agentmanager.framework.service.TurnUsageTracker usage,
                                          java.util.concurrent.atomic.AtomicLong turnStartedAt,
                                          TurnFinalizer.TurnOutcome outcome) {
        // 停止监视必须先注销：播发/收尾期间可能仍有轮询 tick（注销后不再 dispatch），
        // 同时清理本轮遗留的 turn_stop 行（replyId 栅栏，误删不了下一轮）
        if (turnStopWatcher != null) {
            turnStopWatcher.unregister(sessionId, replyId);
        }
        // 终态只收尾一次（与 TurnFinalizer.endTurn 4 参的 CAS 语义相同，此处前移以便在
        // closeSession 刷缓冲**之前**发射 turn_finished——拿到紧邻终态帧的 seq 且落库可回放）。
        // 只有真正收尾的这次清理意图行：意图行在租约到手后保留到终态，供 stop 在首个
        // 事件前解析 replyId；replyId 栅栏：同会话更新的排队意图不会被误删。
        if (!turnEnded.compareAndSet(false, true)) {
            return;
        }
        // turn 收尾汇总帧（chat-trace-observability-design §2.2）：丢锁路径跳过——
        // abandon 语义禁止再写行（此刻任何 append 都会占用可能与新 owner 重叠的 seq）
        if (!lease.isLost()) {
            TurnFinalizer.emitTurnFinished(eventBus, sessionId, replyId, usage,
                turnStartedAt.get(), outcome);
        }
        TurnFinalizer.endTurn(eventBus, lease, sessionId);
        clearTurnIntent(sessionId, replyId);
        // 沙箱生命周期 turn 终态注销 + 终刷（§4.4 T2）：只挂 turn 终态（正常收尾/出错/
        // 被拒三入口经此汇聚），不挂 per-call stop()——一个 turn 可能多 call
        if (sandboxLifecycle != null) {
            sandboxLifecycle.onTurnEnd(sessionId);
        }
        for (String toolCallId : turnBucketKeys) {
            toolCallNames.remove(toolCallId);
            presentFileBuffers.remove(toolCallId);
            writeFileInputBuffers.remove(toolCallId);
        }
    }

    /** 异常链（含 harness 包装层）中查找沙箱配额满异常（§4.6 结构化 SSE 帧的判定） */
    private static io.agentmanager.framework.sandbox.opensandbox.UserQuotaExhaustedException findQuotaExhausted(
            Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof io.agentmanager.framework.sandbox.opensandbox.UserQuotaExhaustedException q) {
                return q;
            }
            if (t.getCause() == t) {
                break;   // 自引用 cause 防环
            }
        }
        return null;
    }

    /** 清除本轮排队意图（租约到手/正常放弃；store 不可用时为 no-op）。 */
    private void clearTurnIntent(String sessionId, String replyId) {
        if (turnIntentStore != null) {
            turnIntentStore.clear(sessionId, replyId);
        }
    }

    /** 标记本轮失败（排队超时/建立失败），让刷新后的客户端仍能从 /status 看到原因。 */
    private void failTurnIntent(String sessionId, String replyId, String error) {
        if (turnIntentStore != null) {
            turnIntentStore.fail(sessionId, replyId, error, TURN_INTENT_FAILED_TTL);
        }
    }

    /**
     * turn 失败留痕：把失败原因写入会话消息轨，使刷新后 history 可见「本次执行失败：…」，
     * 不再只剩回合起始归档留下的用户提问（孤儿提问，用户无法知道上轮失败）。
     * 归档存储缺失（归档关闭 / 单测）时跳过；失败忽略——留痕不影响主链路。
     */
    private void recordTurnError(String sessionId, String replyId, String message) {
        if (sessionMessageStore == null || message == null || message.isBlank()) {
            return;
        }
        try {
            var masked = io.agentmanager.framework.service.StateDataParser.maskSensitiveText(message);
            var text = masked.length() > 500 ? masked.substring(0, 500) : masked;
            sessionMessageStore.archiveTurnError(sessionId, replyId, "⚠️ 本次执行失败：" + text);
        } catch (Exception ex) {
            log.debug("recordTurnError failed (sid={}): {}", sessionId, ex.getMessage());
        }
    }

    // ===== 产出文件工具（present_file/present_url）累积 & file_ready 合成 =====

    /**
     * 该 toolCallId 是否属于指定工具：优先查登记表（ToolCallStart 登记的权威名字），
     * 表未命中时回落到事件自带的工具名——覆盖 ToolResult* 等本就携带真实名的事件类型。
     */
    private boolean isTool(String toolCallId, String expected, String eventToolName) {
        if (toolCallId == null) {
            return false;
        }
        String registered = toolCallNames.get(toolCallId);
        return expected.equals(registered != null ? registered : eventToolName);
    }

    private boolean isTool(String toolCallId, String expected) {
        return isTool(toolCallId, expected, null);
    }

    // ===== 私有可测性观察点（A4）：仅供测试断言，不进公有 API =====

    /** 三个工具桶条目数之和（toolCallNames/presentFileBuffers/writeFileInputBuffers） */
    int bucketEntryCount() {
        return toolCallNames.size() + presentFileBuffers.size() + writeFileInputBuffers.size();
    }

    /** 定向查看登记表中某 toolCallId 的工具名（测试断言「并行 turn 防误删」用） */
    String registeredToolName(String toolCallId) {
        return toolCallNames.get(toolCallId);
    }

    /**
     * 参数/结果到齐事件 → 追加合成 {@code tool_call_summary} / {@code tool_result_preview} 帧。
     *
     * <p>摘要由 {@link TurnToolSummaryTracker} 累积 delta 后生成，经 EventBus 落库广播，
     * 因此**多副本续传与刷新回放同样能拿到**（与 file_ready 同一路径）。
     */
    private void emitToolSummary(TurnToolSummaryTracker toolSummary, AgentEvent event) {
        try {
            if (event instanceof ToolCallEndEvent e) {
                toolSummary.onToolCallEnd(e);
            } else if (event instanceof ToolResultEndEvent e) {
                toolSummary.onToolResultEnd(e);
            }
        } catch (Exception e) {
            log.debug("tool summary synthesis failed: {}", e.getMessage());
        }
    }

    private void accumulatePresentFile(String toolCallId, String delta) {
        if (toolCallId == null) return;
        var buf = presentFileBuffers.computeIfAbsent(toolCallId, k -> new StringBuilder());
        synchronized (buf) {
            int room = PRESENT_FILE_BUFFER_MAX - buf.length();
            if (room <= 0) {
                log.warn("present_file result buffer overflow for toolCallId {}, dropping tail", toolCallId);
                return;
            }
            // 溢出保留头部、截去尾部（原实现单个 delta 超限会整段丢弃，桶里空无一字）：
            // file_id/file_name 等元数据固定在 JSON 前部，头部留存即可被 parseFileResult 正则救回
            if (delta.length() > room) {
                log.warn("present_file result buffer overflow for toolCallId {}, dropping tail", toolCallId);
                delta = delta.substring(0, room);
            }
            buf.append(delta);
        }
    }

    private void emitFileReadyViaEventBus(String sessionId, String replyId, String toolCallId,
                                         String forwardedPrefix) {
        var buf = presentFileBuffers.remove(toolCallId);
        if (buf == null) return;
        String json;
        synchronized (buf) { json = buf.toString(); }
        try {
            var node = parseFileResult(json);
            if (node != null && node.has("error")) {
                log.debug("file tool returned error: {}, skip file_ready", node.get("error").asText());
                return;
            }
            if (node == null || !node.has("file_id")) {
                log.warn("file tool result missing file_id, skip file_ready (sid={})", sessionId);
                return;
            }
            var fileId = node.get("file_id").asText();
            var payload = new LinkedHashMap<String, Object>();
            payload.put("type", "file_ready");
            payload.put("file_id", fileId);
            payload.put("file_name", node.get("file_name").asText());
            payload.put("mime_type", node.has("mime_type") ? node.get("mime_type").asText() : null);
            payload.put("size", node.has("size") ? node.get("size").asLong() : 0);
            // subpath-routing-design §3.1：经代理（有 X-Forwarded-Prefix）访问时下发从域名根
            // 解析的完整路径（客户端免拼前缀）；直连维持 /files/{id} 服务根相对路径（旧契约）
            payload.put("download_url", (forwardedPrefix == null ? "" : forwardedPrefix) + "/files/" + fileId);
            eventBus.emitSynthetic(sessionId, replyId, "file_ready",
                AgentEventSseSerializer.payload(payload));
            // 回写 reply_id 和 session_id 到 file_asset，供历史回放按消息/会话维度分发卡片
            fileAssetStore.updateReplyId(fileId, replyId);
            fileAssetStore.updateSessionId(fileId, sessionId);
            log.info("file_ready emitted for {} (sid={})", node.get("file_name").asText(), sessionId);
        } catch (Exception e) {
            log.warn("file_ready synthesis failed: {}", e.getMessage());
        }
    }

    /**
     * 产出文件工具结果文本 → JSON 节点。
     *
     * <p>全文解析失败时正则兜底：结果体可能超出 {@link #PRESENT_FILE_BUFFER_MAX} 被截尾
     * （大结果卸载/流式分片场景），而 file_id/file_name/mime_type/size 固定位于 JSON 前部，
     * 从残存头部提取即可救回下载卡片。
     */
    private com.fasterxml.jackson.databind.JsonNode parseFileResult(String json) {
        try {
            var node = JSON.readTree(json);
            if (node != null && node.isTextual()) {
                node = JSON.readTree(node.asText());
            }
            if (node != null && node.isObject()) {
                return node;
            }
        } catch (Exception ignored) {
            // 落到正则兜底
        }
        var node = JSON.createObjectNode();
        var id = java.util.regex.Pattern
            .compile("\"file_id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"").matcher(json);
        if (!id.find()) {
            return null;
        }
        node.put("file_id", id.group(1));
        var name = java.util.regex.Pattern
            .compile("\"file_name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        node.put("file_name", name.find() ? name.group(1) : "file");
        var mime = java.util.regex.Pattern
            .compile("\"mime_type\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (mime.find()) {
            node.put("mime_type", mime.group(1));
        }
        var size = java.util.regex.Pattern.compile("\"size\"\\s*:\\s*(\\d+)").matcher(json);
        node.put("size", size.find() ? Long.parseLong(size.group(1)) : 0L);
        return node;
    }

    // ===== write_file 输入截获 → KV =====

    private void accumulateWriteFileInput(String toolCallId, String delta) {
        if (toolCallId == null) return;
        var buf = writeFileInputBuffers.computeIfAbsent(toolCallId, k -> new StringBuilder());
        synchronized (buf) {
            if (buf.length() + delta.length() > 2 * 1024 * 1024) {
                log.warn("write_file input buffer overflow for toolCallId {}, dropping tail", toolCallId);
                return;
            }
            buf.append(delta);
        }
    }

    private void syncWriteFileToKv(String toolCallId, String userKey) {
        var buf = writeFileInputBuffers.remove(toolCallId);
        if (buf == null || userKey == null || userKey.isBlank()) return;
        String json;
        synchronized (buf) { json = buf.toString(); }
        try {
            var node = JSON.readTree(json);
            if (node == null) return;
            var pathNode = node.has("path") ? node.get("path")
                : node.has("file_path") ? node.get("file_path") : null;
            var contentNode = node.has("content") ? node.get("content") : null;
            if (pathNode == null || contentNode == null) {
                log.debug("write_file input missing path/content, skip KV sync (toolCallId={})", toolCallId);
                return;
            }
            var relPath = pathNode.asText();
            var content = contentNode.asText();
            relPath = relPath.replace('\\', '/');
            if (relPath.startsWith("/workspace/")) relPath = relPath.substring("/workspace/".length());
            else if (relPath.startsWith("/")) relPath = relPath.substring(1);
            if (relPath.contains("..")) {
                log.debug("write_file path contains .., skip KV sync: {}", relPath);
                return;
            }
            long maxKvBytes = props.file().presentMaxMb() * 1024L * 1024L;
            if (content.length() > maxKvBytes) {
                log.debug("write_file content exceeds {}MB limit, skip KV sync: {}", props.file().presentMaxMb(), relPath);
                return;
            }
            boolean ok = workspaceReader.writeWorkspaceFile(userKey, relPath, content);
            if (ok) {
                // 落库登记 (相对路径 → userKey)：present_file 读 KV 需要写入侧命名空间键，
                // 而工具侧 ctx.userId 在 Channel 链路下是网关 peer（D3）。落库以支持跨副本。
                fileAssetStore.upsertKvSyncKey(relPath, userKey);
                log.info("write_file → KV sync: {} ({} chars) for user {}", relPath, content.length(), userKey);
            }
        } catch (Exception e) {
            log.debug("write_file KV sync parse failed (toolCallId={}): {}", toolCallId, e.getMessage());
        }
    }

    // ===== 审计 =====

    private void audit(AgentEvent event, String sessionId) {
        if (event instanceof ToolCallStartEvent tc) {
            recordAudit(sessionId, tc.getToolCallName(), tc.getToolCallId(), "TOOL_CALL_START", event);
        } else if (event instanceof ToolCallEndEvent tc) {
            recordAudit(sessionId, tc.getToolCallName(), tc.getToolCallId(), "TOOL_CALL_END", event);
        } else if (event instanceof ToolResultStartEvent tc) {
            recordAudit(sessionId, tc.getToolCallName(), tc.getToolCallId(), "TOOL_RESULT_START", event);
        } else if (event instanceof ToolResultEndEvent tc) {
            recordAudit(sessionId, tc.getToolCallName(), tc.getToolCallId(), "TOOL_RESULT_END", event);
        }
    }

    private void recordAudit(String sessionId, String toolName, String toolCallId, String state, AgentEvent event) {
        try {
            toolAuditStore.record(sessionId, toolName, toolCallId, state,
                AgentEventSseSerializer.payload(event));
        } catch (Exception e) {
            log.debug("audit record skipped (sid={}): {}", sessionId, e.getMessage());
        }
    }

    // ===== SSE 工厂方法 =====

    private static ServerSentEvent<String> sessionCreatedSSE(String sessionId) {
        return ServerSentEvent.<String>builder()
            .data("{\"type\":\"session_created\",\"session_id\":"
                + AgentEventSseSerializer.jsonEsc(sessionId) + "}")
            .build();
    }

    /** 排队心跳帧（不进 EventBus、不落库）；带会话归属保证连接内任何一帧可归属（§3.2） */
    private static ServerSentEvent<String> waitingSSE(String sessionId) {
        return ServerSentEvent.<String>builder()
            .data("{\"type\":\"waiting\",\"session_id\":"
                + AgentEventSseSerializer.jsonEsc(sessionId) + "}")
            .build();
    }

    /** 错误帧（手工恒定键序，TurnFinalizer#abortSetup javadoc 的字节兼容约定）；带会话归属 */
    private static ServerSentEvent<String> errorSSE(String sessionId, String msg) {
        String payload = "{\"type\":\"error\",\"error\":" + AgentEventSseSerializer.jsonEsc(msg)
            + ",\"session_id\":" + AgentEventSseSerializer.jsonEsc(sessionId) + "}";
        return ServerSentEvent.<String>builder().data(payload).build();
    }

    /**
     * 用户停止终态帧（排队 turn 取消直接发本连接；执行中的 turn 经 EventBus 落库广播，
     * 该路径由写路径注入 session_id，直发路径在此手工带上）。
     */
    private static ServerSentEvent<String> cancelledSSE(String sessionId, String reason) {
        return ServerSentEvent.<String>builder()
            .data("{\"type\":\"cancelled\",\"reason\":\"" + reason + "\",\"session_id\":"
                + AgentEventSseSerializer.jsonEsc(sessionId) + "}")
            .build();
    }

    /** 停止请求检查周期（排队等待 loop 用）；未装配 TurnStopSettings 时取 1s。 */
    private long stopPollIntervalMs() {
        return turnStopSettings != null ? turnStopSettings.pollIntervalMs() : 1_000L;
    }

    private static boolean isTrailingSandboxTeardownError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("No active sandbox")) return true;
        }
        return false;
    }

    /** POST 请求体：message 或 fileIds 至少一项；userId、sessionId、model、skills、tools 可选 */
    public record ChatRequest(String message, String userId, String sessionId, List<String> fileIds,
                              String model, List<String> skills, List<String> tools) {
    }
}
