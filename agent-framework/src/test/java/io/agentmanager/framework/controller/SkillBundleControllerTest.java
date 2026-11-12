package io.agentmanager.framework.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.sandbox.opensandbox.SkillBundleService;

class SkillBundleControllerTest {

    private static final String TOKEN = "sync-token";
    private static final byte[] GZ = "gz-payload".getBytes(StandardCharsets.UTF_8);

    private final SkillBundleService bundleService = mock(SkillBundleService.class);
    private MockMvc mvc;
    private MockMvc closedMvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                new SkillBundleController(config(TOKEN), bundleService)).build();
        closedMvc = MockMvcBuilders.standaloneSetup(
                new SkillBundleController(config(""), bundleService)).build();
    }

    @Test
    void versionShouldReturn404WhenTokenNotConfigured() throws Exception {
        closedMvc.perform(get("/internal/skills/version"))
            .andExpect(status().isNotFound());
    }

    @Test
    void bundleShouldReturn404WhenTokenNotConfigured() throws Exception {
        closedMvc.perform(get("/internal/skills/bundle").param("version", "abc"))
            .andExpect(status().isNotFound());
    }

    @Test
    void versionShouldReturn401WhenAuthorizationMissing() throws Exception {
        mvc.perform(get("/internal/skills/version"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void versionShouldReturn401WhenBearerTokenWrong() throws Exception {
        mvc.perform(get("/internal/skills/version")
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
            .andExpect(status().isUnauthorized());

        mvc.perform(get("/internal/skills/version")
                .header(HttpHeaders.AUTHORIZATION, TOKEN))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void versionShouldReturnFingerprintWithProtocol() throws Exception {
        when(bundleService.currentVersion())
            .thenReturn(new SkillBundleService.SkillVersion("abc123", 3, 27L));

        mvc.perform(get("/internal/skills/version")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value("abc123"))
            .andExpect(jsonPath("$.skillCount").value(3))
            .andExpect(jsonPath("$.sizeBytes").value(27))
            .andExpect(jsonPath("$.serverProtocol").value("v1"));
    }

    @Test
    void bundleShouldStreamGzipWithSha256Header() throws Exception {
        when(bundleService.bundle("abc123")).thenReturn(Optional.of(payload()));

        MvcResult result = mvc.perform(get("/internal/skills/bundle")
                .param("version", "abc123")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
            .andExpect(request().asyncStarted())
            .andReturn();

        mvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.parseMediaType("application/gzip")))
            .andExpect(header().string("X-Bundle-Sha256", sha256Hex(GZ)))
            .andExpect(content().bytes(GZ));
    }

    @Test
    void bundleShouldReturn404WhenVersionMismatch() throws Exception {
        when(bundleService.bundle("ghost")).thenReturn(Optional.empty());

        mvc.perform(get("/internal/skills/bundle")
                .param("version", "ghost")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
            .andExpect(status().isNotFound());
    }

    @Test
    void bundleShouldReturn401WhenBearerTokenWrong() throws Exception {
        mvc.perform(get("/internal/skills/bundle")
                .param("version", "abc123")
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
            .andExpect(status().isUnauthorized());
    }

    private static SkillBundleService.BundlePayload payload() {
        return new SkillBundleService.BundlePayload(GZ.length, sha256Hex(GZ),
            () -> new ByteArrayInputStream(GZ));
    }

    private static SandboxConfig config(String token) {
        return new SandboxConfig(
            false, "img", 30, 30, 1024, 1,
            List.of("/e"), Duration.ofMillis(100),
            true, true, 900, true, true, true, true, 500, 30,
            token, "/tmp/skills-bundle-cache-test", "",
            new SandboxConfig.OpenSandboxConfig("127.0.0.1:8090", ""));
    }

    private static String sha256Hex(byte[] data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(data);
            var sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
