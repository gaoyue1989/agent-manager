package io.agentmanager.framework.tool;

import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.sandbox.opensandbox.OpenSandboxFilesystemSpec;
import io.agentmanager.framework.service.FileAssetStore;
import io.agentmanager.framework.service.WorkspaceReader;
import io.agentmanager.framework.service.storage.FileStorage;
import io.agentscope.core.agent.RuntimeContext;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * present_file 工具单测（UT-18~20）：路径校验、大小限制、落存储+落元数据、回滚。
 */
class FileToolsTest {

    private FileStorage fileStorage;
    private FileAssetStore fileAssetStore;
    private SandboxConfig sandboxConfig;
    private FileTools tools;

    @BeforeEach
    void setUp() {
        fileStorage = mock(FileStorage.class);
        fileAssetStore = mock(FileAssetStore.class);
        sandboxConfig = mock(SandboxConfig.class);
        when(sandboxConfig.enabled()).thenReturn(false);
        var props = io.agentmanager.framework.controller.FileControllerTest.testProps();
        tools = new FileTools(fileAssetStore, fileStorage, props, sandboxConfig,
            mock(WorkspaceReader.class));
    }

    private RuntimeContext ctx() {
        return RuntimeContext.builder().userId("alice").sessionId("s1").build();
    }

    @Test
    void presentFileShouldStoreAndRegister() throws Exception {
        var content = "RESULT-SENTINEL-77".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var result = tools.presentFile(ctx(), "outputs/report.txt",
            Base64.getEncoder().encodeToString(content));

        assertTrue(result.contains("\"file_id\""), "返回 file_id: " + result);
        assertTrue(result.contains("report.txt"), "返回文件名: " + result);
        assertTrue(result.contains("text/plain"), "mime 推断: " + result);
        verify(fileStorage).write(anyString(), any(), any(Long.class), anyString());
        verify(fileAssetStore).insert(any(FileAssetStore.FileAsset.class));
    }

    @Test
    void presentFileShouldRejectPathTraversal() {
        var result = tools.presentFile(ctx(), "../../etc/passwd", Base64.getEncoder().encodeToString(new byte[]{1}));
        assertTrue(result.contains("invalid path"), "穿越应拒绝: " + result);
    }

    @Test
    void presentFileShouldRejectAbsoluteNonWorkspace() {
        var result = tools.presentFile(ctx(), "/etc/passwd", Base64.getEncoder().encodeToString(new byte[]{1}));
        assertTrue(result.contains("invalid path"), "绝对路径应拒绝: " + result);
    }

    @Test
    void presentFileShouldAcceptWorkspaceAbsolute() {
        var result = tools.presentFile(ctx(), "/workspace/outputs/x.txt",
            Base64.getEncoder().encodeToString("hi".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(result.contains("x.txt"), "workspace 绝对路径应接受: " + result);
    }

    @Test
    void presentFileShouldRejectInvalidBase64() {
        var result = tools.presentFile(ctx(), "outputs/x.txt", "!!!not-base64!!!");
        assertTrue(result.contains("not valid base64"), "非法 base64 应拒绝: " + result);
    }

    @Test
    void presentFileSandboxModeRequiresBase64() {
        when(sandboxConfig.enabled()).thenReturn(true);
        var result = tools.presentFile(ctx(), "outputs/x.txt", null);
        assertTrue(result.contains("file_content_base64"), "沙箱模式必须传 base64: " + result);
    }

    @Test
    void presentFileShouldRejectOversize() {
        var big = new byte[51 * 1024 * 1024];
        var result = tools.presentFile(ctx(), "outputs/big.bin",
            Base64.getEncoder().encodeToString(big));
        assertTrue(result.contains("limit"), "超限应拒绝: " + result);
    }

    @Test
    void presentFileShouldRollbackStorageOnMetadataFailure() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
            .when(fileAssetStore).insert(any(FileAssetStore.FileAsset.class));
        var result = tools.presentFile(ctx(), "outputs/x.txt",
            Base64.getEncoder().encodeToString("x".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(result.contains("metadata write failed"), "落库失败应报错: " + result);
        verify(fileStorage).delete(anyString());
    }

    @Test
    void presentFileNonSandboxReadsFromKv() {
        var ws = mock(WorkspaceReader.class);
        when(ws.readWorkspaceFile("alice", "outputs/kv.txt")).thenReturn(
            "from-kv".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var props = io.agentmanager.framework.controller.FileControllerTest.testProps();
        var t = new FileTools(fileAssetStore, fileStorage, props, sandboxConfig, ws);

        var result = t.presentFile(ctx(), "outputs/kv.txt", null);
        assertTrue(result.contains("from-kv") || result.contains("\"file_id\""),
            "非沙箱 KV 读取路径应工作: " + result);
    }

    @Test
    void presentFileSandboxReadsFromSandboxWorkspace() throws Exception {
        // 沙箱模式：无 base64 时经 spec.latestSandbox.readWorkspaceFile 直读（同 userKey 校验通过）
        when(sandboxConfig.enabled()).thenReturn(true);
        var spec = mock(OpenSandboxFilesystemSpec.class);
        var sandbox = mock(io.agentmanager.framework.sandbox.opensandbox.OpenSandbox.class);
        when(spec.getLatestSandbox()).thenReturn(sandbox);
        when(sandbox.getUserKey()).thenReturn("alice");
        when(sandbox.readWorkspaceFile("outputs/pkg.zip")).thenReturn(
            "PK\u0003\u0004fake-zip".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        var props = io.agentmanager.framework.controller.FileControllerTest.testProps();
        var t = new FileTools(fileAssetStore, fileStorage, props, sandboxConfig,
            mock(WorkspaceReader.class), spec);

        var result = t.presentFile(ctx(), "/workspace/outputs/pkg.zip", null);
        assertTrue(result.contains("\"file_id\""), "沙箱直读应登记成功: " + result);
        verify(sandbox).readWorkspaceFile("outputs/pkg.zip");
    }

    @Test
    void presentFileSandboxRejectsForeignUserSandbox() throws Exception {
        // userKey 不匹配（并发串沙箱防护）：不应读取，报错提示
        when(sandboxConfig.enabled()).thenReturn(true);
        var spec = mock(OpenSandboxFilesystemSpec.class);
        var sandbox = mock(io.agentmanager.framework.sandbox.opensandbox.OpenSandbox.class);
        when(spec.getLatestSandbox()).thenReturn(sandbox);
        when(sandbox.getUserKey()).thenReturn("other-user");
        var props = io.agentmanager.framework.controller.FileControllerTest.testProps();
        var t = new FileTools(fileAssetStore, fileStorage, props, sandboxConfig,
            mock(WorkspaceReader.class), spec);

        var result = t.presentFile(ctx(), "outputs/x.txt", null);
        assertTrue(result.contains("not readable"), "userKey 不匹配应拒绝读取: " + result);
        verify(sandbox, org.mockito.Mockito.never()).readWorkspaceFile(anyString());
    }
}