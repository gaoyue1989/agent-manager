package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.opentelemetry.api.GlobalOpenTelemetry;

import ch.qos.logback.classic.spi.LoggingEvent;

/**
 * TraceIdConverter 测试：活跃 span 时输出 trace_id，无 span 时输出 "-"。
 */
class TraceIdConverterTest {

    private final TraceIdConverter converter = new TraceIdConverter();

    @BeforeAll
    static void initOtel() {
        // 自包含初始化：单独执行/顺序变化时 GlobalOpenTelemetry 可能仍是 no-op
        // （TracingTestBase 的 set() 单 JVM 仅首次成功），此处幂等兜底。
        try {
            GlobalOpenTelemetry.set(io.opentelemetry.sdk.OpenTelemetrySdk.builder()
                .setTracerProvider(io.opentelemetry.sdk.trace.SdkTracerProvider.builder()
                    .addSpanProcessor(io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(
                        io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create()))
                    .build())
                .build());
        } catch (IllegalStateException ignored) {
            // 已被其他测试类初始化
        }
    }

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