package io.agentmanager.framework.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 会话管理器：维护 userId + sessionId 与 threadId 的映射，支持会话续接与 TTL 过期。
 */
@Service
public class SessionManager {
    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /** 默认会话 TTL：30 天 */
    private static final Duration DEFAULT_TTL = Duration.ofDays(30);

    /** sessionKey -> SessionState */
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public record SessionState(
        String sessionKey,
        String userId,
        String threadId,
        Instant createdAt,
        Instant expiresAt
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * 获取或创建会话。
     *
     * @param userId    用户 ID（可为 null，回退 anonymous）
     * @param sessionId 会话标识（来自 A2A sessionId/conversationId/thread_id）
     * @return 会话状态（已存在且未过期则复用，否则新建）
     */
    public SessionState getOrCreateSession(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            // 无 sessionId 时创建一次性会话
            return createSession(userId, UUID.randomUUID().toString());
        }

        String sessionKey = buildSessionKey(userId, sessionId);
        SessionState existing = sessions.get(sessionKey);
        if (existing != null && !existing.isExpired()) {
            log.debug("Resuming session: {}", sessionKey);
            return existing;
        }

        return createSession(userId, sessionId);
    }

    /**
     * 根据 userId + sessionId 查找现有会话的 threadId。
     *
     * @return 未过期会话的 threadId，不存在或已过期返回 null
     */
    public String resolveThreadId(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String sessionKey = buildSessionKey(userId, sessionId);
        SessionState state = sessions.get(sessionKey);
        if (state != null && !state.isExpired()) {
            return state.threadId();
        }
        return null;
    }

    private SessionState createSession(String userId, String sessionId) {
        String uid = userId != null && !userId.isBlank() ? userId : "anonymous";
        String sessionKey = uid + ":" + sessionId;

        SessionState newSession = new SessionState(
            sessionKey, userId, sessionId,
            Instant.now(), Instant.now().plus(DEFAULT_TTL)
        );

        sessions.put(sessionKey, newSession);
        log.info("Created session: {} (TTL {} days)", sessionKey, DEFAULT_TTL.toDays());
        return newSession;
    }

    private String buildSessionKey(String userId, String sessionId) {
        String uid = userId != null && !userId.isBlank() ? userId : "anonymous";
        return uid + ":" + sessionId;
    }

    /**
     * 清理过期会话。
     *
     * @return 清理数量
     */
    public int cleanupExpired() {
        Instant now = Instant.now();
        int count = 0;

        var it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired()) {
                it.remove();
                count++;
            }
        }

        if (count > 0) {
            log.info("Cleaned {} expired sessions", count);
        }
        return count;
    }

    public int getActiveCount() {
        return (int) sessions.values().stream()
            .filter(s -> !s.isExpired())
            .count();
    }
}
