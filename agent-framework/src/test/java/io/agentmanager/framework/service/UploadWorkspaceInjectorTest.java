package io.agentmanager.framework.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.service.storage.FileStorage;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UploadWorkspaceInjector 单测（UT-10/11 + 消息构造三分支）：KV 注入、workspace_path 回写、
 * ImageBlock 内联、文档路径提示、图片预算降级、读取失败降级。
 */
class UploadWorkspaceInjectorTest {

    private FileStorage fileStorage;
    private FileAssetStore fileAssetStore;
    private UploadWorkspaceInjector injector;

    private final byte[] PNG_BYTES = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @BeforeEach
    void setUp() {
        fileStorage = mock(FileStorage.class);
        fileAssetStore = mock(FileAssetStore.class);
        injector = new UploadWorkspaceInjector(fileStorage, fileAssetStore,
            io.agentmanager.framework.controller.FileControllerTest.testProps());
    }

    private FileAssetStore.FileAsset asset(String id, String mime, long size, String wsPath) {
        return new FileAssetStore.FileAsset(id, "alice", "s1", null, "a" + id + ".png",
            wsPath, mime, size, "local", "upload/k/" + id, "upload", "injected", LocalDateTime.now());
    }

    @Test
    void buildContentShouldInlineSmallImage() throws Exception {
        when(fileAssetStore.get("img-1")).thenReturn(Optional.of(asset("img-1", "image/png", PNG_BYTES.length, null)));
        when(fileStorage.read("upload/k/img-1")).thenReturn(new java.io.ByteArrayInputStream(PNG_BYTES));

        var blocks = injector.buildContentBlocks(List.of("img-1"), "看看", "alice");
        assertEquals(2, blocks.size(), "文本 + 图片");
        assertInstanceOf(ImageBlock.class, blocks.get(1), "图片应内联为 ImageBlock");
        var img = (ImageBlock) blocks.get(1);
        var src = (Base64Source) img.getSource();
        assertEquals("image/png", src.getMediaType());
        assertEquals(java.util.Base64.getEncoder().encodeToString(PNG_BYTES), src.getData());
    }

    @Test
    void buildContentShouldDegradeOversizeImageToPathHint() throws Exception {
        when(fileAssetStore.get("big-1")).thenReturn(Optional.of(asset("big-1", "image/png", 6L * 1024 * 1024, null)));
        var blocks = injector.buildContentBlocks(List.of("big-1"), "go", "alice");
        assertEquals(2, blocks.size());
        assertInstanceOf(TextBlock.class, blocks.get(1));
        assertTrue(((TextBlock) blocks.get(1)).getText().contains("uploads/a big-1.png")
            || ((TextBlock) blocks.get(1)).getText().contains("uploads/"), "路径提示应出现: "
            + ((TextBlock) blocks.get(1)).getText());
    }

    @Test
    void buildContentShouldDegradeDocumentToPathHint() {
        when(fileAssetStore.get("doc-1")).thenReturn(Optional.of(
            new FileAssetStore.FileAsset("doc-1", "alice", "s1", null, "report.docx", "uploads/report_1.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 2048,
                "local", "upload/k/doc-1", "upload", "injected", LocalDateTime.now())));
        var blocks = injector.buildContentBlocks(List.of("doc-1"), "go", "alice");
        assertEquals(2, blocks.size());
        assertInstanceOf(TextBlock.class, blocks.get(1));
        // 使用 workspace_path（含唯一化后缀），不拼接原始文件名
        assertTrue(((TextBlock) blocks.get(1)).getText().contains("uploads/report_1.docx"),
            "路径提示应使用 workspace_path");
    }

    @Test
    void buildContentShouldHonorTotalImageBudget() throws Exception {
        // 4 张 4MB 图片：单文件 ≤5MB（通过），总 16MB > 15MB 预算 → 前 3 张内联、第 4 张降级
        long single = 4L * 1024 * 1024;
        when(fileAssetStore.get("m1")).thenReturn(Optional.of(asset("m1", "image/png", single, null)));
        when(fileAssetStore.get("m2")).thenReturn(Optional.of(asset("m2", "image/png", single, null)));
        when(fileAssetStore.get("m3")).thenReturn(Optional.of(asset("m3", "image/png", single, null)));
        when(fileAssetStore.get("m4")).thenReturn(Optional.of(asset("m4", "image/png", single, null)));
        when(fileStorage.read(any(String.class))).thenReturn(new java.io.ByteArrayInputStream(PNG_BYTES));

        var blocks = injector.buildContentBlocks(List.of("m1", "m2", "m3", "m4"), null, "alice");
        long images = blocks.stream().filter(b -> b instanceof ImageBlock).count();
        assertEquals(3, images, "总预算内仅 3 张内联");
        assertTrue(blocks.stream().filter(b -> b instanceof TextBlock)
            .anyMatch(t -> ((TextBlock) t).getText().contains("uploads/")), "第 4 张降级路径提示");
    }

