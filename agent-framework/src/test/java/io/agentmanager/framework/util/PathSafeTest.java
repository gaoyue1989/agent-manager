package io.agentmanager.framework.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * PathSafe.sanitize 单测：
 * 验证 Windows 不兼容字符、路径遍历、空白字符、长字符串等场景。
 */
class PathSafeTest {

    private static String invoke(String raw) {
        return PathSafe.sanitize(raw);
    }

    @Test
    void nullReturnsFallback() {
        assertEquals("unknown", invoke(null));
    }

    @Test
    void blankReturnsFallback() {
        assertEquals("unknown", invoke(""));
        assertEquals("unknown", invoke("   "));
    }

    @Test
    void normalUserIdUnchanged() {
        assertEquals("alice", invoke("alice"));
        assertEquals("user-123", invoke("user-123"));
    }

    @Test
    void debugUserWithTimestamp() {
        String result = invoke("debug-user_m1a2b3c");
        assertEquals("debug-user_m1a2b3c", result);
    }

    @Test
    void debugUserWithColonReplaced() {
        // debug-user:xxx → debug-user_xxx  （关键场景：旧浏览器缓存 / Channel peer）
        assertEquals("debug-user_m1a2b3c", invoke("debug-user:m1a2b3c"));
    }

    @Test
    void windowsColonReplaced() {
        assertEquals("C_", invoke("C:"));
        // C:\path → C_ _path (colon and backslash both replaced)
        assertEquals("C__path", invoke("C:\\path"));
    }

    @Test
    void pathTraversalRemoved() {
        String result = invoke("../../etc/passwd");
        assertFalse(result.contains(".."), "路径遍历应被清理: " + result);
    }

    @Test
    void illegalCharsReplaced() {
        assertEquals("a_b_c_d_e_f_g", invoke("a*b?c\"d<e>f|g"));
    }

    @Test
    void controlCharsReplaced() {
        assertEquals("a_b", invoke("a\u001Fb"));
    }

    @Test
    void multipleSpacesCollapsed() {
        assertEquals("a b c", invoke("a   b   c"));
    }

    @Test
    void leadingTrailingSpacesStripped() {
        assertEquals("hello", invoke("  hello  "));
    }

    @Test
    void longStringTruncated() {
        String longStr = "x".repeat(200);
        String result = invoke(longStr);
        assertEquals(64, result.length(), "超过 64 字符应截断");
    }

    @Test
    void allIllegalProducesFallback() {
        assertEquals("unknown", invoke("***???"));
    }

    @Test
    void windowsBackslashReplaced() {
        assertEquals("a_b_c", invoke("a\\b\\c"));
    }
}
