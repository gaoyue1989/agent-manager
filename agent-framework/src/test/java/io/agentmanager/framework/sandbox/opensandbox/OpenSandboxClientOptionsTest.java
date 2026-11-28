package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

class OpenSandboxClientOptionsTest {

    @Test
    void defaultsShouldMatchDesign() {
        var options = new OpenSandboxClientOptions();

        assertEquals("opensandbox", options.getType());
        assertEquals("opensandbox/code-interpreter:v1.1.0", options.getImage());
        assertEquals(Duration.ofMinutes(60), options.getTimeout());
        assertEquals("1", options.getResource().get("cpu"));
        assertEquals("1024Mi", options.getResource().get("memory"));
        assertEquals("/workspace", options.getWorkspaceRoot());
        assertTrue(options.getEnvironment().isEmpty());
    }

    @Test
    void fluentBuilderShouldSetAllFields() {
        var options = new OpenSandboxClientOptions()
            .serverUrl("192.168.31.155:8090")
            .apiKey("secret-key")
            .image("ubuntu:24.04")
            .timeout(Duration.ofMinutes(30))
            .resource(Map.of("cpu", "2", "memory", "2Gi"))
            .environment(Map.of("NODE_ENV", "production"))
            .workspaceRoot("/ws");

        assertEquals("192.168.31.155:8090", options.getServerUrl());
        assertEquals("secret-key", options.getApiKey());
        assertEquals("ubuntu:24.04", options.getImage());
        assertEquals(Duration.ofMinutes(30), options.getTimeout());
        assertEquals("2", options.getResource().get("cpu"));
        assertEquals("2Gi", options.getResource().get("memory"));
        assertEquals("production", options.getEnvironment().get("NODE_ENV"));
        assertEquals("/ws", options.getWorkspaceRoot());
    }

    @Test
    void skillSyncSwitchesShouldDefaultOnAndFluentToggleOff() {
        var options = new OpenSandboxClientOptions();
        assertTrue(options.isSkillRevisionGateEnabled());
        assertTrue(options.isSyncSnapshotDiffEnabled());

        var off = new OpenSandboxClientOptions()
            .skillRevisionGateEnabled(false)
            .syncSnapshotDiffEnabled(false);
        assertFalse(off.isSkillRevisionGateEnabled());
        assertFalse(off.isSyncSnapshotDiffEnabled());
    }

    @Test
    void createClientShouldReturnOpenSandboxClient() {
        var options = new OpenSandboxClientOptions()
            .serverUrl("192.168.31.155:8090")
            .apiKey("k");
        assertInstanceOf(OpenSandboxClient.class, options.createClient());
    }

    @Test
    void skillsFallbackOptionsShouldValidateModeAndStoreDir() {
        var options = new OpenSandboxClientOptions();
        assertEquals("off", options.getSkillsFallbackMode());
        assertEquals("/tmp/.skills-store", options.getSkillsFallbackStoreDir());
        assertEquals(64L * 1024 * 1024, options.getSkillsFallbackMaxBytes());
        assertEquals(4, options.getSkillsFallbackMaxConcurrency());
        assertEquals(5000L, options.getSkillsFallbackAcquireTimeoutMs());
        assertTrue(options.isSkillsFallbackOnVersionMismatch());

        assertTrue(OpenSandboxClientOptions.skillsFallbackPushEnabled("push"));
        assertTrue(OpenSandboxClientOptions.skillsFallbackPushEnabled(" PUSH "));
        assertFalse(OpenSandboxClientOptions.skillsFallbackPushEnabled("off"));
        assertFalse(OpenSandboxClientOptions.skillsFallbackPushEnabled(""));
        assertFalse(OpenSandboxClientOptions.skillsFallbackPushEnabled(null));

        assertTrue(OpenSandboxClientOptions.isValidFallbackStoreDir("/tmp/.skills-store"));
        assertTrue(OpenSandboxClientOptions.isValidFallbackStoreDir("/var/cache/skills_v1"));
        assertFalse(OpenSandboxClientOptions.isValidFallbackStoreDir("/"));
        assertFalse(OpenSandboxClientOptions.isValidFallbackStoreDir("/tmp/.skills-store/"));
        assertFalse(OpenSandboxClientOptions.isValidFallbackStoreDir("relative/path"));
        assertFalse(OpenSandboxClientOptions.isValidFallbackStoreDir("/tmp/../etc"));
        assertFalse(OpenSandboxClientOptions.isValidFallbackStoreDir("/tmp/bad dir"));
        assertFalse(OpenSandboxClientOptions.isValidFallbackStoreDir("/tmp/a'b"));
    }
}
