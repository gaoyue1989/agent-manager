package io.agentmanager.framework.service;

import java.util.List;
import java.util.function.Function;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
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
 * <b>行来源分工</b>（实测约束）：ToolCallStartEvent 在本中间件更外层才汇入事件流
 * （内层不可见，依赖它会导致 START 行漏记），故 START 行在 onActing 入口直接由
 * ActingInput.toolCalls() 落（payload 不落——审计仅元信息，工具名/id 已有列）；
 * ToolResultEndEvent 由工具执行内核发出、全链路可见，RESULT 行仍取事件侧（携带
 * 终态 result_state）。无终态的中断调用只剩 START 行（明细按「未落终态」展示）。
 *
 * <p><b>指标</b>：Micrometer 计数 {@code oaf.tool.calls}，标签 service（OAF slug）、
 * tool、result（success / error——非 SUCCESS 终态一律按 error 桶，与 Monitor
 * 问题口径一致）。Prometheus 暴露为 oaf_tool_calls_total，平台观测面板经 PromQL 聚合。
 *
 * <p><b>执行位置</b>：注册于 ToolFilterMiddleware / ProtocolDenyRulesMiddleware 内侧
 * （先注册者更外层），被过滤/DENY 短路的调用不经过本层——只计真实执行的调用。
 * 会话标识经 {@link SessionKeyResolver} 解析业务规范键（与 FrameworkTracingMiddleware
 * 同一实例、同一 memo 语义；Channel 链路 ctx.sessionId 是网关 gw-hash）。
 *
 * <p>解析在 hook 入口做一次（不进 doOnNext 热路径）；写入全程 fail-soft，不影响工具执行。
 */
public class ToolCallObservabilityMiddleware implements MiddlewareBase {

    /** Micrometer 计数名（Prometheus：oaf_tool_calls_total） */
    static final String METRIC_NAME = "oaf.tool.calls";

    private final MeterRegistry meterRegistry;
    private final ToolAuditStore toolAuditStore;
    private final SessionKeyResolver sessionKeyResolver;
    private final String serviceSlug;

    public ToolCallObservabilityMiddleware(MeterRegistry meterRegistry, ToolAuditStore toolAuditStore,
                                          SessionKeyResolver sessionKeyResolver, String serviceSlug) {
        this.meterRegistry = meterRegistry;
        this.toolAuditStore = toolAuditStore;
        this.sessionKeyResolver = sessionKeyResolver;
        this.serviceSlug = serviceSlug;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        String sessionId = sessionKeyResolver != null && ctx != null
            ? sessionKeyResolver.canonicalSessionId(ctx)
            : ctx == null ? null : ctx.getSessionId();
        // START 行：入口直接落（ToolCallStartEvent 在本层外才汇入事件流，内层不可见）
        List<ToolUseBlock> calls = input.toolCalls();
        if (calls != null) {
            for (ToolUseBlock call : calls) {
                if (call != null && call.getName() != null) {
                    auditStart(sessionId, call.getName(), call.getId());
                }
            }
        }
        return next.apply(input)
            .doOnNext(evt -> {
                if (evt instanceof ToolResultEndEvent end) {
                    boolean success = end.getState() == ToolResultState.SUCCESS;
                    count(end.getToolCallName(), success);
                    auditResult(end, sessionId, end.getToolCallName(), end.getToolCallId(),
                        success ? "success" : "error");
                }
            });
    }

    private void count(String toolName, boolean success) {
        if (meterRegistry == null || toolName == null) {
            return;
        }
        try {
            meterRegistry.counter(METRIC_NAME,
                "service", serviceSlug == null ? "unknown" : serviceSlug,
                "tool", toolName,
                "result", success ? "success" : "error").increment();
        } catch (Exception e) {
            // 指标 best-effort，不影响工具执行
        }
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
