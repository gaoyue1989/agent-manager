package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import io.opentelemetry.api.trace.Span;
import reactor.core.publisher.Flux;

/**
 * 工具调用输入输出追踪中间件：把每次工具调用的名称/描述/入参/出参写入
 * OtelTracingMiddleware 创建的 execute_tool span，补齐 SDK 内置仅记录
 * 工具名与 call id、不记内容的缺口。
 *
 * <p>两个属性（JSON 数组，按 toolCallId 归并，批次并行执行时含多个调用，
 * 属性名对齐 OTel GenAI 语义约定 registry；批次多调用为数组扩展）：
 * <ul>
 *   <li>{@code gen_ai.tool.call.arguments}：[{id, name, description, arguments}]</li>
 *   <li>{@code gen_ai.tool.call.result}：[{id, name, state, output}]</li>
 * </ul>
 *
 * <p>数据来源：
 * <ul>
 *   <li>名称/入参：ActingInput.toolCalls()（ToolUseBlock 带 id/name/args）</li>
 *   <li>描述：ToolUseBlock 不携带，从 onModelCall 的 ToolSchema 按名称缓存
 *       （同一中间件实例共享两个钩子；每次模型调用重刷，覆盖会话内工具变更）</li>
 *   <li>出参：onActing 事件流的 ToolResultTextDeltaEvent / ToolResultDataDeltaEvent
 *       增量累积，ToolResultEndEvent 补记最终 state</li>
 * </ul>
 *
 * <p>写入时机与覆盖范围：批次全部 End 后写一次（每次调用至多一次），缺失 End
 * 事件/异常时由 complete/error 兜底写入部分出参；<b>取消路径不保证写入</b>——
 * cancel 信号由外向内传播，外层 Otel 先 end span，本层的 setAttribute 会被
 * SDK 静默丢弃（中断执行可能缺出参）。无有效 span 时不做任何序列化。
 *
 * <p>执行位置：注册于 OtelTracingMiddleware 之后（内层），事件回调中 Span.current()
 * 即 execute_tool span。ModelIoTracingMiddleware 的 renderBlocks/truncate/toJson 复用。
 */
public class ToolCallTracingMiddleware implements MiddlewareBase {

    /** span 属性名（OTel GenAI 语义约定 registry；批次多调用为数组扩展） */
    static final String ATTR_TOOL_CALL_ARGUMENTS = "gen_ai.tool.call.arguments";
    static final String ATTR_TOOL_CALL_RESULT = "gen_ai.tool.call.result";

    /** 工具描述缓存：name → description（ToolUseBlock 不携带描述，从 ToolSchema 补） */
    private final Map<String, String> toolDescriptions = new ConcurrentHashMap<>();

    /** 工具出参累积器：文本/数据块增量 + 最终 state */
    private static final class OutputAcc {
        final StringBuilder output = new StringBuilder();
        String name;
        ToolResultState state;
    }

    /** 在模型调用侧采集工具描述（ToolSchema 仅在 onModelCall 可见；put 重刷以跟进会话内工具变更） */
    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        List<ToolSchema> tools = input.tools();
        if (tools != null) {
            for (ToolSchema tool : tools) {
                if (tool != null && tool.getName() != null && tool.getDescription() != null) {
                    toolDescriptions.put(tool.getName(), tool.getDescription());
                }
            }
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> toolCalls = input.toolCalls();
        int expected = toolCalls == null ? 0 : toolCalls.size();
        Map<String, OutputAcc> outputs = new LinkedHashMap<>();
        // 追踪属 best-effort：累积器跨信号共享依赖 Reactor 串行信号保证，不做加锁；
        // 按 End 事件计数判定批次完成（delta 事件会提前建条目，不能用 map.size 判断），
        // 全部 End 齐了才写（避免并行批次反复重序列化），缺失 End 事件由 complete/error 兜底
        AtomicBoolean flushed = new AtomicBoolean(false);
        AtomicInteger endedCount = new AtomicInteger();
        Runnable writeIo = () -> {
            if (!flushed.compareAndSet(false, true)) {
                return;
            }
            Span span = Span.current();
            if (!span.getSpanContext().isValid()) {
                return;
            }
            span.setAttribute(ATTR_TOOL_CALL_ARGUMENTS,
                ModelIoTracingMiddleware.truncate(renderInput(toolCalls)));
            span.setAttribute(ATTR_TOOL_CALL_RESULT,
                ModelIoTracingMiddleware.truncate(
                    ModelIoTracingMiddleware.toJson(renderOutput(outputs))));
        };

        return next.apply(input)
            .doOnNext(evt -> {
                if (evt instanceof ToolResultTextDeltaEvent e) {
                    var acc = accFor(outputs, e.getToolCallId(), e.getToolCallName());
                    if (e.getDelta() != null) {
                        acc.output.append(e.getDelta());
                    }
                } else if (evt instanceof ToolResultDataDeltaEvent e) {
                    if (e.getData() != null) {
                        var acc = accFor(outputs, e.getToolCallId(), e.getToolCallName());
                        acc.output.append(
                            ModelIoTracingMiddleware.renderBlocks(List.of(e.getData())));
                    }
                } else if (evt instanceof ToolResultEndEvent e) {
                    var acc = accFor(outputs, e.getToolCallId(), e.getToolCallName());
                    acc.state = e.getState();
                    if (endedCount.incrementAndGet() >= expected) {
                        writeIo.run();
                    }
                }
            })
            // End 事件缺失/空流时兜底；异常时写入已累积的部分出参，便于排查中断执行
            .doOnComplete(writeIo::run)
            .doOnError(e -> writeIo.run());
    }

    private static OutputAcc accFor(Map<String, OutputAcc> outputs, String toolCallId,
                                    String toolCallName) {
        var acc = outputs.computeIfAbsent(
            toolCallId == null ? "" : toolCallId, id -> new OutputAcc());
        if (acc.name == null && toolCallName != null) {
            acc.name = toolCallName;
        }
        return acc;
    }

    /** 工具调用入参 → [{id, name, description, arguments}] JSON */
    private String renderInput(List<ToolUseBlock> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> rendered = new ArrayList<>(toolCalls.size());
        for (ToolUseBlock tu : toolCalls) {
            if (tu == null) {
                continue;
            }
            String name = tu.getName();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", tu.getId());
            item.put("name", name);
            item.put("description", name == null ? null : toolDescriptions.get(name));
            item.put("arguments", JsonUtils.resolveToolCallArgsJson(tu));
            rendered.add(item);
        }
        return ModelIoTracingMiddleware.toJson(rendered);
    }

    /** 工具调用出参 → [{id, name, state, output}] 列表（writeIo 统一序列化/截断） */
    private static List<Map<String, Object>> renderOutput(Map<String, OutputAcc> outputs) {
        List<Map<String, Object>> rendered = new ArrayList<>(outputs.size());
        for (var entry : outputs.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.getKey());
            item.put("name", entry.getValue().name);
            item.put("state", entry.getValue().state != null
                ? entry.getValue().state.getValue() : null);
            item.put("output", entry.getValue().output.toString());
            rendered.add(item);
        }
        return rendered;
    }
}
