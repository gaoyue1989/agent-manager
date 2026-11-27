package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SkillZipSupport 测试：技能名清洗、规范名解析与 shell 安全判定。
 */
class SkillZipSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void sanitizeShouldKeepChinesePunctuation() {
        assertEquals("公文检查（内部）", SkillZipSupport.sanitizeSkillName("公文检查（内部）"));
        assertEquals("合同、审批：流程", SkillZipSupport.sanitizeSkillName("合同、审批：流程"));
    }

    @Test
    void sanitizeShouldReplacePathSeparatorsAndControlChars() {
        assertEquals("a_b", SkillZipSupport.sanitizeSkillName("a/b"));
        assertEquals("a_b", SkillZipSupport.sanitizeSkillName("a\\b"));
        assertEquals("a_b", SkillZipSupport.sanitizeSkillName("a\u0000b"));
    }

    @Test
    void sanitizeShouldNotAllowLeadingDot() {
        assertEquals("_hidden", SkillZipSupport.sanitizeSkillName(".hidden"));
        assertEquals("_.", SkillZipSupport.sanitizeSkillName(".."));
    }

    @Test
    void canonicalNameShouldKeepChineseParentheses() throws IOException {
        var dir = writeSkill("公文检查_内部_", "公文检查（内部）");

        assertEquals("公文检查（内部）", SkillZipSupport.canonicalSkillName(dir));
    }

    @Test
    void canonicalNameShouldFallBackToDirNameForPathUnsafeFrontmatter() throws IOException {
        assertEquals("safe-dir", SkillZipSupport.canonicalSkillName(writeSkill("safe-dir", "../escape")));
        assertEquals("safe-dir", SkillZipSupport.canonicalSkillName(writeSkill("safe-dir", "a/b")));
        assertEquals("safe-dir", SkillZipSupport.canonicalSkillName(writeSkill("safe-dir", "..")));
        assertEquals("safe-dir", SkillZipSupport.canonicalSkillName(writeSkill("safe-dir", "")));
    }

    @Test
    void shellSafeShouldAcceptChinesePunctuation() {
        assertTrue(SkillZipSupport.isShellSafeSegment("公文检查（内部）"));
        assertTrue(SkillZipSupport.isShellSafeSegment("合同、审批：流程"));
        assertTrue(SkillZipSupport.isShellSafeSegment("v1.0"));
    }

    @Test
    void shellSafeShouldRejectCommandInjectionChars() {
        assertFalse(SkillZipSupport.isShellSafeSegment("a'b"));
        assertFalse(SkillZipSupport.isShellSafeSegment("a|b"));
        assertFalse(SkillZipSupport.isShellSafeSegment("a;b"));
        assertFalse(SkillZipSupport.isShellSafeSegment("a`b"));
        assertFalse(SkillZipSupport.isShellSafeSegment("a$(b)"));
        assertFalse(SkillZipSupport.isShellSafeSegment("a&b"));
        assertFalse(SkillZipSupport.isShellSafeSegment("a>b"));
        assertFalse(SkillZipSupport.isShellSafeSegment("a/b"));
        assertFalse(SkillZipSupport.isShellSafeSegment("."));
        assertFalse(SkillZipSupport.isShellSafeSegment("a..b"));
    }

    private Path writeSkill(String dirName, String frontmatterName) throws IOException {
        var dir = tempDir.resolve(dirName);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
            "---\nname: " + frontmatterName + "\ndescription: test\n---\n# " + frontmatterName + "\n",
            StandardCharsets.UTF_8);
        return dir;
    }
}
