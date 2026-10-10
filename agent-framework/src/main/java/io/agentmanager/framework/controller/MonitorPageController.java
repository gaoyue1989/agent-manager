package io.agentmanager.framework.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营监控独立页入口（从调试控制台独立出来的只读运营页）。
 *
 * <p>与 {@code DebugController} 同构：静态资源（app.js / monitor.js）由 Spring Boot
 * 自动从 classpath:/static/monitor/ 托管，此处仅处理 {@code /monitor} 无尾斜杠时的入口页。
 * 页面仅挂载 Monitor 模块，不暴露 Chat / Config / Database 等调试功能。
 */
@RestController
@RequestMapping("/monitor")
public class MonitorPageController {

    /**
     * 无尾斜杠访问（如 /agent/{name}/monitor）时 302 到相对的 monitor/：
     * 相对资源（app.js）才能基于 .../monitor/ 目录正确解析，兼容任意子路径部署。
     */
    @GetMapping
    public void redirectToSlash(jakarta.servlet.http.HttpServletRequest req,
                                jakarta.servlet.http.HttpServletResponse resp) throws IOException {
        var prefix = req.getHeader("X-Forwarded-Prefix");
        if (prefix != null && !prefix.isBlank()) {
            resp.setHeader("Location", prefix + "/monitor/");
            resp.setStatus(302);
        } else {
            resp.sendRedirect("monitor/");
        }
    }

    @GetMapping(value = {"/"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> monitorPage() {
        try {
            var resource = new ClassPathResource("static/monitor/index.html");
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
