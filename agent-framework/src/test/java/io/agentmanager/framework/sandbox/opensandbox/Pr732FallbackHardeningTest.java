package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionError;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionLogs;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.services.Commands;
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.LoggerFactory;

class Pr732FallbackHardeningTest {

    private static Execution execResult(Integer exitCode, String stdout, ExecutionError error) {
        var exec = mock(Execution.class);
        when(exec.getExitCode()).thenReturn(exitCode);
        when(exec.getError()).thenReturn(error);
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

    private static Sandbox mockSandbox(String id, Commands commands, Filesystem files) {
        var sb = mock(Sandbox.class);
        when(sb.getId()).thenReturn(id);
        when(sb.commands()).thenReturn(commands);
        when(sb.files()).thenReturn(files);
        return sb;
    }

    private static OpenSandboxClientOptions pushOptions(SkillBundleService svc, SimpleMeterRegistry registry,
                                                        Semaphore sem) {
        return new OpenSandboxClientOptions()
            .skillsSharedStore("/skills-store")
            .skillBundleService(svc)
            .farmMeterRegistry(registry)
            .skillsFallbackMode("push")
            .skillsFallbackStoreDir("/tmp/.skills-store")
            .skillsFallbackMaxBytes(64L * 1024 * 1024)
            .skillsFallbackSemaphore(sem)
            .skillsFallbackAcquireTimeoutMs(10L)
            .skillsFallbackOnVersionMismatch(true);
    }

    private static byte[] createRealTarGz(Path workDir, String skillName, String content) throws Exception {
        Path src = Files.createTempDirectory(workDir, "tar-src-");
        Path skillDir = src.resolve(skillName);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), content, StandardCharsets.UTF_8);
        Path tarGz = Files.createTempFile(workDir, "bundle-", ".tar.gz");
        Process p = new ProcessBuilder("tar", "czf", tarGz.toString(), "-C", src.toString(), ".").start();
        assertEquals(0, p.waitFor());
        return Files.readAllBytes(tarGz);
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static int runSh(String script) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", script).start();
        return p.waitFor();
    }

    @Test
    void issue733_transientThrottleAndProbeFailureShouldNotPoisonSkillSyncGate() throws Exception {
        var svc = mock(SkillBundleService.class);
        when(svc.currentVersion()).thenReturn(
            new SkillBundleService.SkillVersion("v100", 1, 1, 16L, List.of("alpha")));
        when(svc.bundle("v100")).thenReturn(Optional.of(
            new SkillBundleService.BundlePayload(16L, "sha", () -> new ByteArrayInputStream(new byte[16]))));

        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        var r1 = execResult(1, "", null);
        when(commands.run("readlink '/skills-store/CURRENT'")).thenReturn(r1);
        var r2 = execResult(1, "", null);
        when(commands.run("test -d '/skills-store'")).thenReturn(r2);
        var r3 = execResult(1, "", null);
        when(commands.run(SkillBundlePusher.probeCommand("/tmp/.skills-store"))).thenReturn(r3);
        var r4 = execResult(0, "", null);
        when(commands.run(startsWith("set -e;"))).thenReturn(r4);
        var r5 = execResult(0, "", null);
        when(commands.run(contains("ln -s '/tmp/.skills-store/CURRENT/alpha'"))).thenReturn(r5);

        var sem = new Semaphore(0);
        var registry = new SimpleMeterRegistry();
        var spec = new OpenSandboxFilesystemSpec();
        var farm = new SkillsFarm(pushOptions(svc, registry, sem), mockSandbox("sbx-733", commands, files),
            null, spec, () -> "u1", () -> "sbx-733");

        assertFalse(farm.tryHydrate("u1"));
        assertNull(spec.skillSyncGate("u1", "sbx-733").skillFarmState());

        sem.release(1);
        assertTrue(farm.tryHydrate("u1"));
        assertEquals(SkillSyncGate.FarmMode.PUSH, spec.skillSyncGate("u1", "sbx-733").skillFarmState().mode());

        var probeCmds = mock(Commands.class);
        var probeFiles = mock(Filesystem.class);
        var probeOk = execResult(0, "v-v100", null);
        when(probeCmds.run("readlink '/skills-store/CURRENT'"))
            .thenThrow(new RuntimeException("transient 502"))
            .thenReturn(probeOk);
        var linkOk = execResult(0, "", null);
        when(probeCmds.run(contains("ln -s '/skills-store/CURRENT/alpha'"))).thenReturn(linkOk);

        var spec2 = new OpenSandboxFilesystemSpec();
        var farm2 = new SkillsFarm(pushOptions(svc, new SimpleMeterRegistry(), new Semaphore(1)),
            mockSandbox("sbx-733-probe", probeCmds, probeFiles), null, spec2, () -> "u1", () -> "sbx-733-probe");

        assertFalse(farm2.tryHydrate("u1"));
        assertNull(spec2.skillSyncGate("u1", "sbx-733-probe").skillFarmState());
        verify(probeFiles, never()).write(anyList());

        assertTrue(farm2.tryHydrate("u1"));
        assertEquals(SkillSyncGate.FarmMode.FARM, spec2.skillSyncGate("u1", "sbx-733-probe").skillFarmState().mode());
    }

    @Test
    void issue734_podLocalHitShouldVerifyFullSharedSlotEntryModeAndFingerprint() {
        var store = mock(ScopedFingerprintStore.class);
        var spec = new OpenSandboxFilesystemSpec().sharedFingerprintStore(store);

        spec.recordScopedSkillCacheFingerprint("sbx-multi", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM, "v1|");
        when(store.slotEntry("sbx-multi")).thenReturn(new ScopedFingerprintStore.SlotEntry("sess-A", "push|v2|"));

        assertNull(spec.scopedSkillCacheFingerprint("sbx-multi", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM));
        assertEquals("v2|", spec.scopedSkillCacheFingerprint("sbx-multi", "sess-A", OpenSandboxFilesystemSpec.HYDRATE_MODE_PUSH));
    }

    @Test
    void issue735_and_741_and_742_shellExtractSwapGcAndSha256Integrity(@TempDir Path tempDir) throws Exception {
        Path fb = tempDir.resolve("fb-store");
        byte[] gz1 = createRealTarGz(tempDir, "alpha", "v1-content");
        String sha1 = sha256Hex(gz1);
        Path tmp1 = tempDir.resolve("b1.tar.gz");
        Files.write(tmp1, gz1);

        String badSha = "0".repeat(64);
        String badCmd = SkillBundlePusher.buildExtractAndSwapCommand(
            fb.toString(), "v-ver1", "ver1", "u1", tmp1.toString(), gz1.length, badSha);
        assertNotEquals(0, runSh(badCmd));
        assertFalse(Files.exists(fb.resolve("CURRENT")));

        Files.write(tmp1, gz1);
        Files.createDirectories(fb.resolve("CURRENT"));
        String okCmd = SkillBundlePusher.buildExtractAndSwapCommand(
            fb.toString(), "v-ver1", "ver1", "u2", tmp1.toString(), gz1.length, sha1);
        assertEquals(0, runSh(okCmd));
        assertTrue(Files.isSymbolicLink(fb.resolve("CURRENT")));
        assertEquals("v-ver1", Files.readSymbolicLink(fb.resolve("CURRENT")).toString());

        Files.write(tmp1, gz1);
        String sameVerCmd = SkillBundlePusher.buildExtractAndSwapCommand(
            fb.toString(), "v-ver1", "ver1", "u3", tmp1.toString(), gz1.length, sha1);
        assertEquals(0, runSh(sameVerCmd));
        try (var stream = Files.list(fb.resolve("v-ver1"))) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().startsWith(".staging-")));
        }

        String probeCmd = SkillBundlePusher.probeCommand(fb.toString());
        assertEquals(0, runSh(probeCmd));

        try (var walk = Files.walk(fb.resolve("v-ver1"))) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
        }
        assertNotEquals(0, runSh(probeCmd));
    }

    @Test
    void issue736_nullExitCodeOrExecutionErrorMustFailAndCleanupTmpTar() throws Exception {
        var pusher = new SkillBundlePusher();
        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        var probeMiss = execResult(1, "", null);
        when(commands.run(SkillBundlePusher.probeCommand("/tmp/.skills-store")))
            .thenReturn(probeMiss);
        var err = mock(ExecutionError.class);
        when(err.getValue()).thenReturn("signal: killed");
        var killedExec = execResult(null, "", err);
        when(commands.run(startsWith("set -e;"))).thenReturn(killedExec);

        var svc = mock(SkillBundleService.class);
        when(svc.bundle("v1")).thenReturn(Optional.of(
            new SkillBundleService.BundlePayload(4L, "sha", () -> new ByteArrayInputStream(new byte[4]))));

        var outcome = pusher.ensureSynced(
            mockSandbox("sbx-736", commands, files), svc, "v1",
            "/tmp/.skills-store", 1024L, new Semaphore(1), 100L);

        assertEquals(SkillBundlePusher.Status.FAILED, outcome.status());
        assertFalse(outcome.ready());
        verify(files, times(1)).deleteFiles(anyList());
    }

    @Test
    void issue737_hydrateVersionBumpRecordsMetricsAndRunsDisabledCleanupWithoutThrowing() throws Exception {
        var ver = new AtomicReference<>(new SkillBundleService.SkillVersion("v1", 1, 1, 16L, List.of("alpha")));
        var svc = mock(SkillBundleService.class);
        when(svc.currentVersion()).thenAnswer(i -> ver.get());
        when(svc.bundle("v1")).thenReturn(Optional.of(
            new SkillBundleService.BundlePayload(16L, "sha", () -> new ByteArrayInputStream(new byte[16]))));
        when(svc.bundle("v2")).thenReturn(Optional.of(
            new SkillBundleService.BundlePayload(2048L, "sha", () -> new ByteArrayInputStream(new byte[0]))));

        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        when(commands.run(anyString())).thenAnswer(inv -> {
            String cmd = inv.getArgument(0);
            if ("readlink '/skills-store/CURRENT'".equals(cmd)
                    || "test -d '/skills-store'".equals(cmd)
                    || SkillBundlePusher.probeCommand("/tmp/.skills-store").equals(cmd)) {
                return execResult(1, "", null);
            }
            return execResult(0, "", null);
        });

        var checker = mock(SkillDisabledChecker.class);
        when(checker.packageDisabled()).thenReturn(Set.of());
        when(checker.isUserDisabled("u1", "beta")).thenReturn(true);

        var registry = new SimpleMeterRegistry();
        var opts = pushOptions(svc, registry, new Semaphore(2))
            .skillsFallbackMaxBytes(1024L)
            .skillDisabledChecker(checker);
        var spec = new OpenSandboxFilesystemSpec();
        var farm = new SkillsFarm(opts, mockSandbox("sbx-737", commands, files), null, spec, () -> "u1", () -> "sbx-737");

        assertTrue(farm.tryHydrate("u1"));

        ver.set(new SkillBundleService.SkillVersion("v2", 1, 1, 2048L, List.of("beta")));
        assertDoesNotThrow(() -> farm.tryHydrate("u1"));
        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "degraded_fallback_too_large")
            .counter().count());
        verify(commands, atLeastOnce()).run(contains("rm -rf '/workspace/.skills-cache/u1/oaf-package/beta'"));
    }

    @Test
    void issue738_transitionToEmptySkillPackShouldGcFallbackStore(@TempDir Path tempDir) throws Exception {
        Path fb = tempDir.resolve("fb-empty");
        Files.createDirectories(fb.resolve("v-v1/secret-skill"));
        Files.writeString(fb.resolve("v-v1/secret-skill/SKILL.md"), "secret", StandardCharsets.UTF_8);
        Files.createSymbolicLink(fb.resolve("CURRENT"), Path.of("v-v1"));

        var svc = mock(SkillBundleService.class);
        when(svc.currentVersion()).thenReturn(
            new SkillBundleService.SkillVersion("v2", 0, 0, 0L, List.of()));

        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        when(commands.run(anyString())).thenAnswer(inv -> {
            String cmd = inv.getArgument(0);
            if ("readlink '/skills-store/CURRENT'".equals(cmd) || "test -d '/skills-store'".equals(cmd)) {
                return execResult(1, "", null);
            }
            int rc = runSh(cmd);
            return execResult(rc, "", null);
        });

        var spec = new OpenSandboxFilesystemSpec();
        spec.skillSyncGate("u1", "sbx-738").setSkillFarmState(
            new SkillSyncGate.SkillFarmState(SkillSyncGate.FarmMode.PUSH, fb.toString(), Set.of()));
        var opts = pushOptions(svc, new SimpleMeterRegistry(), new Semaphore(2))
            .skillsFallbackStoreDir(fb.toString())
            .workspaceRoot(tempDir.resolve("ws").toString());
        var farm = new SkillsFarm(opts, mockSandbox("sbx-738", commands, files), null, spec, () -> "u1", () -> "sbx-738");

        assertTrue(farm.tryHydrate("u1"));
        assertEquals("v-v2", Files.readSymbolicLink(fb.resolve("CURRENT")).toString());
        assertFalse(Files.exists(fb.resolve("v-v1")));
    }

    @Test
    void issue739_sameUserNewSessionReusingSandboxShouldRecoverFromPushToFarm() throws Exception {
        var svc = mock(SkillBundleService.class);
        when(svc.currentVersion()).thenReturn(
            new SkillBundleService.SkillVersion("v1", 1, 1, 16L, List.of("alpha")));
        when(svc.bundle("v1")).thenReturn(Optional.of(
            new SkillBundleService.BundlePayload(16L, "sha", () -> new ByteArrayInputStream(new byte[16]))));

        var primaryReady = new AtomicReference<>(false);
        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        when(commands.run(anyString())).thenAnswer(inv -> {
            String cmd = inv.getArgument(0);
            if ("readlink '/skills-store/CURRENT'".equals(cmd)) {
                return primaryReady.get() ? execResult(0, "v-v1", null) : execResult(1, "", null);
            }
            if ("test -d '/skills-store'".equals(cmd)) {
                return execResult(1, "", null);
            }
            if (SkillBundlePusher.probeCommand("/tmp/.skills-store").equals(cmd)) {
                return execResult(1, "", null);
            }
            return execResult(0, "", null);
        });

        var spec = new OpenSandboxFilesystemSpec();
        var farm = new SkillsFarm(pushOptions(svc, new SimpleMeterRegistry(), new Semaphore(2)),
            mockSandbox("sbx-739", commands, files), null, spec, () -> "alice", () -> "sbx-739");

        assertTrue(farm.tryHydrate("sess-A"));
        assertEquals(SkillSyncGate.FarmMode.PUSH, spec.skillSyncGate("alice", "sbx-739").skillFarmState().mode());

        primaryReady.set(true);
        clearInvocations(commands, files);
        assertTrue(farm.tryHydrate("sess-B"));
        assertEquals(SkillSyncGate.FarmMode.FARM, spec.skillSyncGate("alice", "sbx-739").skillFarmState().mode());
        verify(commands, times(1)).run(contains("ln -s '/skills-store/CURRENT/alpha'"));
        verify(files, never()).write(anyList());
    }

    @Test
    void issue740_pathValidationAndLazySemaphoreInit() {
        assertFalse(OpenSandboxClientOptions.isValidFallbackStoreDir("/."));
        assertFalse(OpenSandboxClientOptions.isValidFallbackStoreDir("/./."));
        assertFalse(OpenSandboxClientOptions.isValidFallbackStoreDir("/tmp"));
        assertFalse(OpenSandboxClientOptions.isValidFallbackStoreDir("/tmp/."));
        assertFalse(OpenSandboxClientOptions.isValidFallbackStoreDir("/etc"));
        assertFalse(OpenSandboxClientOptions.isValidFallbackStoreDir("/tmp//store"));
        assertTrue(OpenSandboxClientOptions.isValidFallbackStoreDir("/tmp/.skills-store"));

        var spec = new OpenSandboxFilesystemSpec()
            .skillsFallbackMode("push")
            .skillsFallbackMaxConcurrency(2);
        var opts1 = spec.clientOptions();
        var opts2 = spec.clientOptions();
        assertNotNull(opts1.getSkillsFallbackSemaphore());
        assertEquals(2, opts1.getSkillsFallbackSemaphore().availablePermits());
        assertSame(opts1.getSkillsFallbackSemaphore(), opts2.getSkillsFallbackSemaphore());
    }

    @Test
    void issue743_rollingMismatchAbsorbedByPushShouldNotEmitErrorLog() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(SkillsFarm.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var svc = mock(SkillBundleService.class);
            when(svc.currentVersion()).thenReturn(
                new SkillBundleService.SkillVersion("v2", 1, 1, 16L, List.of("alpha")));
            when(svc.bundle("v2")).thenReturn(Optional.of(
                new SkillBundleService.BundlePayload(16L, "sha", () -> new ByteArrayInputStream(new byte[16]))));

            var commands = mock(Commands.class);
            var files = mock(Filesystem.class);
            var p1 = execResult(0, "v-v1", null);
            var p2 = execResult(1, "", null);
            var p3 = execResult(0, "", null);
            var p4 = execResult(0, "", null);
            when(commands.run("readlink '/skills-store/CURRENT'")).thenReturn(p1);
            when(commands.run(SkillBundlePusher.probeCommand("/tmp/.skills-store"))).thenReturn(p2);
            when(commands.run(startsWith("set -e;"))).thenReturn(p3);
            when(commands.run(contains("ln -s '/tmp/.skills-store/CURRENT/alpha'"))).thenReturn(p4);

            var farm = new SkillsFarm(pushOptions(svc, new SimpleMeterRegistry(), new Semaphore(2)),
                mockSandbox("sbx-743", commands, files), null, new OpenSandboxFilesystemSpec(), () -> "u1", () -> "sbx-743");

            assertTrue(farm.tryHydrate("u1"));
            assertTrue(appender.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
