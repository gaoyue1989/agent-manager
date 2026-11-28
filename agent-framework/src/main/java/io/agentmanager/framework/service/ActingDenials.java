package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
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
    public static final String SUPPRESSED_DIRECT_RETURN_IDS_KEY = "oaf.acting_denials.suppressed_direct_return_ids";

    private static final class BridgeSlot {
        final ConcurrentLinkedQueue<AgentEvent> queue = new ConcurrentLinkedQueue<>();
        final Set<String> suppressedIds = ConcurrentHashMap.newKeySet();
        volatile AgentState state;
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
        ctx.put(SUPPRESSED_DIRECT_RETURN_IDS_KEY, slot.suppressedIds);
        return next.apply(input)
            .concatMap(event -> {
                restoreSuppressedToolResults(slot.state, slot.suppressedIds);
                AgentEvent effective = restoreSuppressedEndEvent(event, slot.suppressedIds);
                if (effective instanceof AgentEndEvent) {
                    agentEndEmitted.set(true);
                    slot.active = false;
                }
                var drained = drain(slot.queue);
                if (drained.isEmpty()) {
                    return Flux.just(effective);
                }
                drained.add(effective);
                return Flux.fromIterable(drained);
            })
            .concatWith(Flux.defer(() -> {
                restoreSuppressedToolResults(slot.state, slot.suppressedIds);
                if (agentEndEmitted.get()) {
                    slot.queue.clear();
                    return Flux.empty();
                }
                var remaining = drain(slot.queue);
                return remaining.isEmpty() ? Flux.empty() : Flux.fromIterable(remaining);
            }))
            .doFinally(sig -> {
                restoreSuppressedToolResults(slot.state, slot.suppressedIds);
                slot.active = false;
                try {
                    var extra = ctx.getExtra();
                    if (extra != null) {
                        extra.remove(EVENT_BRIDGE_KEY, slot);
                        extra.remove(SUPPRESSED_DIRECT_RETURN_IDS_KEY, slot.suppressedIds);
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
        final BridgeSlot activeBridge = bridge;
        Set<String> suppressedIds = activeBridge != null ? activeBridge.suppressedIds : ConcurrentHashMap.newKeySet();
        if (activeBridge != null) {
            activeBridge.state = state;
        }
        Flux<AgentEvent> allowedFlux = Flux.defer(() -> {
            if (ctx != null) {
                ctx.put(SUPPRESS_DIRECT_RETURN_KEY, Boolean.TRUE);
                ctx.put(SUPPRESSED_DIRECT_RETURN_IDS_KEY, suppressedIds);
            }
            return next.apply(new ActingInput(allowed))
                .map(evt -> restoreSuppressedEndEvent(evt, suppressedIds))
                .doOnComplete(() -> restoreSuppressedToolResults(state, suppressedIds));
        }).doFinally(sig -> {
            restoreSuppressedToolResults(state, suppressedIds);
            if (ctx != null) {
                try {
                    var extra = ctx.getExtra();
                    if (extra != null) {
                        extra.remove(SUPPRESS_DIRECT_RETURN_KEY, Boolean.TRUE);
                        if (activeBridge == null) {
                            extra.remove(SUPPRESSED_DIRECT_RETURN_IDS_KEY, suppressedIds);
                        }
                    }
                } catch (UnsupportedOperationException ignored) {
                }
            }
        });
        return Flux.concat(Flux.fromIterable(syntheticEvents), allowedFlux);
    }

    public static ToolResultState effectiveResultState(RuntimeContext ctx, ToolResultEndEvent end) {
        if (end == null) {
            return null;
        }
        if (end.getState() == ToolResultState.ERROR
                && end.getToolCallId() != null
                && ctx != null
                && ctx.get(SUPPRESSED_DIRECT_RETURN_IDS_KEY) instanceof Set<?> rawSet
                && rawSet.contains(end.getToolCallId())) {
            return ToolResultState.SUCCESS;
        }
        return end.getState();
    }

    public static void restoreSuppressedToolResults(AgentState state, RuntimeContext ctx) {
        if (state == null || ctx == null) {
            return;
        }
        if (ctx.get(SUPPRESSED_DIRECT_RETURN_IDS_KEY) instanceof Set<?> rawSet && !rawSet.isEmpty()) {
            @SuppressWarnings("unchecked")
            Set<String> idSet = (Set<String>) rawSet;
            restoreSuppressedToolResults(state, idSet);
        }
    }

    public static List<Msg> restoreSuppressedMessages(List<Msg> messages, RuntimeContext ctx) {
        if (messages == null || messages.isEmpty() || ctx == null) {
            return messages;
        }
        if (!(ctx.get(SUPPRESSED_DIRECT_RETURN_IDS_KEY) instanceof Set<?> rawSet) || rawSet.isEmpty()) {
            return messages;
        }
        @SuppressWarnings("unchecked")
        Set<String> suppressedIds = (Set<String>) rawSet;
        boolean anyChanged = false;
        List<Msg> result = new ArrayList<>(messages.size());
        for (Msg msg : messages) {
            Msg restored = restoreSuppressedMsg(msg, suppressedIds);
            if (restored != msg) {
                anyChanged = true;
            }
            result.add(restored);
        }
        return anyChanged ? result : messages;
    }

    private static Msg restoreSuppressedMsg(Msg msg, Set<String> suppressedIds) {
        if (msg == null || msg.getContent() == null) {
            return msg;
        }
        boolean changed = false;
        List<ContentBlock> updated = new ArrayList<>(msg.getContent().size());
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ToolResultBlock tr
                    && tr.getState() == ToolResultState.ERROR
                    && tr.getId() != null
                    && suppressedIds.contains(tr.getId())) {
                updated.add(tr.withState(ToolResultState.SUCCESS));
                changed = true;
            } else {
                updated.add(block);
            }
        }
        return changed ? msg.withContent(updated) : msg;
    }

    private static AgentEvent restoreSuppressedEndEvent(AgentEvent evt, Set<String> suppressedIds) {
        if (evt instanceof ToolResultEndEvent end
                && end.getState() == ToolResultState.ERROR
                && end.getToolCallId() != null
                && suppressedIds.contains(end.getToolCallId())) {
            return new ToolResultEndEvent(
                end.getReplyId(),
                end.getToolCallId(),
                end.getToolCallName(),
                ToolResultState.SUCCESS);
        }
        return evt;
    }

    private static void restoreSuppressedToolResults(AgentState state, Set<String> suppressedIds) {
        if (state == null || suppressedIds.isEmpty()) {
            return;
        }
        List<Msg> msgs = state.contextMutable();
        for (int i = 0; i < msgs.size(); i++) {
            Msg msg = msgs.get(i);
            if (msg == null || msg.getContent() == null) {
                continue;
            }
            boolean changed = false;
            List<ContentBlock> updated = new ArrayList<>(msg.getContent().size());
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolResultBlock tr
                        && tr.getState() == ToolResultState.ERROR
                        && tr.getId() != null
                        && suppressedIds.contains(tr.getId())) {
                    updated.add(tr.withState(ToolResultState.SUCCESS));
                    changed = true;
                } else {
                    updated.add(block);
                }
            }
            if (changed) {
                msgs.set(i, msg.withContent(updated));
            }
        }
    }
}
