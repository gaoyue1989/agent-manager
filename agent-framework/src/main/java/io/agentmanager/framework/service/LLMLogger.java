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
        return storage.getOrDefault(threadId, List.of());
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
