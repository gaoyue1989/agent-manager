package io.agentmanager.framework.browser;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.agentmanager.framework.controller.ChatStreamController;
import io.agentmanager.framework.service.SessionAccessGuard;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.util.PathSafe;
import reactor.core.publisher.Flux;
import org.springframework.http.codec.ServerSentEvent;

@RestController
@ConditionalOnProperty(prefix = "agent.browser", name = "enabled", havingValue = "true")
public class BrowserControlController {

    private static final Logger log = LoggerFactory.getLogger(BrowserControlController.class);

    static final String RESUME_MESSAGE = "【控制权交还】人工操作已完成，浏览器控制权已交还给你。"
        + "页面可能已经跳转、打开新标签页或写入了新的 Cookie。请先重新采样浏览器现场"
        + "（chrome-devtools list_pages / take_snapshot），确认当前状态后再继续未完成的任务；"
        + "不要重复人工已经完成的步骤。";

    private final BrowserControlService control;
    private final SessionUserStore sessionUserStore;
    private final ChatStreamController chatStreamController;
    private final BrowserViewTicketStore ticketStore;

    public BrowserControlController(BrowserControlService control, SessionUserStore sessionUserStore,
                                    ChatStreamController chatStreamController,
                                    BrowserViewTicketStore ticketStore) {
        this.control = control;
        this.sessionUserStore = sessionUserStore;
        this.chatStreamController = chatStreamController;
        this.ticketStore = ticketStore;
    }

    public record TakeoverRequest(String reason) {
    }

    @GetMapping("/threads/{sessionId}/browser")
    public ResponseEntity<Map<String, Object>> state(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        String sid = PathSafe.sanitize(sessionId);
        String owner = assertOwned(sid, headerUserId);
        try {
            return ResponseEntity.ok(control.state(owner, sid));
        } catch (BrowserControlUnavailableException e) {
            return unavailable("control_store_unavailable", e);
        }
    }

    @PostMapping("/threads/{sessionId}/browser/takeover")
    public ResponseEntity<Map<String, Object>> takeover(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestBody(required = false) TakeoverRequest body) {
        String sid = PathSafe.sanitize(sessionId);
        String owner = assertOwned(sid, headerUserId);
        try {
            var result = control.takeover(sid, owner,
                body != null ? body.reason() : null, BrowserControlService.TRIGGER_USER);
            var out = new LinkedHashMap<String, Object>();
            out.put("control", result.control());
            out.put("stop_outcome", result.stopOutcome());
            return ResponseEntity.ok(out);
        } catch (BrowserControlUnavailableException e) {
            return unavailable("control_store_unavailable", e);
        }
    }

    @PostMapping(value = "/threads/{sessionId}/browser/release",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> release(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Forwarded-Prefix", required = false) String forwardedPrefixHeader,
            @RequestHeader(required = false) HttpHeaders requestHeaders) {
        String sid = PathSafe.sanitize(sessionId);
        String owner = assertOwned(sid, headerUserId);
        try {
            if (!control.release(sid)) {
                return Flux.just(errorFrame("not_taken_over: browser control is not held by the human"));
            }
        } catch (BrowserControlUnavailableException e) {
            return Flux.just(errorFrame("control_store_unavailable: " + e.getMessage()));
        }
        var request = new ChatStreamController.ChatRequest(RESUME_MESSAGE, owner,
            sid, null, null, null, null);
        log.info("[browser-control] release accepted, resuming agent turn (sid={})", sid);
        return chatStreamController.chat(request, owner, forwardedPrefixHeader, requestHeaders);
    }

    @PostMapping("/threads/{sessionId}/browser/vnc-ticket")
    public ResponseEntity<Map<String, Object>> vncTicket(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        String sid = PathSafe.sanitize(sessionId);
        String owner = assertOwned(sid, headerUserId);
        try {
            var out = new LinkedHashMap<String, Object>();
            out.put("ticket", ticketStore.issue(sid, owner));
            out.put("expires_in_seconds", ticketStore.ttlSeconds());
            return ResponseEntity.ok(out);
        } catch (BrowserControlUnavailableException e) {
            return unavailable("view_ticket_unavailable", e);
        }
    }

    private String assertOwned(String sessionId, String headerUserId) {
        var owner = SessionAccessGuard.establishedOwner(sessionUserStore, sessionId, headerUserId);
        if (owner == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Session '" + sessionId + "' not found");
        }
        return owner;
    }

    private static ResponseEntity<Map<String, Object>> unavailable(String error,
                                                                  BrowserControlUnavailableException e) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", error);
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private static ServerSentEvent<String> errorFrame(String message) {
        return ServerSentEvent.<String>builder()
            .data("{\"type\":\"error\",\"error\":\""
                + io.agentmanager.framework.controller.AgentEventSseSerializer.jsonEsc(message) + "\"}")
            .build();
    }
}
