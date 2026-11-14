package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.service.SkillManageService;

class SkillBundleServiceTest {

    @TempDir
    Path configDir;

    @TempDir
    Path cacheDir;

    private final MutableClock clock = new MutableClock(Instant.now().toEpochMilli());

    @Test
    void versionShouldStayStableAcrossRecomputesOnUntouchedSource() throws Exception {
        var service = service(Duration.ZERO);
        skill("demo-a", "# demo a\n", "scripts/run.py", "print(1)\n");
        skill("demo-b", "# demo b\n");

        var first = service.currentVersion();
        var second = service.currentVersion();

        assertEquals(first.version(), second.version());
        assertEquals(16, first.version().length());
        assertTrue(first.version().matches("[0-9a-f]{16}"));
        assertEquals(3, first.skillCount());
        assertEquals(9 + 9 + 9, first.sizeBytes());
    }

    @Test
    void versionShouldBeCachedWithinTtlAndRecomputedAfterExpiry() throws Exception {
        var service = service(Duration.ofSeconds(5));
        skill("demo-a", "# demo a\n");
        var before = service.currentVersion();

        skill("demo-b", "# demo b\n");
        clock.advance(1000);
        assertEquals(before.version(), service.currentVersion().version());

        clock.advance(5000);
        assertNotEquals(before.version(), service.currentVersion().version());
    }

    @Test
    void versionShouldChangeWhenServiceDisabledSetChanges() throws Exception {
        var service = service(Duration.ZERO);
        skill("demo-a", "# demo a\n");
        skill("demo-b", "# demo b\n");
        var before = service.currentVersion();

        manageService().toggleSkill("demo-b");

        assertNotEquals(before.version(), service.currentVersion().version());
    }

    @Test
    void versionShouldChangeWhenSkillContentChanges() throws Exception {
        var service = service(Duration.ZERO);
        skill("demo-a", "# demo a\n");
        var before = service.currentVersion();

        skill("demo-a", "# demo a changed\n");

        assertNotEquals(before.version(), service.currentVersion().version());
    }

    @Test
    void versionCountsShouldExcludeDisabledSkills() throws Exception {
        var service = service(Duration.ZERO);
        skill("demo-a", "# demo a\n", "scripts/run.py", "print(1)\n");
        skill("demo-b", "# demo b\n");
        manageService().toggleSkill("demo-b");

        var version = service.currentVersion();

        assertEquals(2, version.skillCount());
        assertEquals(9 + 9, version.sizeBytes());
    }

    @Test
    void versionShouldExposeEnabledSkillNamesForFarmManifest() throws Exception {
        // PR-4b 端点 manifest：farm 候选名单来自 version 响应（与 bundle 打包同源枚举，
        // 服务级禁用不进名单）
        var service = service(Duration.ZERO);
        skill("demo-a", "# demo a\n", "scripts/run.py", "print(1)\n");
        skill("demo-b", "# demo b\n");
        manageService().toggleSkill("demo-b");

        var version = service.currentVersion();

        assertEquals(List.of("demo-a"), version.skills());
    }

    @Test
    void bundleShouldProduceIdenticalBytesForSameVersion() throws Exception {
        var service = service(Duration.ZERO);
        skill("demo-a", "# demo a\n", "scripts/run.py", "print(1)\n");
        skill("demo-b", "# demo b\n");
        var version = service.currentVersion().version();

        var bytes1 = bundleBytes(service, version);
        deleteRecursively(cacheDir);
        var bytes2 = bundleBytes(service, version);

        assertArrayEquals(bytes1, bytes2);
    }

    @Test
    void bundleSha256ShouldMatchDeliveredContent() throws Exception {
        var service = service(Duration.ZERO);
        skill("demo-a", "# demo a\n", "scripts/run.py", "print(1)\n");
        var version = service.currentVersion().version();

        var payload = service.bundle(version).orElseThrow();
        var bytes = payload.stream().open().readAllBytes();

        assertEquals(64, payload.sha256().length());
        assertTrue(payload.sha256().matches("[0-9a-f]{64}"));
        assertEquals(payload.sha256(), sha256Hex(bytes));
        assertEquals(bytes.length, payload.size());
    }

