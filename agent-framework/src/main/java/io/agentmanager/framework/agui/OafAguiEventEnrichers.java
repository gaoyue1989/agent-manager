package io.agentmanager.framework.agui;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.URLSource;
import io.agentmanager.framework.service.McpToolRegistrar;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AG-UI 平台自定义事件 enricher（agui-migration-plan §5.1 事件词表 oaf.* 前缀）。
 *
 * <p>全部以 AguiEventEnricher 实现（纯追加，不覆盖内置 converter 的既有语义，
 * 避免复制 SDK 内部组装逻辑）。三个 enricher：
 * <ul>
 *   <li>{@link #mcpUi(McpToolRegistrar)}：TOOL_CALL_START 后追加 CUSTOM "oaf.mcp_ui"
 *       （MCP Apps 卡片锚点，{toolCallId, resourceUri, server}）。</li>
 *   <li>{@link #fileReady()}：present_file 工具结果 TextDelta 累积 + End 解析 JSON →
 *       追加 CUSTOM "oaf.file_ready"（下载卡片，{file_id, file_name, mime_type, size, download_url}）。</li>
 *   <li>{@link #toolImage()}：工具结果图片 DataBlock → 追加 CUSTOM "oaf.tool_image"
 *       （{toolCallId, media_type, data|url}）。</li>
 * </ul>
 */
public final class OafAguiEventEnrichers {

    private static final Logger log = LoggerFactory.getLogger(OafAguiEventEnrichers.class);

    private OafAguiEventEnrichers() {
    }

    /** MCP Apps：工具带 ui:// 资源时追加卡片元数据（裸名冲突时 resolveUiRef 返回 null 降级不追加） */
    public static AguiEventEnricher mcpUi(McpToolRegistrar registrar) {
        return (source, events, context) -> {
            if (source instanceof ToolCallStartEvent tc) {
                var uiRef = registrar.resolveUiRef(tc.getToolCallName());
                if (uiRef != null) {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("toolCallId", tc.getToolCallId());
                    value.put("resourceUri", uiRef.resourceUri());
                    value.put("server", uiRef.serverName());
                    events = append(context, events, "oaf.mcp_ui", value);
                }
            }
            return events;
        };
    }

    /** 交付类工具（结果 JSON 与 present_file 同构）：file_ready 合成范围 */
    private static boolean isFileDeliveryTool(String name) {
        return "present_file".equals(name) || "create_oaf_zip".equals(name);
    }

    /** 交付类工具结果 → oaf.file_ready（有状态：TextDelta 按 toolCallId 累积，64KB 单桶上限） */
    public static AguiEventEnricher fileReady() {
        return new FileReadyEnricher();
    }

    /** 工具结果图片 DataBlock → oaf.tool_image */
    public static AguiEventEnricher toolImage() {
        return (source, events, context) -> {
            if (source instanceof ToolResultDataDeltaEvent dataDelta
                && dataDelta.getData() instanceof ImageBlock image
                && image.getSource() != null) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("toolCallId", dataDelta.getToolCallId());
                if (image.getSource() instanceof Base64Source base64) {
                    value.put("media_type", base64.getMediaType());
                    value.put("data", base64.getData());
                } else if (image.getSource() instanceof URLSource url) {
                    value.put("media_type", url.getMimeType());
                    value.put("url", url.getUrl());
                } else {
                    return events;
                }
                events = append(context, events, "oaf.tool_image", value);
            }
            return events;
        };
    }

    private static List<AguiEvent> append(
        io.agentscope.core.agui.adapter.strategy.AguiStreamContext context,
        List<AguiEvent> events, String name, Map<String, Object> value) {
        var appended = new ArrayList<>(events);
        appended.add(new AguiEvent.Custom(
            context.getThreadId(), context.getRunId(), name, value, null, null));
        return appended;
    }

    /**
     * present_file 累积 + 合成（对齐 SessionStreamController 的 emitFileReady 解析逻辑：
     * SDK 对工具返回字符串再做一次 JSON 编码，Textual 节点需二次解析）。
     */
    private static final class FileReadyEnricher implements AguiEventEnricher {

        /** present_file 结果文本累积（toolCallId → 文本桶，64KB 上限防内存膨胀） */
        private final ConcurrentHashMap<String, StringBuilder> buffers = new ConcurrentHashMap<>();

        private static final int BUFFER_MAX = 64 * 1024;

        @Override
        public List<AguiEvent> enrich(
            io.agentscope.core.event.AgentEvent source, List<AguiEvent> events,
            io.agentscope.core.agui.adapter.strategy.AguiStreamContext context) {
            if (source instanceof ToolResultTextDeltaEvent trd
                && isFileDeliveryTool(trd.getToolCallName())) {
                accumulate(trd.getToolCallId(), String.valueOf(trd.getDelta()));
                return events;
            }
            if (source instanceof ToolResultEndEvent tre
                && isFileDeliveryTool(tre.getToolCallName())) {
                var payload = synthFileReady(tre.getToolCallId());
                if (payload != null) {
                    return append(context, events, "oaf.file_ready", payload);
                }
            }
            return events;
        }

        private void accumulate(String toolCallId, String delta) {
            if (toolCallId == null) {
                return;
            }
            var buf = buffers.computeIfAbsent(toolCallId, k -> new StringBuilder());
            synchronized (buf) {
                if (buf.length() + delta.length() > BUFFER_MAX) {
                    log.warn("present_file result buffer overflow for toolCallId {}, dropping tail", toolCallId);
                    return;
                }
                buf.append(delta);
            }
        }

        /** 解析累积 JSON → file_ready payload（JSON 畸形/字段缺失降级为无帧，不阻断流） */
        private Map<String, Object> synthFileReady(String toolCallId) {
            var buf = buffers.remove(toolCallId);
            if (buf == null) {
                return null;
            }
            String json;
            synchronized (buf) {
                json = buf.toString();
            }
            try {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var node = mapper.readTree(json);
                if (node != null && node.isTextual()) {
                    node = mapper.readTree(node.asText());
                }
                if (node == null || !node.has("file_id") || !node.has("file_name")) {
                    log.warn("present_file result missing file_id/file_name, skip oaf.file_ready");
                    return null;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("file_id", node.get("file_id").asText());
                payload.put("file_name", node.get("file_name").asText());
                payload.put("mime_type", node.has("mime_type") ? node.get("mime_type").asText() : null);
                payload.put("size", node.has("size") ? node.get("size").asLong() : 0);
                // 下载 URL：前端 Next rewrite 前缀下相对路径（对齐 SessionStreamController 现有约定）
                payload.put("download_url", "/agent/release-agent/files/" + node.get("file_id").asText());
                return payload;
            } catch (Exception e) {
                log.warn("oaf.file_ready synthesis failed: {}", e.getMessage());
                return null;
            }
        }
    }
}
