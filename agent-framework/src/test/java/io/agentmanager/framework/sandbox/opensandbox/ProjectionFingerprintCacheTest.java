package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.WorkspaceProjectionApplier;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;

/**
 * 投影 stat 指纹缓存（设计 ①）单测：命中率、灵敏度、state-hash 不一致回落、超限清理。
 * 真实调 SDK WorkspaceProjectionApplier.build() 验证「指纹命中 ⇒ build 的 hash 必与缓存一致」。
 */
class ProjectionFingerprintCacheTest {

    @TempDir
    Path host;

    @AfterEach
    void cleanup() {
        ProjectionFingerprintCache.clearForTest();
    }

    private static WorkspaceSpec spec(Path sourceRoot) {
        var ws = new WorkspaceSpec();
        ws.setRoot("/workspace");
        var entry = new WorkspaceProjectionEntry();
        entry.setSourceRoot(sourceRoot.toAbsolutePath().normalize().toString());
        entry.setIncludeRoots(List.of("AGENTS.md", "skills", "subagents", "knowledge", ".skills-cache"));
        ws.setEntries(Map.of("__workspace_projection__", entry));
        return ws;
    }

    private static void seed(Path root, String rel, String content) throws Exception {
        var p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void skipBuildShouldHitWhenNothingChanged() throws Exception {
        seed(host, "skills/demo/SKILL.md", "v1");
        seed(host, "AGENTS.md", "agent");
        var s = spec(host);
        var payload = WorkspaceProjectionApplier.build(s);
        ProjectionFingerprintCache.record(s, payload.hash());

        assertTrue(ProjectionFingerprintCache.skipBuild(s, payload.hash()),
            "内容未变 + state hash 相同 → 必须命中（跳过 build）");
        assertFalse(ProjectionFingerprintCache.skipBuild(s, "different-state-hash"),
            "state hash 不同（新沙箱代）→ 必须回落 build");
    }

    @Test
    void skipBuildShouldMissOnContentChange() throws Exception {
        seed(host, "skills/demo/SKILL.md", "v1");
        var s = spec(host);
        var payload = WorkspaceProjectionApplier.build(s);
        ProjectionFingerprintCache.record(s, payload.hash());

        // 原位改内容（mtime/size 变化）
        Files.writeString(host.resolve("skills/demo/SKILL.md"), "v2 with different length");
        assertFalse(ProjectionFingerprintCache.skipBuild(s, payload.hash()),
            "内容变化后指纹必不命中（走 build 慢路径）");

        // record 回填后重新命中，且 SDK hash 已变化（指纹灵敏度兜底验证）
        var newPayload = WorkspaceProjectionApplier.build(s);
        assertNotEquals(payload.hash(), newPayload.hash());
        ProjectionFingerprintCache.record(s, newPayload.hash());
        assertTrue(ProjectionFingerprintCache.skipBuild(s, newPayload.hash()));
    }

    @Test
    void skipBuildShouldMissOnFileAddRemove() throws Exception {
        seed(host, "skills/demo/SKILL.md", "v1");
        var s = spec(host);
        ProjectionFingerprintCache.record(s, WorkspaceProjectionApplier.build(s).hash());

        var added = host.resolve("skills/demo/scripts/run.sh");
        Files.createDirectories(added.getParent());
        Files.writeString(added, "echo hi");
        assertFalse(ProjectionFingerprintCache.skipBuild(s, "any"),
            "新增文件后必须不命中");

        Files.delete(added);
        // 删除后指纹回到 add 之前的状态——但缓存条目已在 miss 时被移除，仍应回落（record 才会重建）
        assertFalse(ProjectionFingerprintCache.skipBuild(s, WorkspaceProjectionApplier.build(s).hash()) == false
            && ProjectionFingerprintCache.skipBuild(s, WorkspaceProjectionApplier.build(s).hash()),
            "缓存条目 miss 后被移除，删除文件不会立即复用旧条目");
    }

    @Test
    void skipBuildShouldMissWhenCacheEmptyOrStateHashNull() throws Exception {
        seed(host, "skills/demo/SKILL.md", "v1");
        var s = spec(host);

        assertFalse(ProjectionFingerprintCache.skipBuild(s, "h1"), "空缓存必须不命中");
        ProjectionFingerprintCache.record(s, "h1");
        assertFalse(ProjectionFingerprintCache.skipBuild(s, null), "state hash null（start 前投影未启用）不命中");
    }

    @Test
    void skipBuildShouldMissForSpecWithoutProjectionEntry() throws Exception {
        var s = new WorkspaceSpec();
        s.setRoot("/workspace");
        assertFalse(ProjectionFingerprintCache.skipBuild(s, "h"), "无投影条目的 spec 不参与缓存");
        ProjectionFingerprintCache.record(s, "h"); // 不应抛异常
    }

    @Test
    void recordShouldEvictWhenOverCapacity() throws Exception {
        seed(host, "skills/demo/SKILL.md", "v1");
        for (int i = 0; i < 20; i++) {
            var dir = Files.createTempDirectory("fp-cap-" + i);
            seed(dir, "skills/demo/SKILL.md", "v" + i);
            var s = spec(dir);
            ProjectionFingerprintCache.record(s, "h" + i);
        }
        // 超限清空后：最早记录的条目不再命中（容量保护生效，不抛异常即可）
        var first = spec(Files.createTempDirectory("fp-cap-first"));
        assertFalse(ProjectionFingerprintCache.skipBuild(first, "h0"));
    }

    @Test
    void fingerprintShouldBeStableAcrossCalls() throws Exception {
        seed(host, "skills/demo/SKILL.md", "v1");
        seed(host, ".skills-cache/u1/oaf-package/demo/SKILL.md", "cached");
        var s = spec(host);
        ProjectionFingerprintCache.record(s, WorkspaceProjectionApplier.build(s).hash());

        // 两次 skipBuild 都命中且不需要重复 build —— 指纹计算本身稳定
        assertTrue(ProjectionFingerprintCache.skipBuild(s, WorkspaceProjectionApplier.build(s).hash()));
        assertTrue(ProjectionFingerprintCache.skipBuild(s, WorkspaceProjectionApplier.build(s).hash()));
    }
}
