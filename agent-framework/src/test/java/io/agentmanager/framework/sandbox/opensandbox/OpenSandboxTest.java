package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    // ---------- 工作区投影新鲜度检查（refreshProjectionIfStale）：补齐 stage 晚于 start 的技能目录 ----------

    /**
     * 构造与 harness 生产路径同构的投影 spec：entry key 固定 __workspace_projection__
     * （SandboxFilesystemSpec.buildWorkspaceSpecWithProjection 同款），sourceRoot 指向
     * 临时目录，includeRoots 用 SDK 默认投影根（AGENTS.md/skills/subagents/knowledge/.skills-cache）。
     */
    private static OpenSandboxState projectedState(java.nio.file.Path sourceRoot) {
        var s = new OpenSandboxState();
        var ws = new io.agentscope.harness.agent.sandbox.WorkspaceSpec();
        ws.setRoot("/workspace");
        var entry = new io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry();
        entry.setSourceRoot(sourceRoot.toAbsolutePath().normalize().toString());
        entry.setIncludeRoots(List.of("AGENTS.md", "skills", "subagents", "knowledge", ".skills-cache"));
        ws.setEntries(Map.of("__workspace_projection__", entry));
        s.setWorkspaceSpec(ws);
        return s;
    }

    @Test
    void execShouldRefreshProjectionWhenHostDirChangedAfterStart() throws Exception {
        // E-SKILL-4 回归钉子：技能目录在 sandbox start 之后才 stage（每轮 onSystemPrompt），
        // 首次 exec 须把它补水合进容器，使 /workspace/.skills-cache/... 在 shell 里真实可见
        var host = java.nio.file.Files.createTempDirectory("projection-refresh");
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(projectedState(host), osb, options(), null, null);

        sandbox.start(); // start 时宿主为空，记录"空投影"hash

        // start 之后 stage 出技能目录（模拟 onSystemPrompt 时序）
        var skillDir = host.resolve(".skills-cache/u1/oaf-package/demo-skill");
        java.nio.file.Files.createDirectories(skillDir);
        java.nio.file.Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: demo\n---\nhi");

        sandbox.exec(RuntimeContext.builder().userId("u1").build(), "ls /workspace", 30);

        // 补水合走 doHydrateWorkspace：base64 写临时 tar + 解压命令
        verify(osb.files(), atLeastOnce()).write(anyList());
        verify(osb.commands(), atLeastOnce()).run(contains("tar xzf - -C /workspace"));
        var state = sandbox.getOsbState();
        assertNotNull(state.getWorkspaceProjectionHash(), "补水合后须回写 hash");
        assertFalse(state.getWorkspaceProjectionHash().isEmpty());
    }

    @Test
    void execShouldSkipProjectionRefreshWhenUnchanged() throws Exception {
        // hash 一致（宿主自 start 后无变化）时不得重复水合：
        // start 走 Branch D 水合一次并记录 hash；本次 exec 比对一致 → 只有 start 那一次
        var host = java.nio.file.Files.createTempDirectory("projection-same");
        var skillDir = host.resolve("skills/demo");
        java.nio.file.Files.createDirectories(skillDir);
        java.nio.file.Files.writeString(skillDir.resolve("SKILL.md"), "v1");
        var exec = mockExecution(0, "", "");
        var osb = mockOsb(exec);
        var sandbox = new OpenSandbox(projectedState(host), osb, options(), null, null);

        sandbox.start(); // Branch D：宿主非空 → 水合 + 记 hash
        sandbox.exec(RuntimeContext.builder().userId("u").build(), "ls /workspace", 30);

        verify(osb.commands(), times(1)).run(contains("tar xzf - -C /workspace"));
    }

    @Test
    void projectionRefreshFailureShouldNotBlockExec() throws Exception {
        // fail-soft：水合命令失败仅告警，命令本身照常执行
        var host = java.nio.file.Files.createTempDirectory("projection-fail");
        var exec = mockExecution(0, "ok", "");
        var osb = mockOsb(exec);
        var sandbox = new OpenSandbox(projectedState(host), osb, options(), null, null);
        sandbox.start();

        var skillDir = host.resolve("skills/demo");
        java.nio.file.Files.createDirectories(skillDir);
        java.nio.file.Files.writeString(skillDir.resolve("SKILL.md"), "v1");

        // 让水合的解压命令失败（exit 1）——先建好返回对象再 stub，避免嵌套 when()
        var failExec = mockExecution(1, "", "untar fail");
        var okExec = mockExecution(0, "ok", "");
        var commands = mock(Commands.class);
        when(commands.run(contains("tar xzf - -C /workspace"))).thenReturn(failExec);
        when(commands.run(anyString())).thenReturn(okExec);
        when(osb.commands()).thenReturn(commands);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), "echo ok", 30);

        assertTrue(result.ok());
        assertEquals("ok", result.stdout());
    }

    @Test
    void projectionRefreshShouldRecheckAfterStop() throws Exception {
        // 每 call 一次：stop 复位后，下一 call 宿主再变化仍会补水合
        var host = java.nio.file.Files.createTempDirectory("projection-recall");
        var exec = mockExecution(0, "", "");
        var osb = mockOsb(exec);
        var sandbox = new OpenSandbox(projectedState(host), osb, options(), null, null);
        sandbox.start();

        sandbox.exec(RuntimeContext.builder().userId("u").build(), "echo 1", 30);
        sandbox.stop();

        // stop 之后 stage 出新目录（下一 call 的 onSystemPrompt 时序）
        var skillDir = host.resolve(".skills-cache/u2/oaf-package/new-skill");
        java.nio.file.Files.createDirectories(skillDir);
        java.nio.file.Files.writeString(skillDir.resolve("SKILL.md"), "new");

        var freshExec = mockExecution(0, "", "");
        var commands = mock(Commands.class);
        when(commands.run(anyString())).thenReturn(freshExec);
        when(osb.commands()).thenReturn(commands);

        sandbox.exec(RuntimeContext.builder().userId("u").build(), "echo 2", 30);

        verify(osb.commands(), atLeastOnce()).run(contains("tar xzf - -C /workspace"));
    }

    @Test
    void projectionRefreshShouldSkipWhenSpecMissing() throws Exception {
        // 投影未启用/未装配（spec 无 entries）：build 返回 null，直接跳过且不报错
        var s = new OpenSandboxState();
        var ws = new io.agentscope.harness.agent.sandbox.WorkspaceSpec();
        ws.setRoot("/workspace");
        s.setWorkspaceSpec(ws);
        var osb = mockOsb(mockExecution(0, "ok", ""));
        var sandbox = new OpenSandbox(s, osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), "echo ok", 30);

        assertTrue(result.ok());
        verify(osb.commands(), never()).run(contains("tar xzf - -C /workspace"));
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
            null, null, true, revisionGate, true);
        sandbox.setUserKey("alice");
        return sandbox;
    }

    @Test
    void revisionGateShouldMaterializeOnFirstAcquireThenSkipOnUnchangedRevision() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var reader = mock(WorkspaceReader.class);
        var spec = new OpenSandboxFilesystemSpec();
        when(reader.readUserSkillRevision("alice")).thenReturn("rev-1");
        when(reader.materializeUserSkills(any(), eq("alice"))).thenReturn(2);

        // 第一次 acquire：必须全量物化，物化后回填 gate
        gatedInstance(spec, reader, osb, true).materializeUserSkills();
        verify(reader, times(1)).materializeUserSkills(any(), eq("alice"));
        var gate = spec.skillSyncGate("alice", "sbx-gate-1");
        assertNotNull(gate);
        assertTrue(gate.lastMaterialized());
        assertEquals("rev-1", gate.revision());

        // 第二次 acquire（新实例，revision 未变）：门控跳过（只多一次 revision get）
        gatedInstance(spec, reader, osb, true).materializeUserSkills();
        verify(reader, times(1)).materializeUserSkills(any(), eq("alice"));
    }

    @Test
    void revisionGateShouldRematerializeWhenRevisionChanges() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var reader = mock(WorkspaceReader.class);
        var spec = new OpenSandboxFilesystemSpec();
        when(reader.readUserSkillRevision("alice")).thenReturn("rev-1", "rev-2");
        when(reader.materializeUserSkills(any(), eq("alice"))).thenReturn(2);

        gatedInstance(spec, reader, osb, true).materializeUserSkills();
        // 管理面写入了新技能（revision 变化）→ 第二次 acquire 必须重新物化
        gatedInstance(spec, reader, osb, true).materializeUserSkills();
        verify(reader, times(2)).materializeUserSkills(any(), eq("alice"));
    }

    @Test
    void revisionGateShouldFallbackWhenRevisionUnreadable() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var reader = mock(WorkspaceReader.class);
        var spec = new OpenSandboxFilesystemSpec();
        // revision 读不到（KV 异常/键不存在）→ 门控分支跳过（gate 未置位）、保守走全量物化；
        // 物化后回填读 revision（mock 返回 rev-1），gate 记最新值
        when(reader.readUserSkillRevision("alice")).thenReturn("rev-1");
        when(reader.materializeUserSkills(any(), eq("alice"))).thenReturn(2);

        gatedInstance(spec, reader, osb, true).materializeUserSkills();
        verify(reader, times(1)).materializeUserSkills(any(), eq("alice"));
        var gate = spec.skillSyncGate("alice", "sbx-gate-1");
        assertEquals("rev-1", gate.revision(), "物化后应回填最新 revision");
    }

    @Test
    void revisionGateDisabledShouldAlwaysMaterialize() throws Exception {
        var osb = mockOsb(mockExecution(0, "", ""));
        var reader = mock(WorkspaceReader.class);
        var spec = new OpenSandboxFilesystemSpec();
        when(reader.readUserSkillRevision("alice")).thenReturn("rev-1");
        when(reader.materializeUserSkills(any(), eq("alice"))).thenReturn(2);

        gatedInstance(spec, reader, osb, false).materializeUserSkills();
        gatedInstance(spec, reader, osb, false).materializeUserSkills();
        verify(reader, times(2)).materializeUserSkills(any(), eq("alice"));
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
            null, null, true, true, true);
        sandbox.exec(RuntimeContext.builder().userId("user-alice").build(), "echo x", 30);
        sandbox.stop();
        verify(sync).syncBack(eq("user-alice"), eq(osb), any(SkillSyncGate.class));

        var sync2 = mock(WorkspaceSyncService.class);
        var st2 = state();
        st2.setSandboxId("sbx-diff-2");
        var sandbox2 = new OpenSandbox(st2, osb, options(), null, sync2, spec,
            null, null, true, true, false);
        sandbox2.exec(RuntimeContext.builder().userId("user-bob").build(), "echo x", 30);
        sandbox2.stop();
        verify(sync2).syncBack(eq("user-bob"), eq(osb), isNull());
    }
}
