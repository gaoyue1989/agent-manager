package io.agentmanager.framework.service;

import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agent.RuntimeContext;
import io.agentmanager.framework.config.AguiProperties;
import io.agentmanager.framework.model.AguiRunProps;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

/**
 * AG-UI 单次流运行服务（agui-migration-plan Phase 1，对齐 SessionStreamController 结构）。
 *
 * <p>POST /agui/run 职能承载：抢 Turn 租约（排队发 CUSTOM "oaf.waiting" 心跳）→
 * forwardedProps.fileIds 注入工作区 → extractLatestUserMessage（D7 + 要点 2）→
 * resume 闭环（load → 覆盖率校验 → CAS → RuntimeContext 注入，§5.1/§5.3）→
 * AguiAgentAdapter.run → AguiEventEncoder 编码 SSE → 终态释放租约。
 *
 * <p>与旧链路差异：不经 ChatUiChannel 网关（sessionId 直接取 threadId，D5）；
 * HITL 恢复走 resume[] + agui_interrupt 表（无状态跨副本）；审计走 AguiEvent 词表映射。
 */
@Service
public class AguiRunService {

    private static final Logger log = LoggerFactory.getLogger(AguiRunService.class);

    /** waiting 帧间隔：每 15s（防 Nginx 60s 读超时），对齐 SessionStreamController */
    private static final Duration WAITING_FRAME_INTERVAL = Duration.ofSeconds(15);
    /** 租约排队等待超时：120s 后仍未拿到 → RUN_ERROR 帧兜底 */
    private static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(120);

    private final io.agentscope.harness.agent.HarnessAgent agent;
    private final AguiAgentAdapter adapter;
    private final AguiEventEncoder encoder;
    private final TurnLeaseStore turnLeaseStore;
    private final ToolAuditStore toolAuditStore;
    private final UploadWorkspaceInjector workspaceInjector;
    private final AguiInterruptStore interruptStore;
    private final AguiProperties props;

    public AguiRunService(io.agentscope.harness.agent.HarnessAgent agent,
                          AguiAgentAdapter adapter,
                          AguiEventEncoder encoder,
                          TurnLeaseStore turnLeaseStore,
                          ToolAuditStore toolAuditStore,
                          UploadWorkspaceInjector workspaceInjector,
                          AguiInterruptStore interruptStore,
                          AguiProperties props) {
        this.agent = agent;
        this.adapter = adapter;
        this.encoder = encoder;
        this.turnLeaseStore = turnLeaseStore;
        this.toolAuditStore = toolAuditStore;
        this.workspaceInjector = workspaceInjector;
        this.interruptStore = interruptStore;
        this.props = props;
    }

