package io.agentmanager.framework.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class SandboxConfigTest {

    @Test
    void defaultsShouldMatchDesign() {
        var config = new SandboxConfig(
            false, "opensandbox/code-interpreter:v1.1.0", 60, 30, 1024, 1,
            List.of("/opt/code-interpreter/code-interpreter.sh"),
            Duration.ofMillis(100),
            true, true, 900,
            true, true, false, 500, 30,
            new SandboxConfig.OpenSandboxConfig("192.168.31.155:8090", "key"));

        assertFalse(config.enabled());
        assertEquals("opensandbox/code-interpreter:v1.1.0", config.image());
        assertEquals(60, config.timeoutMinutes());
        assertEquals(1024, config.memoryMb());
        assertEquals(1, config.cpuCount());
        assertEquals(List.of("/opt/code-interpreter/code-interpreter.sh"), config.entrypoint());
        assertEquals(Duration.ofMillis(100), config.execdGraceShutdown());
        assertTrue(config.skillRevisionGateEnabled());
        assertTrue(config.syncSnapshotDiffEnabled());
        assertEquals("192.168.31.155:8090", config.opensandbox().serverUrl());
        assertEquals("key", config.opensandbox().apiKey());
        // oa-1110-repro-fixes-design §2.3b：建箱 HTTP 请求超时默认 30s（≠ timeoutMinutes 的 TTL 语义）
        assertEquals(30, config.requestTimeoutSeconds());
    }

    @Test
    void enabledFlagShouldBeOverridable() {
        var config = new SandboxConfig(
            true, "ubuntu:24.04", 30, 30, 512, 2, List.of("python", "main.py"),
            Duration.ofSeconds(1),
            true, true, 900,
            true, true, false, 500, 30,
            new SandboxConfig.OpenSandboxConfig("127.0.0.1:8090", "k"));

        assertTrue(config.enabled());
        assertEquals("ubuntu:24.04", config.image());
        assertEquals(30, config.timeoutMinutes());
        assertEquals(512, config.memoryMb());
        assertEquals(2, config.cpuCount());
        assertEquals(List.of("python", "main.py"), config.entrypoint());
        assertEquals(Duration.ofSeconds(1), config.execdGraceShutdown());
        assertEquals("127.0.0.1:8090", config.opensandbox().serverUrl());
    }
}
