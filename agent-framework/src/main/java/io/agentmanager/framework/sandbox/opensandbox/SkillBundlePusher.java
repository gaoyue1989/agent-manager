package io.agentmanager.framework.sandbox.opensandbox;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;

import io.agentmanager.framework.service.SkillZipSupport;

final class SkillBundlePusher {

    private static final Logger log = LoggerFactory.getLogger(SkillBundlePusher.class);
    private static final String STORE_VERSION_DIR_PREFIX = "v-";

    enum Status {
        HIT,
        SYNCED,
        TOO_LARGE,
        THROTTLED,
        FAILED
    }

    record PushOutcome(Status status, long bytes, long costMs, String detail) {
        boolean ready() {
            return status == Status.HIT || status == Status.SYNCED;
        }
    }

    PushOutcome ensureSynced(Sandbox osbSandbox,
                             SkillBundleService bundleService,
                             String version,
                             String fallbackStoreDir,
                             long maxBytes,
                             Semaphore semaphore,
                             long acquireTimeoutMs) {
        long startNanos = System.nanoTime();
        String fb = fallbackStoreDir == null ? "" : fallbackStoreDir.trim();
        if (!OpenSandboxClientOptions.isValidFallbackStoreDir(fb)
                || !SkillZipSupport.isShellSafeSegment(version)) {
            return new PushOutcome(Status.FAILED, 0L, elapsedMs(startNanos),
                "invalid fallback store dir or version");
        }
        String expectedDir = STORE_VERSION_DIR_PREFIX + version;
        if (expectedDir.equals(probeCurrentVersion(osbSandbox, fb))) {
            return new PushOutcome(Status.HIT, 0L, elapsedMs(startNanos), null);
        }
        SkillBundleService.BundlePayload payload;
        try {
            var opt = bundleService.bundle(version);
            if (opt.isEmpty()) {
                return new PushOutcome(Status.FAILED, 0L, elapsedMs(startNanos),
                    "bundle version no longer current");
            }
            payload = opt.get();
        } catch (Exception e) {
            return new PushOutcome(Status.FAILED, 0L, elapsedMs(startNanos), e.getMessage());
        }
        if (maxBytes > 0 && payload.size() > maxBytes) {
            return new PushOutcome(Status.TOO_LARGE, payload.size(), elapsedMs(startNanos),
                "bundle size " + payload.size() + " exceeds max " + maxBytes);
        }
        boolean acquired = false;
        if (semaphore != null) {
            try {
                acquired = semaphore.tryAcquire(Math.max(0L, acquireTimeoutMs), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new PushOutcome(Status.FAILED, payload.size(), elapsedMs(startNanos), "interrupted");
            }
            if (!acquired) {
                return new PushOutcome(Status.THROTTLED, payload.size(), elapsedMs(startNanos),
                    "fallback push concurrency limit reached");
            }
        }
        String uid = UUID.randomUUID().toString();
        String tmpTar = "/tmp/.skills-bundle." + uid + ".tar.gz";
        try {
            try (InputStream in = payload.stream().open()) {
                osbSandbox.files().write(List.of(
                    WriteEntry.builder()
                        .path(tmpTar)
                        .data(in)
                        .mode(644)
                        .build()
                ));
            }
            String cmd = buildExtractAndSwapCommand(fb, expectedDir, version, uid, tmpTar);
            var execution = osbSandbox.commands().run(cmd);
            if (execution.getExitCode() != null && execution.getExitCode() != 0) {
                return new PushOutcome(Status.FAILED, payload.size(), elapsedMs(startNanos),
                    "extract exit " + execution.getExitCode());
            }
            return new PushOutcome(Status.SYNCED, payload.size(), elapsedMs(startNanos), null);
        } catch (Exception e) {
            try {
                osbSandbox.files().deleteFiles(List.of(tmpTar));
            } catch (Exception ignored) {
                log.debug("[sandbox-open] fallback push cleanup ignored for {}", tmpTar);
            }
            return new PushOutcome(Status.FAILED, payload.size(), elapsedMs(startNanos), e.getMessage());
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    static String buildExtractAndSwapCommand(String fb, String verDir, String version, String uid, String tmpTar) {
        String stgDir = fb + "/.staging-" + version + "." + uid;
        String curTmp = fb + "/.CURRENT." + uid;
        return "set -e; fb='" + fb + "'; ver='" + verDir + "'; tmp='" + tmpTar
            + "'; stg='" + stgDir + "'; cur='" + curTmp
            + "'; trap 'rm -rf \"$stg\" \"$tmp\" \"$cur\"' EXIT; mkdir -p \"$fb\"; "
            + "if [ ! -d \"$fb/$ver\" ]; then mkdir -p \"$stg\"; tar xzf \"$tmp\" -C \"$stg\"; "
            + "chmod -R a+rX \"$stg\" 2>/dev/null || true; "
            + "mv \"$stg\" \"$fb/$ver\" 2>/dev/null || rm -rf \"$stg\"; fi; "
            + "ln -sfn \"$ver\" \"$cur\"; "
            + "mv -Tf \"$cur\" \"$fb/CURRENT\" 2>/dev/null || ln -sfn \"$ver\" \"$fb/CURRENT\"; "
            + "find \"$fb\" -mindepth 1 -maxdepth 1 -type d -name 'v-*' ! -name \"$ver\" "
            + "-exec rm -rf {} + 2>/dev/null || true";
    }

    private String probeCurrentVersion(Sandbox osbSandbox, String store) {
        try {
            var execution = osbSandbox.commands().run("readlink '" + store + "/CURRENT'");
            if (execution.getExitCode() != null && execution.getExitCode() == 0) {
                var stdout = execution.getLogs() != null && execution.getLogs().getStdout() != null
                    ? execution.getLogs().getStdout().stream()
                        .map(msg -> msg.getText())
                        .collect(Collectors.joining("\n"))
                    : "";
                var token = stdout.trim();
                if (!token.isEmpty()) {
                    int slash = token.lastIndexOf('/');
                    return slash >= 0 ? token.substring(slash + 1) : token;
                }
            }
        } catch (Exception e) {
            log.debug("[sandbox-open] fallback store probe failed: {}", e.getMessage());
        }
        return null;
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
