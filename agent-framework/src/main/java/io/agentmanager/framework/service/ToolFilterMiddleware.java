package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Flux;

/**
 * 会话级工具过滤 middleware（session-tool-list-design §3/§4）：按<b>本轮请求</b>传入的
 * 可用工具列表（{@code tools}），把模型侧 MCP + 自定义工具可见集收敛为该列表——
 * <b>内置工具恒可见</b>（框架基础能力不受会话级接口影响，启停走包级 deniedTools）。
 *
 * <p><b>传递链路</b>：调用方在 {@code POST /threads/chat} 的 {@code tools} 字段（或 A2A
 * {@code message.metadata.tools}）声明列表；入口把它写入<b>本条用户消息的 metadata</b>
 * （{@link #METADATA_TOOLS_KEY}），本中间件从 {@link ReasoningInput#messages()} 的最后一条
 * USER 消息读取（与 {@link SkillFilterMiddleware} 同机制）。列表不落库、不进进程内状态，
 * 仅对本轮生效；下一轮未传即恢复全部工具。
 *
 * <p><b>双防线</b>：
 * <ul>
 *   <li>{@code onReasoning}（主防线）：白名单外 MCP/自定义 schema 不进发给 LLM 的请求
 *       ——省 token、模型不会尝试调用；ReAct 每轮 reasoning 都过本中间件，多轮一致性天然成立；</li>
 *   <li>{@code onActing}（次防线）：模型对白名单外工具的幻觉调用不执行，合成 DENIED
 *       工具结果回模型（{@code ProtocolDenyRulesMiddleware} 同构，SSE 流与模型上下文
 *       双通道完整）。内置工具调用永不触发该防线。</li>
 * </ul>
 *
 * <p><b>语义</b>（§2.1）：metadata 无该键（未传）→ 全部工具（存量行为，零开销直通）；
 * 显式空数组 → 仅内置（MCP/自定义全部剔除）；非空数组 → 内置 + 名单内（未知名忽略，
 * 解析经 {@link SessionToolScopeService#resolve}：裸名精确 / {@code mcp__{server}__{tool}}
 * 全名别名 / 内置名 WARN 不生效）。
 *
 * <p><b>HITL 边界</b>：onActing 的白名单来自 onReasoning 在 RuntimeContext 登记的 turn 级
 * 属性。确认恢复轮（confirm/AG-UI resume）重新进入 agent 回路时 onReasoning 会重跑、
 * 读到同一条带名单的 USER 消息，白名单自然延续；若某恢复路径未经过 onReasoning
 * （属性缺失），onActing fail-open 放行——恢复的调用本就是原轮白名单内、经权限确认的
 * 调用，放行语义正确。
 *
 * <p>fail-soft：metadata 畸形等任何异常只 WARN、按未传处理（全部工具），不阻断对话。
 */
