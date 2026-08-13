package io.agentmanager.framework;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * tracing 组件测试基类：注册带 InMemorySpanExporter 的 OTel SDK，
 * 供 GlobalOpenTelemetry 全局单例读取，捕获并断言 span。
 *
 * <p>同时注册 ContextPropagationOperator 的全局 hook（等价于
 * OtelTracingMiddleware 构造时行为），使 runWithContext 的上下文传播在
 * 测试中生效。
 *
 * <p>注意：GlobalOpenTelemetry.set() 单 JVM 仅能成功一次；多个测试类共享同一
 * SDK 实例，@BeforeEach 清空 exporter 保证用例隔离。
 */
public abstract class TracingTestBase {

    protected static InMemorySpanExporter spanExporter;

    @BeforeAll
    static void registerTestOpenTelemetry() {
        spanExporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        // GlobalOpenTelemetry 单 JVM 仅可注册一次；resetForTest 保证测试类间隔离
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build());
        // 注册 Reactor 全局上下文传播 hook（幂等，等价 SDK 中间件构造时行为）
        ContextPropagationOperator.builder().build().registerOnEachOperator();
    }

    @BeforeEach
    void clearExporter() {
        spanExporter.reset();
    }

    /** 按名称过滤已完成 span */
    protected List<SpanData> findSpans(String name) {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(name))
                .toList();
    }
}