package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import io.agentscope.harness.agent.HarnessAgent;

/**
 * {@link TurnControlRegistry}：按生效 RuntimeContext 键投递 SDK 会话级 interrupt。
 */
class TurnControlRegistryTest {

    @Test
    void interruptShouldUseEffectiveGatewayKeys() {
        var registry = new TurnControlRegistry();
        var agent = org.mockito.Mockito.mock(HarnessAgent.class);
        // Channel 链路：userId = 网关 peer（前端 sid），sessionId = 网关 gw-hash
        registry.register("frontend-sid", agent, "frontend-sid", "gw-hash");
        registry.bindTurn("frontend-sid", "rid-1");

        assertTrue(registry.interrupt("frontend-sid", "rid-1"));
        verify(agent).interrupt("frontend-sid", "gw-hash");
    }

    @Test
    void unknownSessionShouldReturnFalse() {
        var registry = new TurnControlRegistry();
        assertFalse(registry.interrupt("missing", "rid-1"));
        assertEquals(0, registry.size());
    }

    @Test
    void removeShouldDropEntry() {
        var registry = new TurnControlRegistry();
        registry.register("s1", org.mockito.Mockito.mock(HarnessAgent.class), "p", "gw");
        registry.bindTurn("s1", "rid-1");
        assertEquals(1, registry.size());

        registry.remove("s1");

        assertEquals(0, registry.size());
        assertFalse(registry.interrupt("s1", "rid-1"));
    }

    @Test
    void staleReplyIdShouldNotInterruptNewTurn() {
        var registry = new TurnControlRegistry();
        var agent = org.mockito.Mockito.mock(HarnessAgent.class);
        registry.register("s1", agent, "p", "gw");
        registry.bindTurn("s1", "rid-new");

        assertFalse(registry.interrupt("s1", "rid-old"),
            "旧 turn 的停止请求不得打到新一轮执行段");
        assertFalse(registry.interrupt("s1", null));
    }

    @Test
    void blankSessionIdShouldNotBeRegistered() {
        var registry = new TurnControlRegistry();
        registry.register(" ", org.mockito.Mockito.mock(HarnessAgent.class), "p", "gw");
        assertEquals(0, registry.size());
    }
}
