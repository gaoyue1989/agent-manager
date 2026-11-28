package io.agentmanager.framework.service;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.agentmanager.framework.config.OafConfigHolder;
import io.agentmanager.framework.config.TurnStopSettings;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.redis.RedisConnectionFacade;

@Component
public class RedisTurnStopNotifier implements TurnStopNotifier {

    private static final Logger log = LoggerFactory.getLogger(RedisTurnStopNotifier.class);
    static final String BASE_CHANNEL = "turn-stop:notify";

    private final RedisConnectionFacade facade;
    private final TurnStopWatcher watcher;
    private final TurnStopSettings settings;
    private final String rawChannel;

    @Autowired
    public RedisTurnStopNotifier(ObjectProvider<RedisConnectionFacade> facadeProvider,
                                 TurnStopWatcher watcher,
                                 TurnStopSettings settings,
                                 ObjectProvider<OafConfigHolder> oafConfigHolderProvider) {
        this(facadeProvider != null ? facadeProvider.getIfAvailable() : null,
            watcher, settings, resolveAgentNamespace(oafConfigHolderProvider));
    }

    RedisTurnStopNotifier(RedisConnectionFacade facade,
                          TurnStopWatcher watcher,
                          TurnStopSettings settings,
                          String agentNamespace) {
        this.facade = facade;
        this.watcher = watcher;
        this.settings = settings;
        this.rawChannel = buildRawChannel(agentNamespace);
    }

    @PostConstruct
    void start() {
        if (facade == null || watcher == null || (settings != null && !settings.enabled())) {
            return;
        }
        facade.subscribeAsync(rawChannel, this::onMessage);
        log.info("[turn-stop] subscribed Redis pub/sub channel={}", facade.key(rawChannel));
    }

    @Override
    public void publish(String sessionId, String replyId) {
        if (facade == null || sessionId == null || sessionId.isBlank()
                || replyId == null || replyId.isBlank()
                || (settings != null && !settings.enabled())) {
            return;
        }
        try {
            facade.publish(rawChannel, encodePayload(sessionId, replyId));
        } catch (Exception e) {
            log.debug("[turn-stop] redis publish failed (fallback to poll): sid={}, rid={}, err={}",
                sessionId, replyId, e.toString());
        }
    }

    @Override
    public void ensureSubscribed() {
        if (facade != null && (settings == null || settings.enabled()) && !facade.isPubSubOpen()) {
            facade.ensurePubSubSubscribedAsync();
        }
    }

    String rawChannel() {
        return rawChannel;
    }

    void onMessage(String channel, String payload) {
        if (watcher == null || payload == null || payload.isBlank()) {
            return;
        }
        int sep = payload.indexOf('\n');
        if (sep <= 0 || sep >= payload.length() - 1) {
            return;
        }
        String replyId = payload.substring(0, sep);
        String sessionId = payload.substring(sep + 1);
        watcher.notifyLocal(sessionId, replyId);
    }

    static String encodePayload(String sessionId, String replyId) {
        return replyId + "\n" + sessionId;
    }

    static String buildRawChannel(String agentNamespace) {
        if (agentNamespace == null || agentNamespace.isBlank()) {
            return BASE_CHANNEL;
        }
        String trimmed = agentNamespace.trim();
        String sanitized = trimmed.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (sanitized.isEmpty()) {
            sanitized = "ns";
        }
        return BASE_CHANNEL + ":" + sanitized + "-" + shortSha256(trimmed);
    }

    private static String shortSha256(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String resolveAgentNamespace(ObjectProvider<OafConfigHolder> holderProvider) {
        if (holderProvider == null) {
            return null;
        }
        var holder = holderProvider.getIfAvailable();
        if (holder == null) {
            return null;
        }
        OafConfig cfg = holder.get();
        if (cfg == null) {
            return null;
        }
        if (cfg.slug() != null && !cfg.slug().isBlank()) {
            return cfg.slug();
        }
        if (cfg.agentKey() != null && !cfg.agentKey().isBlank()) {
            return cfg.agentKey();
        }
        return cfg.name();
    }
}
