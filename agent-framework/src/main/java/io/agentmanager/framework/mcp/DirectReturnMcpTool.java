package io.agentmanager.framework.mcp;

import java.util.EnumSet;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.service.ActingDenials;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpTool;
import reactor.core.publisher.Mono;

public final class DirectReturnMcpTool extends ToolBase {

    private static final Logger log = LoggerFactory.getLogger(DirectReturnMcpTool.class);

    private static final EnumSet<ToolResultState> FAILED_STATES = EnumSet.of(
        ToolResultState.ERROR, ToolResultState.DENIED, ToolResultState.INTERRUPTED);

    private final McpTool delegate;

    public DirectReturnMcpTool(McpTool delegate) {
        super(ToolBase.builder()
            .name(delegate.getName())
            .description(delegate.getDescription())
            .inputSchema(delegate.getParameters())
            .readOnly(delegate.isReadOnly())
            .concurrencySafe(delegate.isConcurrencySafe())
            .mcp(delegate.getMcpName())
            .returnDirect(true));
        this.delegate = delegate;
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        var ctx = param != null ? param.getRuntimeContext() : null;
        boolean suppressDirectReturn = ctx != null
            && Boolean.TRUE.equals(ctx.get(ActingDenials.SUPPRESS_DIRECT_RETURN_KEY));
        return delegate.callAsync(param).map(result -> {
            boolean failed = result.isSuspended() || FAILED_STATES.contains(result.getState());
            boolean presentable = result.getOutput() != null && result.getOutput().stream()
                .anyMatch(block -> !(block instanceof TextBlock text)
                    || (text.getText() != null && !text.getText().isBlank()));
            if (failed) {
                return result;
            }
            if (!presentable) {
                log.warn("[MCP] direct_return tool '{}' returned empty content, "
                    + "short-circuit skipped so the model handles it", getName());
                return ToolResultBlock.error("MCP tool '" + getName()
                    + "' is configured as direct_return but returned empty content; "
                    + "nothing was presented to the user");
            }
            if (suppressDirectReturn) {
                String callId = result.getId();
                if ((callId == null || callId.isBlank()) && param != null && param.getToolUseBlock() != null) {
                    callId = param.getToolUseBlock().getId();
                }
                if (callId != null && !callId.isBlank()
                        && ctx.get(ActingDenials.SUPPRESSED_DIRECT_RETURN_IDS_KEY) instanceof java.util.Set<?> rawSet) {
                    @SuppressWarnings("unchecked")
                    java.util.Set<String> idSet = (java.util.Set<String>) rawSet;
                    idSet.add(callId);
                }
                return result.withState(ToolResultState.ERROR);
            }
            return result;
        });
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
                                                     PermissionContextState context) {
        return delegate.checkPermissions(toolInput, context);
    }
}
