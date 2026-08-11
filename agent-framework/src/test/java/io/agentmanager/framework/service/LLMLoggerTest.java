package io.agentmanager.framework.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LLMLoggerTest {

    @Test
    void logCallShouldStoreAndReturnCallId() {
        var logger = new LLMLogger(50);

        var callId = logger.logCall("t1", Map.of("prompt", "hi"), Map.of("text", "hello"));

        assertTrue(callId.startsWith("call-"));
        var calls = logger.getCalls("t1");
        assertEquals(1, calls.size());
        assertEquals("hi", calls.get(0).request().get("prompt"));
        assertEquals("hello", calls.get(0).response().get("text"));
    }

    @Test
    void logCallShouldTrimToMaxWhenExceeded() {
        var logger = new LLMLogger(2);
        logger.logCall("t1", Map.of(), Map.of());
        logger.logCall("t1", Map.of(), Map.of());
        logger.logCall("t1", Map.of(), Map.of());

        var calls = logger.getCalls("t1");
        assertEquals(2, calls.size());
        // 最旧一条被移除, 保留最新两条
        assertNotEquals(calls.get(0).callId(), calls.get(1).callId());
    }

    @Test
    void getCallsShouldReturnEmptyForUnknownThread() {
        var logger = new LLMLogger();
        assertTrue(logger.getCalls("nope").isEmpty());
    }

    @Test
    void clearThreadShouldRemoveRecords() {
        var logger = new LLMLogger();
        logger.logCall("t1", Map.of(), Map.of());
        logger.clearThread("t1");

        assertTrue(logger.getCalls("t1").isEmpty());
    }

    @Test
    void logCallShouldKeepSeparateStoragePerThread() {
        var logger = new LLMLogger();
        logger.logCall("t1", Map.of("n", 1), Map.of());
        logger.logCall("t2", Map.of("n", 2), Map.of());

        assertEquals(1, logger.getCalls("t1").size());
        assertEquals(1, logger.getCalls("t2").size());
    }

    @Test
    void getCallsShouldMatchPrefixedVariant() {
        var logger = new LLMLogger();
        logger.logCall("debug-user:llm-test-001", Map.of("n", 1), Map.of());

        // agent_state 中带前缀变体（__anon__ 租户段）也应命中同一会话
        assertEquals(1, logger.getCalls("__anon__:debug-user:llm-test-001").size());
        assertEquals(1, logger.getCalls("__anon__:__anon__:debug-user:llm-test-001").size());
        // 仅会话本体（末段）也应命中
        assertEquals(1, logger.getCalls("llm-test-001").size());
    }
}