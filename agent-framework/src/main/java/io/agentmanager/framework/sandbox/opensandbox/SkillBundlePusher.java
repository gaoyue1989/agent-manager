package io.agentmanager.framework.sandbox.opensandbox;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;

import io.agentmanager.framework.service.SkillZipSupport;

final class SkillBundlePusher {

    private static final Logger log = LoggerFactory.getLogger(SkillBundlePusher.class);
    private static final String STORE_VERSION_DIR_PREFIX = "v-";
    private static final Pattern HEX_SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final int LOCK_STRIPES = 64;
    private static final Object[] SANDBOX_LOCKS = new Object[LOCK_STRIPES];

    static {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            SANDBOX_LOCKS[i] = new Object();
        }
    }

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
        if (probeCurrentMatches(osbSandbox, fb, expectedDir)) {
            return new PushOutcome(Status.HIT, 0L, elapsedMs(startNanos), null);
        }
        synchronized (sandboxLock(osbSandbox)) {
            if (probeCurrentMatches(osbSandbox, fb, expectedDir)) {
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
                if (probeCurrentMatches(osbSandbox, fb, expectedDir)) {
                    return new PushOutcome(Status.HIT, 0L, elapsedMs(startNanos), null);
                }
                boolean checkSha = isHexSha256(payload.sha256());
                CountingDigestInputStream digestStream = null;
                try (InputStream rawIn = payload.stream().open()) {
                    InputStream uploadIn = rawIn;
                    if (checkSha) {
                        digestStream = new CountingDigestInputStream(rawIn);
                        uploadIn = digestStream;
                    }
                    osbSandbox.files().write(List.of(
                        WriteEntry.builder()
                            .path(tmpTar)
                            .data(uploadIn)
                            .mode(644)
                            .build()
                    ));
                }
                if (digestStream != null && digestStream.bytesRead() > 0) {
                    if (digestStream.bytesRead() != payload.size()
                            || !digestStream.hexSha256().equalsIgnoreCase(payload.sha256())) {
                        cleanupTmpTar(osbSandbox, tmpTar);
                        return new PushOutcome(Status.FAILED, payload.size(), elapsedMs(startNanos),
                            "bundle digest mismatch");
                    }
                }
                String cmd = buildExtractAndSwapCommand(
                    fb, expectedDir, version, uid, tmpTar, payload.size(), payload.sha256());
                var execution = osbSandbox.commands().run(cmd);
                if (execution == null || execution.getError() != null
                        || !Integer.valueOf(0).equals(execution.getExitCode())) {
                    cleanupTmpTar(osbSandbox, tmpTar);
                    String reason = execution == null
                        ? "null execution"
                        : (execution.getError() != null
                            ? "extract error " + execution.getError().getValue()
                            : "extract exit " + execution.getExitCode());
                    return new PushOutcome(Status.FAILED, payload.size(), elapsedMs(startNanos), reason);
                }
                return new PushOutcome(Status.SYNCED, payload.size(), elapsedMs(startNanos), null);
            } catch (Exception e) {
                cleanupTmpTar(osbSandbox, tmpTar);
                return new PushOutcome(Status.FAILED, payload.size(), elapsedMs(startNanos), e.getMessage());
            } finally {
                if (acquired) {
                    semaphore.release();
                }
            }
        }
    }

    static String buildExtractAndSwapCommand(String fb, String verDir, String version, String uid, String tmpTar) {
        return buildExtractAndSwapCommand(fb, verDir, version, uid, tmpTar, -1L, null);
    }

    static String buildExtractAndSwapCommand(String fb, String verDir, String version, String uid,
                                             String tmpTar, long expectedBytes, String expectedSha256) {
        String stgDir = fb + "/.staging-" + version + "." + uid;
        String curTmp = fb + "/.CURRENT." + uid;
        var sb = new StringBuilder();
        sb.append("set -e; fb='").append(fb)
            .append("'; ver='").append(verDir)
            .append("'; tmp='").append(tmpTar)
            .append("'; stg='").append(stgDir)
            .append("'; cur='").append(curTmp)
            .append("'; trap 'rm -rf \"$stg\" \"$tmp\" \"$cur\"' EXIT; mkdir -p \"$fb\"; ");
        if (expectedBytes >= 0) {
            sb.append("[ \"$(wc -c < \"$tmp\" | tr -d ' ')\" -eq ").append(expectedBytes).append(" ]; ");
        }
        if (isHexSha256(expectedSha256)) {
            sb.append("if command -v sha256sum >/dev/null 2>&1; then printf '%s  %s\\n' '")
                .append(expectedSha256.toLowerCase())
                .append("' \"$tmp\" | sha256sum -c - >/dev/null; fi; ");
        }
        sb.append("if [ ! -d \"$fb/$ver\" ]; then mkdir -p \"$stg\"; tar xzf \"$tmp\" -C \"$stg\"; ")
            .append("chmod -R a+rX \"$stg\" 2>/dev/null || true; ")
            .append("mv -T \"$stg\" \"$fb/$ver\" 2>/dev/null || rm -rf \"$stg\"; fi; ")
            .append("if [ -d \"$fb/CURRENT\" ] && [ ! -L \"$fb/CURRENT\" ]; then rm -rf \"$fb/CURRENT\"; fi; ")
            .append("ln -sfn \"$ver\" \"$cur\"; ")
            .append("mv -Tf \"$cur\" \"$fb/CURRENT\" 2>/dev/null || ln -sfn \"$ver\" \"$fb/CURRENT\"; ")
            .append("test -L \"$fb/CURRENT\"; ")
            .append("active=$(readlink \"$fb/CURRENT\" 2>/dev/null || true); active=${active##*/}; ")
            .append("find \"$fb\" -mindepth 1 -maxdepth 1 -type d -name 'v-*' ! -name \"$ver\" ! -name \"$active\" ")
            .append("-exec rm -rf {} + 2>/dev/null || true");
        return sb.toString();
    }

    static String probeCommand(String store) {
        return "test -L '" + store + "/CURRENT' && test -d '" + store + "/CURRENT' && readlink '" + store + "/CURRENT'";
    }

    private boolean probeCurrentMatches(Sandbox osbSandbox, String store, String expectedDir) {
        try {
            var execution = osbSandbox.commands().run(probeCommand(store));
            if (execution != null && execution.getError() == null
                    && Integer.valueOf(0).equals(execution.getExitCode())) {
                var stdout = execution.getLogs() != null && execution.getLogs().getStdout() != null
                    ? execution.getLogs().getStdout().stream()
                        .map(msg -> msg.getText())
                        .collect(Collectors.joining("\n"))
                    : "";
                var token = stdout.trim();
                return expectedDir.equals(token) || (store + "/" + expectedDir).equals(token);
            }
        } catch (Exception e) {
            log.debug("[sandbox-open] fallback store probe failed: {}", e.getMessage());
        }
        return false;
    }

    private void cleanupTmpTar(Sandbox osbSandbox, String tmpTar) {
        try {
            osbSandbox.files().deleteFiles(List.of(tmpTar));
        } catch (Exception ignored) {
            log.debug("[sandbox-open] fallback push cleanup ignored for {}", tmpTar);
        }
    }

    private static Object sandboxLock(Sandbox osbSandbox) {
        String id = null;
        try {
            id = osbSandbox.getId();
        } catch (Exception ignored) {
            id = null;
        }
        int h = (id != null && !id.isBlank()) ? id.hashCode() : System.identityHashCode(osbSandbox);
        return SANDBOX_LOCKS[(h & Integer.MAX_VALUE) % LOCK_STRIPES];
    }

    private static boolean isHexSha256(String sha) {
        return sha != null && HEX_SHA256.matcher(sha).matches();
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static final class CountingDigestInputStream extends FilterInputStream {
        private final MessageDigest digest;
        private long bytesRead;

        CountingDigestInputStream(InputStream in) {
            super(in);
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                digest.update((byte) b);
                bytesRead++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                digest.update(b, off, n);
                bytesRead += n;
            }
            return n;
        }

        long bytesRead() {
            return bytesRead;
        }

        String hexSha256() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
