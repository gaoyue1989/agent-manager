package io.agentmanager.framework.browser;

import java.util.LinkedHashMap;

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
public class BrowserControlStore {

    private static final Logger log = LoggerFactory.getLogger(BrowserControlStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String KEY_PREFIX = "brw:ctrl:";

    private final RedisConnectionFacade facade;
    private final BrowserProperties props;

    public BrowserControlStore(RedisConnectionFacade facade, BrowserProperties props) {
        this.facade = facade;
        this.props = props;
    }

    public record ControlState(String reason, String by, String trigger, long sinceEpochMs) {
    }

    public String key(String sessionId) {
        return facade.key(KEY_PREFIX + sessionId);
    }

    public void takeover(String sessionId, String reason, String by, String trigger) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("reason", reason == null ? "" : reason);
        payload.put("by", by == null ? "" : by);
        payload.put("trigger", trigger == null ? "" : trigger);
        payload.put("since", System.currentTimeMillis());
        try {
            facade.sync().set(key(sessionId), MAPPER.writeValueAsString(payload),
                SetArgs.Builder.ex(props.takeoverTtlSeconds()));
        } catch (Exception e) {
            throw unavailable("takeover", sessionId, e);
        }
    }

    public boolean release(String sessionId) {
        try {
            Long removed = facade.sync().del(key(sessionId));
            return removed != null && removed > 0;
        } catch (Exception e) {
            throw unavailable("release", sessionId, e);
        }
    }

    public boolean isHumanControlled(String sessionId) {
        try {
            Long exists = facade.sync().exists(key(sessionId));
            return exists != null && exists > 0;
        } catch (Exception e) {
            throw unavailable("probe", sessionId, e);
        }
    }

    public ControlState find(String sessionId) {
        try {
            var raw = facade.sync().get(key(sessionId));
            if (raw == null) {
                return null;
            }
            var node = MAPPER.readTree(raw);
            return new ControlState(node.path("reason").asText(""), node.path("by").asText(""),
                node.path("trigger").asText(""), node.path("since").asLong(0));
        } catch (Exception e) {
            throw unavailable("read", sessionId, e);
        }
    }

    public long ttlSeconds(String sessionId) {
        try {
            Long ttl = facade.sync().ttl(key(sessionId));
            return ttl == null ? -1 : ttl;
        } catch (Exception e) {
            throw unavailable("ttl", sessionId, e);
        }
    }

    private static BrowserControlUnavailableException unavailable(String op, String sessionId, Exception cause) {
        log.warn("[browser-control] redis {} failed (sid={}): {}", op, sessionId, cause.getMessage());
        return new BrowserControlUnavailableException("browser control store unavailable", cause);
    }
}
