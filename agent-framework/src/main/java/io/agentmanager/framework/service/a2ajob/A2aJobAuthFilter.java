package io.agentmanager.framework.service.a2ajob;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * A2A Job 端点（/a2a/jobs*）认证过滤器（Issue #69 §2.3；复刻 AgentProtocolAuthFilter）。
 *
 * <p>Header {@link #TOKEN_HEADER}（Agent-A2A-Job-Token）：无/错一律 401，不区分
 * 「缺失」与「错误」（防探测）；比较用 {@link MessageDigest#isEqual} 常数时间实现。
 *
 * <p>注册与生命周期：不由本类自注册；经 {@code A2aJobConfig} 的
 * {@code FilterRegistrationBean} 装配（URL 限 /a2a/jobs、/a2a/jobs/*）——Job 未启用时
 * 过滤器整体不存在。装配前置保证 token 非空白（enabled=true 时 fail-fast）。
 *
 * <p><b>与 /tasks 协议 token 分属两个信任域</b>：/tasks 是远程子 agent 面（lead→member），
 * /a2a/jobs 是外部互操作面（平台外调用方）——不复用凭据（Issue §2.3）。
 */
public class A2aJobAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(A2aJobAuthFilter.class);

    /** Job 入口认证 token Header 名 */
    public static final String TOKEN_HEADER = "Agent-A2A-Job-Token";

    private final String expectedToken;

    public A2aJobAuthFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var provided = request.getHeader(TOKEN_HEADER);
        if (!tokenMatches(provided)) {
            // 统一 401 + 固定报文：缺失与错误不做区分，防探测
            log.warn("A2A Job request rejected: uri={}, reason={}",
                request.getRequestURI(), provided == null ? "missing_token" : "token_mismatch");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * token 比对：期望值空白一律拒绝（fail-fast 之外的兜底）；比较用
     * {@link MessageDigest#isEqual} 常数时间实现，防时序侧信道逐字节猜解。
     */
    private boolean tokenMatches(String provided) {
        if (provided == null || expectedToken == null || expectedToken.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
            provided.getBytes(StandardCharsets.UTF_8),
            expectedToken.getBytes(StandardCharsets.UTF_8));
    }
}
