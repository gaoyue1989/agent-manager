package io.agentmanager.framework.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 调试页面入口：静态资源（css/js/modules）由 Spring Boot 自动从
 * classpath:/static/debug/ 托管，此处仅处理 /debug 无尾斜杠时的入口页。
 */
@RestController
@RequestMapping("/debug")
public class DebugController {

    /**
     * 无尾斜杠访问（如 /agent/{name}/debug）时 302 到相对的 debug/：
     * 相对资源（css/js）才能基于 .../debug/ 目录正确解析，兼容任意子路径部署。
     */
    @GetMapping
    public void redirectToSlash(jakarta.servlet.http.HttpServletRequest req,
                                jakarta.servlet.http.HttpServletResponse resp) throws java.io.IOException {
        // 经 ingress rewrite 后后端只见 /debug，外部前缀由 x-forwarded-prefix 注解透传
        var prefix = req.getHeader("X-Forwarded-Prefix");
        if (prefix != null && !prefix.isBlank()) {
            resp.setHeader("Location", prefix + "/debug/");
            resp.setStatus(302);
        } else {
            // 直连部署（无前缀）：相对跳转 /debug → /debug/
            resp.sendRedirect("debug/");
        }
    }

    @GetMapping(value = {"/"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> debugPage() {
        try {
            var resource = new ClassPathResource("static/debug/index.html");
            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }
            var content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(content);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
