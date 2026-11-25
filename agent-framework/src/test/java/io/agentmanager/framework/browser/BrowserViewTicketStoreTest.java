package io.agentmanager.framework.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.agentmanager.framework.config.BrowserProperties;
import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.lettuce.core.RedisException;
import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;

class BrowserViewTicketStoreTest {

    private static final String SESSION = "sess-1";
    private static final String USER = "u-1";
    private static final String PREFIX = "svc:";

    private final RedisConnectionFacade facade = mock(RedisConnectionFacade.class);
    @SuppressWarnings("unchecked")
    private final RedisClusterCommands<String, String> commands = mock(RedisClusterCommands.class);
    private final BrowserProperties props =
        new BrowserProperties(true, 60, 6080, "/websockify", 60, List.of());
    private final BrowserViewTicketStore store = new BrowserViewTicketStore(facade, props);

    @BeforeEach
    void setUp() {
        when(facade.sync()).thenReturn(commands);
        when(facade.key(anyString())).thenAnswer(invocation -> PREFIX + invocation.getArgument(0));
    }

    @Test
    void issueShouldStoreClaimWithTtlAndReturnOpaqueTicket() throws Exception {
        var ticket = store.issue(SESSION, USER);

        assertNotNull(ticket);
        assertTrue(ticket.length() >= 32);
        var value = ArgumentCaptor.forClass(String.class);
        var args = ArgumentCaptor.forClass(SetArgs.class);
        verify(commands).set(eq(PREFIX + BrowserViewTicketStore.KEY_PREFIX + ticket), value.capture(),
            args.capture());
        assertTrue(value.getValue().contains("\"sid\":\"" + SESSION + "\""));
        assertTrue(value.getValue().contains("\"uid\":\"" + USER + "\""));
        assertNotNull(args.getValue());
    }

    @Test
    void consumeShouldReturnClaimAndDeleteKey() {
        when(commands.getdel(PREFIX + BrowserViewTicketStore.KEY_PREFIX + "t1"))
            .thenReturn("{\"sid\":\"" + SESSION + "\",\"uid\":\"" + USER + "\"}");

        var claim = store.consume("t1");

        assertNotNull(claim);
        assertEquals(SESSION, claim.sessionId());
        assertEquals(USER, claim.userId());
    }

    @Test
    void consumeShouldReturnNullForUnknownTicket() {
        when(commands.getdel(anyString())).thenReturn(null);

        assertNull(store.consume("unknown"));
    }

    @Test
    void consumeShouldFailClosedWhenRedisFails() {
        when(commands.getdel(anyString())).thenThrow(new RedisException("down"));

        assertNull(store.consume("t1"));
    }

    @Test
    void consumeShouldReturnNullForMalformedPayload() {
        when(commands.getdel(anyString())).thenReturn("not-json");

        assertNull(store.consume("t1"));
    }

    @Test
    void issueShouldReportUnavailableWhenRedisFails() {
        when(commands.set(anyString(), anyString(), any(SetArgs.class)))
            .thenThrow(new RedisException("down"));

        assertThrows(BrowserControlUnavailableException.class, () -> store.issue(SESSION, USER));
    }
}
