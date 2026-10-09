package io.agentmanager.framework.service.protocol;

import java.util.ArrayList;
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
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Flux;

/**
 * 远程子 agent 动态 DENY 中间件（travel-fulfillment 设计 F16/§6.1）：协议任务携带的
 * {@code context.deny_rules}（经 {@link ProtocolDenyRulesContextCustomizer} 挂
 * RuntimeContext）在 acting 阶段按工具名拦截——命中调用不执行，直接合成 DENIED
 * 工具结果回给模型，其余调用照常放行。
 *
 * <p>挂载于常驻中间件链（{@code HarnessAgentFactory}，ToolCallValidationMiddleware 之后）：
 * 非协议轮次 RuntimeContext 无该属性即零开销直通；协议任务的 DENY 作用域天然按任务
 * 隔离（规则随任务 RuntimeContext 走，不触碰共享 agent 的静态权限上下文——那是
 * 构建期烘焙、无法按任务变更的）。未配置 deny_rules 的远程提交行为不变（未覆盖工具
 * fail-closed 走 ask）。
 *
 * <p>合成事件/结果注入机制与 {@code ToolCallValidationMiddleware} 同构（ToolCallStart/
 * End + ToolResultStart/TextDelta/End 三段 + ToolResultMsg 入上下文），保持 SSE 流
 * 与模型上下文双通道完整。
 */
public class ProtocolDenyRulesMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ProtocolDenyRulesMiddleware.class);

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> allCalls = input.toolCalls();
        if (allCalls == null || allCalls.isEmpty() || ctx == null) {
            return next.apply(input);
        }
        Set<String> deniedNames = ProtocolDenyRules.parseToolNames(
            ctx.get(ProtocolDenyRules.RUNTIME_ATTRIBUTE_KEY));
        if (deniedNames.isEmpty()) {
            return next.apply(input);
        }

        var allowed = new ArrayList<ToolUseBlock>(allCalls.size());
        var denied = new ArrayList<ToolUseBlock>();
        for (var tub : allCalls) {
            if (tub.getName() != null && deniedNames.contains(tub.getName())) {
                denied.add(tub);
            } else {
                allowed.add(tub);
            }
        }
        if (denied.isEmpty()) {
            return next.apply(input);
        }
        log.info("[protocol-deny-rules] denying tool calls {} by parent deny_rules (task={})",
            denied.stream().map(ToolUseBlock::getName).toList(), ctx.getSessionId());

        var syntheticEvents = new ArrayList<AgentEvent>();
        AgentState state = agent.getAgentState();
        for (var tub : denied) {
            String toolId = tub.getId();
            // replyId 约定与 ToolCallValidationMiddleware 一致：合成事件用随机 id（原调用未执行）
            String replyId = java.util.UUID.randomUUID().toString().replace("-", "");
            String reason = "Error: tool '" + tub.getName()
                + "' is denied by parent deny_rules (remote subagent policy); the call was not executed.";
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
}
