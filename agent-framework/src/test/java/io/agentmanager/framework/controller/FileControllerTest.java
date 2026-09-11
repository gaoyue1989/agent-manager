package io.agentmanager.framework.controller;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.HttpStatus;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.service.FileAssetStore;
import io.agentmanager.framework.service.storage.FileStorage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件上传/下载端点单测（file-upload-download-plan UT-07~09/UT-23）。
 */
public class FileControllerTest {

    private FileStorage fileStorage;
    private FileAssetStore fileAssetStore;
    private AgentManagerProperties props;
    private FileController controller;

    @BeforeEach
    void setUp() {
        fileStorage = mock(FileStorage.class);
        fileAssetStore = mock(FileAssetStore.class);
        props = testProps();
        var sandbox = mock(SandboxConfig.class);
        when(sandbox.enabled()).thenReturn(false);
        controller = new FileController(fileStorage, fileAssetStore, props, sandbox);
    }

    public static AgentManagerProperties testProps() {
        return new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("", "", "", "openai", 0.7, 4096, 120),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/cp", "u", "p", "cp"),
            "/config", "", new AgentManagerProperties.CleanupConfig(60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20,
                "image/*,text/plain,text/markdown,text/csv,application/pdf,"
                    + "application/vnd.openxmlformats-officedocument.*,application/vnd.ms-*",
                5, 15, 50, true, 7, "local", "/tmp/test-files", "", "", "", "agent-files"));
    }

    @Test
    void uploadShouldReturnFileIdAndMeta() throws Exception {
        var file = new MockMultipartFile("file", "report.csv", "text/csv",
            "a,b\n1,2".getBytes(StandardCharsets.UTF_8));
        var resp = controller.upload(file, "alice", "s1");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().get("file_id"));
        assertEquals("report.csv", resp.getBody().get("file_name"));
        assertEquals("text/csv", resp.getBody().get("mime_type"));
        assertEquals(7L, resp.getBody().get("size"));
        // 存储先行 + 元数据落库（非沙箱 → injected）
        verify(fileStorage).write(anyString(), any(), anyLong(), anyString());
        verify(fileAssetStore).insert(any(FileAssetStore.FileAsset.class));
    }

    @Test
    void uploadShouldRejectUnsupportedMime() {
        var file = new MockMultipartFile("file", "evil.exe", "application/x-msdownload",
            new byte[]{1});
        var resp = controller.upload(file, "alice", null);
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, resp.getStatusCode());
        assertEquals("unsupported_file_type", resp.getBody().get("error"));
    }

    @Test
    void uploadShouldRejectOversize() {
        var big = new byte[21 * 1024 * 1024];
        var file = new MockMultipartFile("file", "big.csv", "text/csv", big);
        var resp = controller.upload(file, "alice", null);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, resp.getStatusCode());
        assertEquals("file_too_large", resp.getBody().get("error"));
    }

    @Test
    void uploadShouldRejectTraversalFileName() {
        var file = new MockMultipartFile("file", "../escape.csv", "text/csv", new byte[]{1});
        var resp = controller.upload(file, "alice", null);
        // sanitize 后取 basename "escape.csv"，应通过白名单而非拒绝——验证 sanitize 生效
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("escape.csv", resp.getBody().get("file_name"));
    }

    @Test
    void uploadShouldRollbackStorageOnMetadataFailure() throws Exception {
        var file = new MockMultipartFile("file", "ok.csv", "text/csv", new byte[]{1});
        doThrow(new IllegalStateException("db down")).when(fileAssetStore)
            .insert(any(FileAssetStore.FileAsset.class));
        var resp = controller.upload(file, "alice", null);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        verify(fileStorage).delete(anyString());
    }

    @Test
    void uploadDisabledShouldReturn403() {
        var disabled = new AgentManagerProperties(
            props.llm(), props.server(), props.checkpoint(), "/config", "",
            props.cleanup(), new AgentManagerProperties.FileConfig(false, 20, 20,
                "image/*", 5, 15, 50, true, 7, "local", "/tmp", "", "", "", "b"));
        var c = new FileController(fileStorage, fileAssetStore, disabled, mock(SandboxConfig.class));
        var file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
        assertEquals(HttpStatus.FORBIDDEN, c.upload(file, null, null).getStatusCode());
    }

    @Test
    void downloadMissingShouldReturn404() {
        when(fileAssetStore.get("nope")).thenReturn(java.util.Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, controller.download("nope", 0).getStatusCode());
    }

    @Test
    void downloadShouldReturnAttachmentStream() throws Exception {
        var asset = new FileAssetStore.FileAsset("id-1", "alice", "s1", "report.pdf",
            "outputs/report.pdf", "application/pdf", 100, "local", "generated/k/id.pdf",
            "generated", "injected", java.time.LocalDateTime.now());
        when(fileAssetStore.get("id-1")).thenReturn(java.util.Optional.of(asset));
        when(fileStorage.exists("generated/k/id.pdf")).thenReturn(true);
        when(fileStorage.read("generated/k/id.pdf")).thenReturn(
            new java.io.ByteArrayInputStream(new byte[100]));

        var resp = controller.download("id-1", 0);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        var cd = resp.getHeaders().getFirst("Content-Disposition");
        assertTrue(cd != null && cd.contains("attachment"), "默认 attachment: " + cd);
        assertTrue(cd.contains("report.pdf"), "含文件名: " + cd);
        assertEquals(100L, resp.getHeaders().getContentLength());
    }

    @Test
    void downloadInlineForImage() throws Exception {
        var asset = new FileAssetStore.FileAsset("id-2", "alice", null, "pic.png",
            null, "image/png", 10, "local", "upload/k/pic.png", "upload", "injected",
            java.time.LocalDateTime.now());
        when(fileAssetStore.get("id-2")).thenReturn(java.util.Optional.of(asset));
        when(fileStorage.exists("upload/k/pic.png")).thenReturn(true);
        var resp = controller.download("id-2", 1);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getHeaders().getFirst("Content-Disposition").contains("inline"),
            "inline=1 且 image/* → inline");
    }

    @Test
    void downloadStorageMissingShouldReturn502() throws Exception {
        var asset = new FileAssetStore.FileAsset("id-3", "alice", null, "a.txt",
            null, "text/plain", 10, "local", "upload/k/a.txt", "upload", "injected",
            java.time.LocalDateTime.now());
        when(fileAssetStore.get("id-3")).thenReturn(java.util.Optional.of(asset));
        when(fileStorage.exists("upload/k/a.txt")).thenReturn(false);
        assertEquals(HttpStatus.BAD_GATEWAY, controller.download("id-3", 0).getStatusCode());
    }

    @Test
    void sanitizeFileNameShouldStripPath() {
        assertEquals("a.txt", FileController.sanitizeFileName("/path/to/a.txt"));
        assertEquals("a.txt", FileController.sanitizeFileName("..\\..\\a.txt"));
        assertNull(FileController.sanitizeFileName(".."));
        assertNull(FileController.sanitizeFileName(""));
    }

    @Test
    void mimeAllowedShouldMatchWildcard() {
        assertTrue(FileController.mimeAllowed("image/png", "image/*,text/plain"));
        assertTrue(FileController.mimeAllowed("text/plain", "image/*,text/plain"));
        assertTrue(!FileController.mimeAllowed("application/pdf", "image/*,text/plain"));
        assertTrue(FileController.mimeAllowed("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.*"));
        // 白名单为空 → 全部拒绝
        assertTrue(!FileController.mimeAllowed("image/png", ""));
    }
}