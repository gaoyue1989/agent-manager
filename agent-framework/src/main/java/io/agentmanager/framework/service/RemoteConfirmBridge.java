package io.agentmanager.framework.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.harness.agent.subagent.protocol.RemoteConfirmDecision;
import io.agentscope.harness.agent.subagent.protocol.RemotePendingConfirm;
import io.agentscope.harness.agent.subagent.task.AgentProtocolTaskClient;
import io.agentscope.harness.agent.subagent.task.RemoteTaskStatus;

/**
 * 远程确认桥（travel-fulfillment 设计 §5，M0 定形态 v1.3）。
 *
 * <p>职责（全部以快照轮询为唯一确认源，F15——PROPAGATE 不产生父流确认事件）：
 * <ol>
 *   <li><b>在途任务登记</b>：{@link #onAgentSpawnResult} 由工具结果抓取中间件调用
 *       （解析 agent_spawn 结果里的 task_id + 入参里的目标，endpoint 按声明清单匹配），
 *       登记 (session → endpoint, taskId)；注册点在 {@code HarnessAgentFactory.build}
 *       的 builder 链（{@link RemoteSpawnCaptureMiddleware}）。</li>
 *   <li><b>快照轮询</b>：{@link #pollOnce} 对在途任务周期 {@code GET /tasks/{id}}
 *       （AGENT_REMOTE_POLL_SECONDS，默认 5s；仅存在在途任务时轮询），
 *       发现 {@code status=awaiting_confirm} → 写 confirm_context 远程行
 *       （confirm_key='task:{task_id}'，remote_task 锚点 JSON）→ 前端确认卡 FIFO 排队；
 *       {@code status=error} 但 error 为传输类文案（404/HTTP>=400，SDK 客户端映射，
 *       见 {@link #isTransportError}）→ 保留登记重试、连续超限才放弃（§9）。</li>
 *   <li><b>决策路由</b>：{@link #routeDecision}（confirm 端点对远程行调用）：
 *       approve→ALLOW、reject→DENY 组 {@link RemoteConfirmDecision} 调
 *       {@code POST /tasks/{id}/resume}（M0：拒绝路径干净终止，F17）。</li>
 *   <li><b>终态唤醒</b>：resume 后异步监听子任务终态（快照轮询至终态）→ 合成消息驱动
 *       一次 lead 汇总 turn（走 {@link AgentRuntimeService#invokeStream} 管线，
 *       SessionEventBus emit 由本桥自行补——Controller 层职责，设计 §4 步骤 7 实现要点）。
 *       唤醒仅对「经确认卡批准/拒绝而续跑的远程任务」启用。</li>
 *   <li><b>TTL 超时治理</b>：{@link #sweepExpiredRemote}——远程行超独立 TTL 未消费 →
 *       自动 resume(DENY, reason=confirm_timeout) + 审计（用户不作为分支，设计 §5.4/§9）。</li>
 *   <li><b>并发防御</b>（§5.3）：同 session 已有未消费远程行又收到新挂起 → ERROR 审计
 *       （违反写任务串行规约的可观测信号），新任务照常排队（不悬挂、不丢弃）。</li>
 * </ol>
 *
 * <p>本服务未配置远程子 agent / 无在途任务时自然静默（登记表为空轮询即空转），
 * 存量服务零影响。决策/超时/唤醒均写 tool_audit（设计 §10）。
 */
@Service
public class RemoteConfirmBridge {

    private static final Logger log = LoggerFactory.getLogger(RemoteConfirmBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> HEADERS_TYPE =
        new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
        new TypeReference<>() {};

    /**
     * agent_spawn 结果文本中的任务句柄形态（SDK AgentSpawnTool："task_id: %s"）。
     * 只捕获 id 字符集（字母/数字/下划线/连字符）：demo 实测（2026-09-29 双进程）同步超时
     * 升格变体的结果文本里 task_id 紧邻 JSON 收尾引号，\S+ 会把引号一并捕获——带引号的
     * taskId 查快照恒 404 "task not found"，走传输错误静默重试（连续 60 次才告警），
     * 快照轮询永不生效。
     */
    private static final Pattern TASK_ID_PATTERN = Pattern.compile("task_id:\\s*([A-Za-z0-9_-]+)");

    /** 决策路由 confirm_key 前缀（与 ConfirmContextStore 远程行键一致） */
    public static final String CONFIRM_KEY_PREFIX = "task:";

    /** 远程确认卡片默认 env */
    static final String ENV_POLL_SECONDS = "AGENT_REMOTE_POLL_SECONDS";
    static final String ENV_REMOTE_HEADERS_JSON = "AGENT_REMOTE_HEADERS_JSON";
    static final int DEFAULT_POLL_SECONDS = 5;

    /** 终态监听上限：默认按 5s × 720 次 ≈ 1h，超过放弃（退化为下一用户 turn 消费交付，§13.9） */
    static final int MAX_TERMINAL_POLLS = 720;

    /**
     * 连续传输类错误判终态上限：默认 5s 轮询下 60 次 ≈ 5 分钟。快照轮询对传输类错误
     * 保留在途登记重试（§9），但任务持续失联（子服务下线/404）不能无限占住登记表——
     * 连续超限后放弃登记并审计（未消费确认卡仍由 TTL 治理兜底）。
     */
    static final int MAX_TRANSPORT_ERROR_POLLS = 60;

    private final ConfirmContextStore confirmContextStore;
    private final AgentRuntimeService runtimeService;
    private final SessionEventBus eventBus;
    private final SessionUserStore sessionUserStore;
    private final TurnLeaseStore turnLeaseStore;
    private final ToolAuditStore toolAuditStore;
    private final RemoteTaskClient taskClient;
    private final RemoteTaskRegistryStore registryStore;

    /** 在途任务登记表：sessionId|taskId → 引用（内存缓存；持久事实源为 remote_task_registry，
     *  副本经 rebuildFromRegistry 周期回填——lead 重启/其他副本均可接管轮询，设计 §18.2） */
    private final ConcurrentHashMap<String, RemoteTaskRef> inFlight = new ConcurrentHashMap<>();

    /** 远程调用认证头（AGENT_REMOTE_HEADERS_JSON，懒解析缓存） */
    private volatile Map<String, String> cachedHeaders;

    /** 轮询节流：@Scheduled 固定 tick + env 周期门（fixedDelay 注解需编译期常量） */
    private final long pollIntervalMs;
    private volatile long nextPollAt = 0;

    /** 传输类错误连续计数（sessionId|taskId → 次数；awaiting/终态/放弃时清除） */
    private final ConcurrentHashMap<String, Integer> transportErrorCounts = new ConcurrentHashMap<>();

    /** 已唤醒守卫（sessionId|taskId）：终态唤醒幂等的**进程内兜底**——跨副本权威在
     *  remote_task_registry 的 claimWake CAS（§18.2）；本守卫仅覆盖 registry 无行的
     *  历史/异常路径（v1.4 行为保持）。 */
    private final java.util.Set<String> wokenTasks = ConcurrentHashMap.newKeySet();

    /**
     * 终态监听/唤醒线程池：每任务一线程（守护线程，监听结束线程即退出）。
     * 不能用单线程池：每个 routeDecision 提交的监听独占线程最长
     * MAX_TERMINAL_POLLS × pollInterval（默认约 1h）+ wakeLead 抢租约再占 10s，
     * 串行化会把并发第二个远程决策的唤醒阻塞到小时级（§5.2/§5.3 同 session
     * 并发多远程挂起是设计内场景）。绝不占用 Reactor 调度线程做阻塞等待。
     */
    private final ExecutorService wakeExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofPlatform().name("remote-confirm-wake-", 0).daemon(true).factory());