    /**
     * 预检 + 组装（controller 阶段执行，可抛 HTTP 语义异常）：
     * 输入校验 → D7/要点 2 裁剪 → resume 闭环（load/覆盖率/CAS）→ RuntimeContext 组装。
     */
    public PreparedRun prepare(RunAgentInput input, String agentId) {
        if (agentId != null && !props.agentId().equals(agentId)) {
            throw new AguiRequestException(404,
                "unknown agent '" + agentId + "', expected '" + props.agentId() + "'");
        }
        if (input.getThreadId() == null || input.getThreadId().isBlank()) {
            throw new AguiRequestException(400, "threadId is required");
        }
        if (input.getRunId() == null || input.getRunId().isBlank()) {
            throw new AguiRequestException(400, "runId is required");
        }
        var threadId = input.getThreadId().trim();
        AguiRunProps runProps;
        try {
            runProps = AguiRunProps.from(input);
        } catch (IllegalArgumentException e) {
            throw new AguiRequestException(400, e.getMessage());
        }

        // ===== resume 闭环（§5.1 resume 约束 / §5.3 操作语义）=====
        Map<String, AguiEvent.Interrupt> resumeInterrupts = Map.of();
        if (input.hasResume()) {
            // 1) load：无 open interrupts（已消费/过期/不存在）→ 409
            var open = interruptStore.load(threadId);
            if (open.isEmpty()) {
                throw new AguiRequestException(409,
                    "no pending interrupt for thread '" + threadId + "' (already consumed or expired)");
            }
            // 2) 覆盖率校验：部分覆盖 → 400（否则挂起 tool_use 拿不到 ConfirmResult，行为未定义）
            var uncovered = interruptStore.uncoveredInterruptIds(open, input.getResume());
            if (!uncovered.isEmpty()) {
                throw new AguiRequestException(400,
                    "resume[] must cover all open interrupts, missing: " + uncovered);
            }
            // 3) CAS 消费：失败（并发重复）→ 409
            if (!interruptStore.consume(threadId)) {
                throw new AguiRequestException(409,
                    "interrupt for thread '" + threadId + "' already consumed");
            }
            resumeInterrupts = AguiInterruptStore.toInterruptMap(open);
        }

        // ===== RuntimeContext：sessionId=threadId（D5），userId=forwardedProps ?? webui =====
        var ctxBuilder = RuntimeContext.builder()
            .sessionId(threadId)
            .userId(runProps.userId())
            // UiContext 注入（要点 6）：middleware onSystemPrompt 按此 key 查 ui_context 表
            .put(io.agentmanager.framework.agui.OafAguiMiddleware.RUNTIME_UI_CONTEXT_SESSION_KEY, threadId);
        if (!resumeInterrupts.isEmpty()) {
            // resume 元数据注入（adapter resumeInterrupts 读取此 key 构造 ConfirmResult 消息）
            ctxBuilder.put(AguiAgentAdapter.RUNTIME_CONTEXT_RESUME_INTERRUPTS_KEY, resumeInterrupts);
        }
        var runtimeContext = ctxBuilder.build();
        return new PreparedRun(threadId, input.getRunId(), effectiveInput(input), runtimeContext, runProps);
    }

