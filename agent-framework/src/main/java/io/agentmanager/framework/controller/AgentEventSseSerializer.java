package io.agentmanager.framework.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.service.SessionEventStore;
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
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;

/**
 * AgentEvent → SSE JSON 序列化工具（对话单次流与订阅端点共用，保证词表一致）。
 */
public final class AgentEventSseSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentEventSseSerializer() {
    }

    /** 将 AgentEvent 序列化为 SSE data 的 JSON 字符串（词表前后端一致） */
    public static String payload(AgentEvent event) {
        return payload(event, null, null);
    }

    /**
     * 将 AgentEvent 序列化为 SSE data 的 JSON 字符串。
     * MCP Apps 扩展：TOOL_CALL_START 可携带 ui 元数据（{resourceUri, server}），
     * 由事件发源地（ChatStream/Confirm 控制器）查 McpToolRegistrar.resolveUiRef 后传入
     * （见 ChatStreamController.payloadForEvent，2026-09-17 经 approval-forms e2e 钉住）；
     * 无 UI 的工具传 null 保持原词表（向后兼容）。
     *
     * @param uiResourceUri ui:// 资源 URI；null 表示不带 UI 元数据
     * @param uiServer      资源所属 MCP server 名（供前端调代理端点）
     */
    public static String payload(AgentEvent event, String uiResourceUri, String uiServer) {
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
            if (uiResourceUri != null && uiServer != null) {
                payload.put("ui", Map.of("resourceUri", uiResourceUri, "server", uiServer));
            }
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
        } else if (event instanceof ToolResultDataDeltaEvent trd) {
            // SDK 原生二进制工具结果（file-upload-download-plan §10）：DataBlock →
            // Base64Source（data 内联）/ URLSource（url 引用），图片类前端可直接渲染。
            // 词表与 AgentRuntimeService.forwardEvent 的 tool_result_data_delta 对齐（snake_case）。
            payload.put("tool_call_id", trd.getToolCallId());
            payload.put("tool_call_name", trd.getToolCallName());
            var content = trd.getData();
            if (content instanceof io.agentscope.core.message.DataBlock dataBlock) {
                var source = dataBlock.getSource();
                if (source instanceof io.agentscope.core.message.Base64Source base64) {
                    payload.put("media_type", base64.getMediaType());
                    payload.put("data", base64.getData());
                } else if (source instanceof io.agentscope.core.message.URLSource urlSource) {
                    payload.put("media_type", urlSource.getMimeType());
                    payload.put("url", urlSource.getUrl());
                }
            }
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
        } else if (event instanceof io.agentscope.core.event.RequireUserConfirmEvent confirm) {
            // HITL 统一词条 permission_ask（snake_case，与 invokeStream 链路一致，见 hitl-permission-plan.md 6.4）
            var calls = confirm.getToolCalls().stream().map(tc -> {
                var c = new LinkedHashMap<String, Object>();
                c.put("tool_call_id", tc.getId());
                c.put("name", tc.getName());
                c.put("input", tc.getInput());
                // ToolUseBlock 无 getSuggestedRules()（javap 验证）——不输出 suggested_rules
                return c;
            }).toList();
            payload.put("type", "permission_ask");
            payload.put("tool_calls", calls);
            payload.put("reply_id", confirm.getReplyId());
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

    /** 将已组装的 Map（如 invokeStream/resumeWithConfirmStream 的词表帧）序列化为 JSON 字符串 */
    public static String payload(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 将 replyId 注入 payload JSON 顶层（A1 收口后的唯一实现，此前 SessionEventBus 与
     * SessionEventTailer 各持有一份相同拷贝）。
     *
     * <p>注入算法与收口前的读端逐行相同：readTree &rarr; isObject &rarr; {@code !has("replyId")}
     * &rarr; put（追加在 JSON 末尾）&rarr; writeValueAsString；任何异常吞掉、replyId 为
     * null/blank 时一律原串返回。
     *
     * <p>刻意<b>保留</b> {@code !node.has("replyId")} 顶层键判定、不做 {@code contains("replyId")}
     * 之类的子串捷径：delta/tool 入参里可能出现字面量 {@code "replyId"}，子串判定与顶层键
     * 判定在存量数据上不等价，会破坏帧字节一致性。写路径（emit/emitSynthetic）已先行注入，
     * 这里主要服务于<b>存量行</b>（升级前落库、p 内无 replyId）的读端兜底；随 Redis TTL
     * （retentionDays）耗尽存量后，兜底块成为死代码，届时可整体删除（后续独立小 commit）。
     *
     * <p>新数据自带 replyId 时走 {@code has("replyId")} 短路、原引用返回（零重序列化）——
     * 这是读端「零 JSON 重写」的实现基础。
     *
     * @param payload SSE data 的 JSON 字符串（可能不含 replyId）
     * @param replyId turn 标识；null/blank 不注入
     * @return 注入后的字符串；无法注入时返回原串
     */
    public static String withReplyId(String payload, String replyId) {
        if (payload == null || replyId == null || replyId.isBlank()) {
            return payload;
        }
        try {
            var node = MAPPER.readTree(payload);
            if (node != null && node.isObject() && !node.has("replyId")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("replyId", replyId);
                return MAPPER.writeValueAsString(node);
            }
            return payload;
        } catch (Exception e) {
            // 注入失败不阻塞主链路，使用原始 payload
            return payload;
        }
    }

    /**
     * EnvelopedEvent &rarr; SSE 帧（A1：SessionEventBus 与 SessionEventTailer 的私有 toSSE
     * 收口于此，两条路径必然同构，杜绝双份拷贝漂移）。
     *
     * <p>data 不是直通透传：新数据（写路径已注入 replyId）经 {@link #withReplyId} 短路原串
     * 返回；存量行（p 内无 replyId）由同一实现兜底注入——与收口前读端输出逐字节一致。
     * id 仍为回放游标 seq。
     */
    public static ServerSentEvent<String> toSseFrame(SessionEventStore.EnvelopedEvent e) {
        return ServerSentEvent.<String>builder()
            .data(withReplyId(e.payload(), e.replyId()))
            .id(String.valueOf(e.seq()))
            .build();
    }
}