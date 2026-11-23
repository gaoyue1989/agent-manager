package io.agentmanager.framework.mcp;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import reactor.core.publisher.Mono;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DirectReturnMcpToolTest {

    private static final String SERVER = "order";
    private static final String REMOTE_NAME = "render_confirm_card";
    private static final String TOOL_NAME = "mcp__order__render_confirm_card";
    private static final Map<String, Object> INPUT = Map.of("order_id", "o-1");

    private final McpClientWrapper wrapper = mock(McpClientWrapper.class);

    private static List<McpSchema.Content> text(String value) {
        return List.of(new McpSchema.TextContent(value));
    }

    private DirectReturnMcpTool tool(boolean readOnly) {
        var delegate = new McpTool(TOOL_NAME, REMOTE_NAME, "render a confirm card",
            Map.of("type", "object"), null, wrapper, null, SERVER, readOnly);
        return new DirectReturnMcpTool(delegate);
    }

    private ToolCallParam param() {
        var toolUse = ToolUseBlock.builder().id("call-1").name(TOOL_NAME).input(INPUT).build();
        return ToolCallParam.builder().toolUseBlock(toolUse).input(INPUT).build();
    }

    private void serverReturns(McpSchema.CallToolResult result) {
        when(wrapper.callTool(anyString(), any(), any())).thenReturn(Mono.just(result));
    }

    private static String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    @Test
    void shouldMirrorDelegateMetadataAndFlagReturnDirect() {
        var delegate = new McpTool(TOOL_NAME, REMOTE_NAME, "render a confirm card",
            Map.of("type", "object"), null, wrapper, null, SERVER, true);
        var tool = new DirectReturnMcpTool(delegate);

        assertTrue(tool.isReturnDirect());
        assertFalse(delegate.isReturnDirect());
        assertEquals(delegate.getName(), tool.getName());
        assertEquals(delegate.getDescription(), tool.getDescription());
        assertEquals(delegate.getParameters(), tool.getParameters());
        assertEquals(delegate.getOutputSchema(), tool.getOutputSchema());
        assertTrue(tool.isReadOnly());
        assertFalse(tool.isConcurrencySafe());
        assertEquals(SERVER, tool.getMcpName());
    }

    @Test
    void shouldDelegateCallToRemoteToolAndKeepNonBlankResult() {
        var payload = "{\"title\":\"确认订单\",\"total\":299}";
        serverReturns(new McpSchema.CallToolResult(text(payload), false));

        var result = tool(false).callAsync(param()).block();

        assertNotNull(result);
        assertNotEquals(ToolResultState.ERROR, result.getState());
        assertEquals(payload, resultText(result));
        verify(wrapper).callTool(eq(REMOTE_NAME), eq(INPUT), any());
    }

    static List<McpSchema.CallToolResult> blankSuccessResults() {
        return List.of(
            new McpSchema.CallToolResult(text(""), false),
            new McpSchema.CallToolResult(text("  \n\t"), false),
            new McpSchema.CallToolResult(List.of(), false));
    }

    @ParameterizedTest
    @MethodSource("blankSuccessResults")
    void shouldTurnBlankSuccessIntoErrorSoModelHandlesIt(McpSchema.CallToolResult serverResult) {
        serverReturns(serverResult);

        var result = tool(false).callAsync(param()).block();

        assertNotNull(result);
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(resultText(result).contains(TOOL_NAME));
        assertTrue(resultText(result).contains("empty content"));
    }

    @Test
    void shouldKeepImageOnlyResultPresentable() {
        serverReturns(new McpSchema.CallToolResult(
            List.of(new McpSchema.ImageContent(null, "aGVsbG8=", "image/png")), false));

        var result = tool(false).callAsync(param()).block();

        assertNotNull(result);
        assertNotEquals(ToolResultState.ERROR, result.getState());
    }

    @Test
    void shouldKeepToolFailureUntouched() {
        serverReturns(new McpSchema.CallToolResult(text("order not found"), true));

        var result = tool(false).callAsync(param()).block();

        assertNotNull(result);
        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(resultText(result).contains("order not found"));
        assertFalse(resultText(result).contains("empty content"));
    }

    @Test
    void shouldDelegatePermissionCheckToMcpTool() {
        var context = PermissionContextState.builder().build();

        assertEquals(PermissionBehavior.ASK,
            tool(false).checkPermissions(INPUT, context).block().getBehavior());
        assertEquals(PermissionBehavior.ALLOW,
            tool(true).checkPermissions(INPUT, context).block().getBehavior());
    }

    private static PermissionEngine engineWith(PermissionBehavior behavior) {
        var rule = new PermissionRule(TOOL_NAME, null, behavior, "test");
        var context = PermissionContextState.builder().mode(PermissionMode.DEFAULT);
        switch (behavior) {
            case DENY -> context.addDenyRule(TOOL_NAME, rule);
            case ASK -> context.addAskRule(TOOL_NAME, rule);
            default -> context.addAllowRule(TOOL_NAME, rule);
        }
        return new PermissionEngine(context.build());
    }

    @Test
    void shouldHonorPermissionEngineRules() {
        var tool = tool(false);

        assertEquals(PermissionBehavior.DENY,
            engineWith(PermissionBehavior.DENY).checkPermission(tool, INPUT).block().getBehavior());
        assertEquals(PermissionBehavior.ASK,
            engineWith(PermissionBehavior.ASK).checkPermission(tool, INPUT).block().getBehavior());
        assertEquals(PermissionBehavior.ALLOW,
            engineWith(PermissionBehavior.ALLOW).checkPermission(tool, INPUT).block().getBehavior());
        assertEquals(PermissionBehavior.ASK,
            new PermissionEngine(PermissionContextState.builder().mode(PermissionMode.DEFAULT).build())
                .checkPermission(tool, INPUT).block().getBehavior());
    }
}
