package io.agentmanager.framework.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 分号矩阵参数拒绝过滤器（issue #391）：Tomcat CoyoteAdapter 会在路由到 MVC/Actuator
 * 之前剥离路径段中的矩阵参数（;...），使 /actuator;bypass/metrics 绕过入口层的
 * /actuator 前缀排除规则直达端点（无鉴权暴露指标）。本过滤器在一切业务过滤器之前
 * 检查原始请求 URI（getRequestURI 未剥离、不含 query），含分号即 404——与应用层
 * 「内部端点对外恒 404」语义一致，一次封死 router/host/Chart 全部入口模式。
 * 业务与内部路径均无使用矩阵参数的场景（session 追踪不依赖 URL 重写）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SemicolonPathRejectFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getRequestURI().indexOf(';') >= 0) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
