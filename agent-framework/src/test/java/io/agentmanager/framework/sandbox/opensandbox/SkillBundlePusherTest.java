package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionLogs;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.services.Commands;
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem;

class SkillBundlePusherTest {

    private final SkillBundlePusher pusher = new SkillBundlePusher();

    private static Sandbox mockSandbox(Commands commands, Filesystem files) {
        var sb = mock(Sandbox.class);
        when(sb.commands()).thenReturn(commands);
        when(sb.files()).thenReturn(files);
        return sb;
    }

    private static Execution execResult(int exitCode, String stdout) {
        var exec = mock(Execution.class);
        when(exec.getExitCode()).thenReturn(exitCode);
        var logs = mock(ExecutionLogs.class);
        if (stdout != null) {
            var msg = mock(OutputMessage.class);
            when(msg.getText()).thenReturn(stdout);
            when(logs.getStdout()).thenReturn(List.of(msg));
        } else {
            when(logs.getStdout()).thenReturn(List.of());
        }
        when(exec.getLogs()).thenReturn(logs);
        return exec;
    }

    @Test
    void ensureSyncedShouldReturnHitWithoutUploadWhenFallbackStoreMatchesVersion() throws Exception {
        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        var res1 = execResult(0, "v-abcdef0123456789"); when(commands.run("readlink '/tmp/.skills-store/CURRENT'")).thenReturn(res1);
        var svc = mock(SkillBundleService.class);
        var sem = new Semaphore(1);

        var outcome = pusher.ensureSynced(
            mockSandbox(commands, files), svc, "abcdef0123456789",
            "/tmp/.skills-store", 1024L, sem, 100L);

        assertEquals(SkillBundlePusher.Status.HIT, outcome.status());
        assertTrue(outcome.ready());
        assertEquals(0L, outcome.bytes());
        assertEquals(1, sem.availablePermits());
        verify(svc, never()).bundle(anyString());
        verify(files, never()).write(anyList());
    }

    @Test
    void ensureSyncedShouldStreamInputStreamAndRunAtomicExtractCommand() throws Exception {
        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        var res2 = execResult(1, ""); when(commands.run(startsWith("readlink "))).thenReturn(res2);
        var r_101 = execResult(0, ""); when(commands.run(startsWith("set -e;"))).thenReturn(r_101);
        var svc = mock(SkillBundleService.class);
        byte[] gzBytes = new byte[] { 0x1f, (byte) 0x8b, 0x08, 0x00 };
        when(svc.bundle("ver123")).thenReturn(Optional.of(
            new SkillBundleService.BundlePayload(gzBytes.length, "sha-deadbeef",
                () -> new ByteArrayInputStream(gzBytes))));
        var sem = new Semaphore(2);

        var outcome = pusher.ensureSynced(
            mockSandbox(commands, files), svc, "ver123",
            "/tmp/.skills-store", 1024L, sem, 100L);

        assertEquals(SkillBundlePusher.Status.SYNCED, outcome.status());
        assertTrue(outcome.ready());
        assertEquals(gzBytes.length, outcome.bytes());
        assertEquals(2, sem.availablePermits());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WriteEntry>> writeCaptor = ArgumentCaptor.forClass(List.class);
        verify(files, times(1)).write(writeCaptor.capture());
        var entries = writeCaptor.getValue();
        assertEquals(1, entries.size());
        var entry = entries.get(0);
        assertTrue(entry.getPath().startsWith("/tmp/.skills-bundle."));
        assertTrue(entry.getPath().endsWith(".tar.gz"));
        assertEquals(644, entry.getMode());
        assertInstanceOf(InputStream.class, entry.getData());

        ArgumentCaptor<String> cmdCaptor = ArgumentCaptor.forClass(String.class);
        verify(commands, times(2)).run(cmdCaptor.capture());
        String extractCmd = cmdCaptor.getAllValues().get(1);
        assertTrue(extractCmd.contains("trap 'rm -rf \"$stg\" \"$tmp\" \"$cur\"' EXIT"));
        assertTrue(extractCmd.contains("tar xzf \"$tmp\" -C \"$stg\""));
        assertTrue(extractCmd.contains("mv -Tf \"$cur\" \"$fb/CURRENT\""));
        assertTrue(extractCmd.contains("find \"$fb\" -mindepth 1 -maxdepth 1 -type d -name 'v-*' ! -name \"$ver\""));
    }

