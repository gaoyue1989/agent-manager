package io.agentmanager.framework.sandbox.opensandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.RepoBound;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.StageResult;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.StageResult.Cached;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.StageResult.None;
import io.agentscope.harness.agent.skill.runtime.MarketplaceStager.StageResult.WorkspaceNative;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 遮蔽类行为契约（docs/design/sdk-stager-shadow-design-261114.md §2.3/§3.1）：不写盘 + 纯
 * 数据合成，分类逐字复刻原 stage（WorkspaceSkillRepository→WorkspaceNative，其余→Cached）。
 */
class MarketplaceStagerShadowTest {

    @org.junit.jupiter.api.io.TempDir
    Path tempDir;

    private FileSystemSkillRepository pkgRepo() {
        try {
            return new FileSystemSkillRepository(java.nio.file.Files.createDirectories(tempDir.resolve("pkg")),
                false, "oaf-package");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RepoBound bound(AgentSkillRepository repo, String name) {
        var skill = org.mockito.Mockito.mock(AgentSkill.class);
        org.mockito.Mockito.when(skill.getName()).thenReturn(name);
        return new RepoBound(skill, repo);
    }

    @Test
    void constantsMustMatchSdkOriginalValues() {
        // 消费方（SkillCacheScope/中间件）依赖这些值的字面语义；SDK 升级时本组断言同步重 derive
        assertEquals(".skills-cache", MarketplaceStager.CACHE_DIR);
        assertEquals("_global", MarketplaceStager.GLOBAL_NAMESPACE);
        assertEquals("_shared", MarketplaceStager.SHARED_SCOPE);
        assertEquals(Duration.ofMinutes(30), MarketplaceStager.DEFAULT_ORPHAN_GRACE);
    }

    @Test
    void stageShouldSynthesizeCachedWithoutDiskWrites() {
        var stager = new MarketplaceStager(Path.of("/workspace"));
        var repo = pkgRepo();
        var ns = Map.<AgentSkillRepository, String>of(repo, "oaf-package");

        var out = stager.stage(List.of(bound(repo, "demo-a"), bound(repo, "demo-b")), ns, "user-1");

        var cachedA = assertInstanceOf(Cached.class, out.get("demo-a"));
        assertEquals("user-1", cachedA.scopeSegment());
        assertEquals("oaf-package", cachedA.sourceNamespace());
        assertEquals("demo-a", cachedA.skillName());
        assertTrue(out.get("demo-b") instanceof Cached);
    }

    @Test
    void stageShouldClassifyWorkspaceRepoAsNativeAndMarketplaceAsCached() {
        var wsRepo = new WorkspaceSkillRepository(
            org.mockito.Mockito.mock(io.agentscope.harness.agent.filesystem.AbstractFilesystem.class),
            "skills", "workspace-namespaced", false);
        var repo = pkgRepo();
        var stager = new MarketplaceStager(Path.of("/workspace"));
        var ns = Map.<AgentSkillRepository, String>of(repo, "oaf-package", wsRepo, "workspace-namespaced");

        var out = stager.stage(List.of(bound(wsRepo, "l4-skill"), bound(repo, "demo-a")), ns, "user-1");

        assertInstanceOf(WorkspaceNative.class, out.get("l4-skill"), "L4 原生路径不经过 .skills-cache");
        assertInstanceOf(Cached.class, out.get("demo-a"));
    }

    @Test
    void nullScopeShouldFallBackToSharedSegment() {
        var stager = new MarketplaceStager(Path.of("/workspace"));

        var repo = pkgRepo();
        var out = stager.stage(List.of(bound(repo, "demo-a")),
            Map.<AgentSkillRepository, String>of(repo, "oaf-package"), null);

        var cached = assertInstanceOf(Cached.class, out.get("demo-a"));
        assertEquals("_shared", cached.scopeSegment(), "scope 缺省 = 共享段（与原 scopeSegment 语义一致）");
    }

    @Test
    void twoArgOverloadShouldDelegateWithNullScope() {
        var stager = new MarketplaceStager(Path.of("/workspace"));

        var repo = pkgRepo();
        var out = stager.stage(List.of(bound(repo, "demo-a")),
            Map.<AgentSkillRepository, String>of(repo, "oaf-package"));

        assertInstanceOf(Cached.class, out.get("demo-a"));
    }

    @Test
    void nullOrEmptyBoundsShouldReturnEmptyMap() {
        var stager = new MarketplaceStager(Path.of("/workspace"));
        assertTrue(stager.stage(null, null, "s").isEmpty());
        assertTrue(stager.stage(List.of(), Map.of(), "s").isEmpty());
    }

    @Test
    void resolveSourceNamespacesShouldPassThroughUniqueSources() throws Exception {
        var repo = pkgRepo();
        var other = new FileSystemSkillRepository(java.nio.file.Files.createDirectories(tempDir.resolve("other")), true, "market-x");

        var out = MarketplaceStager.resolveSourceNamespaces(List.of(repo, other));

        assertEquals("oaf-package", out.get(repo));
        assertEquals("market-x", out.get(other));
    }

    @Test
    void resolveSourceNamespacesShouldDisambiguateDuplicatesWithIndexSuffix() throws Exception {
        var dupA = new FileSystemSkillRepository(java.nio.file.Files.createDirectories(tempDir.resolve("a")), false, "oaf-package");
        var dupB = new FileSystemSkillRepository(java.nio.file.Files.createDirectories(tempDir.resolve("b")), false, "oaf-package");

        var out = MarketplaceStager.resolveSourceNamespaces(List.of(dupA, dupB));

        assertEquals("oaf-package-1", out.get(dupA), "重复 source 按出现序加 -n 后缀（原 concat 语义）");
        assertEquals("oaf-package-2", out.get(dupB));
    }

    @Test
    void resolveSourceNamespacesShouldHandleNullAndBlankSources() {
        var anonymous = org.mockito.Mockito.mock(AgentSkillRepository.class);
        org.mockito.Mockito.when(anonymous.getSource()).thenReturn(null);

        var out = MarketplaceStager.resolveSourceNamespaces(List.of(anonymous));

        assertEquals("_global", out.get(anonymous), "source 空 = _global（原 effectiveSource 语义）");
        assertTrue(MarketplaceStager.resolveSourceNamespaces(null).isEmpty());
    }

    @Test
    void shadowMarkerMustExistForStartupSelfCheck() {
        // 启动自检依据：原类没有此字段——缺失即遮蔽失效（jar 原版被加载）
        assertEquals("agent-framework", MarketplaceStager.SHADOW_MARKER);
    }

    @Test
    void noneAndWorkspaceNativeMustBeStatelessSingletons() {
        assertSame(None.class, StageResult.NONE.getClass());
        assertDoesNotThrow(None::new);
        assertDoesNotThrow(WorkspaceNative::new);
    }
}