    @Test
    void buildContentShouldDegradeOnStorageReadFailure() throws Exception {
        when(fileAssetStore.get("img-x")).thenReturn(Optional.of(asset("img-x", "image/png", 100, null)));
        org.mockito.Mockito.doThrow(new java.io.IOException("s3 down"))
            .when(fileStorage).read("upload/k/img-x");

        var blocks = injector.buildContentBlocks(List.of("img-x"), "go", "alice");
        // 读取失败 → 降级路径提示，不抛错
        assertEquals(2, blocks.size());
        assertInstanceOf(TextBlock.class, blocks.get(1));
    }

    @Test
    void buildContentShouldSkipMissingAsset() throws Exception {
        when(fileAssetStore.get("gone")).thenReturn(Optional.empty());
        var blocks = injector.buildContentBlocks(List.of("gone"), "go", "alice");
        assertEquals(1, blocks.size(), "缺失文件跳过，仅保留文本");
    }

    @Test
    void pathHintCarriesSectionMarkerAndStripsCleanly() {
        when(fileAssetStore.get("doc-1")).thenReturn(Optional.of(
            new FileAssetStore.FileAsset("doc-1", "alice", "s1", null, "report.docx", "uploads/report_1.docx",
                "application/msword", 2048, "local", "upload/k/doc-1", "upload", "injected", LocalDateTime.now())));
        var blocks = injector.buildContentBlocks(List.of("doc-1"), "go", "alice");

        var hint = ((TextBlock) blocks.get(1)).getText();
        assertTrue(hint.startsWith(UploadWorkspaceInjector.UPLOAD_SECTION_MARKER),
            "提示块须带剥离标记（与 stripUploadHints 共用常量）");
        // 拼接后的用户可见文本：用户文本 + 换行 + 标记区块 → 剥离后只剩用户文本
        assertEquals("go", UploadWorkspaceInjector.stripUploadHints("go\n" + hint));
        assertEquals("", UploadWorkspaceInjector.stripUploadHints(hint), "纯上传（无用户文本）剥成空");
        assertEquals("原样", UploadWorkspaceInjector.stripUploadHints("原样"), "无标记时原样返回");
        assertEquals("", UploadWorkspaceInjector.stripUploadHints(null));
    }

    @Test
    void pathHintCarriesShellQuotingGuidance() {
        // 平台给所有模型的默认指引：read_file 路径原样可用（免 shell 转义）；
        // shell 引用含特殊字符的路径须双引号包裹（含引号/空格的文件名 shell 裸引用必语法错）
        when(fileAssetStore.get("doc-1")).thenReturn(Optional.of(
            new FileAssetStore.FileAsset("doc-1", "alice", "s1", null, "it's-a-test.txt", "uploads/it's-a-test.txt",
                "application/msword", 2048, "local", "upload/k/doc-1", "upload", "injected", LocalDateTime.now())));
        var blocks = injector.buildContentBlocks(List.of("doc-1"), "go", "alice");

        var hint = ((TextBlock) blocks.get(1)).getText();
        assertTrue(hint.contains("双引号"), "提示须含 shell 双引号包裹指引: " + hint);
        assertTrue(hint.contains("read_file"), "提示须引导优先用 read_file");
    }

    // ---------- bind-at-use 绑定 + 路径预分配（assignWorkspacePaths，issue #345） ----------

    /** 上传行工厂：userKey/sessionId/workspacePath/status 可变 */
    private FileAssetStore.FileAsset uploadRow(String id, String userKey, String sessionId,
                                               String wsPath, String status) {
        return new FileAssetStore.FileAsset(id, userKey, sessionId, null, "note.txt", wsPath,
            "text/plain", 1, "local", "upload/k/" + id, "upload", status, LocalDateTime.now());
    }

