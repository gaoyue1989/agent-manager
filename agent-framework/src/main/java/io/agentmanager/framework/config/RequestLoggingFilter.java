package io.agentmanager.framework.config;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * MDC 请求上下文过滤器：
 * <ul>
 *   <li>为每个 HTTP 请求注入 requestId、userId、sessionId 到 MDC，日志自动携带</li>
 *   <li>记录请求方法、路径、耗时、状态码（排除 SSE 流端点，避免刷屏）</li>
 *   <li>请求结束时清理 MDC，防止线程池复用导致的上下文泄漏</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    static final String MDC_REQUEST_ID = "requestId";
    static final String MDC_USER_ID = "userId";
    static final String MDC_SESSION_ID = "sessionId";

    /** SSE 流端点：不记录响应日志（流式数据量大、持续时间长） */
    private static final String SSE_PATH_PREFIX = "/threads/";
    private static final String SSE_CHAT_SUFFIX = "/chat";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var requestId = deriveRequestId(request);
        var userId = request.getHeader("X-User-Id");
        var sessionId = extractSessionId(request);

        MDC.put(MDC_REQUEST_ID, requestId);
        if (userId != null && !userId.isBlank()) {
            MDC.put(MDC_USER_ID, userId);
        }
        if (sessionId != null) {
            MDC.put(MDC_SESSION_ID, sessionId);
        }

        long startMs = System.currentTimeMillis();
        boolean isSse = isSseEndpoint(request);

        if (!isSse) {
            log.info(">>> {} {} (userId={})", request.getMethod(), request.getRequestURI(), userId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - startMs;

            if (!isSse) {
                // 尝试获取实际状态码
                int status = response.getStatus();
                log.info("<<< {} {} {} ({}ms, userId={})",
                    request.getMethod(), request.getRequestURI(), status, durationMs, userId);
            }

            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_USER_ID);
            MDC.remove(MDC_SESSION_ID);
        }
    }

    /** 优先取 X-Request-Id（网关注入），否则生成 UUID */
    private static String deriveRequestId(HttpServletRequest request) {
        var fromHeader = request.getHeader("X-Request-Id");
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 从路径变量提取 sessionId（/threads/{sessionId}/...） */
    private static String extractSessionId(HttpServletRequest request) {
        var uri = request.getRequestURI();
        // /threads/{sessionId}/chat /subscribe /status /confirm-stream
        if (uri.startsWith(SSE_PATH_PREFIX)) {
            var rest = uri.substring(SSE_PATH_PREFIX.length());
            var slashIdx = rest.indexOf('/');
            return slashIdx > 0 ? rest.substring(0, slashIdx) : null;
        }
        // /threads/chat（sessionId 在请求体中，此处无法提取，跳过）
        return null;
    }

    /** 判断是否为 SSE 流端点（避免逐帧日志刷屏） */
    private static boolean isSseEndpoint(HttpServletRequest request) {
        var uri = request.getRequestURI();
        // /threads/chat、/threads/{sid}/chat、/threads/{sid}/confirm-stream、/threads/{sid}/subscribe
        return (uri.startsWith(SSE_PATH_PREFIX) && uri.endsWith(SSE_CHAT_SUFFIX))
            || uri.startsWith(SSE_PATH_PREFIX) && uri.endsWith("/confirm-stream")
            || uri.startsWith(SSE_PATH_PREFIX) && uri.endsWith("/subscribe")
            || uri.equals("/threads/chat");
    }
}
