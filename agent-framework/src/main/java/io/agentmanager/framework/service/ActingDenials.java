package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

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
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

public final class ActingDenials {

    private static final String EVENT_BRIDGE_KEY = "oaf.acting_denials.event_bridge";

    private ActingDenials() {
    }

    public static Flux<AgentEvent> bridge(RuntimeContext ctx, AgentInput input,
                                          Function<AgentInput, Flux<AgentEvent>> next) {
        if (ctx == null || ctx.get(EVENT_BRIDGE_KEY) != null) {
            return next.apply(input);
        }
        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        ctx.put(EVENT_BRIDGE_KEY, sink);
        return Flux.merge(
            next.apply(input).doFinally(sig -> sink.tryEmitComplete()),
            sink.asFlux());
    }

    public static Set<ToolUseBlock> identitySet(List<ToolUseBlock> calls) {
        var set = java.util.Collections.newSetFromMap(new IdentityHashMap<ToolUseBlock, Boolean>());
        set.addAll(calls);
        return set;
    }

    @SuppressWarnings("unchecked")
    public static Flux<AgentEvent> deny(Agent agent, RuntimeContext ctx, ActingInput input,
                                        Set<ToolUseBlock> denied, Function<ToolUseBlock, String> reasonFor,
                                        Function<ActingInput, Flux<AgentEvent>> next) {
        var allCalls = input.toolCalls();
        var allowed = new ArrayList<ToolUseBlock>(allCalls.size());
        var blocked = new ArrayList<ToolUseBlock>();
        for (var call : allCalls) {
            if (denied.contains(call)) {
                blocked.add(call);
            } else {
                allowed.add(call);
            }
        }
        if (blocked.isEmpty()) {
            return next.apply(input);
        }
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        if (state == null) {
            return next.apply(input);
        }
        Sinks.Many<AgentEvent> bridge = ctx == null ? null : (Sinks.Many<AgentEvent>) ctx.get(EVENT_BRIDGE_KEY);
        var syntheticEvents = new ArrayList<AgentEvent>();
        for (var call : blocked) {
            String toolId = call.getId();
            String replyId = java.util.UUID.randomUUID().toString().replace("-", "");
            String reason = reasonFor.apply(call);
            var resStart = new ToolResultStartEvent(replyId, toolId, call.getName());
            var resDelta = new ToolResultTextDeltaEvent(replyId, toolId, call.getName(), reason);
            var resEnd = new ToolResultEndEvent(replyId, toolId, call.getName(), ToolResultState.DENIED);
            syntheticEvents.add(new ToolCallStartEvent(replyId, toolId, call.getName()));
            syntheticEvents.add(new ToolCallEndEvent(replyId, toolId, call.getName()));
            syntheticEvents.add(resStart);
            syntheticEvents.add(resDelta);
            syntheticEvents.add(resEnd);
            if (bridge != null) {
                bridge.tryEmitNext(resStart);
                bridge.tryEmitNext(resDelta);
                bridge.tryEmitNext(resEnd);
            }
            ToolResultBlock deniedResult = ToolResultBlock.builder()
                .id(toolId)
                .name(call.getName())
                .output(List.of(TextBlock.builder().text(reason).build()))
                .state(ToolResultState.DENIED)
                .build();
            state.contextMutable().add(
                ToolResultMessageBuilder.buildToolResultMsg(deniedResult, call, agent.getName()));
        }
        if (allowed.isEmpty()) {
            return Flux.fromIterable(syntheticEvents);
        }
        return Flux.concat(Flux.fromIterable(syntheticEvents),
            next.apply(new ActingInput(allowed)));
    }
}
