package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.service.McpToolRegistrar.ToolCallMeta;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.ToolResultState;

import static org.junit.jupiter.api.Assertions.*;

class DirectReturnSignalTrackerTest {

    private static final Function<String, ToolCallMeta> META = name -> "mcp__chart__render_png".equals(name)
        ? new ToolCallMeta(null, null, true)
        : new ToolCallMeta(null, null, false);

    record Frame(String type, String payload) {}

    private record Run(List<Frame> frames) {
        Frame single() {
            assertEquals(1, frames.size(), "expected exactly one synthetic frame: " + frames);
            return frames.get(0);
        }
    }

    private static Run feed(Function<String, ToolCallMeta> meta, AgentEvent... events) {
        List<Frame> frames = new ArrayList<>();
        var tracker = new DirectReturnSignalTracker(meta,
            (type, payload) -> frames.add(new Frame(type, payload)));
        for (var e : events) {
            tracker.onEvent(e);
        }
        return new Run(frames);
    }

    private static final String RID = "exec-reply-1";

    @Test
    void imageOnlyDirectReturnTurnShouldEmitSignalFrameBeforeAgentEnd() {
        var run = feed(META,
            new ToolCallStartEvent(RID, "call-1", "mcp__chart__render_png"),
            new ToolResultEndEvent(RID, "call-1", "mcp__chart__render_png", ToolResultState.SUCCESS),
            new AgentEndEvent(RID));

        var frame = run.single();
        assertEquals("TEXT_BLOCK_DELTA", frame.type());
        assertTrue(frame.payload().contains("\"delta\":\"\""), frame.payload());
        assertTrue(frame.payload().contains("\"generateReason\":\"TOOL_RETURN_DIRECT\""), frame.payload());
    }

    @Test
    void textDirectReturnTurnShouldNotDuplicateSignal() {
        var delta = new TextBlockDeltaEvent(RID, "blk-1", "already presented text")
            .withMetadata(Map.of(AgentEvent.METADATA_GENERATE_REASON, "TOOL_RETURN_DIRECT"));
        var run = feed(META,
            new ToolCallStartEvent(RID, "call-1", "mcp__chart__render_png"),
            new ToolResultEndEvent(RID, "call-1", "mcp__chart__render_png", ToolResultState.SUCCESS),
            delta,
            new AgentEndEvent(RID));

        assertTrue(run.frames().isEmpty(), "SDK already emitted the signal: " + run.frames());
    }

    @Test
    void mixedBatchFedBackToModelShouldNotEmitSignal() {
        var run = feed(META,
            new ToolCallStartEvent(RID, "call-1", "mcp__chart__render_png"),
            new ToolCallStartEvent(RID, "call-2", "mcp__chart__plain"),
            new ToolResultEndEvent(RID, "call-1", "mcp__chart__render_png", ToolResultState.SUCCESS),
            new ToolResultEndEvent(RID, "call-2", "mcp__chart__plain", ToolResultState.SUCCESS),
            new ModelCallStartEvent(RID),
            new AgentEndEvent(RID));

        assertTrue(run.frames().isEmpty(), "mixed batch is fed back to the model: " + run.frames());
    }

    @Test
    void failedDirectReturnToolShouldNotEmitSignal() {
        var run = feed(META,
            new ToolCallStartEvent(RID, "call-1", "mcp__chart__render_png"),
            new ToolResultEndEvent(RID, "call-1", "mcp__chart__render_png", ToolResultState.ERROR),
            new AgentEndEvent(RID));

        assertTrue(run.frames().isEmpty(), "failed tool is fed back to the model: " + run.frames());
    }

    @Test
    void plainToolTurnShouldNotEmitSignal() {
        var run = feed(META,
            new ToolCallStartEvent(RID, "call-1", "mcp__chart__plain"),
            new ToolResultEndEvent(RID, "call-1", "mcp__chart__plain", ToolResultState.SUCCESS),
            new AgentEndEvent(RID));

        assertTrue(run.frames().isEmpty());
    }

    @Test
    void secondTurnDirectReturnAfterPlainTurnShouldEmitSignal() {
        var run = feed(META,
            new ToolCallStartEvent(RID, "call-1", "mcp__chart__plain"),
            new ToolResultEndEvent(RID, "call-1", "mcp__chart__plain", ToolResultState.SUCCESS),
            new ModelCallStartEvent(RID),
            new ToolCallStartEvent(RID, "call-2", "mcp__chart__render_png"),
            new ToolResultEndEvent(RID, "call-2", "mcp__chart__render_png", ToolResultState.SUCCESS),
            new AgentEndEvent(RID));

        assertEquals("TEXT_BLOCK_DELTA", run.single().type());
    }

    @Test
    void confirmStreamWithoutToolCallStartShouldEmitSignal() {
        var run = feed(META,
            new ToolResultEndEvent(RID, "call-1", "mcp__chart__render_png", ToolResultState.SUCCESS),
            new AgentEndEvent(RID));

        assertEquals(1, run.frames().size());
        assertEquals("TEXT_BLOCK_DELTA", run.single().type());
    }

    @Test
    void mixedBatchDirectErrorAndNormalSuccessShouldNotEmitSignal() {
        var run = feed(META,
            new ToolCallStartEvent(RID, "call-1", "mcp__chart__render_png"),
            new ToolCallStartEvent(RID, "call-2", "mcp__chart__plain"),
            new ToolResultEndEvent(RID, "call-1", "mcp__chart__render_png", ToolResultState.ERROR),
            new ToolResultEndEvent(RID, "call-2", "mcp__chart__plain", ToolResultState.SUCCESS),
            new AgentEndEvent(RID));

        assertTrue(run.frames().isEmpty());
    }

    @Test
    void mixedBatchDirectSuccessAndNormalSuccessShouldNotEmitSignal() {
        var run = feed(META,
            new ToolResultEndEvent(RID, "call-1", "mcp__chart__render_png", ToolResultState.SUCCESS),
            new ToolResultEndEvent(RID, "call-2", "mcp__chart__plain", ToolResultState.SUCCESS),
            new AgentEndEvent(RID));

        assertTrue(run.frames().isEmpty());
    }

    @Test
    void multiStepDirectErrorThenNormalSuccessShouldNotEmitSignal() {
        var run = feed(META,
            new ToolCallStartEvent(RID, "call-1", "mcp__chart__render_png"),
            new ToolResultEndEvent(RID, "call-1", "mcp__chart__render_png", ToolResultState.ERROR),
            new ModelCallStartEvent(RID),
            new ToolCallStartEvent(RID, "call-2", "mcp__chart__plain"),
            new ToolResultEndEvent(RID, "call-2", "mcp__chart__plain", ToolResultState.SUCCESS),
            new AgentEndEvent(RID));

        assertTrue(run.frames().isEmpty());
    }

    @Test
    void remoteForwardedDirectReturnEndShouldBeIgnored() {
        var forwarded = new ToolResultEndEvent(RID, "call-1", "mcp__chart__render_png", ToolResultState.SUCCESS);
        forwarded.withSource("child-agent");
        var run = feed(META, forwarded, new AgentEndEvent(RID));

        assertTrue(run.frames().isEmpty());
    }
}
