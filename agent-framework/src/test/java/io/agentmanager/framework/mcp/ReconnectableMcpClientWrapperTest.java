package io.agentmanager.framework.mcp;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReconnectableMcpClientWrapperTest {

    private McpClientWrapper stubBase(String marker) {
        var base = mock(McpClientWrapper.class);
        when(base.getName()).thenReturn(marker);
        when(base.listTools()).thenReturn(Mono.just(List.<McpSchema.Tool>of()));
        return base;
    }

    @Test
    void shouldDelegateAllCallsToCurrentDelegate() {
        var base = mock(McpClientWrapper.class);
        when(base.getName()).thenReturn("s");
        var tool = mock(McpSchema.Tool.class);
        when(base.getCachedTool("t")).thenReturn(tool);
        when(base.isInitialized()).thenReturn(true);
        when(base.listTools()).thenReturn(Mono.just(List.of(tool)));
        when(base.callTool("t", Map.of())).thenReturn(Mono.empty());

        var handle = new ReconnectableMcpClientWrapper("s", base);

        assertEquals("s", handle.getName());
        assertEquals(tool, handle.getCachedTool("t"));
        assertTrue(handle.isInitialized());
        assertSame(base, handle.delegate());
        handle.listTools().block();
        handle.callTool("t", Map.of()).block();
        verify(base).listTools();
        verify(base).callTool("t", Map.of());
    }

    @Test
    void swapShouldCloseOldAndRouteToNew() {
        var old = stubBase("old");
        var fresh = stubBase("fresh");
        var handle = new ReconnectableMcpClientWrapper("s", old);

        assertTrue(handle.swap(fresh));

        assertSame(fresh, handle.delegate());
        verify(old).close();
        verifyNoInteractions(fresh); // 换芯动作本身不触碰新连接
        handle.listTools().block();
        verify(fresh).listTools();
    }

    @Test
    void swapShouldRejectNullAndSameInstance() {
        var base = stubBase("base");
        var handle = new ReconnectableMcpClientWrapper("s", base);

        assertFalse(handle.swap(null));
        assertFalse(handle.swap(base));
        verifyNoInteractions(base); // 拒绝路径不得关闭现役连接
    }

    @Test
    void swapAfterCloseShouldRejectAndKeepHandleClosed() {
        var old = stubBase("old");
        var fresh = stubBase("fresh");
        var handle = new ReconnectableMcpClientWrapper("s", old);
        handle.close();
        verify(old).close();

        assertFalse(handle.swap(fresh));
        verifyNoInteractions(fresh); // reload 收尾竞态兜底：关死的句柄不得吞掉新连接
    }

    @Test
    void closeShouldDelegate() {
        var base = stubBase("base");
        var handle = new ReconnectableMcpClientWrapper("s", base);
        handle.close();
        verify(base).close();
    }

    @Test
    void nullDelegateShouldBeRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ReconnectableMcpClientWrapper("s", null));
    }
}
