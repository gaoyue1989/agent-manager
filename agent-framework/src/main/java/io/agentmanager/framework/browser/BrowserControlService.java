package io.agentmanager.framework.browser;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.sandbox.opensandbox.SandboxHoldSignal;
import io.agentmanager.framework.sandbox.opensandbox.SandboxLocator;
import io.agentmanager.framework.service.SandboxRuntime;
import io.agentmanager.framework.service.TurnStopService;
import io.agentscope.harness.agent.IsolationScope;

@Service
@ConditionalOnProperty(prefix = "agent.browser", name = "enabled", havingValue = "true")
public class BrowserControlService implements SandboxHoldSignal {

    private static final Logger log = LoggerFactory.getLogger(BrowserControlService.class);

    public static final String TRIGGER_USER = "user";
    public static final String TRIGGER_AGENT = "agent";

    private final BrowserControlStore store;
    private final TurnStopService turnStopService;
    private final SandboxRuntime sandboxRuntime;
    private final SandboxLocator sandboxLocator;

    public BrowserControlService(BrowserControlStore store,
                                 @org.springframework.context.annotation.Lazy TurnStopService turnStopService,
                                 SandboxRuntime sandboxRuntime, SandboxLocator sandboxLocator) {
        this.store = store;
        this.turnStopService = turnStopService;
        this.sandboxRuntime = sandboxRuntime;
        this.sandboxLocator = sandboxLocator;
    }

    public record ControlView(String owner, String reason, String by, String trigger, String since,
                              long expiresInSeconds) {
    }

    public record TakeoverResult(ControlView control, String stopOutcome) {
    }

    public ControlView control(String sessionId) {
        var state = store.find(sessionId);
        if (state == null) {
            return new ControlView("AGENT", null, null, null, null, 0);
        }
        return new ControlView("HUMAN", state.reason(), state.by(), state.trigger(),
            Instant.ofEpochMilli(state.sinceEpochMs()).toString(), store.ttlSeconds(sessionId));
    }

    public boolean isHumanControlled(String sessionId) {
        try {
            return store.isHumanControlled(sessionId);
        } catch (BrowserControlUnavailableException e) {
            log.warn("[browser-control] control probe failed, gate fails open (sid={}): {}",
                sessionId, e.getMessage());
            return false;
        }
    }

    public TakeoverResult takeover(String sessionId, String userId, String reason, String trigger) {
        store.takeover(sessionId, reason, userId, trigger);
        String stop = null;
        if (TRIGGER_USER.equals(trigger)) {
            try {
                stop = turnStopService.request(sessionId, "browser_takeover").outcome().name();
            } catch (Exception e) {
                log.warn("[browser-control] turn stop request failed (sid={}): {}", sessionId, e.getMessage());
                stop = TurnStopService.Outcome.UNAVAILABLE.name();
            }
        }
        log.info("[browser-control] takeover: sid={}, by={}, trigger={}, stop={}",
            sessionId, userId, trigger, stop);
        return new TakeoverResult(control(sessionId), stop);
    }

    public boolean release(String sessionId) {
        boolean released = store.release(sessionId);
        log.info("[browser-control] release: sid={}, changed={}", sessionId, released);
        return released;
    }

    public Map<String, Object> state(String userId, String sessionId) {
        var control = control(sessionId);
        var controlView = new LinkedHashMap<String, Object>();
        controlView.put("owner", control.owner());
        controlView.put("reason", control.reason());
        controlView.put("by", control.by());
        controlView.put("trigger", control.trigger());
        controlView.put("since", control.since());
        controlView.put("expires_in_seconds", control.expiresInSeconds());

        var sandbox = new LinkedHashMap<String, Object>();
        if (sandboxRuntime.enabled()) {
            String sandboxId = sandboxLocator.sandboxId(userId, sessionId, IsolationScope.USER);
            sandbox.put("ready", sandboxId != null);
            sandbox.put("sandbox_id", sandboxId);
        } else {
            sandbox.put("ready", false);
            sandbox.put("reason", "sandbox_disabled");
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("control", controlView);
        out.put("sandbox", sandbox);
        out.put("vnc_path", "/threads/" + sessionId + "/browser/vnc");
        return out;
    }

    @Override
    public boolean isHeld(String sessionId) {
        return isHumanControlled(sessionId);
    }
}
