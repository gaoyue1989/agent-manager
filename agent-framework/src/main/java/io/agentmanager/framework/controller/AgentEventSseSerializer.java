package io.agentmanager.framework.controller;

import java.util.LinkedHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.DataBlockDeltaEvent;
import io.agentscope.core.event.DataBlockStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;

/**
 * AgentEvent → SSE JSON 序列化工具（/chat/stream 与长连接订阅端点共用，保证词表一致）。
 */
public final class AgentEventSseSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentEventSseSerializer() {
    }

    /** 将 AgentEvent 序列化为 SSE data 的 JSON 字符串（词表与原 /chat/stream 完全一致） */
    public static String payload(AgentEvent event) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", event.getType().name());
        payload.put("id", event.getId());

        if (event instanceof TextBlockDeltaEvent delta) {
            payload.put("delta", delta.getDelta());
        } else if (event instanceof ThinkingBlockDeltaEvent delta) {
            payload.put("delta", delta.getDelta());
        } else if (event instanceof DataBlockStartEvent) {
            // v2.0.0 无 mediaType 字段，仅转发块标识
        } else if (event instanceof DataBlockDeltaEvent delta) {
            payload.put("delta", delta.getDelta());
        } else if (event instanceof ToolCallStartEvent tc) {
            payload.put("toolName", tc.getToolCallName());
            payload.put("toolCallId", tc.getToolCallId());
        } else if (event instanceof ToolCallDeltaEvent delta) {
            payload.put("delta", delta.getDelta());
            payload.put("toolCallId", delta.getToolCallId());
            payload.put("toolCallName", delta.getToolCallName());
        } else if (event instanceof ToolCallEndEvent end) {
            payload.put("toolCallId", end.getToolCallId());
            payload.put("toolCallName", end.getToolCallName());
        } else if (event instanceof ToolResultStartEvent tr) {
            payload.put("toolCallId", tr.getToolCallId());
            payload.put("toolCallName", tr.getToolCallName());
        } else if (event instanceof ToolResultTextDeltaEvent tr) {
            payload.put("delta", tr.getDelta());
            payload.put("toolCallId", tr.getToolCallId());
            payload.put("toolCallName", tr.getToolCallName());
        } else if (event instanceof ToolResultEndEvent tr) {
            payload.put("state", tr.getState().name());
            payload.put("toolCallId", tr.getToolCallId());
            payload.put("toolCallName", tr.getToolCallName());
        } else if (event instanceof ModelCallStartEvent) {
            // v2.0.0 无 modelName 字段
        } else if (event instanceof ModelCallEndEvent mce) {
            if (mce.getUsage() != null) {
                payload.put("inputTokens", mce.getUsage().getInputTokens());
                payload.put("outputTokens", mce.getUsage().getOutputTokens());
                payload.put("totalTokens", mce.getUsage().getTotalTokens());
            }
        }

        // replyId / blockId 通用附注（长连接订阅多 run 区分）
        String replyId = extractReplyId(event);
        if (replyId != null) {
            payload.put("replyId", replyId);
        }
        String blockId = extractBlockId(event);
        if (blockId != null) {
            payload.put("blockId", blockId);
        }

        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String extractReplyId(AgentEvent event) {
        if (event instanceof io.agentscope.core.event.AgentStartEvent e) return e.getReplyId();
        if (event instanceof io.agentscope.core.event.AgentEndEvent e) return e.getReplyId();
        if (event instanceof TextBlockDeltaEvent e) return e.getReplyId();
        if (event instanceof ThinkingBlockDeltaEvent e) return e.getReplyId();
        if (event instanceof ToolCallStartEvent e) return e.getReplyId();
        if (event instanceof ToolCallDeltaEvent e) return e.getReplyId();
        if (event instanceof ToolCallEndEvent e) return e.getReplyId();
        if (event instanceof ToolResultStartEvent e) return e.getReplyId();
        if (event instanceof ToolResultTextDeltaEvent e) return e.getReplyId();
        if (event instanceof ToolResultEndEvent e) return e.getReplyId();
        if (event instanceof ModelCallStartEvent e) return e.getReplyId();
        if (event instanceof ModelCallEndEvent e) return e.getReplyId();
        return null;
    }

    private static String extractBlockId(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent e) return e.getBlockId();
        if (event instanceof ThinkingBlockDeltaEvent e) return e.getBlockId();
        if (event instanceof DataBlockDeltaEvent e) return e.getBlockId();
        return null;
    }

    /** 将原始文本序列化为 JSON 字符串（供 error 事件使用） */
    public static String jsonEsc(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            return "\"error\"";
        }
    }
}