    @Test
    void bundleShouldExcludeDisabledSkillsAndTopLevelStateFile() throws Exception {
        var service = service(Duration.ZERO);
        skill("demo-a", "# demo a\n", "scripts/run.py", "print(1)\n");
        skill("demo-b", "# demo b\n");
        manageService().toggleSkill("demo-b");
        var version = service.currentVersion().version();

        var names = bundleEntryNames(service, version);

        assertEquals(List.of("demo-a/SKILL.md", "demo-a/scripts/run.py"), names);
    }

    @Test
    void bundleEntriesShouldCarryFixedZeroMtime() throws Exception {
        var service = service(Duration.ZERO);
        skill("demo-a", "# demo a\n");
        var version = service.currentVersion().version();

        var bytes = bundleBytes(service, version);
        try (var tis = new TarArchiveInputStream(
                new GzipCompressorInputStream(new ByteArrayInputStream(bytes)))) {
            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                assertEquals(0, entry.getModTime().getTime(), entry.getName());
                assertEquals(0644, entry.getMode(), entry.getName());
                assertEquals("# demo a\n", new String(tis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void bundleShouldPreserveExecutableBitFromSourceFile() throws Exception {
        var service = service(Duration.ZERO);
        skill("demo-a", "# demo a\n", "scripts/run.py", "print(1)\n");
        Files.setPosixFilePermissions(configDir.resolve("skills/demo-a/scripts/run.py"),
            PosixFilePermissions.fromString("rwxr-xr-x"));
        var version = service.currentVersion().version();

        var modes = new LinkedHashMap<String, Integer>();
        try (var tis = new TarArchiveInputStream(new GzipCompressorInputStream(
                new ByteArrayInputStream(bundleBytes(service, version))))) {
            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                modes.put(entry.getName(), entry.getMode());
            }
        }

        assertEquals(Map.of("demo-a/SKILL.md", 0644, "demo-a/scripts/run.py", 0755), modes);
    }

    @Test
    void springShouldInstantiateThroughAutowiredConstructor() {
        var config = new SandboxConfig(false, "img", 60, 30, 1024, 1, List.of("/e"),
            Duration.ofMillis(100), true, true, 900, true, true, true, false, 500, 30,
            new SandboxConfig.OpenSandboxConfig("server:8090", "key"));
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("skillManageService", SkillManageService.class, this::manageService);
            ctx.registerBean("sandboxConfig", SandboxConfig.class, () -> config);
            ctx.register(SkillBundleService.class);
            ctx.refresh();

            assertNotNull(ctx.getBean(SkillBundleService.class));
        }
    }

    @Test
    void gzipHeaderShouldCarryNoTimestamp() throws Exception {
        var service = service(Duration.ZERO);
        skill("demo-a", "# demo a\n");
        var version = service.currentVersion().version();

        var bytes = bundleBytes(service, version);

        assertEquals(0x1f, bytes[0] & 0xff);
        assertEquals(0x8b, bytes[1] & 0xff);
        for (int i = 4; i <= 7; i++) {
            assertEquals(0, bytes[i], "gzip mtime byte " + i);
        }
    }

    @Test
    void bundleShouldReturnEmptyWhenVersionMismatch() throws Exception {
        var service = service(Duration.ZERO);
        skill("demo-a", "# demo a\n");

        assertTrue(service.bundle("0123456789abcdef").isEmpty());
        assertTrue(service.bundle("").isEmpty());
    }

    @Test
    void bundleShouldFallBackToInMemoryStreamingWhenCacheDirUnusable() throws Exception {
        var blockedCacheDir = configDir.resolve("cache-is-a-file");
        Files.writeString(blockedCacheDir, "not a dir");
        var service = new SkillBundleService(manageService(), blockedCacheDir, Duration.ZERO, clock);
        skill("demo-a", "# demo a\n", "scripts/run.py", "print(1)\n");
        var version = service.currentVersion().version();

        var payload = service.bundle(version).orElseThrow();
        var bytes = payload.stream().open().readAllBytes();

        assertEquals(List.of("demo-a/SKILL.md", "demo-a/scripts/run.py"), entryNames(bytes));
        assertEquals(sha256Hex(bytes), payload.sha256());
    }

    @Test
    void bundleShouldStreamFromDiskCacheOnSecondRequest() throws Exception {
        var service = service(Duration.ZERO);
        skill("demo-a", "# demo a\n");
        var version = service.currentVersion().version();

        var first = service.bundle(version).orElseThrow();
        var second = service.bundle(version).orElseThrow();

        assertTrue(Files.isRegularFile(cacheDir.resolve(version + ".tar.gz")));
        assertArrayEquals(first.stream().open().readAllBytes(),
            second.stream().open().readAllBytes());
        assertEquals(first.sha256(), second.sha256());
    }

    @Test
    void currentVersionShouldReturnErrPrefixWhenFingerprintComputationFails() {
        // computeVersion 全 catch 输出约定：指纹/bundle 枚举失败 → ERR-<nanos>（非空、非正常指纹），
        // 供版本端点（503）与农场判定（degraded_endpoint_down）识别「端点不可用」
        var missing = Path.of("/nonexistent-skills-" + System.nanoTime());
        var manage = org.mockito.Mockito.mock(SkillManageService.class);
        org.mockito.Mockito.when(manage.getSkillsDir()).thenReturn(missing);
        org.mockito.Mockito.when(manage.getDisabledSet()).thenReturn(java.util.Set.of());
        var service = new SkillBundleService(manage, cacheDir, Duration.ofSeconds(5), clock);

        var version = service.currentVersion();

        assertTrue(version.version().startsWith(SkillBundleService.ERROR_VERSION_PREFIX),
            "枚举失败必须落 ERR-* 输出约定，实际: " + version.version());
        assertEquals(0, version.skillCount());
    }

    private SkillBundleService service(Duration ttl) {
        return new SkillBundleService(manageService(), cacheDir, ttl, clock);
    }

    private SkillManageService manageService() {
        var props = new AgentManagerProperties(null, null, null,
            configDir.toString(), null, null, null, null, null);
        return new SkillManageService(props);
    }

    private void skill(String name, String skillMd, String... extraPathsAndContent) throws IOException {
        var root = configDir.resolve("skills").resolve(name);
        Files.createDirectories(root);
        Files.writeString(root.resolve("SKILL.md"), skillMd, StandardCharsets.UTF_8);
        for (int i = 0; i + 1 < extraPathsAndContent.length; i += 2) {
            var f = root.resolve(extraPathsAndContent[i]);
            Files.createDirectories(f.getParent());
            Files.writeString(f, extraPathsAndContent[i + 1], StandardCharsets.UTF_8);
        }
    }

    private byte[] bundleBytes(SkillBundleService service, String version) throws IOException {
        return service.bundle(version).orElseThrow().stream().open().readAllBytes();
    }

    private List<String> bundleEntryNames(SkillBundleService service, String version) throws IOException {
        var bytes = bundleBytes(service, version);
        return entryNames(bytes);
    }

    private List<String> entryNames(byte[] bundleBytes) throws IOException {
        var names = new ArrayList<String>();
        try (var tis = new TarArchiveInputStream(
                new GzipCompressorInputStream(new ByteArrayInputStream(bundleBytes)))) {
            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static String sha256Hex(byte[] data) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256").digest(data);
        var sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long startMillis) {
            millis = startMillis;
        }

        void advance(long ms) {
            millis += ms;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
