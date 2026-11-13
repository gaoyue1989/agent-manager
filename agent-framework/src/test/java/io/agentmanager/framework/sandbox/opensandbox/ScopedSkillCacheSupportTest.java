package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentmanager.framework.service.WorkspaceReader;

class ScopedSkillCacheSupportTest {

    @TempDir
    Path dir;

    private static SkillDisabledChecker checker(Set<String> pkgDisabled, Set<String> userDisabled) {
        return new SkillDisabledChecker() {
            @Override
            public Set<String> packageDisabled() {
                return pkgDisabled;
            }

            @Override
            public boolean isUserDisabled(String userId, String name) {
                return userDisabled.contains(name);
            }
        };
    }

    private static void skill(Path scopeDir, String ns, String name) throws Exception {
        Files.createDirectories(scopeDir.resolve(ns).resolve(name));
    }

    private static Path bundle(Path pkg, String name) throws Exception {
        var dir = pkg.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\n---");
        return pkg;
    }

    // ---- contentFingerprint（tar/farm 两路同源宿主指纹） ----

    @Test
    void contentFingerprintShouldReturnEmptyWhenScopeDirMissing() throws Exception {
        var missing = dir.resolve("no-such-scope");

        assertEquals("", ScopedSkillCacheSupport.contentFingerprint(
            new OpenSandboxFilesystemSpec(), null, "u1", missing));
    }

    @Test
    void contentFingerprintShouldStayStableAcrossCalls() throws Exception {
        var scopeDir = Files.createDirectories(dir.resolve("scope"));
        var pkg = bundle(dir.resolve("pkg"), "demo-a");
        var spec = new OpenSandboxFilesystemSpec().packageSkillsDir(pkg);
        var reader = mock(WorkspaceReader.class);
        when(reader.readUserSkillRevision(anyString())).thenReturn("rev-1");

        var first = ScopedSkillCacheSupport.contentFingerprint(spec, reader, "u1", scopeDir);
        var second = ScopedSkillCacheSupport.contentFingerprint(spec, reader, "u1", scopeDir);

        assertEquals(first, second);
    }

    @Test
    void contentFingerprintShouldChangeWhenRevisionOrPackageSourceChanges() throws Exception {
        var scopeDir = Files.createDirectories(dir.resolve("scope"));
        var pkg = bundle(dir.resolve("pkg"), "demo-a");
        var spec = new OpenSandboxFilesystemSpec().packageSkillsDir(pkg);
        var reader = mock(WorkspaceReader.class);
        var rev = new AtomicReference<>("rev-1");
        when(reader.readUserSkillRevision(anyString())).thenAnswer(inv -> rev.get());
        var baseline = ScopedSkillCacheSupport.contentFingerprint(spec, reader, "u1", scopeDir);

        rev.set("rev-2");
        assertNotEquals(baseline, ScopedSkillCacheSupport.contentFingerprint(spec, reader, "u1", scopeDir),
            "L4 revision 变化 → 指纹变");

        rev.set("rev-1");
        bundle(pkg, "demo-b");
        assertNotEquals(baseline, ScopedSkillCacheSupport.contentFingerprint(spec, reader, "u1", scopeDir),
            "包内源内容变化 → 指纹变");
    }

    @Test
    void contentFingerprintShouldTreatMissingReaderOrBlankUidAsNoRevision() throws Exception {
        var scopeDir = Files.createDirectories(dir.resolve("scope"));
        var pkg = bundle(dir.resolve("pkg"), "demo-a");
        var spec = new OpenSandboxFilesystemSpec().packageSkillsDir(pkg);
        var reader = mock(WorkspaceReader.class);
        when(reader.readUserSkillRevision(anyString())).thenReturn("rev-1");

        assertEquals(
            ScopedSkillCacheSupport.contentFingerprint(spec, null, "u1", scopeDir),
            ScopedSkillCacheSupport.contentFingerprint(spec, reader, null, scopeDir),
            "无 reader 与空白 uid 同语义：revision 分量为空");
        assertNotEquals(ScopedSkillCacheSupport.contentFingerprint(spec, reader, "u1", scopeDir),
            ScopedSkillCacheSupport.contentFingerprint(spec, null, "u1", scopeDir));
    }

    // ---- disabledSkillDirs（包级 + 用户级禁用解析） ----

    @Test
    void disabledSkillDirsShouldReturnEmptyWithoutCheckerOrScopeDir() throws Exception {
        var scopeDir = Files.createDirectories(dir.resolve("scope"));

        assertEquals(Set.of(), ScopedSkillCacheSupport.disabledSkillDirs(null, "u1", scopeDir));
        assertEquals(Set.of(), ScopedSkillCacheSupport.disabledSkillDirs(
            checker(Set.of(), Set.of()), "u1", dir.resolve("missing")));
    }

