package io.agentmanager.framework.controller;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import io.agentmanager.framework.sandbox.opensandbox.SkillBundleService;

/**
 * skills-syncd 内部端点（sandbox-skills-syncd-design §4.3/§6.1）：版本协商与 bundle 拉取。
 * 仅集群内访问（业务入口排除见设计 §6.1）；无鉴权、不参与 ROUTER_AUTH_MODE/业务鉴权链，
 * 与 /actuator 同级。
 */
@RestController
public class SkillBundleController {

    static final String SERVER_PROTOCOL = "v1";

    private final SkillBundleService bundleService;

    public SkillBundleController(SkillBundleService bundleService) {
        this.bundleService = bundleService;
    }

    @GetMapping("/internal/skills/version")
    public ResponseEntity<Map<String, Object>> version() {
        var current = bundleService.currentVersion();
        if (current.version().startsWith(SkillBundleService.ERROR_VERSION_PREFIX)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("version", current.version());
        body.put("skillCount", current.skillCount());
        body.put("fileCount", current.fileCount());
        body.put("sizeBytes", current.sizeBytes());
        body.put("serverProtocol", SERVER_PROTOCOL);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/internal/skills/bundle")
    public ResponseEntity<StreamingResponseBody> bundle(@RequestParam String version) throws IOException {
        Optional<SkillBundleService.BundlePayload> payload = bundleService.bundle(version);
        if (payload.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var p = payload.get();
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/gzip"));
        headers.setContentLength(p.size());
        headers.set("X-Bundle-Sha256", p.sha256());
        StreamingResponseBody body = out -> {
            try (InputStream in = p.stream().open()) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
