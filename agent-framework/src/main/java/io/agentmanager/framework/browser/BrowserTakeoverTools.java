package io.agentmanager.framework.browser;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.agentmanager.framework.service.SessionKeyResolver;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.tool.CustomTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

@Component
@ConditionalOnProperty(prefix = "agent.browser", name = "enabled", havingValue = "true")
public class BrowserTakeoverTools implements CustomTool {

    private final BrowserControlService control;
    private final SessionKeyResolver sessionKeyResolver;

    public BrowserTakeoverTools(BrowserControlService control, SessionUserStore sessionUserStore) {
        this(control, sessionUserStore, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BrowserTakeoverTools(BrowserControlService control, SessionUserStore sessionUserStore,
                                io.agentmanager.framework.model.OafConfig oafConfig) {
        this.control = control;
        this.sessionKeyResolver = new SessionKeyResolver(
            sessionUserStore, oafConfig != null ? oafConfig.slug() : null);
    }

    @Tool(
        name = "request_browser_takeover",
        description = "Hand the browser over to the human operator. Call this when a human must do "
            + "something in the browser that you cannot: log in with a password you do not have, enter "
            + "an SMS/2FA code, solve a captcha, or approve a high-risk action. After calling this tool, "
            + "end your turn with a short message telling the user what to do; do not call any other tool "
            + "until you receive a 【控制权交还】 message.",
        concurrencySafe = false)
    public String requestTakeover(
            RuntimeContext ctx,
            @ToolParam(name = "reason", description = "Why the human operator is needed, in one or two sentences")
                    String reason) {
        var sessionId = sessionKeyResolver.canonicalSessionId(ctx);
        if (sessionId == null || sessionId.isBlank()) {
            return "Error: cannot resolve the current session; takeover was not requested.";
        }
        var userId = sessionKeyResolver.realUserId(ctx);
        try {
            control.takeover(sessionId, userId, reason, BrowserControlService.TRIGGER_AGENT);
        } catch (BrowserControlUnavailableException e) {
            return "Error: browser control store unavailable; takeover was not requested: " + e.getMessage();
        }
        String why = (reason == null || reason.isBlank()) ? "" : " (reason: " + reason + ")";
        return "Browser control has been handed to the human operator" + why + ". "
            + "End your turn now with a short message telling the user what to do. "
            + "Do not call any other tool; sandbox command execution is rejected until the human "
            + "returns control, and the next user message will start with 【控制权交还】.";
    }
}
