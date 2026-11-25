package io.agentmanager.framework.service;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.GenerateReason;

import static org.junit.jupiter.api.Assertions.*;

class DirectReturnDeltaJoinerTest {

    private static final String RID = "reply-1";

    private static TextBlockDeltaEvent directDelta(String blockId, String text) {
        var delta = new TextBlockDeltaEvent(RID, blockId, text);
        delta.withMetadata(Map.of(AgentEvent.METADATA_GENERATE_REASON,
            GenerateReason.TOOL_RETURN_DIRECT.name()));
        return delta;
    }

    private static String joined(AgentEvent... events) {
        var joiner = new DirectReturnDeltaJoiner();
        var sb = new StringBuilder();
        for (var e : events) {
            var out = joiner.apply(e);
            if (out instanceof TextBlockDeltaEvent delta) {
                sb.append(delta.getDelta());
            }
        }
        return sb.toString();
    }

    private static String directHistoryText(String... blockTexts) throws Exception {
        var blocks = new StringBuilder("[");
        for (var i = 0; i < blockTexts.length; i++) {
            if (i > 0) blocks.append(",");
            blocks.append("{\"type\":\"text\",\"text\":\"")
                .append(blockTexts[i].replace("\"", "\\\"")).append("\"}");
        }
        blocks.append("]");
        var msg = new ObjectMapper().readTree(
            "{\"role\":\"assistant\",\"metadata\":{\"" + io.agentscope.core.message.Msg.METADATA_GENERATE_REASON + "\":\"TOOL_RETURN_DIRECT\"},\"content\":" + blocks + "}");
        return StateDataParser.extractContentText(msg);
    }

    @Test
    void splitJsonChunksShouldNotBeCorruptedByNewlineAndMatchDirectHistory() throws Exception {
        var sseText = joined(
            directDelta("blk-1", "{\"msg\":\"hello "),
            directDelta("blk-2", "world\"}"));
        assertEquals("{\"msg\":\"hello world\"}", sseText);
        assertEquals(directHistoryText("{\"msg\":\"hello ", "world\"}"), sseText);
        assertEquals("hello world", new ObjectMapper().readTree(sseText).get("msg").asText());
    }

    @Test
    void multiBlockDirectReturnShouldMatchDirectHistoryVerbatim() throws Exception {
        var sseText = joined(
            directDelta("blk-1", "PartA"),
            directDelta("blk-2", "PartB"));
        assertEquals("PartAPartB", sseText);
        assertEquals(directHistoryText("PartA", "PartB"), sseText);
    }

    @Test
    void normalAssistantMessageShouldStillJoinTextBlocksWithNewline() throws Exception {
        var msg = new ObjectMapper().readTree(
            "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"PartA\"},{\"type\":\"text\",\"text\":\"PartB\"}]}");
        assertEquals("PartA\nPartB", StateDataParser.extractContentText(msg));
    }
}
