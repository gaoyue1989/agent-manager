package io.agentmanager.framework.util;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文件名字节预算单测：上传侧与 presentFile 产出侧共用的截断语义（issue #425/#499）。 */
class FileNameBudgetTest {

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void shouldReturnOriginalWithinBudget() {
        var name = "测".repeat(67) + ".txt"; // 201+4=205 字节，恰好预算内
        assertEquals(name, FileNameBudget.truncatePreservingExtension(name));
        assertEquals(FileNameBudget.MAX_FILE_NAME_BYTES, bytes(name));
    }

    @Test
    void shouldTruncateStemPreservingExtension() {
        var capped = FileNameBudget.truncatePreservingExtension("测".repeat(70) + ".txt"); // 214 字节
        assertTrue(bytes(capped) <= FileNameBudget.MAX_FILE_NAME_BYTES,
            "预算 " + FileNameBudget.MAX_FILE_NAME_BYTES + " 内，实际 " + bytes(capped));
        assertTrue(capped.endsWith(".txt"), "保扩展名: " + capped);
        assertFalse(capped.contains("\uFFFD"), "无替换字符: " + capped);
    }

    @Test
    void shouldFallBackToCharBoundary() {
        // stem 预算 201 字节落在"测"（3 字节）的第 2 字节上：须回退到字符起点
        var capped = FileNameBudget.truncatePreservingExtension("a".repeat(200) + "测".repeat(10) + ".png");
        var b = capped.getBytes(StandardCharsets.UTF_8);
        assertTrue(b.length <= FileNameBudget.MAX_FILE_NAME_BYTES, "预算内: " + b.length);
        assertTrue(capped.endsWith(".png"), "扩展名保留: " + capped);
        assertEquals(capped, new String(b, StandardCharsets.UTF_8), "可无损往返解码");
    }

    @Test
    void shouldTruncateNameWithoutExtension() {
        var capped = FileNameBudget.truncatePreservingExtension("测".repeat(80)); // 240 字节无扩展名
        assertTrue(bytes(capped) <= FileNameBudget.MAX_FILE_NAME_BYTES,
            "预算内，实际 " + bytes(capped));
        assertFalse(capped.contains("\uFFFD"), "无替换字符");
    }

    @Test
    void shouldCapPathologicalLongExtension() {
        // 超长"扩展名"（无更靠前的 dot）：ext 先截到预算内，stem 不得产出负预算越界
        var capped = FileNameBudget.truncatePreservingExtension("a." + "测".repeat(100));
        assertTrue(bytes(capped) <= FileNameBudget.MAX_FILE_NAME_BYTES,
            "预算内，实际 " + bytes(capped));
        assertFalse(capped.contains("\uFFFD"), "无替换字符");
    }

    @Test
    void truncateUtf8BytesShouldRespectBudgetAndBoundary() {
        assertEquals("abc", FileNameBudget.truncateUtf8Bytes("abcdef", 3));
        assertEquals("a测", FileNameBudget.truncateUtf8Bytes("a测b", 4)); // 恰为完整字符边界，不回退
        assertEquals("a", FileNameBudget.truncateUtf8Bytes("a测b", 2)); // 预算落在"测"中间 → 回退
        assertEquals("完整", FileNameBudget.truncateUtf8Bytes("完整保留", 6));
        assertFalse(FileNameBudget.truncateUtf8Bytes("测".repeat(100), 205).contains("\uFFFD"));
    }
}
