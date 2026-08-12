package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;

/**
 * SandboxAwareMysqlAgentStateStore 校验放宽验证：
 * 沙箱 slot ID（sandbox/user/{agentId}/{userId}）含 "/" 必须放行，空 ID 拒绝。
 */
class SandboxAwareMysqlAgentStateStoreTest {

    private final DataSource ds = Mockito.mock(DataSource.class, Mockito.RETURNS_DEEP_STUBS);

    private SandboxAwareMysqlAgentStateStore newStore() {
        // 父类构造会 verifyDatabaseExists（连接检查）：mock 库存在
        try {
            org.mockito.Mockito.when(ds.getConnection().prepareStatement(
                org.mockito.ArgumentMatchers.anyString()).executeQuery().next()).thenReturn(true);
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
        return new SandboxAwareMysqlAgentStateStore(ds, "db", "agent_state", false);
    }

    @Test
    void shouldAcceptSandboxSlotIdWithSlashes() {
        var store = newStore();

        // 通过反射调用 protected validateSessionId
        assertDoesNotThrow(() -> invokeValidate(store, "sandbox/user/Minimal Agent/sandbox-e2e-user"));
        assertDoesNotThrow(() -> invokeValidate(store, "sandbox/session/s1"));
        assertDoesNotThrow(() -> invokeValidate(store, "sandbox/agent/a1"));
        assertDoesNotThrow(() -> invokeValidate(store, "normal-session-id"));
    }

    @Test
    void shouldRejectBlankId() {
        var store = newStore();

        assertThrows(IllegalArgumentException.class, () -> invokeValidate(store, ""));
        assertThrows(IllegalArgumentException.class, () -> invokeValidate(store, null));
        assertThrows(IllegalArgumentException.class, () -> invokeValidate(store, "   "));
    }

    private static void invokeValidate(SandboxAwareMysqlAgentStateStore store, String id) throws Exception {
        var m = io.agentscope.extensions.mysql.state.MysqlAgentStateStore.class
            .getDeclaredMethod("validateSessionId", String.class);
        m.setAccessible(true);
        try {
            m.invoke(store, id);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // 反射包装：透传业务异常
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }
}
