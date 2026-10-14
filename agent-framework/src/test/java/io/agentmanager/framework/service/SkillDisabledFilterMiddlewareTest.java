package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
}