    /** SSE 主流：租约排队（waiting CUSTOM 帧）→ adapter.run → encode 直吐 → 终态释放 */
    public Flux<ServerSentEvent<String>> stream(PreparedRun prepared) {
        var threadId = prepared.threadId();
        var runId = prepared.runId();
        return Flux.<ServerSentEvent<String>>create(sink -> {
            // ===== 抢 Turn 租约（等待式，对齐 SessionStreamController）=====
            var token = turnLeaseStore.tryAcquire(threadId);
            long deadline = System.currentTimeMillis() + ACQUIRE_TIMEOUT.toMillis();
            while (token == null && System.currentTimeMillis() < deadline) {
                // 排队心跳：CUSTOM oaf.waiting（出现在 RUN_STARTED 之前，R7 spike 验证客户端解析预期）
                sink.next(frame(customEvent(threadId, runId, "oaf.waiting", Map.of("queued", true))));
                try {
                    Thread.sleep(WAITING_FRAME_INTERVAL.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sink.complete();
                    return;
                }
                token = turnLeaseStore.tryAcquire(threadId);
            }
            if (token == null) {
                // 排队超时兜底：RUN_ERROR（无前置 RUN_STARTED，随 R7 spike 验证；不通过则降级 HTTP 409）
                sink.next(frame(runError(threadId, runId,
                    "turn_in_progress: thread '" + threadId + "' has an active turn and queue timeout reached")));
                sink.complete();
                return;
            }

            // ===== 启动续租（绑定 turn 执行器生命周期）=====
            var lease = new TurnLeaseGuard(turnLeaseStore, threadId, token);

            // ===== 上传文件注入工作区（隔离键=threadId，对齐 SessionStreamController sessionId 用法）=====
            for (var fileId : prepared.props().fileIds()) {
                try {
                    workspaceInjector.injectToWorkspace(fileId, threadId);
                } catch (Exception e) {
                    log.warn("fileIds workspace inject failed (fileId={}): {}", fileId, e.getMessage());
                }
            }

            var runtimeContext = prepared.runtimeContext();
            var agentSubscription = adapter.run(prepared.input(), runtimeContext)
                .subscribe(
                    event -> handleAguiEvent(sink, event, prepared, lease),
                    e -> {
                        log.warn("agui run stream error (threadId={}): {}", threadId, e.getMessage());
                        if (isTrailingSandboxTeardownError(e)) {
                            // 对齐旧链路：沙箱收尾期异常忽略，业务事件已全部发出
                            log.info("ignore trailing sandbox teardown error (threadId={})", threadId);
                        } else {
                            sink.next(frame(runError(threadId, runId, errorMessage(e))));
                        }
                        lease.release();
                        sink.complete();
                    },
                    () -> {
                        // 正常完成（含 RUN_FINISHED(interrupt) HITL 暂停点）：租约幂等兜底释放
                        lease.release();
                        sink.complete();
                    });

            sink.onCancel(() -> {
                // 客户端断开：终止 agent 管道 + 释放租约（对齐 SessionStreamController）
                log.info("client disconnected, cancelling agui run (threadId={})", threadId);
                try {
                    agent.interrupt(runtimeContext);
                } catch (Exception e) {
                    log.warn("agent interrupt on disconnect failed: {}", e.getMessage());
                }
                agentSubscription.dispose();
                lease.release();
            });
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** stop 端点：中断该 thread 的在跑执行段（agentId 已由 controller 校验） */
    public Map<String, Object> stop(String threadId) {
        var ctx = RuntimeContext.builder()
            .sessionId(threadId)
            .userId("webui")
            .put(io.agentmanager.framework.agui.OafAguiMiddleware.RUNTIME_UI_CONTEXT_SESSION_KEY, threadId)
            .build();
        agent.interrupt(ctx);
        return Map.of("threadId", threadId, "stopped", true);
    }

    /** 单帧处理：审计 + RUN_FINISHED(interrupts) 持久化 + SSE 直吐 */
    private void handleAguiEvent(FluxSink<ServerSentEvent<String>> sink, AguiEvent event,
                                 PreparedRun prepared, TurnLeaseGuard lease) {
        audit(event, prepared.threadId());
        // HITL 暂停点：RUN_FINISHED(interrupts) → 元数据落库（跨副本恢复）+ 释放租约（执行段结束）
        if (event instanceof AguiEvent.RunFinished finished
            && finished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome outcome) {
            var interrupts = outcome.interrupts();
            // enrich() 为同步函数，JDBC 不得内联（要点 7）：fire-and-forget 异步落库
            var threadId = prepared.threadId();
            var runId = prepared.runId();
            reactor.core.publisher.Mono.fromRunnable(() ->
                    interruptStore.persist(threadId, interrupts, runId))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
            lease.release();
        }
        sink.next(frame(event));
    }

    /** 工具事件审计（AguiEvent 词表映射，异步批量仅元信息，失败静默——对齐旧链路） */
    private void audit(AguiEvent event, String threadId) {
        try {
            if (event instanceof AguiEvent.ToolCallStart tc) {
                toolAuditStore.record(threadId, tc.toolCallName(), tc.toolCallId(),
                    "TOOL_CALL_START", encoder.encodeToJson(tc));
            } else if (event instanceof AguiEvent.ToolCallEnd te) {
                toolAuditStore.record(threadId, null, te.toolCallId(),
                    "TOOL_CALL_END", encoder.encodeToJson(te));
            } else if (event instanceof AguiEvent.ToolCallResult tr) {
                toolAuditStore.record(threadId, null, tr.toolCallId(),
                    "TOOL_RESULT", encoder.encodeToJson(tr));
            }
        } catch (Exception e) {
            log.debug("agui audit record skipped (threadId={}): {}", threadId, e.getMessage());
        }
    }

    /**
     * D7 + Phase 1 要点 2 的输入裁剪：
     * <ul>
     *   <li>resume run（请求携带 resume[]）：一律不下发用户文本——CopilotKit 发全量本地历史，
     *       触发 HITL 的 user 消息仍在其中，再下发即与 SDK 记忆重复（R3 变体）；仅携带 resume
     *       转换结果（adapter toMsgList 从空 messages + resume[] 构造 ConfirmResult 消息）。</li>
     *   <li>普通 run：对齐上游 AguiRequestProcessor.extractLatestUserMessage（:307）——
     *       末尾 assistant 轮之后的 follow-up 消息；无 follow-up（regenerate 流）时取
     *       末尾 assistant 前最后一条 user 消息。</li>
     * </ul>
     */
    private RunAgentInput effectiveInput(RunAgentInput input) {
        if (input.hasResume()) {
            return rebuild(input, List.of());
        }
        var messages = input.getMessages();
        if (messages == null || messages.isEmpty()) {
            return input;
        }
        int lastAssistantIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equalsIgnoreCase(messages.get(i).getRole())) {
                lastAssistantIdx = i;
                break;
            }
        }
        if (lastAssistantIdx < 0) {
            // 无 assistant 轮（新会话首轮）：全量即 follow-up，原样传（converter 转换后 agent
            // 记忆以服务端为准，多条 user 输入场景由前端保证最后一条为新输入；对齐上游 return input）
            return input;
        }
        List<io.agentscope.core.agui.model.AguiMessage> after =
            lastAssistantIdx < messages.size() - 1
                ? List.copyOf(messages.subList(lastAssistantIdx + 1, messages.size()))
                : List.of();
        if (after.isEmpty()) {
            for (int i = lastAssistantIdx - 1; i >= 0; i--) {
                if ("user".equalsIgnoreCase(messages.get(i).getRole())) {
                    after = List.of(messages.get(i));
                    break;
                }
            }
        }
        if (after.isEmpty()) {
            return rebuild(input, List.of());
        }
        return rebuild(input, after);
    }

    /** 以原 input 的非 messages 字段重建 RunAgentInput */
    private RunAgentInput rebuild(RunAgentInput input,
                                  List<io.agentscope.core.agui.model.AguiMessage> messages) {
        return RunAgentInput.builder()
            .threadId(input.getThreadId())
            .runId(input.getRunId())
            .messages(messages)
            .tools(input.getTools())
            .context(input.getContext())
            .state(input.getState())
            .forwardedProps(input.getForwardedProps())
            .resume(input.getResume())
            .build();
    }

    // ===== 帧构造 =====

    private AguiEvent customEvent(String threadId, String runId, String name, Object value) {
        return new AguiEvent.Custom(threadId, runId, name, value, null, null);
    }

    private AguiEvent runError(String threadId, String runId, String message) {
        return new AguiEvent.RunError(threadId, runId, message, null, null, null);
    }

    /** encodeToJson 带前导空格 → Spring SSE "data:" 前缀拼接后即标准 "data: {...}" */
    private ServerSentEvent<String> frame(AguiEvent event) {
        return ServerSentEvent.<String>builder().data(encoder.encodeToJson(event)).build();
    }

    private static String errorMessage(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** 尾部沙箱收尾错误识别（对齐 SessionStreamController） */
    private static boolean isTrailingSandboxTeardownError(Throwable e) {
        for (var t = e; t != null; t = t.getCause()) {
            var msg = t.getMessage();
            if (msg != null && msg.contains("No active sandbox")) {
                return true;
            }
        }
        return false;
    }

    /** prepare 产物（input=裁剪后有效输入，props=forwardedProps 解析） */
    public record PreparedRun(String threadId, String runId, RunAgentInput input,
                              RuntimeContext runtimeContext, AguiRunProps props) {
    }

    /** 预检失败（HTTP 语义）——controller 映射 4xx JSON */
    public static class AguiRequestException extends RuntimeException {
        private final int status;

        public AguiRequestException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
