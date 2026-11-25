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

    @Test
    void poisonedFlagShouldExposeAndClearOnSuccessfulSwap() {
        // issue #417：注册失败的驱逐改为毒化标记（句柄留缓存原地换芯）——毒化标志可读、
        // 成功换芯即自愈清除；拒绝型 swap（closed/null/同实例）不清毒化
        var old = stubBase("old");
        var fresh = stubBase("fresh");
        var handle = new ReconnectableMcpClientWrapper("s", old);
        assertFalse(handle.poisoned());
        assertTrue(handle.markPoisonedIfSame(old));
        assertTrue(handle.poisoned());
        assertTrue(handle.swap(fresh));
        assertFalse(handle.poisoned(), "成功换芯 = 连接已重建，毒化应清除");
    }

    @Test
    void markPoisonedIfSameShouldIgnoreStaleFailureAfterSwap() {
        // issue #493 缺陷①：会话持旧 delegate 调用失败期间，看门狗已换芯健康新连接——
        // 旧连接的迟到失败不得把新 delegate 重新毒化（否则触发不必要的销毁重建风暴）
        var old = stubBase("old");
        var fresh = stubBase("fresh");
        var handle = new ReconnectableMcpClientWrapper("s", old);
        var staleSnapshot = handle.delegate();
        assertTrue(handle.swap(fresh));

        assertFalse(handle.markPoisonedIfSame(staleSnapshot), "delegate 已换芯，旧失败必须被拒");
        assertFalse(handle.poisoned(), "换芯后的健康 delegate 不得被旧连接的失败毒化");
        assertTrue(handle.markPoisonedIfSame(fresh), "对当前 delegate 的失败仍应毒化");
        assertTrue(handle.poisoned());
    }

    @Test
    void markPoisonedIfSameShouldIgnoreWhenClosed() {
        var base = stubBase("base");
        var handle = new ReconnectableMcpClientWrapper("s", base);
        handle.close();

        assertFalse(handle.markPoisonedIfSame(base), "closed 终态句柄无换芯价值，毒化无意义");
    }

    @Test
    void closeShouldRaceSafelyWithSwap() throws Exception {
        // issue #417：close 未与 swap 同步——close 在 swap 装新关旧之间进入会关掉刚装的
        // next 而 swap 仍返回 true（调用方误以为换芯成功，使用死连接）。close 加锁后两段
        // 串行化，并发压测下关闭次数守恒：每个 delegate 桩的 close 至多一次（swap 成功关旧
        // 1 次，或 handle.close 关当时 delegate；重复 close 是误杀直接证据）
        for (int round = 0; round < 300; round++) {
            var first = stubBase("d" + round + "-0");
            var handle = new ReconnectableMcpClientWrapper("s", first);
            var bases = new java.util.ArrayList<McpClientWrapper>();
            for (int i = 1; i <= 6; i++) {
                bases.add(stubBase("d" + round + "-" + i));
            }
            var closer = new Thread(handle::close);
            var swapper = new Thread(() -> {
                for (var next : bases) {
                    handle.swap(next);
                }
            });
            closer.start();
            swapper.start();
            closer.join();
            swapper.join();
            org.mockito.Mockito.verify(first, org.mockito.Mockito.atMostOnce()).close();
            for (var next : bases) {
                org.mockito.Mockito.verify(next, org.mockito.Mockito.atMostOnce()).close();
            }
            assertTrue(handle.isClosed(), "句柄终态必须 closed");
        }
    }

    @Test
    void clearPoisonIfSameShouldIgnoreStaleDelegate() {
        var base1 = mock(McpClientWrapper.class);
        var base2 = mock(McpClientWrapper.class);
        var handle = new ReconnectableMcpClientWrapper("srv", base1);

        handle.swap(base2);
        assertTrue(handle.markPoisonedIfSame(base2));
        assertTrue(handle.poisoned());

        assertFalse(handle.clearPoisonIfSame(base1));
        assertTrue(handle.poisoned());

        assertTrue(handle.clearPoisonIfSame(base2));
        assertFalse(handle.poisoned());
    }
}
