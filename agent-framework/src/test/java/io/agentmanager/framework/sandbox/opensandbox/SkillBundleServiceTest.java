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

    /** issue #358：未显式配置 SKILLSYNC_BUNDLE_CACHE_DIR 时解析落 workspace 卷内——业务 Pod
     * /tmp 不可写实测（resolvedSpoolDir 同源结论），旧默认 /tmp 让 D-3 磁盘缓存静默失效 */
    @Test
    void unconfiguredBundleCacheDirResolvesUnderWorkspaceVolume(@TempDir Path workspaceBase) {
        var props = new AgentManagerProperties(null, null, null, workspaceBase.toString(),
            null, null, null, null, null);
        var service = new SkillBundleService(manageService(), sandboxConfigWithCacheDir(""), props);
        assertEquals(workspaceBase.resolve("tmp").resolve("skills-bundle-cache"), service.bundleCacheDir(),
            "未配置默认必须落 workspace 卷内，不得落 /tmp");
    }

    @Test
    void explicitBundleCacheDirWinsOverWorkspaceDefault() {
        var props = new AgentManagerProperties(null, null, null, configDir.toString(),
            null, null, null, null, null);
        var service = new SkillBundleService(manageService(), sandboxConfigWithCacheDir("/data/bundle-cache"), props);
        assertEquals(Path.of("/data/bundle-cache"), service.bundleCacheDir(),
            "显式配置（SKILLSYNC_BUNDLE_CACHE_DIR）优先");
    }

    @Test
    void canonicalNameDisableShouldExcludeFromManifestAndBundle() throws Exception {
        var service = service(Duration.ZERO);
        skill("my-dir-name", "---\nname: my-frontmatter-name\n---\n# body\n", "scripts/run.py", "print(1)\n");
        skill("demo-a", "# demo a\n");
        manageService().toggleSkill("my-frontmatter-name");

        var version = service.currentVersion().version();

        assertEquals(List.of("demo-a"), service.currentVersion().skills());
        assertEquals(List.of("demo-a/SKILL.md"), bundleEntryNames(service, version));
    }

    @Test
    void legacyDirNameDisableShouldExcludeFromManifestAndBundle() throws Exception {
        var service = service(Duration.ZERO);
        skill("my-dir-name", "---\nname: my-frontmatter-name\n---\n# body\n", "scripts/run.py", "print(1)\n");
        skill("demo-a", "# demo a\n");
        manageService().toggleSkill("my-dir-name");

        var version = service.currentVersion().version();

        assertEquals(List.of("demo-a"), service.currentVersion().skills(),
            "历史目录名禁用经归一化后必须同样剔除规范名技能");
        assertEquals(List.of("demo-a/SKILL.md"), bundleEntryNames(service, version));
    }

    private SandboxConfig sandboxConfigWithCacheDir(String cacheDir) {
        return new SandboxConfig(
            true, "img", 30, 30, 512, 1, List.of("sh"),
            Duration.ofMillis(100),
            true, true, 900, true, true, true, true, 500, 30,
            cacheDir, "/skills-store",
            new SandboxConfig.OpenSandboxConfig("127.0.0.1:8090", "k"));
    }

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
        assertEquals(2, first.skillCount());
        assertEquals(3, first.fileCount());
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

        assertEquals(1, version.skillCount());
        assertEquals(2, version.fileCount());
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
            ctx.registerBean("agentManagerProperties", io.agentmanager.framework.config.AgentManagerProperties.class,
                () -> new io.agentmanager.framework.config.AgentManagerProperties(null, null, null,
                    configDir.toString(), null, null, null, null, null));
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
        // computeVersion 全 catch 输出约定：枚举链路真实 I/O 故障（如权限拒绝）→ ERR-<nanos>
        // （非空、非正常指纹），供版本端点（503）与农场判定（degraded_endpoint_down）识别
        // 「端点不可用」。目录缺失/普通文件不算故障——见空源语义用例（issue #397）。
        var manage = org.mockito.Mockito.mock(SkillManageService.class);
        org.mockito.Mockito.when(manage.getSkillsDir()).thenReturn(configDir.resolve("skills"));
        org.mockito.Mockito.when(manage.getDisabledSet())
            .thenThrow(new IllegalStateException("simulated io failure"));
        var service = new SkillBundleService(manage, cacheDir, Duration.ofSeconds(5), clock);

        var version = service.currentVersion();

        assertTrue(version.version().startsWith(SkillBundleService.ERROR_VERSION_PREFIX),
            "枚举失败必须落 ERR-* 输出约定，实际: " + version.version());
        assertEquals(0, version.skillCount());
        assertEquals(0, version.fileCount());
    }

    @Test
    void missingSkillsDirShouldYieldStableEmptyVersion() {
        // issue #397：只读 /config 无 skills/ 子目录（无技能包）必须与 PackageSourceFingerprint
        // 空源语义对齐——稳定空版本、空名单、合法空 bundle，而非 ERR-* + 503 死循环退避
        var missing = Path.of("/nonexistent-skills-" + System.nanoTime());
        var service = serviceWithSkillsDir(missing);

        var first = service.currentVersion();
        var second = service.currentVersion();

        assertEquals(first.version(), second.version());
        assertTrue(first.version().matches("[0-9a-f]{16}"),
            "无技能目录应返回稳定空指纹，实际: " + first.version());
        assertEquals(0, first.skillCount());
        assertEquals(0, first.fileCount());
        assertEquals(List.of(), first.skills());
    }

    @Test
    void missingSkillsDirShouldServeLegalEmptyBundle() throws Exception {
        var missing = Path.of("/nonexistent-skills-" + System.nanoTime());
        var service = serviceWithSkillsDir(missing);
        var version = service.currentVersion().version();

        var names = bundleEntryNames(service, version);

        assertEquals(List.of(), names);
    }

    @Test
    void skillsDirAsRegularFileShouldYieldStableEmptyVersion() throws IOException {
        // skills 被普通文件占据与缺失同口径（isDirectory 判定与 PackageSourceFingerprint 一致）
        var notADir = configDir.resolve("skills-is-a-file");
        Files.writeString(notADir, "not a dir");
        var service = serviceWithSkillsDir(notADir);

        var version = service.currentVersion();

        assertTrue(version.version().matches("[0-9a-f]{16}"),
            "skills 为普通文件应与缺失同口径返回稳定空指纹，实际: " + version.version());
        assertEquals(0, version.skillCount());
        assertEquals(0, version.fileCount());
        assertEquals(List.of(), version.skills());
    }

    private SkillBundleService serviceWithSkillsDir(Path skillsDir) {
        var manage = org.mockito.Mockito.mock(SkillManageService.class);
        org.mockito.Mockito.when(manage.getSkillsDir()).thenReturn(skillsDir);
        org.mockito.Mockito.when(manage.getDisabledSet()).thenReturn(java.util.Set.of());
        return new SkillBundleService(manage, cacheDir, Duration.ofSeconds(5), clock);
    }

    @Test
    void frontmatterNameShouldCanonicalizeManifestAndBundleTopLevel() throws Exception {
        // issue #398：SDK 提示词渲染（MarketplaceStager Cached）按 SKILL.md frontmatter name
        // 推导 /workspace/.skills-cache/<scope>/<ns>/<name>；farm 建链来自 version.skills 清单 +
        // bundle 解包顶层目录——两者必须以同一规范名对齐，否则渲染路径 404
        var service = service(Duration.ZERO);
        skill("my-dir-name", "---\nname: my-frontmatter-name\n---\n# body\n", "scripts/run.py", "print(1)\n");

        var version = service.currentVersion();

        assertEquals(List.of("my-frontmatter-name"), version.skills(),
            "version.skills 清单必须用 frontmatter 规范名（SDK 渲染口径）");
        assertEquals(List.of(
            "my-frontmatter-name/SKILL.md",
            "my-frontmatter-name/scripts/run.py"), bundleEntryNames(service, version.version()),
            "bundle 顶层目录必须重写为规范名（守护解包后 /skills-store/CURRENT/<规范名> 与软链 target 一致）");
    }

    @Test
    void missingOrBlankFrontmatterNameShouldFallBackToDirName() throws Exception {
        var service = service(Duration.ZERO);
        skill("no-fm", "# 无 frontmatter\n");
        skill("blank-fm", "---\ntitle: x\n---\n# name 缺失\n");

        var version = service.currentVersion();

        assertEquals(List.of("blank-fm", "no-fm"), version.skills(),
            "无 frontmatter/无 name 字段回落目录名");
    }

    @Test
    void unsafeCanonicalNameShouldFallBackToDirName() throws Exception {
        // 规范名进入 tar rel 与容器命令拼接（farm 建链引号路径段），与 SkillsFarm
        // isShellSafeRelPath 同防线：含 '..'、引号、空白、'/'、以 / 开头一律回落目录名
        var service = service(Duration.ZERO);
        skill("escape-dir", "---\nname: ../../escape\n---\n# x\n");
        skill("quote-dir", "---\nname: has'quote\n---\n# x\n");
        skill("space-dir", "---\nname: has space\n---\n# x\n");
        skill("slash-dir", "---\nname: a/b\n---\n# x\n");

        var version = service.currentVersion();

        assertEquals(List.of("escape-dir", "quote-dir", "slash-dir", "space-dir"), version.skills(),
            "不安全规范名回落目录名");
    }

    @Test
    void binarySkillMdShouldFallBackToDirName() throws Exception {
        var service = service(Duration.ZERO);
        var root = configDir.resolve("skills").resolve("bin-dir");
        Files.createDirectories(root);
        Files.write(root.resolve("SKILL.md"), new byte[]{0, 1, 2, (byte) 0xFF, (byte) 0xFE});

        var version = service.currentVersion();

        assertEquals(List.of("bin-dir"), version.skills(),
            "SKILL.md 非文本（extractSkillName 抛 IllegalArgumentException）回落目录名，不炸版本计算");
    }

    @Test
    void dirNameDisabledShouldStillExcludeCanonicalSkill() throws Exception {
        // 服务级禁用集键 = 目录名（SkillManageService.toggleSkill 按目录 resolve 校验），
        // 禁用判定保持目录名口径；被禁技能不进清单不进 bundle（规范名也不得出现）
        var service = service(Duration.ZERO);
        skill("my-dir-name", "---\nname: my-frontmatter-name\n---\n# x\n");
        skill("keep-dir", "---\nname: keep-name\n---\n# x\n");
        manageService().toggleSkill("my-dir-name");

        var version = service.currentVersion();

        assertEquals(List.of("keep-name"), version.skills());
        assertEquals(List.of("keep-name/SKILL.md"), bundleEntryNames(service, version.version()));
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
