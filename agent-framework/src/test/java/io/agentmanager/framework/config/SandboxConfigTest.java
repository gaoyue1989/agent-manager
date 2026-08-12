package io.agentmanager.framework.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SandboxConfigTest {

    @Test
    void defaultsShouldMatchDesign() {
        var config = new SandboxConfig(
            false, "opensandbox/code-interpreter:v1.1.0", 60, 1024, 1,
            new SandboxConfig.OpenSandboxConfig("192.168.31.155:8090", "key"));

        assertFalse(config.enabled());
        assertEquals("opensandbox/code-interpreter:v1.1.0", config.image());
        assertEquals(60, config.timeoutMinutes());
        assertEquals(1024, config.memoryMb());
        assertEquals(1, config.cpuCount());
        assertEquals("192.168.31.155:8090", config.opensandbox().serverUrl());
        assertEquals("key", config.opensandbox().apiKey());
    }

    @Test
    void enabledFlagShouldBeOverridable() {
        var config = new SandboxConfig(
            true, "ubuntu:24.04", 30, 512, 2,
            new SandboxConfig.OpenSandboxConfig("127.0.0.1:8090", "k"));

        assertTrue(config.enabled());
        assertEquals("ubuntu:24.04", config.image());
        assertEquals(30, config.timeoutMinutes());
        assertEquals(512, config.memoryMb());
        assertEquals(2, config.cpuCount());
        assertEquals("127.0.0.1:8090", config.opensandbox().serverUrl());
    }
}