    @Test
    void assignShouldBindUnboundRowsAndAllocateAgainstTaken() {
        when(fileAssetStore.get("f1")).thenReturn(Optional.of(uploadRow("f1", "alice", null, null, "pending")));
        when(fileAssetStore.get("f2")).thenReturn(Optional.of(uploadRow("f2", "alice", null, null, "pending")));
        when(fileAssetStore.listWorkspacePaths("s1")).thenReturn(List.of("uploads/other.txt"));
        when(fileAssetStore.bindSession(any(), any())).thenReturn(true);

        var bound = injector.assignWorkspacePaths(List.of("f1", "f2"), "alice", "s1");

        assertEquals(List.of("f1", "f2"), bound);
        verify(fileAssetStore).bindSession("f1", "s1");
        verify(fileAssetStore).bindSession("f2", "s1");
        verify(fileAssetStore).updateWorkspacePath("f1", "uploads/note.txt");
        verify(fileAssetStore).updateWorkspacePath("f2", "uploads/note_1.txt");
    }

    @Test
    void assignShouldBeIdempotentForAlreadyAssignedRows() {
        when(fileAssetStore.get("f2")).thenReturn(Optional.of(
            uploadRow("f2", "alice", "s1", "uploads/note.txt", "pending")));

        var bound = injector.assignWorkspacePaths(List.of("f2"), "alice", "s1");

        assertEquals(List.of("f2"), bound);
        verify(fileAssetStore, never()).bindSession(any(), any());
        verify(fileAssetStore, never()).updateWorkspacePath(any(), any());
    }

    @Test
    void assignShouldSkipForeignSessionRow() {
        when(fileAssetStore.get("f1")).thenReturn(Optional.of(
            uploadRow("f1", "alice", "s-other", "uploads/note.txt", "pending")));

        var bound = injector.assignWorkspacePaths(List.of("f1"), "alice", "s1");

        assertTrue(bound.isEmpty(), "已绑定异 session 的行跳过（所有权=首引用会话）");
        verify(fileAssetStore, never()).bindSession(any(), any());
        verify(fileAssetStore, never()).updateWorkspacePath(any(), any());
    }

    @Test
    void assignShouldSkipForeignUserRow() {
        when(fileAssetStore.get("f1")).thenReturn(Optional.of(uploadRow("f1", "bob", null, null, "pending")));

        var bound = injector.assignWorkspacePaths(List.of("f1"), "alice", "s1");

        assertTrue(bound.isEmpty(), "他人上传文件不可引用注入");
        verify(fileAssetStore, never()).bindSession(any(), any());
    }

    @Test
    void assignShouldSkipWhenBindRaceLost() {
        when(fileAssetStore.get("f1")).thenReturn(Optional.of(uploadRow("f1", "alice", null, null, "pending")));
        when(fileAssetStore.bindSession("f1", "s1")).thenReturn(false);

        var bound = injector.assignWorkspacePaths(List.of("f1"), "alice", "s1");

        assertTrue(bound.isEmpty(), "并发抢绑失败（他 session 先绑定）→ 跳过，不分配路径");
        verify(fileAssetStore, never()).updateWorkspacePath(any(), any());
    }

    @Test
    void assignShouldFailSoftForMissingAsset() {
        when(fileAssetStore.get("gone")).thenReturn(Optional.empty());

        assertTrue(injector.assignWorkspacePaths(List.of("gone"), "alice", "s1").isEmpty());
    }

    // ---------- 非沙箱注入（injectToWorkspace）：injected 幂等跳过 + 磁盘撞名重分配 ----------

    @Test
    void injectToWorkspaceShouldSkipAlreadyInjectedRow(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        // 幂等跳过的前提是工作区文件仍在磁盘（issue #355/#354 语境区分：这里是 #354）——
        // workspaceBase 用 @TempDir 并预写文件，模拟「已注入且磁盘健在」
        var base = io.agentmanager.framework.controller.FileControllerTest.testProps();
        var props = new AgentManagerProperties(base.llm(), base.server(), base.checkpoint(),
            "/config", tmp.toString(), base.cleanup(), base.file(), base.sse(), base.harness());
        var localInjector = new UploadWorkspaceInjector(fileStorage, fileAssetStore, props);
        when(fileAssetStore.get("f1")).thenReturn(Optional.of(
            uploadRow("f1", "alice", "s1", "uploads/note.txt", "injected")));
        java.nio.file.Files.createDirectories(localInjector.workspaceRoot("s1").resolve("uploads"));
        java.nio.file.Files.writeString(
            localInjector.workspaceRoot("s1").resolve("uploads/note.txt"), "materialized");

        assertEquals("uploads/note.txt", localInjector.injectToWorkspace("f1", "s1"));
        verify(fileStorage, never()).read(any());
    }

