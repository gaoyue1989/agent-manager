package io.agentmanager.framework.service.storage;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S3FileStorage.writeBounded 单测（issue #345 P3 交付通道 OOM 加固）：未知长度流先 spool
 * 落盘计数——越限抛 StorageOverflowException 且无 PUT、spool 无论成败必删、成功后按精确
 * size 单 PUT。S3FileStorageIT 依赖真实 S3（S3_IT 环境变量）默认跳过，本测试以
 * mockConstruction(MinioClient) 覆盖构造器 bucketExists 后在进程内验证 writeBounded 契约。
 */
class S3FileStorageTest {

    @TempDir
    Path spoolDir;

    private AgentManagerProperties props() {
        return new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120, true, 0, "", null),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass", "test"),
            "/config",
            "",
            new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20, "image/*,text/plain", 5, 15, 50, true, 7,
                "s3", "/data/files", "http://localhost:9000", "ak-test", "sk-test", "agent-files", "",
                spoolDir.toString()),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            AgentManagerProperties.HarnessConfig.defaults()
        );
    }

    @Test
    void writeBoundedShouldAbortOnOverflowWithoutPutAndWithoutSpoolResidue() throws Exception {
        try (var mocked = mockConstruction(MinioClient.class,
                (mock, ctx) -> when(mock.bucketExists(any(BucketExistsArgs.class))).thenReturn(true))) {
            var storage = new S3FileStorage(props());
            var client = mocked.constructed().get(0);

            var content = new byte[2048];
            Arrays.fill(content, (byte) 7);
            var e = assertThrows(StorageOverflowException.class,
                () -> storage.writeBounded("generated/over.bin", new ByteArrayInputStream(content), 1024, null),
                "越限抛 StorageOverflowException");
            assertEquals(1024, e.maxBytes());
            assertTrue(e.actualBytes() > 1024, "actualBytes 应反映已读字节");

            // 越限即中止：无 PUT 产生 + spool 目录无残留（堆外计数文件不留尸）
            verify(client, never()).putObject(any(PutObjectArgs.class));
            try (var files = Files.list(spoolDir)) {
                assertEquals(0, files.count(), "spool 无残留");
            }
        }
    }

    @Test
    void writeBoundedShouldSpoolThenSinglePutExactSizeAndCleanup() throws Exception {
        try (var mocked = mockConstruction(MinioClient.class,
                (mock, ctx) -> when(mock.bucketExists(any(BucketExistsArgs.class))).thenReturn(true))) {
            var storage = new S3FileStorage(props());
            var client = mocked.constructed().get(0);
            // putObject mock 侧读尽入参流（此时 spool 仍存在，finally 在调用返回后才清理），
            // 验证单 PUT 承载的正是完整原文
            var putContent = new byte[][]{null};
            doAnswer(inv -> {
                var args = inv.getArgument(0, PutObjectArgs.class);
                putContent[0] = args.stream().readAllBytes();
                return null;
            }).when(client).putObject(any(PutObjectArgs.class));

            var content = "bounded-content".getBytes(StandardCharsets.UTF_8);
            long written = storage.writeBounded("generated/x.bin",
                new ByteArrayInputStream(content), 1024, "text/plain");

            assertEquals(content.length, written, "返回落盘字节数");
            var captor = ArgumentCaptor.forClass(PutObjectArgs.class);
            verify(client, times(1)).putObject(captor.capture());
            var args = captor.getValue();
            assertEquals("generated/x.bin", args.object());
            assertEquals(content.length, args.objectSize(), "单 PUT 按精确 size（≤cap）");
            assertEquals("text/plain", args.contentType());
            assertArrayEquals(content, putContent[0], "PUT 内容与源流逐位一致");
            try (var files = Files.list(spoolDir)) {
                assertEquals(0, files.count(), "成功后 spool 已删");
            }
        }
    }

    @Test
    void writeBoundedShouldSpoolIntoFlatFileForKeyWithSlashes() throws Exception {
        try (var mocked = mockConstruction(MinioClient.class,
                (mock, ctx) -> when(mock.bucketExists(any(BucketExistsArgs.class))).thenReturn(true))) {
            var storage = new S3FileStorage(props());
            var client = mocked.constructed().get(0);

            var content = "x".getBytes(StandardCharsets.UTF_8);
            assertEquals(1, storage.writeBounded("a/b/c.txt",
                new ByteArrayInputStream(content), 10, null));

            // spool 文件名 replace('/','_')：不得在 spoolDir 下建子目录（残留空目录即实现回归）
            assertTrue(Files.notExists(spoolDir.resolve("a")),
                "spool 文件必须平铺在 spoolDir 根，不得按 key 建子目录");
            verify(client, times(1)).putObject(any(PutObjectArgs.class));
            try (var files = Files.list(spoolDir)) {
                assertEquals(0, files.count(), "spool 无残留");
            }
        }
    }
}
