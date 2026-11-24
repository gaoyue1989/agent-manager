package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.skill.SkillFilter;
import reactor.core.publisher.Flux;

/**
 * {@link SkillDisabledFilterMiddleware} 单测：把 L2 包内 + L4 个人禁用技能合成为
 * per-call SkillFilter overlay，使 SDK 的 {@code <available_skills>} / load 工具排除它们。
 */
class SkillDisabledFilterMiddlewareTest {

    private static UserSkillService.UserSkillSummary summary(String name, boolean enabled) {
        return new UserSkillService.UserSkillSummary(
            name, List.of("SKILL.md"), 10L, 1L, false, false, "desc", enabled, "2026-01-01 00:00:00.000");
    }

    @Test
    void disabledSkillNamesShouldUnionL2AndL4() {
        var l2 = mock(SkillManageService.class);
        when(l2.getDisabledSet()).thenReturn(Set.of("pkgA"));
        var l4 = mock(UserSkillService.class);
        when(l4.listSkills("alice")).thenReturn(List.of(
            summary("userA", false), summary("userB", true)));
        var resolver = mock(SessionKeyResolver.class);
        when(resolver.realUserId(any())).thenReturn("alice");

        var mw = new SkillDisabledFilterMiddleware(l2, l4, resolver);
        var ctx = RuntimeContext.builder().sessionId("gw-x").userId("peer-y").build();

        assertEquals(Set.of("pkgA", "userA"), mw.disabledSkillNames(ctx));
    }

    @Test
    void onAgentShouldInjectOverlayFilter() {
        var l2 = mock(SkillManageService.class);
        when(l2.getDisabledSet()).thenReturn(Set.of("pkgA"));
        var l4 = mock(UserSkillService.class);
        when(l4.listSkills("alice")).thenReturn(List.of(summary("userA", false)));
        var resolver = mock(SessionKeyResolver.class);
        when(resolver.realUserId(any())).thenReturn("alice");

        var mw = new SkillDisabledFilterMiddleware(l2, l4, resolver);
        var ctx = RuntimeContext.builder().sessionId("gw-x").userId("peer-y").build();

        mw.onAgent(mock(io.agentscope.core.agent.Agent.class), ctx,
            mock(AgentInput.class), in -> Flux.empty()).blockLast();

        var filter = ctx.get(SkillFilter.class);
        assertNotNull(filter, "禁用集非空时必须注入 SkillFilter overlay");
        assertFalse(filter.isAllowed("pkgA"), "包内禁用技能必须被过滤");
        assertFalse(filter.isAllowed("userA"), "个人禁用技能必须被过滤");
        assertTrue(filter.isAllowed("other"), "未禁用技能不受影响");
        assertTrue(filter.isOverlay(), "必须是 overlay（与 builder 级 filter 叠加）");
    }

    @Test
    void onAgentShouldNotInjectFilterWhenNothingDisabled() {
        var l2 = mock(SkillManageService.class);
        when(l2.getDisabledSet()).thenReturn(Set.of());
        var l4 = mock(UserSkillService.class);
        when(l4.listSkills(any())).thenReturn(List.of(summary("userB", true)));
        var resolver = mock(SessionKeyResolver.class);
        when(resolver.realUserId(any())).thenReturn("alice");

        var mw = new SkillDisabledFilterMiddleware(l2, l4, resolver);
        var ctx = RuntimeContext.builder().sessionId("gw-x").userId("peer-y").build();

        mw.onAgent(mock(io.agentscope.core.agent.Agent.class), ctx,
            mock(AgentInput.class), in -> Flux.empty()).blockLast();

        assertNull(ctx.get(SkillFilter.class), "无禁用技能时不注入，保持 SDK 默认 all()");
    }

    @Test
    void resolveFailureShouldBeFailSoft() {
        var l2 = mock(SkillManageService.class);
        when(l2.getDisabledSet()).thenThrow(new RuntimeException("db down"));
        var l4 = mock(UserSkillService.class);
        when(l4.listSkills(any())).thenThrow(new RuntimeException("kv down"));
        var resolver = mock(SessionKeyResolver.class);
        when(resolver.realUserId(any())).thenReturn("alice");

        var mw = new SkillDisabledFilterMiddleware(l2, l4, resolver);
        var ctx = RuntimeContext.builder().sessionId("gw-x").userId("peer-y").build();

        // 两侧都抛异常 → 返回空集，不抛出
        assertTrue(mw.disabledSkillNames(ctx).isEmpty());
    }

