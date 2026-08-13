package io.agentmanager.framework.service;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.opentelemetry.api.trace.Span;

/**
 * logback 转换器：输出当前活跃 span 的 trace_id（无活跃 span 时输出 "-"）。
 * 用于日志 pattern 中的 %traceId，打通 "Jaeger trace ↔ 应用日志" 排障闭环。
 *
 * <p>不依赖线程局部 MDC：ContextPropagationOperator.runWithContext 在信号回调
 * 执行期间 makeCurrent()，故 reactive 链内打日志时 Span.current() 有效。
 */
public class TraceIdConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        Span span = Span.current();
        if (span != null && span.getSpanContext().isValid()) {
            return span.getSpanContext().getTraceId();
        }
        return "-";
    }
}