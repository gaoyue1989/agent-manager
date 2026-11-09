package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link SkillCacheGcService#gc(Path, int)} 按龄清扫宿主 .skills-cache 的 scope 目录。 */
class SkillCacheGcServiceTest {

    private static Path scopeDir(Path cacheDir, String scope) throws Exception {
        Path dir = cacheDir.resolve(scope).resolve("oaf-package").resolve("skill-a");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: a\n---\n");
        return cacheDir.resolve(scope);
    }

    private static void setMtime(Path p, Instant t) throws Exception {
        Files.setLastModifiedTime(p, FileTime.from(t));
    }

    /** 整棵子树统一设为给定时间（模拟该 scope 目录长期未被 stage/touch）。 */
    private static void setAllMtime(Path root, Instant t) throws Exception {
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                setMtime(p, t);
            }
        }
    }

    @Test
    void removesStaleScopeDirKeepsFresh(@TempDir Path cacheDir) throws Exception {
        var now = Instant.now();
        var stale = scopeDir(cacheDir, "stale-scope");
        var fresh = scopeDir(cacheDir, "fresh-scope");
        // 整棵 stale 子树设为 3 天前；fresh 保持当前
        setAllMtime(stale, now.minus(3, ChronoUnit.DAYS));
        setMtime(fresh, now);

        int removed = SkillCacheGcService.gc(cacheDir, 2, 0);

        assertEquals(1, removed);
        assertFalse(Files.exists(stale), "过期 scope 目录应被删除");
        assertTrue(Files.exists(fresh), "近期 scope 目录应保留");
    }

    @Test
    void childRecentMtimeKeepsScope(@TempDir Path cacheDir) throws Exception {
        // 顶层目录 mtime 旧、但内部技能目录刚被 SDK touch → 子树最新 mtime 新 → 保留
        var scope = scopeDir(cacheDir, "touched-scope");
        setMtime(scope, Instant.now().minus(5, ChronoUnit.DAYS));
        setMtime(scope.resolve("oaf-package").resolve("skill-a"), Instant.now());

        assertEquals(0, SkillCacheGcService.gc(cacheDir, 2, 0));
        assertTrue(Files.exists(scope));
    }

    @Test
    void nonPositiveRetentionDisablesGc(@TempDir Path cacheDir) throws Exception {
        var scope = scopeDir(cacheDir, "stale-scope");
        setMtime(scope, Instant.now().minus(30, ChronoUnit.DAYS));

        assertEquals(0, SkillCacheGcService.gc(cacheDir, 0, 0));
        assertTrue(Files.exists(scope), "retention<=0 = 关闭，不删除");
    }

    @Test
    void missingCacheDirIsNoop(@TempDir Path base) throws Exception {
        assertEquals(0, SkillCacheGcService.gc(base.resolve("nope"), 2, 0));
    }

    // ==================== P1 容量护栏（maxTotalMb） ====================

    /** 超限按「子树最新 mtime 最旧优先」逐目录回收，直至回落限内 */
    @Test
    void capacityExceededRemovesOldestScopesFirst(@TempDir Path cacheDir) throws Exception {
        var now = Instant.now();
        // old：3 天前（1MB）；mid：1 天前（2MB）；fresh：现在（1MB）→ 总 4MB，限 2MB
        var old = writeSizedScope(cacheDir, "old-scope", 1);
        var mid = writeSizedScope(cacheDir, "mid-scope", 2);
        var fresh = writeSizedScope(cacheDir, "fresh-scope", 1);
        setAllMtime(old, now.minus(3, ChronoUnit.DAYS));
        setAllMtime(mid, now.minus(1, ChronoUnit.DAYS));
        setAllMtime(fresh, now);

        int removed = SkillCacheGcService.gc(cacheDir, 30, 2);

        assertEquals(2, removed, "超限 2MB：先删最旧 old（1MB）仍超，再删 mid（2MB）后 1MB ≤ 限");
        assertFalse(Files.exists(old), "最旧 scope 先删");
        assertFalse(Files.exists(mid));
        assertTrue(Files.exists(fresh), "最新 scope 保留");
    }

    /** 未超限不删任何目录（容量护栏与按龄规则独立） */
    @Test
    void underCapacityKeepsAllScopes(@TempDir Path cacheDir) throws Exception {
        var scope = writeSizedScope(cacheDir, "small-scope", 1);
        assertEquals(0, SkillCacheGcService.gc(cacheDir, 2, 5120));
        assertTrue(Files.exists(scope));
    }

    /** maxTotalMb≤0 = 容量护栏关闭（只剩按龄规则） */
    @Test
    void nonPositiveCapacityDisablesCapacityGuard(@TempDir Path cacheDir) throws Exception {
        var big = writeSizedScope(cacheDir, "big-scope", 3);
        assertEquals(0, SkillCacheGcService.gc(cacheDir, 2, 0));
        assertTrue(Files.exists(big), "max<=0 不按容量删除");
    }

    /** 写入约 sizeMb 的 scope 目录（单个 1MB 填充文件），返回 scope 目录路径 */
    private static Path writeSizedScope(Path cacheDir, String scope, int sizeMb) throws Exception {
        Path dir = cacheDir.resolve(scope).resolve("oaf-package").resolve("skill-a");
        Files.createDirectories(dir);
        byte[] oneMb = new byte[1024 * 1024];
        java.util.Arrays.fill(oneMb, (byte) 'x');
        Files.write(dir.resolve("blob.bin"), oneMb);
        if (sizeMb > 1) {
            Files.write(dir.resolve("blob2.bin"), java.util.Arrays.copyOfRange(oneMb, 0, (sizeMb - 1) * 1024 * 1024));
        }
        return cacheDir.resolve(scope);
    }
}
