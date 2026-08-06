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

    @GetMapping(value = {"", "/"}, produces = MediaType.TEXT_HTML_VALUE)
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
