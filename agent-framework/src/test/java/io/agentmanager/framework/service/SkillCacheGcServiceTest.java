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

        int removed = SkillCacheGcService.gc(cacheDir, 2);

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

        assertEquals(0, SkillCacheGcService.gc(cacheDir, 2));
        assertTrue(Files.exists(scope));
    }

    @Test
    void nonPositiveRetentionDisablesGc(@TempDir Path cacheDir) throws Exception {
        var scope = scopeDir(cacheDir, "stale-scope");
        setMtime(scope, Instant.now().minus(30, ChronoUnit.DAYS));

        assertEquals(0, SkillCacheGcService.gc(cacheDir, 0));
        assertTrue(Files.exists(scope), "retention<=0 = 关闭，不删除");
    }

    @Test
    void missingCacheDirIsNoop(@TempDir Path base) throws Exception {
        assertEquals(0, SkillCacheGcService.gc(base.resolve("nope"), 2));
    }
}