    /** 远程任务客户端端口（测试 fake 注入；默认包一层 SDK AgentProtocolTaskClient） */
    public interface RemoteTaskClient {
        RemoteTaskStatus getStatus(String url, Map<String, String> headers, String taskId)
            throws IOException, InterruptedException;

        void resumeTask(String url, Map<String, String> headers, String taskId,
                        List<RemoteConfirmDecision> decisions) throws IOException, InterruptedException;
    }

    /** SDK 默认实现（薄封装，无状态） */
    static final class SdkRemoteTaskClient implements RemoteTaskClient {
        private final AgentProtocolTaskClient delegate = new AgentProtocolTaskClient();

        @Override
        public RemoteTaskStatus getStatus(String url, Map<String, String> headers, String taskId)
                throws IOException, InterruptedException {
            return delegate.getStatus(url, headers, taskId);
        }

        @Override
        public void resumeTask(String url, Map<String, String> headers, String taskId,
                               List<RemoteConfirmDecision> decisions)
                throws IOException, InterruptedException {
            delegate.resumeTask(url, headers, taskId, decisions);
        }
    }

    /** 在途任务引用 */
    record RemoteTaskRef(String sessionId, String service, String endpoint, String taskId,
                         Instant registeredAt) {
    }

    @Autowired
    public RemoteConfirmBridge(ConfirmContextStore confirmContextStore,
                               AgentRuntimeService runtimeService,
                               SessionEventBus eventBus,
                               SessionUserStore sessionUserStore,
                               TurnLeaseStore turnLeaseStore,
                               ToolAuditStore toolAuditStore,
                               RemoteTaskRegistryStore registryStore) {
        this(confirmContextStore, runtimeService, eventBus, sessionUserStore,
            turnLeaseStore, toolAuditStore, new SdkRemoteTaskClient(),
            pollIntervalMsFromEnv(), registryStore);
    }

    /** 测试构造：注入 fake RemoteTaskClient（不起真实 HTTP）；registry 直连 DataSource */
    RemoteConfirmBridge(ConfirmContextStore confirmContextStore,
                        AgentRuntimeService runtimeService,
                        SessionEventBus eventBus,
                        SessionUserStore sessionUserStore,
                        TurnLeaseStore turnLeaseStore,
                        ToolAuditStore toolAuditStore,
                        RemoteTaskClient taskClient) {
        this(confirmContextStore, runtimeService, eventBus, sessionUserStore,
            turnLeaseStore, toolAuditStore, taskClient, pollIntervalMsFromEnv());
    }

    /** 测试全参构造：轮询周期可注入（终态监听 sleep 用 1ms 加速） */
    RemoteConfirmBridge(ConfirmContextStore confirmContextStore,
                        AgentRuntimeService runtimeService,
                        SessionEventBus eventBus,
                        SessionUserStore sessionUserStore,
                        TurnLeaseStore turnLeaseStore,
                        ToolAuditStore toolAuditStore,
                        RemoteTaskClient taskClient,
                        long pollIntervalMs) {
        this(confirmContextStore, runtimeService, eventBus, sessionUserStore,
            turnLeaseStore, toolAuditStore, taskClient, pollIntervalMs, null);
    }

    /** 全参构造：registry store 可注入（测试 null = 兼容旧用例，登记/重建/唤醒 CAS 静默跳过） */
    RemoteConfirmBridge(ConfirmContextStore confirmContextStore,
                        AgentRuntimeService runtimeService,
                        SessionEventBus eventBus,
                        SessionUserStore sessionUserStore,
                        TurnLeaseStore turnLeaseStore,
                        ToolAuditStore toolAuditStore,
                        RemoteTaskClient taskClient,
                        long pollIntervalMs,
                        RemoteTaskRegistryStore registryStore) {
        this.confirmContextStore = confirmContextStore;
        this.runtimeService = runtimeService;
        this.eventBus = eventBus;
        this.sessionUserStore = sessionUserStore;
        this.turnLeaseStore = turnLeaseStore;
        this.toolAuditStore = toolAuditStore;
        this.taskClient = taskClient;
        this.registryStore = registryStore;
        this.pollIntervalMs = pollIntervalMs > 0 ? pollIntervalMs : DEFAULT_POLL_SECONDS * 1000L;
    }

