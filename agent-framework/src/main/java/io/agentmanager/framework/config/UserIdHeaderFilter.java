package io.agentmanager.framework.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 网关 userId Header 适配过滤器
 *
 * <p>从 {@code X-User-Id} Header 提取网关注入的 userId，存入 request attribute，
 * 供 Controller 层通过 {@link #getUserId(HttpServletRequest)} 获取。
 *
 * <h3>优先级</h3>
 * <ol>
 *   <li>{@code X-User-Id} Header（网关注入，可信）</li>
 *   <li>请求参数 / 请求体中的 userId（直连模式，调试用）</li>
 *   <li>默认值 "debug-user"</li>
 * </ol>
 *
 * <h3>使用方式（Controller 中）</h3>
 * <pre>{@code
 * var userId = UserIdHeaderFilter.getUserId(request);
 * // 或如果 userId 不在 request attribute 里
 * var userId = UserIdHeaderFilter.resolveUserId(request, body.userId());
 * }</pre>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class UserIdHeaderFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UserIdHeaderFilter.class);

    /** 网关注入的 userId Header 名，与网关配置保持一致 */
    public static final String USER_ID_HEADER = "X-User-Id";

    /** request attribute key */
    private static final String ATTR_USER_ID = "io.agentmanager.gateway.userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {

        var userIdFromHeader = request.getHeader(USER_ID_HEADER);

        if (userIdFromHeader != null && !userIdFromHeader.isBlank()) {
            // 网关模式：将 X-User-Id Header 值存入 request attribute
            log.debug("UserId from gateway header: {}", userIdFromHeader);
            request.setAttribute(ATTR_USER_ID, userIdFromHeader);
        }

        chain.doFilter(request, response);
    }

    /**
     * 从 request attribute 中获取网关注入的 userId。
     *
     * @return 网关注入的 userId，不存在时返回 null
     */
    public static String getHeaderUserId(HttpServletRequest request) {
        return (String) request.getAttribute(ATTR_USER_ID);
    }

    /**
     * 智能解析 userId：网关 Header 优先，否则回落到调用方传入的值。
     *
     * <p>典型用法（Controller 中）：
     * <pre>{@code
     * var userId = UserIdHeaderFilter.resolveUserId(request, body.userId());
     * }</pre>
     *
     * @param request     当前请求
     * @param fallback    回落值（来自请求参数、请求体等）
     * @return 最终 userId
     */
    public static String resolveUserId(HttpServletRequest request, String fallback) {
        var fromHeader = getHeaderUserId(request);
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }
        return (fallback != null && !fallback.isBlank()) ? fallback : "debug-user";
    }
}
