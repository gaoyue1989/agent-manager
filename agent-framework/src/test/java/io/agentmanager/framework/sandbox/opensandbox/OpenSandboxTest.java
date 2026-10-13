package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionLogs;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.services.Commands;
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem;

import io.agentmanager.framework.service.FileAssetStore;
import io.agentmanager.framework.service.WorkspaceReader;
import io.agentmanager.framework.service.storage.FileStorage;
import io.agentscope.core.agent.RuntimeContext;

class OpenSandboxTest {

    private static OpenSandboxClientOptions options() {
        return new OpenSandboxClientOptions()
            .serverUrl("s").apiKey("k")
            .image("opensandbox/code-interpreter:v1.1.0")
            .resource(Map.of("cpu", "1", "memory", "1024Mi"));
    }

    /** AbstractBaseSandbox 构造要求 state.workspaceSpec 非空 */
    private static OpenSandboxState state() {
        var s = new OpenSandboxState();
        var ws = new io.agentscope.harness.agent.sandbox.WorkspaceSpec();
        ws.setRoot("/workspace");
        s.setWorkspaceSpec(ws);
        return s;
    }

    private static Execution mockExecution(int exitCode, String stdout, String stderr) {
        var exec = mock(Execution.class);
        when(exec.getExitCode()).thenReturn(exitCode);
        var logs = mock(ExecutionLogs.class);
        when(logs.getStdout()).thenReturn(
            stdout == null || stdout.isEmpty() ? List.of()
                : List.of(new OutputMessage(stdout, 0L, false)));
        when(logs.getStderr()).thenReturn(
            stderr == null || stderr.isEmpty() ? List.of()
                : List.of(new OutputMessage(stderr, 0L, true)));
        when(exec.getLogs()).thenReturn(logs);
        return exec;
    }

    private static Sandbox mockOsb(Execution exec) {
        var osb = mock(Sandbox.class);
        var commands = mock(Commands.class);
        when(commands.run(anyString())).thenReturn(exec);
        when(osb.commands()).thenReturn(commands);
        var files = mock(Filesystem.class);
        when(osb.files()).thenReturn(files);
        return osb;
    }

    @Test
    void doExecShouldMapExecutionToExecResult() throws Exception {
        var osb = mockOsb(mockExecution(0, "hello\nworld", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);
        var ctx = RuntimeContext.builder().userId("u1").build();

        var result = sandbox.exec(ctx, "echo hello", 30);

        assertTrue(result.ok());
        assertEquals("hello\nworld", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void doExecShouldCaptureStderrAndNonZeroExit() throws Exception {
        var osb = mockOsb(mockExecution(2, "", "boom"));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), "false", 30);

        assertEquals(2, result.exitCode());
        assertFalse(result.ok());
        assertEquals("boom", result.stderr());
    }

    @Test
    void doExecShouldResolveRealUserViaSessionKeyResolver() throws Exception {
        // Channel 链路（issue #52）：ctx.userId 是网关 peer（=前端 sessionId），doExec
        // 须与 SandboxUserKeyMiddleware 同源反查真实用户，不得用 ctx 原值覆盖退化
        var osb = mockOsb(mockExecution(0, "ok", ""));
        var spec = new OpenSandboxFilesystemSpec();
        var store = mock(io.agentmanager.framework.service.SessionUserStore.class);
        when(store.findUserIdBySession("gw-hash")).thenReturn(null);
        when(store.findUserIdBySession("front-sid")).thenReturn("alice");
        spec.setSessionKeyResolver(new io.agentmanager.framework.service.SessionKeyResolver(store));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null, spec);

        var ctx = RuntimeContext.builder().sessionId("gw-hash").userId("front-sid").build();
        sandbox.exec(ctx, "echo hi", 30);

        assertEquals("alice", sandbox.getUserKey());
    }

    @Test
    void doExecShouldFallBackToCtxValuesWithoutResolver() throws Exception {
        // 未装配 resolver：保持旧行为（userId 优先，空则降级 sessionId）
        var osb = mockOsb(mockExecution(0, "ok", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        sandbox.exec(RuntimeContext.builder().userId("u1").build(), "echo hi", 30);
        assertEquals("u1", sandbox.getUserKey());

        sandbox.exec(RuntimeContext.builder().sessionId("s-1").build(), "echo hi", 30);
        assertEquals("s-1", sandbox.getUserKey());
    }

    @Test
    void doSetupWorkspaceShouldCreateWorkspaceDir() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        sandbox.start();

        verify(osb.commands()).run("mkdir -p /workspace");
    }

    @Test
    void doHydrateWorkspaceShouldUntarArchive() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);
        // tar 包内容（任意字节）经 base64 写入 /tmp 后解压
        var archive = new ByteArrayInputStream("fake-tar-content".getBytes(StandardCharsets.UTF_8));

