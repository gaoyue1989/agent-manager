package io.agentmanager.framework.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.service.SessionAccessGuard;
import io.agentmanager.framework.service.SessionKeyNamespace;
import io.agentmanager.framework.service.SessionContextStore;
import io.agentmanager.framework.service.SessionContextStore.RemoveOutcome;
import io.agentmanager.framework.service.SessionContextStore.UpsertOutcome;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.util.PathSafe;

@RestController
@RequestMapping("/threads")
public class SessionContextController {

    private static final Logger log = LoggerFactory.getLogger(SessionContextController.class);

    private final SessionContextStore sessionContextStore;
    private final SessionAccessGuard sessionAccessGuard;

    public SessionContextController(SessionContextStore sessionContextStore,
                                    SessionAccessGuard sessionAccessGuard) {
        this.sessionContextStore = sessionContextStore;
        this.sessionAccessGuard = sessionAccessGuard;
    }

    public record InjectRequest(String key, String title, String content) {
    }

    @PostMapping("/{sessionId}/context")
    public ResponseEntity<Map<String, Object>> inject(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestBody InjectRequest request) {
        var sid = PathSafe.sanitize(sessionId);
        var requester = requester(headerUserId);
        var outcome = sessionContextStore.upsert(sid, requester, request.key(), request.title(), request.content());
        if (outcome instanceof UpsertOutcome.EntryLimitExceeded limit) {
            var body = new LinkedHashMap<String, Object>();
            body.put("error", "context_entry_limit_exceeded");
            body.put("message", "Session context entry limit exceeded: " + limit.count()
                + " > " + limit.maxEntries() + " entries");
            body.put("count", limit.count());
            body.put("maxEntries", limit.maxEntries());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
        }
        if (outcome instanceof UpsertOutcome.BudgetExceeded exceeded) {
            var body = new LinkedHashMap<String, Object>();
            body.put("error", "context_budget_exceeded");
            body.put("message", "Session context budget exceeded: " + exceeded.totalChars()
                + " > " + exceeded.maxTotalChars() + " chars");
            body.put("contentChars", exceeded.contentChars());
            body.put("totalChars", exceeded.totalChars());
            body.put("maxTotalChars", exceeded.maxTotalChars());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
        }
        if (!(outcome instanceof UpsertOutcome.Accepted accepted)) {
            log.info("[session-context] reject foreign session: sid={}, requester={}", sid, requester);
            return threadNotFound(sid);
        }
        log.info("[session-context] injected: sid={}, key={}, chars={}, total={}, created={}",
            sid, accepted.key(), accepted.contentChars(), accepted.totalChars(), accepted.created());
        var body = new LinkedHashMap<String, Object>();
        body.put("sessionId", sid);
        body.put("key", accepted.key());
        body.put("title", accepted.title());
        body.put("contentChars", accepted.contentChars());
        body.put("totalChars", accepted.totalChars());
        body.put("maxTotalChars", accepted.maxTotalChars());
        body.put("created", accepted.created());
        body.put("effectiveFrom", "next_turn");
        return ResponseEntity.status(accepted.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    @GetMapping("/{sessionId}/context")
    public ResponseEntity<Map<String, Object>> list(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "includeContent", required = false, defaultValue = "false") boolean includeContent) {
        var sid = PathSafe.sanitize(sessionId);
        var requester = requester(headerUserId);
        if (SessionKeyNamespace.isReservedSessionId(sid)
                || (!sid.equals(requester) && sessionContextStore.existsAsUserId(sid))
                || !SessionAccessGuard.isOwner(sessionContextStore.ownerOf(sid), requester)) {
            return threadNotFound(sid);
        }
        var entries = new ArrayList<Map<String, Object>>();
        var totalChars = 0;
        for (var entry : sessionContextStore.findBySession(sid)) {
            var item = new LinkedHashMap<String, Object>();
            item.put("key", entry.key());
            item.put("title", entry.title());
            item.put("contentChars", entry.contentChars());
            item.put("createdAt", entry.createdAt().toString());
            item.put("updatedAt", entry.updatedAt().toString());
            if (includeContent) {
                item.put("content", entry.content());
            }
            entries.add(item);
            totalChars += entry.contentChars();
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("sessionId", sid);
        body.put("totalChars", totalChars);
        body.put("maxTotalChars", sessionContextStore.maxTotalChars());
        body.put("entries", entries);
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/{sessionId}/context/{key}")
    public ResponseEntity<Map<String, Object>> remove(
            @PathVariable String sessionId,
            @PathVariable String key,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
        var sid = PathSafe.sanitize(sessionId);
        var requester = requester(headerUserId);
        var outcome = sessionContextStore.remove(sid, requester, key);
        if (outcome instanceof RemoveOutcome.NotOwned) {
            log.info("[session-context] reject foreign session: sid={}, requester={}", sid, requester);
            return threadNotFound(sid);
        }
        if (outcome instanceof RemoveOutcome.NotFound) {
            var body = new LinkedHashMap<String, Object>();
            body.put("error", "context_not_found");
            body.put("message", "Context '" + key + "' not found in session '" + sid + "'");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
        }
        log.info("[session-context] removed: sid={}, key={}", sid, key);
        var body = new LinkedHashMap<String, Object>();
        body.put("sessionId", sid);
        body.put("key", key);
        body.put("deleted", true);
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(SessionContextStore.StorageException.class)
    public ResponseEntity<Map<String, Object>> storageUnavailable(SessionContextStore.StorageException e) {
        log.error("[session-context] storage unavailable: {}", e.getMessage());
        var body = new LinkedHashMap<String, Object>();
        body.put("error", "storage_unavailable");
        body.put("message", "Session context storage is unavailable, retry later");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private String requester(String headerUserId) {
        var sanitized = PathSafe.sanitize(sessionAccessGuard.resolveRequester(headerUserId));
        return SessionUserStore.PLACEHOLDER_USER.equals(sanitized)
            ? SessionAccessGuard.ANONYMOUS_USER : sanitized;
    }

    private static ResponseEntity<Map<String, Object>> threadNotFound(String sessionId) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "error", "thread_not_found",
            "message", "Session '" + sessionId + "' not found"));
    }
}
