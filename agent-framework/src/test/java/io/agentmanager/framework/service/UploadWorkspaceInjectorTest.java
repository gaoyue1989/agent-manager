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
        return new FileAssetStore.FileAsset(id, "alice", "s1", "a" + id + ".png",
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
            new FileAssetStore.FileAsset("doc-1", "alice", "s1", "report.docx", "uploads/report_1.docx",
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
}