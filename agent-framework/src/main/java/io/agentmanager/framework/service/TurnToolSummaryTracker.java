package io.agentmanager.framework.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.controller.AgentEventSseSerializer;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;

/**
 * 单个执行段（turn）内「工具参数 / 工具结果」的累积与摘要帧合成。
 *
 * <p><b>为什么需要</b>：SDK 把工具参数与结果都切成 delta 事件下发，且
 * {@code ToolCallDeltaEvent.getToolCallName()} 实测恒为占位符 {@code "__fragment__"}
 * （真实工具名只在 {@link ToolCallStartEvent} 上，见 e2e-ci-plan §11.3 D3）。
 * 因此要生成「执行 cd... && node output/create-ai-ppt.js」这种摘要，必须在 turn 内
 * 按 toolCallId 登记工具名、按 id 累积缓冲，到 END 事件时一次性合成。
 *
 * <p><b>共享原因</b>：对话流（{@code /threads/chat}）与 HITL 恢复流
 * （{@code /threads/{sid}/confirm-stream}）是两条独立的事件管道，但输出摘要的需求完全一致。
 * 这里收敛成一份实现，避免两侧各写一套缓冲导致行为漂移。
 *
 * <p><b>线程/生命周期</b>：非线程安全，由每个 turn 的订阅回调单线程驱动；
 * 实例随 turn 创建（与 {@code TurnLeaseGuard} 同级），turn 结束即丢弃。
 */
public final class TurnToolSummaryTracker {

    private static final Logger log = LoggerFactory.getLogger(TurnToolSummaryTracker.class);

    /** 单个工具参数的累积上限（JSON 片段）：write_file 整份内容可达 MB 级，防内存膨胀 */
    private static final int ARG_BUFFER_MAX = 2 * 1024 * 1024;

    /** 工具结果文本累积上限：足够取首行摘要即可，无需保存完整大输出 */
    private static final int RESULT_BUFFER_MAX = 64 * 1024;

    /** toolCallId → 真实工具名（ToolCallStart 登记） */
    private final ConcurrentHashMap<String, String> toolNames = new ConcurrentHashMap<>();

    /** toolCallId → 拼接中的参数 JSON */
    private final ConcurrentHashMap<String, StringBuilder> argBuffers = new ConcurrentHashMap<>();

    /** toolCallId → 拼接中的结果文本 */
    private final ConcurrentHashMap<String, StringBuilder> resultBuffers = new ConcurrentHashMap<>();

    /** 摘要帧的落库/广播回调（由控制器注入 EventBus） */
    private final Emitter emitter;

    private final String sessionId;
    private final String replyId;

    public TurnToolSummaryTracker(String sessionId, String replyId, Emitter emitter) {
        this.sessionId = sessionId;
        this.replyId = replyId;
        this.emitter = emitter;
    }

    /** 合成帧的落库出口：对接 {@code SessionEventBus.emitSynthetic} */
    public interface Emitter {
        void emit(String type, String payload);
    }

    // ===== 事件摄入：每个方法都返回 void，调用方无条件转发原事件即可 =====

    /** 登记真实工具名（后续 delta / result 事件的名字不可信） */
    public void onToolCallStart(ToolCallStartEvent e) {
        if (e.getToolCallId() != null && e.getToolCallName() != null) {
            toolNames.put(e.getToolCallId(), e.getToolCallName());
        }
    }

    /** 累积参数 JSON 片段 */
    public void onToolCallDelta(ToolCallDeltaEvent e) {
        append(argBuffers, e.getToolCallId(), e.getDelta(), ARG_BUFFER_MAX, "tool args");
    }

    /** 累积结果文本片段 */
    public void onToolResultDelta(ToolResultTextDeltaEvent e) {
        append(resultBuffers, e.getToolCallId(), e.getDelta(), RESULT_BUFFER_MAX, "tool result");
    }

    /**
     * 参数到齐：合成 {@code tool_call_summary} 帧。
     *
     * <p>顺序上必须先于调用方自己的清理逻辑：缓冲在此移除。
     */
    public void onToolCallEnd(ToolCallEndEvent e) {
        var toolCallId = e.getToolCallId();
        var toolName = resolveName(toolCallId, e.getToolCallName());
        var args = take(argBuffers, toolCallId);
        var summary = ToolSummaryGenerator.callSummary(toolName, args);
        if (summary == null || summary.isBlank()) {
            return;
        }
        emit("tool_call_summary", fields(
            "type", "tool_call_summary",
            "toolCallId", toolCallId == null ? "" : toolCallId,
            "toolName", toolName == null ? "" : toolName,
            "summary", summary));
    }