public class ToolFilterMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolFilterMiddleware.class);

    /**
     * 本轮可用工具列表的 metadata 键（用户消息 metadata）。
     * chat 入口写入 {@code List<String>}；A2A 调用方经 {@code message.metadata.tools} 透传。
     */
    public static final String METADATA_TOOLS_KEY = "tools";

    /** onReasoning 解析、onActing 消费的 turn 级白名单（RuntimeContext typed attribute；包私有供同包单测断言登记） */
    record TurnToolWhitelist(SessionToolScopeService.Resolution resolution) {
    }

    private final SessionToolScopeService scopeService;

    public ToolFilterMiddleware(SessionToolScopeService scopeService) {
        this.scopeService = scopeService;
    }

    // ===== 主防线：收敛模型侧工具 schema =====

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (ctx == null || input == null) {
            return next.apply(input);
        }
        try {
            // 每轮 reasoning 重读 metadata（幂等）：登记 turn 级白名单供 onActing 消费；
            // 未传不登记（RuntimeContext 按 turn 新建，无跨轮残留，直通即存量行为）
            var requested = turnToolNames(input.messages());
            if (requested != null) {
                ctx.put(TurnToolWhitelist.class,
                    new TurnToolWhitelist(scopeService.resolve(requested)));
            }
        } catch (Exception e) {
            log.warn("[tool-filter] resolve per-turn tools failed, fallback to all tools: {}",
                e.getMessage());
        }
        var whitelist = ctx.get(TurnToolWhitelist.class);
        var schemas = input.tools();
        if (whitelist == null || schemas == null || schemas.isEmpty()) {
            return next.apply(input);   // 未传 = 全部；无 schema 无可过滤
        }
        var keepNames = whitelist.resolution().keepNames();
        var mcp = scopeService.mcpToolNames();
        var custom = scopeService.customToolNames();
        List<ToolSchema> kept = new ArrayList<>(schemas.size());
        for (var schema : schemas) {
            var name = schema.getName();
            // 业务工具（MCP/自定义）按白名单收敛；其余（内置）恒保留
            boolean business = mcp.contains(name) || custom.contains(name);
            if (!business || keepNames.contains(name)) {
                kept.add(schema);
            }
        }
        if (kept.size() == schemas.size()) {
            return next.apply(input);   // 未过滤任何工具：原引用直传（零拷贝）
        }
        if (log.isDebugEnabled()) {
            log.debug("[tool-filter] session={} tools {} -> {} schemas",
                ctx.getSessionId(), keepNames, kept.size());
        }
        return next.apply(new ReasoningInput(input.messages(), List.copyOf(kept), input.options()));
    }

    // ===== 次防线：兜底拦截白名单外业务工具调用 =====

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        var whitelist = ctx != null ? ctx.get(TurnToolWhitelist.class) : null;
        var allCalls = input == null ? null : input.toolCalls();
        if (whitelist == null || allCalls == null || allCalls.isEmpty()) {
            return next.apply(input);   // 非白名单轮 fail-open（含 HITL 恢复轮，见类注）
        }
        var keepNames = whitelist.resolution().keepNames();
        var mcp = scopeService.mcpToolNames();
        var custom = scopeService.customToolNames();
        var allowed = new ArrayList<ToolUseBlock>(allCalls.size());
        var denied = new ArrayList<ToolUseBlock>();
        for (var tub : allCalls) {
            var name = tub.getName();
            // 只拦业务工具（MCP/自定义）；内置调用永不触发防线。
            // null 名（畸形 tool call）归 allowed——SetN.contains(null) 会抛 NPE，
            // fail-soft 语义下不能让防线本身击穿对话（ProtocolDenyRules 同款判空）
            if (name != null && (mcp.contains(name) || custom.contains(name))
                    && !keepNames.contains(name)) {
                denied.add(tub);
            } else {
                allowed.add(tub);
            }
        }
        if (denied.isEmpty()) {
            return next.apply(input);
        }
        log.info("[tool-filter] denying tool calls {} not in this turn's tools whitelist (sid={})",
            denied.stream().map(ToolUseBlock::getName).toList(),
            ctx.getSessionId());

        var syntheticEvents = new ArrayList<AgentEvent>();
        AgentState state = agent.getAgentState();
        for (var tub : denied) {
            String toolId = tub.getId();
            // replyId 约定与 ProtocolDenyRulesMiddleware 一致：合成事件用随机 id（原调用未执行）
            String replyId = java.util.UUID.randomUUID().toString().replace("-", "");
            String reason = "Error: tool '" + tub.getName()
                + "' is not in this turn's allowed tools list; the call was not executed.";
            // 合成前端事件（保持 SSE 流连续）
            syntheticEvents.add(new ToolCallStartEvent(replyId, toolId, tub.getName()));
            syntheticEvents.add(new ToolCallEndEvent(replyId, toolId, tub.getName()));
            syntheticEvents.add(new ToolResultStartEvent(replyId, toolId, tub.getName()));
            syntheticEvents.add(new ToolResultTextDeltaEvent(replyId, toolId, tub.getName(), reason));
            syntheticEvents.add(new ToolResultEndEvent(replyId, toolId, tub.getName(),
                ToolResultState.DENIED));
            // 注入 DENIED ToolResultBlock 到 agent 上下文（模型可感知并调整后续动作）
            ToolResultBlock deniedResult = ToolResultBlock.builder()
                .id(toolId)
                .name(tub.getName())
                .output(List.of(TextBlock.builder().text(reason).build()))
                .state(ToolResultState.DENIED)
                .build();
            state.contextMutable().add(
                ToolResultMessageBuilder.buildToolResultMsg(deniedResult, tub, agent.getName()));
        }

        if (allowed.isEmpty()) {
            return Flux.fromIterable(syntheticEvents);
        }
        return Flux.concat(Flux.fromIterable(syntheticEvents),
            next.apply(new ActingInput(allowed)));
    }

    /**
     * 本轮（最后一条 USER 消息）的 tools 声明；该消息未携带键 → null（= 未传）。
     * 只认最后一条 USER 消息、不回溯历史——历史消息的同名键属于旧轮次（仅本轮生效）；
     * HITL 恢复轮重跑 onReasoning 时读到同一条带名单的消息（白名单自然延续）。
     */
    static List<String> turnToolNames(List<io.agentscope.core.message.Msg> msgs) {
        if (msgs == null) {
            return null;
        }
        for (int i = msgs.size() - 1; i >= 0; i--) {
            var msg = msgs.get(i);
            if (msg == null || msg.getRole() != io.agentscope.core.message.MsgRole.USER) {
                continue;
            }
            var metadata = msg.getMetadata();
            return metadata != null && metadata.containsKey(METADATA_TOOLS_KEY)
                ? toToolNames(metadata.get(METADATA_TOOLS_KEY))
                : null;
        }
        return null;
    }

    /** metadata 值 → 工具名列表：支持 List（chat 入口）/ 逗号分隔字符串（A2A 旧客户端） */
    private static List<String> toToolNames(Object raw) {
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
}