    @Test
    void disabledSkillDirsShouldResolvePackageAndUserDisabled() throws Exception {
        var scopeDir = Files.createDirectories(dir.resolve("scope"));
        skill(scopeDir, "oaf-package", "demo-a");
        skill(scopeDir, "oaf-package", "demo-b");
        skill(scopeDir, "oaf-package", "demo-c");

        var disabled = ScopedSkillCacheSupport.disabledSkillDirs(
            checker(Set.of("demo-b"), Set.of("demo-c")), "u1", scopeDir);

        assertEquals(Set.of(
            scopeDir.relativize(scopeDir.resolve("oaf-package/demo-b")),
            scopeDir.relativize(scopeDir.resolve("oaf-package/demo-c"))), disabled);
    }

    @Test
    void disabledSkillDirsShouldFailSoftOnCheckerErrors() throws Exception {
        var scopeDir = Files.createDirectories(dir.resolve("scope"));
        skill(scopeDir, "oaf-package", "demo-a");
        skill(scopeDir, "oaf-package", "demo-b");

        var pkgBroken = new SkillDisabledChecker() {
            @Override
            public Set<String> packageDisabled() {
                throw new IllegalStateException("kv down");
            }

            @Override
            public boolean isUserDisabled(String userId, String name) {
                return "demo-b".equals(name);
            }
        };
        assertEquals(Set.of(scopeDir.relativize(scopeDir.resolve("oaf-package/demo-b"))),
            ScopedSkillCacheSupport.disabledSkillDirs(pkgBroken, "u1", scopeDir),
            "包级禁用集读取失败按空集处理，用户级判定不受影响");

        var userBroken = new SkillDisabledChecker() {
            @Override
            public Set<String> packageDisabled() {
                return Set.of();
            }

            @Override
            public boolean isUserDisabled(String userId, String name) {
                if ("demo-a".equals(name)) {
                    throw new IllegalStateException("kv down");
                }
                return "demo-b".equals(name);
            }
        };
        assertEquals(Set.of(scopeDir.relativize(scopeDir.resolve("oaf-package/demo-b"))),
            ScopedSkillCacheSupport.disabledSkillDirs(userBroken, "u1", scopeDir),
            "单技能用户级判定异常按未禁用处理，其余技能照常解析");
    }

    // ---- disabledRmCmd（容器内禁用目录清理命令） ----

    @Test
    void disabledRmCmdShouldBeEmptyWithoutDisabledDirs() {
        assertEquals("", ScopedSkillCacheSupport.disabledRmCmd("sess-A", Set.of(), "/workspace"));
    }

    @Test
    void disabledRmCmdShouldQuoteScopeRootPathsInInputOrder() {
        var scopeDir = Path.of("scope");
        var disabled = new LinkedHashSet<Path>();
        disabled.add(Path.of("oaf-package/demo-b"));
        disabled.add(Path.of("oaf-package/demo-c"));

        assertEquals("rm -rf '/workspace/.skills-cache/sess-A/oaf-package/demo-b'"
            + " && rm -rf '/workspace/.skills-cache/sess-A/oaf-package/demo-c'",
            ScopedSkillCacheSupport.disabledRmCmd("sess-A", disabled, "/workspace"));
    }

    @Test
    void disabledRmCmdShouldSkipUnsafeRelPaths() {
        var disabled = new LinkedHashSet<Path>();
        disabled.add(Path.of("oaf-package/demo o"));
        disabled.add(Path.of("oaf-pack'age/demo-x"));
        disabled.add(Path.of("../escape/demo-x"));
        disabled.add(Path.of("oaf-package/demo-b"));

        assertEquals("rm -rf '/workspace/.skills-cache/sess-A/oaf-package/demo-b'",
            ScopedSkillCacheSupport.disabledRmCmd("sess-A", disabled, "/workspace"));
    }

    // ---- isShellSafeRelPath（shell 单引号段安全） ----

    @Test
    void shellSafetyRules() {
        assertTrue(ScopedSkillCacheSupport.isShellSafeRelPath("oaf-package/demo-a"));
        assertTrue(ScopedSkillCacheSupport.isShellSafeRelPath("ns/name.ext"));
        assertFalse(ScopedSkillCacheSupport.isShellSafeRelPath(null));
        assertFalse(ScopedSkillCacheSupport.isShellSafeRelPath("  "));
        assertFalse(ScopedSkillCacheSupport.isShellSafeRelPath("/abs/path"));
        assertFalse(ScopedSkillCacheSupport.isShellSafeRelPath("a/../b"));
        assertFalse(ScopedSkillCacheSupport.isShellSafeRelPath("a'quote"));
        assertFalse(ScopedSkillCacheSupport.isShellSafeRelPath("has space"));
    }
}
