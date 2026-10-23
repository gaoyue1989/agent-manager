package io.agentmanager.framework.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.service.SessionEventStore.EnvelopedEvent;
import io.agentmanager.framework.service.SessionEventTailer.TailFrameRenderer;

/**
 * AG-UI 渲染器与 resume 翻译层单测（agui-native-parity-design M5）。
 */
class AguiTailRendererTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TailFrameRenderer renderer = new AguiTailRenderer("sess-1", "run-1");

    @Test
    void splitsPaArrayIntoFramesSharingSeqId() throws Exception {
        var e = new EnvelopedEvent(42, "TEXT_BLOCK_DELTA", "{\"type\":\"TEXT_BLOCK_DELTA\"}",
            "run-1", "[{\"type\":\"TEXT_MESSAGE_START\",\"threadId\":\"sess-1\"},"
                   + "{\"type\":\"TEXT_MESSAGE_CONTENT\",\"delta\":\"你好\"}]");
        var frames = renderer.render(e);
        assertEquals(2, frames.size());
        assertEquals("42", frames.get(0).id());
        assertEquals("42", frames.get(1).id());
        var first = JSON.readTree(frames.get(0).data());
        assertEquals("TEXT_MESSAGE_START", first.get("type").asText());
    }

    @Test
    void degradesNativeOnlyRowsToCustomNativeEvent() throws Exception {
        var e = new EnvelopedEvent(7, "AGENT_START", "{\"type\":\"AGENT_START\"}", "run-1");
        var frames = renderer.render(e);
        assertEquals(1, frames.size());
        assertEquals("7", frames.get(0).id());
        var node = JSON.readTree(frames.get(0).data());
        assertEquals("CUSTOM", node.get("type").asText());
        assertEquals("platform.native_event", node.get("name").asText());
        assertEquals("AGENT_START", node.get("value").get("type").asText());
    }

    @Test
    void doneEmitsNoFrame() {
        assertTrue(renderer.doneFrames().isEmpty(),
            "RUN_FINISHED 已是数据事件，done 不补帧（防双终态）");
    }

    @Test
    void interruptedEmitsControlCustom() throws Exception {
        var frames = renderer.interruptedFrames();
        assertEquals(1, frames.size());
        var node = JSON.readTree(frames.get(0).data());
        assertEquals("CUSTOM", node.get("type").asText());
        assertEquals("platform.control", node.get("name").asText());
        assertEquals("interrupted", node.get("value").get("kind").asText());
    }

    @Test
    void errorEmitsRunError() throws Exception {
        var frames = renderer.errorFrames("boom");
        assertEquals(1, frames.size());
        var node = JSON.readTree(frames.get(0).data());
        assertEquals("RUN_ERROR", node.get("type").asText());
        assertEquals("boom", node.get("message").asText());
    }

    @Test
    void splitJsonArraySurvivesMalformedPayload() {
        List<String> out = AguiTailRenderer.splitJsonArray("not-json");
        assertEquals(1, out.size());
        assertEquals("not-json", out.get(0));
    }

    // ===== resume 翻译 =====

    @Test
    void translateResolvesApprovedAndRejectsOthers() {
        var resumes = List.of(
            new AguiRunController.AguiResumeDTO("run-1:call-a", "resolved",
                Map.of("approved", true)),
            new AguiRunController.AguiResumeDTO("run-1:call-b", "resolved",
                Map.of("approved", false)),
            new AguiRunController.AguiResumeDTO("run-1:call-c", "cancelled", Map.of()),
            new AguiRunController.AguiResumeDTO("run-1:call-d", "resolved",
                Map.of("approved", true, "acceptRule", true)));
        var results = AguiRunController.translateResumes(resumes);
        assertEquals(4, results.size());
        assertEquals(true, results.get(0).get("confirmed"));
        assertEquals(false, results.get(1).get("confirmed"));
        assertEquals(false, results.get(2).get("confirmed"));
        assertEquals(true, results.get(3).get("confirmed"));
        assertEquals(true, results.get(3).get("accept_rule"));
        // 非 strict 布尔的 approved 一律拒绝（官方语义：仅 approved===true 放行）
        var sneaky = List.of(new AguiRunController.AguiResumeDTO("run-1:call-e", "resolved",
            Map.of("approved", "true")));
        assertEquals(false, AguiRunController.translateResumes(sneaky).get(0).get("confirmed"));
    }

    @Test
    void translateRejectsMalformedInterruptId() {
        var bad = List.of(new AguiRunController.AguiResumeDTO("no-colon", "resolved",
            Map.of("approved", true)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> AguiRunController.translateResumes(bad));
        var empty = List.of(new AguiRunController.AguiResumeDTO("", "resolved", Map.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> AguiRunController.translateResumes(empty));
    }

    @Test
    void extractLatestUserTextHandlesStringAndBlocks() {
        var msgs = List.of(
            new AguiRunController.AguiMessageDTO("m1", "assistant", "hi"),
            new AguiRunController.AguiMessageDTO("m2", "user", "第一问"),
            new AguiRunController.AguiMessageDTO("m3", "user",
                List.of(Map.of("type", "text", "text", "第"),
                        Map.of("type", "text", "text", "二问"))));
        assertEquals("第二问", AguiRunController.extractLatestUserText(msgs));
    }
}
