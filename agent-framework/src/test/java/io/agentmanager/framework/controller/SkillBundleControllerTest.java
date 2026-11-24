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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.agentmanager.framework.sandbox.opensandbox.SkillBundleService;

class SkillBundleControllerTest {

    private static final byte[] GZ = "gz-payload".getBytes(StandardCharsets.UTF_8);

    private final SkillBundleService bundleService = mock(SkillBundleService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SkillBundleController(bundleService)).build();
    }

    @Test
    void versionShouldReturnFingerprintWithProtocol() throws Exception {
        when(bundleService.currentVersion())
            .thenReturn(new SkillBundleService.SkillVersion("abc123", 2, 3, 27L, List.of()));

        mvc.perform(get("/internal/skills/version"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value("abc123"))
            .andExpect(jsonPath("$.skillCount").value(2))
            .andExpect(jsonPath("$.fileCount").value(3))
            .andExpect(jsonPath("$.sizeBytes").value(27))
            .andExpect(jsonPath("$.serverProtocol").value("v1"));
    }

    @Test
    void endpointsShouldServeRequestsCarryingAuthorizationHeader() throws Exception {
        when(bundleService.currentVersion())
            .thenReturn(new SkillBundleService.SkillVersion("abc123", 2, 3, 27L, List.of()));
        when(bundleService.bundle("abc123")).thenReturn(Optional.of(payload()));

        mvc.perform(get("/internal/skills/version")
                .header(HttpHeaders.AUTHORIZATION, "Bearer stale-token"))
            .andExpect(status().isOk());
        MvcResult result = mvc.perform(get("/internal/skills/bundle")
                .param("version", "abc123")
                .header(HttpHeaders.AUTHORIZATION, "Bearer stale-token"))
            .andExpect(request().asyncStarted())
            .andReturn();

        mvc.perform(asyncDispatch(result))
            .andExpect(status().isOk())
            .andExpect(content().bytes(GZ));
    }

    @Test
    void versionShouldReturn503WhenFingerprintUnavailable() throws Exception {
        // 指纹计算失败（computeVersion 全 catch → ERR-*）= 端点当前不可用：返回 503 让守护
        // 退避重试，不输出 ERR-* 版本（守护会把它当合法版本装进 store 污染版本空间）
        when(bundleService.currentVersion())
            .thenReturn(new SkillBundleService.SkillVersion("ERR-123456", 0, 0, 0L, List.of()));

        mvc.perform(get("/internal/skills/version"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void bundleShouldStreamGzipWithSha256Header() throws Exception {
        when(bundleService.bundle("abc123")).thenReturn(Optional.of(payload()));

        MvcResult result = mvc.perform(get("/internal/skills/bundle").param("version", "abc123"))
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

        mvc.perform(get("/internal/skills/bundle").param("version", "ghost"))
            .andExpect(status().isNotFound());
    }

    private static SkillBundleService.BundlePayload payload() {
        return new SkillBundleService.BundlePayload(GZ.length, sha256Hex(GZ),
            () -> new ByteArrayInputStream(GZ));
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
