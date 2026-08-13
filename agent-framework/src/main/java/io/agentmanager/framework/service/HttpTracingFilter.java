package io.agentmanager.framework.service;

import java.io.IOException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * HTTP 请求链路追踪过滤器（必需，非可选）。
 *
 * <p>sandbox.create（Mono.using resourceSupplier）、invoke_agent（middleware 链）、
 * sandbox.delete（resourceClosure）三处 span 创建时都没有活跃父 span。
 * 本 Filter 创建 HTTP span 作为共同父级，保证一次 Agent 调用产出单一 trace。
 *
 * <p>仅在 OTEL_TRACES_EXPORTER=otlp 时注册；none（默认）时不参与请求处理。
 */
@Component
@ConditionalOnProperty(name = "otel.traces.exporter", havingValue = "otlp")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpTracingFilter extends OncePerRequestFilter {

    private static final String INSTRUMENTATION_NAME = "io.agentmanager.http";

    /** 惰性获取：Filter 构造可能早于 OtelConfig 注册 SDK，字段初始化会拿到永久 no-op tracer */
    private Tracer tracer() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        Span span = tracer().spanBuilder(request.getMethod() + " " + request.getRequestURI())
                .startSpan();
        span.setAttribute("http.request.method", request.getMethod());
        span.setAttribute("url.path", request.getRequestURI());
        try (var scope = span.makeCurrent()) {
            chain.doFilter(request, response);
            span.setAttribute("http.response.status_code", response.getStatus());
            span.setStatus(response.getStatus() >= 400 ? StatusCode.ERROR : StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}