    @Test
    void injectToWorkspaceShouldRematerializeWhenWorkspaceFileMissing(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        // issue #354：injected 行 + 工作区文件丢失（oaf-agent 工作区卷 emptyDir，Pod 重建
        // 即清空）——只看 DB 状态的幂等跳过会让提示路径永久指向已消失文件。磁盘缺失必须
        // 落回写盘路径重物化（S3 后端字节仍在，下次引用即自愈）
        var base = io.agentmanager.framework.controller.FileControllerTest.testProps();
        var props = new AgentManagerProperties(base.llm(), base.server(), base.checkpoint(),
            "/config", tmp.toString(), base.cleanup(), base.file(), base.sse(), base.harness());
        var localInjector = new UploadWorkspaceInjector(fileStorage, fileAssetStore, props);
        when(fileAssetStore.get("f1")).thenReturn(Optional.of(
            uploadRow("f1", "alice", "s1", "uploads/note.txt", "injected")));
        when(fileStorage.read("upload/k/f1")).thenReturn(
            new java.io.ByteArrayInputStream("content".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        var wsPath = localInjector.injectToWorkspace("f1", "s1");

        assertEquals("uploads/note.txt", wsPath, "磁盘缺失 → 重物化而非早退");
        assertEquals("content",
            java.nio.file.Files.readString(localInjector.workspaceRoot("s1").resolve("uploads/note.txt")),
            "工作区文件必须重建");
        verify(fileAssetStore).markInjected(java.util.List.of("f1"));
    }

    @Test
    void injectToWorkspaceShouldRematerializeInjectedRowWithNullWorkspacePath(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        // issue #431：存量 injected 行 workspace_path 可为 NULL（#346 之前的行，写盘分支
        // :83 的 null 兜底同证）——磁盘存在性检查直接 resolve(null) 会 NPE。null = 未落盘，
        // 必须按重物化分支走
        var base = io.agentmanager.framework.controller.FileControllerTest.testProps();
        var props = new AgentManagerProperties(base.llm(), base.server(), base.checkpoint(),
            "/config", tmp.toString(), base.cleanup(), base.file(), base.sse(), base.harness());
        var localInjector = new UploadWorkspaceInjector(fileStorage, fileAssetStore, props);
        when(fileAssetStore.get("f1")).thenReturn(Optional.of(
            uploadRow("f1", "alice", "s1", null, "injected")));
        when(fileStorage.read("upload/k/f1")).thenReturn(
            new java.io.ByteArrayInputStream("content".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(fileAssetStore.listWorkspacePaths("s1")).thenReturn(List.of());

        var wsPath = localInjector.injectToWorkspace("f1", "s1");

        assertEquals("uploads/note.txt", wsPath, "null workspace_path 按未落盘重物化");
        verify(fileAssetStore).updateWorkspacePath("f1", "uploads/note.txt");
        verify(fileAssetStore).markInjected(java.util.List.of("f1"));
    }

    @Test
    void injectToWorkspaceShouldRenameWhenTargetExistsOnDisk(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        // 工作区基目录用 @TempDir（testProps 默认 /config，跨运行残留会让撞名断言漂移）
        var base = io.agentmanager.framework.controller.FileControllerTest.testProps();
        var props = new AgentManagerProperties(base.llm(), base.server(), base.checkpoint(),
            "/config", tmp.toString(), base.cleanup(), base.file(), base.sse(), base.harness());
        var localInjector = new UploadWorkspaceInjector(fileStorage, fileAssetStore, props);
        var row = uploadRow("f1", "alice", "s1", "uploads/note.txt", "pending");
        when(fileAssetStore.get("f1")).thenReturn(Optional.of(row));
        when(fileStorage.read("upload/k/f1")).thenReturn(
            new java.io.ByteArrayInputStream("content".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(fileAssetStore.listWorkspacePaths("s1")).thenReturn(List.of("uploads/note.txt"));
        // 预留路径已被 agent 手写同名文件占用（防静默覆盖）
        java.nio.file.Files.createDirectories(localInjector.workspaceRoot("s1").resolve("uploads"));
        java.nio.file.Files.writeString(localInjector.workspaceRoot("s1").resolve("uploads/note.txt"), "agent wrote");

        var wsPath = localInjector.injectToWorkspace("f1", "s1");

        assertEquals("uploads/note_1.txt", wsPath, "目标已被占用 → 重分配 _1（改名可见于后续提示构造）");
        verify(fileAssetStore).updateWorkspacePath("f1", "uploads/note_1.txt");
        verify(fileAssetStore).markInjected(List.of("f1"));
        assertEquals("content",
            java.nio.file.Files.readString(localInjector.workspaceRoot("s1").resolve("uploads/note_1.txt")));
        assertEquals("agent wrote",
            java.nio.file.Files.readString(localInjector.workspaceRoot("s1").resolve("uploads/note.txt")),
            "agent 手写文件不被覆盖");
    }
}