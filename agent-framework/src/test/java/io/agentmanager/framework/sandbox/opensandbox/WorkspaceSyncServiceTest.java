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

import io.agentmanager.framework.service.UserSkillService;
import io.agentmanager.framework.service.WorkspaceReader;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;

/**
 * WorkspaceSyncService 测试：真实 InMemoryStore 验证 KV 回写落盘，
 * mock SDK 文件读取。
 */
class WorkspaceSyncServiceTest {

    private static final String USER = "user-alice";
    private static final String AGENT = "Sandbox Skill Upload E2E";

    /** 与 WorkspaceReaderTest 同款：用 mock DistributedStore 暴露 InMemoryStore */
    private static io.agentscope.harness.agent.DistributedStore distributedStore(InMemoryStore store) {
        var ds = mock(io.agentscope.harness.agent.DistributedStore.class);
        when(ds.baseStore()).thenReturn(store);
        return ds;
    }

    /** 被测服务：agentName 为 null 时回落裸 userId 命名空间（旧行为），非 null 时与框架一致 */
    private static WorkspaceSyncService service(InMemoryStore store, String agentName) {
        return new WorkspaceSyncService(new WorkspaceReader(distributedStore(store), agentName));
    }

    private static EntryInfo dirEntry(String path) {
        var e = mock(EntryInfo.class);
        when(e.getPath()).thenReturn(path);
        when(e.getType()).thenReturn("directory");
        return e;
    }

    private static EntryInfo fileEntry(String path) {
        return fileEntry(path, 0L);
    }

    /** size 显式打桩：EntryInfo.getSize() 是基本类型 long，不桩则恒为 0，超限预检（读之前那道防线）测不到 */
    private static EntryInfo fileEntry(String path, long size) {
        var e = mock(EntryInfo.class);
        when(e.getPath()).thenReturn(path);
        when(e.getType()).thenReturn("file");
        when(e.getSize()).thenReturn(size);
        return e;
    }

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

        service(store, null).syncBack(USER, osb);

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

        service(store, null).syncBack(USER, osb);

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

        service(store, null).syncBack(USER, osb);

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

        service(store, null).syncBack(USER, osb);

