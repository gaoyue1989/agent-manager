package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

/**
 * LogCollector 挂载到 root logger 后应能采集日志。
 */
class LogCollectorTest {

    @BeforeEach
    void detachExistingAppender() {
        // 避免测试间 root logger 上的 IN_MEMORY appender 相互污染
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var existing = root.getAppender("IN_MEMORY");
        if (existing != null) {
            existing.stop();
            root.detachAppender(existing);
        }
    }

    @Test
    void attachShouldHookInMemoryAppenderAndCollectLogs() {
        var collector = new LogCollector();
        collector.attach();

        LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)
            .info("collector probe message");

        var logs = collector.getLogs("INFO", 5);
        assertFalse(logs.isEmpty());
        assertTrue(logs.stream().anyMatch(l -> l.contains("collector probe message")));
    }

    @Test
    void attachShouldBeIdempotent() {
        var collector = new LogCollector();
        collector.attach();
        collector.attach();

        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var names = new java.util.ArrayList<String>();
        root.iteratorForAppenders().forEachRemaining(a -> names.add(a.getName()));
        // IN_MEMORY 只出现一次（第二次 attach 时已存在则跳过）
        assertEquals(1, java.util.Collections.frequency(names, "IN_MEMORY"));
    }

    @Test
    void getLogsShouldFilterByLevel() {
        var collector = new LogCollector();
        collector.attach();

        LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).warn("warn-only-probe");

        var errors = collector.getLogs("ERROR", 5);
        assertTrue(errors.stream().noneMatch(l -> l.contains("warn-only-probe")));
    }

    @Test
    void getLogsShouldRespectLimit() {
        var collector = new LogCollector();
        collector.attach();

        for (var i = 0; i < 10; i++) {
            LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).info("limit-probe-" + i);
        }

        var logs = collector.getLogs(null, 10);
        assertEquals(10, logs.size());
    }
}