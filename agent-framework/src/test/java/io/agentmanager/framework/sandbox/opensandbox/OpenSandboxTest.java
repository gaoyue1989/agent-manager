package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxConnectionException;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxError;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.ExecutionLogs;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.services.Commands;
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.service.FileAssetStore;
import io.agentmanager.framework.service.SkillManageService;
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
        when(store.findUserIdBySession("gw-3f20f08c5499")).thenReturn(null);
        when(store.findUserIdBySession("front-sid")).thenReturn("alice");
        spec.setSessionKeyResolver(new io.agentmanager.framework.service.SessionKeyResolver(store));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null, spec);

        var ctx = RuntimeContext.builder().sessionId("gw-3f20f08c5499").userId("front-sid").build();
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
    void doHydrateWorkspaceShouldGzipAndUntarViaUniqueTempFile() throws Exception {
        // 回归钉子 A：临时文件唯一化——并发 hydrate 共用固定 /tmp/workspace.tar.b64
        // 会互相 rm/覆盖（chmod no such file → 写入失败 → 重复传全量投影）
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);
        var archive = new ByteArrayInputStream("fake-tar-content".getBytes(StandardCharsets.UTF_8));

        sandbox.hydrateWorkspace(archive);

        var captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(osb.files()).write(captor.capture());
        var entry = (WriteEntry) captor.getValue().get(0);
        assertTrue(entry.getPath().startsWith("/tmp/workspace."),
            "临时文件必须唯一化（/tmp/workspace.{uuid}.tar.gz），不能共用固定路径");
        assertTrue(entry.getPath().endsWith(".tar.gz"));
        // 回归钉子 B：写入的是 gzip 压缩后的 base64 文本，解压命令链 tar xzf
        var b64 = (String) entry.getData();
        var gunzipped = new String(new java.util.zip.GZIPInputStream(
            new ByteArrayInputStream(Base64.getDecoder().decode(b64))).readAllBytes(),
            StandardCharsets.UTF_8);
        assertEquals("fake-tar-content", gunzipped, "压缩传输必须无损还原归档内容");
        verify(osb.commands()).run(contains("tar xzf - -C /workspace"));
        verify(osb.commands()).run(contains("&& rm " + entry.getPath()));
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

        // 先执行命令记录 userId，再 stop 触发回写。
        // state() 未设 sandboxId → gate=null（合理降级：无沙箱代元数据时不做快照差分）
        sandbox.exec(RuntimeContext.builder().userId("user-alice").build(), "echo x", 30);
        sandbox.stop();

        verify(sync).syncBack(eq("user-alice"), eq(osb), isNull());
    }

    @Test
    void stopShouldNotSyncWithoutUserId() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var sync = mock(WorkspaceSyncService.class);
        var sandbox = new OpenSandbox(state(), osb, options(), null, sync);

        sandbox.stop();

        verify(sync, never()).syncBack(anyString(), any(), any());
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

        verify(sync).syncBack(eq("debug-user:sess-1"), eq(osb), isNull());
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
        when(store.listWorkspacePaths(anyString())).thenReturn(List.of());
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
        sandbox.setSessionId("u1");

        sandbox.injectPendingUploads();

        var written = capturedData(osb);
        assertArrayEquals(raw, written, "落盘字节必须与上传原始字节逐位一致");
        verify(osb.files()).write(anyList());
        verify(store).updateWorkspacePath("f1", "uploads/sample.docx");
        verify(store).markInjected(List.of("f1"));
    }

    @Test
    void injectUploadsShouldHonorReservedWorkspacePath() throws Exception {
        // 回归钉子（2026-11-14 真机实测复现）：路径分配先行于提示构造（chat 请求时
        // assignWorkspacePaths），注入必须按已预留 workspace_path 原样写入——即便历史集合
        // 含同值路径也不得改名。旧实现注入时按历史撞名重命名 → 提示路径（请求时冻结）与
        // 磁盘实际路径漂移，agent 按提示读文件必 file_not_found（同名二次上传/容器重建
        // 重放即触发）。
        var store = mockAssetStore();
        when(store.listWorkspacePaths("u2")).thenReturn(List.of("uploads/report.docx"));
        when(store.listPending("u2", "u2")).thenReturn(List.of(reserved("f2", "report.docx", "uploads/report.docx")));
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null, null, store, mockStorage(new byte[]{1}));
        sandbox.setUserKey("u2");
        sandbox.setSessionId("u2");

        sandbox.injectPendingUploads();

        var captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(osb.files()).write(captor.capture());
        var entry = (WriteEntry) captor.getValue().get(0);
        assertEquals("uploads/report.docx", entry.getPath(), "注入必须按预留路径原样写入，保证提示路径=磁盘路径");
        verify(store).updateWorkspacePath("f2", "uploads/report.docx");
        verify(store).markInjected(List.of("f2"));
    }

    @Test
    void injectUploadsShouldAllocateAndPersistForLegacyRowsWithoutPath() throws Exception {
        // 无预留路径的遗留 pending 行（上传后从未发起对话）：注入时按历史撞名分配并回写，
        // 保证后续轮次提示路径与磁盘一致
        var store = mockAssetStore();
        when(store.listWorkspacePaths("u3")).thenReturn(List.of("uploads/plan.docx"));
        when(store.listPending("u3", "u3")).thenReturn(List.of(asset("f3", "plan.docx")));
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null, null, store, mockStorage(new byte[]{1}));
        sandbox.setUserKey("u3");
        sandbox.setSessionId("u3");

        sandbox.injectPendingUploads();

        var captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(osb.files()).write(captor.capture());
        var entry = (WriteEntry) captor.getValue().get(0);
        assertEquals("uploads/plan_1.docx", entry.getPath());
        verify(store).updateWorkspacePath("f3", "uploads/plan_1.docx");
    }

    @Test
    void injectUploadsShouldDeduplicateWithinSameBatch() throws Exception {
        // 同一批次两份无预留的同名文件：第二份也得避开第一份刚分配的路径
        var store = mockAssetStore();
        when(store.listWorkspacePaths("u4")).thenReturn(List.of());
        when(store.listPending("u4", "u4")).thenReturn(List.of(
            asset("a", "plan.docx"), asset("b", "plan.docx")));
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null, null, store, mockStorage(new byte[]{1}));
        sandbox.setUserKey("u4");
        sandbox.setSessionId("u4");

        sandbox.injectPendingUploads();

        var captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(osb.files(), times(2)).write(captor.capture());
        var paths = captor.getAllValues().stream()
            .map(l -> ((WriteEntry) l.get(0)).getPath()).toList();
        assertEquals(List.of("uploads/plan.docx", "uploads/plan_1.docx"), paths);
        verify(store).updateWorkspacePath("a", "uploads/plan.docx");
        verify(store).updateWorkspacePath("b", "uploads/plan_1.docx");
    }

    @Test
    void injectUploadsShouldSkipWhenNoSessionBound() throws Exception {
        // session 单条件过滤（issue #345）：无会话键时跳过注入（user 维度兜底是跨 session
        // 泄漏根源）——行保持 pending，下 turn 身份齐备再注入
        var store = mockAssetStore();
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null, null, store, mockStorage(new byte[]{1}));
        sandbox.setUserKey("u1");

        sandbox.injectPendingUploads();

        verify(store, never()).listPending(anyString(), anyString());
        verify(osb.files(), never()).write(anyList());
    }

    @Test
    void injectUploadsShouldFallBackToSpecPendingSessionId() throws Exception {
        // 实例未绑定（acquire 先于 bindUserKey 的窗口）→ spec ThreadLocal 兜底
        var store = mockAssetStore();
        when(store.listPending("sess-9", "u1")).thenReturn(List.of(asset("f9", "a.txt")));
        var spec = new OpenSandboxFilesystemSpec();
        spec.setPendingSessionId("sess-9");
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null, spec, store, mockStorage(new byte[]{1}));
        sandbox.setUserKey("u1");

        sandbox.injectPendingUploads();

        verify(store).listPending("sess-9", "u1");
        verify(store).markInjected(List.of("f9"));
    }

    @Test
    void invalidatePendingUploadMemoShouldForceRecheckAndSteadyStateStaysShortCircuited() throws Exception {
        // issue #413 语义钉子：查无 pending 置位备忘（pendingEmptyChecked）后——
        // ① 不失效备忘：同实例重复调用不重查（P2-16 稳态短路，实例存活期=单 call 的
        //   file_asset 查询节流不得回归）；
        // ② 失效备忘（middleware 换代 reset 确有行回滚后调用）：下一次调用真实重查重注
        //   （wrapReused/resume 换代后历史上传件必须重新注入）
        var store = mockAssetStore();
        when(store.listPending("u9", "u9")).thenReturn(List.of());
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null, null, store, mockStorage(new byte[]{1}));
        sandbox.setUserKey("u9");
        sandbox.setSessionId("u9");

        sandbox.injectPendingUploads();
        verify(store, times(1)).listPending("u9", "u9");

        // 对照组：不 invalidate，稳态短路保持
        sandbox.injectPendingUploads();
        verify(store, times(1)).listPending("u9", "u9");
        verify(osb.files(), never()).write(anyList());

        // 换代 reset 有行回滚 → invalidate → 真实重查重注
        when(store.listPending("u9", "u9")).thenReturn(List.of(asset("f9", "memo.txt")));
        sandbox.invalidatePendingUploadMemo();
        sandbox.injectPendingUploads();
        verify(store, times(2)).listPending("u9", "u9");
        verify(osb.files()).write(anyList());
        verify(store).markInjected(List.of("f9"));
    }

    @Test
    void injectUploadsShouldSkipWhenNoUserKeyBound() throws Exception {
        // 注入前身份齐备校验（issue #411）：listPending 双条件的 userKey 维度取自实例绑定
        // （bindUserKey / middleware setUserKey）——缺失即无法与 file_asset.user_key 比对，
        // 必须跳过注入（行保持 pending，不置 pendingEmptyChecked，下 turn 身份齐备后重试）。
        // 与 injectUploadsShouldSkipWhenNoSessionBound 对偶：绝不回退 user 维度兜底
        var store = mockAssetStore();
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null, null, store, mockStorage(new byte[]{1}));
        sandbox.setSessionId("sess-10");

        sandbox.injectPendingUploads();

        verify(store, never()).listPending(anyString(), anyString());
        verify(osb.files(), never()).write(anyList());
    }

    private static FileAssetStore.FileAsset asset(String id, String fileName) {
        return new FileAssetStore.FileAsset(id, "u", null, null, fileName, null,
            "application/octet-stream", 1, "local", "upload/x/" + id, "upload", "pending", null);
    }

    private static FileAssetStore.FileAsset reserved(String id, String fileName, String wsPath) {
        return new FileAssetStore.FileAsset(id, "u2", null, null, fileName, wsPath,
            "application/octet-stream", 1, "local", "upload/x/" + id, "upload", "pending", null);
    }

    // ---------- scoped .skills-cache 投放（方案 1：只投当前会话 scope + 清理容器内其它 scope） ----------

    /** 生产形态投影 spec：投影根不含 .skills-cache（AgentScopeConfig.workspaceProjectionRoots 收窄）。 */
    private static OpenSandboxState projectedStateNoSkillCache(java.nio.file.Path sourceRoot) {
        var s = new OpenSandboxState();
        var ws = new io.agentscope.harness.agent.sandbox.WorkspaceSpec();
        ws.setRoot("/workspace");
        var entry = new io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry();
        entry.setSourceRoot(sourceRoot.toAbsolutePath().normalize().toString());
        entry.setIncludeRoots(List.of("AGENTS.md", "skills", "subagents", "knowledge"));
        ws.setEntries(Map.of("__workspace_projection__", entry));
        s.setWorkspaceSpec(ws);
        return s;
    }

    private static void writeHostSkill(java.nio.file.Path host, String scope, String name) throws Exception {
        var dir = host.resolve(".skills-cache").resolve(scope).resolve("oaf-package").resolve(name);
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\n---\nhi");
    }

    @Test
    void scopedSkillCacheSkipsWhenScopeUnknown() throws Exception {
        // 身份未知（ctx null 且未绑定 scope）：不投放、不清理（下次 exec 重试）
        var host = java.nio.file.Files.createTempDirectory("scoped-unknown");
        writeHostSkill(host, "sess-A", "demo-a");
        var osb = mockOsb(mockExecution(0, "ok", ""));
        var sandbox = new OpenSandbox(projectedStateNoSkillCache(host), osb, options(), null, null);
        sandbox.start();

        sandbox.hydrateForCall(null);

        verify(osb.commands(), never()).run(contains(".skills-cache"));
    }

    // ---------- exec 数据面故障感知（oa-1110-repro-fixes-design §2.3d） ----------

    private static RuntimeException gateway502() {
        return new com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException(
            "502 Bad Gateway {\"message\":\"Could not connect to the backend sandbox\"}",
            null, 502, new com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxError(
                "gateway_unreachable", "Could not connect to the backend sandbox"));
    }

    @Test
    void execDataplaneFailuresBelowThresholdPropagateOriginal() {
        var osb = mockOsb(mockExecution(0, "", ""));
        when(osb.commands().run(anyString())).thenThrow(gateway502());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var ex = assertThrows(Exception.class,
            () -> sandbox.exec(RuntimeContext.builder().userId("u").build(), "echo hi", 30));

        assertTrue(ex.getMessage().contains("502 Bad Gateway"),
            "阈值内保持原始异常透传（容忍瞬时抖动）：" + ex.getMessage());
        // 连续失败 1 次 < 默认阈值 3：命令未改写、无健康标记日志面之外的副作用
        verify(osb.commands(), times(1)).run(anyString());
    }

    @Test
    void execDataplaneFailuresAtThresholdBecomeReadable() throws Exception {
        // 链式单次打桩（thenThrow×3 → thenReturn → thenThrow），规避 throwing 桩重打桩触发旧桩
        var commands = mock(Commands.class);
        var osb = mock(Sandbox.class);
        when(osb.commands()).thenReturn(commands);
        var gw = gateway502();
        when(commands.run(anyString()))
            .thenThrow(gw, gw, gw)                    // 连续 3 次（第 3 次达阈值改写）
            .thenReturn(mockExecution(0, "ok", ""))   // 恢复成功 → 计数清零
            .thenThrow(gw);                           // 再失败 → 从头计（原样透传）
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var ctx = RuntimeContext.builder().userId("u").build();
        assertThrows(Exception.class, () -> sandbox.exec(ctx, "echo 1", 30));
        assertThrows(Exception.class, () -> sandbox.exec(ctx, "echo 2", 30));
        var third = assertThrows(Exception.class, () -> sandbox.exec(ctx, "echo 3", 30));

        // 达阈值（默认 3）：异常消息可读化，引导模型停止重试风暴并告知用户（复现 118×502 的根治）
        assertTrue(third.getMessage().contains("已连续 3 次命令失败"),
            "阈值达限应改写为可读文案：" + third.getMessage());
        assertTrue(third.getMessage().contains("停止重试"), third.getMessage());
        assertTrue(third.getMessage().contains("502 Bad Gateway"), "保留原始错误：" + third.getMessage());
        verify(commands, times(3)).run(anyString());

        // 阈值后恢复成功 → 计数清零，下次失败从头计
        var ok = sandbox.exec(ctx, "echo ok", 30);
        assertTrue(ok.ok());
        var fresh = assertThrows(Exception.class, () -> sandbox.exec(ctx, "echo 4", 30));
        assertTrue(fresh.getMessage().contains("502 Bad Gateway"),
            "恢复后计数应清零，单次失败不触发改写：" + fresh.getMessage());
    }



    // ---------- SandboxFileTransfer 单文件直传（替代「单文件写入 → 全量 hydrate」退化） ----------

    @Test
    void shouldAdvertiseFileTransferForFrameworkPaths() {
        // 框架文件（sessions.json/memory/会话 jsonl/outputs）走单文件直传：
        // 未实现该接口时 SDK 把每个单文件写入退化成一次全量 tar hydrate（30M 技能下每写
        // 一个小文件传 46M），实测单容器重复 10 次全量传输
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        assertTrue(sandbox.supportsFileTransfer("agents/E2E Test Agent/sessions/sessions.json"));
        assertTrue(sandbox.supportsFileTransfer("MEMORY.md"));
        assertTrue(sandbox.supportsFileTransfer("memory/2026-10-14.md"));
        assertTrue(sandbox.supportsFileTransfer("agents/agent/sessions/gw-x.jsonl"));
        assertTrue(sandbox.supportsFileTransfer("outputs/result.zip"));
    }

    @Test
    void shouldRejectFileTransferForSkillCacheProjection() {
        // .skills-cache 必须排除：技能进容器由受控链路负责（start 投影 + refreshProjectionIfStale
        // 的 hash 一致性），直传绕过会让 E-SKILL-4 的 stage 时序竞态重新出现且无 hash 兜底
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        assertFalse(sandbox.supportsFileTransfer(".skills-cache/u1/oaf-package/demo-skill/SKILL.md"));
        assertFalse(sandbox.supportsFileTransfer("sub/.skills-cache/x/f.md"), "嵌套 .skills-cache 同样排除");
        assertFalse(sandbox.supportsFileTransfer((String) null), "防御：null path 不直传");
    }

    @Test
    void uploadFileShouldWriteRawBytesToSamePath() throws Exception {
        // 直传语义：byte[] 原样落盘（execd 不做 base64 解码，同 §6.2.3 上传注入语义）、
        // 路径保持 SDK 给定的工作区相对路径
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);
        var raw = new byte[]{0x7B, 0x22, 0x6B, 0x22, 0x3A, 0x31, 0x7D}; // {"k":1}

        sandbox.uploadFile("agents/a/sessions/sessions.json", raw);

        var captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(osb.files()).write(captor.capture());
        var entry = (WriteEntry) captor.getValue().get(0);
        assertEquals("agents/a/sessions/sessions.json", entry.getPath());
        assertArrayEquals(raw, (byte[]) entry.getData());
    }

    @Test
    void uploadFileFailureShouldSurfaceToCaller() throws Exception {
        // 直传失败必须抛出（SDK uploadFiles 捕获后记 fail 响应并走下一文件），
        // 不得静默吞掉——静默会让框架误以为文件已写入
        var osb = mock(Sandbox.class);
        var files = mock(Filesystem.class);
        when(osb.files()).thenReturn(files);
        doThrow(new RuntimeException("execd down")).when(files).write(anyList());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        assertThrows(Exception.class,
            () -> sandbox.uploadFile("MEMORY.md", new byte[]{1}));
    }

    @Test
    void downloadFileShouldReadViaExecd() throws Exception {
        var osb = mock(Sandbox.class);
        var files = mock(Filesystem.class);
        when(osb.files()).thenReturn(files);
        when(files.readByteArray("MEMORY.md")).thenReturn("hello".getBytes(StandardCharsets.UTF_8));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), sandbox.downloadFile("MEMORY.md"));
    }

    // ---------- L4 revision 门控（设计 ③，SANDBOX_SKILL_REVISION_GATE）----------

    /**
     * 复用 spec（gate 表挂在 spec 上，跨实例持久=生产形态）；每次新 OpenSandbox 实例模拟
     * 一次新 acquire——实例级幂等标志（userSkillsMaterialized）随实例归零，门控本身经 gate 生效。
     */
    private static OpenSandbox gatedInstance(OpenSandboxFilesystemSpec spec, WorkspaceReader reader,
                                             Sandbox osb, boolean revisionGate) {
        var st = state();
        st.setSandboxId("sbx-gate-1"); // 生产由 create 写入；测试显式设置让 gate 生效
        var sandbox = new OpenSandbox(st, osb, options(), reader, null, spec,
            null, null, revisionGate, true);
        sandbox.setUserKey("alice");
        return sandbox;
    }

    @Test
    void revisionGateShouldMaterializeOnFirstAcquireThenSkipOnUnchangedRevision() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var reader = mock(WorkspaceReader.class);
        var spec = new OpenSandboxFilesystemSpec();
        when(reader.readUserSkillRevision("alice")).thenReturn("rev-1");
        when(reader.materializeUserSkills(any(), eq("alice"), any())).thenReturn(2);

        // 第一次 acquire：必须全量物化，物化后回填 gate
        gatedInstance(spec, reader, osb, true).materializeUserSkills();
        verify(reader, times(1)).materializeUserSkills(any(), eq("alice"), any());
        var gate = spec.skillSyncGate("alice", "sbx-gate-1");
        assertNotNull(gate);
        assertTrue(gate.lastMaterialized());
        assertEquals("rev-1", gate.revision());

        // 第二次 acquire（新实例，revision 未变）：门控跳过（只多一次 revision get）
        gatedInstance(spec, reader, osb, true).materializeUserSkills();
        verify(reader, times(1)).materializeUserSkills(any(), eq("alice"), any());
    }

    @Test
    void revisionGateShouldRematerializeWhenRevisionChanges() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var reader = mock(WorkspaceReader.class);
        var spec = new OpenSandboxFilesystemSpec();
        when(reader.readUserSkillRevision("alice")).thenReturn("rev-1", "rev-2");
        when(reader.materializeUserSkills(any(), eq("alice"), any())).thenReturn(2);

        gatedInstance(spec, reader, osb, true).materializeUserSkills();
        // 管理面写入了新技能（revision 变化）→ 第二次 acquire 必须重新物化
        gatedInstance(spec, reader, osb, true).materializeUserSkills();
        verify(reader, times(2)).materializeUserSkills(any(), eq("alice"), any());
    }

    @Test
    void revisionGateShouldFallbackWhenRevisionUnreadable() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var reader = mock(WorkspaceReader.class);
        var spec = new OpenSandboxFilesystemSpec();
        // revision 读不到（KV 异常/键不存在）→ 门控分支跳过（gate 未置位）、保守走全量物化；
        // 物化后回填读 revision（mock 返回 rev-1），gate 记最新值
        when(reader.readUserSkillRevision("alice")).thenReturn("rev-1");
        when(reader.materializeUserSkills(any(), eq("alice"), any())).thenReturn(2);

        gatedInstance(spec, reader, osb, true).materializeUserSkills();
        verify(reader, times(1)).materializeUserSkills(any(), eq("alice"), any());
        var gate = spec.skillSyncGate("alice", "sbx-gate-1");
        assertEquals("rev-1", gate.revision(), "物化后应回填最新 revision");
    }

    @Test
    void revisionGateDisabledShouldAlwaysMaterialize() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var reader = mock(WorkspaceReader.class);
        var spec = new OpenSandboxFilesystemSpec();
        when(reader.readUserSkillRevision("alice")).thenReturn("rev-1");
        when(reader.materializeUserSkills(any(), eq("alice"), any())).thenReturn(2);

        gatedInstance(spec, reader, osb, false).materializeUserSkills();
        gatedInstance(spec, reader, osb, false).materializeUserSkills();
        verify(reader, times(2)).materializeUserSkills(any(), eq("alice"), any());
    }

    @Test
    void syncBackShouldPassGateWhenSnapshotDiffEnabled() throws Exception {
        // stop 回写：开关开 → 传 gate（快照差分）；开关关 → 传 null（全量读）
        var osb = mockOsb(mockExecution(0, "", ""));
        var sync = mock(WorkspaceSyncService.class);
        var spec = new OpenSandboxFilesystemSpec();
        var st = state();
        st.setSandboxId("sbx-diff-1");
        var sandbox = new OpenSandbox(st, osb, options(), null, sync, spec,
            null, null, true, true);
        sandbox.exec(RuntimeContext.builder().userId("user-alice").build(), "echo x", 30);
        sandbox.stop();
        verify(sync).syncBack(eq("user-alice"), eq(osb), any(SkillSyncGate.class));

        var sync2 = mock(WorkspaceSyncService.class);
        var st2 = state();
        st2.setSandboxId("sbx-diff-2");
        var sandbox2 = new OpenSandbox(st2, osb, options(), null, sync2, spec,
            null, null, true, false);
        sandbox2.exec(RuntimeContext.builder().userId("user-bob").build(), "echo x", 30);
        sandbox2.stop();
        verify(sync2).syncBack(eq("user-bob"), eq(osb), isNull());
    }

    // ---------- 稳态记账 Pod 级短路（跨实例） ----------

    /**
     * 生产 acquire 语义：每 call 新建 OpenSandbox 包装实例。稳态记账记在 spec（Pod 级）后，
     * <b>新实例</b>在同容器代、同 scope、端点版本未变时必须命中稳态短路（零收敛 exec）。
     */
    @Test
    void farmSteadySkipSurvivesInstanceRecreation() throws Exception {
        var host = java.nio.file.Files.createTempDirectory("farm-pod-cache");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-pod-cache-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var osb = farmOsbTracking(svc);
        var spec = new OpenSandboxFilesystemSpec();
        var st1 = projectedStateNoSkillCache(host);
        st1.setSandboxId("sbx-farm-pod-1");
        var call1 = new OpenSandbox(st1, osb, farmOptions(svc, null, null), null, null, spec);
        bindFarmSession(call1, "sess-A");
        call1.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 1", 30);
        call1.stop();
        assertEquals(1, farmConvergeCommands(osb).size(), "首轮收敛");

        // 新包装实例（同 sandboxId=同容器代，模拟生产 resume）：
        var st2 = projectedStateNoSkillCache(host);
        st2.setSandboxId("sbx-farm-pod-1");
        var call2 = new OpenSandbox(st2, osb, farmOptions(svc, null, null), null, null, spec);
        bindFarmSession(call2, "sess-A");
        call2.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 2", 30);
        call2.stop();
        assertEquals(1, farmConvergeCommands(osb).size(),
            "指纹未变 + 本代已收敛同 scope → 新实例不得重收敛（稳态 0 exec）");
    }

    /** 同容器代换 scope（池化借出复用/同用户双会话）：换 scope 必收敛新 scope 并清理容器内旧 scope */
    @Test
    void farmScopeSwitchOnSameGenerationShouldReconvergeNewScope() throws Exception {
        var host = java.nio.file.Files.createTempDirectory("farm-scope-switch");
        writeHostSkill(host, "sess-A", "demo-a");
        writeHostSkill(host, "sess-B", "demo-b");
        var cfg = java.nio.file.Files.createTempDirectory("farm-scope-switch-cfg");
        bundleSkill(cfg, "demo-a");
        bundleSkill(cfg, "demo-b");
        var svc = bundleService(cfg);
        var osb = farmOsbTracking(svc);
        var spec = new OpenSandboxFilesystemSpec();
        var st1 = projectedStateNoSkillCache(host);
        st1.setSandboxId("sbx-farm-switch-1");
        var callA = new OpenSandbox(st1, osb, farmOptions(svc, null, null), null, null, spec);
        bindFarmSession(callA, "sess-A");
        callA.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 1", 30);
        callA.stop();

        // 同 sandboxId 换 scope：sess-B 不得命中稳态（已收敛 scope 表按代记单 scope）
        var st2 = projectedStateNoSkillCache(host);
        st2.setSandboxId("sbx-farm-switch-1");
        var callB = new OpenSandbox(st2, osb, farmOptions(svc, null, null), null, null, spec);
        bindFarmSession(callB, "sess-B");
        callB.exec(RuntimeContext.builder().userId("sess-B").build(), "echo 2", 30);
        callB.stop();

        var converges = farmConvergeCommands(osb);
        assertEquals(2, converges.size(), "同代换 scope 必须收敛新 scope");
        assertTrue(converges.get(1).contains("ln -s '" + FARM_STORE + "/CURRENT/demo-b' '"
            + "/workspace/.skills-cache/sess-B/oaf-package/demo-b'"), converges.get(1));
        // prune 在收敛 exec 中执行且按当前 scope 保留
        verify(osb.commands(), atLeastOnce()).run(contains("! -name 'sess-B'"));
    }

    /** 缺口①：downloadFile（SDK SandboxFileTransfer 下载）路径命中 .skills-cache 时触发延迟收敛 */
    @Test
    void downloadFileReferencingSkillsCacheTriggersDeferredFarmConverge() throws Exception {
        var host = java.nio.file.Files.createTempDirectory("farm-dl");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-dl-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var osb = farmOsbTracking(svc);
        var st = projectedStateNoSkillCache(host);
        st.setSandboxId("sbx-farm-dl-1");
        var sandbox = new OpenSandbox(st, osb, farmOptions(svc, null, null), null, null,
            new OpenSandboxFilesystemSpec());
        bindFarmSession(sandbox, "sess-A");

        // 先 download 非 .skills-cache 路径：不触发判定/收敛
        sandbox.downloadFile("outputs/plain.txt");
        verify(osb.commands(), never()).run(anyString());

        // 再 download .skills-cache 路径：延迟语义触发判定 + 收敛后才读
        sandbox.downloadFile(".skills-cache/sess-A/oaf-package/demo-a/SKILL.md");
        assertEquals(1, farmConvergeCommands(osb).size(), ".skills-cache 下载必须先收敛再读");
    }

    // ---------- exec→execd 翻译异常分型（issue #296）：仅 execd files API 404 = 缺失哨兵，
    // 其余异常（5xx/连接失败/超时）向上抛、交 tryTranslateExec 外层 catch 回落真 exec ----------
    // 用例统一经 sandbox.exec(ctx, 探测模板, 30) 走真实 doExec→translate→回落链路。

    /** read 探测模板（P_READ 同形，单空格形态） */
    private static final String READ_PROBE =
        "if [ ! -f 'AGENTS.md' ]; then echo '__NOT_FOUND__'; elif [ ! -s 'AGENTS.md' ]; then"
            + " echo '__EMPTY__'; else sed -n '1,200p' 'AGENTS.md'; fi";
    /** ls 目录探测模板（P_LS 同形） */
    private static final String LS_PROBE =
        "if [ ! -e 'docs' ]; then echo '__NOT_EXISTS__'; elif [ ! -d 'docs' ]; then echo '__NOT_A_DIR__';"
            + " else for f in 'docs'/*; do if [ -d \"$f\" ]; then mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0);"
            + " printf 'DIR:%s\\t%s\\n' \"$f\" \"$mtime\"; elif [ -f \"$f\" ]; then"
            + " size=$(stat -c '%s' \"$f\" 2>/dev/null || echo 0);"
            + " mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0);"
            + " printf 'FILE:%s\\t%s\\t%s\\n' \"$f\" \"$size\" \"$mtime\"; fi; done; fi";
    /** test -e 存在性探测模板（P_TEST_E 同形） */
    private static final String TEST_E_PROBE = "test -e 'AGENTS.md' && echo yes || echo no";
    /** 工作区根探测模板（P_TEST_D 同形） */
    private static final String TEST_D_PROBE = "test -d /workspace";
    /** glob 探测模板（P_FIND 同形） */
    private static final String FIND_PROBE =
        "find 'tree' -type f -name '*.md' 2>/dev/null | sort | while IFS= read -r f; do"
            + " size=$(stat -c '%s' \"$f\" 2>/dev/null || echo 0);"
            + " mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0);"
            + " printf '%s\\t%s\\t%s\\n' \"$f\" \"$size\" \"$mtime\"; done";
    /** glob 回落命令的 -L 归一化形态（doExec normalizeSymlinkHostileFind 契约：回落真 exec 必带 -L） */
    private static final String FIND_PROBE_L =
        "find -L 'tree' -type f -name '*.md' 2>/dev/null | sort | while IFS= read -r f; do"
            + " size=$(stat -c '%s' \"$f\" 2>/dev/null || echo 0);"
            + " mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0);"
            + " printf '%s\\t%s\\t%s\\n' \"$f\" \"$size\" \"$mtime\"; done";

    /** glob 探测模板参数化形态（P_FIND 同形，root/pattern 可指定的根级/子树 glob 用例构造） */
    private static String findProbeForm(String root, String pattern) {
        return "find '" + root + "' -type f -name '" + pattern + "' 2>/dev/null | sort | while IFS= read -r f; do"
            + " size=$(stat -c '%s' \"$f\" 2>/dev/null || echo 0);"
            + " mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0);"
            + " printf '%s\\t%s\\t%s\\n' \"$f\" \"$size\" \"$mtime\"; done";
    }

    /** ls 目录探测模板参数化形态（P_LS 同形，memory/ 目录合并视图用例构造，issue #498） */
    private static String lsProbeForm(String dir) {
        return "if [ ! -e '" + dir + "' ]; then echo '__NOT_EXISTS__'; elif [ ! -d '" + dir
            + "' ]; then echo '__NOT_A_DIR__'; else for f in '" + dir + "'/*; do "
            + "if [ -d \"$f\" ]; then mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0);"
            + " printf 'DIR:%s\\t%s\\n' \"$f\" \"$mtime\"; elif [ -f \"$f\" ]; then "
            + "size=$(stat -c '%s' \"$f\" 2>/dev/null || echo 0); "
            + "mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0); "
            + "printf 'FILE:%s\\t%s\\t%s\\n' \"$f\" \"$size\" \"$mtime\"; fi; done; fi";
    }

    /** glob 回落命令参数化形态（doExec normalizeSymlinkHostileFind 契约：回落真 exec 必带 -L） */
    private static String findLProbeForm(String root, String pattern) {
        return "find -L '" + root + "' -type f -name '" + pattern + "' 2>/dev/null | sort | while IFS= read -r f; do"
            + " size=$(stat -c '%s' \"$f\" 2>/dev/null || echo 0);"
            + " mtime=$(stat -c '%Y' \"$f\" 2>/dev/null || echo 0);"
            + " printf '%s\\t%s\\t%s\\n' \"$f\" \"$size\" \"$mtime\"; done";
    }

    /** symlink 条目（type 对齐 execd Lstat 语义，OpenSandboxExecTranslateTest 同款） */
    private static EntryInfo symlinkEntry(String path) {
        var epoch = java.time.OffsetDateTime.ofInstant(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC);
        return new EntryInfo(path, 493, "root", "root", 0, epoch, epoch, "symlink");
    }

    /** e2e mock 空 body 404 形态：SDK 对空 body 解析出 UNEXPECTED_RESPONSE（非 FILE_NOT_FOUND） */
    private static SandboxApiException sandboxApi404() {
        return new SandboxApiException("HTTP 404 Not Found", null, 404,
            new SandboxError("UNEXPECTED_RESPONSE", ""));
    }

    private static SandboxApiException sandboxApi500() {
        return new SandboxApiException("500 Internal Server Error", null, 500,
            new SandboxError("UNEXPECTED_RESPONSE", "execd internal error"));
    }

    private static SandboxApiException sandboxApi503() {
        return new SandboxApiException("503 Service Unavailable", null, 503,
            new SandboxError("UNEXPECTED_RESPONSE", "upstream error"));
    }

    private static EntryInfo dirEntry(String path) {
        // EntryInfo（Kotlin）的 modifiedAt/createdAt 非空参数，统一用固定纪元时刻
        var epoch = java.time.OffsetDateTime.ofInstant(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC);
        return new EntryInfo(path, 493, "root", "root", 0, epoch, epoch, "directory");
    }

    private static EntryInfo fileEntry(String path) {
        var epoch = java.time.OffsetDateTime.ofInstant(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC);
        return new EntryInfo(path, 644, "root", "root", 3, epoch, epoch, "file");
    }

    @Test
    void readProbeWith404EmptyBodyShapeShouldMapToNotFoundSentinel() throws Exception {
        // a) e2e mock 空 body 404（error.code != FILE_NOT_FOUND）：status==404 判据必须吃下——
        //    否则缺失探测回落真 exec 会被 mock EXEC_WHITELIST 拒绝（126），X 组回归
        var osb = mockOsb(mockExecution(0, "unused", ""));
        when(osb.files().readByteArray("AGENTS.md")).thenThrow(sandboxApi404());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), READ_PROBE, 30);

        assertEquals(0, result.exitCode());
        assertEquals("__NOT_FOUND__", result.stdout());
        verify(osb.commands(), never()).run(anyString());
    }

    @Test
    void readProbeWith404FileNotFoundCodeShouldMapToNotFoundSentinel() throws Exception {
        // b) FILE_NOT_FOUND code 形态：与判据无关（status==404 即哨兵），两种形态行为一致
        var osb = mockOsb(mockExecution(0, "unused", ""));
        when(osb.files().readByteArray("AGENTS.md")).thenThrow(
            new SandboxApiException("HTTP 404 Not Found", null, 404,
                new SandboxError("FILE_NOT_FOUND", "no such file")));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), READ_PROBE, 30);

        assertEquals(0, result.exitCode());
        assertEquals("__NOT_FOUND__", result.stdout());
        verify(osb.commands(), never()).run(anyString());
    }

    @Test
    void readProbeWith5xxShouldFallBackToRealExec() throws Exception {
        // c) read 探测 + 503：非 404 向上抛 → 回落真 exec，commands.run 收到原探测命令
        var osb = mockOsb(mockExecution(0, "real content", ""));
        when(osb.files().readByteArray("AGENTS.md")).thenThrow(sandboxApi503());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), READ_PROBE, 30);

        assertTrue(result.ok());
        assertEquals("real content", result.stdout(), "结果必须取自真 exec stub（回落生效）");
        verify(osb.commands()).run(READ_PROBE);
    }

    @Test
    void readProbeWithConnectionFailureShouldFallBackToRealExec() throws Exception {
        // d) read 探测 + 连接失败：死沙箱形态（SandboxConnectionException，非 HTTP 404）→ 回落真 exec
        //    （readByteArray 无 checked 异常声明，ConnectException 不能直接桩——SDK 传输层的
        //    连接失败在本层即 SandboxConnectionException）
        var osb = mockOsb(mockExecution(0, "real content", ""));
        when(osb.files().readByteArray("AGENTS.md"))
            .thenThrow(new SandboxConnectionException("connection refused", null, false));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), READ_PROBE, 30);

        assertTrue(result.ok());
        assertEquals("real content", result.stdout());
        verify(osb.commands()).run(READ_PROBE);
    }

    @Test
    void lsProbeWith404ShouldMapToNotExistsAnd502ShouldFallBack() throws Exception {
        // e) ls 探测：404 → __NOT_EXISTS__ 哨兵；502 → 回落真 exec
        var osb404 = mockOsb(mockExecution(0, "", ""));
        when(osb404.files().listDirectory("docs")).thenThrow(sandboxApi404());
        var sandbox404 = new OpenSandbox(state(), osb404, options(), null, null);
        var r404 = sandbox404.exec(RuntimeContext.builder().userId("u").build(), LS_PROBE, 30);

        assertEquals(0, r404.exitCode());
        assertEquals("__NOT_EXISTS__", r404.stdout());
        verify(osb404.commands(), never()).run(anyString());

        var osb = mockOsb(mockExecution(0, "DIR:docs/a\t0", ""));
        when(osb.files().listDirectory("docs")).thenThrow(gateway502());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);
        var r = sandbox.exec(RuntimeContext.builder().userId("u").build(), LS_PROBE, 30);

        assertTrue(r.ok());
        assertEquals("DIR:docs/a\t0", r.stdout(), "结果必须取自真 exec stub（回落生效）");
        verify(osb.commands()).run(LS_PROBE);
    }

    @Test
    void existsProbeBothLayers404ReturnsNoAnd500FallsBack() throws Exception {
        // f) test -e 探测：两层均 404 → 确定缺失 "no"（不回落）；readByteArray 500 → 回落真 test
        var osb404 = mockOsb(mockExecution(0, "", ""));
        when(osb404.files().readByteArray("AGENTS.md")).thenThrow(sandboxApi404());
        when(osb404.files().listDirectory("AGENTS.md")).thenThrow(sandboxApi404());
        var sandbox404 = new OpenSandbox(state(), osb404, options(), null, null);
        var r404 = sandbox404.exec(RuntimeContext.builder().userId("u").build(), TEST_E_PROBE, 30);

        assertEquals(0, r404.exitCode());
        assertEquals("no", r404.stdout());
        verify(osb404.commands(), never()).run(anyString());

        var osb = mockOsb(mockExecution(0, "yes", ""));
        when(osb.files().readByteArray("AGENTS.md")).thenThrow(sandboxApi500());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);
        var r = sandbox.exec(RuntimeContext.builder().userId("u").build(), TEST_E_PROBE, 30);

        assertTrue(r.ok());
        assertEquals("yes", r.stdout());
        verify(osb.commands()).run(TEST_E_PROBE);
    }

    @Test
    void testDirProbeWithBothLayers500FallsBackWithRealExitCode() throws Exception {
        // g) test -d 探测 + 两层 500：回落真 exec 且 exit code 来自真 exec stub
        var osb = mockOsb(mockExecution(1, "", "not a directory"));
        when(osb.files().readByteArray("/workspace")).thenThrow(sandboxApi500());
        when(osb.files().listDirectory("/workspace")).thenThrow(sandboxApi500());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), TEST_D_PROBE, 30);

        assertEquals(1, result.exitCode(), "exit code 必须来自真 exec（回落生效）");
        assertFalse(result.ok());
        verify(osb.commands()).run(TEST_D_PROBE);
    }

    @Test
    void globProbeRoot404ReturnsEmptyAndSubtree503FallsBack() throws Exception {
        // h) glob 探测：根目录 404 → 空输出 exit 0（与真 find 对缺失目录行为一致）；
        //    子目录 503 → 向上抛回落真 find
        var osb404 = mockOsb(mockExecution(0, "", ""));
        when(osb404.files().listDirectory("tree")).thenThrow(sandboxApi404());
        var sandbox404 = new OpenSandbox(state(), osb404, options(), null, null);
        var r404 = sandbox404.exec(RuntimeContext.builder().userId("u").build(), FIND_PROBE, 30);

        assertEquals(0, r404.exitCode());
        assertEquals("", r404.stdout());
        verify(osb404.commands(), never()).run(anyString());

        var osb = mockOsb(mockExecution(0, "tree/sub/x.md\t3\t0", ""));
        when(osb.files().listDirectory("tree")).thenReturn(List.of(dirEntry("/workspace/tree/sub")));
        when(osb.files().listDirectory("tree/sub")).thenThrow(sandboxApi503());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);
        var r = sandbox.exec(RuntimeContext.builder().userId("u").build(), FIND_PROBE, 30);

        assertTrue(r.ok());
        assertEquals("tree/sub/x.md\t3\t0", r.stdout(), "结果必须取自真 exec stub（回落生效）");
        verify(osb.commands()).run(FIND_PROBE_L);
    }

    @Test
    void globTranslateShouldStopAtDepthLimitWithoutThrowing() throws Exception {
        // i) 深度>8 截断：结果正确（收集 depth 0..8 共 9 个文件）且不抛；
        //    截断 WARN 每次 translateGlob 仅一条（仓库无 log appender 惯例，不断言日志）
        var files = mock(Filesystem.class);
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);
        when(osb.commands()).thenReturn(mock(Commands.class));
        String dir = "tree";
        for (int i = 0; i < 12; i++) {
            String next = dir + "/d" + (i + 1);
            when(files.listDirectory(dir)).thenReturn(List.of(
                dirEntry("/workspace/" + next), fileEntry("/workspace/" + dir + "/f" + i + ".md")));
            dir = next;
        }
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), FIND_PROBE, 30);

        assertEquals(0, result.exitCode());
        assertEquals(9, result.stdout().split("\n").length,
            "depth 0..8 各收集 1 个文件，更深层级截断");
        verify(osb.commands(), never()).run(anyString());
    }

    // ---------- 评审 g1：useServerProxy(true) 下死沙箱经 server 代理的 404 不是路径缺失 ----------

    /** server 代理死沙箱 404 形态（body {"message":"sandbox not found"}，对齐 mock server.mjs:325-326 录制件形态） */
    private static SandboxApiException sandboxNotFound404() {
        return new SandboxApiException("HTTP 404 Not Found", null, 404,
            new SandboxError("UNEXPECTED_RESPONSE", "dead sandbox"), null,
            "{\"message\":\"sandbox not found\"}", false);
    }

    /** 死沙箱 404 真实形态（2026-11-11 本地探针实证）：docker runtime，code 字段下划线形、message 夹 id */
    private static SandboxApiException dockerRuntimeSandboxNotFound404() {
        return new SandboxApiException("HTTP 404 Not Found", null, 404,
            new SandboxError("UNEXPECTED_RESPONSE", "dead sandbox"), null,
            "{\"code\":\"DOCKER::SANDBOX_NOT_FOUND\",\"message\":\"Sandbox "
                + "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0 not found.\"}", false);
    }

    /** 死沙箱 404 真实形态（2026-11-11 本地探针实证）：k8s runtime（batchsandbox + Pool，业务目标架构） */
    private static SandboxApiException kubernetesRuntimeSandboxNotFound404() {
        return new SandboxApiException("HTTP 404 Not Found", null, 404,
            new SandboxError("UNEXPECTED_RESPONSE", "dead sandbox"), null,
            "{\"code\":\"KUBERNETES::SANDBOX_NOT_FOUND\",\"message\":\"Sandbox "
                + "'0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0' not found\"}", false);
    }

    /** 路径缺失 404 真实形态（2026-11-11 本地探针实证）：execd files API 带 FILE_NOT_FOUND code */
    private static SandboxApiException fileNotFound404() {
        return new SandboxApiException("HTTP 404 Not Found", null, 404,
            new SandboxError("FILE_NOT_FOUND", "file not found. open /x: no such file or directory"), null,
            "{\"code\":\"FILE_NOT_FOUND\",\"message\":\"file not found. open /x: no such file or directory\"}", false);
    }

    /** 路径缺失 404 mock 形态（server.mjs:356/:363：files/download 与 directories/list 缺失返回空 body） */
    private static SandboxApiException emptyBody404() {
        return new SandboxApiException("HTTP 404 Not Found", null, 404,
            new SandboxError("UNEXPECTED_RESPONSE", "not found"), null, null, false);
    }

    @Test
    void readProbeWithProxySandboxNotFound404ShouldFallBackToRealExec() throws Exception {
        // useServerProxy(true)（OpenSandboxClient.java:116-117）下死沙箱（TTL 过期/故障态）
        // 经 server 代理得到 404 + "sandbox not found" body——不是 execd 路径缺失，不得吞成
        // 哨兵与同 turn 真 exec 回落的「沙箱不可达」报错矛盾；须回落真 exec（runCommandGuarded
        // 连续失败熔断收口为一致故障信号）
        var osb = mockOsb(mockExecution(0, "real content", ""));
        when(osb.files().readByteArray("AGENTS.md")).thenThrow(sandboxNotFound404());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), READ_PROBE, 30);

        assertTrue(result.ok());
        assertEquals("real content", result.stdout(), "死沙箱代理 404 必须回落真 exec，不得假缺失");
        verify(osb.commands()).run(READ_PROBE);
    }

    @Test
    void readProbeWithRealRuntimeSandboxNotFound404ShouldFallBackToRealExec() throws Exception {
        // 真实 server 探针实证（2026-11-11，双运行时）：死沙箱 404 body 为
        // {"code":"DOCKER::|KUBERNETES::SANDBOX_NOT_FOUND","message":"Sandbox '<id>' not found"}——
        // message 里 id 夹在 "Sandbox" 与 "not found" 之间，连续子串 "sandbox not found" 不命中；
        // 判据必须按 code 字段下划线形 sandbox_not_found 匹配，否则退化为「404 即缺失」（评审 g1 残留风险成真）
        for (SandboxApiException real404 : List.of(dockerRuntimeSandboxNotFound404(),
            kubernetesRuntimeSandboxNotFound404())) {
            var osb = mockOsb(mockExecution(0, "real content", ""));
            when(osb.files().readByteArray("AGENTS.md")).thenThrow(real404);
            var sandbox = new OpenSandbox(state(), osb, options(), null, null);

            var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), READ_PROBE, 30);

            assertTrue(result.ok());
            assertEquals("real content", result.stdout(), "真实形态死沙箱 404 必须回落真 exec，不得假缺失: " + real404.getResponseBody());
            verify(osb.commands()).run(READ_PROBE);
        }
    }

    @Test
    void readProbeWithRealFileNotFound404ShouldReturnMissingSentinel() throws Exception {
        // 真实 execd 路径缺失形态（2026-11-11 探针实证）：body {"code":"FILE_NOT_FOUND",...}
        // 不含 sandbox_not_found 字样 → 判缺失出哨兵（与死沙箱区分开）
        var osb = mockOsb(mockExecution(0, "ignored", ""));
        when(osb.files().readByteArray("AGENTS.md")).thenThrow(fileNotFound404());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), READ_PROBE, 30);

        assertEquals("__NOT_FOUND__", result.stdout(), "真实 FILE_NOT_FOUND 404 须判路径缺失");
        verify(osb.commands(), never()).run(anyString());
    }

    @Test
    void readProbeWithEmptyBody404ShouldReturnMissingSentinel() throws Exception {
        // mock 空 body 404（server.mjs:356/:363）＝ execd 路径缺失 → 判缺失出哨兵（e2e 语义不变）
        var osb = mockOsb(mockExecution(0, "ignored", ""));
        when(osb.files().readByteArray("AGENTS.md")).thenThrow(emptyBody404());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), READ_PROBE, 30);

        assertEquals("__NOT_FOUND__", result.stdout(), "空 body 404（mock 缺失形态）须判路径缺失");
        verify(osb.commands(), never()).run(anyString());
    }

    @Test
    void globProbeWithProxySandboxNotFound404ShouldFallBackToRealFind() throws Exception {
        // 同型：glob 遍历对死沙箱代理 404 不得视作「空目录」输出假空结果
        var osb = mockOsb(mockExecution(0, "tree/x.md\t3\t0", ""));
        when(osb.files().listDirectory("tree")).thenThrow(sandboxNotFound404());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), FIND_PROBE, 30);

        assertTrue(result.ok());
        assertEquals("tree/x.md\t3\t0", result.stdout(), "死沙箱代理 404 必须回落真 find，不得假空");
        verify(osb.commands()).run(FIND_PROBE_L);
    }

    @Test
    void globProbeWithRealRuntimeSandboxNotFound404ShouldFallBackToRealFind() throws Exception {
        // 同型覆盖真实形态：glob 路径的死沙箱代理 404 同样按 code 字段识别
        var osb = mockOsb(mockExecution(0, "tree/x.md\t3\t0", ""));
        when(osb.files().listDirectory("tree")).thenThrow(kubernetesRuntimeSandboxNotFound404());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), FIND_PROBE, 30);

        assertTrue(result.ok());
        assertEquals("tree/x.md\t3\t0", result.stdout(), "真实形态死沙箱 404 必须回落真 find，不得假空");
        verify(osb.commands()).run(FIND_PROBE_L);
    }

    // ---------- issue #514：server proxy 前置 nginx 的 HTML 404 不是路径缺失 ----------

    private static SandboxApiException nginxHtml404() {
        String body = "<!DOCTYPE html>\n<html><head><title>404 Not Found</title></head><body>"
            + "<h1>Not Found</h1><p>Test Page for the HTTP Server on Anolis OS</p></body></html>";
        return new SandboxApiException("HTTP 404 Not Found", null, 404,
            new SandboxError("UNEXPECTED_RESPONSE", body), null, body, false);
    }

    @Test
    void readProbeWithNginxHtml404ShouldFallBackToRealExec() throws Exception {
        var osb = mockOsb(mockExecution(0, "real content", ""));
        when(osb.files().readByteArray("AGENTS.md")).thenThrow(nginxHtml404());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), READ_PROBE, 30);

        assertTrue(result.ok());
        assertEquals("real content", result.stdout(), "HTML 404 是上游代理故障，不得吞成文件缺失");
        verify(osb.commands()).run(READ_PROBE);
    }

    @Test
    void lsProbeWithNginxHtml404ShouldFallBackToRealExec() throws Exception {
        var osb = mockOsb(mockExecution(0, "DIR:/workspace/docs\t0", ""));
        when(osb.files().listDirectory("docs")).thenThrow(nginxHtml404());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), LS_PROBE, 30);

        assertTrue(result.ok());
        assertEquals("DIR:/workspace/docs\t0", result.stdout(), "HTML 404 是上游代理故障，不得吞成目录不存在");
        verify(osb.commands()).run(LS_PROBE);
    }

    @Test
    void testEProbeWithNginxHtml404ShouldFallBackToRealExec() throws Exception {
        var osb = mockOsb(mockExecution(0, "yes", ""));
        when(osb.files().readByteArray("AGENTS.md")).thenThrow(nginxHtml404());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), TEST_E_PROBE, 30);

        assertTrue(result.ok());
        assertEquals("yes", result.stdout(), "HTML 404 是上游代理故障，不得吞成路径不存在");
        verify(osb.commands()).run(TEST_E_PROBE);
    }

    @Test
    void globProbeWithNginxHtml404ShouldFallBackToRealFind() throws Exception {
        var osb = mockOsb(mockExecution(0, "tree/x.md\t3\t0", ""));
        when(osb.files().listDirectory("tree")).thenThrow(nginxHtml404());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), FIND_PROBE, 30);

        assertTrue(result.ok());
        assertEquals("tree/x.md\t3\t0", result.stdout(), "HTML 404 是上游代理故障，不得吞成空目录");
        verify(osb.commands()).run(FIND_PROBE_L);
    }

    // ---------- exec→execd 翻译器（P0-12，issue #290 收窄回归） ----------

    @Test
    void execTranslateShouldFallbackMkdirAndNonProbeTestD() {
        // issue #290：删除 P_MKDIR + P_TEST_D 收窄为精确等值——"mkdir -p ..." 与
        // 非工作区根形态的 "test -d ..." 一律回落真 exec（翻译器返回 null）。
        // 此前 P_MKDIR 宽匹配（^mkdir -p (.+)$）会把 harness BaseSandboxFilesystem 的
        // move 模板（mkdir -p $(dirname 'x') && mv 'x' 'x'）误翻译成 createDirectories，
        // mv 永不执行；SDK doSetupWorkspace 实走 commands().run 直连 execd、不经 doExec，
        // 模型侧 "mkdir -p a b" 等多目录命令也无需翻译
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        assertNull(sandbox.translateForProbe("mkdir -p a b"));
        assertNull(sandbox.translateForProbe("mkdir -p /workspace"));
        assertNull(sandbox.translateForProbe("test -d $HOME/foo"));
        assertNull(sandbox.translateForProbe("test -d /opt/other"));
        verify(osb.files(), never()).createDirectories(anyList());
    }

    @Test
    void execTranslateShouldKeepWorkspaceRootProbeFastPath() throws Exception {
        // issue #290：工作区根探测唯一合法形态（harness
        // AbstractBaseSandbox.probeWorkspaceRootForPreservedResume 经 doExec 下发
        // "test -d " + getWorkspaceRoot()，10s 超时）保留 fast path——存在 exit 0、
        // 缺失 exit 1（translateExists 两级探测：先文件 readByteArray，再目录 listDirectory）。
        // 必须显式 stub Filesystem：Mockito 默认对 byte[] 返回 null，而 translateExists
        // 不判 null，null 返回会被误当"文件存在"
        var osb = mockOsb(mockExecution(0, "", ""));
        var files = osb.files();
        // 打桩用 files API 404（issue #296 收窄后仅 404 判缺失）：裸 RuntimeException 会上抛
        // 回落真 exec、translateForProbe 返回 null
        when(files.readByteArray(anyString())).thenThrow(sandboxApi404());
        // 链式打桩：第一路探测命中目录（exist 路径），第二路抛 404（missing 路径）
        when(files.listDirectory(anyString())).thenReturn(List.of())
            .thenThrow(sandboxApi404());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        assertEquals(0, sandbox.translateForProbe("test -d /workspace").exitCode());
        assertEquals(1, sandbox.translateForProbe("test -d /workspace").exitCode());
    }

    // ---------- scoped 指纹源（缺口④，9d77505）：包内源 stat + L4 revision，stage 目录不参与 ----------

    /** 内存 map 假 Redis：ScopedFingerprintStore 的 get/setex 落到共享 HashMap（模拟跨副本共享层） */
    @SuppressWarnings("unchecked")
    private static io.agentmanager.framework.redis.RedisConnectionFacade fakeScopedFpFacade(
            java.util.Map<String, String> data) {
        var sync = mock(io.lettuce.core.cluster.api.sync.RedisClusterCommands.class);
        when(sync.get(anyString())).thenAnswer(inv -> data.get((String) inv.getArgument(0)));
        doAnswer(inv -> {
            data.put((String) inv.getArgument(0), (String) inv.getArgument(2));
            return "OK";
        }).when(sync).setex(anyString(), anyLong(), anyString());
        var facade = mock(io.agentmanager.framework.redis.RedisConnectionFacade.class);
        when(facade.sync()).thenReturn(sync);
        when(facade.key(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return facade;
    }

    @Test
    void farmFingerprintShouldUseEndpointVersionNotStageStat() throws Exception {
        // 回归钉子（9d77505 缺口④①，PR-4b 拆分后口径）：稳态指纹 = 端点版本 + L4 revision，
        // 与宿主 stage 目录 stat 无关——stage 副本 mtime 各副本不同，若指纹退化回宿主目录
        // stat，跨副本指纹永不等、共享层永不命中、每副本重复付收敛 exec。
        // 端点版本 = SkillBundleService.computeVersion（包内源 stat + 禁用 overlay，跨副本一致）。
        // 两个 mtime 不同的 stage 目录（显式拉开，模拟不同副本各自 staging）
        var hostA = java.nio.file.Files.createTempDirectory("farm-fp-a");
        writeHostSkill(hostA, "sess-A", "demo-a");
        var hostB = java.nio.file.Files.createTempDirectory("farm-fp-b");
        writeHostSkill(hostB, "sess-A", "demo-a");
        java.nio.file.Files.setLastModifiedTime(
            hostA.resolve(".skills-cache/sess-A/oaf-package/demo-a/SKILL.md"),
            java.nio.file.attribute.FileTime.fromMillis(1700000000000L));
        java.nio.file.Files.setLastModifiedTime(
            hostB.resolve(".skills-cache/sess-A/oaf-package/demo-a/SKILL.md"),
            java.nio.file.attribute.FileTime.fromMillis(1777777777777L));

        var cfg = java.nio.file.Files.createTempDirectory("farm-fp-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var data = new java.util.HashMap<String, String>();
        var store = new ScopedFingerprintStore(fakeScopedFpFacade(data));
        var osb = farmOsbTracking(svc);

        // 副本1：全新 spec（Pod 级表空）→ 收敛 → 指纹经共享层 setex 落地
        var spec1 = new OpenSandboxFilesystemSpec().sharedFingerprintStore(store);
        var st1 = projectedStateNoSkillCache(hostA);
        st1.setSandboxId("sbx-fp-1");
        var call1 = new OpenSandbox(st1, osb, farmOptions(svc, null, null), null, null, spec1);
        bindFarmSession(call1, "sess-A");
        call1.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 1", 30);
        call1.stop();
        assertEquals(1, farmConvergeCommands(osb).size(), "副本1 首轮收敛");
        assertFalse(data.isEmpty(), "收敛成功后指纹必须写入共享层（recordScopedSkillCacheFingerprint 双写）");

        // 副本2：全新 spec（Pod 级表空 → 强制走共享层读回）+ mtime 不同的 stage 目录
        // → 指纹仍一致（源自端点版本而非 stage stat）→ 命中稳态短路，不重收敛
        var spec2 = new OpenSandboxFilesystemSpec().sharedFingerprintStore(store);
        var st2 = projectedStateNoSkillCache(hostB);
        st2.setSandboxId("sbx-fp-1");
        var call2 = new OpenSandbox(st2, osb, farmOptions(svc, null, null), null, null, spec2);
        bindFarmSession(call2, "sess-A");
        call2.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 2", 30);
        call2.stop();
        assertEquals(1, farmConvergeCommands(osb).size(),
            "同端点版本+同 revision（stage mtime 不同）→ 共享层指纹命中 → 副本2 不得重收敛（重收敛即指纹源退化为 stage stat）");
    }

    @Test
    void farmScopedFpSlotOverwriteShouldForceCrossReplicaReconverge() throws Exception {
        // 回归钉子（issue #393）：共享层键布局 = 沙箱代单槽 sbx:scopedfp:{sandboxId}，值=
        // {scope}|{记账值}，put 新 scope 原子覆盖旧 scope——与容器内 prune「同代只存一个有效
        // scope」的物理事实对齐。修复前 per-scope key 布局下：Pod1 切 scope-B prune 掉容器内
        // scope-A 后，Pod2（Pod 级表空）仍凭 scope-A 陈旧共享记录命中稳态 + 回填伪造
        // hydratedScopedScopes 而短路（converge=2）——但容器 .skills-cache 已无 scope-A，
        // 技能执行面全废。单槽覆盖后 Pod2 读 scope-A 必视为未知 → 重收敛（converge=3）。
        var host = java.nio.file.Files.createTempDirectory("farm-slot");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-slot-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var data = new java.util.HashMap<String, String>();
        var store = new ScopedFingerprintStore(fakeScopedFpFacade(data));
        var osb = farmOsbTracking(svc);

        // Pod1：scope-A 收敛（converge=1），共享槽记录 sess-A
        var spec1 = new OpenSandboxFilesystemSpec().sharedFingerprintStore(store);
        var st1 = projectedStateNoSkillCache(host);
        st1.setSandboxId("sbx-slot-1");
        var call1 = new OpenSandbox(st1, osb, farmOptions(svc, null, null), null, null, spec1);
        bindFarmSession(call1, "sess-A");
        call1.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 1", 30);
        call1.stop();
        assertEquals(1, farmConvergeCommands(osb).size(), "Pod1 scope-A 首轮收敛");

        // Pod1 同代切 scope-B：收敛（converge=2，prune 掉容器内 scope-A），共享槽被 scope-B 原子覆盖
        var st2 = projectedStateNoSkillCache(host);
        st2.setSandboxId("sbx-slot-1");
        var call2 = new OpenSandbox(st2, osb, farmOptions(svc, null, null), null, null, spec1);
        bindFarmSession(call2, "sess-B");
        call2.exec(RuntimeContext.builder().userId("sess-B").build(), "echo 2", 30);
        call2.stop();
        assertEquals(2, farmConvergeCommands(osb).size(), "同代 scope 切换必须重收敛（prune 旧 scope）");

        // Pod2（全新 spec，Pod 级表全空）读 scope-A：容器内已被 prune，共享槽=scope-B
        // → scope-A 必须重收敛（converge=3）；修复前陈旧命中短路（converge=2）必红
        var spec2 = new OpenSandboxFilesystemSpec().sharedFingerprintStore(store);
        var st3 = projectedStateNoSkillCache(host);
        st3.setSandboxId("sbx-slot-1");
        var call3 = new OpenSandbox(st3, osb, farmOptions(svc, null, null), null, null, spec2);
        bindFarmSession(call3, "sess-A");
        call3.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 3", 30);
        call3.stop();
        assertEquals(3, farmConvergeCommands(osb).size(),
            "Pod2 的 scope-A 在 Pod1 prune+槽覆盖后必须重收敛（共享槽已记 scope-B，陈旧命中即伪造稳态）");
    }

    @Test
    void farmPodLocalStaleHitShouldBeInvalidatedBySlotScopeCrossCheck() throws Exception {
        // 回归钉子（issue #393 评审 g1，Pod 级表向量）：稳态门两操作数（Pod 级指纹 +
        // hydratedScopedScope）均为本 Pod 信念且无跨副本失效机制——单槽修复只封住「Pod 级
        // miss → 共享层」一条路径，Pod 级命中仍绕过共享槽。同代 A(Pod2)→B(Pod1，prune 容器内
        // A、单槽覆盖为 B)→A 回落 Pod2：Pod2 两表仍记 A，Pod 级命中若不做槽 scope 交叉核验
        // 即伪造稳态短路（converge=2），容器内已无 A、技能执行面全废。
        var host = java.nio.file.Files.createTempDirectory("farm-slot-pod");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-slot-pod-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var data = new java.util.HashMap<String, String>();
        var store = new ScopedFingerprintStore(fakeScopedFpFacade(data));
        var osb = farmOsbTracking(svc);

        // Pod2：scope-A 收敛（converge=1），Pod 级两表与共享槽均记 sess-A
        var specPod2 = new OpenSandboxFilesystemSpec().sharedFingerprintStore(store);
        var st1 = projectedStateNoSkillCache(host);
        st1.setSandboxId("sbx-slot-pod");
        var callPod2A = new OpenSandbox(st1, osb, farmOptions(svc, null, null), null, null, specPod2);
        bindFarmSession(callPod2A, "sess-A");
        callPod2A.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 1", 30);
        callPod2A.stop();
        assertEquals(1, farmConvergeCommands(osb).size(), "Pod2 scope-A 首轮收敛");

        // Pod1：同代切 scope-B → 收敛（converge=2，prune 掉容器内 A），单槽被覆盖为 sess-B
        var specPod1 = new OpenSandboxFilesystemSpec().sharedFingerprintStore(store);
        var st2 = projectedStateNoSkillCache(host);
        st2.setSandboxId("sbx-slot-pod");
        var callPod1B = new OpenSandbox(st2, osb, farmOptions(svc, null, null), null, null, specPod1);
        bindFarmSession(callPod1B, "sess-B");
        callPod1B.exec(RuntimeContext.builder().userId("sess-B").build(), "echo 2", 30);
        callPod1B.stop();
        assertEquals(2, farmConvergeCommands(osb).size(), "Pod1 切 scope-B 重收敛并 prune 容器内 A");

        // A 回落 Pod2（复用同一 spec 实例=Pod 级两表仍记 A）：槽现役 scope=B ≠ A → 权威否定
        // 陈旧两表 → 必须重收敛（converge=3）；修复前 Pod 级命中绕过共享槽伪造稳态（=2）必红
        var st3 = projectedStateNoSkillCache(host);
        st3.setSandboxId("sbx-slot-pod");
        var callPod2Again = new OpenSandbox(st3, osb, farmOptions(svc, null, null), null, null, specPod2);
        bindFarmSession(callPod2Again, "sess-A");
        callPod2Again.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 3", 30);
        callPod2Again.stop();
        assertEquals(3, farmConvergeCommands(osb).size(),
            "Pod 级表命中也须过共享槽 scope 交叉核验：槽已记 scope-B，Pod2 的陈旧 A 记录不得伪造稳态");
    }

    @Test
    void farmShouldReconvergeWhenRevisionChanges() throws Exception {
        // 回归钉子（9d77505 缺口④②，PR-4b 口径）：指纹必须含 L4 revision 段——revision 是
        // KV 共享的用户级技能差异信号，变化后同容器代同 scope 的下一 call 必须重收敛
        // （缺失该段=陈旧软链=正确性）
        var host = java.nio.file.Files.createTempDirectory("farm-rev");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-rev-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var reader = mock(WorkspaceReader.class);
        var rev = new java.util.concurrent.atomic.AtomicReference<>("rev-1");
        when(reader.readUserSkillRevision(anyString())).thenAnswer(inv -> rev.get());

        var osb = farmOsbTracking(svc);
        var spec = new OpenSandboxFilesystemSpec();

        // call1（实例1，生产 acquire 语义）：rev-1 → 收敛并记录指纹
        var st1 = projectedStateNoSkillCache(host);
        st1.setSandboxId("sbx-rev-1");
        var call1 = new OpenSandbox(st1, osb, farmOptions(svc, null, null), reader, null, spec);
        call1.setUserKey("alice");
        call1.setSkillCacheScope("sess-A");
        call1.exec(RuntimeContext.builder().userId("alice").build(), "echo 1", 30);
        call1.stop();
        assertEquals(1, farmConvergeCommands(osb).size(), "首轮收敛");

        // 用户技能变更：KV revision rev-1 → rev-2（跨实例可见，生产=任意副本）
        rev.set("rev-2");

        // call2（实例2，同容器代同 scope）：revision 变化 → 指纹变化 → 必须重收敛
        var st2 = projectedStateNoSkillCache(host);
        st2.setSandboxId("sbx-rev-1");
        var call2 = new OpenSandbox(st2, osb, farmOptions(svc, null, null), reader, null, spec);
        call2.setUserKey("alice");
        call2.setSkillCacheScope("sess-A");
        call2.exec(RuntimeContext.builder().userId("alice").build(), "echo 2", 30);
        call2.stop();
        assertEquals(2, farmConvergeCommands(osb).size(),
            "revision rev-1→rev-2 后第二次 call 必须重收敛（不收敛即指纹缺 revision 段，软链陈旧）");
    }

    // ---------- first-reasoning：D1 混合读穿 / D4 探针直供 / D5 探测修正 / D6 补投开关 ----------

    /** 真实 InMemoryStore + WorkspaceReader 的实例构造（读穿/默认两版，13 参构造器） */
    private static OpenSandbox readerInstance(Sandbox osb, io.agentmanager.framework.service.WorkspaceReader reader,
                                              boolean readThrough) {
        var st = state();
        st.setSandboxId("sbx-fr-1");
        return new OpenSandbox(st, osb, options(), reader, null, new OpenSandboxFilesystemSpec(),
            null, null, true, true, readThrough, true);
    }

    /** P_READ 探测模板（SDK 系统提示探针原样形态） */
    private static String probeRead(String path, int lines) {
        return "if [ ! -f '" + path + "' ]; then echo '__NOT_FOUND__'; elif [ ! -s '" + path
            + "' ]; then echo '__EMPTY__'; else sed -n '1," + lines + "p' '" + path + "'; fi";
    }

    /** D1：读穿开启 → 预热注入跳过，MEMORY.md 探测从 KV 应答（容器 files().write 零调用） */
    @Test
    @SuppressWarnings("unchecked")
    void readThroughShouldSkipEagerInjectionAndServeProbeFromKv() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var fs = new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
            store, List.of("u1"));
        var ctx = RuntimeContext.builder().userId("u1").build();
        fs.write(ctx, "MEMORY.md", "# kv memory line1\nline2\n");

        var osb = mockOsb(mockExecution(0, "", ""));
        // 容器侧缺失 = 404 异常（真实 execd 语义；空文件≠缺失，不回落 KV）
        var filesMock = osb.files();
        org.mockito.Mockito.doThrow(sandboxApi404())
            .when(filesMock).readByteArray(org.mockito.ArgumentMatchers.anyString());
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds);
        var sandbox = readerInstance(osb, reader, true);

        var result = sandbox.exec(ctx, probeRead("/workspace/MEMORY.md", 10), 30);

        assertEquals(0, result.exitCode());
        assertEquals("# kv memory line1\nline2", result.stdout(), "KV 内容必须按 sed '1,Np' 语义应答");
        verify(osb.files(), never()).write(anyList());
    }

    /** D1 语义边界：容器存在但为空的 MEMORY.md 必须 __EMPTY__，不得回落 KV 读到旧内容 */
    @Test
    void emptyContainerMemoryFileShouldAnswerEmptyNotKvFallback() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var fs = new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
            store, List.of("u1"));
        var ctx = RuntimeContext.builder().userId("u1").build();
        fs.write(ctx, "MEMORY.md", "# stale kv content");

        var osb = mockOsb(mockExecution(0, "", ""));
        var filesMock = osb.files();
        when(filesMock.readByteArray("/workspace/MEMORY.md")).thenReturn(new byte[0]);
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds);
        var sandbox = readerInstance(osb, reader, true);

        var result = sandbox.exec(ctx, probeRead("/workspace/MEMORY.md", 10), 30);

        assertEquals("__EMPTY__", result.stdout(), "容器空文件 ≠ 缺失：不回落 KV");
    }

    /** 读穿开启下的根级 MEMORY.md test -e 探针（P_TEST_E 同形）：KV 有 → yes，KV 无 → no */
    @Test
    void readThroughTestExistsRootMemoryMdShouldFallBackToKv() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var fs = new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
            store, List.of("u1"));
        var ctx = RuntimeContext.builder().userId("u1").build();

        var osb = mockOsb(mockExecution(0, "", ""));
        var filesMock = osb.files();
        org.mockito.Mockito.doThrow(sandboxApi404())
            .when(filesMock).readByteArray(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doThrow(sandboxApi404())
            .when(filesMock).listDirectory(org.mockito.ArgumentMatchers.anyString());
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds);
        var sandbox = readerInstance(osb, reader, true);

        var probe = "test -e '/workspace/MEMORY.md' && echo yes || echo no";
        assertEquals("no", sandbox.exec(ctx, probe, 30).stdout(), "KV 无 MEMORY.md → 探测答 no");

        fs.write(ctx, "MEMORY.md", "# kv memory line1\nline2\n");
        assertEquals("yes", sandbox.exec(ctx, probe, 30).stdout(),
            "KV 有 MEMORY.md → 探测必须与 read 同口径答 yes（issue #356）");
        verify(osb.files(), never()).write(anyList());
    }

    /** 读穿开启下的根级 glob：MEMORY.md / *.md 命中并入 KV 条目；memory/ 子树模式不并入 */
    @Test
    @SuppressWarnings("unchecked")
    void readThroughRootGlobShouldMergeKvMemoryMd() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var fs = new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
            store, List.of("u1"));
        var ctx = RuntimeContext.builder().userId("u1").build();
        fs.write(ctx, "MEMORY.md", "# kv memory line1\nline2\n");

        var osb = mockOsb(mockExecution(0, "", ""));
        var filesMock = osb.files();
        org.mockito.Mockito.doThrow(sandboxApi404())
            .when(filesMock).listDirectory(org.mockito.ArgumentMatchers.anyString());
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds);
        var sandbox = readerInstance(osb, reader, true);

        var exact = sandbox.exec(ctx, findProbeForm("/workspace", "MEMORY.md"), 30);
        assertTrue(exact.stdout().contains("MEMORY.md\t24\t0"),
            "根级 glob MEMORY.md 必须并入 KV 条目（issue #356），got: " + exact.stdout());

        var star = sandbox.exec(ctx, findProbeForm("/workspace", "*.md"), 30);
        assertTrue(star.stdout().contains("MEMORY.md\t24\t0"),
            "*.md 模式同样要拿到 KV 中的 MEMORY.md，got: " + star.stdout());

        var memorySubtree = sandbox.exec(ctx, findProbeForm("/workspace/memory", "*.md"), 30);
        assertFalse(memorySubtree.stdout().contains("MEMORY.md"),
            "memory/ 子树 glob 不得混入根级 MEMORY.md");
    }

    /** 农场形态容器根：.skills-cache 受控缓存目录（子树软链）+ 一个真实工作区文件 */
    private static void stubFarmWorkspaceRoot(Sandbox osb) throws Exception {
        var filesMock = osb.files();
        when(filesMock.listDirectory("/workspace")).thenReturn(List.of(
            dirEntry("/workspace/.skills-cache"),
            fileEntry("/workspace/notes.md")));
        when(filesMock.listDirectory(".skills-cache")).thenReturn(List.of(
            symlinkEntry("/workspace/.skills-cache/sess/oaf-package/demo")));
    }

    /** 读穿 KV 基建：InMemoryStore + RemoteFilesystem（u1）+ 已写入 MEMORY.md，返回 ctx */
    private static RuntimeContext readThroughKvWithMemoryMd(
            io.agentscope.harness.agent.DistributedStore[] dsOut) throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var fs = new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
            store, List.of("u1"));
        fs.write(RuntimeContext.builder().userId("u1").build(), "MEMORY.md", "# kv memory line1\nline2\n");
        dsOut[0] = ds;
        return RuntimeContext.builder().userId("u1").build();
    }

    @Test
    void readThroughRootGlobWithFarmSymlinkShouldStillMergeKvMemoryMd() throws Exception {
        // issue #500 主缺陷：农场软链（.skills-cache 受控子树）不得让根 glob 整树回落真 exec——
        // 回落既令 P0-12 翻译快路径失效（先 4 次 listDirectory 再 exec），又跳过
        // fillGlobFromMemoryRootFile 且 looksLikeMemoryAccess 对 *.md glob 不命中（懒注入
        // 不触发），真 find -L 漏报 KV 中的 MEMORY.md（回归 #356）
        var ds = new io.agentscope.harness.agent.DistributedStore[1];
        var ctx = readThroughKvWithMemoryMd(ds);
        var osb = mockOsb(mockExecution(0, "FALLBACK_EXEC", ""));
        stubFarmWorkspaceRoot(osb);
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds[0]);
        var sandbox = readerInstance(osb, reader, true);

        var r = sandbox.exec(ctx, findProbeForm("/workspace", "*.md"), 30);

        assertFalse(r.stdout().contains("FALLBACK_EXEC"),
            "农场软链不得触发真 exec 回落（快路径失效即本 issue 缺陷）");
        assertTrue(r.stdout().contains("notes.md"), "真实工作区文件必须照常枚举");
        assertTrue(r.stdout().contains("MEMORY.md\t24\t0"),
            "读穿根 glob 必须并入 KV MEMORY.md（#356 回归），got: " + r.stdout());
        verify(osb.commands(), never()).run(anyString());
    }

    @Test
    void readThroughRootGlobFallbackOverUserSymlinkShouldLazyInjectMemory() throws Exception {
        // issue #500 兜底（修复建议 A 懒注入变体）：用户内容软链触发根 glob 回落真 exec 前，
        // 根级 *.md 模式视作记忆访问触发 D1 懒注入——find -L 地面真相包含已注入的
        // MEMORY.md，读穿在回落路径不漏 KV（既有 CAS 一次性机制，无新增合并输出路径）
        var ds = new io.agentscope.harness.agent.DistributedStore[1];
        var ctx = readThroughKvWithMemoryMd(ds);
        var osb = mockOsb(mockExecution(0, "link-target.md\t3\t0", ""));
        var filesMock = osb.files();
        when(filesMock.listDirectory("/workspace")).thenReturn(List.of(
            symlinkEntry("/workspace/user-link")));
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds[0]);
        var sandbox = readerInstance(osb, reader, true);

        sandbox.exec(ctx, findProbeForm("/workspace", "*.md"), 30);

        verify(osb.commands()).run(findLProbeForm("/workspace", "*.md"));
        verify(osb.files()).write(anyList());
    }

    @Test
    void readThroughOffRootGlobFallbackShouldNotLazyInject() throws Exception {
        // 读穿关闭（默认）：根 glob 回落真 exec 不触发懒注入分支（D1 门短路），容器侧
        // 预热注入已含 MEMORY.md、真 find 地面真相自足——回归边界即回落语义不变
        var ds = new io.agentscope.harness.agent.DistributedStore[1];
        var ctx = readThroughKvWithMemoryMd(ds);
        var osb = mockOsb(mockExecution(0, "link-target.md\t3\t0", ""));
        var filesMock = osb.files();
        when(filesMock.listDirectory("/workspace")).thenReturn(List.of(
            symlinkEntry("/workspace/user-link")));
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds[0]);
        var sandbox = readerInstance(osb, reader, false);

        var r = sandbox.exec(ctx, findProbeForm("/workspace", "*.md"), 30);

        assertEquals("link-target.md\t3\t0", r.stdout(), "读穿关闭时回落真 exec（结果取自 stub）");
        verify(osb.commands()).run(findLProbeForm("/workspace", "*.md"));
    }

    /** 口径一致性：KV 空 MEMORY.md → test -e yes 且 read __EMPTY__（两侧同判「存在但空」） */
    @Test
    void readThroughEmptyKvMemoryMdShouldBeConsistentAcrossProbes() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var fs = new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
            store, List.of("u1"));
        var ctx = RuntimeContext.builder().userId("u1").build();
        fs.write(ctx, "MEMORY.md", "");

        var osb = mockOsb(mockExecution(0, "", ""));
        var filesMock = osb.files();
        org.mockito.Mockito.doThrow(sandboxApi404())
            .when(filesMock).readByteArray(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doThrow(sandboxApi404())
            .when(filesMock).listDirectory(org.mockito.ArgumentMatchers.anyString());
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds);
        var sandbox = readerInstance(osb, reader, true);

        assertEquals("yes", sandbox.exec(ctx, "test -e '/workspace/MEMORY.md' && echo yes || echo no", 30).stdout(),
            "空内容 ≠ 不存在：test -e 必须 yes");
        assertEquals("__EMPTY__", sandbox.exec(ctx, probeRead("/workspace/MEMORY.md", 10), 30).stdout(),
            "read 侧同口径 __EMPTY__");
    }

    /** issue #423 回归：容器内本轮新写、尚未回写 KV 的记忆文件，read 能读而 test -e 不得答 no */
    @Test
    void readThroughContainerFreshMemoryShouldAnswerConsistentlyAcrossReadAndTestExists() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var ctx = RuntimeContext.builder().userId("u1").build();

        var osb = mockOsb(mockExecution(0, "", ""));
        var filesMock = osb.files();
        when(filesMock.readByteArray("/workspace/MEMORY.md"))
            .thenReturn("fresh memory".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(filesMock.readByteArray("/workspace/memory/insight.md"))
            .thenReturn("fresh insight".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(filesMock.readByteArray("/workspace/memory")).thenThrow(sandboxApi404());
        when(filesMock.listDirectory("/workspace/memory"))
            .thenReturn(List.of(fileEntry("/workspace/memory/insight.md")));
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds);
        var sandbox = readerInstance(osb, reader, true);

        var readRoot = sandbox.exec(ctx, probeRead("/workspace/MEMORY.md", 10), 30);
        assertEquals("fresh memory", readRoot.stdout(), "容器优先：read 读到本轮新写内容");

        var existsRoot = sandbox.exec(ctx, "test -e '/workspace/MEMORY.md' && echo yes || echo no", 30);
        assertEquals("yes", existsRoot.stdout(),
            "test -e 必须与 read 同口径（容器存在 || KV 存在，issue #423）");

        var readLeaf = sandbox.exec(ctx, probeRead("/workspace/memory/insight.md", 10), 30);
        assertEquals("fresh insight", readLeaf.stdout(), "容器优先：read 读到本轮新写内容");

        var existsLeaf = sandbox.exec(ctx, "test -e '/workspace/memory/insight.md' && echo yes || echo no", 30);
        assertEquals("yes", existsLeaf.stdout(),
            "memory/ 一级 .md 容器新写时 test -e 必须与 read 同口径（issue #423）");

        var existsDir = sandbox.exec(ctx, "test -e '/workspace/memory' && echo yes || echo no", 30);
        assertEquals("yes", existsDir.stdout(),
            "memory/ 目录容器存在时 test -e 不得只凭 KV 清单判 no（issue #423）");
    }

    /** issue #423 对照组：容器缺失时 KV 回落仍生效——test -e memory/ 依 KV 清单答 yes */
    @Test
    void readThroughTestExistsMemoryDirShouldFallBackToKvWhenContainerMissing() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var fs = new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(store, List.of("u1"));
        var ctx = RuntimeContext.builder().userId("u1").build();
        fs.write(ctx, "memory/insight.md", "# kv insight\n");

        var osb = mockOsb(mockExecution(0, "", ""));
        var filesMock = osb.files();
        org.mockito.Mockito.doThrow(sandboxApi404())
            .when(filesMock).readByteArray(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doThrow(sandboxApi404())
            .when(filesMock).listDirectory(org.mockito.ArgumentMatchers.anyString());
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds);
        var sandbox = readerInstance(osb, reader, true);

        assertEquals("yes", sandbox.exec(ctx, "test -e '/workspace/memory' && echo yes || echo no", 30).stdout(),
            "容器全缺失 + KV 清单有 memory/ 文件 → 目录探测 yes（回落语义保持）");
    }

    /** issue #502：读穿模式带尾斜杠的 memory/ 目录探测必须与不带斜杠同口径（KV 清单回落） */
    @Test
    void readThroughTestExistsTrailingSlashMemoryDirShouldFallBackToKv() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var fs = new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(store, List.of("u1"));
        var ctx = RuntimeContext.builder().userId("u1").build();
        fs.write(ctx, "memory/2026-11-24.md", "# daily note\n");

        var osb = mockOsb(mockExecution(0, "", ""));
        var filesMock = osb.files();
        org.mockito.Mockito.doThrow(sandboxApi404())
            .when(filesMock).readByteArray(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doThrow(sandboxApi404())
            .when(filesMock).listDirectory(org.mockito.ArgumentMatchers.anyString());
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds);
        var sandbox = readerInstance(osb, reader, true);

        assertEquals("yes", sandbox.exec(ctx, "test -e '/workspace/memory/' && echo yes || echo no", 30).stdout(),
            "带尾斜杠 memory/ 必须与 test -e memory 同口径依 KV 清单答 yes（issue #502）");
        assertEquals("yes", sandbox.exec(ctx, "test -e 'memory/' && echo yes || echo no", 30).stdout(),
            "工作区相对路径形态同判（issue #502）");
    }

    /** issue #498 主回归：读穿模式下 glob 对 memory/ 必须呈现「容器 ∪ KV」合并视图 */
    @Test
    void readThroughGlobShouldMergeContainerFreshAndKvHistoryMemory() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var fs = new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(store, List.of("u1"));
        var ctx = RuntimeContext.builder().userId("u1").build();
        fs.write(ctx, "memory/old.md", "# kv history\n");

        var osb = mockOsb(mockExecution(0, "", ""));
        var filesMock = osb.files();
        // 容器内本轮新写 fresh.md：memory 目录存在（不再 404），仅见容器文件
        when(filesMock.listDirectory("/workspace/memory"))
            .thenReturn(List.of(fileEntry("/workspace/memory/fresh.md", 14)));
        when(filesMock.readByteArray("/workspace/memory/fresh.md"))
            .thenReturn("fresh insight\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(filesMock.readByteArray("/workspace/memory/old.md")).thenThrow(sandboxApi404());
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds);
        var sandbox = readerInstance(osb, reader, true);

        var glob = sandbox.exec(ctx, findProbeForm("/workspace/memory", "*.md"), 30);
        assertTrue(glob.stdout().contains("memory/fresh.md"),
            "glob 必须看到容器本轮新写文件（修复前 KV-only 视角不可见），got: " + glob.stdout());
        assertTrue(glob.stdout().contains("memory/old.md"),
            "glob 必须并入 KV 历史文件，got: " + glob.stdout());
    }

    /** issue #498 主回归：读穿模式下 ls 对 memory/ 必须呈现「容器 ∪ KV」合并视图 */
    @Test
    void readThroughLsShouldMergeContainerFreshAndKvHistoryMemory() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var fs = new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(store, List.of("u1"));
        var ctx = RuntimeContext.builder().userId("u1").build();
        fs.write(ctx, "memory/old.md", "# kv history\n");

        var osb = mockOsb(mockExecution(0, "", ""));
        var filesMock = osb.files();
        when(filesMock.listDirectory("/workspace/memory"))
            .thenReturn(List.of(fileEntry("/workspace/memory/fresh.md", 14)));
        when(filesMock.readByteArray("/workspace/memory/fresh.md"))
            .thenReturn("fresh insight\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(filesMock.readByteArray("/workspace/memory/old.md")).thenThrow(sandboxApi404());
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds);
        var sandbox = readerInstance(osb, reader, true);

        var ls = sandbox.exec(ctx, lsProbeForm("/workspace/memory"), 30);
        assertTrue(ls.stdout().contains("FILE:memory/fresh.md"),
            "ls 必须看到容器本轮新写文件，got: " + ls.stdout());
        assertTrue(ls.stdout().contains("FILE:memory/old.md"),
            "ls 必须并入 KV 历史文件（修复前容器目录存在即隐藏 KV），got: " + ls.stdout());
    }

    /** issue #498 同路径去重与容器优先：glob/ls 对 memory/ 呈现一致视图且与 exists/read 自洽 */
    @Test
    void readThroughMemoryViewShouldBeUnifiedAndContainerPreferred() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var fs = new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(store, List.of("u1"));
        var ctx = RuntimeContext.builder().userId("u1").build();
        fs.write(ctx, "memory/old.md", "# kv history\n");
        fs.write(ctx, "memory/dual.md", "kv");

        var osb = mockOsb(mockExecution(0, "", ""));
        var filesMock = osb.files();
        // 容器内 fresh.md（新写）与 dual.md（与 KV 同路径不同元数据，size=99 vs KV size=2）
        when(filesMock.listDirectory("/workspace/memory")).thenReturn(List.of(
            fileEntry("/workspace/memory/fresh.md", 14),
            fileEntry("/workspace/memory/dual.md", 99)));
        when(filesMock.readByteArray("/workspace/memory/fresh.md"))
            .thenReturn("fresh insight\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(filesMock.readByteArray("/workspace/memory/dual.md"))
            .thenReturn("container".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(filesMock.readByteArray("/workspace/memory/old.md")).thenThrow(sandboxApi404());
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds);
        var sandbox = readerInstance(osb, reader, true);

        var glob = sandbox.exec(ctx, findProbeForm("/workspace/memory", "*.md"), 30);
        assertTrue(glob.stdout().contains("memory/dual.md\t99\t"),
            "同路径双份时 glob 必须以容器元数据为准（size=99 非 KV size=2），got: " + glob.stdout());
        assertEquals(3, glob.stdout().split("\n").length,
            "glob 应恰好列出 fresh/dual/old 三个文件各一次，got: " + glob.stdout());

        var ls = sandbox.exec(ctx, lsProbeForm("/workspace/memory"), 30);
        assertEquals(3, ls.stdout().split("\n").length,
            "ls 应恰好列出 fresh/dual/old 三个文件各一次（同路径去重），got: " + ls.stdout());
        assertEquals(1, ls.stdout().split("memory/dual.md", -1).length - 1,
            "dual.md 在 ls 输出中不得重复（容器条目优先去重）");

        assertEquals("yes", sandbox.exec(ctx, "test -e '/workspace/memory/old.md' && echo yes || echo no", 30).stdout(),
            "test -e 对 KV-only 历史文件必须 yes（issue #423 口径）");
        assertEquals("yes", sandbox.exec(ctx, "test -e '/workspace/memory/fresh.md' && echo yes || echo no", 30).stdout(),
            "test -e 对容器新写文件必须 yes");
        var readOld = sandbox.exec(ctx, probeRead("/workspace/memory/old.md", 10), 30);
        assertEquals("# kv history", readOld.stdout(), "read 对 KV-only 文件必须回落 KV 内容");
        var readFresh = sandbox.exec(ctx, probeRead("/workspace/memory/fresh.md", 10), 30);
        assertEquals("fresh insight", readFresh.stdout(), "read 对容器新写文件必须容器优先");
    }

    /** issue #498 对照组：容器整树缺失时 glob/ls 纯 KV 视图保持（回落语义不因合并改动回退） */
    @Test
    void readThroughGlobAndLsShouldServePureKvWhenContainerMissing() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var fs = new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(store, List.of("u1"));
        var ctx = RuntimeContext.builder().userId("u1").build();
        fs.write(ctx, "memory/kv-only.md", "kv only file\n");

        var osb = mockOsb(mockExecution(0, "", ""));
        var filesMock = osb.files();
        org.mockito.Mockito.doThrow(sandboxApi404())
            .when(filesMock).readByteArray(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doThrow(sandboxApi404())
            .when(filesMock).listDirectory(org.mockito.ArgumentMatchers.anyString());
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds);
        var sandbox = readerInstance(osb, reader, true);

        var glob = sandbox.exec(ctx, findProbeForm("/workspace/memory", "*.md"), 30);
        assertEquals("memory/kv-only.md\t13\t0", glob.stdout(),
            "容器整树缺失时 glob 必须纯 KV 应答（404 视作空目录）");

        var ls = sandbox.exec(ctx, lsProbeForm("/workspace/memory"), 30);
        assertEquals("FILE:memory/kv-only.md\t13\t0", ls.stdout(),
            "容器整树缺失时 ls 必须纯 KV 应答（回落语义保持）");

        assertEquals("yes", sandbox.exec(ctx, "test -e '/workspace/memory/kv-only.md' && echo yes || echo no", 30).stdout(),
            "test -e KV 回落保持（issue #423 对照组口径）");
        assertEquals("kv only file", sandbox.exec(ctx, probeRead("/workspace/memory/kv-only.md", 10), 30).stdout(),
            "read KV 回落保持");
    }

    /** D1：SDK edit_file 形态（python3 -c + base64 载荷，路径不可见）的真实 exec 也要触发补注入 */
    @Test
    @SuppressWarnings("unchecked")
    void editStyleRealExecShouldTriggerLazyInjection() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var fs = new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
            store, List.of("u1"));
        fs.write(RuntimeContext.builder().userId("u1").build(), "MEMORY.md", "# edit target");

        var osb = mockOsb(mockExecution(0, "", ""));
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds);
        var sandbox = readerInstance(osb, reader, true);

        // SDK edit 模板形态：路径编进 base64，命令文本不含 MEMORY.md
        sandbox.exec(RuntimeContext.builder().userId("u1").build(),
            "python3 -c " + java.util.Base64.getEncoder().encodeToString("edit".getBytes()), 30);

        var captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(osb.files(), atLeastOnce()).write(captor.capture());
        var paths = ((java.util.List<WriteEntry>) captor.getAllValues().stream()
            .flatMap(l -> ((java.util.List<WriteEntry>) l).stream()).toList())
            .stream().map(WriteEntry::getPath).toList();
        assertTrue(paths.contains("/workspace/MEMORY.md"), "edit 形态真实 exec 必须先补注入");
        var inOrder = org.mockito.Mockito.inOrder(osb.files(), osb.commands());
        inOrder.verify(osb.files(), atLeastOnce()).write(anyList());
        inOrder.verify(osb.commands()).run(org.mockito.ArgumentMatchers.startsWith("python3 -c"));
    }

    /** D1：读穿默认关闭保持旧行为——预热注入先于探测（readRuntimeFiles + injectToSandbox 必达） */
    @Test
    void eagerInjectionShouldRemainDefaultBehavior() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var reader = org.mockito.Mockito.mock(io.agentmanager.framework.service.WorkspaceReader.class);
        org.mockito.Mockito.when(reader.readRuntimeFiles("u1")).thenReturn(Map.of());

        readerInstance(osb, reader, false).exec(
            RuntimeContext.builder().userId("u1").build(), probeRead("/workspace/MEMORY.md", 10), 30);

        org.mockito.Mockito.verify(reader).readRuntimeFiles("u1");
        org.mockito.Mockito.verify(reader).injectToSandbox(org.mockito.Mockito.any(),
            org.mockito.Mockito.eq(Map.of()));
    }

    /** D1 混合触发器：真实 exec 命令含 MEMORY.md → 先补注入（KV→容器）再执行 */
    @Test
    @SuppressWarnings("unchecked")
    void realExecTouchingMemoryShouldTriggerLazyInjection() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var fs = new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
            store, List.of("u1"));
        var ctx = RuntimeContext.builder().userId("u1").build();
        fs.write(ctx, "MEMORY.md", "# full memory");

        var osb = mockOsb(mockExecution(0, "cat-output", ""));
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds);
        var sandbox = readerInstance(osb, reader, true);

        sandbox.exec(ctx, "cat /workspace/MEMORY.md", 30);

        var captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(osb.files(), atLeastOnce()).write(captor.capture());
        var paths = ((java.util.List<WriteEntry>) captor.getAllValues().stream()
            .flatMap(l -> ((java.util.List<WriteEntry>) l).stream()).toList())
            .stream().map(WriteEntry::getPath).toList();
        assertTrue(paths.contains("/workspace/MEMORY.md"), "真实访问记忆前必须补注入全量副本");
        // 注入必须发生在真实命令执行之前（cat 才能读到刚注入的文件）
        var inOrder = org.mockito.Mockito.inOrder(osb.files(), osb.commands());
        inOrder.verify(osb.files(), atLeastOnce()).write(anyList());
        inOrder.verify(osb.commands()).run("cat /workspace/MEMORY.md");
    }

    /** D1：翻译命中的探测命令不触发补注入（首推理探测窗口零注入成本） */
    @Test
    void translatedProbeShouldNotTriggerLazyInjection() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var osb = mockOsb(mockExecution(0, "", ""));
        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds);
        var sandbox = readerInstance(osb, reader, true);

        sandbox.exec(RuntimeContext.builder().userId("u1").build(),
            probeRead("/workspace/MEMORY.md", 10), 30);

        verify(osb.files(), never()).write(anyList());
    }

    /** D4：物化回填的 SKILL.md 探测直供缓存应答；真实 exec 后窗口关闭（回落容器读） */
    @Test
    void probeCacheShouldServeMaterializedSkillReadUntilRealExec() throws Exception {
        var store = new io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore();
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        var fs = new io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem(
            store, java.util.List.of("agents", "agent-x", "users", "alice", "skills"));
        var ctx = RuntimeContext.builder().userId("alice").build();
        fs.write(ctx, "/pdf/SKILL.md", "# cached skill body\n");

        var reader = new io.agentmanager.framework.service.WorkspaceReader(ds, "agent-x");
        var osb = mockOsb(mockExecution(0, "ok", ""));
        // 容器侧该路径桩定为 404（抛异常）：缓存命中则不触达；窗口关闭后走容器读 → __NOT_FOUND__
        var filesMock = osb.files();
        org.mockito.Mockito.doThrow(sandboxApi404())
            .when(filesMock).readByteArray("/workspace/skills/pdf/SKILL.md");
        var sandbox = readerInstance(osb, reader, false);
        sandbox.setUserKey("alice");

        sandbox.materializeUserSkills();
        var probe = sandbox.exec(RuntimeContext.builder().userId("alice").build(),
            probeRead("/workspace/skills/pdf/SKILL.md", 50), 30);

        assertEquals("# cached skill body", probe.stdout(), "物化写入的内容必须由直供缓存应答");
        verify(osb.files(), never()).readByteArray("/workspace/skills/pdf/SKILL.md");

        // 真实 exec → 窗口关闭：同一条探测改为容器读（未桩定 → __NOT_FOUND__，证明不再走缓存）
        sandbox.exec(RuntimeContext.builder().userId("alice").build(), "echo real", 30);
        var after = sandbox.exec(RuntimeContext.builder().userId("alice").build(),
            probeRead("/workspace/skills/pdf/SKILL.md", 50), 30);
        assertEquals("__NOT_FOUND__", after.stdout(), "真实 exec 后直供窗口必须关闭");
        verify(osb.files(), atLeastOnce()).readByteArray("/workspace/skills/pdf/SKILL.md");
    }

    /** D5：test -d 目录直查——不经 readByteArray（杜绝目录 500 异常路径） */
    @Test
    void testDashDShouldUseDirectoryListingOnly() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var files = mock(Filesystem.class);
        when(osb.files()).thenReturn(files);
        when(files.listDirectory("/workspace")).thenReturn(List.of());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), "test -d /workspace", 30);

        assertEquals(0, result.exitCode());
        verify(files, never()).readByteArray(org.mockito.ArgumentMatchers.anyString());
        verify(files, atLeastOnce()).listDirectory("/workspace");
    }

    /** D5：test -e 父目录列举判存在（文件/目录通吃，单次 200/404） */
    @Test
    void testDashEShouldCheckViaParentListing() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var files = mock(Filesystem.class);
        when(osb.files()).thenReturn(files);
        var entry = mock(com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo.class);
        when(entry.getPath()).thenReturn("/workspace/memory/diary.md");
        when(files.listDirectory("/workspace/memory")).thenReturn(List.of(entry));
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var hit = sandbox.exec(RuntimeContext.builder().userId("u").build(),
            "test -e '/workspace/memory/diary.md' && echo yes || echo no", 30);
        assertEquals("yes", hit.stdout(), "父目录列举命中叶名即存在");

        var miss = sandbox.exec(RuntimeContext.builder().userId("u").build(),
            "test -e '/workspace/memory/absent.md' && echo yes || echo no", 30);
        assertEquals("no", miss.stdout(), "父目录列举无叶名 → 不存在（一次 200/404 语义）");
    }

    /** D6：补投开关关闭时 stop() 不再收尾投放 .skills-cache */
    @Test
    void stopShouldSkipScopedBackfillWhenFlagDisabled() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var st = state();
        st.setSandboxId("sbx-backfill-1");
        var sandbox = org.mockito.Mockito.spy(new OpenSandbox(st, osb, options(), null, null,
            new OpenSandboxFilesystemSpec(), null, null, true, true, false, false));

        sandbox.exec(RuntimeContext.builder().userId("u").build(), "echo x", 30);
        sandbox.stop();

        org.mockito.Mockito.verify(sandbox, never()).hydrateScopedSkillCache();
    }

    /** D6：默认（开关 true）保持旧行为——stop 收尾补投仍在 */
    @Test
    void stopShouldKeepScopedBackfillByDefault() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var st = state();
        st.setSandboxId("sbx-backfill-2");
        var sandbox = org.mockito.Mockito.spy(new OpenSandbox(st, osb, options(), null, null,
            new OpenSandboxFilesystemSpec(), null, null, true, true, false, true));

        sandbox.exec(RuntimeContext.builder().userId("u").build(), "echo x", 30);
        sandbox.stop();

        org.mockito.Mockito.verify(sandbox, atLeastOnce()).hydrateScopedSkillCache();
    }

    // ---------- present_file 交付 seam：statWorkspaceFile / openWorkspaceStream（issue #345 P3） ----------

    private static EntryInfo fileEntry(String path, long size) {
        var epoch = java.time.OffsetDateTime.ofInstant(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC);
        return new EntryInfo(path, 644, "root", "root", size, epoch, epoch, "file");
    }

    @Test
    void statWorkspaceFileShouldMatchEntryByBasenameAndReturnSize() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var files = osb.files();
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        // 多级路径形态：relPath 相对 /workspace，entry.getPath 带目录前缀——按 basename 等值匹配
        when(files.listDirectory("tree/sub"))
            .thenReturn(List.of(fileEntry("/workspace/tree/sub/x.md", 42)));
        assertEquals(42, sandbox.statWorkspaceFile("tree/sub/x.md"));

        // 无目录前缀形态：根下文件，dir 为空串
        when(files.listDirectory("")).thenReturn(List.of(fileEntry("MEMORY.md", 7)));
        assertEquals(7, sandbox.statWorkspaceFile("MEMORY.md"));
    }

    @Test
    void statWorkspaceFileShouldDegradeToMinusOneOnListFailureAndMissingEntry() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var files = osb.files();
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        // 链式单次打桩（thenThrow → thenReturn），规避 throwing 桩重打桩触发旧桩（同 §execTranslate）
        // 父目录列举失败（execd 5xx）：降级 -1 交调用方走 writeBounded 兜底
        when(files.listDirectory("docs")).thenThrow(sandboxApi500())
            .thenReturn(List.of(fileEntry("/workspace/docs/other.txt", 9)));
        assertEquals(-1, sandbox.statWorkspaceFile("docs/report.pdf"), "列举失败降级 -1");

        // 列举成功但入口缺失：同样 -1（缺失非错误，不抛）
        assertEquals(-1, sandbox.statWorkspaceFile("docs/report.pdf"), "入口缺失降级 -1");

        assertEquals(-1, sandbox.statWorkspaceFile(" "), "blank 路径防御性降级 -1");
    }

    @Test
    void workspaceFileAccessShouldHydrateScopedSkillCacheForSkillsCachePathOnly() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var files = osb.files();
        var sandbox = org.mockito.Mockito.spy(new OpenSandbox(state(), osb, options(), null, null));
        doNothing().when(sandbox).hydrateScopedSkillCache();

        // 非 .skills-cache 路径：stat / 读流均不触发投放
        when(files.readStream("MEMORY.md"))
            .thenReturn(new ByteArrayInputStream("mem".getBytes(StandardCharsets.UTF_8)));
        assertEquals("mem", new String(sandbox.openWorkspaceStream("MEMORY.md").readAllBytes(),
            StandardCharsets.UTF_8), "openWorkspaceStream 返回 execd 读流原文");
        verify(sandbox, never()).hydrateScopedSkillCache();

        // .skills-cache 命中：stat 与读流各自触发一次延迟投放（未投放目录 stat/读流恒失败）
        when(files.listDirectory(".skills-cache/sess-A/oaf-package/demo-a")).thenReturn(List.of());
        assertEquals(-1, sandbox.statWorkspaceFile(".skills-cache/sess-A/oaf-package/demo-a/SKILL.md"));
        verify(sandbox, times(1)).hydrateScopedSkillCache();

        when(files.readStream(".skills-cache/sess-A/oaf-package/demo-a/SKILL.md"))
            .thenReturn(new ByteArrayInputStream(new byte[]{1}));
        sandbox.openWorkspaceStream(".skills-cache/sess-A/oaf-package/demo-a/SKILL.md");
        verify(sandbox, times(2)).hydrateScopedSkillCache();
        verify(files).readStream(".skills-cache/sess-A/oaf-package/demo-a/SKILL.md");

        // openWorkspaceStream 防御：blank 路径拒绝（非法输入不应触达 execd）
        assertThrows(IllegalArgumentException.class, () -> sandbox.openWorkspaceStream(" "));
    }

    // ---------- 软链农场（skills-syncd §6.2/§6.4/§7.2：会话级二值判定 + 软链农场 + D-5 打点） ----------

    private static final String FARM_STORE = "/skills-store";

    /** 真实端点指纹服务（SkillManageService 指向临时 configDir/skills，TTL 与生产同参） */
    private static SkillBundleService bundleService(Path configDir) {
        var props = new AgentManagerProperties(null, null, null, configDir.toString(),
            null, null, null, null, null);
        return new SkillBundleService(new SkillManageService(props),
            configDir.resolve("bundle-cache"), SkillBundleService.VERSION_TTL,
            java.time.Clock.systemUTC());
    }

    private static void bundleSkill(Path configDir, String name) throws Exception {
        var dir = configDir.resolve("skills").resolve(name);
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.writeString(dir.resolve("SKILL.md"), "---\nname: " + name + "\n---\nhi");
    }

    private static OpenSandboxClientOptions farmOptions(SkillBundleService bundleService,
                                                        io.micrometer.core.instrument.MeterRegistry registry,
                                                        SkillDisabledChecker checker) {
        return options().skillDisabledChecker(checker)
            .skillsSharedStore(FARM_STORE)
            .skillBundleService(bundleService)
            .farmMeterRegistry(registry);
    }

    /** commands.run 分流桩：readlink 探测按 storeVersion 应答（null = 无 CURRENT），test -d 按
     * storeDirExists 应答（S15：store 目录由守护启动时创建，存在性 = 守护存活信号），其余
     * （农场收敛/投放）恒成功 */
    private static Sandbox farmOsb(String storeVersion, boolean storeDirExists) {
        var osb = mock(Sandbox.class);
        var commands = mock(Commands.class);
        when(osb.commands()).thenReturn(commands);
        when(commands.run(anyString())).thenAnswer(inv -> {
            var cmd = inv.getArgument(0, String.class);
            if (cmd.startsWith("readlink ")) {
                return storeVersion == null ? mockExecution(1, "", "") : mockExecution(0, storeVersion, "");
            }
            if (cmd.startsWith("test -d ")) {
                return storeDirExists ? mockExecution(0, "", "") : mockExecution(1, "", "");
            }
            return mockExecution(0, "", "");
        });
        var files = mock(Filesystem.class);
        when(osb.files()).thenReturn(files);
        return osb;
    }

    private static Sandbox farmOsb(String storeVersion) {
        return farmOsb(storeVersion, storeVersion != null);
    }

    /** store 探测应答动态跟踪端点版本（版本切换/内容变更场景）：readlink 应答 v-&lt;当前版本&gt; */
    private static Sandbox farmOsbTracking(SkillBundleService svc) {
        var osb = mock(Sandbox.class);
        var commands = mock(Commands.class);
        when(osb.commands()).thenReturn(commands);
        when(commands.run(anyString())).thenAnswer(inv -> {
            var cmd = inv.getArgument(0, String.class);
            if (cmd.startsWith("readlink ")) {
                return mockExecution(0, "v-" + svc.currentVersion().version(), "");
            }
            return mockExecution(0, "", "");
        });
        when(osb.files()).thenReturn(mock(Filesystem.class));
        return osb;
    }

    /** 零 TTL 版本服务：内容变更立即可见（D-4 TTL 缓存会吞掉测试内的包源变更） */
    private static SkillBundleService bundleServiceLive(Path configDir) {
        var props = new AgentManagerProperties(null, null, null, configDir.toString(),
            null, null, null, null, null);
        return new SkillBundleService(new SkillManageService(props),
            configDir.resolve("bundle-cache"), java.time.Duration.ZERO,
            java.time.Clock.systemUTC());
    }

    /** 捕获农场收敛 exec（含引号容器路径段的命令；tar 物化/readlink/用户命令均不含该形态） */
    private static List<String> farmConvergeCommands(Sandbox osb) throws Exception {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(osb.commands(), atLeastOnce()).run(captor.capture());
        return captor.getAllValues().stream()
            .filter(c -> c.contains("'/workspace/.skills-cache/"))
            .toList();
    }

    private static int farmReadlinkCount(Sandbox osb) throws Exception {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(osb.commands(), atLeastOnce()).run(captor.capture());
        return (int) captor.getAllValues().stream().filter(c -> c.startsWith("readlink ")).count();
    }

    private static OpenSandboxState farmState(Path host, String sandboxId) {
        var st = projectedStateNoSkillCache(host);
        st.setSandboxId(sandboxId);
        return st;
    }

    /** 无投影 entry 形态（SANDBOX_PROJECTION_ENABLED=false）：spec 未装配 __workspace_projection__ */
    private static OpenSandboxState farmStateNoProjection(String sandboxId) {
        var st = state();
        st.setSandboxId(sandboxId);
        return st;
    }

    private static void bindFarmSession(OpenSandbox sandbox, String scope) {
        sandbox.setUserKey(scope);
        sandbox.setSkillCacheScope(scope);
    }

    @Test
    void farmSwitchOffShouldDeliverNoPackageSkills() throws Exception {
        // PR-4b：开关（store 路径）为空 = 总闸关闭 = 包技能不投放（无 tar 回落，回滚 = 回滚整个 release）
        var host = java.nio.file.Files.createTempDirectory("farm-off");
        writeHostSkill(host, "sess-A", "demo-a");
        var osb = farmOsb("v-anything");

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-off"), osb, options(), null, null);
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(0, farmReadlinkCount(osb), "开关（store 路径）为空不得探测 store");
        assertTrue(farmConvergeCommands(osb).isEmpty(), "开关关闭不得收敛建链");
        verify(osb.files(), never()).write(anyList());
    }

    @Test
    void farmShouldConvergeWithoutProjectionEntry() throws Exception {
        // issue #392：SANDBOX_PROJECTION_ENABLED=false（spec 无 __workspace_projection__ entry）
        // 不再连带关闭软链农场——投影开关只管 AGENTS.md/skills/subagents/knowledge 投影，
        // 包技能投放唯一路径是农场（PR-4b 后 tar 已退役）。state() 形态 spec（无投影 entry）：
        // 投影 entry 早退门控在位时本用例密（农场被连带早退、零收敛），门控删除后通
        var cfg = java.nio.file.Files.createTempDirectory("farm-no-proj-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var version = svc.currentVersion().version();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsb("v-" + version);

        var sandbox = new OpenSandbox(farmStateNoProjection("sbx-farm-noproj"), osb,
            farmOptions(svc, registry, null), null, null, new OpenSandboxFilesystemSpec());
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(1, farmConvergeCommands(osb).size(), "无投影 entry 农场仍收敛建链");
        verify(osb.files(), never()).write(anyList());
        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "farm").counter().count(),
            "farm 判定打点不受投影开关影响");
    }

    @Test
    void farmOffSentinelShouldDeliverNoPackageSkills() throws Exception {
        // issue #392 评审 g1：平台 env 写路径空串=删除（publish.go dropEmptyEnv），「置空关闸」
        // 平台不可送达——off 哨兵（忽略大小写与首尾空白）是平台表单可送达的显式关闸形态，
        // 行为须与空串总闸完全一致（零探测/零收敛/零写入）
        var host = java.nio.file.Files.createTempDirectory("farm-off-sentinel");
        writeHostSkill(host, "sess-A", "demo-a");
        var osb = farmOsb("v-anything");

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-off-sentinel"), osb,
            options().skillsSharedStore("off"), null, null);
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(0, farmReadlinkCount(osb), "off 哨兵不得探测 store");
        assertTrue(farmConvergeCommands(osb).isEmpty(), "off 哨兵不得收敛建链");
        verify(osb.files(), never()).write(anyList());
        // 关闸判定契约（SkillsFarm.enabled 与 AgentScopeConfig 启动 WARN 共用单一谓词）：
        // 大小写与首尾空白宽限对齐表单人工输入形态；空值恒关闸
        assertTrue(OpenSandboxClientOptions.skillsSharedStoreOff(" OFF "),
            "off 哨兵忽略大小写与首尾空白");
        assertTrue(OpenSandboxClientOptions.skillsSharedStoreOff(null));
        assertTrue(OpenSandboxClientOptions.skillsSharedStoreOff("  "));
        assertFalse(OpenSandboxClientOptions.skillsSharedStoreOff("/skills-store"));
    }

    @Test
    void farmModeShouldLinkPackageSkillsAndSkipTar() throws Exception {
        var host = java.nio.file.Files.createTempDirectory("farm-link");
        writeHostSkill(host, "sess-A", "demo-a");
        writeHostSkill(host, "sess-A", "demo-b");
        var cfg = java.nio.file.Files.createTempDirectory("farm-link-cfg");
        bundleSkill(cfg, "demo-a");
        bundleSkill(cfg, "demo-b");
        var svc = bundleService(cfg);
        var version = svc.currentVersion().version();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsb("v-" + version);

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-1"), osb,
            farmOptions(svc, registry, null), null, null, new OpenSandboxFilesystemSpec());
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        verify(osb.commands()).run("readlink '" + FARM_STORE + "/CURRENT'");
        verify(osb.commands(), never()).run(startsWith("test -d "));
        var converge = farmConvergeCommands(osb);
        assertEquals(1, converge.size(), "农场收敛为单条 exec");
        var cmd = converge.get(0);
        String scopeRoot = "/workspace/.skills-cache/sess-A/oaf-package";
        assertTrue(cmd.contains("mkdir -p '" + scopeRoot + "'"), cmd);
        assertTrue(cmd.contains("rm -rf '" + scopeRoot + "/demo-a'"), cmd);
        assertTrue(cmd.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-a' '" + scopeRoot + "/demo-a'"), cmd);
        assertTrue(cmd.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-b' '" + scopeRoot + "/demo-b'"), cmd);
        assertTrue(cmd.contains("find /workspace/.skills-cache") && cmd.contains("! -name 'sess-A'"),
            "农场 exec 保留其它 scope 清理语义：" + cmd);
        verify(osb.files(), never()).write(anyList());
        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "farm").counter().count(),
            "D-5：farm 判定打点");
    }

    @Test
    void farmStoreMissingShouldDegradeSessionWithNoPackageSkills() throws Exception {
        var host = java.nio.file.Files.createTempDirectory("farm-missing");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-missing-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsb(null, false);

        var spec = new OpenSandboxFilesystemSpec();
        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-2"), osb,
            farmOptions(svc, registry, null), null, null, spec);
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "degraded_store_missing")
            .counter().count(), "D-5：store 缺失降级打点");
        assertTrue(farmConvergeCommands(osb).isEmpty(), "降级会话不得建软链");
        // 降级会话无 tar 回落（PR-4b）
        verify(osb.files(), never()).write(anyList());

        var sandbox2 = new OpenSandbox(farmState(host, "sbx-farm-2"), osb,
            farmOptions(svc, registry, null), null, null, spec);
        bindFarmSession(sandbox2, "sess-A");
        sandbox2.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(1, farmReadlinkCount(osb), "降级会话记忆：不再重复判定");
        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "degraded_store_missing")
            .counter().count(), "记忆命中不重复打点");
    }

    @Test
    void farmDaemonAliveButSyncFailedShouldDegradeAsEndpointDown() throws Exception {
        // S15：store 目录由守护启动时创建——目录存在但无 CURRENT = 守护活着但同步失败，
        // 第一根因即守护→端点链路不可达（egress 断网），
        // 归 degraded_endpoint_down（runbook 信号直指端点链路），不得归 store_missing
        var host = java.nio.file.Files.createTempDirectory("farm-dead-endpoint");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-dead-endpoint-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsb(null, true);

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-dead-ep"), osb,
            farmOptions(svc, registry, null), null, null, new OpenSandboxFilesystemSpec());
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "degraded_endpoint_down")
            .counter().count(), "D-5：守护活着但同步失败 = 端点链路不可达降级");
        assertTrue(farmConvergeCommands(osb).isEmpty(), "降级会话不得建软链");
        verify(osb.files(), never()).write(anyList());
    }

    @Test
    void farmEndpointFingerprintErrorShouldDegradeAsEndpointDown() throws Exception {
        // 指纹计算失败（computeVersion 全 catch → ERR-*，输出约定见 SkillBundleServiceTest）=
        // 端点无法提供有效版本：判定与 svc 未装配同类归 degraded_endpoint_down，不得落
        // version_mismatch（版本端点同时 503，守护退避重试不污染 store）
        var host = java.nio.file.Files.createTempDirectory("farm-err-endpoint");
        writeHostSkill(host, "sess-A", "demo-a");
        var svc = mock(SkillBundleService.class);
        when(svc.currentVersion())
            .thenReturn(new SkillBundleService.SkillVersion("ERR-123456", 0, 0, 0L, java.util.List.of()));
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsb("v-0123456789abcdef", true);

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-err-ep"), osb,
            farmOptions(svc, registry, null), null, null, new OpenSandboxFilesystemSpec());
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "degraded_endpoint_down")
            .counter().count(), "D-5：指纹计算失败 = 端点不可用降级");
        assertTrue(farmConvergeCommands(osb).isEmpty(), "降级会话不得建软链");
        verify(osb.files(), never()).write(anyList());
    }

    @Test
    void farmVersionMismatchShouldDegradeSessionWithNoPackageSkills() throws Exception {
        var host = java.nio.file.Files.createTempDirectory("farm-mismatch");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-mismatch-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsb("v-0123456789abcdef");

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-3"), osb,
            farmOptions(svc, registry, null), null, null, new OpenSandboxFilesystemSpec());
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "degraded_version_mismatch")
            .counter().count(), "D-5：版本硬校验失败降级打点");
        assertTrue(farmConvergeCommands(osb).isEmpty(), "硬校验失败 = store 不可用，本会话无包技能投放");
        verify(osb.files(), never()).write(anyList());
    }

    @Test
    void farmEndpointUnwiredShouldDegradeSessionWithNoPackageSkills() throws Exception {
        var host = java.nio.file.Files.createTempDirectory("farm-endpoint");
        writeHostSkill(host, "sess-A", "demo-a");
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsb("v-0123456789abcdef");

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-4"), osb,
            options().skillsSharedStore(FARM_STORE).farmMeterRegistry(registry),
            null, null, new OpenSandboxFilesystemSpec());
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "degraded_endpoint_down")
            .counter().count(), "D-5：端点不可达降级打点");
        assertTrue(farmConvergeCommands(osb).isEmpty(), "端点不可达 = 无包技能投放（PR-4b）");
        verify(osb.files(), never()).write(anyList());
    }

    @Test
    void farmDecisionShouldReDecideOnlyForNewSession() throws Exception {
        var host = java.nio.file.Files.createTempDirectory("farm-remember");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-remember-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var version = svc.currentVersion().version();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsb("v-" + version);
        var spec = new OpenSandboxFilesystemSpec();

        var call1 = new OpenSandbox(farmState(host, "sbx-farm-5"), osb,
            farmOptions(svc, registry, null), null, null, spec);
        bindFarmSession(call1, "sess-A");
        call1.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        var call2 = new OpenSandbox(farmState(host, "sbx-farm-5"), osb,
            farmOptions(svc, registry, null), null, null, spec);
        bindFarmSession(call2, "sess-A");
        call2.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        var call3 = new OpenSandbox(farmState(host, "sbx-farm-6"), osb,
            farmOptions(svc, registry, null), null, null, spec);
        bindFarmSession(call3, "sess-A");
        call3.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(2, farmReadlinkCount(osb), "同会话记忆一次判定，新会话（新 sandboxId）重新判定");
        assertEquals(2, farmConvergeCommands(osb).size(),
            "稳态短路（D-10）：call2 同沙箱代同指纹跳过收敛 exec，call1/call3（新代）各收敛一次");
        assertEquals(2.0, registry.get("skills.farm.decision").tag("result", "farm").counter().count());
    }

    @Test
    void farmOrphanLinkShouldBeRemovedWhenRemovedFromManifest() throws Exception {
        // §6.2-3「农场触发时对 manifest 已禁用/移除的既有软链执行删除」：宿主 stage 已无 demo-b
        // （包版本移除后端点版本变化 → 指纹变化 → 重收敛），容器内旧 demo-b 软链为孤儿——
        // 不在 linkCandidates 内，必须被收敛 exec 清理（否则悬空链永久滞留，load 撞已删内容）。
        // demo-a 照常建链。真实场景实测（kind 三路径验收）揪出：PR-4a 起孤儿链即不清理。
        var host = java.nio.file.Files.createTempDirectory("farm-orphan");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-orphan-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var version = svc.currentVersion().version();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = mock(Sandbox.class);
        var commands = mock(Commands.class);
        when(osb.commands()).thenReturn(commands);
        when(osb.files()).thenReturn(mock(Filesystem.class));
        String scopeAll = "/workspace/.skills-cache/sess-A";
        String scopeRoot = scopeAll + "/oaf-package";
        when(commands.run(anyString())).thenAnswer(inv -> {
            var cmd = inv.getArgument(0, String.class);
            if (cmd.startsWith("readlink ")) {
                return mockExecution(0, "v-" + version, "");
            }
            return mockExecution(0, "", "");
        });

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-orphan"), osb,
            farmOptions(svc, registry, null), null, null, new OpenSandboxFilesystemSpec());
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        var cmd = farmConvergeCommands(osb).get(0);
        assertTrue(cmd.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-a' '" + scopeRoot + "/demo-a'"),
            "候选内技能照常建链：" + cmd);
        // 差集清理段：枚举容器软链，与候选字面集（仅 demo-a）比对——集外（demo-b 孤儿）rm
        assertTrue(cmd.contains("find '" + scopeAll + "' -type l"), "收敛 exec 内嵌孤儿枚举：" + cmd);
        assertTrue(cmd.contains("case '|oaf-package/demo-a|' in"), "候选字面集不含已移除的 demo-b：" + cmd);
        assertTrue(cmd.contains("*) rm -rf \"$__f\" ;; esac"), "集外软链差集删除：" + cmd);
        assertFalse(cmd.contains("|oaf-package/demo-b|"), "已移除技能不得进入候选字面集：" + cmd);
    }

    @Test
    void farmShouldSkipUnsafeNamesFromManifestWithoutBreakingConverge() throws Exception {
        // issue #501 farm 侧防线：端点 manifest 直接携带不安全规范名（包内目录名本身含 |，
        // 无 frontmatter → 规范名回落目录名）——| 是孤儿差集 case 字面集分隔符，若进候选集，
        // 孤儿 oaf-package/foo 会误命中 |oaf-package/foo|dir| 边界而永久滞留。不安全名整体
        // 跳过（会话内缺失 + WARN 留痕），合法技能建链与差集清理不受影响
        var host = java.nio.file.Files.createTempDirectory("farm-unsafe-name");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-unsafe-name-cfg");
        bundleSkill(cfg, "demo-a");
        var pipeDir = cfg.resolve("skills").resolve("pipe|dir");
        java.nio.file.Files.createDirectories(pipeDir);
        java.nio.file.Files.writeString(pipeDir.resolve("SKILL.md"), "# 无 frontmatter\n");
        var svc = bundleService(cfg);
        var version = svc.currentVersion().version();
        assertEquals(java.util.List.of("demo-a", "pipe|dir"),
            svc.currentVersion().skills(), "前置：manifest 确实携带不安全目录名");
        var osb = mock(Sandbox.class);
        var commands = mock(Commands.class);
        when(osb.commands()).thenReturn(commands);
        when(osb.files()).thenReturn(mock(Filesystem.class));
        when(commands.run(anyString())).thenAnswer(inv -> {
            var cmd = inv.getArgument(0, String.class);
            if (cmd.startsWith("readlink ")) {
                return mockExecution(0, "v-" + version, "");
            }
            return mockExecution(0, "", "");
        });

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-unsafe"), osb,
            farmOptions(svc, null, null), null, null, new OpenSandboxFilesystemSpec());
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        var cmd = farmConvergeCommands(osb).get(0);
        assertTrue(cmd.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-a' "
                + "'/workspace/.skills-cache/sess-A/oaf-package/demo-a'"),
            "合法技能照常建链（不安全名不得中断 && 链）：" + cmd);
        assertFalse(cmd.contains("pipe|dir"), "不安全名不得进入收敛命令任何段（建链/禁用清理/孤儿差集）：" + cmd);
        assertTrue(cmd.contains("case '|oaf-package/demo-a|' in"),
            "孤儿差集字面集只含合法候选，| 分隔符无歧义：" + cmd);
    }

    @Test
    void farmConvergeShouldRebuildRealFileAndDanglingLink() throws Exception {
        // D-2/§10.2「悬空链重建 + 降级会话 tar 物化残留真实文件统一收敛为软链」：农场对 manifest
        // 内包技能路径有全权，路径现存任何物目标态一律 rm -rf + ln -s 重建为软链，不得因「已存在」
        // 跳过。mock 架构下容器态以存在性探测应答预置：demo-a = 降级会话 tar 物化残留真实文件
        // （探测默认应答即「存在」），demo-b = 指向已移除版本的悬空链（链接本体在、目标已删，
        // test -e 失效）——未来「存在即跳过」优化会在此断言处回归。
        var host = java.nio.file.Files.createTempDirectory("farm-residue");
        writeHostSkill(host, "sess-A", "demo-a");
        writeHostSkill(host, "sess-A", "demo-b");
        var cfg = java.nio.file.Files.createTempDirectory("farm-residue-cfg");
        bundleSkill(cfg, "demo-a");
        bundleSkill(cfg, "demo-b");
        var svc = bundleService(cfg);
        var version = svc.currentVersion().version();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = mock(Sandbox.class);
        var commands = mock(Commands.class);
        when(osb.commands()).thenReturn(commands);
        when(osb.files()).thenReturn(mock(Filesystem.class));
        String scopeRoot = "/workspace/.skills-cache/sess-A/oaf-package";
        when(commands.run(anyString())).thenAnswer(inv -> {
            var cmd = inv.getArgument(0, String.class);
            if (cmd.startsWith("readlink ")) {
                return mockExecution(0, "v-" + version, "");
            }
            if (cmd.equals("test -e '" + scopeRoot + "/demo-b'")) {
                return mockExecution(1, "", "");   // 悬空链：test -e 失效（其余存在性探测恒「存在」）
            }
            return mockExecution(0, "", "");
        });

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-residue"), osb,
            farmOptions(svc, registry, null), null, null, new OpenSandboxFilesystemSpec());
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "farm").counter().count(),
            "前提：会话判定为 farm（残留收敛语义只在 farm 收敛路径生效）");
        var cmd = farmConvergeCommands(osb).get(0);
        assertTrue(cmd.contains("rm -rf '" + scopeRoot + "/demo-a'"),
            "tar 物化残留真实文件统一收敛为软链：" + cmd);
        assertTrue(cmd.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-a' '" + scopeRoot + "/demo-a'"), cmd);
        assertTrue(cmd.contains("rm -rf '" + scopeRoot + "/demo-b'"), "悬空链重建：" + cmd);
        assertTrue(cmd.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-b' '" + scopeRoot + "/demo-b'"), cmd);
    }

    @Test
    void farmDisabledSkillsShouldNotLinkAndRemoveResidue() throws Exception {
        var host = java.nio.file.Files.createTempDirectory("farm-disabled");
        writeHostSkill(host, "sess-A", "demo-a");
        writeHostSkill(host, "sess-A", "demo-b");
        writeHostSkill(host, "sess-A", "demo-c");
        var cfg = java.nio.file.Files.createTempDirectory("farm-disabled-cfg");
        bundleSkill(cfg, "demo-a");
        bundleSkill(cfg, "demo-b");
        bundleSkill(cfg, "demo-c");
        var svc = bundleService(cfg);
        var version = svc.currentVersion().version();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsb("v-" + version);
        var disabledNames = new java.util.HashSet<>(java.util.Set.of("demo-c"));
        var checker = new SkillDisabledChecker() {
            @Override
            public java.util.Set<String> packageDisabled() {
                return java.util.Set.of("demo-b");
            }

            @Override
            public boolean isUserDisabled(String userId, String name) {
                return disabledNames.contains(name);
            }
        };

        var spec = new OpenSandboxFilesystemSpec();
        // 用户级禁用变更经 L4 revision bump 进入指纹（D-10）：短路后指纹不变则不会重收敛，
        // 会话中途禁用生效依赖 revision 段参与比对（生产 WorkspaceReader 所有 L4 写删漏斗必 bump）
        var reader = mock(WorkspaceReader.class);
        var rev = new java.util.concurrent.atomic.AtomicReference<>("rev-1");
        when(reader.readUserSkillRevision(anyString())).thenAnswer(inv -> rev.get());
        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-7"), osb,
            farmOptions(svc, registry, checker), reader, null, spec);
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        var cmd = farmConvergeCommands(osb).get(0);
        String scopeRoot = "/workspace/.skills-cache/sess-A/oaf-package";
        assertTrue(cmd.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-a'"), cmd);
        assertFalse(cmd.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-b'"), "L2 全局禁用不建链：" + cmd);
        assertFalse(cmd.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-c'"), "L4 用户禁用不建链：" + cmd);
        assertTrue(cmd.contains("rm -rf '" + scopeRoot + "/demo-b'"), "全局禁用随农场 exec 清理容器残留：" + cmd);
        assertTrue(cmd.contains("rm -rf '" + scopeRoot + "/demo-c'"), "用户禁用随农场 exec 清理既有软链：" + cmd);

        disabledNames.add("demo-a");
        rev.set("rev-2");
        var sandbox2 = new OpenSandbox(farmState(host, "sbx-farm-7"), osb,
            farmOptions(svc, registry, checker), reader, null, spec);
        bindFarmSession(sandbox2, "sess-A");
        sandbox2.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        var cmd2 = farmConvergeCommands(osb).get(1);
        assertFalse(cmd2.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-a'"), "会话中途禁用：不再建链");
        assertTrue(cmd2.contains("rm -rf '" + scopeRoot + "/demo-a'"), "会话中途禁用：删除既有软链");
    }

    @Test
    void farmSteadyShouldSkipConvergeExecUntilEndpointVersionChanges() throws Exception {
        // D-10（PR-4b 口径）：稳态指纹 = 端点版本 + L4 revision——同沙箱代同指纹 0 exec；
        // 端点版本变化（包内容/全局禁用变更 → 发布语义）才重收敛。同代内不再收敛同时是
        // 「会话中途容器重启（同 sandboxId resume）软链不重建」盲点的代码事实（§6.2-4 已接受）。
        var host = java.nio.file.Files.createTempDirectory("farm-steady");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-steady-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleServiceLive(cfg);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsbTracking(svc);
        var spec = new OpenSandboxFilesystemSpec();

        var call1 = new OpenSandbox(farmState(host, "sbx-farm-steady"), osb,
            farmOptions(svc, registry, null), null, null, spec);
        bindFarmSession(call1, "sess-A");
        call1.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        var call2 = new OpenSandbox(farmState(host, "sbx-farm-steady"), osb,
            farmOptions(svc, registry, null), null, null, spec);
        bindFarmSession(call2, "sess-A");
        call2.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(1, farmConvergeCommands(osb).size(), "同代同指纹稳态：第二次触发不跑收敛 exec（0 exec）");
        assertEquals(1, farmReadlinkCount(osb), "判定记忆：稳态不重复判定");
        verify(osb.files(), never()).write(anyList());

        // 端点版本变更（包源内容变更 → 发布语义）→ 指纹变化 → 重收敛
        java.nio.file.Files.writeString(cfg.resolve("skills/demo-a/SKILL.md"), "---\nname: demo-a\n---\nchanged");
        java.nio.file.Files.setLastModifiedTime(cfg.resolve("skills/demo-a/SKILL.md"),
            java.nio.file.attribute.FileTime.fromMillis(1777777777777L));
        var call3 = new OpenSandbox(farmState(host, "sbx-farm-steady"), osb,
            farmOptions(svc, registry, null), null, null, spec);
        bindFarmSession(call3, "sess-A");
        call3.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(2, farmConvergeCommands(osb).size(), "端点版本变化必须重收敛（否则新内容不进链）");
    }

    @Test
    void farmRecoverToFarmAfterDegradedSessionShouldConverge() throws Exception {
        // 同沙箱代降级 → gate 丢失（框架重启/漂副本/LRU 逐出）→ 守护就绪重判定 farm。
        // PR-4b：降级会话零动作（无 tar 物化记账），farm 记账本就不存在 → 正常收敛建链；
        // 记账 mode|指纹 分量保留为升级窗口/未来模式的防陈旧护栏（D-11 机制不删）
        var host = java.nio.file.Files.createTempDirectory("farm-flip-f");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-flip-f-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var version = svc.currentVersion().version();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

        // store 探测应答可变：首会话无 CURRENT（守护未就绪 → 降级），恢复后切到版本目录（farm）
        var osb = mock(Sandbox.class);
        var commands = mock(Commands.class);
        when(osb.commands()).thenReturn(commands);
        when(osb.files()).thenReturn(mock(Filesystem.class));
        var current = new java.util.concurrent.atomic.AtomicReference<>(null);
        when(commands.run(anyString())).thenAnswer(inv -> {
            var cmd = inv.getArgument(0, String.class);
            if (cmd.startsWith("readlink ")) {
                var v = (String) current.get();
                return v == null ? mockExecution(1, "", "") : mockExecution(0, v, "");
            }
            return mockExecution(0, "", "");
        });

        var spec = new OpenSandboxFilesystemSpec();
        var call1 = new OpenSandbox(farmState(host, "sbx-flip-f"), osb,
            farmOptions(svc, registry, null), null, null, spec);
        bindFarmSession(call1, "sess-A");
        call1.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertTrue(farmConvergeCommands(osb).isEmpty(), "降级会话不得建软链");
        verify(osb.files(), never()).write(anyList());

        // gate 丢失而指纹记账存续：移除会话 gate（同 sandboxId），翻转 store 探测为 farm
        spec.removeSkillSyncGate("sess-A", "sbx-flip-f");
        current.set("v-" + version);
        var call2 = new OpenSandbox(farmState(host, "sbx-flip-f"), osb,
            farmOptions(svc, registry, null), null, null, spec);
        bindFarmSession(call2, "sess-A");
        call2.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(1, farmConvergeCommands(osb).size(),
            "同代降级(tar)→farm 翻转：tar 记账不得满足 farm 稳态，必须重收敛建链（D-2/§10.3-11）");
        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "farm").counter().count(),
            "重判定按 farm 落打点");
    }

    @Test
    void farmDegradedWithinSameGenerationShouldKeepFarmSteady() throws Exception {
        // 同代 farm 收敛记账后 gate 丢失、重判定降级——PR-4b 降级会话零动作（不建链不物化），
        // 容器链接保持原样；再次 farm 且指纹未变 → 稳态跳过（B-1 的 tar 重物化分支已随 tar
        // 退役消失，farm 记账即容器事实）
        var host = java.nio.file.Files.createTempDirectory("farm-flip-t");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-flip-t-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var version = svc.currentVersion().version();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

        // store 探测应答可变：首会话 farm，翻转后无 CURRENT（降级）
        var osb = mock(Sandbox.class);
        var commands = mock(Commands.class);
        when(osb.commands()).thenReturn(commands);
        when(osb.files()).thenReturn(mock(Filesystem.class));
        var current = new java.util.concurrent.atomic.AtomicReference<>("v-" + version);
        when(commands.run(anyString())).thenAnswer(inv -> {
            var cmd = inv.getArgument(0, String.class);
            if (cmd.startsWith("readlink ")) {
                var v = (String) current.get();
                return v == null ? mockExecution(1, "", "") : mockExecution(0, v, "");
            }
            return mockExecution(0, "", "");
        });

        var spec = new OpenSandboxFilesystemSpec();
        var call1 = new OpenSandbox(farmState(host, "sbx-flip-t"), osb,
            farmOptions(svc, registry, null), null, null, spec);
        bindFarmSession(call1, "sess-A");
        call1.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(1, farmConvergeCommands(osb).size(), "farm 会话收敛建链并记账");

        spec.removeSkillSyncGate("sess-A", "sbx-flip-t");
        current.set(null);
        var call2 = new OpenSandbox(farmState(host, "sbx-flip-t"), osb,
            farmOptions(svc, registry, null), null, null, spec);
        bindFarmSession(call2, "sess-A");
        call2.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(1, farmConvergeCommands(osb).size(), "降级会话不得建软链（不再追加收敛 exec）");
        verify(osb.files(), never()).write(anyList());

        // 同代恢复 farm（gate 再丢 + store 探测恢复）：指纹未变 + farm 记账存续 → 稳态跳过
        spec.removeSkillSyncGate("sess-A", "sbx-flip-t");
        current.set("v-" + version);
        var call3 = new OpenSandbox(farmState(host, "sbx-flip-t"), osb,
            farmOptions(svc, registry, null), null, null, spec);
        bindFarmSession(call3, "sess-A");
        call3.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(1, farmConvergeCommands(osb).size(),
            "降级零动作 + farm 记账存续 → 稳态命中不重收敛（容器链接即最新）");
    }

    @Test
    void farmConvergeFailureShouldNotRecordAndRetryNextTrigger() throws Exception {
        // D-10：收敛 exec 失败 → 上抛（CAS 复位）且不记账——失败即记账会让稳态短路跳过重建，
        // 容器永久缺链；下次触发重收敛，成功后才记账进入稳态。
        var host = java.nio.file.Files.createTempDirectory("farm-retry");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-retry-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var version = svc.currentVersion().version();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = mock(Sandbox.class);
        var commands = mock(Commands.class);
        when(osb.commands()).thenReturn(commands);
        when(osb.files()).thenReturn(mock(Filesystem.class));
        var attempted = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(commands.run(anyString())).thenAnswer(inv -> {
            var cmd = inv.getArgument(0, String.class);
            if (cmd.startsWith("readlink ")) {
                return mockExecution(0, "v-" + version, "");
            }
            if (cmd.contains("ln -s ") && attempted.compareAndSet(false, true)) {
                return mockExecution(1, "", "converge failed");   // 首次收敛失败
            }
            return mockExecution(0, "", "");
        });
        var spec = new OpenSandboxFilesystemSpec();

        for (int i = 0; i < 3; i++) {
            var call = new OpenSandbox(farmState(host, "sbx-farm-retry"), osb,
                farmOptions(svc, registry, null), null, null, spec);
            bindFarmSession(call, "sess-A");
            call.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);
        }

        assertEquals(2, farmConvergeCommands(osb).size(),
            "call1 失败不记账、call2 重试成功（2 次收敛），call3 记账命中稳态跳过");
        assertEquals(1, farmReadlinkCount(osb), "判定记忆不受收敛失败影响");
    }

    @Test
    void farmSameNameL4SkillShouldWinAndCoexist() throws Exception {
        var host = java.nio.file.Files.createTempDirectory("farm-l4");
        writeHostSkill(host, "sess-A", "demo-a");
        writeHostSkill(host, "sess-A", "demo-b");
        var cfg = java.nio.file.Files.createTempDirectory("farm-l4-cfg");
        bundleSkill(cfg, "demo-a");
        bundleSkill(cfg, "demo-b");
        var svc = bundleService(cfg);
        var version = svc.currentVersion().version();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsb("v-" + version);
        var reader = mock(WorkspaceReader.class);
        when(reader.listUserSkills("sess-A")).thenReturn(java.util.Map.of("demo-a", List.of("SKILL.md")));
        when(reader.materializeUserSkills(any(), eq("sess-A"), any())).thenReturn(1);

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-8"), osb,
            farmOptions(svc, registry, null), reader, null, new OpenSandboxFilesystemSpec());
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        var inOrder = org.mockito.Mockito.inOrder(reader, osb.commands());
        inOrder.verify(reader).materializeUserSkills(any(), eq("sess-A"), any());
        inOrder.verify(osb.commands()).run(startsWith("readlink "));

        var cmd = farmConvergeCommands(osb).get(0);
        String scopeRoot = "/workspace/.skills-cache/sess-A/oaf-package";
        assertFalse(cmd.contains("'" + scopeRoot + "/demo-a'"), "同名 L4 用户技能优先：包技能跳过且路径不触碰：" + cmd);
        assertTrue(cmd.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-b' '" + scopeRoot + "/demo-b'"),
            "非同名包技能正常建链（与 L4 共存）：" + cmd);
    }

    @Test
    void farmL4DeleteShouldRelinkPackageSkillOnReconverge() throws Exception {
        // issue #399-1：l4Names 冻结于 gate（首判定快照整个沙箱代复用）——用户删除同名 L4 覆盖
        // （revision bump → farmFingerprint 变化触发重收敛）后，hydrate 仍用冻结快照跳过该名
        // 建链，包技能直到换沙箱代都不可达。非稳态重收敛必须重新 capture L4 名单（稳态短路
        // 路径零开销、零 KV 枚举不变）
        var host = java.nio.file.Files.createTempDirectory("farm-l4-del");
        writeHostSkill(host, "sess-A", "demo-a");
        writeHostSkill(host, "sess-A", "demo-b");
        var cfg = java.nio.file.Files.createTempDirectory("farm-l4-del-cfg");
        bundleSkill(cfg, "demo-a");
        bundleSkill(cfg, "demo-b");
        var svc = bundleService(cfg);
        var version = svc.currentVersion().version();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsb("v-" + version);
        var reader = mock(WorkspaceReader.class);
        // 轮 1：L4 存在同名 demo-a（revision rev-1）
        when(reader.listUserSkills("sess-A")).thenReturn(java.util.Map.of("demo-a", List.of("SKILL.md")));
        when(reader.readUserSkillRevision("sess-A")).thenReturn("rev-1");
        when(reader.materializeUserSkills(any(), eq("sess-A"), any())).thenReturn(1);
        var spec = new OpenSandboxFilesystemSpec();

        var call1 = new OpenSandbox(farmState(host, "sbx-farm-399"), osb,
            farmOptions(svc, registry, null), reader, null, spec);
        bindFarmSession(call1, "sess-A");
        call1.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        String scopeRoot = "/workspace/.skills-cache/sess-A/oaf-package";
        var cmd1 = farmConvergeCommands(osb).get(0);
        assertFalse(cmd1.contains("'" + scopeRoot + "/demo-a'"), "轮 1 同名 L4 优先跳过包技能建链：" + cmd1);
        assertTrue(cmd1.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-b' '" + scopeRoot + "/demo-b'"),
            "轮 1 非同名包技能正常建链：" + cmd1);

        // 用户删除 L4 demo-a：名单变空 + revision bump → 指纹变化 → 非稳态重收敛
        when(reader.listUserSkills("sess-A")).thenReturn(java.util.Map.of());
        when(reader.readUserSkillRevision("sess-A")).thenReturn("rev-2");

        var call2 = new OpenSandbox(farmState(host, "sbx-farm-399"), osb,
            farmOptions(svc, registry, null), reader, null, spec);
        bindFarmSession(call2, "sess-A");
        call2.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        var cmd2 = farmConvergeCommands(osb).get(1);
        assertTrue(cmd2.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-a' '" + scopeRoot + "/demo-a'"),
            "issue #399：删除 L4 覆盖后重收敛必须为包技能 demo-a 补建链（l4Names 快照刷新）：" + cmd2);
    }

    @Test
    void farmPendingUserKeyShouldHonorUserDisabledAndRevision() throws Exception {
        // issue #399-2：tryHydrate 解析的 uid（userKey 缺失时回退 pendingUserKey）未传入
        // hydrate——延迟投放场景（downloadFile/statWorkspaceFile/doExec(null)，userKey 未绑定）
        // hydrate 内直接读 userKey.get()=null：用户级禁用判定恒 false（禁用技能照常建链）且
        // farmFingerprint 丢 L4 revision 分量。uid 必须整链路传递，身份口径唯一
        var host = java.nio.file.Files.createTempDirectory("farm-pending");
        writeHostSkill(host, "alice", "secret-skill");
        writeHostSkill(host, "alice", "open-skill");
        var cfg = java.nio.file.Files.createTempDirectory("farm-pending-cfg");
        bundleSkill(cfg, "secret-skill");
        bundleSkill(cfg, "open-skill");
        var svc = bundleService(cfg);
        var version = svc.currentVersion().version();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsb("v-" + version);
        var reader = mock(WorkspaceReader.class);
        when(reader.readUserSkillRevision("alice")).thenReturn("rev-alice");
        when(reader.materializeUserSkills(any(), eq("alice"), any())).thenReturn(1);
        var checker = mock(io.agentmanager.framework.sandbox.opensandbox.SkillDisabledChecker.class);
        when(checker.packageDisabled()).thenReturn(java.util.Set.of());
        when(checker.isUserDisabled(eq("alice"), eq("secret-skill"))).thenReturn(true);
        var spec = new OpenSandboxFilesystemSpec();
        spec.setPendingUserKey("alice");

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-399b"), osb,
            farmOptions(svc, registry, checker), reader, null, spec);
        // 不 setUserKey（模拟 userKey 未绑定的延迟投放），仅设 scope + pendingUserKey 兜底
        sandbox.setSkillCacheScope("alice");
        sandbox.exec(RuntimeContext.builder().build(), "ls /workspace/.skills-cache", 30);

        String scopeRoot = "/workspace/.skills-cache/alice/oaf-package";
        var cmd = farmConvergeCommands(osb).get(0);
        assertFalse(cmd.contains("ln -s '" + FARM_STORE + "/CURRENT/secret-skill' '" + scopeRoot + "/secret-skill'"),
            "issue #399：pendingUserKey 场景用户级禁用必须生效（uid 传入 hydrate），禁用名不建链：" + cmd);
        assertTrue(cmd.contains("ln -s '" + FARM_STORE + "/CURRENT/open-skill'"),
            "未禁用技能正常建链：" + cmd);
        // farmFingerprint/materialize 门控都以 uid（而非 null）读 revision——身份口径唯一
        org.mockito.Mockito.verify(reader, org.mockito.Mockito.atLeastOnce())
            .readUserSkillRevision("alice");
        org.mockito.Mockito.verify(reader, org.mockito.Mockito.never())
            .readUserSkillRevision(org.mockito.Mockito.isNull());
    }

    @Test
    void farmUnsafeSkillDirNameShouldSkipLinkAndWarn() throws Exception {
        // 包技能目录名含空白/单引号：farm 侧 isShellSafeRelPath 不建链，tar 物化却会携带同一
        // 文件——两模式对同一包行为分叉，跳过必须 WARN 留痕（含 rel 名），否则只表现为
        // 「该技能缺失」无从排查
        var host = java.nio.file.Files.createTempDirectory("farm-unsafe-name");
        writeHostSkill(host, "sess-A", "demo-a");
        writeHostSkill(host, "sess-A", "bad skill");
        var cfg = java.nio.file.Files.createTempDirectory("farm-unsafe-name-cfg");
        bundleSkill(cfg, "demo-a");
        bundleSkill(cfg, "bad skill");
        var svc = bundleService(cfg);
        var version = svc.currentVersion().version();
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsb("v-" + version);

        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
            io.agentmanager.framework.sandbox.opensandbox.SkillsFarm.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var sandbox = new OpenSandbox(farmState(host, "sbx-farm-unsafe"), osb,
                farmOptions(svc, registry, null), null, null, new OpenSandboxFilesystemSpec());
            bindFarmSession(sandbox, "sess-A");
            sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        var cmd = farmConvergeCommands(osb).get(0);
        String scopeRoot = "/workspace/.skills-cache/sess-A/oaf-package";
        assertTrue(cmd.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-a' '" + scopeRoot + "/demo-a'"),
            "安全名技能正常建链：" + cmd);
        assertFalse(cmd.contains("bad skill"), "不安全名不得进入任何引号路径段：" + cmd);
        var messages = appender.list.stream()
            .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("rel not shell-safe")
                && m.contains("oaf-package/bad skill")),
            "跳过必须 WARN 留痕（含 rel 名）：" + messages);
    }

    @Test
    void farmFingerprintUnknownShouldPreserveLinksAndSkipOrphanDiff() throws Exception {
        // issue #355：端点 skills 目录瞬时 I/O 异常（端点 version 计算失败 → ERR-
        // 版本 + 空清单，issue #397 后目录缺失=健康空源故以端点故障注入模拟）→ 指纹未知仍收敛。
        // 收敛执行孤儿差集的空候选形态（case '|'）会把
        // scope 内全部有效软链删光且 exit 0 记账旧指纹、稳态短路吞掉恢复收敛——指纹未知
        // 时必须跳过孤儿差集段（保留现状等恢复），prune/禁用清理语义不变
        var host = java.nio.file.Files.createTempDirectory("farm-err");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-err-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleServiceLive(cfg);
        var version = svc.currentVersion().version();
        var osb = farmOsb("v-" + version);
        // gate 记忆跨 call 生效的前提：同一会话同一 filesystemSpec（生产形态 spec 常驻）
        var spec = new OpenSandboxFilesystemSpec();

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-err"), osb,
            farmOptions(svc, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), null),
            null, null, spec);
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        var first = farmConvergeCommands(osb);
        assertEquals(1, first.size());
        assertTrue(first.get(0).contains("ln -s '" + FARM_STORE + "/CURRENT/demo-a'"),
            "健康端点收敛先建链：" + first.get(0));

        // call2 前命令快照（指纹未知收敛只跑 prune 清理，不含带引号容器路径段——
        // farmConvergeCommands 的过滤形态不适用，改用全量命令快照 diff）
        var beforeCaptor = ArgumentCaptor.forClass(String.class);
        verify(osb.commands(), atLeastOnce()).run(beforeCaptor.capture());
        var beforeCall2 = beforeCaptor.getAllValues().size();

        // 端点真实 I/O 故障（同会话后续 call）：gate 已记忆 farm，收敛照常触发，但端点 ERR-。
        // issue #397 后删除/缺失 skills 目录 = 健康空源（稳定空版本），不再是指纹未知——
        // 故障注入改为端点 version 计算失败（同 ERR-* 输出约定）
        var brokenSvc = org.mockito.Mockito.mock(SkillBundleService.class);
        org.mockito.Mockito.when(brokenSvc.currentVersion())
            .thenReturn(new SkillBundleService.SkillVersion(
                SkillBundleService.ERROR_VERSION_PREFIX + System.nanoTime(), 0, 0, 0, java.util.List.of()));
        var call2 = new OpenSandbox(farmState(host, "sbx-farm-err"), osb,
            farmOptions(brokenSvc, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), null),
            null, null, spec);
        bindFarmSession(call2, "sess-A");
        call2.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        var afterCaptor = ArgumentCaptor.forClass(String.class);
        verify(osb.commands(), atLeastOnce()).run(afterCaptor.capture());
        var all = afterCaptor.getAllValues();
        assertTrue(all.size() > beforeCall2, "指纹未知仍触发收敛 exec（gate 记忆 farm，自愈重试语义不变）");
        var added = String.join("\n---\n", all.subList(beforeCall2, all.size()));
        assertTrue(added.contains("-maxdepth 1 -type d ! -name 'sess-A'"),
            "prune/其它 scope 清理语义保留：" + added);
        assertFalse(added.contains("while IFS= read -r __f"),
            "指纹未知必须跳过孤儿差集（空候选差集会删光有效软链，issue #355）：" + added);
        assertFalse(added.contains("ln -s"), "ERR- 无候选：不建链：" + added);
    }

    @Test
    void farmPruneShouldClearPrunedScopeAccounting() throws Exception {
        // issue #416：同 Pod prune 其它 scope 目录后，其记账（Pod 级 + 共享层 + 现役槽）必须
        // 同步清除——否则切回该 scope 时稳态判定（记账==指纹 && 现役==scope）恒 true，容器内
        // 目录已被 prune 删除而软链不再重建（稳态短路锁死）。同时 ERR- 收敛不得 mark 现役
        // scope（不写「从未成功投放」的错误事实，实现于 hydrate 收敛记账段）
        var host = java.nio.file.Files.createTempDirectory("farm-prune");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-prune-cfg");
        bundleSkill(cfg, "demo-a");
        var healthySvc = bundleServiceLive(cfg);
        var version = healthySvc.currentVersion().version();
        var osb = farmOsb("v-" + version);
        var spec = new OpenSandboxFilesystemSpec();
        String id = "sbx-farm-prune";
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

        // call1：scope A 健康收敛（记账 A + mark A）
        var call1 = new OpenSandbox(farmState(host, id), osb, farmOptions(healthySvc, registry, null),
            null, null, spec);
        bindFarmSession(call1, "sess-A");
        call1.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);
        assertEquals("sess-A", spec.hydratedScopedScope(id), "健康收敛后记账现役 scope");
        assertNotNull(spec.scopedSkillCacheFingerprint(id, "sess-A",
            OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM), "scope A 记账存在");

        // call2：scope B 健康收敛——prune 删除 A 目录，其记账必须同步清除
        var call2 = new OpenSandbox(farmState(host, id), osb, farmOptions(healthySvc, registry, null),
            null, null, spec);
        bindFarmSession(call2, "sess-B");
        call2.exec(RuntimeContext.builder().userId("sess-B").build(), "ls /workspace/.skills-cache", 30);
        assertEquals("sess-B", spec.hydratedScopedScope(id), "scope B 健康收敛记账现役 scope");
        assertNull(spec.scopedSkillCacheFingerprint(id, "sess-A",
            OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM),
            "issue #416：被 prune scope 的记账必须同步清除（否则切回稳态锁死）");

        // call3：切回 A——记账已清 → 必触发重收敛并建链（不被旧记账稳态锁死）
        var beforeCaptor = ArgumentCaptor.forClass(String.class);
        verify(osb.commands(), atLeastOnce()).run(beforeCaptor.capture());
        var beforeCall3 = beforeCaptor.getAllValues().size();
        var call3 = new OpenSandbox(farmState(host, id), osb, farmOptions(healthySvc, registry, null),
            null, null, spec);
        bindFarmSession(call3, "sess-A");
        call3.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);
        var afterCaptor = ArgumentCaptor.forClass(String.class);
        verify(osb.commands(), atLeastOnce()).run(afterCaptor.capture());
        var added = String.join("\n---\n", afterCaptor.getAllValues().subList(beforeCall3, afterCaptor.getAllValues().size()));
        assertTrue(added.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-a'"),
            "切回 A 必须重收敛建链（不被旧记账稳态锁死）：" + added);
    }

    @Test
    void farmErrPruneShouldBroadcastTombstoneSoCrossReplicaStaleBookkeepingReconverges() throws Exception {
        // 回归钉子（issue #481，复现 T1-01a/01b）：多副本下 ERR- prune 的执行副本（Pod2）
        // 从未收敛过被删 scope 时本地 stale 表为空，共享槽残留记账副本（Pod1）写入的 sess-A
        // 记账；修复前即便删了槽，Pod1 也把槽缺失 fail-open 混同为不可达而维持陈旧两表——
        // 端点恢复后 Pod1 的 scope-A 三条件（Pod 级记账==指纹 && 槽核验 && 现役==scope）全
        // true 稳态短路，容器内 scope-A 已被 pruneCmd 删除，该会话技能执行面永久 404。修复
        // = pruneExcept 经 markPruned 把单槽覆写为 keepScope 非空墓碑：现役 scope 段权威否定
        // 所有副本的陈旧信念（槽缺失 fail-open 通道不复存在）
        var host = java.nio.file.Files.createTempDirectory("farm-err-prune");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-err-prune-cfg");
        bundleSkill(cfg, "demo-a");
        var healthySvc = bundleServiceLive(cfg);
        var version = healthySvc.currentVersion().version();
        var osb = farmOsb("v-" + version);
        var data = new java.util.HashMap<String, String>();
        var store = new ScopedFingerprintStore(fakeScopedFpFacade(data));
        String id = "sbx-err-prune";

        // Pod1 健康收敛 scope-A（converge=1），Pod 级两表与共享槽均记 sess-A
        var spec1 = new OpenSandboxFilesystemSpec().sharedFingerprintStore(store);
        var call1 = new OpenSandbox(farmState(host, id), osb,
            farmOptions(healthySvc, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), null),
            null, null, spec1);
        bindFarmSession(call1, "sess-A");
        call1.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);
        assertEquals(1, farmConvergeCommands(osb).size(), "Pod1 scope-A 首轮收敛");
        assertEquals("sess-A", store.slotScope(id), "共享槽现役 scope = sess-A");

        // Pod2（新 spec = 新副本，同代池化容器复用）在 ERR- 窗口收敛 scope-B：gate 携带健康
        // 窗口判定的 farm 记忆（判定健康、收敛时端点已 ERR- 的真实窗口）→ 收敛 exec =
        // pruneCmd 删容器内 scope-A，record 对 null 指纹 no-op 不覆盖槽
        var brokenSvc = org.mockito.Mockito.mock(SkillBundleService.class);
        org.mockito.Mockito.when(brokenSvc.currentVersion())
            .thenReturn(new SkillBundleService.SkillVersion(
                SkillBundleService.ERROR_VERSION_PREFIX + System.nanoTime(), 0, 0, 0, java.util.List.of()));
        var spec2 = new OpenSandboxFilesystemSpec().sharedFingerprintStore(store);
        spec2.skillSyncGate("sess-B", id)
            .setSkillFarmState(new SkillSyncGate.SkillFarmState(true, java.util.Set.of()));
        var call2 = new OpenSandbox(farmState(host, id), osb,
            farmOptions(brokenSvc, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), null),
            null, null, spec2);
        bindFarmSession(call2, "sess-B");
        call2.exec(RuntimeContext.builder().userId("sess-B").build(), "ls /workspace/.skills-cache", 30);
        assertEquals("sess-B", store.slotScope(id),
            "ERR- prune 也必须把现役 scope 墓碑广播到共享槽（修复前槽仍=sess-A，PRUNED 墓碑=keepScope）");

        // 端点恢复：scope-A 回落 Pod1（Pod 级两表仍记 A）→ 墓碑现役=B 权威否定 → 重收敛
        // （converge=2）；修复前槽核验过+指纹相等+现役=sess-A 三条件全 true 稳态短路（converge
        // 停在 1）必红
        var call3 = new OpenSandbox(farmState(host, id), osb,
            farmOptions(healthySvc, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), null),
            null, null, spec1);
        bindFarmSession(call3, "sess-A");
        call3.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);
        assertEquals(2, farmConvergeCommands(osb).size(),
            "恢复后 scope-A 必须重收敛（陈旧记账被墓碑否定），不得伪造稳态短路");
    }

    @Test
    void farmTtlEdgeShouldUseSingleVersionSnapshot() throws Exception {
        // issue #416a：hydrate 多次读取 currentVersion（指纹一次 + 候选两次，5s TTL 内
        // 缓存过期重算即分裂）——首次健康 V、随后 ERR+空清单的组合会以健康指纹执行孤儿
        // 差集，空候选把 scope 内有效软链删光且记账稳态锁死。快照单次读取：指纹/候选/
        // 禁用判定同源，且 ERR- 统一视为指纹未知（不建链不清链不记账）
        var host = java.nio.file.Files.createTempDirectory("farm-ttl");
        writeHostSkill(host, "sess-A", "demo-a");
        var svc = org.mockito.Mockito.mock(SkillBundleService.class);
        var healthy = new SkillBundleService.SkillVersion("aaaaaaaaaaaaaaaa", 1, 1, 9,
            java.util.List.of("demo-a"));
        // 前 2 次读返回健康 V（decideMode 判定 1 次 + hydrate 快照 1 次），此后全部 ERR
        // （模拟 TTL 过期重算失败）——修复前 hydrate 内再读 2 次（指纹+候选）拿到空清单，
        // 指纹却仍是首读的健康 V，健康指纹+空候选的孤儿差集删光软链
        var err = new SkillBundleService.SkillVersion(
            SkillBundleService.ERROR_VERSION_PREFIX + System.nanoTime(), 0, 0, 0, java.util.List.of());
        org.mockito.Mockito.when(svc.currentVersion()).thenReturn(healthy, healthy, err, err);
        var osb = farmOsb("v-" + healthy.version());
        var spec = new OpenSandboxFilesystemSpec();

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-ttl"), osb,
            farmOptions(svc, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), null),
            null, null, spec);
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(osb.commands(), atLeastOnce()).run(captor.capture());
        var all = String.join("\n---\n", captor.getAllValues());
        assertTrue(all.contains("ln -s '" + FARM_STORE + "/CURRENT/demo-a'"),
            "快照健康 V：候选与指纹同源，demo-a 正常建链：" + all);
        // 快照同源的判据：孤儿差集的保护名单（case '|'）必须含快照候选 demo-a——修复前
        // 指纹取首读健康 V、候选取后续 ERR 空清单，差集段为空候选形态（case '|'），demo-a
        // 会被当孤儿删除
        assertTrue(all.contains("case '|oaf-package/demo-a|'"),
            "issue #416：孤儿差集保护名单必须含快照候选（指纹/候选同源），否则有效软链被删：" + all);
        // decideMode 判定 1 次 + hydrate 快照 1 次；修复前 hydrate 内再读 2 次（共 4）
        org.mockito.Mockito.verify(svc, org.mockito.Mockito.atMost(2)).currentVersion();
    }

    @Test
    void farmEndpointHealthyWithNoSkillsShouldStillWipeOrphanLinks() throws Exception {
        // 对照（防回归）：端点健康但零技能（包技能真实移除场景）→ 空候选孤儿差集必须照常
        // 执行（删链是设计的 §6.2-3 语义，issue #355 只豁免指纹未知分支）
        var host = java.nio.file.Files.createTempDirectory("farm-noskill");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-noskill-cfg");
        var svc = bundleService(cfg);
        var version = svc.currentVersion().version();
        var osb = farmOsb("v-" + version);

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-noskill"), osb,
            farmOptions(svc, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), null),
            null, null, new OpenSandboxFilesystemSpec());
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        var cmd = farmConvergeCommands(osb).get(0);
        assertTrue(cmd.contains("while IFS= read -r __f"),
            "健康端点空候选 = 真实移除场景，孤儿差集照常执行：" + cmd);
    }

    @Test
    void globTranslateTruncationShouldEmitSingleWarn() throws Exception {
        // issue #357：截断可观测性（#296）——WARN 在重构搬移时被删成空 if 块，截断重回静默。
        // 补回后断言：一条 WARN、含 root（farm 用例已立 logback appender 断言先例）
        var files = mock(Filesystem.class);
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);
        when(osb.commands()).thenReturn(mock(Commands.class));
        String dir = "tree";
        for (int i = 0; i < 12; i++) {
            String next = dir + "/d" + (i + 1);
            when(files.listDirectory(dir)).thenReturn(List.of(
                dirEntry("/workspace/" + next), fileEntry("/workspace/" + dir + "/f" + i + ".md")));
            dir = next;
        }
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var logger = org.slf4j.LoggerFactory.getLogger(
            io.agentmanager.framework.sandbox.opensandbox.OpenSandbox.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        ((ch.qos.logback.classic.Logger) logger).addAppender(appender);
        try {
            sandbox.exec(RuntimeContext.builder().userId("u").build(), FIND_PROBE, 30);
        } finally {
            ((ch.qos.logback.classic.Logger) logger).detachAppender(appender);
            appender.stop();
        }

        var warns = appender.list.stream()
            .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
            .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
        assertEquals(1, warns.size(), "每次 translateGlob 截断仅一条 WARN：" + warns);
        assertTrue(warns.get(0).contains("tree"), "截断 WARN 必须含 root 便于定位：" + warns);
    }
}
