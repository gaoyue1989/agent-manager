package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LLMLogger {
    private static final Logger log = LoggerFactory.getLogger(LLMLogger.class);

    private final int maxCallsPerThread;
    private final Map<String, List<CallRecord>> storage = new ConcurrentHashMap<>();

    public LLMLogger() {
        this(50);
    }

    public LLMLogger(int maxCallsPerThread) {
        this.maxCallsPerThread = maxCallsPerThread;
    }

    public String logCall(String threadId, Map<String, Object> requestInfo, Map<String, Object> responseInfo) {
        var calls = storage.computeIfAbsent(threadId, k -> new ArrayList<>());
        var callId = "call-" + System.currentTimeMillis() + "-" + calls.size();
        var record = new CallRecord(callId, System.currentTimeMillis(), requestInfo, responseInfo);
        calls.add(record);
        if (calls.size() > maxCallsPerThread) {
            calls.remove(0);
        }
        return callId;
    }

    public List<CallRecord> getCalls(String threadId) {
        var exact = storage.get(threadId);
        if (exact != null) {
            return exact;
        }
        // 兼容 agent_state 中带租户前缀的会话变体（如 "__anon__:debug-user:xxx" 与 "debug-user:xxx" 视为同一会话）
        var normalized = normalizeThreadId(threadId);
        if (normalized != null) {
            for (var entry : storage.entrySet()) {
                if (normalized.equals(normalizeThreadId(entry.getKey()))) {
                    return entry.getValue();
                }
            }
        }
        return List.of();
    }

    /** 剥离租户前缀段（冒号分隔），保留末尾会话本体 */
    private static String normalizeThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) return null;
        var idx = threadId.lastIndexOf(':');
        return idx >= 0 ? threadId.substring(idx + 1) : threadId;
    }

    public void clearThread(String threadId) {
        storage.remove(threadId);
    }

    public record CallRecord(
        String callId, long timestamp,
        Map<String, Object> request,
        Map<String, Object> response
    ) {}
}
