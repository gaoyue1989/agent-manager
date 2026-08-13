package io.agentmanager.framework.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.opentelemetry.api.trace.Span;

/**
 * 内存日志 Appender：环形缓冲保留最近日志，供调试页面 /debug/logs 读取。
 * append 时从当前 OTel span 读取 trace_id/span_id 写入条目，打通日志 ↔ Jaeger 排障闭环。
 */
public class InMemoryLogAppender extends AppenderBase<ILoggingEvent> {
    public static final int MAX_ENTRIES = 500;

    private final ArrayDeque<String> buffer = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    protected void append(ILoggingEvent event) {
        var line = "%s %-5s %s - %s [%s]".formatted(
            Instant.ofEpochMilli(event.getTimeStamp()).toString(),
            event.getLevel().toString(),
            event.getLoggerName(),
            event.getFormattedMessage(),
            traceId());
        lock.lock();
        try {
            buffer.addLast(line);
            while (buffer.size() > MAX_ENTRIES) {
                buffer.removeFirst();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 当前活跃 span 的 trace_id，无活跃 span 时返回 "-"（Span.current() 恒非 null） */
    private String traceId() {
        Span span = Span.current();
        if (span != null && span.getSpanContext().isValid()) {
            return span.getSpanContext().getTraceId();
        }
        return "-";
    }

    /** 按级别过滤快照；level=all 返回全部，limit 限制条数（最新在前） */
    public List<String> snapshot(String level, int limit) {
        lock.lock();
        try {
            var all = new ArrayList<>(buffer);
            var result = new ArrayList<String>();
            var upper = level == null ? "ALL" : level.toUpperCase();
            for (var i = all.size() - 1; i >= 0; i--) {
                var line = all.get(i);
                if (!"ALL".equals(upper) && !line.contains(" " + upper + " ")) {
                    continue;
                }
                result.add(line);
                if (result.size() >= limit) {
                    break;
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }
}
