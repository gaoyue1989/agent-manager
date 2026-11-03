package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.skill.SkillFilter;
import reactor.core.publisher.Flux;

/**
 * {@link SkillFilterMiddleware} 单测（docs/design/session-skill-list-design.md §4）：
 * 本轮 skills 列表 → per-call SkillFilter 白名单；缺省 = 不设置（全部）；空列表 = none；
 * 未知技能名忽略（未知名仅在白名单里，仓库中匹配不到）；禁用由后续中间件 overlay 剔除。
 */
class SkillFilterMiddlewareTest {

    private static final String KEY = SkillFilterMiddleware.METADATA_SKILLS_KEY;

    private static Msg userMsg(Object skillsValue) {
        var md = new LinkedHashMap<String, Object>();
        if (skillsValue != null) {
            md.put(KEY, skillsValue);
        }
        return Msg.builder().role(MsgRole.USER).textContent("hi").metadata(md).build();
    }

    private static AgentInput input(Msg... msgs) {
        return new AgentInput(List.of(msgs));
    }

    private static RuntimeContext run(AgentInput input) {
        var ctx = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        new SkillFilterMiddleware().onAgent(mock(io.agentscope.core.agent.Agent.class), ctx,
            input, in -> Flux.empty()).blockLast();
        return ctx;
    }

    @Test
    void skillsListShouldSetOnlyWhitelist() {
        var ctx = run(input(userMsg(List.of("a", "b"))));
        var filter = ctx.get(SkillFilter.class);
        assertNotNull(filter, "非空 skills 必须设置 per-call filter");
        assertTrue(filter.isAllowed("a"));
        assertTrue(filter.isAllowed("b"));
        assertFalse(filter.isAllowed("c"), "白名单外技能必须被过滤");
        assertFalse(filter.isOverlay(), "only 白名单非 overlay（SDK 语义：整体采用）");
    }

    @Test
    void emptyListShouldDenyAllSkills() {
        var ctx = run(input(userMsg(List.of())));
        var filter = ctx.get(SkillFilter.class);
        assertNotNull(filter, "显式空数组 = 无技能（不能退化为全部）");
        assertFalse(filter.isAllowed("anything"));
    }

    @Test
    void missingKeyShouldNotSetFilter() {
        var ctx = run(input(userMsg(null)));
        assertNull(ctx.get(SkillFilter.class), "未传 skills 不设置 filter（全部启用技能，存量行为）");
    }

    @Test
    void unknownNamesShouldBeIgnoredByRepoButAllowedInList() {
        // 未知名不做校验：它只是白名单里的一个名字，仓库中不存在 → 实际不可见
        var ctx = run(input(userMsg(List.of("ghost", "real"))));
        var filter = ctx.get(SkillFilter.class);
        assertNotNull(filter);
        assertTrue(filter.isAllowed("ghost"), "未知名进入白名单但不影响其他名字");
        assertTrue(filter.isAllowed("real"));
        assertFalse(filter.isAllowed("other"));
    }

    @Test
    void duplicateAndBlankNamesShouldBeNormalized() {
        // SkillFilter.only 内部 Set.of 拒绝重复名——中间件必须先归一
        assertDoesNotThrow(() -> run(input(userMsg(List.of("a", "a", " ", "", "b")))));
        var ctx = run(input(userMsg(List.of("a", "a", "b"))));
        var filter = ctx.get(SkillFilter.class);
        assertTrue(filter.isAllowed("a"));
        assertTrue(filter.isAllowed("b"));
    }

    @Test
    void lastUserMessageShouldWin() {
        // 历史消息带旧白名单，本轮未传 → 不限制（仅本轮生效）
        var ctx = run(input(
            userMsg(List.of("old-skill")),
            Msg.builder().role(MsgRole.ASSISTANT).textContent("reply").build(),
            userMsg(null)));
        assertNull(ctx.get(SkillFilter.class), "本轮未传 → 不沿用历史轮次的白名单");
    }

    @Test
    void emptyListInCurrentTurnShouldOverrideHistory() {
        var ctx = run(input(
            userMsg(List.of("old-skill")),
            userMsg(List.of())));
        var filter = ctx.get(SkillFilter.class);
        assertNotNull(filter);
        assertFalse(filter.isAllowed("old-skill"));
        assertFalse(filter.isAllowed("anything"));
    }

    @Test
    void commaSeparatedStringShouldBeAccepted() {
        // A2A 旧客户端把 skills 放 metadata 为字符串时的兼容形态
        var ctx = run(input(userMsg("a, b ,c")));
        var filter = ctx.get(SkillFilter.class);
        assertTrue(filter.isAllowed("a"));
        assertTrue(filter.isAllowed("b"));
        assertTrue(filter.isAllowed("c"));
        assertFalse(filter.isAllowed("d"));
    }

    @Test
    void existingCtxFilterShouldNotExpandWhitelist() {
        var ctx = RuntimeContext.builder().sessionId("s1").userId("u1").build();
        ctx.put(SkillFilter.class, SkillFilter.all());
        new SkillFilterMiddleware().onAgent(mock(io.agentscope.core.agent.Agent.class), ctx,
            input(userMsg(List.of("a"))), in -> Flux.empty()).blockLast();
        var filter = ctx.get(SkillFilter.class);
        assertTrue(filter.isAllowed("a"));
        assertFalse(filter.isAllowed("b"), "existing=all 不得扩权白名单外技能");
    }

    @Test
    void malformedValueShouldNotThrow() {
        var ctx = run(input(userMsg(123)));
        assertNotNull(ctx.get(SkillFilter.class), "非列表值按单名处理，不抛异常");
        assertTrue(ctx.get(SkillFilter.class).isAllowed("123"));
    }

    @Test
    void nullInputOrContextShouldNotThrow() {
        assertDoesNotThrow(() -> new SkillFilterMiddleware().onAgent(
            mock(io.agentscope.core.agent.Agent.class), null, input(userMsg(List.of("a"))),
            in -> Flux.empty()).blockLast());
        assertDoesNotThrow(() -> new SkillFilterMiddleware().onAgent(
            mock(io.agentscope.core.agent.Agent.class),
            RuntimeContext.builder().sessionId("s1").userId("u1").build(), null,
            in -> Flux.empty()).blockLast());
    }
}
