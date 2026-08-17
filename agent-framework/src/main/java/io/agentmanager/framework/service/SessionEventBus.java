package io.agentmanager.framework.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.agentscope.core.event.AgentEvent;
import reactor.core.publisher.Sinks;

/**
 * 会话级事件总线：按 sessionId 分桶的广播通道，供长连接 SSE 订阅（debug 页面）。
 *
 * <p>设计：应用层自建（SDK 的 ChatUiChannel 为请求-响应式，无会话级广播）。
 * 触发端点（POST /debug/threads/{sid}/chat）调用 sendStream 后将每个 AgentEvent
 * doOnNext 扇出到本总线；订阅端点（GET /debug/threads/{sid}/events）从总线拉取。</p>
 *
 * <p>生命周期：惰性创建，无订阅者后清理（释放内存）。多播 = 多页面订阅同会话共享事件。</p>
 */
public class SessionEventBus {

    /** 无订阅者后等待的空闲清理阈值（毫秒） */
    private static final long IDLE_EXPIRE_MS = 5 * 60 * 1000;

    private final Map<String, BusEntry> sessions = new ConcurrentHashMap<>();

    /** 获取（或创建）某个会话的事件总线出口 */
    public Sinks.Many<AgentEvent> sink(String sessionId) {
        return sessions.compute(sessionId, (k, entry) -> {
            // 不存在，或已有条目空闲超时且无订阅者 → 重建
            boolean expired = entry != null
                && entry.sink.currentSubscriberCount() == 0
                && System.currentTimeMillis() - entry.createdAt > IDLE_EXPIRE_MS;
            if (entry == null || expired) {
                Sinks.Many<AgentEvent> sink = Sinks.many().multicast().directBestEffort();
                return new BusEntry(sink, System.currentTimeMillis());
            }
            return entry;
        }).sink;
    }

    /** 事件扇出（供触发端点调用） */
    public void emit(String sessionId, AgentEvent event) {
        var entry = sessions.get(sessionId);
        if (entry != null) {
            entry.sink.tryEmitNext(event);
        }
    }

    /** 移除某订阅者（订阅断开时调用），无订阅者则整体清理 */
    public void onUnsubscribe(String sessionId) {
        sessions.computeIfPresent(sessionId, (k, entry) -> {
            if (entry.sink.currentSubscriberCount() == 0) {
                entry.sink.tryEmitComplete();
                return null;
            }
            return entry;
        });
    }

    private static class BusEntry {
        final Sinks.Many<AgentEvent> sink;
        final long createdAt;

        BusEntry(Sinks.Many<AgentEvent> sink, long createdAt) {
            this.sink = sink;
            this.createdAt = createdAt;
        }
    }
}