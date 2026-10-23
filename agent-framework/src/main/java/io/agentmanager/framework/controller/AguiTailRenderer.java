package io.agentmanager.framework.controller;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.codec.ServerSentEvent;

import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionEventTailer.TailFrameRenderer;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;

/**
 * AG-UI 续传端点的帧渲染器（agui-native-parity-design §4 D5 / §6.3）。
 *
 * <p>复用 {@code SessionEventTailer.tail()} 的回放/追赶/终态探测/心跳骨架，仅替换帧渲染：
 * <ul>
 *   <li>pa 存在（AG-UI 轮）：拆 JSON 数组逐帧下发，{@code id:} 行 = 信封 seq（同 seq 多帧
 *       共享游标，afterSeq 整组跳过，续传语义不变）；</li>
 *   <li>pa 缺失（native-only 段，§6.3 降级）：包一层 {@code CUSTOM(name=platform.native_event,
 *       value=<p 原文解析>)}——标准客户端安全忽略或按需渲染；不做 p→AgentEvent 反向重建
 *       （p 是有损投影，语义不保真）；</li>
 *   <li>done 终态：RUN_FINISHED 已是数据事件，不补帧直接收流（避免双终态违反协议）；</li>
 *   <li>interrupted（执行副本崩溃）：CUSTOM(platform.control, kind=interrupted)——AG-UI 词表
 *       无 turn 中断语义，**不用 RUN_ERROR**（把 turn 中断误标为 run 失败）；</li>
 *   <li>排队失败：RUN_ERROR（此时 run 确实没跑起来，语义准确）。</li>
 * </ul>
 */
public final class AguiTailRenderer implements TailFrameRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String threadId;
    private final String runId;
    private final AguiEventEncoder encoder = new AguiEventEncoder();

    public AguiTailRenderer(String threadId, String runId) {
        this.threadId = threadId;
        this.runId = runId;
    }

    /** 请求的 runId（未传时为空串——events 端点把它转为 null 表示不限 run） */
    public String runId() {
        return runId;
    }

    @Override
    public List<ServerSentEvent<String>> render(SessionEventStore.EnvelopedEvent e) {
        var frames = new ArrayList<ServerSentEvent<String>>();
        String id = String.valueOf(e.seq());
        if (e.aguiPayload() != null && !e.aguiPayload().isEmpty()) {
            for (String json : splitJsonArray(e.aguiPayload())) {
                frames.add(ServerSentEvent.<String>builder().data(json).id(id).build());
            }
            return frames;
        }
        // §6.3 native-only 降级：CUSTOM(platform.native_event)
        frames.add(ServerSentEvent.<String>builder()
            .data(customJson("platform.native_event", parsePayload(e.payload())))
            .id(id)
            .build());
        return frames;
    }

    @Override
    public List<ServerSentEvent<String>> doneFrames() {
        return List.of();
    }

    @Override
    public List<ServerSentEvent<String>> interruptedFrames() {
        return List.of(ServerSentEvent.<String>builder()
            .data(customJson("platform.control",
                java.util.Map.of("kind", "interrupted", "reason", "turn_interrupted")))
            .build());
    }

    @Override
    public List<ServerSentEvent<String>> errorFrames(String error) {
        var event = new AguiEvent.RunError(threadId, runId, error, "PLATFORM_TURN_ERROR");
        return List.of(ServerSentEvent.<String>builder()
            .data(encoder.encodeToJson(event))
            .build());
    }

    /** CUSTOM 事件 JSON（与 SDK AguiEvent.Custom 同构的 wire 形态） */
    private String customJson(String name, Object value) {
        try {
            return encoder.encodeToJson(new AguiEvent.Custom(threadId, runId, name, value));
        } catch (Exception e) {
            // SDK 编码失败（极罕见）：退化为手拼最小 CUSTOM 帧，保证收流语义可达
            return "{\"type\":\"CUSTOM\",\"threadId\":\"" + threadId + "\",\"runId\":\""
                + runId + "\",\"name\":\"" + name + "\",\"value\":null}";
        }
    }

    /** p 原文 → JSON 值（解析失败按字符串承载） */
    private Object parsePayload(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            return MAPPER.readTree(payload);
        } catch (Exception e) {
            return payload;
        }
    }

    /**
     * 拆 pa JSON 数组为逐元素 JSON 字符串。经 Jackson round-trip（ObjectNode 保持字段
     * 插入序），单元素字节与数组内一致；解析失败按单帧整体下发（客户端自行容错）。
     */
    static List<String> splitJsonArray(String pa) {
        var out = new ArrayList<String>();
        try {
            var node = MAPPER.readTree(pa);
            if (node != null && node.isArray()) {
                for (var el : node) {
                    out.add(MAPPER.writeValueAsString(el));
                }
                return out;
            }
        } catch (Exception ignored) {
            // 落到整体下发
        }
        out.add(pa);
        return out;
    }
}
