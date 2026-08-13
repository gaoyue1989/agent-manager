package io.agentmanager.framework.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import io.agentmanager.framework.TracingTestBase;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通过真实 logback Logger 驱动 InMemoryLogAppender，验证收集/过滤/环形缓冲。
 *
 * <p>继承 TracingTestBase 使 GlobalOpenTelemetry 注册真实 SDK，
 * 保证 trace_id 用例中 span 有效。
 */
class InMemoryLogAppenderTest extends TracingTestBase {

    private InMemoryLogAppender appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        // 独立 logger，避免影响 root logger 其他测试
        logger = (Logger) LoggerFactory.getLogger("io.agentmanager.inmemory.test");
        logger.setLevel(Level.DEBUG);
        logger.detachAndStopAllAppenders();
        logger.setAdditive(false);

        appender = new InMemoryLogAppender();
        appender.setName("TEST_IN_MEMORY");
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        appender.stop();
        logger.detachAppender(appender);
    }

    @Test
    void appendShouldStoreFormattedLine() {
        logger.info("hello world");

        var logs = appender.snapshot("all", 10);
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).contains("INFO"));
        assertTrue(logs.get(0).contains("hello world"));
    }

    @Test
    void snapshotShouldFilterByLevel() {
        logger.info("info line");
        logger.error("error line");
        logger.warn("warn line");

        var errors = appender.snapshot("ERROR", 10);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("ERROR"));

        var infos = appender.snapshot("INFO", 10);
        assertEquals(1, infos.size());
        assertTrue(infos.get(0).contains("INFO"));
    }

    @Test
    void snapshotAllShouldReturnNewestFirst() {
        logger.info("first");
        logger.info("second");

        var logs = appender.snapshot("all", 10);
        assertEquals(2, logs.size());
        assertTrue(logs.get(0).contains("second"));
        assertTrue(logs.get(1).contains("first"));
    }

    @Test
    void snapshotShouldRespectLimit() {
        for (var i = 0; i < 10; i++) {
            logger.info("line " + i);
        }

        var logs = appender.snapshot("all", 3);
        assertEquals(3, logs.size());
        assertTrue(logs.get(0).contains("line 9"));
    }

    @Test
    void snapshotShouldHandleNullLevelAsAll() {
        logger.info("x");
        assertEquals(1, appender.snapshot(null, 10).size());
    }

    @Test
    void appendShouldCapBufferAtMaxEntries() {
        for (var i = 0; i < InMemoryLogAppender.MAX_ENTRIES + 50; i++) {
            logger.debug("line " + i);
        }

        var logs = appender.snapshot("all", InMemoryLogAppender.MAX_ENTRIES);
        assertEquals(InMemoryLogAppender.MAX_ENTRIES, logs.size());
        // 最旧的 50 条被丢弃, 最新在最前
        assertTrue(logs.get(0).contains("line " + (InMemoryLogAppender.MAX_ENTRIES + 49)));
    }

    @Test
    void appendShouldIncludeTraceIdWhenSpanActive() {
        var tracer = io.opentelemetry.api.GlobalOpenTelemetry.getTracer("test");
        var span = tracer.spanBuilder("test-span").startSpan();
        try (var scope = span.makeCurrent()) {
            logger.info("traced line");
        } finally {
            span.end();
        }

        var logs = appender.snapshot("all", 10);
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).contains(span.getSpanContext().getTraceId()));
        assertTrue(logs.get(0).matches(".*\\[%s\\]".formatted(span.getSpanContext().getTraceId())));
    }

    @Test
    void appendShouldUseDashWithoutActiveSpan() {
        logger.info("untraced line");

        var logs = appender.snapshot("all", 10);
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).endsWith("[-]"));
    }
}