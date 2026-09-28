package io.agentmanager.framework.service.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Agent Protocol 端点（/tasks*）服务间认证过滤器（travel-fulfillment agent-protocol 设计 §6.2）。
 *
 * <p>背景（F1/F12）：Agent Protocol 端点无内建认证，业务 Ingress 单正则把服务根全量暴露到
 * NodePort 30080——不设防等于任何人可远程调度该 agent（手握 shell/MCP 写工具）。
 * 本过滤器对 /tasks 与 /tasks/* 前置校验 {@link #TOKEN_HEADER}：无/错 token 一律 401，
 * 不区分「缺失」与「错误」（避免向探测方泄露校验细节）。
 *
 * <p>注册与生命周期：不由本类自注册；经 {@code AgentProtocolConfig.ProtocolEnabledAssembly}
 * 的 {@code FilterRegistrationBean} 装配（URL 限 /tasks、/tasks/*）——协议未启用时过滤器
 * 整体不存在。装配前置条件保证 token 非空白（enabled=true 时 fail-fast），此处保留
 * 空白拒绝的兜底防御。
 *
 * <p>父侧（lead）经 {@code SubagentDeclaration.headers} 注入同一 token（值走 env，
 * 不落包内），集群内 svc 直连 + token 构成信任边界（设计 §6.2 信任假设）。
 */
public class AgentProtocolAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AgentProtocolAuthFilter.class);

    /** 服务间认证 token Header 名（与 lead 侧 SubagentDeclaration.headers 注入键一致） */
    public static final String TOKEN_HEADER = "X-Agent-Protocol-Token";

    private final String expectedToken;

    public AgentProtocolAuthFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var provided = request.getHeader(TOKEN_HEADER);
        if (!tokenMatches(provided)) {
            // 统一 401 + 固定报文：缺失与错误不做区分，防探测
            log.warn("Agent Protocol request rejected: uri={}, reason={}",
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
