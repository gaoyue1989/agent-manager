package io.agentmanager.framework.service;

import java.util.function.BiConsumer;
import java.util.function.Function;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolResultState;

/**
 * 直返完成信号兜底（issue #492）：SDK 的直返收尾文本事件只遍历 TextBlock
 * （{@code publishReturnDirectTextEvents}），纯图片直返轮不会发出
 * {@code TEXT_BLOCK_DELTA.generateReason=TOOL_RETURN_DIRECT}——前端唯一权威的
 * 「本轮以直返结束」信号缺失，与空输出不可区分。本组件跟踪本轮事件流：直返工具
 * 成功结束且其后无模型回喂（短路成立）而 SDK 又未发信号时，在 AGENT_END 前合成
 * 一帧空文本信号（delta 为空，对按既有契约消费该帧的前端零渲染影响）。合成帧经
 * emitSynthetic 落库，订阅回放与续传自然一致。
 */
public final class DirectReturnSignalTracker {

    /** 与 SDK 信号帧同词表：空 delta 只承载 generateReason 标记，不含可渲染文本 */
    private static final String SIGNAL_PAYLOAD =
        "{\"type\":\"TEXT_BLOCK_DELTA\",\"delta\":\"\",\"generateReason\":\"TOOL_RETURN_DIRECT\"}";

    private final Function<String, McpToolRegistrar.ToolCallMeta> toolMeta;
    private final BiConsumer<String, String> emitSynthetic;

    private boolean directReturnSeen;
    private boolean shortCircuitCandidate;
    private boolean sdkSignalSeen;

    public DirectReturnSignalTracker(Function<String, McpToolRegistrar.ToolCallMeta> toolMeta,
                                     BiConsumer<String, String> emitSynthetic) {
        this.toolMeta = toolMeta;
        this.emitSynthetic = emitSynthetic;
    }

    public void onEvent(AgentEvent event) {
        if (event instanceof ToolCallStartEvent start) {
            var meta = toolMeta.apply(start.getToolCallName());
            if (meta != null && meta.directReturn()) {
                directReturnSeen = true;
            }
        } else if (event instanceof ToolResultEndEvent end) {
            if (directReturnSeen && end.getState() == ToolResultState.SUCCESS) {
                shortCircuitCandidate = true;
            }
        } else if (event instanceof ModelCallStartEvent) {
            shortCircuitCandidate = false;
        } else if (event instanceof TextBlockDeltaEvent delta) {
            var metadata = delta.getMetadata();
            if (metadata != null
                    && GenerateReason.TOOL_RETURN_DIRECT.name().equals(
                        metadata.get(AgentEvent.METADATA_GENERATE_REASON))) {
                sdkSignalSeen = true;
            }
        } else if (event instanceof AgentEndEvent
                && !isRemoteForwarded(event)
                && shortCircuitCandidate && !sdkSignalSeen) {
            emitSynthetic.accept("TEXT_BLOCK_DELTA", SIGNAL_PAYLOAD);
        }
    }

    /** 与 AgentEventSseSerializer.isRemoteForwarded 同判据（source 非空 = 远程子 agent 转发） */
    private static boolean isRemoteForwarded(AgentEvent event) {
        String source = event.getSource();
        return source != null && !source.isBlank();
    }
}
