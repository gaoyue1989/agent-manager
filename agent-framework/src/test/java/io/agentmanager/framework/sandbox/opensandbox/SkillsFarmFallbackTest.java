package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionLogs;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.services.Commands;
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem;

import io.agentmanager.framework.service.WorkspaceReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class SkillsFarmFallbackTest {

    private static Execution execResult(int exitCode, String stdout) {
        var exec = mock(Execution.class);
        when(exec.getExitCode()).thenReturn(exitCode);
        var logs = mock(ExecutionLogs.class);
        if (stdout != null && !stdout.isEmpty()) {
            var msg = mock(OutputMessage.class);
            when(msg.getText()).thenReturn(stdout);
            when(logs.getStdout()).thenReturn(List.of(msg));
        } else {
            when(logs.getStdout()).thenReturn(List.of());
        }
        when(exec.getLogs()).thenReturn(logs);
        return exec;
    }

    private static Sandbox mockSandbox(Commands commands, Filesystem files) {
        var sb = mock(Sandbox.class);
        when(sb.commands()).thenReturn(commands);
        when(sb.files()).thenReturn(files);
        return sb;
    }

    private static OpenSandboxClientOptions pushOptions(SkillBundleService svc, SimpleMeterRegistry registry) {
        return new OpenSandboxClientOptions()
            .skillsSharedStore("/skills-store")
            .skillBundleService(svc)
            .farmMeterRegistry(registry)
            .skillsFallbackMode("push")
            .skillsFallbackStoreDir("/tmp/.skills-store")
            .skillsFallbackMaxBytes(64L * 1024 * 1024)
            .skillsFallbackSemaphore(new Semaphore(4))
            .skillsFallbackAcquireTimeoutMs(1000L)
            .skillsFallbackOnVersionMismatch(true);
    }

    @Test
    void masterSwitchOffShouldNeverTriggerFallbackPush() throws Exception {
        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        var svc = mock(SkillBundleService.class);
        var registry = new SimpleMeterRegistry();
        var opts = pushOptions(svc, registry).skillsSharedStore("off");
        var spec = new OpenSandboxFilesystemSpec();
        var farm = new SkillsFarm(opts, mockSandbox(commands, files), null, spec, () -> "u1", () -> "sbx-1");

        assertFalse(farm.tryHydrate("u1"));
        verifyNoInteractions(commands, files, svc);
        assertNull(registry.find("skills.farm.decision").counter());
    }

    @Test
    void storeMissingAndEndpointDownAndVersionMismatchShouldFallbackToPush() throws Exception {
        var svc = mock(SkillBundleService.class);
        var ver = new SkillBundleService.SkillVersion("v111", 1, 2, 100L, List.of("alpha"));
        when(svc.currentVersion()).thenReturn(ver);
        when(svc.bundle("v111")).thenReturn(Optional.of(
            new SkillBundleService.BundlePayload(16L, "sha", () -> new ByteArrayInputStream(new byte[16]))));

        for (String scenario : List.of("store_missing", "daemon_endpoint_down", "version_mismatch")) {
            var commands = mock(Commands.class);
            var files = mock(Filesystem.class);
            var registry = new SimpleMeterRegistry();
            if ("store_missing".equals(scenario)) {
                var res1 = execResult(1, ""); when(commands.run("readlink '/skills-store/CURRENT' && test -d '/skills-store/CURRENT'")).thenReturn(res1);
                var res2 = execResult(1, ""); when(commands.run("test -d '/skills-store'")).thenReturn(res2);
            } else if ("daemon_endpoint_down".equals(scenario)) {
                var res3 = execResult(1, ""); when(commands.run("readlink '/skills-store/CURRENT' && test -d '/skills-store/CURRENT'")).thenReturn(res3);
                var res4 = execResult(0, ""); when(commands.run("test -d '/skills-store'")).thenReturn(res4);
            } else {
                var res5 = execResult(0, "v-old999"); when(commands.run("readlink '/skills-store/CURRENT' && test -d '/skills-store/CURRENT'")).thenReturn(res5);
            }
            var res6 = execResult(1, ""); when(commands.run(SkillBundlePusher.probeCommand("/tmp/.skills-store"))).thenReturn(res6);
            var r_101 = execResult(0, ""); when(commands.run(startsWith("set -e;"))).thenReturn(r_101);
            var res7 = execResult(0, ""); when(commands.run(contains("ln -s '/tmp/.skills-store/CURRENT/alpha'"))).thenReturn(res7);

            var spec = new OpenSandboxFilesystemSpec();
            var farm = new SkillsFarm(pushOptions(svc, registry), mockSandbox(commands, files),
                null, spec, () -> "u1", () -> "sbx-" + scenario);

            assertTrue(farm.tryHydrate("u1"), scenario);
            assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "fallback_push_synced")
                .counter().count(), scenario);
            verify(files, times(1)).write(anyList());
            verify(commands, times(1)).run(contains("ln -s '/tmp/.skills-store/CURRENT/alpha'"));
        }
    }

    @Test
    void versionMismatchShouldNotFallbackWhenMismatchSwitchDisabled() throws Exception {
        var svc = mock(SkillBundleService.class);
        when(svc.currentVersion()).thenReturn(
            new SkillBundleService.SkillVersion("v111", 1, 2, 100L, List.of("alpha")));
        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        var res8 = execResult(0, "v-other"); when(commands.run("readlink '/skills-store/CURRENT' && test -d '/skills-store/CURRENT'")).thenReturn(res8);
        var registry = new SimpleMeterRegistry();
        var opts = pushOptions(svc, registry).skillsFallbackOnVersionMismatch(false);
        var farm = new SkillsFarm(opts, mockSandbox(commands, files), null,
            new OpenSandboxFilesystemSpec(), () -> "u1", () -> "sbx-mm");

        assertFalse(farm.tryHydrate("u1"));
        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "degraded_version_mismatch")
            .counter().count());
        verify(files, never()).write(anyList());
    }

    @Test
    void localEndpointErrorShouldNeverFallbackPush() throws Exception {
        var svc = mock(SkillBundleService.class);
        when(svc.currentVersion()).thenReturn(
            new SkillBundleService.SkillVersion("ERR-999", 0, 0, 0L, List.of()));
        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        var registry = new SimpleMeterRegistry();
        var farm = new SkillsFarm(pushOptions(svc, registry), mockSandbox(commands, files), null,
            new OpenSandboxFilesystemSpec(), () -> "u1", () -> "sbx-err");

        assertFalse(farm.tryHydrate("u1"));
        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "degraded_endpoint_down")
            .counter().count());
        verifyNoInteractions(commands, files);
    }

    @Test
    void emptyPackageSkillsShouldSkipUploadAndConvergeCleanly() throws Exception {
        var svc = mock(SkillBundleService.class);
        when(svc.currentVersion()).thenReturn(
            new SkillBundleService.SkillVersion("emptyver", 0, 0, 0L, List.of()));
        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        var res9 = execResult(1, ""); when(commands.run("readlink '/skills-store/CURRENT' && test -d '/skills-store/CURRENT'")).thenReturn(res9);
        var res10 = execResult(1, ""); when(commands.run("test -d '/skills-store'")).thenReturn(res10);
        var res11 = execResult(0, ""); when(commands.run(startsWith("{ find "))).thenReturn(res11);
        var registry = new SimpleMeterRegistry();
        var farm = new SkillsFarm(pushOptions(svc, registry), mockSandbox(commands, files), null,
            new OpenSandboxFilesystemSpec(), () -> "u1", () -> "sbx-empty");

        assertTrue(farm.tryHydrate("u1"));
        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "fallback_push_hit")
            .counter().count());
        verify(svc, never()).bundle(anyString());
        verify(files, never()).write(anyList());
    }

    @Test
    void pushModeShouldRespectL4PrecedenceAndDisabledSkills() throws Exception {
        var svc = mock(SkillBundleService.class);
        when(svc.currentVersion()).thenReturn(
            new SkillBundleService.SkillVersion("v200", 3, 6, 300L, List.of("alpha", "beta", "gamma")));
        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        var res12 = execResult(1, ""); when(commands.run("readlink '/skills-store/CURRENT' && test -d '/skills-store/CURRENT'")).thenReturn(res12);
        var res13 = execResult(1, ""); when(commands.run("test -d '/skills-store'")).thenReturn(res13);
        var res14 = execResult(0, "v-v200"); when(commands.run(SkillBundlePusher.probeCommand("/tmp/.skills-store"))).thenReturn(res14);
        var res15 = execResult(0, ""); when(commands.run(contains("ln -s '/tmp/.skills-store/CURRENT/alpha'"))).thenReturn(res15);

        var reader = mock(WorkspaceReader.class);
        when(reader.listUserSkills("u1")).thenReturn(Map.of("beta", List.of("SKILL.md")));
        when(reader.readUserSkillRevision("u1")).thenReturn("rev1");

        var checker = mock(SkillDisabledChecker.class);
        when(checker.packageDisabled()).thenReturn(Set.of());
        when(checker.isUserDisabled("u1", "gamma")).thenReturn(true);

        var registry = new SimpleMeterRegistry();
        var opts = pushOptions(svc, registry).skillDisabledChecker(checker);
        var farm = new SkillsFarm(opts, mockSandbox(commands, files), reader,
            new OpenSandboxFilesystemSpec(), () -> "u1", () -> "sbx-l4");

        assertTrue(farm.tryHydrate("u1"));
        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "fallback_push_hit")
            .counter().count());
        verify(files, never()).write(anyList());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(commands, atLeastOnce()).run(captor.capture());
        String converge = captor.getAllValues().stream()
            .filter(c -> c.contains("ln -s"))
            .findFirst().orElseThrow();
        assertTrue(converge.contains("ln -s '/tmp/.skills-store/CURRENT/alpha'"));
        assertFalse(converge.contains("ln -s '/tmp/.skills-store/CURRENT/beta'"));
        assertFalse(converge.contains("ln -s '/tmp/.skills-store/CURRENT/gamma'"));
        assertTrue(converge.contains("rm -rf '/workspace/.skills-cache/u1/oaf-package/gamma'"));
    }

    @Test
    void pushModeSteadyShortCircuitAndVersionBumpAndModeFlipShouldConvergeCorrectly() throws Exception {
        var currentVer = new AtomicReference<>(
            new SkillBundleService.SkillVersion("v1", 1, 2, 100L, List.of("alpha")));
        var svc = mock(SkillBundleService.class);
        when(svc.currentVersion()).thenAnswer(inv -> currentVer.get());
        when(svc.bundle(anyString())).thenAnswer(inv -> Optional.of(
            new SkillBundleService.BundlePayload(16L, "sha", () -> new ByteArrayInputStream(new byte[16]))));

        var primaryCurrent = new AtomicReference<String>(null);
        var fallbackCurrent = new AtomicReference<String>(null);
        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        when(commands.run(anyString())).thenAnswer(inv -> {
            String cmd = inv.getArgument(0);
            if ("readlink '/skills-store/CURRENT' && test -d '/skills-store/CURRENT'".equals(cmd)) {
                String v = primaryCurrent.get();
                return v != null ? execResult(0, v) : execResult(1, "");
            }
            if ("test -d '/skills-store'".equals(cmd)) {
                return execResult(1, "");
            }
            if (SkillBundlePusher.probeCommand("/tmp/.skills-store").equals(cmd)) {
                String v = fallbackCurrent.get();
                return v != null ? execResult(0, v) : execResult(1, "");
            }
            if (cmd.startsWith("set -e;")) {
                fallbackCurrent.set("v-" + currentVer.get().version());
                return execResult(0, "");
            }
            return execResult(0, "");
        });

        var registry = new SimpleMeterRegistry();
        var spec = new OpenSandboxFilesystemSpec();
        var opts = pushOptions(svc, registry);
        var farm = new SkillsFarm(opts, mockSandbox(commands, files), null, spec, () -> "u1", () -> "sbx-flip");

        assertTrue(farm.tryHydrate("u1"));
        verify(files, times(1)).write(anyList());
        assertEquals("v1|", spec.scopedSkillCacheFingerprint("sbx-flip", "u1",
            OpenSandboxFilesystemSpec.HYDRATE_MODE_PUSH));
        assertNull(spec.scopedSkillCacheFingerprint("sbx-flip", "u1",
            OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM));

        clearInvocations(commands, files);
        assertTrue(farm.tryHydrate("u1"));
        verifyNoInteractions(commands, files);

        currentVer.set(new SkillBundleService.SkillVersion("v2", 1, 2, 120L, List.of("alpha")));
        assertTrue(farm.tryHydrate("u1"));
        verify(files, times(1)).write(anyList());
        assertEquals("v2|", spec.scopedSkillCacheFingerprint("sbx-flip", "u1",
            OpenSandboxFilesystemSpec.HYDRATE_MODE_PUSH));

        spec.removeSkillSyncGate("u1", "sbx-flip");
        primaryCurrent.set("v-v2");
        clearInvocations(commands, files);
        assertTrue(farm.tryHydrate("u1"));
        verify(files, never()).write(anyList());
        verify(commands, times(1)).run(contains("ln -s '/skills-store/CURRENT/alpha'"));
        assertEquals("v2|", spec.scopedSkillCacheFingerprint("sbx-flip", "u1",
            OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM));
    }
}
