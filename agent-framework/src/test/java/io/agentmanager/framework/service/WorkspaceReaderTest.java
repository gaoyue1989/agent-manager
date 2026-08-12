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
}
