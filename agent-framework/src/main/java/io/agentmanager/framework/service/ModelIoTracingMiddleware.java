package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.util.JsonUtils;
import io.opentelemetry.api.trace.Span;
import reactor.core.publisher.Flux;

/**
 * 模型输入输出追踪中间件：把每次模型调用的实际输入（完整 messages）与实际输出
 * （正文/思考/工具调用，随流式事件累积）写入 OtelTracingMiddleware 创建的 chat span，
 * 补齐 SDK 内置 OtelTracingMiddleware 仅记录条数/token 用量、不记内容的缺口。
 *
 * <p>属性名遵循 OTel GenAI 语义约定：{@code gen_ai.input.messages} /
 * {@code gen_ai.output.messages}，值为 JSON 文本。
 *
 * <p>执行位置：注册于 OtelTracingMiddleware 之后（内层）。事件回调执行时
 * Span.current() 即 chat span（同 FrameworkTracingMiddleware 机制：Otel 通过
 * ContextPropagationOperator 把 span context 注入 Reactor Context）。
 * ModelCallEndEvent 在 chat span end()（外层 doOnComplete/doOnError）之前
 * 到达，故可安全在事件回调内补写属性。
 *
 * <p>写入时机与覆盖范围：首次写入落在 ModelCallEndEvent（异常时落在 doOnError），
 * 每次调用只写一次；<b>取消路径不保证写入</b>——cancel 信号由外向内传播，外层
 * Otel 先 end span，本层的 setAttribute 会被 SDK 静默丢弃（中断调用可能缺内容）。
 *
 * <p>内容截断：单条属性超过 {@link #MAX_CONTENT_CHARS} 截断并标注总长，
 * 防止超长 prompt/输出撑爆 span 属性（Jaeger 等后端对属性长度敏感）。
 * 无有效 span（如 OTEL_TRACES_EXPORTER=none）时不做任何序列化。
 */
public class ModelIoTracingMiddleware implements MiddlewareBase {

    /** span 属性名（OTel GenAI 语义约定） */
    static final String ATTR_INPUT_MESSAGES = "gen_ai.input.messages";
    static final String ATTR_OUTPUT_MESSAGES = "gen_ai.output.messages";

    /** 单条属性最大字符数，超出截断（输入含全量 prompt，极易超长） */
    static final int MAX_CONTENT_CHARS = 8192;

    /** 工具调用累积器：名称 + 参数增量拼接（ToolCallDeltaEvent 按 toolCallId 分片） */
    private record ToolCallAcc(String name, StringBuilder args) {}

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        // 输出随流式事件累积：正文 / 思考 / 工具调用
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        Map<String, ToolCallAcc> toolCalls = new LinkedHashMap<>();
        // 追踪属 best-effort：累积器跨信号共享依赖 Reactor 串行信号保证，不做加锁；
        // 每次调用只渲染/写入一次（end 事件或异常先到者得），无有效 span 时不序列化
        AtomicBoolean flushed = new AtomicBoolean(false);
        Runnable writeIo = () -> {
            if (!flushed.compareAndSet(false, true)) {
                return;
            }
            Span span = Span.current();
            if (!span.getSpanContext().isValid()) {
                return;
            }
            span.setAttribute(ATTR_INPUT_MESSAGES,
                truncate(renderInput(input.messages())));
            span.setAttribute(ATTR_OUTPUT_MESSAGES,
                truncate(renderOutput(text, thinking, toolCalls)));
        };

        return next.apply(input)
            .doOnNext(evt -> {
                if (evt instanceof TextBlockDeltaEvent e) {
                    if (e.getDelta() != null) {
                        text.append(e.getDelta());
                    }
                } else if (evt instanceof ThinkingBlockDeltaEvent e) {
                    if (e.getDelta() != null) {
                        thinking.append(e.getDelta());
                    }
                } else if (evt instanceof ToolCallDeltaEvent e) {
                    var acc = toolCalls.computeIfAbsent(
                        e.getToolCallId() == null ? "" : e.getToolCallId(),
                        id -> new ToolCallAcc(
                            e.getToolCallName() == null ? "" : e.getToolCallName(),
                            new StringBuilder()));
                    if (e.getDelta() != null) {
                        acc.args().append(e.getDelta());
                    }
                } else if (evt instanceof ModelCallEndEvent) {
                    writeIo.run();
                }
            })
            // End 事件缺失/空流时兜底；异常时写入已累积的部分输出，便于排查中断调用
            .doOnComplete(writeIo::run)
            .doOnError(e -> writeIo.run());
    }

    /** 输入 messages → [{"role","content"}] JSON；content 为文本化渲染（含工具调用/结果标注） */
    private static String renderInput(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> rendered = new ArrayList<>(messages.size());
        for (Msg m : messages) {
            if (m == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", String.valueOf(m.getRole()));
            item.put("content", renderBlocks(m.getContent()));
            rendered.add(item);
        }
        return toJson(rendered);
    }

    /** Msg 内容块 → 文本：正文直出，思考/工具调用/工具结果加标注（包内复用：ToolCallTracingMiddleware） */
    static String renderBlocks(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock tb) {
                if (tb.getText() != null) {
                    sb.append(tb.getText());
                }
            } else if (block instanceof ThinkingBlock th) {
                if (th.getThinking() != null) {
                    sb.append("[thinking] ").append(th.getThinking()).append('\n');
                }
            } else if (block instanceof ToolUseBlock tu) {
                sb.append("[tool_call ").append(tu.getName()).append("] ")
                    .append(JsonUtils.resolveToolCallArgsJson(tu)).append('\n');
            } else if (block instanceof ToolResultBlock tr) {
                sb.append("[tool_result ").append(tr.getName()).append("] ")
                    .append(renderBlocks(tr.getOutput())).append('\n');
            } else {
                sb.append('[').append(block.getClass().getSimpleName()).append(']');
            }
        }
        return sb.toString();
    }

    /** 累积的输出 → [{"role":"assistant","content","thinking","tool_calls"}] JSON */
    private static String renderOutput(StringBuilder text, StringBuilder thinking,
                                       Map<String, ToolCallAcc> toolCalls) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("role", "assistant");
        item.put("content", text.toString());
        if (thinking.length() > 0) {
            item.put("thinking", thinking.toString());
        }
        if (!toolCalls.isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>(toolCalls.size());
            for (ToolCallAcc acc : toolCalls.values()) {
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("name", acc.name());
                call.put("arguments", acc.args().toString());
                calls.add(call);
            }
            item.put("tool_calls", calls);
        }
        return toJson(List.of(item));
    }

    static String toJson(Object value) {
        try {
            return JsonUtils.getJsonCodec().toJson(value);
        } catch (Exception e) {
            // 序列化失败不阻断调用链，降级为 toString（内部仅简单 Map/List，不应失败）
            return String.valueOf(value);
        }
    }

    /** 超长截断，保留前缀并标注原始总长（包内复用：ToolCallTracingMiddleware） */
    static String truncate(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() <= MAX_CONTENT_CHARS) {
            return s;
        }
        return s.substring(0, MAX_CONTENT_CHARS)
            + "...(truncated, total " + s.length() + " chars)";
    }
}
