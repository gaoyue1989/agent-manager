package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo;
import com.alibaba.opensandbox.sandbox.domain.services.Filesystem;

import io.agentmanager.framework.service.WorkspaceReader;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;

/**
 * WorkspaceSyncService 测试：真实 InMemoryStore 验证 KV 回写落盘，
 * mock SDK 文件读取。
 */
class WorkspaceSyncServiceTest {

    private static final String USER = "user-alice";

    private static Filesystem mockFiles(String memoryContent, List<String> dailyLogs) throws Exception {
        var files = mock(Filesystem.class);
        when(files.readByteArray("/workspace/MEMORY.md"))
            .thenReturn(memoryContent.getBytes(StandardCharsets.UTF_8));
        when(files.readFile("/workspace/MEMORY.md")).thenReturn(memoryContent);
        if (dailyLogs == null) {
            when(files.listDirectory("/workspace/memory")).thenThrow(new RuntimeException("not found"));
        } else {
            // listDirectory 返回沙箱内完整路径
            var entries = dailyLogs.stream()
                .map(name -> {
                    var e = mock(EntryInfo.class);
                    when(e.getPath()).thenReturn("/workspace/memory/" + name);
                    return e;
                })
                .toList();
            when(files.listDirectory("/workspace/memory")).thenReturn(entries);
            for (var name : dailyLogs) {
                when(files.readFile("/workspace/memory/" + name))
                    .thenReturn("content-of-" + name);
            }
        }
        return files;
    }

    @Test
    void syncBackShouldWriteMemoryAndDailyLogsToKV() throws Exception {
        var store = new InMemoryStore();
        var files = mockFiles("# MEMORY\n- updated", List.of("2026-08-12.md"));
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        new WorkspaceSyncService(store).syncBack(USER, osb);

        // 验证 KV 已落盘（用框架 RemoteFilesystem 读回）
        var fs = new RemoteFilesystem(store, List.of(USER));
        var ctx = io.agentscope.core.agent.RuntimeContext.builder().userId(USER).build();
        var memory = fs.read(ctx, WorkspaceReader.MEMORY_FILE, 0, -1);
        assertTrue(memory.isSuccess());
        assertEquals("# MEMORY\n- updated", memory.fileData().content());
        var daily = fs.read(ctx, "memory/2026-08-12.md", 0, -1);
        assertTrue(daily.isSuccess());
        assertEquals("content-of-2026-08-12.md", daily.fileData().content());
    }

    @Test
    void syncBackShouldEditExistingFileInsteadOfFailing() throws Exception {
        var store = new InMemoryStore();
        // 预置旧记忆（模拟首次回写后的状态）
        var seed = new RemoteFilesystem(store, List.of(USER));
        var seedCtx = io.agentscope.core.agent.RuntimeContext.builder().userId(USER).build();
        seed.write(seedCtx, WorkspaceReader.MEMORY_FILE, "old memory");

        // 第二次回写：沙箱内容已更新
        var files = mockFiles("# MEMORY\n- new content", null);
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        new WorkspaceSyncService(store).syncBack(USER, osb);

        // 已存在文件应通过 edit 更新而非 write 失败
        var fs = new RemoteFilesystem(store, List.of(USER));
        var memory = fs.read(seedCtx, WorkspaceReader.MEMORY_FILE, 0, -1);
        assertTrue(memory.isSuccess());
        assertEquals("# MEMORY\n- new content", memory.fileData().content());
    }

    @Test
    void syncBackShouldEditExistingDailyLog() throws Exception {
        var store = new InMemoryStore();
        var seed = new RemoteFilesystem(store, List.of(USER));
        var seedCtx = io.agentscope.core.agent.RuntimeContext.builder().userId(USER).build();
        seed.write(seedCtx, "memory/2026-08-12.md", "old daily");

        var files = mockFiles("# M", List.of("2026-08-12.md"));
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        new WorkspaceSyncService(store).syncBack(USER, osb);

        var fs = new RemoteFilesystem(store, List.of(USER));
        var daily = fs.read(seedCtx, "memory/2026-08-12.md", 0, -1);
        assertTrue(daily.isSuccess());
        assertEquals("content-of-2026-08-12.md", daily.fileData().content());
    }

    @Test
    void syncBackShouldSkipWhenNoMemoryDir() throws Exception {
        var store = new InMemoryStore();
        var files = mockFiles("# M", null);
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        new WorkspaceSyncService(store).syncBack(USER, osb);

        var fs = new RemoteFilesystem(store, List.of(USER));
        var ctx = io.agentscope.core.agent.RuntimeContext.builder().userId(USER).build();
        assertTrue(fs.read(ctx, WorkspaceReader.MEMORY_FILE, 0, -1).isSuccess());
    }

    @Test
    void syncBackShouldIgnoreBlankUserIdOrNullSandbox() throws Exception {
        var store = new InMemoryStore();
        var svc = new WorkspaceSyncService(store);

        svc.syncBack("", null);
        svc.syncBack(null, null);
        svc.syncBack("  ", mock(Sandbox.class));

        assertEquals(0, store.size());
    }

    @Test
    void syncBackShouldNotThrowOnFailure() {
        var store = new InMemoryStore();
        var osb = mock(Sandbox.class);
        var files = mock(Filesystem.class);
        when(files.readByteArray(anyString())).thenThrow(new RuntimeException("execd down"));
        when(osb.files()).thenReturn(files);

        // 不抛异常，仅告警
        new WorkspaceSyncService(store).syncBack(USER, osb);
    }
}
