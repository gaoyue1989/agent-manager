package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.GlobalOpenTelemetry;

import ch.qos.logback.classic.spi.LoggingEvent;

/**
 * TraceIdConverter 测试：活跃 span 时输出 trace_id，无 span 时输出 "-"。
 */
class TraceIdConverterTest {

    private final TraceIdConverter converter = new TraceIdConverter();

    @Test
    void shouldReturnDashWithoutActiveSpan() {
        assertEquals("-", converter.convert(new LoggingEvent()));
    }

    @Test
    void shouldReturnTraceIdWithActiveSpan() {
        var tracer = GlobalOpenTelemetry.getTracer("test");
        var span = tracer.spanBuilder("test-span").startSpan();
        try (var scope = span.makeCurrent()) {
            var out = converter.convert(new LoggingEvent());
            assertEquals(span.getSpanContext().getTraceId(), out);
            assertEquals(32, out.length());
            assertTrue(out.matches("[0-9a-f]{32}"));
        } finally {
            span.end();
        }
    }
}