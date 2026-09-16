package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;

/**
 * WorkspaceReader 测试：使用真实 InMemoryStore + 框架 RemoteFilesystem，
 * 验证 KV 读写与注入路径（与生产 JdbcStore 同接口）。
 */
class WorkspaceReaderTest {

    private static final String USER = "user-alice";

    private InMemoryStore store() {
        return new InMemoryStore();
    }


    private static io.agentscope.harness.agent.DistributedStore distributedStore(
            io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore store) {
        var ds = org.mockito.Mockito.mock(io.agentscope.harness.agent.DistributedStore.class);
        org.mockito.Mockito.when(ds.baseStore()).thenReturn(store);
        return ds;
    }

    /** 用框架 RemoteFilesystem 预置 KV 数据（模拟记忆系统写入） */
    private void seedMemory(InMemoryStore store, String user, String memoryContent) {
        var fs = new RemoteFilesystem(store, java.util.List.of(user));
        var ctx = io.agentscope.core.agent.RuntimeContext.builder().userId(user).build();
        fs.write(ctx, "MEMORY.md", memoryContent);
        fs.write(ctx, "memory/2026-08-12.md", "## 2026-08-12\n- fact one");
    }

    @Test
    void readRuntimeFilesShouldReturnMemoryAndDailyLogs() {
        var store = store();
        seedMemory(store, USER, "# MEMORY\n- long term");
        var reader = new WorkspaceReader(distributedStore(store));

        var files = reader.readRuntimeFiles(USER);

        assertTrue(files.containsKey("MEMORY.md"));
        assertEquals("# MEMORY\n- long term",
            new String(files.get("MEMORY.md"), StandardCharsets.UTF_8));
        assertTrue(files.containsKey("memory/2026-08-12.md"));
        assertTrue(new String(files.get("memory/2026-08-12.md"), StandardCharsets.UTF_8)
            .contains("fact one"));
    }

    @Test
    void readRuntimeFilesShouldReturnEmptyForNewUser() {
        var reader = new WorkspaceReader(distributedStore(store()));

        var files = reader.readRuntimeFiles("brand-new-user");

        assertTrue(files.isEmpty());
    }

    @Test
    void readRuntimeFilesShouldIsolateByUser() {
        var store = store();
        seedMemory(store, USER, "# alice memory");
        var reader = new WorkspaceReader(distributedStore(store));

        var bobFiles = reader.readRuntimeFiles("user-bob");

        assertTrue(bobFiles.isEmpty());
    }

    @Test
    void injectToSandboxShouldWriteAllFiles() {
        var files = Map.of(
            "MEMORY.md", "m1".getBytes(StandardCharsets.UTF_8),
            "memory/2026-08-12.md", "m2".getBytes(StandardCharsets.UTF_8));
        var osb = org.mockito.Mockito.mock(com.alibaba.opensandbox.sandbox.Sandbox.class);
        var filesSvc = org.mockito.Mockito.mock(
            com.alibaba.opensandbox.sandbox.domain.services.Filesystem.class);
        org.mockito.Mockito.when(osb.files()).thenReturn(filesSvc);

        new WorkspaceReader(distributedStore(store())).injectToSandbox(osb, files);

        var captor = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        org.mockito.Mockito.verify(filesSvc).write(captor.capture());
        @SuppressWarnings("unchecked")
        java.util.List<com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry> entries =
            (java.util.List<com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry>) captor.getValue();
        assertEquals(2, entries.size());
        // Map.of 不保证顺序，按内容断言
        var paths = entries.stream().map(e -> e.getPath()).toList();
        assertTrue(paths.containsAll(java.util.List.of(
            "/workspace/MEMORY.md", "/workspace/memory/2026-08-12.md")));
    }

    @Test
    void injectToSandboxShouldSkipEmptyFiles() {
        var osb = org.mockito.Mockito.mock(com.alibaba.opensandbox.sandbox.Sandbox.class);

        new WorkspaceReader(distributedStore(store())).injectToSandbox(osb, Map.of());

        org.mockito.Mockito.verify(osb, org.mockito.Mockito.never()).files();
    }

    // ===== writeWorkspaceFile 测试 =====

    @Test
    void writeWorkspaceFileShouldPersistAndBeReadable() {
        var store = store();
        var reader = new WorkspaceReader(distributedStore(store));

        boolean ok = reader.writeWorkspaceFile(USER, "outputs/report.txt", "hello world");
        assertTrue(ok, "写入 KV 应成功");

        // 用 readWorkspaceFile 读回验证
        var bytes = reader.readWorkspaceFile(USER, "outputs/report.txt");
        assertNotNull(bytes, "读回应非 null");
        assertEquals("hello world", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void writeWorkspaceFileShouldOverwriteExisting() {
        var store = store();
        var reader = new WorkspaceReader(distributedStore(store));

        reader.writeWorkspaceFile(USER, "memo.txt", "v1");
        reader.writeWorkspaceFile(USER, "memo.txt", "v2-overwrite");

        var bytes = reader.readWorkspaceFile(USER, "memo.txt");
        assertNotNull(bytes);
        assertEquals("v2-overwrite", new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void writeWorkspaceFileShouldRejectNullOrBlank() {
        var reader = new WorkspaceReader(distributedStore(store()));

        assertFalse(reader.writeWorkspaceFile(null, "a.txt", "c"));
        assertFalse(reader.writeWorkspaceFile(USER, null, "c"));
        assertFalse(reader.writeWorkspaceFile(USER, "a.txt", null));
        assertFalse(reader.writeWorkspaceFile("  ", "a.txt", "c"));
        assertFalse(reader.writeWorkspaceFile(USER, "  ", "c"));
    }

    @Test
    void writeWorkspaceFileShouldIsolateByUser() {
        var store = store();
        var reader = new WorkspaceReader(distributedStore(store));

        reader.writeWorkspaceFile(USER, "secret.txt", "alice-data");

        var bobBytes = reader.readWorkspaceFile("user-bob", "secret.txt");
        assertNull(bobBytes, "Bob 不应能读到 Alice 的数据");
    }

    @Test
    void writeWorkspaceFileThenPresentFileCanRead() {
        // 模拟完整链路：write_file → KV 同步 → present_file 从 KV 读
        var store = store();
        var reader = new WorkspaceReader(distributedStore(store));

        // 1. write_file 同步写 KV
        reader.writeWorkspaceFile(USER, "outputs/result.md", "# AI Report\nContent here");

        // 2. present_file 通过 readWorkspaceFile 从 KV 读
        var bytes = reader.readWorkspaceFile(USER, "outputs/result.md");
        assertNotNull(bytes, "present_file 应从 KV 读到 write_file 写入的内容");
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("AI Report"));
    }
}
