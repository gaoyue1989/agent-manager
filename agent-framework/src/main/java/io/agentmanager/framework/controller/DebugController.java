package io.agentmanager.framework.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
public class DebugController {

    private final Resource debugPage = new ClassPathResource("templates/debug_page.html");

    @GetMapping(value = "/debug", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> debugPage() {
        try {
            var inputStream = debugPage.getInputStream();
            var content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok(content);
        } catch (IOException e) {
            return ResponseEntity.ok("<h1>Debug page not found</h1>");
        }
    }
}
