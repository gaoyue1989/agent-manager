package io.agentmanager.framework.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.BrowserProperties;
import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;

class BrowserControlStoreTest {

    private static final String SESSION = "sess-1";

    private RedisConnectionFacade facade;
    @SuppressWarnings("unchecked")
    private final RedisClusterCommands<String, String> sync = mock(RedisClusterCommands.class);
    private BrowserControlStore store;

    @BeforeEach
    void setUp() {
        facade = mock(RedisConnectionFacade.class);
        when(facade.key(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(facade.sync()).thenReturn(sync);
        store = new BrowserControlStore(facade, new BrowserProperties(true, 60, 6080, "/websockify"));
    }

    private static String key() {
        return BrowserControlStore.KEY_PREFIX + SESSION;
    }

    @Test
    void takeoverShouldWriteStateWithTtl() {
        store.takeover(SESSION, "need login", "u-1", "user");

        verify(sync).set(eq(key()), contains("\"reason\":\"need login\""), any(SetArgs.class));
        verify(sync).set(eq(key()), contains("\"by\":\"u-1\""), any(SetArgs.class));
        verify(sync).set(eq(key()), contains("\"trigger\":\"user\""), any(SetArgs.class));
    }

    @Test
    void releaseShouldReportWhetherControlWasHeld() {
        when(sync.del(key())).thenReturn(1L);
        assertTrue(store.release(SESSION));

        when(sync.del(key())).thenReturn(0L);
        assertFalse(store.release(SESSION));
    }

    @Test
    void isHumanControlledShouldFollowKeyExistence() {
        when(sync.exists(key())).thenReturn(1L);
        assertTrue(store.isHumanControlled(SESSION));

        when(sync.exists(key())).thenReturn(0L);
        assertFalse(store.isHumanControlled(SESSION));
    }

    @Test
    void findShouldParsePayloadAndReturnNullWhenAbsent() {
        when(sync.get(key())).thenReturn(
            "{\"reason\":\"2fa\",\"by\":\"u-1\",\"trigger\":\"agent\",\"since\":1700000000000}");
        var state = store.find(SESSION);
        assertEquals("2fa", state.reason());
        assertEquals("u-1", state.by());
        assertEquals("agent", state.trigger());
        assertEquals(1700000000000L, state.sinceEpochMs());

        when(sync.get(key())).thenReturn(null);
        assertNull(store.find(SESSION));
    }

    @Test
    void ttlSecondsShouldPassThrough() {
        when(sync.ttl(key())).thenReturn(120L);
        assertEquals(120L, store.ttlSeconds(SESSION));
    }

    @Test
    void redisFailureShouldSurfaceAsUnavailable() {
        when(sync.set(anyString(), anyString(), any())).thenThrow(new IllegalStateException("down"));
        when(sync.del(anyString())).thenThrow(new IllegalStateException("down"));
        when(sync.exists(anyString())).thenThrow(new IllegalStateException("down"));
        when(sync.get(anyString())).thenThrow(new IllegalStateException("down"));

        assertThrows(BrowserControlUnavailableException.class,
            () -> store.takeover(SESSION, "r", "u", "user"));
        assertThrows(BrowserControlUnavailableException.class, () -> store.release(SESSION));
        assertThrows(BrowserControlUnavailableException.class, () -> store.isHumanControlled(SESSION));
        assertThrows(BrowserControlUnavailableException.class, () -> store.find(SESSION));
    }
}