        sandbox.hydrateWorkspace(archive);

        verify(osb.files()).write(anyList());
        verify(osb.commands()).run(contains("base64 -d /tmp/workspace.tar.b64 | tar xf - -C /workspace"));
    }

    @Test
    void doPersistWorkspaceShouldDecodeBase64Tar() throws Exception {
        var tarBytes = "tar-data".getBytes(StandardCharsets.UTF_8);
        var b64 = Base64.getEncoder().encodeToString(tarBytes);
        var osb = mockOsb(mockExecution(0, b64, ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var stream = sandbox.persistWorkspace();
        var restored = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

        assertEquals("tar-data", restored);
        verify(osb.commands()).run("tar cf - -C /workspace . | base64");
    }

    @Test
    void doPersistWorkspaceShouldThrowOnTarFailure() throws Exception {
        var osb = mockOsb(mockExecution(1, "", "tar error"));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        assertThrows(io.agentscope.harness.agent.sandbox.SandboxException.class,
            sandbox::persistWorkspace);
    }

    @Test
    void stopShouldSyncBackWithLastUserId() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var sync = mock(WorkspaceSyncService.class);
        var sandbox = new OpenSandbox(state(), osb, options(), null, sync);

        // 先执行命令记录 userId，再 stop 触发回写
        sandbox.exec(RuntimeContext.builder().userId("user-alice").build(), "echo x", 30);
        sandbox.stop();

        verify(sync).syncBack("user-alice", osb);
    }

    @Test
    void stopShouldNotSyncWithoutUserId() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var sync = mock(WorkspaceSyncService.class);
        var sandbox = new OpenSandbox(state(), osb, options(), null, sync);

        sandbox.stop();

        verify(sync, never()).syncBack(anyString(), any());
    }

    @Test
    void firstExecShouldInjectRuntimeFilesFromKV() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var reader = mock(WorkspaceReader.class);
        when(reader.readRuntimeFiles("user-bob")).thenReturn(
            Map.of("MEMORY.md", "memory-content".getBytes(StandardCharsets.UTF_8)));
        var sandbox = new OpenSandbox(state(), osb, options(), reader, null);

        sandbox.exec(RuntimeContext.builder().userId("user-bob").build(), "echo x", 30);

        verify(reader).readRuntimeFiles("user-bob");
        // reader 为 mock：验证注入委托被调用（byte[] 内容用 anyMap 匹配）
        verify(reader).injectToSandbox(eq(osb), anyMap());
    }

    @Test
    void runtimeFilesShouldInjectOnlyOnce() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var reader = mock(WorkspaceReader.class);
        when(reader.readRuntimeFiles("u")).thenReturn(Map.of());
        var sandbox = new OpenSandbox(state(), osb, options(), reader, null);
        var ctx = RuntimeContext.builder().userId("u").build();

        sandbox.exec(ctx, "echo 1", 30);
        sandbox.exec(ctx, "echo 2", 30);
        sandbox.exec(ctx, "echo 3", 30);

        verify(reader, times(1)).readRuntimeFiles("u");
    }

    @Test
    void injectionFailureShouldNotBlockExec() throws Exception {
        var osb = mockOsb(mockExecution(0, "ok", ""));
        var reader = mock(WorkspaceReader.class);
        when(reader.readRuntimeFiles("u")).thenThrow(new RuntimeException("kv down"));
        var sandbox = new OpenSandbox(state(), osb, options(), reader, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), "echo ok", 30);

        assertTrue(result.ok());
        assertEquals("ok", result.stdout());
    }

    @Test
    void stopShouldSyncBackWithSessionIdFallbackWhenUserIdBlank() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var sync = mock(WorkspaceSyncService.class);
        var sandbox = new OpenSandbox(state(), osb, options(), null, sync);

        // Channel 路径 userId 为空（实测 state_data.user_id=""），应降级用 sessionId
        sandbox.exec(RuntimeContext.builder().sessionId("debug-user:sess-1").build(), "echo x", 30);
        sandbox.stop();

        verify(sync).syncBack("debug-user:sess-1", osb);
    }

    @Test
    void firstExecShouldInjectWithSessionIdFallback() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var reader = mock(WorkspaceReader.class);
        when(reader.readRuntimeFiles("debug-user:sess-2")).thenReturn(Map.of());
        var sandbox = new OpenSandbox(state(), osb, options(), reader, null);

        sandbox.exec(RuntimeContext.builder().sessionId("debug-user:sess-2").build(), "echo x", 30);

        verify(reader).readRuntimeFiles("debug-user:sess-2");
    }

    // ---------- 上传注入（injectPendingUploads）：execd 原样落盘语义 + 同名唯一化 ----------

    private static FileAssetStore mockAssetStore() {
        var store = mock(FileAssetStore.class);
        when(store.listWorkspacePaths(anyString(), anyString())).thenReturn(List.of());
        return store;
    }

    private static FileStorage mockStorage(byte[] bytes) throws Exception {
        var storage = mock(FileStorage.class);
        when(storage.read(anyString())).thenReturn(new ByteArrayInputStream(bytes));
        return storage;
    }

    /** 提取 files().write 收到的 WriteEntry.data 原始字节（byte[] 形态） */
    private static byte[] capturedData(Sandbox osb) {
        var captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(osb.files()).write(captor.capture());
        var entry = (WriteEntry) captor.getValue().get(0);
        assertInstanceOf(byte[].class, entry.getData(), "data 必须是原始字节（byte[]），String 会被 execd 原样落盘成 base64 文本");
        return (byte[]) entry.getData();
    }

    @Test
    void injectUploadsShouldWriteRawBytesNotBase64() throws Exception {
        // 回归钉子：execd files API 按提交字节原样落盘（byte[] → octet-stream），
        // 不做 base64 解码——旧实现传 base64 字符串，沙箱内二进制文件全部损坏
        var raw = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, (byte) 0xFF, 0x10}; // zip magic + 二进制
        var store = mockAssetStore();
        when(store.listPending("u1", "u1")).thenReturn(List.of(asset("f1", "sample.docx")));
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null, null, store, mockStorage(raw));
        sandbox.setUserKey("u1");

        sandbox.injectPendingUploads();

        var written = capturedData(osb);
        assertArrayEquals(raw, written, "落盘字节必须与上传原始字节逐位一致");
        verify(osb.files()).write(anyList());
        verify(store).updateWorkspacePath("f1", "uploads/sample.docx");
        verify(store).markInjected(List.of("f1"));
    }

    @Test
    void injectUploadsShouldDeduplicateSameNameAcrossTurns() throws Exception {
        // 回归钉子：同名上传注入同一路径会静默覆盖（file_asset 多行 workspace_path 相同）。
        // 第二 turn 注入第二个同名文件时，须按历史注入记录追加 _1 后缀
        var store = mockAssetStore();
        when(store.listWorkspacePaths("u2", "u2")).thenReturn(List.of("uploads/report.docx"));
        when(store.listPending("u2", "u2")).thenReturn(List.of(asset("f2", "report.docx")));
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null, null, store, mockStorage(new byte[]{1}));
        sandbox.setUserKey("u2");

        sandbox.injectPendingUploads();

        var captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(osb.files()).write(captor.capture());
        var entry = (WriteEntry) captor.getValue().get(0);
        assertEquals("uploads/report_1.docx", entry.getPath(), "同名须唯一化为 _1 后缀，防覆盖历史文件");
    }

    @Test
    void injectUploadsShouldDeduplicateWithinSameBatch() throws Exception {
        // 同一批次两份同名文件：第二份也得避开第一份刚分配的路径
        var store = mockAssetStore();
        when(store.listWorkspacePaths("u3", "u3")).thenReturn(List.of());
        when(store.listPending("u3", "u3")).thenReturn(List.of(
            asset("a", "plan.docx"), asset("b", "plan.docx")));
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null, null, store, mockStorage(new byte[]{1}));
        sandbox.setUserKey("u3");

        sandbox.injectPendingUploads();

        var captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(osb.files(), times(2)).write(captor.capture());
        var paths = captor.getAllValues().stream()
            .map(l -> ((WriteEntry) l.get(0)).getPath()).toList();
        assertEquals(List.of("uploads/plan.docx", "uploads/plan_1.docx"), paths);
    }

    private static FileAssetStore.FileAsset asset(String id, String fileName) {
        return new FileAssetStore.FileAsset(id, "u", null, null, fileName, null,
            "application/octet-stream", 1, "local", "upload/x/" + id, "upload", "pending", null);
    }
}
