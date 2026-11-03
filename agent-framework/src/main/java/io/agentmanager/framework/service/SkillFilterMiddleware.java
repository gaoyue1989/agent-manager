package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.skill.SkillFilter;
import reactor.core.publisher.Flux;

/**
 * 会话级技能过滤 middleware：按<b>本轮请求</b>传入的可用技能列表（{@code skills}），
 * 以 per-call {@link SkillFilter#only} 白名单收敛 SDK 的技能可见集——模型侧的
 * {@code <available_skills>} 与 {@code load_skill_through_path} 只看到本次声明的技能。
 *
 * <p><b>传递链路</b>：调用方在 {@code POST /threads/chat} 的 {@code skills} 字段（或 A2A
 * {@code message.metadata.skills}）声明列表；入口把它写入<b>本条用户消息的 metadata</b>
 * （{@link #METADATA_SKILLS_KEY}），本中间件从 {@link AgentInput} 的最后一条 USER 消息读取
 * （与 UiContextInjectionHook 读消息 metadata 的先例同机制）。列表不落库、不进进程内状态，
 * 仅对本轮生效；下一轮未传即恢复全部技能。
 *
 * <p><b>语义</b>：
 * <ul>
 *   <li>metadata 无该键（未传） → 不设置 filter（全部启用技能，存量行为逐字节不变）；</li>
 *   <li>显式空数组 → {@link SkillFilter#none()}（模型看不到任何技能）；</li>
 *   <li>非空数组 → {@code only(names)}；未知技能名自然匹配不到任何仓库（忽略 + 无需校验），
 *       已禁用技能由 {@link SkillDisabledFilterMiddleware} 的 overlay 剔除（禁用优先）。</li>
 * </ul>
 *
 * <p>顺序约束：必须排在 {@link SkillDisabledFilterMiddleware} <b>之前</b>——后者把禁用名以
 * {@code existing.overlay(disable(...))} 叠加进同一 ctx 键，先立白名单、再减禁用集。
 *
 * <p>fail-soft：任何异常只 WARN、不设置 filter（回落全部技能），不阻断对话。
 */
public class SkillFilterMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SkillFilterMiddleware.class);

    /**
     * 本轮可用技能列表的 metadata 键（用户消息 metadata）。
     * chat 入口写入 {@code List<String>}；A2A 调用方经 {@code message.metadata.skills} 透传。
     */
    public static final String METADATA_SKILLS_KEY = "skills";

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (ctx != null && input != null) {
            try {
                applySkillFilter(ctx, input);
            } catch (Exception e) {
                log.warn("[skill-filter] apply per-turn skills failed, fallback to all skills: {}",
                    e.getMessage());
            }
        }
        return next.apply(input);
    }

    /**
     * 从最后一条 USER 消息读取 skills 并装配白名单；无该键 → 直通（全部技能）。
     * 只取最后一条：历史消息的同名键属于旧轮次，不能参与本轮判定（仅本轮生效）。
     */
    private void applySkillFilter(RuntimeContext ctx, AgentInput input) {
        List<String> requested = turnSkillNames(input.msgs());
        if (requested == null) {
            return; // 未传 = 不限制
        }
        var names = normalize(requested);
        var filter = names.isEmpty()
            ? SkillFilter.none()
            : SkillFilter.only(names.toArray(String[]::new));
        // ctx 已有 filter（防御：未来中间件先行注入）时以其为底做 overlay——本 filter 是
        // only/none（非 overlay），SDK 合并语义下结果恒为白名单本身：既有约束不丢失也不扩权
        var existing = ctx.get(SkillFilter.class);
        ctx.put(SkillFilter.class, existing != null ? existing.overlay(filter) : filter);
        if (log.isDebugEnabled()) {
            log.debug("[skill-filter] session={} skills={}", ctx.getSessionId(), names);
        }
    }

    /**
     * 本轮（最后一条 USER 消息）的 skills 声明；该消息未携带键 → null（= 未传）。
     * 只认最后一条 USER 消息、不回溯历史——历史消息的同名键属于旧轮次（仅本轮生效）。
     */
    private static List<String> turnSkillNames(List<Msg> msgs) {
        if (msgs == null) {
            return null;
        }
        for (int i = msgs.size() - 1; i >= 0; i--) {
            var msg = msgs.get(i);
            if (msg == null || msg.getRole() != MsgRole.USER) {
                continue;
            }
            var metadata = msg.getMetadata();
            return metadata != null && metadata.containsKey(METADATA_SKILLS_KEY)
                ? toSkillNames(metadata.get(METADATA_SKILLS_KEY))
                : null;
        }
        return null;
    }

    /** metadata 值 → 技能名列表：支持 List（chat 入口）/ 逗号分隔字符串（A2A 旧客户端） */
    private static List<String> toSkillNames(Object raw) {
        var names = new ArrayList<String>();
        if (raw instanceof Collection<?> coll) {
            for (var item : coll) {
                if (item != null) {
                    names.add(String.valueOf(item));
                }
            }
        } else if (raw instanceof String s) {
            for (var part : s.split(",")) {
                names.add(part);
            }
        } else if (raw != null) {
            names.add(String.valueOf(raw));
        }
        return names;
    }

    /** 去空白、去重（SkillFilter.only 的 Set.of 拒绝重复名） */
    private static LinkedHashSet<String> normalize(List<String> names) {
        var out = new LinkedHashSet<String>();
        for (var name : names) {
            var trimmed = name == null ? "" : name.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
