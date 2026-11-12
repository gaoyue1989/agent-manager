package io.agentmanager.framework.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.service.SessionEventStore;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
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

    /** 将 AgentEvent 序列化为 SSE data 的 JSON 字符串（不带会话归属；审计留痕等旁路面使用） */
    public static String payload(AgentEvent event) {
        return payload(event, null, null, null);
    }

    /**
     * 将 AgentEvent 序列化为 SSE data 的 JSON 字符串，顶层携带 session_id
     * （chat-trace-observability-design §3.2 构建期注入：LinkedHashMap 直接 put，不做
     * readTree 重写——delta 帧 token 热路径零额外成本）。sessionId 为 null 时与 1 参
     * 版本逐字节一致。
     */
    public static String payload(AgentEvent event, String sessionId) {
        return payload(event, sessionId, null, null);
    }

    /** 不带会话归属的 ui 元数据覆写（向后兼容保留；对话链路请用 4 参版本） */
    public static String payload(AgentEvent event, String uiResourceUri, String uiServer) {
        return payload(event, null, uiResourceUri, uiServer);
    }

    /**
     * 将 AgentEvent 序列化为 SSE data 的 JSON 字符串。
     * MCP Apps 扩展：TOOL_CALL_START 可携带 ui 元数据（{resourceUri, server}），
     * 由事件发源地（ChatStream/Confirm 控制器）查 McpToolRegistrar.resolveUiRef 后传入
     * （见 ChatStreamController.payloadForEvent，2026-09-17 经 approval-forms e2e 钉住）；
     * 无 UI 的工具传 null 保持原词表（向后兼容）。
     *
     * @param sessionId     会话归属（chat-trace-observability-design §3：每帧顶层恒带
     *                      session_id）；null 表示不携带（审计等旁路面）
     * @param uiResourceUri ui:// 资源 URI；null 表示不带 UI 元数据
     * @param uiServer      资源所属 MCP server 名（供前端调代理端点）
     */
    public static String payload(AgentEvent event, String sessionId,
                                 String uiResourceUri, String uiServer) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", event.getType().name());
        payload.put("id", event.getId());
        // 会话归属（§3 构建期注入）：紧跟 type/id；客户端按键名读、不依赖键序
        putIfNotBlank(payload, "session_id", sessionId);

        if (event instanceof AgentStartEvent) {
            // agent 开始处理本 turn 的墙钟时刻（追加需求④）：SDK 在事件构造时即置
            // createdAt = Instant.now()（ISO-8601 UTC），原样透传不改写；远程转发事件
            // 携带的是远端子 agent 真实开始时刻，如实记录
            putIfNotBlank(payload, "created_at", event.getCreatedAt());
        } else if (event instanceof TextBlockDeltaEvent delta) {
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
                payload.put("cachedTokens", mce.getUsage().getCachedTokens());
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
        } else if (event instanceof io.agentscope.core.event.SubagentExposedEvent sub) {
            // 子 agent 调用收口帧（AgentSpawnTool 在结果返回时发出）：snake_case 与
            // permission_ask 同风格，字段供前端「远程调用面板」展示收口标记
            payload.put("type", "subagent_exposed");
            putIfNotBlank(payload, "subagent_id", sub.getSubagentId());
            putIfNotBlank(payload, "agent_id", sub.getAgentId());
            putIfNotBlank(payload, "session_id", sub.getSessionId());
            putIfNotBlank(payload, "label", sub.getLabel());
        }

        // 远端转发标注（AgentSpawnTool.tagRemoteForwardedEvent 对远程子 agent 回流事件打标，
        // lead 自身事件 source 恒为 null）：前端据此把转发事件路由进「远程调用面板」而非主回复流
        String source = event.getSource();
        if (source != null && !source.isBlank()) {
            payload.put("source", source);
        }
        var metadata = event.getMetadata();
        if (metadata != null) {
            Object taskId = metadata.get("taskId");
            if (taskId != null && !String.valueOf(taskId).isBlank()) {
                payload.put("taskId", String.valueOf(taskId));
            }
            Object parentSid = metadata.get("parentSessionId");
            if (parentSid != null && !String.valueOf(parentSid).isBlank()) {
                payload.put("parentSessionId", String.valueOf(parentSid));
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

    /**
     * 是否为远程子 agent 转发事件。
     *
     * <p>SDK {@code AgentSpawnTool.tagRemoteForwardedEvent} 对远程子 agent（Agent Protocol
     * /tasks）回流的事件统一 {@code withSource} 打标，lead 自身事件 source 恒为 null——
     * 这也是 {@code AGENT_END} 区分「turn 真正终点」与「子任务运行终点」的唯一判据：
     * 远程转发的 AGENT_END 若当作 turn 终点，会在 spawn 结果返回前提前释放租约并关流
     * （2026-09-30 order-fulfillment demo 实测 SSE 截断）。ChatStreamController /
     * ConfirmController / AgentRuntimeService 三处收尾点共用本判定。
     */
    public static boolean isRemoteForwarded(AgentEvent event) {
        String source = event == null ? null : event.getSource();
        return source != null && !source.isBlank();
    }

    private static void putIfNotBlank(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
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
        return withSessionContext(payload, null, replyId, false);
    }

    /**
     * 同上，并可同时注入 turn 级标识 {@code turnId}（订阅接口 {@code ?replyId=} 过滤用它）。
     *
     * <p>为什么要区分：帧内已有的 {@code replyId} 来自 SDK 事件（agent 级 / model 调用级 /
     * block 级，同一 turn 内可能各不相同），而 {@code GET /threads/{sid}/subscribe?replyId=}
     * 过滤的是 **turn 信封 id**（ChatStreamController 每轮生成的 UUID，也是落库的
     * session_event.r 字段）。客户端拿帧内 {@code replyId} 去续传会一条也匹配不到。
     * 因此写路径对**非 delta** 事件追加一个明确的 {@code turnId}；delta 保持零重写
     * （token 热路径，turnId 可从任一里程碑帧或 {@code /status.reply_id} 获得）。
     *
     * @param payload       SSE data 的 JSON 字符串
     * @param replyId       turn 标识；null/blank 不注入
     * @param includeTurnId 是否注入 {@code turnId}（建议仅非 delta 事件为 true）
     * @return 注入后的字符串；无法注入时返回原串
     */
    public static String withReplyId(String payload, String replyId, boolean includeTurnId) {
        return withSessionContext(payload, null, replyId, includeTurnId);
    }

    /**
     * 上下文注入收口（chat-trace-observability-design §3.2 写路径兜底）：把
     * {@code session_id}（会话归属，构建期未带的帧在此补齐）/ {@code replyId} /
     * {@code turnId} 注入 payload JSON 顶层。三个键各自独立做 {@code has} 顶层键短路——
     * 构建期已带的键零重写，缺失的键追加在 JSON 末尾（ObjectNode 插入序）。
     *
     * <p>读取成本说明：readTree 对每个经过本方法的帧都会发生（与 withReplyId 现状一致），
     * 新增的只是内存内 has 判定；重序列化仅发生在「确有键缺失」时——新数据构建期已带
     * session_id + replyId，恒走原引用返回。
     *
     * @param payload       SSE data 的 JSON 字符串；null 或非 JSON object 原串返回
     * @param sessionId     会话归属；null/blank 不注入
     * @param replyId       turn 标识；null/blank 不注入
     * @param includeTurnId 是否注入 {@code turnId}
     * @return 注入后的字符串；无法注入时返回原串
     */
    public static String withSessionContext(String payload, String sessionId,
                                            String replyId, boolean includeTurnId) {
        if (payload == null
                || isBlank(sessionId) && isBlank(replyId)) {
            return payload;
        }
        try {
            var node = MAPPER.readTree(payload);
            if (node == null || !node.isObject()) {
                return payload;
            }
            boolean changed = false;
            var obj = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            if (!isBlank(sessionId) && !obj.has("session_id")) {
                obj.put("session_id", sessionId);
                changed = true;
            }
            if (!isBlank(replyId) && !obj.has("replyId")) {
                obj.put("replyId", replyId);
                changed = true;
            }
            if (includeTurnId && !isBlank(replyId) && !obj.has("turnId")) {
                obj.put("turnId", replyId);
                changed = true;
            }
            return changed ? MAPPER.writeValueAsString(obj) : payload;
        } catch (Exception e) {
            // 注入失败不阻塞主链路，使用原始 payload
            return payload;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * EnvelopedEvent &rarr; SSE 帧（A1：SessionEventBus 与 SessionEventTailer 的私有 toSSE
     * 收口于此，两条路径必然同构，杜绝双份拷贝漂移）。
     *
     * <p>data 不是直通透传：新数据（写路径已注入 replyId + session_id）经
     * {@link #withSessionContext} 顶层键短路原串返回；存量行（升级前落库、p 内缺
     * session_id/replyId）由同一实现兜底注入。id 仍为回放游标 seq。
     *
     * @param sessionId 订阅目标会话（回放端点路径参数，必非空）；用于给存量行兜底补
     *                  session_id（chat-trace-observability-design §3.3）
     */
    public static ServerSentEvent<String> toSseFrame(SessionEventStore.EnvelopedEvent e,
                                                     String sessionId) {
        return ServerSentEvent.<String>builder()
            .data(withSessionContext(e.payload(), sessionId, e.replyId(), false))
            .id(String.valueOf(e.seq()))
            .build();
    }
}