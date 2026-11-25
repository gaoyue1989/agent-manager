package io.agentmanager.framework.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.agentmanager.framework.config.AgentRedisProperties;
import io.agentmanager.framework.config.BrowserProperties;
import io.agentmanager.framework.redis.RedisConnectionFacade;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "REDIS_IT", matches = "true|1")
class BrowserControlStoreRedisIT {

    private RedisConnectionFacade facade;
    private BrowserControlStore store;
    private String sessionId;

    @BeforeAll
    void setUp() {
        var url = System.getenv().getOrDefault("REDIS_IT_URL", "redis://127.0.0.1:6379");
        var prefix = System.getenv().getOrDefault("REDIS_IT_PREFIX", "");
        facade = RedisConnectionFacade.create(
            new AgentRedisProperties(url, 2000, 2000, 250_000,
                AgentRedisProperties.Mode.standalone, "", prefix));
        store = new BrowserControlStore(facade, new BrowserProperties(true, 60, 6080, "/websockify", 60, java.util.List.of()));
    }

    @AfterAll
    void tearDown() {
        if (sessionId != null) {
            facade.sync().del(store.key(sessionId));
        }
        facade.close();
    }

    @Test
    void takeoverReleaseCycleShouldBeVisibleAcrossReads() {
        sessionId = "browser-it-" + UUID.randomUUID();

        assertFalse(store.isHumanControlled(sessionId));
        assertNull(store.find(sessionId));
        assertFalse(store.release(sessionId), "无接管时释放返回 false");

        store.takeover(sessionId, "need human login", "u-1", "agent");
        assertTrue(store.isHumanControlled(sessionId));
        var state = store.find(sessionId);
        assertNotNull(state);
        assertEquals("need human login", state.reason());
        assertEquals("u-1", state.by());
        assertEquals("agent", state.trigger());
        assertTrue(store.ttlSeconds(sessionId) > 0);

        assertTrue(store.release(sessionId));
        assertFalse(store.isHumanControlled(sessionId));
        assertNull(store.find(sessionId));
    }
}