    /**
     * 结果到齐：合成 {@code tool_result_preview} 帧。
     *
     * <p>非 SUCCESS 也会发帧（此时预览是终态文案，比结果文本更值得展示）。
     * 无有效预览（成功但结果为空）时不发帧，避免噪音。
     */
    public void onToolResultEnd(ToolResultEndEvent e) {
        var toolCallId = e.getToolCallId();
        var toolName = resolveName(toolCallId, e.getToolCallName());
        var result = take(resultBuffers, toolCallId);
        var state = e.getState() != null ? e.getState().name() : "";
        var preview = ToolSummaryGenerator.resultPreview(toolName, result, state);
        if (preview == null || preview.isBlank()) {
            return;
        }
        emit("tool_result_preview", fields(
            "type", "tool_result_preview",
            "toolCallId", toolCallId == null ? "" : toolCallId,
            "toolName", toolName == null ? "" : toolName,
            "preview", preview));
    }

    /**
     * 该 toolCallId 是否属于指定工具（供调用方复用登记表，如 write_file 的 KV 同步）。
     *
     * @param eventToolName 事件自带的名字（作为登记表未命中时的回落）
     */
    public boolean isTool(String toolCallId, String expected, String eventToolName) {
        if (toolCallId == null) {
            return false;
        }
        var registered = toolNames.get(toolCallId);
        return expected.equals(registered != null ? registered : eventToolName);
    }

    /** 读取累积中的 write_file 参数（供 KV 同步复用，不移除缓冲——END 事件还要用它合成摘要） */
    public String peekArgs(String toolCallId) {
        var buf = argBuffers.get(toolCallId);
        if (buf == null) {
            return null;
        }
        synchronized (buf) {
            return buf.toString();
        }
    }

    /** turn 收尾：清空全部缓冲（丢锁 / 异常路径调用） */
    public void clear() {
        toolNames.clear();
        argBuffers.clear();
        resultBuffers.clear();
    }

    // ===== 内部 =====

    private String resolveName(String toolCallId, String eventToolName) {
        if (toolCallId == null) {
            return eventToolName;
        }
        var registered = toolNames.get(toolCallId);
        return registered != null ? registered : eventToolName;
    }

    private void append(ConcurrentHashMap<String, StringBuilder> buffers, String id,
                        Object delta, int max, String what) {
        if (id == null || delta == null) {
            return;
        }
        var buf = buffers.computeIfAbsent(id, k -> new StringBuilder());
        synchronized (buf) {
            String chunk = String.valueOf(delta);
            int room = max - buf.length();
            if (room <= 0) {
                // 已满：整段丢弃（超出部分对首行摘要无价值，占内存纯是浪费）
                log.debug("{} buffer full for toolCallId {}, dropping tail", what, id);
                return;
            }
            if (chunk.length() > room) {
                // 单个分片就超限：截断保留前缀——write_file 的整份内容常在一帧里到达，
                // 若整帧丢弃会导致连「创建 x.js N行」都生成不出来（首屏无输出）
                buf.append(chunk, 0, room);
                log.debug("{} single chunk exceeds remaining room for toolCallId {}, truncated", what, id);
                return;
            }
            buf.append(chunk);
        }
    }

    private String take(ConcurrentHashMap<String, StringBuilder> buffers, String id) {
        if (id == null) {
            return null;
        }
        var buf = buffers.remove(id);
        if (buf == null) {
            return null;
        }
        synchronized (buf) {
            return buf.toString();
        }
    }

    private void emit(String type, Map<String, String> payload) {
        var full = new LinkedHashMap<String, Object>(payload);
        full.put("replyId", replyId);
        try {
            emitter.emit(type, AgentEventSseSerializer.payload(full));
            log.debug("[tool-summary] {} (sid={}): {}", type, sessionId, payload);
        } catch (Exception e) {
            log.debug("tool summary emit failed (sid={}, type={}): {}", sessionId, type, e.getMessage());
        }
    }

    /** 四字段载荷（toolCallId / toolName / summary|preview / type） */
    private static java.util.Map<String, String> fields(String k1, String v1, String k2, String v2,
                                                        String k3, String v3, String k4, String v4) {
        var m = new LinkedHashMap<String, String>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        m.put(k4, v4);
        return m;
    }
}
