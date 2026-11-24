package io.agentmanager.framework.browser;

import java.util.ArrayList;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.service.ActingDenials;
import io.agentmanager.framework.service.SessionKeyResolver;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;

public class BrowserControlMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(BrowserControlMiddleware.class);

    public static final String EXECUTE_TOOL = "execute";

    static final String DENY_REASON = "Error: the sandbox is under human takeover; all sandbox command "
        + "execution by the agent is paused until control is returned. The command was not executed. "
        + "Do not retry; end your turn and wait for the 【控制权交还】 message.";

    private final BrowserControlService control;
    private final SessionKeyResolver sessionKeyResolver;

    public BrowserControlMiddleware(BrowserControlService control, SessionKeyResolver sessionKeyResolver) {
        this.control = control;
        this.sessionKeyResolver = sessionKeyResolver;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        var calls = input == null ? null : input.toolCalls();
        if (ctx == null || calls == null || calls.isEmpty()) {
            return next.apply(input);
        }
        var sessionId = sessionKeyResolver.canonicalSessionId(ctx);
        if (sessionId == null || sessionId.isBlank() || !control.isHumanControlled(sessionId)) {
            return next.apply(input);
        }
        var denied = new ArrayList<ToolUseBlock>();
        for (var call : calls) {
            if (EXECUTE_TOOL.equals(call.getName())) {
                denied.add(call);
            }
        }
        if (denied.isEmpty()) {
            return next.apply(input);
        }
        log.info("[browser-control] denying {} sandbox command call(s) during human takeover (sid={})",
            denied.size(), sessionId);
        return ActingDenials.deny(agent, ctx, input, ActingDenials.identitySet(denied),
            call -> DENY_REASON, next);
    }
}
