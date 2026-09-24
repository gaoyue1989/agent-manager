package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentmanager.framework.config.AgentManagerProperties;

/**
 * SkillManageService 测试：zip 上传/解压、删除、启停状态管理。
 */
class SkillManageServiceTest {

    @TempDir
    Path tempDir;

    private Path skillsDir;
    private AgentManagerProperties props;

    @BeforeEach
    void setUp() throws IOException {
        skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120, true, 0),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass", "test"),
            tempDir.toString(),
            "",
            new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20, "image/*,text/plain,application/zip", 5, 15, 50, true, 7, "local", "", "", "", "", "agent-files", ""),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            AgentManagerProperties.HarnessConfig.defaults()
        );
    }

    private SkillManageService createService() {
        return new SkillManageService(props);
    }

    // ========== 上传测试 ==========

    @Test
    void uploadFlatZipShouldExtractCorrectly() throws IOException {
        // 扁平结构：zip 根目录包含 SKILL.md
        var zipBytes = createFlatZip("test-skill", "A test skill", "1.0.0");
        var service = createService();
        var name = service.uploadSkill(new ByteArrayInputStream(zipBytes), "test-skill.zip");

        assertEquals("test-skill", name);
        assertTrue(Files.isDirectory(skillsDir.resolve("test-skill")));
        assertTrue(Files.isRegularFile(skillsDir.resolve("test-skill").resolve("SKILL.md")));
    }

    @Test
    void uploadWrappedZipShouldExtractCorrectly() throws IOException {
        // 包裹结构：zip 内一层子目录包含 SKILL.md
        var zipBytes = createWrappedZip("my-skill", "Wrapped skill", "2.0.0");
        var service = createService();
        var name = service.uploadSkill(new ByteArrayInputStream(zipBytes), "my-skill.zip");

        assertEquals("my-skill", name);
        assertTrue(Files.isRegularFile(skillsDir.resolve("my-skill").resolve("SKILL.md")));
    }

    @Test
    void uploadZipWithoutSkillMdShouldFail() throws IOException {
        // 没有 SKILL.md → 报错
        var zipBytes = createZipWithoutSkillMd();
        var service = createService();
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> service.uploadSkill(new ByteArrayInputStream(zipBytes), "bad.zip")
        );
        assertTrue(ex.getMessage().contains("SKILL.md"));
    }

    @Test
    void uploadShouldOverwriteExistingSkill() throws IOException {
        var service = createService();
        // 先上传 v1
        var zipV1 = createFlatZip("overwrite-test", "Version 1", "1.0.0");
        service.uploadSkill(new ByteArrayInputStream(zipV1), "test.zip");
        assertEquals("Version 1", readDescription(service, "overwrite-test"));

        // 再上传 v2 覆盖
        var zipV2 = createFlatZip("overwrite-test", "Version 2", "2.0.0");
        service.uploadSkill(new ByteArrayInputStream(zipV2), "test.zip");
        assertEquals("Version 2", readDescription(service, "overwrite-test"));
    }

    // ========== 删除测试 ==========

    @Test
    void deleteExistingSkillShouldSucceed() throws IOException {
        var service = createService();
        var zipBytes = createFlatZip("del-me", "To delete", "1.0.0");
        service.uploadSkill(new ByteArrayInputStream(zipBytes), "del.zip");

        assertTrue(service.deleteSkill("del-me"));
        assertFalse(Files.exists(skillsDir.resolve("del-me")));
    }

    @Test
    void deleteNonexistentSkillShouldReturnFalse() throws IOException {
        var service = createService();
        assertFalse(service.deleteSkill("ghost"));
    }

    // ========== 启停测试 ==========

    @Test
    void toggleShouldSwitchState() throws IOException {
        var service = createService();
        var zipBytes = createFlatZip("toggle-test", "Toggle", "1.0.0");
        service.uploadSkill(new ByteArrayInputStream(zipBytes), "toggle.zip");

        // 初始启用
        assertFalse(service.isDisabled("toggle-test"));

        // 禁用
        boolean enabled = service.toggleSkill("toggle-test");
        assertFalse(enabled);
        assertTrue(service.isDisabled("toggle-test"));

        // 再启用
        enabled = service.toggleSkill("toggle-test");
        assertTrue(enabled);
        assertFalse(service.isDisabled("toggle-test"));
    }

    @Test
    void toggleNonexistentShouldThrow() throws IOException {
        var service = createService();
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> service.toggleSkill("no-such-skill")
        );
    }

    @Test
    void disabledSetShouldPersist() throws IOException {
        var service = createService();
        var zipBytes = createFlatZip("persist-test", "Persist", "1.0.0");
        service.uploadSkill(new ByteArrayInputStream(zipBytes), "persist.zip");
        service.toggleSkill("persist-test"); // disable

        // 新 service 实例应该读取到持久化状态
        var service2 = new SkillManageService(props);
        assertTrue(service2.isDisabled("persist-test"));
    }

    // ========== 读取/修改 SKILL.md ==========

    @Test
    void readAndWriteSkillContent() throws IOException {
        var service = createService();
        var zipBytes = createFlatZip("edit-test", "Editable", "1.0.0");
        service.uploadSkill(new ByteArrayInputStream(zipBytes), "edit.zip");

        var content = service.readSkillContent("edit-test");
        assertNotNull(content);
        assertTrue(content.contains("Editable"));

        service.writeSkillContent("edit-test", "---\nname: edit-test\ndescription: Updated\n---\n# Updated\n");
        var updated = service.readSkillContent("edit-test");
        assertTrue(updated.contains("Updated"));
    }

    @Test
    void readNonexistentContentShouldThrow() throws IOException {
        var service = createService();
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> service.readSkillContent("ghost")
        );
    }

    // ========== 列出目录 ==========

    @Test
    void listSkillDirsShouldReturnAll() throws IOException {
        var service = createService();
        service.uploadSkill(new ByteArrayInputStream(createFlatZip("a", "A", "1.0.0")), "a.zip");
        service.uploadSkill(new ByteArrayInputStream(createFlatZip("b", "B", "1.0.0")), "b.zip");

        var dirs = service.listSkillDirs();
        assertEquals(2, dirs.size());
        assertTrue(dirs.contains("a"));
        assertTrue(dirs.contains("b"));
    }

    // ========== 工具方法 ==========

    private String readDescription(SkillManageService service, String name) throws IOException {
        var content = service.readSkillContent(name);
        // 简单提取 description 行
        for (var line : content.split("\n")) {
            if (line.trim().startsWith("description:")) {
                return line.substring("description:".length()).trim();
            }
        }
        return null;
    }

    private byte[] createFlatZip(String name, String description, String version) throws IOException {
        var baos = new java.io.ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            var skillMd = "---\nname: " + name + "\ndescription: " + description + "\nversion: " + version + "\n---\n\n# " + name + "\n";
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            zos.write(skillMd.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private byte[] createWrappedZip(String name, String description, String version) throws IOException {
        var baos = new java.io.ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            var skillMd = "---\nname: " + name + "\ndescription: " + description + "\nversion: " + version + "\n---\n\n# " + name + "\n";
            zos.putNextEntry(new ZipEntry(name + "/SKILL.md"));
            zos.write(skillMd.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private byte[] createZipWithoutSkillMd() throws IOException {
        var baos = new java.io.ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("readme.txt"));
            zos.write("No SKILL.md here".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
