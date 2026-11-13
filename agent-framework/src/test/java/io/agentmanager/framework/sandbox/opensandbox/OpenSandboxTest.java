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
        // 同型：walkFiles 对死沙箱代理 404 不得视作「空目录」输出假空结果
        var osb = mockOsb(mockExecution(0, "tree/x.md\t3\t0", ""));
        when(osb.files().listDirectory("tree")).thenThrow(sandboxNotFound404());
        var sandbox = new OpenSandbox(state(), osb, options(), null, null);

        var result = sandbox.exec(RuntimeContext.builder().userId("u").build(), FIND_PROBE, 30);

        assertTrue(result.ok());
        assertEquals("tree/x.md\t3\t0", result.stdout(), "死沙箱代理 404 必须回落真 find，不得假空");
        verify(osb.commands()).run(FIND_PROBE);
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
        return facade;
    }

    @Test
    void scopedSkillCacheFingerprintShouldUsePackageSourceNotStageStat() throws Exception {
        // 回归钉子（9d77505 缺口④①）：stage 副本目录的 mtime 各副本不同（各自 staging 时刻），
        // 若指纹退化回 stage 目录 stat，跨副本指纹永不等、共享层永不命中、每副本重复付 4-6s 慢路径。
        // 正确指纹源 = 包内源目录 stat（PVC 同挂载跨副本一致）+ L4 revision（KV 共享）。
        var pkg = java.nio.file.Files.createTempDirectory("scoped-fp-pkg");
        java.nio.file.Files.writeString(pkg.resolve("SKILL.md"), "---\nname: pkg\n---\n");

        // 两个 mtime 不同的 stage 目录（显式拉开，模拟不同副本各自 staging）
        var hostA = java.nio.file.Files.createTempDirectory("scoped-fp-a");
        writeHostSkill(hostA, "sess-A", "demo-a");
        var hostB = java.nio.file.Files.createTempDirectory("scoped-fp-b");
        writeHostSkill(hostB, "sess-A", "demo-a");
        java.nio.file.Files.setLastModifiedTime(
            hostA.resolve(".skills-cache/sess-A/oaf-package/demo-a/SKILL.md"),
            java.nio.file.attribute.FileTime.fromMillis(1700000000000L));
        java.nio.file.Files.setLastModifiedTime(
            hostB.resolve(".skills-cache/sess-A/oaf-package/demo-a/SKILL.md"),
            java.nio.file.attribute.FileTime.fromMillis(1777777777777L));

        var data = new java.util.HashMap<String, String>();
        var store = new ScopedFingerprintStore(fakeScopedFpFacade(data));
        var osb = mockOsb(mockExecution(0, "", ""));

        // 副本1：全新 spec（Pod 级表空）→ 慢路径投放 → 指纹经共享层 setex 落地
        var spec1 = new OpenSandboxFilesystemSpec();
        spec1.packageSkillsDir(pkg);
        spec1.sharedFingerprintStore(store);
        var st1 = projectedStateNoSkillCache(hostA);
        st1.setSandboxId("sbx-fp-1");
        var call1 = new OpenSandbox(st1, osb, options(), null, null, spec1);
        call1.setSkillCacheScope("sess-A");
        call1.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 1", 30);
        call1.stop();
        int afterCall1 = countHydrateTarWrites(osb);
        assertTrue(afterCall1 >= 1, "副本1 首轮应实际投放");
        assertFalse(data.isEmpty(), "投放成功后指纹必须写入共享层（recordScopedSkillCacheFingerprint 双写）");

        // 副本2：全新 spec（Pod 级表空 → 强制走共享层读回）+ mtime 不同的 stage 目录
        // → 指纹仍一致（源自包内源而非 stage stat）→ 命中稳态短路，不重投
        var spec2 = new OpenSandboxFilesystemSpec();
        spec2.packageSkillsDir(pkg);
        spec2.sharedFingerprintStore(store);
        var st2 = projectedStateNoSkillCache(hostB);
        st2.setSandboxId("sbx-fp-1");
        var call2 = new OpenSandbox(st2, osb, options(), null, null, spec2);
        call2.setSkillCacheScope("sess-A");
        call2.exec(RuntimeContext.builder().userId("sess-A").build(), "echo 2", 30);
        call2.stop();
        assertEquals(afterCall1, countHydrateTarWrites(osb),
            "同包源+同 revision（stage mtime 不同）→ 共享层指纹命中 → 副本2 不得重投（重投即指纹源退化为 stage stat）");
    }

    @Test
    void scopedSkillCacheShouldRehydrateWhenRevisionChanges() throws Exception {
        // 回归钉子（9d77505 缺口④②）：指纹必须含 L4 revision 段——revision 是 KV 共享的用户级
        // 技能差异信号，变化后同容器代同 scope 的下一 call 必须重新投放（缺失该段=陈旧技能=正确性）
        var host = java.nio.file.Files.createTempDirectory("scoped-rev");
        writeHostSkill(host, "sess-A", "demo-a");
        var reader = mock(WorkspaceReader.class);
        var rev = new java.util.concurrent.atomic.AtomicReference<>("rev-1");
        when(reader.readUserSkillRevision(anyString())).thenAnswer(inv -> rev.get());

        var osb = mockOsb(mockExecution(0, "", ""));
        var spec = new OpenSandboxFilesystemSpec();

        // call1（实例1，生产 acquire 语义）：rev-1 → 投放并记录指纹
        var st1 = projectedStateNoSkillCache(host);
        st1.setSandboxId("sbx-rev-1");
        var call1 = new OpenSandbox(st1, osb, options(), reader, null, spec);
        call1.setUserKey("alice");
        call1.setSkillCacheScope("sess-A");
        call1.exec(RuntimeContext.builder().userId("alice").build(), "echo 1", 30);
        call1.stop();
        int afterCall1 = countHydrateTarWrites(osb);
        assertTrue(afterCall1 >= 1, "首轮应实际投放");

        // 用户技能变更：KV revision rev-1 → rev-2（跨实例可见，生产=任意副本）
        rev.set("rev-2");

        // call2（实例2，同容器代同 scope）：revision 变化 → 指纹变化 → 必须重新投放
        var st2 = projectedStateNoSkillCache(host);
        st2.setSandboxId("sbx-rev-1");
        var call2 = new OpenSandbox(st2, osb, options(), reader, null, spec);
        call2.setUserKey("alice");
        call2.setSkillCacheScope("sess-A");
        call2.exec(RuntimeContext.builder().userId("alice").build(), "echo 2", 30);
        call2.stop();
        assertTrue(countHydrateTarWrites(osb) > afterCall1,
            "revision rev-1→rev-2 后第二次 call 必须重新投放（不重投即指纹缺 revision 段，技能陈旧）");
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

    private static void bindFarmSession(OpenSandbox sandbox, String scope) {
        sandbox.setUserKey(scope);
        sandbox.setSkillCacheScope(scope);
    }

    @Test
    void farmSwitchOffShouldKeepCurrentTarBehavior() throws Exception {
        var host = java.nio.file.Files.createTempDirectory("farm-off");
        writeHostSkill(host, "sess-A", "demo-a");
        var osb = farmOsb("v-anything");

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-off"), osb, options(), null, null);
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(0, farmReadlinkCount(osb), "开关（store 路径）为空不得探测 store");
        assertTrue(countHydrateTarWrites(osb) >= 1, "开关关闭走现行为（tar 物化）");
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
    void farmStoreMissingShouldDegradeWholeSessionToTar() throws Exception {
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
        assertTrue(countHydrateTarWrites(osb) >= 1, "降级会话整体走 tar 物化");
        assertTrue(farmConvergeCommands(osb).isEmpty(), "降级会话不得建软链");

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
        // 第一根因即守护→端点链路不可达（token 漂移 401 / 端点关闭 404 / egress 断网），
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
        assertTrue(countHydrateTarWrites(osb) >= 1, "降级会话整体走 tar 物化（fail-open 不变）");
        assertTrue(farmConvergeCommands(osb).isEmpty(), "降级会话不得建软链");
    }

    @Test
    void farmEndpointFingerprintErrorShouldDegradeAsEndpointDown() throws Exception {
        // 指纹计算失败（computeVersion 全 catch → ERR-*，输出约定见 SkillBundleServiceTest）=
        // 端点无法提供有效版本：判定与 svc 未装配同类归 degraded_endpoint_down，不得落
        // version_mismatch（版本端点同时 503，守护退避重试不污染 store）
        var host = java.nio.file.Files.createTempDirectory("farm-err-endpoint");
        writeHostSkill(host, "sess-A", "demo-a");
        var svc = mock(SkillBundleService.class);
        when(svc.currentVersion()).thenReturn(new SkillBundleService.SkillVersion("ERR-123456", 0, 0L));
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsb("v-0123456789abcdef", true);

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-err-ep"), osb,
            farmOptions(svc, registry, null), null, null, new OpenSandboxFilesystemSpec());
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(1.0, registry.get("skills.farm.decision").tag("result", "degraded_endpoint_down")
            .counter().count(), "D-5：指纹计算失败 = 端点不可用降级");
        assertTrue(countHydrateTarWrites(osb) >= 1, "降级会话整体走 tar 物化");
        assertTrue(farmConvergeCommands(osb).isEmpty(), "降级会话不得建软链");
    }

    @Test
    void farmVersionMismatchShouldDegradeToTar() throws Exception {
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
        assertTrue(countHydrateTarWrites(osb) >= 1, "硬校验失败 = store 不可用，本会话 tar 物化");
        assertTrue(farmConvergeCommands(osb).isEmpty());
    }

    @Test
    void farmEndpointUnwiredShouldDegradeToTar() throws Exception {
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
        assertTrue(countHydrateTarWrites(osb) >= 1);
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
    void farmSteadyShouldSkipConvergeExecUntilHostChanges() throws Exception {
        // D-10：农场稳态 = 与 tar 路径 P0-1 同源的指纹短路——同沙箱代同指纹 0 exec（稳态不劣于
        // 关闭农场时的指纹开销）；宿主内容变化（包源变更 → 包指纹变化）才重收敛。同代内不再收敛
        // 同时是「会话中途容器重启（同 sandboxId resume）软链不重建」盲点的代码事实（§6.2-4 已接受）。
        var host = java.nio.file.Files.createTempDirectory("farm-steady");
        writeHostSkill(host, "sess-A", "demo-a");
        var cfg = java.nio.file.Files.createTempDirectory("farm-steady-cfg");
        bundleSkill(cfg, "demo-a");
        var svc = bundleService(cfg);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var osb = farmOsb("v-" + svc.currentVersion().version());
        var spec = new OpenSandboxFilesystemSpec().packageSkillsDir(cfg.resolve("skills"));

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

        // 宿主包源变更（发布语义）→ 包指纹变化 → 重收敛
        java.nio.file.Files.writeString(cfg.resolve("skills/demo-a/SKILL.md"), "---\nname: demo-a\n---\nchanged");
        java.nio.file.Files.setLastModifiedTime(cfg.resolve("skills/demo-a/SKILL.md"),
            java.nio.file.attribute.FileTime.fromMillis(1777777777777L));
        var call3 = new OpenSandbox(farmState(host, "sbx-farm-steady"), osb,
            farmOptions(svc, registry, null), null, null, spec);
        bindFarmSession(call3, "sess-A");
        call3.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(2, farmConvergeCommands(osb).size(), "宿主指纹变化必须重收敛（否则新内容不进链）");
    }

    @Test
    void farmFlipFromDegradedTarToFarmShouldConvergeWithinSameGeneration() throws Exception {
        // D-11：同沙箱代先降级（tar 物化并记账）→ gate 丢失（框架重启/漂副本/LRU 逐出，Pod 级
        // 指纹记账与 Redis 共享层存续）→ 守护就绪重判定 farm——farm 稳态不得命中 tar 记账跳过
        // 收敛，否则降级残留的真实文件副本永久滞留（D-2 失效，发布换版对该会话不可见）
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

        assertTrue(countHydrateTarWrites(osb) >= 1, "降级会话走 tar 物化并记账");
        assertTrue(farmConvergeCommands(osb).isEmpty(), "降级会话不得建软链");

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
    void farmFlipFromFarmToDegradedTarShouldMaterializeWithinSameGeneration() throws Exception {
        // D-11 反向：farm 会话记账后 gate 丢失、重判定降级——降级会话必须走 tar 物化拿新内容
        // （B-1），farm 记账不得满足降级稳态（否则零动作保留旧版 store 软链，降级语义被架空）
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
        long tarWrites = org.mockito.Mockito.mockingDetails(osb.files()).getInvocations().stream()
            .filter(inv -> inv.getMethod().getName().equals("write"))
            .filter(inv -> {
                var arg = inv.getArgument(0);
                return arg instanceof java.util.List<?> list && !list.isEmpty()
                    && ((WriteEntry) list.get(0)).getPath() != null
                    && ((WriteEntry) list.get(0)).getPath().startsWith("/tmp/workspace.");
            }).count();
        assertTrue(tarWrites >= 1,
            "同代 farm→降级翻转：farm 记账不得满足降级稳态，必须重物化拿新内容（B-1）");
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
        var spec = new OpenSandboxFilesystemSpec().packageSkillsDir(cfg.resolve("skills"));

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
    void farmConvergeFailureShouldRetryOnNextTrigger() throws Exception {
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
        when(commands.run(anyString())).thenAnswer(inv -> {
            var cmd = inv.getArgument(0, String.class);
            return cmd.startsWith("readlink ")
                ? mockExecution(0, "v-" + version, "")
                : mockExecution(1, "", "ln failed");
        });
        var files = mock(Filesystem.class);
        when(osb.files()).thenReturn(files);

        var sandbox = new OpenSandbox(farmState(host, "sbx-farm-9"), osb,
            farmOptions(svc, registry, null), null, null, new OpenSandboxFilesystemSpec());
        bindFarmSession(sandbox, "sess-A");
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);
        sandbox.exec(RuntimeContext.builder().userId("sess-A").build(), "ls /workspace/.skills-cache", 30);

        assertEquals(1, farmReadlinkCount(osb), "收敛失败不改判定：会话仍记忆为 farm");
        assertEquals(2, farmConvergeCommands(osb).size(), "收敛失败按投放失败语义复位，下次触发重试");
    }
}
