package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class SessionAccessGuardTest {

    private final SessionUserStore store = mock(SessionUserStore.class);

    @Test
    void reservedSessionIdsAreNeverOwned() {
        assertFalse(SessionAccessGuard.ownedBy(store, "u-1001", "u-2002"));
        assertFalse(SessionAccessGuard.ownedBy(store, "debug-user", "debug-user"));
        assertFalse(SessionAccessGuard.ownedBy(store, "gw-3f20f08c5499", "u-1001"));
        verifyNoInteractions(store);
    }

    @Test
    void unclaimedSessionIsOpenForClaim() {
        when(store.findUserIdBySession("s-1")).thenReturn(null);
        assertTrue(SessionAccessGuard.ownedBy(store, "s-1", "u-1"));
    }

    @Test
    void ownerAndPlaceholderSemanticsUnchanged() {
        when(store.findUserIdBySession("s-1")).thenReturn("u-1");
        when(store.findUserIdBySession("s-ph")).thenReturn(SessionUserStore.PLACEHOLDER_USER);

        assertTrue(SessionAccessGuard.ownedBy(store, "s-1", "u-1"));
        assertFalse(SessionAccessGuard.ownedBy(store, "s-1", "u-2"));
        assertTrue(SessionAccessGuard.ownedBy(store, "s-ph", "u-2"));
    }
}
