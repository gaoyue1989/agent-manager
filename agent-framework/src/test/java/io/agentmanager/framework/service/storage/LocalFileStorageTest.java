package io.agentmanager.framework.service.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LocalFileStorage 单测（file-upload-download-plan UT-01~03）。
 */
class LocalFileStorageTest {

    @TempDir
    Path tempDir;

    private LocalFileStorage storage() {
        return new LocalFileStorage(tempDir);
    }

    @Test
    void writeShouldBeAtomicAndReadable() throws IOException {
        var s = storage();
        var content = "hello".getBytes(StandardCharsets.UTF_8);
        s.write("upload/202509/abc.txt", new ByteArrayInputStream(content), content.length, "text/plain");

        var target = tempDir.resolve("upload/202509/abc.txt");
        assertTrue(Files.exists(target), "write 后文件存在");
        assertArrayEquals(content, Files.readAllBytes(target), "内容一致");
        // 无 .tmp 残留（原子写）
        assertFalse(Files.exists(tempDir.resolve("upload/202509/abc.txt.tmp")), "tmp 残留应为 0");

        try (var in = s.read("upload/202509/abc.txt")) {
            assertArrayEquals(content, in.readAllBytes(), "read 回读一致");
        }
        assertTrue(s.exists("upload/202509/abc.txt"));
        assertFalse(s.exists("upload/202509/missing.txt"));
    }

    @Test
    void writeShouldRejectPathTraversal() throws IOException {
        var s = storage();
        var content = new byte[]{1};
        assertThrows(IOException.class,
            () -> s.write("../escape.txt", new ByteArrayInputStream(content), 1, "text/plain"),
            "../ 应被拒绝");
        assertThrows(IOException.class,
            () -> s.write("../../etc/passwd", new ByteArrayInputStream(content), 1, "text/plain"),
            "深层穿越应被拒绝");
        assertFalse(Files.exists(tempDir.resolve("escape.txt")), "根目录外无文件产生");
    }

    @Test
    void readMissingShouldThrow() {
        var s = storage();
        assertThrows(IOException.class, () -> s.read("nope/none.txt"));
    }

    @Test
    void deleteShouldBeIdempotent() throws IOException {
        var s = storage();
        var content = "x".getBytes(StandardCharsets.UTF_8);
        s.write("a/b.txt", new ByteArrayInputStream(content), 1, "text/plain");
        s.delete("a/b.txt");
        assertFalse(s.exists("a/b.txt"));
        s.delete("a/b.txt"); // 幂等
    }

    @Test
    void concurrentWriteSameKeyShouldNotCorrupt() throws Exception {
        var s = storage();
        var threads = new Thread[4];
        var errors = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < threads.length; i++) {
            final byte[] payload = String.valueOf(i).repeat(1024).getBytes(StandardCharsets.UTF_8);
            threads[i] = new Thread(() -> {
                try {
                    s.write("k/f.txt", new ByteArrayInputStream(payload), payload.length, "text/plain");
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
            threads[i].start();
        }
        for (var t : threads) t.join();
        assertEquals(0, errors.get(), "并发写不应抛错");
        try (var in = s.read("k/f.txt")) {
            var bytes = in.readAllBytes();
            assertEquals(1024, bytes.length, "内容完整（原子写：要么全旧要么全新）");
        }
    }

    @Test
    void blankKeyShouldBeRejected() {
        var s = storage();
        assertThrows(IOException.class, () -> s.write("  ", new ByteArrayInputStream(new byte[1]), 1, "text/plain"));
    }
}