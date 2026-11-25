package io.agentmanager.framework.browser;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.config.BrowserProperties;
import io.agentmanager.framework.redis.RedisConnectionFacade;
import io.lettuce.core.SetArgs;

@Component
@ConditionalOnProperty(prefix = "agent.browser", name = "enabled", havingValue = "true")
public class BrowserViewTicketStore {

    private static final Logger log = LoggerFactory.getLogger(BrowserViewTicketStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String KEY_PREFIX = "brw:vnc:";

    private final RedisConnectionFacade facade;
    private final BrowserProperties props;

    public BrowserViewTicketStore(RedisConnectionFacade facade, BrowserProperties props) {
        this.facade = facade;
        this.props = props;
    }

    public record Claim(String sessionId, String userId) {
    }

    public String issue(String sessionId, String userId) {
        var ticket = UUID.randomUUID().toString();
        var payload = MAPPER.createObjectNode();
        payload.put("sid", sessionId);
        payload.put("uid", userId);
        try {
            facade.sync().set(key(ticket), MAPPER.writeValueAsString(payload),
                SetArgs.Builder.ex(props.vncTicketTtlSeconds()));
            return ticket;
        } catch (Exception e) {
            log.warn("[browser-view] redis ticket issue failed (sid={}): {}", sessionId, e.getMessage());
            throw new BrowserControlUnavailableException("browser view ticket store unavailable", e);
        }
    }

    public Claim consume(String ticket) {
        String raw;
        try {
            raw = facade.sync().getdel(key(ticket));
        } catch (Exception e) {
            log.warn("[browser-view] redis ticket consume failed: {}", e.getMessage());
            return null;
        }
        if (raw == null) {
            return null;
        }
        try {
            var node = MAPPER.readTree(raw);
            var sessionId = node.path("sid").asText("");
            var userId = node.path("uid").asText("");
            if (sessionId.isBlank() || userId.isBlank()) {
                return null;
            }
            return new Claim(sessionId, userId);
        } catch (Exception e) {
            log.warn("[browser-view] malformed view ticket: {}", e.getMessage());
            return null;
        }
    }

    String key(String ticket) {
        return facade.key(KEY_PREFIX + ticket);
    }

    public long ttlSeconds() {
        return props.vncTicketTtlSeconds();
    }
}
