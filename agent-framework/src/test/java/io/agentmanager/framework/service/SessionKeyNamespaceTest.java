package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionKeyNamespaceTest {

    @Test
    void userIdentityNamespaceIsReserved() {
        assertTrue(SessionKeyNamespace.isUserIdentity("u-1001"));
        assertTrue(SessionKeyNamespace.isUserIdentity("u-0"));
        assertTrue(SessionKeyNamespace.isUserIdentity("u-abc"));
        assertTrue(SessionKeyNamespace.isUserIdentity("debug-user"));
        assertFalse(SessionKeyNamespace.isUserIdentity("alice-sess"));
        assertFalse(SessionKeyNamespace.isUserIdentity("webui-abc"));
        assertFalse(SessionKeyNamespace.isUserIdentity("u1001"));
        assertFalse(SessionKeyNamespace.isUserIdentity(null));
    }

    @Test
    void gatewaySessionIdsAreDetectedStrictly() {
        assertTrue(SessionKeyNamespace.isGatewaySessionId("gw-3f20f08c5499"));
        assertTrue(SessionKeyNamespace.isGatewaySessionId("gw-a580c3c8af63"));
        assertFalse(SessionKeyNamespace.isGatewaySessionId("gw-x"));
        assertFalse(SessionKeyNamespace.isGatewaySessionId("peer:gw-abc"));
        assertFalse(SessionKeyNamespace.isGatewaySessionId("gw-3f20f08c549"));
        assertFalse(SessionKeyNamespace.isGatewaySessionId(null));
    }

    @Test
    void reservedSessionIdsCoverBothNamespaces() {
        assertTrue(SessionKeyNamespace.isReservedSessionId("u-1001"));
        assertTrue(SessionKeyNamespace.isReservedSessionId("debug-user"));
        assertTrue(SessionKeyNamespace.isReservedSessionId("gw-anything"));
        assertFalse(SessionKeyNamespace.isReservedSessionId("alice-sess"));
        assertFalse(SessionKeyNamespace.isReservedSessionId("webui-abc"));
        assertFalse(SessionKeyNamespace.isReservedSessionId(null));
    }
}
