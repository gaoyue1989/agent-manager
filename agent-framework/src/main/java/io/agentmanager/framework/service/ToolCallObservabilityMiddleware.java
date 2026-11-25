package io.agentmanager.framework.service;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentmanager.framework.controller.AgentEventSseSerializer;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Flux;

/**
 * 工具调用观测中间件：指标计数 + 审计单一写点（覆盖 ChatStream / AGUI / A2A /
 * Agent Protocol 全部协议链路）。
 *
 * <p><b>审计单一写点</b>：此前 tool_audit_log 由 ChatStreamController / AguiRunController
 * 在各自 SSE 事件出口分别写入，A2A JSON-RPC 链路无事件出口而漏记；本中间件统一写入
 * （每调用 2 行——TOOL_CALL_START + TOOL_RESULT_END，后者携带 result_state），
 * controller 侧写入已移除。计数口径 = TOOL_CALL_START 行。
 * <b>行来源分工</b>：START 行在 onActing 入口由 ActingInput.toolCalls() 落
 * （payload 不落——审计仅元信息，工具名/id 已有列；恢复轮 ALLOWED/ASKING 状态
 * 调用已在挂起询问轮落过，跳过防双写）；RESULT 行在 onAgent 层由流上的
 * ToolResultEndEvent 驱动。onAgent 是整条 agent 事件流的汇出口（onActing 链内
 * 事件与 SDK 直发事件都流经它），终态写点放这里才能同时覆盖两类来源：工具执行
 * 内核的链内终态，以及 HITL 拒绝时 SDK 对被拒调用合成的 DENIED 终态——后者
 * 经 publishEvent 直发事件汇、绕过 onActing 链（部分拒绝时 SDK 不发
 * AllToolsDeniedEvent，全部拒绝时它也只是同一信息的链内冗余副本，不作为写点
 * 信号，否则与直发终态双写；issue #486）。
 *
 * <p><b>指标</b>：Micrometer 计数 {@code oaf.tool.calls}，标签 service（OAF slug）、
 * tool、result（success / error——非 SUCCESS 终态一律按 error 桶，与 Monitor
 * 问题口径一致）。tool 标签只认 toolkit 已注册工具名，未注册/幻觉名归 __unknown__
 * 桶（Meter 不回收，模型输出任意字符串不能无界新增序列）。Prometheus 暴露为
 * oaf_tool_calls_total，平台观测面板经 PromQL 聚合。
 *
 * <p><b>执行位置</b>：注册于 ToolFilterMiddleware / ProtocolDenyRulesMiddleware 内侧
 * （先注册者更外层），被过滤/DENY 短路的调用不经过本层——只计真实执行的调用。
 * 会话标识经 {@link SessionKeyResolver} 解析业务规范键（与 FrameworkTracingMiddleware
 * 同一实例、同一 memo 语义；Channel 链路 ctx.sessionId 是网关 gw-hash）。
 *
 * <p>写入全程 fail-soft，不影响工具执行。
 */
public class ToolCallObservabilityMiddleware implements MiddlewareBase {

    /** Micrometer 计数名（Prometheus：oaf_tool_calls_total） */
    static final String METRIC_NAME = "oaf.tool.calls";

    /** 未注册/幻觉工具名的指标标签归桶（Micrometer Meter 不回收，原样标签会无界增长序列） */
    static final String UNKNOWN_TOOL_LABEL = "__unknown__";

    private final MeterRegistry meterRegistry;
    private final ToolAuditStore toolAuditStore;
    private final SessionKeyResolver sessionKeyResolver;
    private final String serviceSlug;
    /** toolkit 已注册工具名（懒取——构造时 harness 内置工具尚未注册完）；null = 不归桶 */
    private final Supplier<Set<String>> registeredToolNames;

    public ToolCallObservabilityMiddleware(MeterRegistry meterRegistry, ToolAuditStore toolAuditStore,
                                          SessionKeyResolver sessionKeyResolver, String serviceSlug,
                                          Supplier<Set<String>> registeredToolNames) {
        this.meterRegistry = meterRegistry;
        this.toolAuditStore = toolAuditStore;
        this.sessionKeyResolver = sessionKeyResolver;
        this.serviceSlug = serviceSlug;
        this.registeredToolNames = registeredToolNames;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        String sessionId = canonicalSessionId(ctx);
        return next.apply(input)
            .doOnNext(evt -> {
                if (evt instanceof ToolResultEndEvent end && !AgentEventSseSerializer.isRemoteForwarded(evt)) {
                    boolean success = end.getState() == ToolResultState.SUCCESS;
                    count(end.getToolCallName(), success);
                    auditResult(end, sessionId, end.getToolCallName(), end.getToolCallId(),
                        success ? "success" : "error");
                }
            });
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        // START 行：入口直接落（ToolCallStartEvent 无法区分 HITL 恢复轮，恢复轮
        // ALLOWED/ASKING 状态调用已在挂起询问轮落过，按状态跳过防双写）
        String sessionId = canonicalSessionId(ctx);
        List<ToolUseBlock> calls = input.toolCalls();
        if (calls != null) {
            for (ToolUseBlock call : calls) {
                if (call != null && call.getName() != null && !isResumedCall(call)) {
                    auditStart(sessionId, call.getName(), call.getId());
                }
            }
        }
        return next.apply(input);
    }

    private String canonicalSessionId(RuntimeContext ctx) {
        return sessionKeyResolver != null && ctx != null
            ? sessionKeyResolver.canonicalSessionId(ctx)
            : ctx == null ? null : ctx.getSessionId();
    }

    /** HITL 恢复轮的调用（挂起询问轮已落过 START 行）：SDK 确认放行置 ALLOWED、拒绝保持 ASKING */
    private static boolean isResumedCall(ToolUseBlock call) {
        var state = call.getState();
        return state == ToolCallState.ALLOWED || state == ToolCallState.ASKING;
    }

    private void count(String toolName, boolean success) {
        if (meterRegistry == null || toolName == null) {
            return;
        }
        try {
            meterRegistry.counter(METRIC_NAME,
                "service", serviceSlug == null ? "unknown" : serviceSlug,
                "tool", metricToolLabel(toolName),
                "result", success ? "success" : "error").increment();
        } catch (Exception e) {
            // 指标 best-effort，不影响工具执行
        }
    }

    /** 指标 tool 标签只认 toolkit 已注册工具名，未注册/幻觉名归 __unknown__ 桶（审计行保留实名） */
    private String metricToolLabel(String toolName) {
        if (registeredToolNames == null) {
            return toolName;
        }
        try {
            Set<String> names = registeredToolNames.get();
            if (names != null && !names.contains(toolName)) {
                return UNKNOWN_TOOL_LABEL;
            }
        } catch (Exception e) {
            // 名单供给失败 fail-soft：保持原样标签
        }
        return toolName;
    }

    private void auditStart(String sessionId, String toolName, String toolCallId) {
        if (toolAuditStore == null) {
            return;
        }
        try {
            toolAuditStore.record(sessionId, toolName, toolCallId,
                "TOOL_CALL_START", null, null);
        } catch (Exception e) {
            // 审计 fail-soft，不影响工具执行
        }
    }

    private void auditResult(AgentEvent event, String sessionId, String toolName,
                             String toolCallId, String resultState) {
        if (toolAuditStore == null) {
            return;
        }
        try {
            toolAuditStore.record(sessionId, toolName, toolCallId,
                "TOOL_RESULT_END", resultState, AgentEventSseSerializer.payload(event));
        } catch (Exception e) {
            // 审计 fail-soft，不影响工具执行
        }
    }
}