    @Test
    void ensureSyncedShouldRejectBundleExceedingMaxBytes() throws Exception {
        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        var res3 = execResult(1, ""); when(commands.run(startsWith("readlink "))).thenReturn(res3);
        var svc = mock(SkillBundleService.class);
        when(svc.bundle("ver-big")).thenReturn(Optional.of(
            new SkillBundleService.BundlePayload(2048L, "sha", () -> new ByteArrayInputStream(new byte[0]))));
        var sem = new Semaphore(1);

        var outcome = pusher.ensureSynced(
            mockSandbox(commands, files), svc, "ver-big",
            "/tmp/.skills-store", 1024L, sem, 100L);

        assertEquals(SkillBundlePusher.Status.TOO_LARGE, outcome.status());
        assertFalse(outcome.ready());
        assertEquals(2048L, outcome.bytes());
        assertEquals(1, sem.availablePermits());
        verify(files, never()).write(anyList());
    }

    @Test
    void ensureSyncedShouldReturnThrottledWhenSemaphoreUnavailable() throws Exception {
        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        var res4 = execResult(1, ""); when(commands.run(startsWith("readlink "))).thenReturn(res4);
        var svc = mock(SkillBundleService.class);
        when(svc.bundle("ver-busy")).thenReturn(Optional.of(
            new SkillBundleService.BundlePayload(128L, "sha", () -> new ByteArrayInputStream(new byte[128]))));
        var sem = new Semaphore(0);

        var outcome = pusher.ensureSynced(
            mockSandbox(commands, files), svc, "ver-busy",
            "/tmp/.skills-store", 1024L, sem, 10L);

        assertEquals(SkillBundlePusher.Status.THROTTLED, outcome.status());
        assertFalse(outcome.ready());
        verify(files, never()).write(anyList());
    }

    @Test
    void ensureSyncedShouldReleaseSemaphoreAndFailOpenWhenUploadOrExtractFails() throws Exception {
        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        var res5 = execResult(1, ""); when(commands.run(startsWith("readlink "))).thenReturn(res5);
        doThrow(new RuntimeException("upload io error")).when(files).write(anyList());
        var svc = mock(SkillBundleService.class);
        when(svc.bundle("ver-err")).thenReturn(Optional.of(
            new SkillBundleService.BundlePayload(64L, "sha", () -> new ByteArrayInputStream(new byte[64]))));
        var sem = new Semaphore(1);

        var uploadFail = pusher.ensureSynced(
            mockSandbox(commands, files), svc, "ver-err",
            "/tmp/.skills-store", 1024L, sem, 100L);

        assertEquals(SkillBundlePusher.Status.FAILED, uploadFail.status());
        assertEquals(1, sem.availablePermits());
        verify(files, times(1)).deleteFiles(anyList());

        reset(files, commands);
        var res6 = execResult(1, ""); when(commands.run(startsWith("readlink "))).thenReturn(res6);
        var r_102 = execResult(2, ""); when(commands.run(startsWith("set -e;"))).thenReturn(r_102);

        var extractFail = pusher.ensureSynced(
            mockSandbox(commands, files), svc, "ver-err",
            "/tmp/.skills-store", 1024L, sem, 100L);

        assertEquals(SkillBundlePusher.Status.FAILED, extractFail.status());
        assertEquals(1, sem.availablePermits());
    }

    @Test
    void ensureSyncedShouldRejectInvalidFallbackStoreDir() {
        var commands = mock(Commands.class);
        var files = mock(Filesystem.class);
        var svc = mock(SkillBundleService.class);

        var outcome = pusher.ensureSynced(
            mockSandbox(commands, files), svc, "ver123",
            "/tmp/../evil", 1024L, new Semaphore(1), 100L);

        assertEquals(SkillBundlePusher.Status.FAILED, outcome.status());
        verifyNoInteractions(commands, files, svc);
    }
}