        var fs = new RemoteFilesystem(store, List.of(USER));
        var ctx = io.agentscope.core.agent.RuntimeContext.builder().userId(USER).build();
        assertTrue(fs.read(ctx, WorkspaceReader.MEMORY_FILE, 0, -1).isSuccess());
    }

    @Test
    void syncBackShouldIgnoreBlankUserIdOrNullSandbox() throws Exception {
        var store = new InMemoryStore();
        var svc = service(store, null);

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
        service(store, null).syncBack(USER, osb);
    }

    // ==================== 沙箱 L4 技能回写（skill_manage 写容器 → KV） ====================

    /** 沙箱内 skill_manage 产出的技能目录应落到 agents/{agent}/users/{uid}/skills */
    @Test
    void syncBackShouldWriteSandboxSkillsToL4KV() throws Exception {
        var store = new InMemoryStore();
        var skillMd = "---\nname: sbx-skill\n---\n\nSBX-L4-PROBE\n";
        var files = mock(Filesystem.class);
        // 目录/文件条目先构造好再 stub——Mockito 禁止在 when(...) 参数里创建并 stub 另一个 mock
        var skillDir = dirEntry("/workspace/skills/sbx-skill");
        var skillMdEntry = fileEntry("/workspace/skills/sbx-skill/SKILL.md");
        var scriptsDir = dirEntry("/workspace/skills/sbx-skill/scripts");
        var runShEntry = fileEntry("/workspace/skills/sbx-skill/scripts/run.sh");
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(skillDir));
        when(files.listDirectory("/workspace/skills/sbx-skill"))
            .thenReturn(List.of(skillMdEntry, scriptsDir));
        when(files.listDirectory("/workspace/skills/sbx-skill/scripts")).thenReturn(List.of(runShEntry));
        when(files.readFile("/workspace/skills/sbx-skill/SKILL.md")).thenReturn(skillMd);
        when(files.readFile("/workspace/skills/sbx-skill/scripts/run.sh")).thenReturn("#!/bin/sh\necho hi\n");
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        // L4 命名空间与 key 形态（与 WorkspaceReader/UserSkillService/SDK KV 路由一致）
        var fs = new RemoteFilesystem(store, List.of("agents", AGENT, "users", USER, "skills"));
        var ctx = io.agentscope.core.agent.RuntimeContext.builder().userId(USER).build();
        var main = fs.read(ctx, "/sbx-skill/SKILL.md", 0, -1);
        assertTrue(main.isSuccess(), "SKILL.md 应落到 L4 KV");
        assertEquals(skillMd, main.fileData().content());
        var script = fs.read(ctx, "/sbx-skill/scripts/run.sh", 0, -1);
        assertTrue(script.isSuccess(), "技能资源文件应一并回写");
        assertEquals("#!/bin/sh\necho hi\n", script.fileData().content());
    }

    /** 沙箱内二次修改：KV 已有旧内容时应走 edit 覆盖而非失败 */
    @Test
    void syncBackShouldOverwriteExistingL4Skill() throws Exception {
        var store = new InMemoryStore();
        var reader = new WorkspaceReader(distributedStore(store), AGENT);
        assertTrue(reader.writeUserSkillFile(USER, "sbx-skill", "SKILL.md", "old content"));

        var files = mock(Filesystem.class);
        var skillDir = dirEntry("/workspace/skills/sbx-skill");
        var skillMdEntry = fileEntry("/workspace/skills/sbx-skill/SKILL.md");
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(skillDir));
        when(files.listDirectory("/workspace/skills/sbx-skill")).thenReturn(List.of(skillMdEntry));
        when(files.readFile("/workspace/skills/sbx-skill/SKILL.md")).thenReturn("new content");
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        assertEquals("new content", reader.readUserSkillFile(USER, "sbx-skill", "SKILL.md"));
    }

    /** 元数据目录/文件（.audit/_drafts/.skills-cache）与无 SKILL.md 的目录都不算技能 */
    @Test
    void syncBackShouldSkipMetadataAndNonSkillDirs() throws Exception {
        var store = new InMemoryStore();
        var files = mock(Filesystem.class);
        var auditDir = dirEntry("/workspace/skills/.audit");
        var draftsDir = dirEntry("/workspace/skills/_drafts");
        var cacheDir = dirEntry("/workspace/skills/.skills-cache");
        var notesDir = dirEntry("/workspace/skills/notes");
        var usageFile = fileEntry("/workspace/skills/.usage.json");
        var notesReadme = fileEntry("/workspace/skills/notes/readme.txt");
        when(files.listDirectory("/workspace/skills"))
            .thenReturn(List.of(auditDir, draftsDir, cacheDir, notesDir, usageFile));
        when(files.readFile("/workspace/skills/.usage.json")).thenReturn("{}");
        when(files.listDirectory("/workspace/skills/notes")).thenReturn(List.of(notesReadme));
        when(files.readFile("/workspace/skills/notes/readme.txt")).thenReturn("no skill md");
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        assertEquals(0, store.size(), "元数据与非技能目录不应落库");
    }

    /** 超过 100KB 上限的单个技能文件跳过，不影响同技能主文件回写（size 预检 + 读回字节双道防线） */
    @Test
    void syncBackShouldSkipOversizedSkillFile() throws Exception {
        var store = new InMemoryStore();
        var files = mock(Filesystem.class);
        var bigSkillDir = dirEntry("/workspace/skills/big-skill");
        var bigSkillMd = fileEntry("/workspace/skills/big-skill/SKILL.md", 5L);
        // 条目 size 已超限：预检直接在 readFile 前拦下（避免把超大文件整份读进 Java 进程内存）
        var bigBin = fileEntry("/workspace/skills/big-skill/big.bin", UserSkillService.MAX_CONTENT_BYTES + 1L);
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(bigSkillDir));
        when(files.listDirectory("/workspace/skills/big-skill")).thenReturn(List.of(bigSkillMd, bigBin));
        when(files.readFile("/workspace/skills/big-skill/SKILL.md")).thenReturn("# big");
        when(files.readFile("/workspace/skills/big-skill/big.bin"))
            .thenReturn("x".repeat(UserSkillService.MAX_CONTENT_BYTES + 1));
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        var reader = new WorkspaceReader(distributedStore(store), AGENT);
        assertNotNull(reader.readUserSkillFile(USER, "big-skill", "SKILL.md"));
        assertNull(reader.readUserSkillFile(USER, "big-skill", "big.bin"));
        verify(files, never()).readFile("/workspace/skills/big-skill/big.bin");
    }

    /** 条目 size 未超限但读回内容超限：由读回后的字节长度检查拦住（第二道防线） */
    @Test
    void syncBackShouldSkipFileWhoseContentExceedsLimit() throws Exception {
        var store = new InMemoryStore();
        var files = mock(Filesystem.class);
        var skillDir = dirEntry("/workspace/skills/lie-skill");
        var skillMd = fileEntry("/workspace/skills/lie-skill/SKILL.md", 5L);
        var lying = fileEntry("/workspace/skills/lie-skill/lying.txt", 1L); // size 谎报为 1
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(skillDir));
        when(files.listDirectory("/workspace/skills/lie-skill")).thenReturn(List.of(skillMd, lying));
        when(files.readFile("/workspace/skills/lie-skill/SKILL.md")).thenReturn("# lie");
        when(files.readFile("/workspace/skills/lie-skill/lying.txt"))
            .thenReturn("x".repeat(UserSkillService.MAX_CONTENT_BYTES + 1));
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        var reader = new WorkspaceReader(distributedStore(store), AGENT);
        assertNotNull(reader.readUserSkillFile(USER, "lie-skill", "SKILL.md"));
        assertNull(reader.readUserSkillFile(USER, "lie-skill", "lying.txt"));
    }

    /** 目录条目被标成 file（execd type 口径差异）时，应回落按目录递归而不是漏掉整个技能 */
    @Test
    void syncBackShouldRecurseWhenDirMisclassifiedAsFile() throws Exception {
        var store = new InMemoryStore();
        var skillMd = "# mis-dir";
        var files = mock(Filesystem.class);
        var skillDir = dirEntry("/workspace/skills/mis-skill");
        var skillMdEntry = fileEntry("/workspace/skills/mis-skill/SKILL.md");
        // 实测口径未知：scripts 被标成 file
        var scriptsAsFile = fileEntry("/workspace/skills/mis-skill/scripts");
        var runShEntry = fileEntry("/workspace/skills/mis-skill/scripts/run.sh");
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(skillDir));
        when(files.listDirectory("/workspace/skills/mis-skill")).thenReturn(List.of(skillMdEntry, scriptsAsFile));
        when(files.readFile("/workspace/skills/mis-skill/SKILL.md")).thenReturn(skillMd);
        when(files.readFile("/workspace/skills/mis-skill/scripts"))
            .thenThrow(new RuntimeException("Client error : 400 is a directory"));
        when(files.listDirectory("/workspace/skills/mis-skill/scripts")).thenReturn(List.of(runShEntry));
        when(files.readFile("/workspace/skills/mis-skill/scripts/run.sh")).thenReturn("echo nested");
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        var reader = new WorkspaceReader(distributedStore(store), AGENT);
        assertEquals(skillMd, reader.readUserSkillFile(USER, "mis-skill", "SKILL.md"));
        assertEquals("echo nested", reader.readUserSkillFile(USER, "mis-skill", "scripts/run.sh"));
    }

    /** skills/ 列举失败（沙箱无该目录）只跳过技能回写，记忆回写不受影响 */
    @Test
    void syncBackShouldTolerateSkillsListingFailure() throws Exception {
        var store = new InMemoryStore();
        var files = mockFiles("# M", null);
        when(files.listDirectory("/workspace/skills")).thenThrow(new RuntimeException("404 Not Found"));
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        var fs = new RemoteFilesystem(store, List.of("agents", AGENT, "users", USER));
        var ctx = io.agentscope.core.agent.RuntimeContext.builder().userId(USER).build();
        assertTrue(fs.read(ctx, WorkspaceReader.MEMORY_FILE, 0, -1).isSuccess());
    }

    /** 记忆回写命名空间修正：配了 agentName 时与框架读取侧一致（agents/{agent}/users/{uid}） */
    @Test
    void syncBackShouldWriteRuntimeFilesToAgentNamespace() throws Exception {
        var store = new InMemoryStore();
        var files = mockFiles("# MEMORY\n- agent ns", List.of("2026-09-23.md"));
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        var fs = new RemoteFilesystem(store, List.of("agents", AGENT, "users", USER));
        var ctx = io.agentscope.core.agent.RuntimeContext.builder().userId(USER).build();
        var memory = fs.read(ctx, WorkspaceReader.MEMORY_FILE, 0, -1);
        assertTrue(memory.isSuccess());
        assertEquals("# MEMORY\n- agent ns", memory.fileData().content());
        var daily = fs.read(ctx, "memory/2026-09-23.md", 0, -1);
        assertTrue(daily.isSuccess());
        assertEquals("content-of-2026-09-23.md", daily.fileData().content());
    }

    /**
     * 相对路径口径（个别适配器返回相对 /workspace 的路径）：条目必须还原成
     * {@code /workspace/memory/<name>}，KV key 仍为 {@code memory/<name>}（不能丢掉 memory/ 前缀）；
     * 单条目读失败只跳过该条，同目录其余记忆照常回写。
     */
    @Test
    void syncBackShouldHandleRelativeMemoryEntriesAndIsolateReadFailure() throws Exception {
        var store = new InMemoryStore();
        var files = mock(Filesystem.class);
        when(files.readByteArray("/workspace/MEMORY.md")).thenReturn("# M".getBytes(StandardCharsets.UTF_8));
        when(files.readFile("/workspace/MEMORY.md")).thenReturn("# M");
        var okEntry = mock(EntryInfo.class);
        when(okEntry.getPath()).thenReturn("memory/2026-08-13.md");     // 相对 /workspace
        var badEntry = mock(EntryInfo.class);
        when(badEntry.getPath()).thenReturn("memory/2026-08-14.md");
        when(files.listDirectory("/workspace/memory")).thenReturn(List.of(badEntry, okEntry));
        when(files.readFile("/workspace/memory/2026-08-13.md")).thenReturn("relative ok");
        when(files.readFile("/workspace/memory/2026-08-14.md"))
            .thenThrow(new RuntimeException("execd read failed"));
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        var fs = new RemoteFilesystem(store, List.of("agents", AGENT, "users", USER));
        var ctx = io.agentscope.core.agent.RuntimeContext.builder().userId(USER).build();
        var ok = fs.read(ctx, "memory/2026-08-13.md", 0, -1);
        assertTrue(ok.isSuccess(), "相对条目必须映射为 memory/<name>（不能丢前缀/读错路径）");
        assertEquals("relative ok", ok.fileData().content());
        assertFalse(fs.read(ctx, "2026-08-13.md", 0, -1).isSuccess(), "不得写到丢前缀的键上");
        assertEquals(2, store.size(), "仅 MEMORY.md + 成功条目落库（失败的条目跳过，不中断整轮）");
    }

    // ==================== 早退分支与不变量（删掉即静默回归） ====================

    /** 技能目录**内部**的元数据段必须过滤（.archive/_drafts/.usage.json），不能镜像进 L4 KV */
    @Test
    void syncBackShouldFilterMetadataInsideSkillDir() throws Exception {
        var store = new InMemoryStore();
        var files = mock(Filesystem.class);
        var skillDir = dirEntry("/workspace/skills/sbx-meta");
        var skillMdEntry = fileEntry("/workspace/skills/sbx-meta/SKILL.md", 5L);
        var archiveDir = dirEntry("/workspace/skills/sbx-meta/.archive");
        var draftsDir = dirEntry("/workspace/skills/sbx-meta/_drafts");
        var usageFile = fileEntry("/workspace/skills/sbx-meta/.usage.json", 2L);
        var oldMd = fileEntry("/workspace/skills/sbx-meta/.archive/old.md", 3L);
        var wipMd = fileEntry("/workspace/skills/sbx-meta/_drafts/wip.md", 3L);
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(skillDir));
        when(files.listDirectory("/workspace/skills/sbx-meta"))
            .thenReturn(List.of(skillMdEntry, archiveDir, draftsDir, usageFile));
        when(files.listDirectory("/workspace/skills/sbx-meta/.archive")).thenReturn(List.of(oldMd));
        when(files.listDirectory("/workspace/skills/sbx-meta/_drafts")).thenReturn(List.of(wipMd));
        when(files.readFile("/workspace/skills/sbx-meta/SKILL.md")).thenReturn("# meta");
        when(files.readFile("/workspace/skills/sbx-meta/.usage.json")).thenReturn("{}");
        when(files.readFile("/workspace/skills/sbx-meta/.archive/old.md")).thenReturn("old");
        when(files.readFile("/workspace/skills/sbx-meta/_drafts/wip.md")).thenReturn("wip");
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        var reader = new WorkspaceReader(distributedStore(store), AGENT);
        assertEquals("# meta", reader.readUserSkillFile(USER, "sbx-meta", "SKILL.md"));
        assertNull(reader.readUserSkillFile(USER, "sbx-meta", ".archive/old.md"), "归档不得镜像进 KV");
        assertNull(reader.readUserSkillFile(USER, "sbx-meta", "_drafts/wip.md"), "草稿不得镜像进 KV");
        assertNull(reader.readUserSkillFile(USER, "sbx-meta", ".usage.json"));
        assertEquals(1, store.size(), "只有 SKILL.md 落库");
    }

    /** 单个技能文件数超上限（200）→ 整个技能跳过，不写半份 */
    @Test
    void syncBackShouldSkipSkillExceedingFileCountLimit() throws Exception {
        var store = new InMemoryStore();
        var files = mock(Filesystem.class);
        var skillDir = dirEntry("/workspace/skills/many-skill");
        var entries = new java.util.ArrayList<EntryInfo>();
        entries.add(fileEntry("/workspace/skills/many-skill/SKILL.md", 3L));
        when(files.readFile("/workspace/skills/many-skill/SKILL.md")).thenReturn("# many");
        for (var i = 0; i <= UserSkillService.MAX_SYNC_FILES; i++) {
            var path = "/workspace/skills/many-skill/f" + i + ".txt";
            entries.add(fileEntry(path, 1L));
            when(files.readFile(path)).thenReturn("x");
        }
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(skillDir));
        when(files.listDirectory("/workspace/skills/many-skill")).thenReturn(entries);
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        assertEquals(0, store.size(), "文件数超上限必须整体跳过（不写 SKILL.md / 资源文件的半份）");
    }

    /** 目录层级超上限（MAX_SKILL_DEPTH=5）→ 只收前 5 层，不越界 */
    @Test
    void syncBackShouldStopAtDepthLimit() throws Exception {
        var store = new InMemoryStore();
        var files = mock(Filesystem.class);
        var root = "/workspace/skills/deep-skill";
        var rootEntries = new java.util.ArrayList<EntryInfo>();
        rootEntries.add(fileEntry(root + "/SKILL.md", 6L));
        rootEntries.add(dirEntry(root + "/d1"));
        var rootDirEntry = dirEntry(root);
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(rootDirEntry));
        when(files.listDirectory(root)).thenReturn(rootEntries);
        when(files.readFile(root + "/SKILL.md")).thenReturn("# deep");
        // 嵌套 6 层（d1/d2/.../d6），每层放一个 levelN.txt
        var nested = root;
        for (var i = 1; i <= 6; i++) {
            nested = nested + "/d" + i;
            var entries = new java.util.ArrayList<EntryInfo>();
            if (i < 6) {
                entries.add(dirEntry(nested + "/d" + (i + 1)));
            }
            entries.add(fileEntry(nested + "/level" + i + ".txt", 2L));
            when(files.listDirectory(nested)).thenReturn(entries);
            when(files.readFile(nested + "/level" + i + ".txt")).thenReturn("L" + i);
        }
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        var reader = new WorkspaceReader(distributedStore(store), AGENT);
        assertEquals("L5", reader.readUserSkillFile(USER, "deep-skill", "d1/d2/d3/d4/d5/level5.txt"),
            "第 5 层仍在上限内，必须收集");
        assertNull(reader.readUserSkillFile(USER, "deep-skill", "d1/d2/d3/d4/d5/d6/level6.txt"),
            "超过深度上限的层级不得收集");
    }

    /** 非法技能目录名（进入 KV key /{name}/{rel} 前的唯一屏障）→ 整个技能不落库 */
    @Test
    void syncBackShouldSkipIllegalSkillDirNames() throws Exception {
        var store = new InMemoryStore();
        var files = mock(Filesystem.class);
        // 元数据过滤（. / _ 开头）拦不到、但 isValidSkillName 必须拒的名字
        var badNames = List.of("a\\b", "bad\u001Fname", "x".repeat(129));
        var dirs = new java.util.ArrayList<EntryInfo>();
        for (var i = 0; i < badNames.size(); i++) {
            var dir = "/workspace/skills/" + badNames.get(i);
            dirs.add(dirEntry(dir));
            var skillMdEntry = fileEntry(dir + "/SKILL.md", 3L);
            when(files.listDirectory(dir)).thenReturn(List.of(skillMdEntry));
            when(files.readFile(dir + "/SKILL.md")).thenReturn("# bad-" + i);
        }
        when(files.listDirectory("/workspace/skills")).thenReturn(dirs);
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        assertEquals(0, store.size(), "非法技能名不得进入 KV key；整轮无落库");
    }

    /** 增量镜像不变量：沙箱里不存在的 L4 技能不得被差集删除（用户覆盖不能丢） */
    @Test
    void syncBackShouldNotDeleteKvSkillAbsentFromSandbox() throws Exception {
        var store = new InMemoryStore();
        var reader = new WorkspaceReader(distributedStore(store), AGENT);
        assertTrue(reader.writeUserSkillFile(USER, "kept-skill", "SKILL.md", "# kept"));
        var files = mock(Filesystem.class);
        var skillDir = dirEntry("/workspace/skills/other-skill");
        var skillMdEntry = fileEntry("/workspace/skills/other-skill/SKILL.md", 5L);
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(skillDir));
        when(files.listDirectory("/workspace/skills/other-skill")).thenReturn(List.of(skillMdEntry));
        when(files.readFile("/workspace/skills/other-skill/SKILL.md")).thenReturn("# other");
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        assertEquals("# kept", reader.readUserSkillFile(USER, "kept-skill", "SKILL.md"),
            "沙箱内不存在的 L4 技能必须保留（不做删除对账）");
        assertEquals("# other", reader.readUserSkillFile(USER, "other-skill", "SKILL.md"));
    }

    /** 显式删除标记（tombstone）：管理面删除后回写必须跳过同名技能，不能把覆盖“复活” */
    @Test
    void syncBackShouldSkipSkillWithDeletionMarker() throws Exception {
        var store = new InMemoryStore();
        var reader = new WorkspaceReader(distributedStore(store), AGENT);
        assertTrue(reader.writeUserSkillFile(USER, "tomb-skill", "SKILL.md", "# deleted by admin"));
        assertTrue(reader.markUserSkillDeleted(USER, "tomb-skill"));

        var files = mock(Filesystem.class);
        var skillDir = dirEntry("/workspace/skills/tomb-skill");
        var skillMdEntry = fileEntry("/workspace/skills/tomb-skill/SKILL.md", 17L);
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(skillDir));
        when(files.listDirectory("/workspace/skills/tomb-skill")).thenReturn(List.of(skillMdEntry));
        when(files.readFile("/workspace/skills/tomb-skill/SKILL.md")).thenReturn("# resurrected?!");
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        assertEquals("# deleted by admin", reader.readUserSkillFile(USER, "tomb-skill", "SKILL.md"),
            "带删除标记的技能不得被容器内副本写回");
    }

    /**
     * 管理面写入栅栏（admin-override）：管理面 PUT/下发后，同代容器内旧副本在下次 call 结束时
     * 不得把管理面内容改回容器版本（与 tombstone 对称的写侧仲裁）。
     */
    @Test
    void syncBackShouldSkipSkillWithAdminOverrideFence() throws Exception {
        var store = new InMemoryStore();
        var reader = new WorkspaceReader(distributedStore(store), AGENT);
        assertTrue(reader.writeUserSkillFile(USER, "fenced-skill", "SKILL.md", "# admin override"));
        assertTrue(reader.markUserSkillAdminOverride(USER, "fenced-skill"));

        var files = mock(Filesystem.class);
        var skillDir = dirEntry("/workspace/skills/fenced-skill");
        var skillMdEntry = fileEntry("/workspace/skills/fenced-skill/SKILL.md", 19L);
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(skillDir));
        when(files.listDirectory("/workspace/skills/fenced-skill")).thenReturn(List.of(skillMdEntry));
        when(files.readFile("/workspace/skills/fenced-skill/SKILL.md")).thenReturn("# stale container copy");
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        assertEquals("# admin override", reader.readUserSkillFile(USER, "fenced-skill", "SKILL.md"),
            "带管理面写入栅栏的技能不得被容器内旧副本覆盖");
        // 栅栏本身也不能被回写污染（元数据键）
        assertNotNull(reader.readUserSkillFile(USER, "fenced-skill", ".admin-override"));
    }

    /** 栅栏命中时连读都不该发生（回写提前返回，不做无谓的文件收集） */
    @Test
    void syncOneSkillShouldNotReadFilesWhenAdminOverrideFencePresent() throws Exception {
        var reader = mock(WorkspaceReader.class);
        when(reader.isUserSkillDeleted(USER, "fenced-skill")).thenReturn(false);
        when(reader.isUserSkillAdminOverride(USER, "fenced-skill")).thenReturn(true);
        var files = mock(Filesystem.class);
        var skillDir = dirEntry("/workspace/skills/fenced-skill");
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(skillDir));
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        new WorkspaceSyncService(reader).syncBack(USER, osb);

        verify(reader, never()).writeUserSkillFile(anyString(), anyString(), anyString(), anyString());
        verify(files, never()).listDirectory("/workspace/skills/fenced-skill");
        verify(files, never()).readFile("/workspace/skills/fenced-skill/SKILL.md");
    }

    /** 删除标记优先于写入栅栏（管理面删除后即使栅栏残留，回写也必须跳过） */
    @Test
    void syncBackShouldPreferDeletionMarkerOverAdminOverride() throws Exception {
        var store = new InMemoryStore();
        var reader = new WorkspaceReader(distributedStore(store), AGENT);
        assertTrue(reader.writeUserSkillFile(USER, "both-skill", "SKILL.md", "# admin content"));
        assertTrue(reader.markUserSkillAdminOverride(USER, "both-skill"));
        assertTrue(reader.markUserSkillDeleted(USER, "both-skill"));

        var files = mock(Filesystem.class);
        var skillDir = dirEntry("/workspace/skills/both-skill");
        var skillMdEntry = fileEntry("/workspace/skills/both-skill/SKILL.md", 8L);
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(skillDir));
        when(files.listDirectory("/workspace/skills/both-skill")).thenReturn(List.of(skillMdEntry));
        when(files.readFile("/workspace/skills/both-skill/SKILL.md")).thenReturn("# stale");
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        service(store, AGENT).syncBack(USER, osb);

        assertEquals("# admin content", reader.readUserSkillFile(USER, "both-skill", "SKILL.md"),
            "带删除标记的技能不得被容器内副本写回");
    }

    /** SKILL.md 回写失败 → 立即中止，不写其余资源文件（否则 KV 留下管理面看不到也删不掉的孤儿行） */
    @Test
    void syncOneSkillShouldAbortWhenSkillMdWriteFails() throws Exception {
        var reader = mock(WorkspaceReader.class);
        when(reader.isUserSkillDeleted(USER, "fail-skill")).thenReturn(false);
        when(reader.writeUserSkillFile(USER, "fail-skill", "SKILL.md", "# fail")).thenReturn(false);
        var files = mock(Filesystem.class);
        var skillDir = dirEntry("/workspace/skills/fail-skill");
        var skillMdEntry = fileEntry("/workspace/skills/fail-skill/SKILL.md", 6L);
        var scriptsDir = dirEntry("/workspace/skills/fail-skill/scripts");
        var runShEntry = fileEntry("/workspace/skills/fail-skill/scripts/run.sh", 3L);
        when(files.listDirectory("/workspace/skills")).thenReturn(List.of(skillDir));
        when(files.listDirectory("/workspace/skills/fail-skill"))
            .thenReturn(List.of(skillMdEntry, scriptsDir));
        when(files.listDirectory("/workspace/skills/fail-skill/scripts")).thenReturn(List.of(runShEntry));
        when(files.readFile("/workspace/skills/fail-skill/SKILL.md")).thenReturn("# fail");
        when(files.readFile("/workspace/skills/fail-skill/scripts/run.sh")).thenReturn("run");
        var osb = mock(Sandbox.class);
        when(osb.files()).thenReturn(files);

        new WorkspaceSyncService(reader).syncBack(USER, osb);

        verify(reader, never()).writeUserSkillFile(USER, "fail-skill", "scripts/run.sh", "run");
    }
}
