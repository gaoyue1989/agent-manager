package io.agentmanager.framework.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.skill.SkillFilter;
import reactor.core.publisher.Flux;

/**
 * 技能禁用过滤 middleware：把"被禁用的技能名"以 {@link SkillFilter} overlay 注入本次调用的
 * {@link RuntimeContext}，让 SDK 的 HarnessSkillMiddleware 在渲染 {@code <available_skills>}
 * 与 {@code load_skill_through_path} 时排除它们。
 *
 * <p><b>为什么需要它</b>：技能禁用此前只作用于框架自有视图（{@code /skills}、{@code @Skill}
 * 注入、沙箱物化/回写），而真正把技能喂给模型的是 SDK 的技能仓库——L2 包内
 * （{@code FileSystemSkillRepository}）与 L4 个人（{@code WorkspaceSkillRepository}）。
 * 仓库层不认识 {@code .skill-states.json} / {@code .disabled} 标记，于是被禁用的技能仍会
 * 出现在 {@code <available_skills>} 并可经 {@code load_skill_through_path} 加载。本中间件
 * 用 SDK 官方的 per-call filter 钩子（{@code effectiveFilter = builderFilter.overlay(ctx.filter)}）
 * 在调用期把禁用名叠加进去，覆盖两条链路。
 *
 * <p>禁用名来源：L2 = {@link SkillManageService#getDisabledSet()}；L4 = 该用户
 * {@link UserSkillService#listSkills} 中 {@code enabled=false} 者。L4 的用户身份经
 * {@link SessionKeyResolver#realUserId} 解析（Channel 链路 ctx.userId 是网关 peer，需按
 * session_user 反查真实用户）。解析失败 fail-soft：不禁用任何技能、不阻断对话。
 */
public class SkillDisabledFilterMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SkillDisabledFilterMiddleware.class);

    private final SkillManageService skillManageService;
    private final UserSkillService userSkillService;
    private final SessionKeyResolver sessionKeyResolver;

    public SkillDisabledFilterMiddleware(SkillManageService skillManageService,
                                         UserSkillService userSkillService,
                                         SessionKeyResolver sessionKeyResolver) {
        this.skillManageService = skillManageService;
        this.userSkillService = userSkillService;
        this.sessionKeyResolver = sessionKeyResolver;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (ctx != null) {
            try {
                var names = disabledSkillNames(ctx);
                if (!names.isEmpty()) {
                    // overlay 与 builder 级 filter 叠加（默认 all），仅禁用这些名字
                    ctx.put(SkillFilter.class, SkillFilter.disable(names.toArray(new String[0])));
                }
            } catch (Exception e) {
                log.warn("[skill-filter] resolve disabled skills failed: {}", e.getMessage());
            }
        }
        return next.apply(input);
    }

    /**
     * 计算本次调用应禁用的技能名（L2 包内 ∪ L4 个人），去重。
     * 任一来源查询失败仅告警，不影响其他来源（fail-soft）。
     */
    Set<String> disabledSkillNames(RuntimeContext ctx) {
        var names = new LinkedHashSet<String>();
        // L2 包内技能禁用集（.skill-states.json；平台只读 /config 下通常为空）
        try {
            var l2 = skillManageService != null ? skillManageService.getDisabledSet() : null;
            if (l2 != null) {
                names.addAll(l2);
            }
        } catch (Exception e) {
            log.warn("[skill-filter] read L2 disabled set failed: {}", e.getMessage());
        }
        // L4 个人技能禁用（.disabled 标记）
        try {
            var userId = sessionKeyResolver != null ? sessionKeyResolver.realUserId(ctx) : null;
            if (userId != null && !userId.isBlank() && userSkillService != null) {
                for (var skill : userSkillService.listSkills(userId)) {
                    if (skill != null && !skill.enabled() && skill.name() != null
                            && !skill.name().isBlank()) {
                        names.add(skill.name());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[skill-filter] read L4 disabled skills failed: {}", e.getMessage());
        }
        return names;
    }
}
