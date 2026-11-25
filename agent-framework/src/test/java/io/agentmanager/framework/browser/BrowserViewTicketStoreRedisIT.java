package io.agentmanager.framework.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
class BrowserViewTicketStoreRedisIT {

    private RedisConnectionFacade facade;
    private BrowserViewTicketStore store;

    @BeforeAll
    void setUp() {
        var url = System.getenv().getOrDefault("REDIS_IT_URL", "redis://127.0.0.1:6379");
        var prefix = System.getenv().getOrDefault("REDIS_IT_PREFIX", "");
        facade = RedisConnectionFacade.create(
            new AgentRedisProperties(url, 2000, 2000, 250_000,
                AgentRedisProperties.Mode.standalone, "", prefix));
        store = new BrowserViewTicketStore(facade,
            new BrowserProperties(true, 60, 6080, "/websockify", 60, List.of()));
    }

    @AfterAll
    void tearDown() {
        facade.close();
    }

    @Test
    void ticketShouldBeSingleUseWithTtl() {
        var sessionId = "browser-ticket-it-" + UUID.randomUUID();
        var ticket = store.issue(sessionId, "u-1");

        var ttl = facade.sync().ttl(store.key(ticket));
        assertTrue(ttl > 0 && ttl <= 60, "ticket ttl=" + ttl);

        var claim = store.consume(ticket);
        assertNotNull(claim);
        assertEquals(sessionId, claim.sessionId());
        assertEquals("u-1", claim.userId());
        assertNull(store.consume(ticket), "ticket 必须一次性消费");
        assertEquals(-2L, facade.sync().ttl(store.key(ticket)));
    }
}