    @Test
    void nullDependenciesShouldNotThrow() {
        var mw = new SkillDisabledFilterMiddleware(null, null, null);
        var ctx = RuntimeContext.builder().sessionId("gw-x").userId("peer-y").build();
        assertTrue(mw.disabledSkillNames(ctx).isEmpty());
    }

    @Test
    void l2DirNameDisableShouldBlockCanonicalSkillName(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
            throws java.io.IOException {
        var skillsDir = tempDir.resolve("skills");
        java.nio.file.Files.createDirectories(skillsDir);
        var dir = skillsDir.resolve("my-dir-name");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("SKILL.md"),
            "---\nname: my-frontmatter-name\ndescription: m\n---\n# body\n");
        var props = new io.agentmanager.framework.config.AgentManagerProperties(
            null, null, null, tempDir.toString(), null, null, null, null, null);
        var manage = new SkillManageService(props);
        manage.toggleSkill("my-dir-name");

        var mw = new SkillDisabledFilterMiddleware(
            manage, mock(UserSkillService.class), mock(SessionKeyResolver.class));
        var ctx = RuntimeContext.builder().sessionId("gw-x").userId("peer-y").build();

        mw.onAgent(mock(io.agentscope.core.agent.Agent.class), ctx,
            mock(AgentInput.class), in -> Flux.empty()).blockLast();

        var filter = ctx.get(SkillFilter.class);
        assertNotNull(filter);
        assertEquals(java.util.Set.of("my-frontmatter-name"), mw.disabledSkillNames(ctx));
        assertFalse(filter.isAllowed("my-frontmatter-name"),
            "按目录名禁用必须拦住规范名（SDK 按 skill.getName() 匹配）");
        assertTrue(filter.isAllowed("other"));
    }

    @Test
    void disabledSetShouldOverlayExistingSkillListWhitelist() {
        // session-skill-list-design §4：先由 SkillFilterMiddleware 立本轮白名单，禁用集再 overlay 剔除
        var l2 = mock(SkillManageService.class);
        when(l2.getDisabledSet()).thenReturn(Set.of("pkgA"));
        var l4 = mock(UserSkillService.class);
        when(l4.listSkills(any())).thenReturn(List.of());
        var resolver = mock(SessionKeyResolver.class);
        when(resolver.realUserId(any())).thenReturn("alice");

        var mw = new SkillDisabledFilterMiddleware(l2, l4, resolver);
        var ctx = RuntimeContext.builder().sessionId("gw-x").userId("peer-y").build();
        ctx.put(SkillFilter.class, SkillFilter.only("pkgA", "pkgB"));

        mw.onAgent(mock(io.agentscope.core.agent.Agent.class), ctx,
            mock(AgentInput.class), in -> Flux.empty()).blockLast();

        var filter = ctx.get(SkillFilter.class);
        assertFalse(filter.isAllowed("pkgA"), "禁用技能必须从本轮白名单中剔除");
        assertTrue(filter.isAllowed("pkgB"), "白名单内未禁用技能保留");
        assertFalse(filter.isAllowed("pkgC"), "白名单外技能仍不可见（白名单未被冲掉）");
    }

    @Test
    void noDisabledNamesShouldKeepExistingWhitelist() {
        var l2 = mock(SkillManageService.class);
        when(l2.getDisabledSet()).thenReturn(Set.of());
        var l4 = mock(UserSkillService.class);
        when(l4.listSkills(any())).thenReturn(List.of());
        var resolver = mock(SessionKeyResolver.class);
        when(resolver.realUserId(any())).thenReturn("alice");

        var mw = new SkillDisabledFilterMiddleware(l2, l4, resolver);
        var ctx = RuntimeContext.builder().sessionId("gw-x").userId("peer-y").build();
        var whitelist = SkillFilter.only("pkgA");
        ctx.put(SkillFilter.class, whitelist);

        mw.onAgent(mock(io.agentscope.core.agent.Agent.class), ctx,
            mock(AgentInput.class), in -> Flux.empty()).blockLast();

        assertSame(whitelist, ctx.get(SkillFilter.class), "无禁用名时白名单原样保留");
    }
}
