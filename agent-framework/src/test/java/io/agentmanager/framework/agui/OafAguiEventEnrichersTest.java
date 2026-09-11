package io.agentmanager.framework.agui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentmanager.framework.service.McpToolRegistrar;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * OafAguiEventEnrichers 单测（agui-migration-plan §5.1 oaf.* 事件词表）：
 * mcp_ui 追加/降级、file_ready 累积合成（含二次 JSON 解析）、tool_image 双来源。
 */
class OafAguiEventEnrichersTest {

    private AguiStreamContext context(String threadId) {
        return new AguiStreamContext(threadId, "run-1", AguiAdapterConfig.builder().build());
    }

    private static ToolCallStartEvent toolCallStart(String toolCallId, String toolName) {
        return new ToolCallStartEvent("reply-1", toolCallId, toolName);
    }

    // ===== oaf.mcp_ui =====

    @Test
    void mcpUiShouldAppendCustomAfterToolCallStart() {
        var registrar = mock(McpToolRegistrar.class);
        when(registrar.resolveUiRef("ui_tool")).thenReturn(new McpToolRegistrar.UiRef("ui://x", "srv"));
        var enricher = OafAguiEventEnrichers.mcpUi(registrar);

        var result = enricher.enrich(toolCallStart("call-1", "ui_tool"),
            List.of(), context("t1"));

        assertEquals(1, result.size());
        var custom = (AguiEvent.Custom) result.get(0);
        assertEquals("oaf.mcp_ui", custom.name());
        @SuppressWarnings("unchecked")
        var value = (java.util.Map<String, Object>) custom.value();
        assertEquals("call-1", value.get("toolCallId"));
        assertEquals("ui://x", value.get("resourceUri"));
        assertEquals("srv", value.get("server"));
    }

    @Test
    void mcpUiShouldNotAppendWhenNoUiRef() {
        var registrar = mock(McpToolRegistrar.class);
        when(registrar.resolveUiRef(any())).thenReturn(null);
        var result = OafAguiEventEnrichers.mcpUi(registrar)
            .enrich(toolCallStart("call-1", "plain_tool"), List.of(), context("t1"));
        assertTrue(result.isEmpty());
    }

    // ===== oaf.file_ready =====

    @Test
    void fileReadyShouldSynthesizeFromAccumulatedJson() {
        var enricher = OafAguiEventEnrichers.fileReady();
        var ctx = context("t1");
        var trd = mock(ToolResultTextDeltaEvent.class);
        when(trd.getToolCallName()).thenReturn("present_file");
        when(trd.getToolCallId()).thenReturn("call-1");
        when(trd.getDelta())
            .thenReturn("{\"file_id\":\"f-1\",\"file_name\":\"a.zip\",\"mime_type\":\"application/zip\",\"size\":3}")
            .thenReturn("");

        var afterDelta = enricher.enrich(trd, List.of(), ctx);
        assertTrue(afterDelta.isEmpty());

        var tre = mock(ToolResultEndEvent.class);
        when(tre.getToolCallName()).thenReturn("present_file");
        when(tre.getToolCallId()).thenReturn("call-1");
        var result = enricher.enrich(tre, List.of(), ctx);

        assertEquals(1, result.size());
        var custom = (AguiEvent.Custom) result.get(0);
        assertEquals("oaf.file_ready", custom.name());
        @SuppressWarnings("unchecked")
        var value = (java.util.Map<String, Object>) custom.value();
        assertEquals("f-1", value.get("file_id"));
        assertEquals("a.zip", value.get("file_name"));
        assertEquals("/agent/release-agent/files/f-1", value.get("download_url"));
    }

    @Test
    void fileReadyShouldParseDoubleEncodedJson() {
        var enricher = OafAguiEventEnrichers.fileReady();
        var ctx = context("t1");
        // SDK 对工具返回字符串再做一次 JSON 编码：TextDelta 携带 "{\"...\"}" 外层引号
        var trd = mock(ToolResultTextDeltaEvent.class);
        when(trd.getToolCallName()).thenReturn("present_file");
        when(trd.getToolCallId()).thenReturn("call-2");
        when(trd.getDelta()).thenReturn(
            "\"{\\\"file_id\\\":\\\"f-2\\\",\\\"file_name\\\":\\\"b.txt\\\"}\"");

        enricher.enrich(trd, List.of(), ctx);
        var tre = mock(ToolResultEndEvent.class);
        when(tre.getToolCallName()).thenReturn("present_file");
        when(tre.getToolCallId()).thenReturn("call-2");
        var result = enricher.enrich(tre, List.of(), ctx);

        assertEquals(1, result.size());
        @SuppressWarnings("unchecked")
        var value = (java.util.Map<String, Object>) ((AguiEvent.Custom) result.get(0)).value();
        assertEquals("f-2", value.get("file_id"));
    }

    @Test
    void fileReadyShouldSkipOnMalformedJson() {
        var enricher = OafAguiEventEnrichers.fileReady();
        var ctx = context("t1");
        var trd = mock(ToolResultTextDeltaEvent.class);
        when(trd.getToolCallName()).thenReturn("present_file");
        when(trd.getToolCallId()).thenReturn("call-3");
        when(trd.getDelta()).thenReturn("not-json{");
        enricher.enrich(trd, List.of(), ctx);

        var tre = mock(ToolResultEndEvent.class);
        when(tre.getToolCallName()).thenReturn("present_file");
        when(tre.getToolCallId()).thenReturn("call-3");
        assertTrue(enricher.enrich(tre, List.of(), ctx).isEmpty());
    }

    // ===== oaf.tool_image =====

    @Test
    void toolImageShouldAppendForBase64Image() {
        var enricher = OafAguiEventEnrichers.toolImage();
        var dd = mock(ToolResultDataDeltaEvent.class);
        when(dd.getToolCallId()).thenReturn("call-1");
        when(dd.getData()).thenReturn(io.agentscope.core.message.ImageBlock.builder()
            .source(io.agentscope.core.message.Base64Source.builder()
                .mediaType("image/png").data("aGk=").build())
            .build());

        var result = enricher.enrich(dd, List.of(), context("t1"));
        assertEquals(1, result.size());
        var custom = (AguiEvent.Custom) result.get(0);
        assertEquals("oaf.tool_image", custom.name());
        @SuppressWarnings("unchecked")
        var value = (java.util.Map<String, Object>) custom.value();
        assertEquals("image/png", value.get("media_type"));
        assertEquals("aGk=", value.get("data"));
    }

    @Test
    void toolImageShouldNotAppendForNonImageBlock() {
        var enricher = OafAguiEventEnrichers.toolImage();
        var dd = mock(ToolResultDataDeltaEvent.class);
        when(dd.getToolCallId()).thenReturn("call-1");
        when(dd.getData()).thenReturn(null);
        assertTrue(enricher.enrich(dd, List.of(), context("t1")).isEmpty());
    }
}