    static long pollIntervalMsFromEnv() {
        var raw = System.getenv(ENV_POLL_SECONDS);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_POLL_SECONDS * 1000L;
        }
        try {
            var s = Long.parseLong(raw.trim());
            return s > 0 ? s * 1000L : DEFAULT_POLL_SECONDS * 1000L;
        } catch (NumberFormatException e) {
            return DEFAULT_POLL_SECONDS * 1000L;
        }
    }

    /** 远程行 confirm_key：'task:{task_id}'（设计 §5.1） */
    public static String confirmKeyFor(String taskId) {
        return CONFIRM_KEY_PREFIX + taskId;
    }

    // ===== 1. 在途任务登记（抓取中间件调用点；中间件注册在 HarnessAgentFactory.build builder 链） =====

    /**
     * agent_spawn 工具结果登记：解析结果文本里的 task_id，目标 endpoint 按声明清单匹配
     * （入参 agent_key；label 别名不可解析时告警跳过）。非远程/解析失败一律静默忽略。
     *
     * @param sessionId      规范会话 id（抓取中间件经 SessionKeyResolver 解析，Channel 链路非 gw-hash）
     * @param toolInput      agent_spawn 工具入参（agent_key/label/message/…）
     * @param toolResultText agent_spawn 工具结果文本（含 task_id: …）
     */
    public void onAgentSpawnResult(String sessionId, Map<String, Object> toolInput, String toolResultText) {
        try {
            if (sessionId == null || sessionId.isBlank()
                    || toolResultText == null || toolResultText.isBlank()) {
                return;
            }
            var taskId = extractTaskId(toolResultText);
            if (taskId == null) {
                return;   // 同步完成/本地子 agent：无后台任务句柄，与远程确认无关
            }
            var agentName = agentNameFromInput(toolInput);
            if (agentName == null) {
                log.warn("[RemoteConfirmBridge] spawn result carries task_id={} but input has no agent_key/label, "
                    + "skip registration (sid={})", taskId, sessionId);
                return;
            }
            var endpoint = resolveEndpoint(agentName);
            if (endpoint == null) {
                // 本地子 agent（无 endpoint 声明）：确认走父进程 HITL，与远程桥无关
                log.debug("[RemoteConfirmBridge] agent '{}' has no endpoint declaration, skip (sid={})",
                    agentName, sessionId);
                return;
            }
            var key = registryKey(sessionId, taskId);
            inFlight.put(key, new RemoteTaskRef(sessionId, agentName, endpoint, taskId, Instant.now()));
            // 持久登记（§18.2）：跨重启/跨副本重建源；fail-soft 不影响父流
            if (registryStore != null) {
                registryStore.register(sessionId, taskId, agentName, endpoint);
            }
            log.info("[RemoteConfirmBridge] registered in-flight remote task: sid={}, service={}, taskId={}, endpoint={}",
                sessionId, agentName, taskId, endpoint);
        } catch (Exception e) {
            // 登记失败不影响父流：快照轮询依赖登记，缺失时该任务将无人落卡（审计可查）
            log.error("[RemoteConfirmBridge] failed to register spawned task (sid={}): {}",
                sessionId, e.getMessage(), e);
        }
    }

    /** 解析 agent_spawn 结果文本中的 task_id（"task_id: xxx"） */
    static String extractTaskId(String resultText) {
        Matcher m = TASK_ID_PATTERN.matcher(resultText);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 工具入参 → 目标子 agent 名（agent_id 优先——SDK 2.0.3 AgentSpawnTool 实际 schema 主键，
     * demo 实测模型自然使用该参数名且任务正确路由；agent_key/agent/label 为别名兜底；均缺返回 null）。
     */
    static String agentNameFromInput(Map<String, Object> toolInput) {
        if (toolInput == null) {
            return null;
        }
        for (var field : List.of("agent_id", "agent_key", "agent", "label")) {
            var v = toolInput.get(field);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /** 声明清单匹配 endpoint（OafConfig.subAgents：agent 名 → 非空 endpoint） */
    String resolveEndpoint(String agentName) {
        var subAgents = runtimeService.oafConfig() != null ? runtimeService.oafConfig().subAgents() : null;
        if (subAgents == null) {
            return null;
        }
        return subAgents.stream()
            .filter(a -> agentName.equals(a.agent()) && a.endpoint() != null && !a.endpoint().isBlank())
            .map(io.agentmanager.framework.model.OafConfig.SubAgentConfig::endpoint)
            .findFirst().orElse(null);
    }

    private static String registryKey(String sessionId, String taskId) {
        return sessionId + "|" + taskId;
    }

    // ===== 2. 快照轮询（唯一确认源，F15） =====

    /** Spring 调度入口（固定 tick + env 周期门；仅存在在途任务时轮询） */
    @Scheduled(fixedDelay = 2000)
    public void scheduledPoll() {
        long now = System.currentTimeMillis();
        if (now < nextPollAt) {
            return;
        }
        nextPollAt = now + pollIntervalMs;
        try {
            pollOnce();
        } catch (Exception e) {
            log.warn("[RemoteConfirmBridge] poll failed: {}", e.getMessage());
        }
    }

    /** SDK 404 的 error 文案（AgentProtocolTaskClient.getStatus 字节码实证，2.0.3） */
    static final String SDK_TASK_NOT_FOUND_ERROR = "task not found";
    /** SDK 其余 HTTP>=400 的 error 文案形态："HTTP <code>: <body>"（同上字节码实证） */
    static final Pattern SDK_HTTP_ERROR_PATTERN = Pattern.compile("^HTTP \\d+: ");

    /**
     * 传输类错误判定：SDK AgentProtocolTaskClient.getStatus（2.0.3 字节码实证）对
     * HTTP 404 返回 (status="error", error="task not found")、其余 HTTP>=400 返回
     * (status="error", error="HTTP <code>: <body>")——这是「子服务瞬时不可达/路由未就绪」
     * 的传输层信号，不是任务终态失败；子任务真实失败时 member 侧写入的 error 文案为
     * 业务异常描述。二者必须区分：传输类错误保留在途登记重试（§9），真实失败才走终态治理
     * （否则一次瞬时 5xx 就会永久移除登记、丢弃未消费确认卡、发出假失败汇总）。
     */
    static boolean isTransportError(RemoteTaskStatus status) {
        if (status == null || !"error".equalsIgnoreCase(status.status())) {
            return false;
        }
        var err = status.error();
        return err != null
            && (SDK_TASK_NOT_FOUND_ERROR.equalsIgnoreCase(err.trim())
                || SDK_HTTP_ERROR_PATTERN.matcher(err).lookingAt());
    }

    /** 单轮快照轮询（测试可直接调用）：awaiting → 落远程行；传输类错误保登记重试；终态 → 清登记（+残留行治理） */
    void pollOnce() {
        if (inFlight.isEmpty()) {
            return;
        }
        var headers = remoteHeaders();
        for (var ref : List.copyOf(inFlight.values())) {
            var key = registryKey(ref.sessionId(), ref.taskId());
            RemoteTaskStatus status;
            try {
                status = taskClient.getStatus(ref.endpoint(), headers, ref.taskId());
            } catch (Exception e) {
                // 子服务不可达/超时：保留登记，下轮重试（§9 子服务不可达分支，不打断父流）
                log.warn("[RemoteConfirmBridge] status poll failed for task {} ({}): {}",
                    ref.taskId(), ref.endpoint(), e.getMessage());
                continue;
            }
            if (status == null) {
                continue;
            }
            if (status.isAwaitingConfirm()) {
                transportErrorCounts.remove(key);
                ensureRemoteConfirmRow(ref, status);
            } else if (isTransportError(status)) {
                // SDK 把 HTTP>=400/404 映射为 error 状态（isTransportError javadoc）：
                // 非任务终态，保留登记下轮重试；连续超限才放弃（失联任务不无限占住登记表）
                int consecutive = transportErrorCounts.merge(key, 1, Integer::sum);
                if (consecutive >= MAX_TRANSPORT_ERROR_POLLS) {
                    log.error("[RemoteConfirmBridge] task {} unreachable for {} consecutive polls "
                            + "(last error: {}), giving up in-flight registration (sid={})",
                        ref.taskId(), consecutive, status.error(), ref.sessionId());
                    transportErrorCounts.remove(key);
                    inFlight.remove(key);
                    if (registryStore != null) {
                        registryStore.markTerminal(ref.sessionId(), ref.taskId(), true);
                    }
                    // 未消费确认卡若已落库则保留，交 sweepExpiredRemote 的 TTL 治理兜底
                    audit(ref.sessionId(), "remote_confirm", ref.taskId(), "TRANSPORT_ERROR_GAVE_UP",
                        payloadJson(Map.of("confirm_key", confirmKeyFor(ref.taskId()),
                            "consecutive_errors", consecutive,
                            "last_error", String.valueOf(status.error()))));
                }
            } else if (status.isTerminalSuccess() || status.isTerminalFailure() || status.isCancelled()) {
                transportErrorCounts.remove(key);
                onTaskTerminal(ref, status);
            }
        }
    }

    /**
     * 登记重建（设计 §18.2 机制②）：从 remote_task_registry 回填内存登记表——
     * lead 重启、或同 session 的其他副本，均可由此接管在途任务的轮询/落卡/收割。
     * 多副本会重复轮询同一任务：GET /tasks/{id} 只读幂等，落卡由 findPending 前置
     * 判定幂等，唤醒由 claimWake CAS 恰好一次——重复轮询只增加读流量，不产生副作用。
     * initialDelay 让正常启动路径（spawn 进程内登记）先行，避免与首批轮询竞争。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void rebuildFromRegistry() {
        if (registryStore == null) {
            return;
        }
        try {
            // 上限 500（CR P2-7）：在途任务远低于此值；超限属异常积压，监控登记行数告警
            for (var row : registryStore.findInFlight(500)) {
                var key = registryKey(row.sessionId(), row.taskId());
                if (inFlight.containsKey(key)) {
                    continue;
                }
                inFlight.put(key, new RemoteTaskRef(row.sessionId(), row.service(),
                    row.endpoint(), row.taskId(), Instant.now()));
                log.info("[RemoteConfirmBridge] rebuilt in-flight registration from registry: "
                    + "sid={}, service={}, taskId={} (restart/multi-replica takeover)",
                    row.sessionId(), row.service(), row.taskId());
            }
        } catch (Exception e) {
            log.warn("[RemoteConfirmBridge] registry rebuild failed: {}", e.getMessage());
        }
    }

    /**
     * awaiting_confirm → 落 confirm_context 远程行（幂等：已有同键未消费行则跳过）。
     * 并发防御（§5.3）：同 session 已有**其他**未消费远程行 → ERROR 审计，新任务照常排队。
     */
    private void ensureRemoteConfirmRow(RemoteTaskRef ref, RemoteTaskStatus status) {
        var confirmKey = confirmKeyFor(ref.taskId());
        var existing = confirmContextStore.findPending(ref.sessionId(), confirmKey);
        if (existing.isPresent()) {
            return;   // 卡片已落，等待用户决策
        }

        // 串行规约违反观测信号：同 session 已有其他远程行未消费又来新挂起
        var others = confirmContextStore.findUnconsumedRemote(ref.sessionId()).stream()
            .filter(r -> !confirmKey.equals(r.confirmKey()))
            .toList();
        if (!others.isEmpty()) {
            log.error("[RemoteConfirmBridge] serial-rule violation: session {} already has {} unconsumed remote "
                + "confirm(s) ({}) while task {} newly awaits confirm — new task queued (FIFO), not dropped",
                ref.sessionId(), others.size(),
                others.stream().map(ConfirmContextStore.PendingConfirm::confirmKey).toList(),
                ref.taskId());
            audit(ref.sessionId(), "remote_confirm", ref.taskId(), "QUEUE_CONFLICT",
                payloadJson(Map.of("reason", "serial_rule_violation", "confirm_key", confirmKey,
                    "queued_behind", others.stream().map(ConfirmContextStore.PendingConfirm::confirmKey).toList())));
        }

        var pendingConfirms = status.pendingConfirms() != null ? status.pendingConfirms() : List.<RemotePendingConfirm>of();
        var toolCalls = new ArrayList<Map<String, Object>>(pendingConfirms.size());
        for (var pc : pendingConfirms) {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", pc.getToolCallId());
            m.put("name", pc.getToolName());
            m.put("input", parseInputJson(pc.getToolInputJson()));
            toolCalls.add(m);
        }
        // 落库锚点（§5.5）：不依赖 replyId 与父 state 匹配；child_reply_id 快照不可得为 null
        var remoteTask = new LinkedHashMap<String, Object>();
        remoteTask.put("service", ref.service());
        remoteTask.put("task_id", ref.taskId());
        remoteTask.put("tool_calls", toolCalls);
        remoteTask.put("child_reply_id", null);
        // 幽灵本地行清理（F20）：PROPAGATE 转发的子 ask 若已被父侧捕获为 confirm_key='local'
        // 行（Bridge 轮询先于捕获的时序兜底），该行确认不会转发远程任务——按 child
        // tool_call_id 对撞消费，保远程行唯一决策路由。fail-soft：清理失败照常落远程行。
        var childIds = toolCalls.stream()
            .map(t -> String.valueOf(t.get("id")))
            .filter(s -> !s.isBlank())
            .toList();
        try {
            int purged = confirmContextStore.consumeGhostLocalRows(ref.sessionId(), childIds);
            if (purged > 0) {
                log.info("[RemoteConfirmBridge] purged {} ghost local confirm row(s) for task {}", purged, ref.taskId());
                audit(ref.sessionId(), "remote_confirm", ref.taskId(), "GHOST_LOCAL_PURGED",
                    payloadJson(Map.of("confirm_key", confirmKey, "purged", purged,
                        "tool_call_ids", childIds)));
            }
        } catch (Exception e) {
            log.warn("[RemoteConfirmBridge] ghost local row purge failed for task {}: {}",
                ref.taskId(), e.getMessage());
        }
        try {
            // affected==1 = 新插入才记 CARD_QUEUED（多副本并发落卡竞态时更新方=2 不重复审计，§18.2 机制②）
            int affected = confirmContextStore.put(confirmKey, ref.sessionId(), toolCalls, null, null, null,
                MAPPER.writeValueAsString(remoteTask));
            if (affected == 1) {
                audit(ref.sessionId(), "remote_confirm", ref.taskId(), "CARD_QUEUED",
                    payloadJson(Map.of("confirm_key", confirmKey, "service", ref.service(),
                        "tools", toolCalls.stream().map(t -> t.get("name")).toList())));
            }
            log.info("[RemoteConfirmBridge] remote confirm card queued (affected={}): sid={}, taskId={}, tools={}",
                affected, ref.sessionId(), ref.taskId(), toolCalls.stream().map(t -> t.get("name")).toList());
        } catch (Exception e) {
            log.error("[RemoteConfirmBridge] failed to persist remote confirm row for task {}: {}",
                ref.taskId(), e.getMessage(), e);
        }
    }

    /** 终态：清登记；未消费远程行残留（任务自行结束）则 CAS 收口 + 审计。不触发唤醒。 */
    private void onTaskTerminal(RemoteTaskRef ref, RemoteTaskStatus status) {
        inFlight.remove(registryKey(ref.sessionId(), ref.taskId()));
        var confirmKey = confirmKeyFor(ref.taskId());
        var row = confirmContextStore.findPending(ref.sessionId(), confirmKey).orElse(null);
        if (row != null) {
            try {
                confirmContextStore.consume(row.sessionId(), row.confirmKey());
            } catch (Exception e) {
                log.debug("[RemoteConfirmBridge] stale row consume skipped for {}: {}", confirmKey, e.getMessage());
            }
            audit(ref.sessionId(), "remote_confirm", ref.taskId(), "TASK_TERMINAL",
                payloadJson(Map.of("confirm_key", confirmKey, "status", String.valueOf(status.status()),
                    "row", "discarded_unconsumed")));
        }
        log.info("[RemoteConfirmBridge] task {} reached terminal state: {}", ref.taskId(), status.status());
        // 后台收割兜底（travel-fulfillment F23 残留）：未经确认路由的后台任务（同步窗口
        // 超时升格等）终态此前只清登记不交付——收割全靠模型自觉调 task_output 不可靠
        //（demo 三轮实证）。此处确定性唤醒 lead 收割：提示先 task_output 取结果再汇总，
        // 与决策路径（resume 后 awaitTerminalAndWake）语义对齐；幂等由 wakeLead 守卫保证。
        var terminalDesc = describe(status);
        wakeExecutor.submit(() -> wakeLead(ref.sessionId(), ref.taskId(), terminalDesc, true));
    }

    // ===== 3. 决策路由（confirm 端点对远程行调用，§5.4） =====

    /**
     * 远程行决策路由：消费远程行（CAS）→ 组 RemoteConfirmDecision 调 /resume
     * （approve→ALLOW，reject→DENY；结果缺失的工具 fail-closed 按 DENY）→ 异步终态唤醒。
     *
     * @return 路由结果词表（confirm_key/task_id/decisions），供 confirm 端点透传
     * @throws AgentRuntimeService.ConfirmContextNotFoundException 行不存在/过期
     * @throws AgentRuntimeService.ConfirmAlreadyConsumedException 行已被消费（重复确认）
     */
    public Map<String, Object> routeDecision(String sessionId, String confirmKey,
                                             List<Map<String, Object>> results) {
        var row = confirmContextStore.findPending(sessionId, confirmKey)
            .orElseThrow(() -> new AgentRuntimeService.ConfirmContextNotFoundException(sessionId));
        // CAS 消费（防重复确认）：先占行再路由——resume 失败行已消费，用户可重试的语义
        // 由终态监听兜底（任务未续跑时保持挂起，卡片仍可重开），此处不回滚消费
        confirmContextStore.consume(row.sessionId(), row.confirmKey());

        var taskId = row.remoteTaskField("task_id");
        var service = row.remoteTaskField("service");
        if (taskId == null) {
            // 锚点损坏：无法路由，告警并按拒绝收口（不悬挂）
            log.error("[RemoteConfirmBridge] remote row {} has no task_id anchor (sid={})", confirmKey, sessionId);
            audit(sessionId, "remote_confirm", confirmKey, "ROUTE_FAILED",
                payloadJson(Map.of("reason", "missing_task_id_anchor")));
            throw new AgentRuntimeService.ConfirmContextNotFoundException(sessionId);
        }
        var endpoint = resolveEndpointForTask(sessionId, taskId, service);
        var decisions = buildDecisions(row, results);
        var approved = decisions.stream().anyMatch(RemoteConfirmDecision::isApproved);

        try {
            taskClient.resumeTask(endpoint, remoteHeaders(), taskId, decisions);
        } catch (IOException e) {
            log.error("[RemoteConfirmBridge] resume failed for task {} ({}): {}", taskId, endpoint, e.getMessage());
            audit(sessionId, "remote_confirm", taskId, approved ? "ALLOW" : "DENY",
                payloadJson(Map.of("confirm_key", row.confirmKey(), "error", String.valueOf(e.getMessage()))));
            throw new IllegalStateException("remote resume failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("remote resume interrupted", e);
        }
        audit(sessionId, "remote_confirm", taskId, approved ? "ALLOW" : "DENY",
            payloadJson(Map.of("confirm_key", row.confirmKey(), "service", String.valueOf(service),
                "decisions", decisions.stream().map(d -> Map.of(
                    "tool_call_id", String.valueOf(d.getToolCallId()),
                    "approved", d.isApproved())).toList())));
        log.info("[RemoteConfirmBridge] decision routed: sid={}, taskId={}, approved={}, decisions={}",
            sessionId, taskId, approved, decisions.size());

        // 摘在途登记（CR P2-3）：决策路径的终态由 awaitTerminalAndWake 专责监听唤醒，
        // 摘除后轮询路径不再重复处理同一任务（终态审计/后台收割话术双重触发）
        inFlight.remove(registryKey(row.sessionId(), taskId));
        // resume 后异步监听终态 → 唤醒 lead 汇总 turn（§4 步骤 7）
        var endpointRef = endpoint;
        wakeExecutor.submit(() -> awaitTerminalAndWake(row.sessionId(), taskId, endpointRef));

        var out = new LinkedHashMap<String, Object>();
        out.put("routed", "remote");
        out.put("confirm_key", row.confirmKey());
        out.put("task_id", taskId);
        out.put("decision", approved ? "ALLOW" : "DENY");
        return out;
    }

    /** 行内工具 → 决策列表：results 按 tool_call_id 匹配 confirmed，缺失 fail-closed DENY */
    static List<RemoteConfirmDecision> buildDecisions(ConfirmContextStore.PendingConfirm row,
                                                      List<Map<String, Object>> results) {
        var byCallId = new LinkedHashMap<String, Boolean>();
        if (results != null) {
            for (var r : results) {
                var id = r.get("tool_call_id") instanceof String s ? s : null;
                if (id != null) {
                    byCallId.put(id, Boolean.TRUE.equals(r.get("confirmed")));
                }
            }
        }
        var decisions = new ArrayList<RemoteConfirmDecision>();
        for (var tc : row.toolCalls()) {
            // 未匹配的挂起工具 fail-closed：按拒绝下发（不默默放行任何未决策的工具）
            decisions.add(new RemoteConfirmDecision(tc.getId(), byCallId.getOrDefault(tc.getId(), false)));
        }
        return decisions;
    }

    /**
     * endpoint 解析退回链（设计 §18.2 机制⑤）：内存登记表（spawn 副本进程内快照）
     * → remote_task_registry 持久快照（spawn 副本已亡时，跨副本确认路由仍可定位）
     * → OAF 声明清单按 service 名匹配。
     */
    private String resolveEndpointForTask(String sessionId, String taskId, String service) {
        var ref = inFlight.get(registryKey(sessionId, taskId));
        if (ref != null && ref.endpoint() != null && !ref.endpoint().isBlank()) {
            return ref.endpoint();
        }
        if (registryStore != null) {
            var persisted = registryStore.findEndpoint(sessionId, taskId);
            if (persisted.isPresent()) {
                return persisted.get();
            }
        }
        var declared = service != null ? resolveEndpoint(service) : null;
        if (declared != null) {
            return declared;
        }
        throw new IllegalStateException("no endpoint for remote task " + taskId + " (service=" + service + ")");
    }

    // ===== 4. 终态监听与 lead 唤醒（§4 步骤 7 / §5.4） =====

    /** 快照轮询至终态（有界），终态即驱动一次 lead 汇总 turn */
    private void awaitTerminalAndWake(String sessionId, String taskId, String endpoint) {
        var headers = remoteHeaders();
        for (int i = 0; i < MAX_TERMINAL_POLLS; i++) {
            RemoteTaskStatus status;
            try {
                Thread.sleep(pollIntervalMs);
                status = taskClient.getStatus(endpoint, headers, taskId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("[RemoteConfirmBridge] terminal watch poll failed for task {}: {}", taskId, e.getMessage());
                continue;
            }
            if (status == null) {
                continue;
            }
            if (isTransportError(status)) {
                // 子服务瞬时不可达（HTTP>=400/404 → SDK 映射 error 状态）：非任务终态，
                // 不发假失败汇总，继续监听至 MAX_TERMINAL_POLLS 有界放弃（§13.9）
                log.warn("[RemoteConfirmBridge] terminal watch hit transport error for task {}: {}",
                    taskId, status.error());
                continue;
            }
            if (status.isTerminalSuccess() || status.isTerminalFailure() || status.isCancelled()) {
                audit(sessionId, "remote_confirm", taskId, "TASK_TERMINAL",
                    payloadJson(Map.of("status", String.valueOf(status.status()), "wake", "lead_summary_turn")));
                wakeLead(sessionId, taskId, describe(status));
                return;
            }
        }
        // 超限放弃：任务仍由 member 自持，lead 在下一用户 turn 经 task_output/wait 收割（§13.9）
        log.warn("[RemoteConfirmBridge] terminal watch gave up for task {} after {} polls — "
            + "summary degrades to next user turn", taskId, MAX_TERMINAL_POLLS);
    }

    private static String describe(RemoteTaskStatus status) {
        if (status.isTerminalSuccess()) {
            return "成功完成";
        }
        if (status.isCancelled()) {
            return "已取消";
        }
        return "失败" + (status.error() != null && !status.error().isBlank() ? "：" + status.error() : "");
    }

    /**
     * 合成消息驱动一次 lead 汇总 turn：acquire 租约 → invokeStream → 事件写 durable SSE
     * （EventBus emit 为 Controller 层职责，本桥自行复刻——否则离线用户 /subscribe 不可见，C5）。
     */
    void wakeLead(String sessionId, String taskId, String terminalDesc) {
        wakeLead(sessionId, taskId, terminalDesc, false);
    }

    /**
     * 合成消息驱动一次 lead 汇总 turn：acquire 租约 → invokeStream → 事件写 durable SSE
     * （EventBus emit 为 Controller 层职责，本桥自行复刻——否则离线用户 /subscribe 不可见，C5）。
     *
     * <p>幂等守卫两层（设计 §18.2 机制④）：跨副本权威 = remote_task_registry 的
     * claimWake CAS（IN_FLIGHT→TERMINAL 认领即收口，affected=1 者唯一获得唤醒权）——
     * 决策路径（resume 后终态监听）与后台收割路径（onTaskTerminal 轮询终态）可能在不同
     * 副本先后到达同一终态，CAS 保证汇总 turn 恰好一次；registry 无行（历史/异常路径）
     * 时降级为进程内 wokenTasks 守卫（v1.4 行为）。认领后租约忙放弃的唤醒不重试
     * （与既有"下一用户 turn 降级收割"语义一致，§13.9）。
     *
     * @param harvestViaToolOutput true=后台收割路径：lead 上下文没有子任务过程，提示先
     *        task_output 取交付结果再汇总（确定性收割）；false=决策路径既有话术
     */
    void wakeLead(String sessionId, String taskId, String terminalDesc, boolean harvestViaToolOutput) {
        // ① 跨副本唤醒认领（registry 无行时走进程内兜底守卫；认领即收口——
        // claimWake 单语句 IN_FLIGHT→TERMINAL，无中间卡死态，CR P1-3）
        if (registryStore != null && registryStore.exists(sessionId, taskId)) {
            if (!registryStore.claimWake(sessionId, taskId)) {
                log.debug("[RemoteConfirmBridge] wake claim lost for task {} (sid={}), "
                    + "another replica/path is waking", taskId, sessionId);
                return;
            }
        } else if (!wokenTasks.add(registryKey(sessionId, taskId))) {
            log.debug("[RemoteConfirmBridge] wake already delivered for task {} (sid={}), skip",
                taskId, sessionId);
            return;
        }
        // ② 有界排队抢租约（与 ConfirmController.ACQUIRE_WAIT 同量级）；抢不到放弃本次唤醒
        var token = acquireLease(sessionId);
        if (token == null) {
            log.warn("[RemoteConfirmBridge] wake skipped: session {} lease busy (taskId={})", sessionId, taskId);
            return;
        }
        var lease = new TurnLeaseGuard(turnLeaseStore, sessionId, token);
        var replyId = java.util.UUID.randomUUID().toString();
        var finalized = new AtomicBoolean(false);
        try {
            eventBus.beginTurn(sessionId);
            var userId = sessionUserStore.findUserIdBySession(sessionId);
            var message = harvestViaToolOutput
                ? "远程子任务 " + taskId + " 已终态（" + terminalDesc
                    + "）。请先调用 task_output(task_id='" + taskId + "') 获取该任务的交付结果，"
                    + "再向用户汇总报告。"
                : "远程子任务 " + taskId + " 已终态（" + terminalDesc
                    + "）。请汇总该任务的交付结果并向用户报告，无需再次调用任何工具。";
            log.info("[RemoteConfirmBridge] waking lead summary turn: sid={}, taskId={}", sessionId, taskId);
            runtimeService.invokeStream(message, sessionId, userId)
                .subscribe(
                    frame -> emitFrame(sessionId, replyId, frame),
                    e -> {
                        log.warn("[RemoteConfirmBridge] wake turn error (sid={}, taskId={}): {}",
                            sessionId, taskId, e.getMessage());
                        emitSynthetic(sessionId, replyId, "error", Map.of(
                            "type", "error", "error", String.valueOf(e.getMessage())));
                        finalizeTurn(sessionId, lease, finalized);
                    },
                    () -> finalizeTurn(sessionId, lease, finalized));
        } catch (Exception e) {
            log.error("[RemoteConfirmBridge] wake turn setup failed (sid={}, taskId={}): {}",
                sessionId, taskId, e.getMessage(), e);
            finalizeTurn(sessionId, lease, finalized);
        }
    }

    /** invokeStream 词表帧 → EventBus 合成发射（payload 内嵌 type，与 AgentEventSseSerializer 同构） */
    private void emitFrame(String sessionId, String replyId, Map<String, Object> frame) {
        var type = frame.get("type") instanceof String s && !s.isBlank() ? s : "unknown";
        emitSynthetic(sessionId, replyId, type, frame);
    }

    private void emitSynthetic(String sessionId, String replyId, String type, Map<String, Object> payload) {
        try {
            eventBus.emitSynthetic(sessionId, replyId, type, MAPPER.writeValueAsString(payload));
        } catch (Exception e) {
            log.debug("[RemoteConfirmBridge] emit failed (sid={}, type={}): {}", sessionId, type, e.getMessage());
        }
    }

    private void finalizeTurn(String sessionId, TurnLeaseGuard lease, AtomicBoolean finalized) {
        if (finalized.compareAndSet(false, true)) {
            eventBus.closeSession(sessionId);
            lease.close();
        }
    }

    /** 有界抢租约（10s 排队，桥接进行中 turn 收尾；失败返回 null 放弃唤醒） */
    private String acquireLease(String sessionId) {
        long deadline = System.currentTimeMillis() + 10_000;
        var token = turnLeaseStore.tryAcquire(sessionId);
        while (token == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            token = turnLeaseStore.tryAcquire(sessionId);
        }
        return token;
    }

    // ===== 5. TTL 超时治理（§5.4：用户不作为分支） =====

    /** Spring 调度入口：每分钟扫描超远程 TTL 未消费的远程行 */
    @Scheduled(fixedDelay = 60_000)
    public void scheduledSweep() {
        try {
            sweepExpiredRemote();
        } catch (Exception e) {
            log.warn("[RemoteConfirmBridge] TTL sweep failed: {}", e.getMessage());
        }
    }

    /**
     * 超时治理：自动 resume(DENY, reason=confirm_timeout) + 审计 + CAS 收口。
     *
     * <p>CAS 消费**前置**（设计 §18.2 机制⑤，多副本 G3）：consume 的 0→1 原子性
     * 保证同一过期行只有一个副本（或进程）获得治理权，其余直接跳过——v1.4 的
     * "resume 后收口"在 replicas>1 时会重复发送 DENY。resume 失败仍保持收口
     * （与 routeDecision「resume 失败不回滚消费」语义一致），审计记
     * TIMEOUT_RESUME_FAILED 可查。
     */
    void sweepExpiredRemote() {
        var rows = confirmContextStore.findExpiredRemoteRows();
        for (var row : rows) {
            try {
                confirmContextStore.consume(row.sessionId(), row.confirmKey());
            } catch (Exception e) {
                // 已被其他副本消费/已过期：本轮治理权在别处，跳过
                log.debug("[RemoteConfirmBridge] timeout row consume lost for {} (governed elsewhere): {}",
                    row.confirmKey(), e.getMessage());
                continue;
            }
            var taskId = row.remoteTaskField("task_id");
            var service = row.remoteTaskField("service");
            try {
                var endpoint = resolveEndpointForTask(row.sessionId(),
                    taskId != null ? taskId : row.confirmKey(), service);
                var decisions = buildDecisions(row, List.of());   // 空 results → 全 DENY
                taskClient.resumeTask(endpoint, remoteHeaders(), taskId, decisions);
                audit(row.sessionId(), "remote_confirm", taskId, "DENY",
                    payloadJson(Map.of("confirm_key", row.confirmKey(), "reason", "confirm_timeout")));
                log.warn("[RemoteConfirmBridge] confirm timeout: auto-DENY sent for task {} (sid={})",
                    taskId, row.sessionId());
            } catch (Exception e) {
                // resume 失败仍收口行（消费已前置）：避免每轮重复 resume；子服务不可达时任务自持（fail-safe）
                log.error("[RemoteConfirmBridge] timeout resume failed for task {} (sid={}): {}",
                    taskId, row.sessionId(), e.getMessage());
                audit(row.sessionId(), "remote_confirm", taskId, "TIMEOUT_RESUME_FAILED",
                    payloadJson(Map.of("confirm_key", row.confirmKey(), "error", String.valueOf(e.getMessage()))));
            }
        }
    }

    // ===== 公共辅助 =====

    /** 在途任务数（测试/运维观察点） */
    int inFlightCount() {
        return inFlight.size();
    }

    /** 在途 taskId 集合（测试观察点：断言解析清洗后的任务句柄，如尾随引号剥离） */
    java.util.Set<String> inFlightTaskIds() {
        return inFlight.values().stream().map(RemoteTaskRef::taskId)
            .collect(java.util.stream.Collectors.toSet());
    }

    /** 远程调用认证头：AGENT_REMOTE_HEADERS_JSON（lead 声明注入，设计 §8 lead-1；懒解析缓存） */
    Map<String, String> remoteHeaders() {
        var cached = cachedHeaders;
        if (cached != null) {
            return cached;
        }
        var raw = System.getenv(ENV_REMOTE_HEADERS_JSON);
        Map<String, String> parsed = Map.of();
        if (raw != null && !raw.isBlank()) {
            try {
                parsed = MAPPER.readValue(raw, HEADERS_TYPE);
            } catch (Exception e) {
                log.error("[RemoteConfirmBridge] failed to parse {}: {}", ENV_REMOTE_HEADERS_JSON, e.getMessage());
            }
        }
        cachedHeaders = parsed;
        return parsed;
    }

    private static Map<String, Object> parseInputJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 审计（设计 §10：Bridge 决策/超时/唤醒均写审计——决策、task_id、reason） */
    private void audit(String sessionId, String toolName, String toolCallId, String state, String payloadJson) {
        try {
            toolAuditStore.record(sessionId, toolName,
                toolCallId != null ? toolCallId : "-", state,
                payloadJson != null ? payloadJson : "{}");
        } catch (Exception e) {
            log.debug("[RemoteConfirmBridge] audit skipped: {}", e.getMessage());
        }
    }

    private static String payloadJson(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        wakeExecutor.shutdownNow();
    }
}
