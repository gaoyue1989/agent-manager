package io.agentmanager.framework.service;

import java.util.function.BiConsumer;
import java.util.function.Function;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolResultState;

public final class DirectReturnSignalTracker {

    private static final String SIGNAL_PAYLOAD =
        "{\"type\":\"TEXT_BLOCK_DELTA\",\"delta\":\"\",\"generateReason\":\"TOOL_RETURN_DIRECT\"}";

    private final Function<String, McpToolRegistrar.ToolCallMeta> toolMeta;
    private final BiConsumer<String, String> emitSynthetic;

    private boolean batchHasDirectReturn;
    private boolean batchHasNonDirectOrFailure;
    private boolean shortCircuitCandidate;
    private boolean sdkSignalSeen;

    public DirectReturnSignalTracker(Function<String, McpToolRegistrar.ToolCallMeta> toolMeta,
                                     BiConsumer<String, String> emitSynthetic) {
        this.toolMeta = toolMeta;
        this.emitSynthetic = emitSynthetic;
    }

    public void onEvent(AgentEvent event) {
        if (event == null || isRemoteForwarded(event)) {
            return;
        }
        if (event instanceof ToolResultEndEvent end) {
            var meta = toolMeta.apply(end.getToolCallName());
            boolean isDirect = meta != null && meta.directReturn();
            if (isDirect && end.getState() == ToolResultState.SUCCESS) {
                batchHasDirectReturn = true;
            } else {
                batchHasNonDirectOrFailure = true;
            }
            shortCircuitCandidate = batchHasDirectReturn && !batchHasNonDirectOrFailure;
        } else if (event instanceof ModelCallStartEvent) {
            batchHasDirectReturn = false;
            batchHasNonDirectOrFailure = false;
            shortCircuitCandidate = false;
        } else if (event instanceof TextBlockDeltaEvent delta) {
            var metadata = delta.getMetadata();
            if (metadata != null
                    && GenerateReason.TOOL_RETURN_DIRECT.name().equals(
                        metadata.get(AgentEvent.METADATA_GENERATE_REASON))) {
                sdkSignalSeen = true;
            }
        } else if (event instanceof AgentEndEvent && shortCircuitCandidate && !sdkSignalSeen) {
            emitSynthetic.accept("TEXT_BLOCK_DELTA", SIGNAL_PAYLOAD);
        }
    }

    private static boolean isRemoteForwarded(AgentEvent event) {
        String source = event.getSource();
        return source != null && !source.isBlank();
    }
}
