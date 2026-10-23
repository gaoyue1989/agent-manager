package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.config.AguiProperties;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvent.Interrupt;
import io.agentscope.core.agui.model.ToolMergeMode;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ToolUseBlock;

/**
 * AG-UI pa 编码引擎（agui-native-parity-design §4 D2）。
 *
 * <p>AgentEvent → AG-UI 事件 JSON 数组的唯一编码点：SDK converter registry 承担标准词表
 * 映射（文本/推理/工具/子 agent/生命周期），平台自定义 converter 承担扩展语义
 * （HITL interrupt 登记）。产物即 Redis 信封的 {@code pa} 字段——实时响应流与断线回放
 * 读同一份字节，天然零漂移。
 *
 * <p>线程模型：实例无状态可复用；{@link AguiStreamContext} 按 run 创建
 * （blockId→messageId 映射、token 累计、pending interrupts 均为 run 级状态）。
 *
 * <p>不使用官方 {@code AguiAgentAdapter}/{@code AguiRequestProcessor}：二者的 resume
 * 协调与单 run 仲裁为进程内存态（设计 §2.1），多副本不可用；本类只取其**编码层**。
 */
public final class AguiEventCodec {

    private static final Logger log = LoggerFactory.getLogger(AguiEventCodec.class);

    private final AgentEventConverterRegistry registry;
    private final AguiEventEncoder encoder = new AguiEventEncoder();
    private final AguiAdapterConfig config;

    public AguiEventCodec(AguiProperties props) {
        this.config = AguiAdapterConfig.builder()
            .toolMergeMode(ToolMergeMode.AGENT_ONLY)
            .emitTokenUsage(props.emitTokenUsage())
            .emitToolCallArgs(props.emitToolCallArgs())
            .enableReasoning(props.enableReasoning())
            .emitSubagentEventsAsNative(props.emitSubagentEventsAsNative())
            // 平台自定义 converter：优先于 SDK 内置（自定义 PermissionConfirmConverter 覆盖
            // 同名内置转换器——后者按 SDK 内存协议产出，本平台由 confirm_context 承担恢复）
            .addEventConverter(permissionConfirmConverter())
            .build();
        this.registry = new AgentEventConverterRegistry(config.getEventConverters(),
            config.getEventEnrichers());
    }

    /**
     * HITL 权限确认转换器（agui-native-parity-design §4 D4）：不直接产出事件，而是把
     * RequireUserConfirmEvent 登记为 stream context 的 pending interrupts——RUN_FINISHED
     * 由 SDK lifecycle 转换器在 AGENT_END 时统一携带（interrupt outcome 挂在唯一的
     * RUN_FINISHED 上，避免双终态违反协议）。
     *
     * <p>interruptId 采用 {@code runId:toolCallId}（与官方文档示例 reply-1:call-1 同构；
     * resume[] 回传时按最后一个 ':' 解析回 toolCallId）。metadata 携带平台扩展键
     * （interruptKind/toolName/toolInput/replyId），并附 responseSchema 供官方客户端渲染
     * 审批表单。
     */
    static AgentEventConverter permissionConfirmConverter() {
        return new AgentEventConverter() {
            @Override
            public Set<Class<? extends AgentEvent>> eventTypes() {
                return Set.of(RequireUserConfirmEvent.class);
            }

            @Override
            public void convert(AgentEvent event, AguiStreamContext ctx) {
                var confirm = (RequireUserConfirmEvent) event;
                String runId = ctx.getRunId();
                for (ToolUseBlock tc : confirm.getToolCalls()) {
                    ctx.addInterrupt(new Interrupt(
                        runId + ":" + tc.getId(),
                        "tool_call",
                        "等待人工审批：" + tc.getName(),
                        tc.getId(),
                        Map.of("properties",
                            Map.of("approved", Map.of("type", "boolean")),
                            "required", List.of("approved")),
                        null,
                        Map.of("agentscope.interruptKind", "permission_confirm",
                            "toolName", String.valueOf(tc.getName()),
                            "toolInput", tc.getInput() == null ? Map.of() : tc.getInput(),
                            "replyId", String.valueOf(confirm.getReplyId()))));
                    ctx.markToolCallSuspended(tc.getId());
                }
            }
        };
    }

    /** 创建 run 级编码上下文（threadId=平台 sessionId、runId=turn 信封 id） */
    public AguiStreamContext newContext(String threadId, String runId) {
        return new AguiStreamContext(threadId, runId, config);
    }

    /**
     * 编码一个 AgentEvent 为 AG-UI 事件 JSON 数组。
     *
     * @return {@code [{"type":…},…]}（含首尾方括号）；无产出（事件无可映射 AG-UI 帧）
     *         或编码失败时返回 null（调用方落 native-only 行，读侧按 §6.3 降级）
     */
    public String encode(AgentEvent event, AguiStreamContext ctx) {
        try {
            ctx.beginEvent();
            List<AguiEvent> events = registry.convert(event, ctx);
            if (events == null || events.isEmpty()) {
                return null;
            }
            var sb = new StringBuilder("[");
            for (int i = 0; i < events.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(encoder.encodeToJson(events.get(i)));
            }
            return sb.append(']').toString();
        } catch (Exception e) {
            // R8：转换失败降级 native-only + 告警计数（MDC 级别日志，不打断执行链路）
            log.warn("[agui-codec] encode failed (type={}, sid={}): {}",
                event.getType(), ctx.getThreadId(), e.getMessage());
            return null;
        }
    }

    /** 合成帧 → CUSTOM 事件 JSON 数组（tool_call_summary / file_ready 等平台自有帧的对等承载） */
    public String encodeCustom(String threadId, String runId, String name, Object value) {
        try {
            var event = new AguiEvent.Custom(threadId, runId, name, value);
            return "[" + encoder.encodeToJson(event) + "]";
        } catch (Exception e) {
            log.warn("[agui-codec] encodeCustom failed (name={}, sid={}): {}",
                name, threadId, e.getMessage());
            return null;
        }
    }
}
