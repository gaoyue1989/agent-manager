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
    void scopedSkillCacheHydratesOnlyCurrentScope() throws Exception {
        // 宿主 .skills-cache 含两个 scope；投放 tar 只应有当前 scope，其它 scope 目录被清理命令删掉
        var host = java.nio.file.Files.createTempDirectory("scoped-skill-cache");
        writeHostSkill(host, "sess-A", "demo-a");
        writeHostSkill(host, "sess-B", "demo-b");

        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(projectedStateNoSkillCache(host), osb, options(), null, null);
        sandbox.setSkillCacheScope("sess-A");

        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace", 30);
        // 投放延迟语义（first-llm-latency）：exec 命令不含 .skills-cache 时投放推迟到 stop 收尾
        sandbox.stop();

        var entries = capturedHydrateTarEntries(osb);
        assertTrue(entries.contains(".skills-cache/sess-A/oaf-package/demo-a/SKILL.md"), entries.toString());
        assertTrue(entries.stream().noneMatch(e -> e.contains("sess-B")),
            "其它 scope 不得进入容器：" + entries);

        // 容器内非当前 scope 残留被清理
        verify(osb.commands()).run(contains("find /workspace/.skills-cache"));
        verify(osb.commands()).run(contains("! -name 'sess-A'"));
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

    @Test
    void scopedSkillCacheUnchangedSkipsRehydrate() throws Exception {
        // 同 scope 内容未变：第二次 call 不应重复投放 tar（跨 call hash 命中）
        var host = java.nio.file.Files.createTempDirectory("scoped-same");
        writeHostSkill(host, "sess-A", "demo-a");
        var osb = mockOsb(mockExecution(0, "", ""));
        var sandbox = new OpenSandbox(projectedStateNoSkillCache(host), osb, options(), null, null);
        sandbox.setSkillCacheScope("sess-A");

        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 1", 30);
        sandbox.stop();
        int after1 = countHydrateTarWrites(osb);
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 2", 30);
        sandbox.stop();
        int after2 = countHydrateTarWrites(osb);

        assertEquals(after1, after2, "内容未变时第二次 call 不应重复投放");
    }

    @Test
    void scopedSkillCacheExcludesDisabledSkills() throws Exception {
        // oa-1110-repro-fixes-design §2.1a：禁用技能（L2 包级 demo-b / L4 用户级 demo-c）
        // 不得进入投放载荷，且容器内残留目录随投放 exec 一并清理——堵 read_file/execute 直读旁路
        var host = java.nio.file.Files.createTempDirectory("scoped-disabled");
        writeHostSkill(host, "sess-A", "demo-a");
        writeHostSkill(host, "sess-A", "demo-b");
        writeHostSkill(host, "sess-A", "demo-c");

        var osb = mockOsb(mockExecution(0, "", ""));
        var opts = options().skillDisabledChecker(new SkillDisabledChecker() {
            @Override
            public java.util.Set<String> packageDisabled() {
                return java.util.Set.of("demo-b");
            }

            @Override
            public boolean isUserDisabled(String userId, String name) {
                return "demo-c".equals(name);
            }
        });
        var sandbox = new OpenSandbox(projectedStateNoSkillCache(host), osb, opts, null, null);
        sandbox.setSkillCacheScope("sess-A");

        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace", 30);
        sandbox.stop();

        var entries = capturedHydrateTarEntries(osb);
        assertTrue(entries.contains(".skills-cache/sess-A/oaf-package/demo-a/SKILL.md"), entries.toString());
        assertTrue(entries.stream().noneMatch(e -> e.contains("demo-b")),
            "L2 包级禁用技能不得进入容器：" + entries);
        assertTrue(entries.stream().noneMatch(e -> e.contains("demo-c")),
            "L4 用户级禁用技能不得进入容器：" + entries);

        verify(osb.commands()).run(contains("rm -rf '/workspace/.skills-cache/sess-A/oaf-package/demo-b'"));
        verify(osb.commands()).run(contains("rm -rf '/workspace/.skills-cache/sess-A/oaf-package/demo-c'"));
    }

    @Test
    void scopedSkillCacheAllDisabledSkipsPayloadButPrunes() throws Exception {
        // 全部技能被禁用：无 tar 投放（gz=null 分支），但仍执行禁用目录清理
        var host = java.nio.file.Files.createTempDirectory("scoped-all-disabled");
        writeHostSkill(host, "sess-A", "demo-b");

        var osb = mockOsb(mockExecution(0, "", ""));
        var opts = options().skillDisabledChecker(new SkillDisabledChecker() {
            @Override
            public java.util.Set<String> packageDisabled() {
                return java.util.Set.of("demo-b");
            }

            @Override
            public boolean isUserDisabled(String userId, String name) {
                return false;
            }
        });
        var sandbox = new OpenSandbox(projectedStateNoSkillCache(host), osb, opts, null, null);
        sandbox.setSkillCacheScope("sess-A");

        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace", 30);
        sandbox.stop();

        verify(osb.files(), never()).write(anyList());
        verify(osb.commands()).run(contains("rm -rf '/workspace/.skills-cache/sess-A/oaf-package/demo-b'"));
    }

    /** 从 files().write 捕获中定位 hydrate 的临时 tar（/tmp/workspace.*），解出 tar 条目名。 */
    private static List<String> capturedHydrateTarEntries(Sandbox osb) throws Exception {
        var captor = ArgumentCaptor.forClass(List.class);
        verify(osb.files(), atLeastOnce()).write(captor.capture());
        for (var call : captor.getAllValues()) {
            var w = (WriteEntry) call.get(0);
            if (w.getPath() != null && w.getPath().startsWith("/tmp/workspace.")) {
                return readTarEntryNames((String) w.getData());
            }
        }
        throw new AssertionError("未捕获到 hydrate tar 写入");
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

    private static int countHydrateTarWrites(Sandbox osb) throws Exception {
        var captor = ArgumentCaptor.forClass(List.class);
        verify(osb.files(), atLeastOnce()).write(captor.capture());
        int n = 0;
        for (var call : captor.getAllValues()) {
            var w = (WriteEntry) call.get(0);
            if (w.getPath() != null && w.getPath().startsWith("/tmp/workspace.")) {
                n++;
            }
        }
        return n;
    }

    private static List<String> readTarEntryNames(String base64Gz) throws Exception {
        var names = new java.util.ArrayList<String>();
        byte[] gz = Base64.getDecoder().decode(base64Gz);
        try (var in = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(gz));
             var tar = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(in)) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        return names;
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

    // ---------- P0-1：scoped 投放指纹 Pod 级短路（跨实例） ----------

    /**
     * 生产 acquire 语义：每 call 新建 OpenSandbox 包装实例。P0-1 把 scoped 指纹/已投放
     * scope 记在 spec（Pod 级）后，<b>新实例</b>在同容器代、同 scope、宿主内容未变时
     * 必须命中短路（零 build/prune/hydrate）——历史实现 hash 记在实例字段上，短路条件
     * 每 call 失忆，66MB/2000f 下每轮重付 ≈4.9s（基准实证）。
     */
    @Test
    void scopedSkillCacheSkipSurvivesInstanceRecreation() throws Exception {
        var host = java.nio.file.Files.createTempDirectory("scoped-pod-cache");
        writeHostSkill(host, "sess-A", "demo-a");
        var osb = mockOsb(mockExecution(0, "", ""));
        var spec = new OpenSandboxFilesystemSpec();
        var st1 = projectedStateNoSkillCache(host);
        st1.setSandboxId("sbx-pod-1");
        var call1 = new OpenSandbox(st1, osb, options(), null, null, spec);
        call1.setSkillCacheScope("sess-A");
        call1.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 1", 30);
        call1.stop();
        int afterCall1 = countHydrateTarWrites(osb);
        assertTrue(afterCall1 >= 1, "首轮应实际投放");

        // 新包装实例（同 sandboxId=同容器代，模拟生产 resume）：
        var st2 = projectedStateNoSkillCache(host);
        st2.setSandboxId("sbx-pod-1");
        var call2 = new OpenSandbox(st2, osb, options(), null, null, spec);
        call2.setSkillCacheScope("sess-A");
        call2.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 2", 30);
        call2.stop();
        assertEquals(afterCall1, countHydrateTarWrites(osb),
            "指纹未变 + 本代已投放同 scope → 新实例不得重投（P0-1）");
    }

    /** 同容器代换 scope（池化借出复用/同用户双会话）：必须走慢路径 prune 旧 scope 并投放新 scope */
    @Test
    void scopedSkillCacheScopeSwitchOnSameGenerationRehydrates() throws Exception {
        var host = java.nio.file.Files.createTempDirectory("scoped-scope-switch");
        writeHostSkill(host, "sess-A", "demo-a");
        writeHostSkill(host, "sess-B", "demo-b");
        var osb = mockOsb(mockExecution(0, "", ""));
        var spec = new OpenSandboxFilesystemSpec();
        var st1 = projectedStateNoSkillCache(host);
        st1.setSandboxId("sbx-switch-1");
        var callA = new OpenSandbox(st1, osb, options(), null, null, spec);
        callA.setSkillCacheScope("sess-A");
        callA.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 1", 30);
        callA.stop();

        // 同 sandboxId 换 scope：A→B 不得命中短路
        var st2 = projectedStateNoSkillCache(host);
        st2.setSandboxId("sbx-switch-1");
        var callB = new OpenSandbox(st2, osb, options(), null, null, spec);
        callB.setSkillCacheScope("sess-B");
        callB.exec(RuntimeContext.builder().userId("sess-B").build(), "echo 2", 30);
        callB.stop();

        // 两次投放 tar 都在捕获里，任一包含 sess-B 即代表新 scope 走了慢路径投放
        var captor = ArgumentCaptor.forClass(List.class);
        verify(osb.files(), atLeastOnce()).write(captor.capture());
        boolean sessBTarSeen = false;
        for (var call : captor.getAllValues()) {
            var w = (WriteEntry) call.get(0);
            if (w.getPath() != null && w.getPath().startsWith("/tmp/workspace.")) {
                if (readTarEntryNames((String) w.getData()).stream().anyMatch(e -> e.contains("sess-B"))) {
                    sessBTarSeen = true;
                }
            }
        }
        assertTrue(sessBTarSeen, "同代换 scope 必须重新投放新 scope（不得命中 P0-1 短路）");
        // prune 在合并 exec 中执行且按当前 scope 保留
        verify(osb.commands(), atLeastOnce()).run(contains("! -name 'sess-B'"));
    }

    /** 缺口①：downloadFile（SDK SandboxFileTransfer 下载）路径命中 .skills-cache 时触发延迟投放 */
    @Test
    void downloadFileReferencingSkillsCacheTriggersDeferredHydration() throws Exception {
        var host = java.nio.file.Files.createTempDirectory("scoped-dl");
        writeHostSkill(host, "sess-A", "demo-a");
        var osb = mockOsb(mockExecution(0, "", ""));
        var st = projectedStateNoSkillCache(host);
        st.setSandboxId("sbx-dl-1");
        var sandbox = new OpenSandbox(st, osb, options(), null, null, new OpenSandboxFilesystemSpec());
        sandbox.setSkillCacheScope("sess-A");

        // 先 download 非 .skills-cache 路径：不投放（write 零调用）
        sandbox.downloadFile("outputs/plain.txt");
        verify(osb.files(), never()).write(anyList());

        // 再 download .skills-cache 路径：投放（延迟语义）后才读
        sandbox.downloadFile(".skills-cache/sess-A/oaf-package/demo-a/SKILL.md");
        assertTrue(countHydrateTarWrites(osb) >= 1, ".skills-cache 下载必须先投放再读");
    }

    /** P1-3：scoped 慢路径把 prune 与解包合并为单条 exec（不再先单独跑一次 prune） */
    @Test
    void scopedSkillCacheSlowPathFoldsPruneIntoSingleExec() throws Exception {
        var host = java.nio.file.Files.createTempDirectory("scoped-single-exec");
        writeHostSkill(host, "sess-A", "demo-a");
        writeHostSkill(host, "sess-B", "demo-b");
        var osb = mockOsb(mockExecution(0, "", ""));
        var st = projectedStateNoSkillCache(host);
        st.setSandboxId("sbx-single-1");
        var sandbox = new OpenSandbox(st, osb, options(), null, null, new OpenSandboxFilesystemSpec());
        sandbox.setSkillCacheScope("sess-A");

        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace", 30);
        sandbox.stop();

        // 单条 exec 同时含解包与按 scope 清理；不存在独立的 prune-only exec
        var captor = ArgumentCaptor.forClass(String.class);
        verify(osb.commands(), atLeastOnce()).run(captor.capture());
        var hydrateCmds = captor.getAllValues().stream()
            .filter(c -> c.contains("tar xzf -") && c.contains("find ")).toList();
        assertEquals(1, hydrateCmds.size(), "解包与 prune 应合并进同一条 exec（P1-3）");
        assertTrue(hydrateCmds.get(0).contains("! -name 'sess-A'"));
        assertEquals(0, captor.getAllValues().stream()
            .filter(c -> c.contains("find ") && !c.contains("tar xzf -")).count(),
            "不得存在 prune-only 的独立 exec");
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
        verify(osb.commands()).run(FIND_PROBE);
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
    void globProbeWithProxySandboxNotFound404ShouldFallBackToRealFind() throws Exception {
        // 同型：walkFiles 对死沙箱代理 404 不得视作「空目录」输出假空结果
        var osb = mockOsb(mockExecution(0, "tree/x.md\t3\t0", ""));
        when(osb.files().listDirectory("tree")).thenThrow(sandboxNotFound404());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), FIND_PROBE, 30);

        assertTrue(result.ok());
        assertEquals("tree/x.md\t3\t0", result.stdout(), "死沙箱代理 404 必须回落真 find，不得假空");
        verify(osb.commands()).run(FIND_PROBE);
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
}
