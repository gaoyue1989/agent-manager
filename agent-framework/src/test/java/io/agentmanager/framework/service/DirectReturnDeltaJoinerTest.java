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

    /** /history 真相源：extractContentText 对同结构消息的拼接口径 */
    private static String historyText(String... blockTexts) throws Exception {
        var blocks = new StringBuilder("[");
        for (var i = 0; i < blockTexts.length; i++) {
            if (i > 0) blocks.append(",");
            blocks.append("{\"type\":\"text\",\"text\":\"")
                .append(blockTexts[i].replace("\"", "\\\"")).append("\"}");
        }
        blocks.append("]");
        var msg = new ObjectMapper().readTree(
            "{\"role\":\"assistant\",\"content\":" + blocks + "}");
        return StateDataParser.extractContentText(msg);
    }

    @Test
    void multiBlockDirectReturnShouldMatchHistoryNewlineJoin() throws Exception {
        var sseText = joined(
            directDelta("blk-1", "PartA"),
            directDelta("blk-2", "PartB"));
        assertEquals("PartA\nPartB", sseText);
        assertEquals(historyText("PartA", "PartB"), sseText,
            "SSE 拼接必须与 /history extractContentText 逐字符一致");
    }

    @Test
    void multiToolDirectReturnShouldMatchHistoryNewlineJoin() throws Exception {
        var sseText = joined(
            directDelta("blk-a", "DIRECT_A_OUT"),
            directDelta("blk-b", "DIRECT_B_OUT"));
        assertEquals("DIRECT_A_OUT\nDIRECT_B_OUT", sseText);
        assertEquals(historyText("DIRECT_A_OUT", "DIRECT_B_OUT"), sseText);
    }

    @Test
    void blankBlockShouldNotIntroduceSeparatorAndMatchHistory() throws Exception {
        var sseText = joined(
            directDelta("blk-1", "PartA"),
            directDelta("blk-blank", " "),
            directDelta("blk-2", "PartB"));
        assertEquals("PartA\nPartB", sseText);
        assertEquals(historyText("PartA", " ", "PartB"), sseText,
            "blank 块在 history 口径中不存在（跳过），SSE 侧归一为空文本帧");
    }

    @Test
    void emptyBlockFrameShouldPassThroughUnchanged() {
        var joiner = new DirectReturnDeltaJoiner();
        var first = joiner.apply(directDelta("blk-1", "A"));
        var empty = directDelta("blk-2", "");
        var out = joiner.apply(empty);
        assertSame(empty, out, "已是空文本的帧无需重建");
        var second = joiner.apply(directDelta("blk-3", "B"));
        assertEquals("\nB", ((TextBlockDeltaEvent) second).getDelta());
    }

    @Test
    void singleBlockShouldPassThroughUnchanged() {
        var joiner = new DirectReturnDeltaJoiner();
        var only = directDelta("blk-1", "only output");
        assertSame(only, joiner.apply(only), "首块（无分隔）原事件直接透传，热路径零重建");
    }

    @Test
    void modelDeltaWithoutDirectReturnMarkerShouldPassThroughUnchanged() {
        var joiner = new DirectReturnDeltaJoiner();
        var modelDelta = new TextBlockDeltaEvent(RID, "blk-1", "token");
        assertSame(modelDelta, joiner.apply(modelDelta));
        var next = joiner.apply(directDelta("blk-2", "PartB"));
        assertEquals("PartB", ((TextBlockDeltaEvent) next).getDelta(),
            "直返轮的 joiner 不得被模型 delta 污染出前导换行");
    }

    @Test
    void remoteForwardedDirectReturnDeltaShouldPassThroughUnchanged() {
        var joiner = new DirectReturnDeltaJoiner();
        var first = directDelta("blk-1", "PartA");
        joiner.apply(first);
        var forwarded = directDelta("blk-2", "PartB");
        forwarded.withSource("child-agent");
        assertSame(forwarded, joiner.apply(forwarded),
            "远程子 agent 转发的直返 delta 不属 /history 真相源管辖，原样透传");
    }

    @Test
    void rewrittenEventShouldPreserveIdentity() {
        var joiner = new DirectReturnDeltaJoiner();
        var first = directDelta("blk-1", "PartA");
        joiner.apply(first);
        var original = directDelta("blk-2", "PartB");
        var rewritten = (TextBlockDeltaEvent) joiner.apply(original);
        assertEquals("\nPartB", rewritten.getDelta());
        assertEquals(original.getId(), rewritten.getId());
        assertEquals(original.getCreatedAt(), rewritten.getCreatedAt());
        assertEquals(RID, rewritten.getReplyId());
        assertEquals("blk-2", rewritten.getBlockId());
        assertEquals(GenerateReason.TOOL_RETURN_DIRECT.name(),
            rewritten.getMetadata().get(AgentEvent.METADATA_GENERATE_REASON));
    }
}
