package io.agentmanager.framework.service;

import java.util.Map;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.GenerateReason;

/**
 * 直返多块文本分隔（issue #494）：SDK {@code publishReturnDirectTextEvents} 对直返 Msg 的
 * 多个 TextBlock 逐块发送 TEXT_BLOCK_DELTA（每块一帧、块间无分隔），而 /history 的
 * {@link StateDataParser#extractContentText} 以 {@code "\n"} 拼接同一消息的非空 text 块——
 * 同一条直返消息刷新前后内容漂移（{@code "PartAPartB"} vs {@code "PartA\nPartB"}）。
 * 以 /history 拼接口径为真相源重写直返 delta：非空块之间插 {@code "\n"}，blank 块归一为
 * 空文本帧（history 口径中 blank 块不存在），块内文本零改动；非直返 delta、远程子 agent
 * 转发事件（source 非空，子 agent 会话的 history 不在 lead 消息口径内）原样透传。
 *
 * <p>turn 闭包局部组件（与 {@link DirectReturnSignalTracker} 同生命周期），重写后的事件经
 * {@code SessionEventBus.emit} 落库——SSE 实时流、订阅回放、/history 三面一致。
 */
public final class DirectReturnDeltaJoiner {

    private boolean nonBlankEmitted;

    public AgentEvent apply(AgentEvent event) {
        if (!(event instanceof TextBlockDeltaEvent delta) || isRemoteForwarded(event)) {
            return event;
        }
        Map<String, Object> metadata = event.getMetadata();
        if (metadata == null
                || !GenerateReason.TOOL_RETURN_DIRECT.name().equals(
                    metadata.get(AgentEvent.METADATA_GENERATE_REASON))) {
            return event;
        }
        String text = delta.getDelta() == null ? "" : delta.getDelta();
        if (text.isBlank()) {
            return text.isEmpty() ? event : rewrite(delta, metadata, "");
        }
        String joined = nonBlankEmitted ? "\n" + text : text;
        nonBlankEmitted = true;
        return joined.equals(text) ? event : rewrite(delta, metadata, joined);
    }

    private static TextBlockDeltaEvent rewrite(TextBlockDeltaEvent delta,
                                               Map<String, Object> metadata, String text) {
        var rewritten = new TextBlockDeltaEvent(delta.getId(), delta.getCreatedAt(),
            delta.getReplyId(), delta.getBlockId(), text);
        rewritten.withMetadata(metadata);
        return rewritten;
    }

    private static boolean isRemoteForwarded(AgentEvent event) {
        String source = event.getSource();
        return source != null && !source.isBlank();
    }
}
