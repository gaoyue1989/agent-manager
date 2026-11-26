package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
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

public final class ActingDenials {

    private static final String EVENT_BRIDGE_KEY = "oaf.acting_denials.event_bridge";
    public static final String SUPPRESS_DIRECT_RETURN_KEY = "oaf.acting_denials.suppress_direct_return";

    private static final class BridgeSlot {
        final ConcurrentLinkedQueue<AgentEvent> queue = new ConcurrentLinkedQueue<>();
        volatile boolean active = true;
    }

    private ActingDenials() {
    }

    public static Flux<AgentEvent> bridge(RuntimeContext ctx, AgentInput input,
                                          Function<AgentInput, Flux<AgentEvent>> next) {
        if (ctx == null) {
            return next.apply(input);
        }
        Object existing = ctx.get(EVENT_BRIDGE_KEY);
        if (existing instanceof BridgeSlot slot && slot.active) {
            return next.apply(input);
        }
        var slot = new BridgeSlot();
        var agentEndEmitted = new AtomicBoolean(false);
        ctx.put(EVENT_BRIDGE_KEY, slot);
        return next.apply(input)
            .concatMap(event -> {
                if (event instanceof AgentEndEvent) {
                    agentEndEmitted.set(true);
                    slot.active = false;
                }
                var drained = drain(slot.queue);
                if (drained.isEmpty()) {
                    return Flux.just(event);
                }
                drained.add(event);
                return Flux.fromIterable(drained);
            })
            .concatWith(Flux.defer(() -> {
                if (agentEndEmitted.get()) {
                    slot.queue.clear();
                    return Flux.empty();
                }
                var remaining = drain(slot.queue);
                return remaining.isEmpty() ? Flux.empty() : Flux.fromIterable(remaining);
            }))
            .doFinally(sig -> {
                slot.active = false;
                try {
                    var extra = ctx.getExtra();
                    if (extra != null) {
                        extra.remove(EVENT_BRIDGE_KEY, slot);
                    }
                } catch (UnsupportedOperationException ignored) {
                }
            });
    }

    private static List<AgentEvent> drain(ConcurrentLinkedQueue<AgentEvent> queue) {
        var out = new ArrayList<AgentEvent>();
        AgentEvent ev;
        while ((ev = queue.poll()) != null) {
            out.add(ev);
        }
        return out;
    }

    public static Set<ToolUseBlock> identitySet(List<ToolUseBlock> calls) {
        var set = java.util.Collections.newSetFromMap(new IdentityHashMap<ToolUseBlock, Boolean>());
        set.addAll(calls);
        return set;
    }

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
        BridgeSlot bridge = null;
        if (ctx != null && ctx.get(EVENT_BRIDGE_KEY) instanceof BridgeSlot slot && slot.active) {
            bridge = slot;
        }
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
                bridge.queue.offer(resStart);
                bridge.queue.offer(resDelta);
                bridge.queue.offer(resEnd);
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
        Flux<AgentEvent> allowedFlux = Flux.defer(() -> {
            if (ctx != null) {
                ctx.put(SUPPRESS_DIRECT_RETURN_KEY, Boolean.TRUE);
            }
            return next.apply(new ActingInput(allowed));
        }).doFinally(sig -> {
            if (ctx != null) {
                try {
                    var extra = ctx.getExtra();
                    if (extra != null) {
                        extra.remove(SUPPRESS_DIRECT_RETURN_KEY, Boolean.TRUE);
                    }
                } catch (UnsupportedOperationException ignored) {
                }
            }
        });
        return Flux.concat(Flux.fromIterable(syntheticEvents), allowedFlux);
    }
}
