package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
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
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120, true, 0, "", null),
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
    void uploadMultiLevelWrappedZipShouldExtractCorrectly() throws IOException {
        // 多层包裹：用户对整个文件夹压缩，zip 内含多级父目录（outer/inner/SKILL.md）
        var baos = new java.io.ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("outer/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("outer/inner/SKILL.md"));
            zos.write("---\nname: deep-skill\n---\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("outer/inner/scripts/a.sh"));
            zos.write("echo a".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        var service = createService();
        var name = service.uploadSkill(new ByteArrayInputStream(baos.toByteArray()), "deep.zip");

        assertEquals("deep-skill", name);
        assertTrue(Files.isRegularFile(skillsDir.resolve("deep-skill").resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(skillsDir.resolve("deep-skill").resolve("scripts").resolve("a.sh")),
            "多层包裹下的资源文件也要落到技能根");
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
    void uploadZipWithNonUtf8SkillMdShouldFail400() throws IOException {
        // SKILL.md 本身非 UTF-8（GBK）→ 拒绝上传，不得静默装损坏技能（issue #115：此前误报 500）
        var baos = new java.io.ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            var gbkMd = "---\nname: gbk-skill\ndescription: 中文编码测试\n---\n正文"
                .getBytes(java.nio.charset.Charset.forName("GBK"));
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            zos.write(gbkMd);
            zos.closeEntry();
        }
        var service = createService();
        var ex = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> service.uploadSkill(new ByteArrayInputStream(baos.toByteArray()), "gbk.zip")
        );
        assertTrue(ex.getMessage().contains("UTF-8"), "应说明 SKILL.md 非 UTF-8 被拒: " + ex.getMessage());
        assertFalse(Files.exists(skillsDir.resolve("gbk-skill")), "拒绝时不得安装技能");
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

    /**
     * 删除失败（如 /config 为只读挂载、权限不足）必须显式抛 IOException，
     * 不得静默吞掉后谎报成功（止血方案：调用方须能区分「真删了」与「没删掉」）。
     */
    @Test
    void deleteShouldThrowWhenDirectoryCannotBeRemoved() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
            "需要 POSIX 权限支持才能模拟不可删除目录");
        var service = createService();
        service.uploadSkill(new ByteArrayInputStream(createFlatZip("locked", "x", "1.0.0")), "locked.zip");

        var dir = skillsDir.resolve("locked");
        var original = Files.getPosixFilePermissions(dir);
        // 去掉目录写权限：其下文件删除失败，目录因非空也无法删除
        Files.setPosixFilePermissions(dir,
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            var ex = org.junit.jupiter.api.Assertions.assertThrows(
                IOException.class, () -> service.deleteSkill("locked"));
            assertTrue(ex.getMessage().contains("仍存在"), ex.getMessage());
            assertTrue(Files.exists(dir), "删除失败时目录必须保留，不得谎报已删");
        } finally {
            Files.setPosixFilePermissions(dir, original);
        }
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

    // ========== 下载（导出 zip） ==========

    @Test
    void exportSkillZipShouldIncludeNestedFilesAndPreserveBytes() throws IOException {
        var service = createService();
        var skillDir = skillsDir.resolve("dl-test");
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: dl-test\n---\n# dl-test\n");
        Files.writeString(skillDir.resolve("scripts/run.sh"), "echo hi\n");
        var binary = new byte[] {0, 1, 2, 3, (byte) 0xFF, (byte) 0x80};
        Files.write(skillDir.resolve("scripts/asset.bin"), binary);

        var zip = service.exportSkillZip("dl-test");
        assertTrue(zip.isPresent());

        var entries = readZipEntries(zip.get());
        assertEquals(3, entries.size());
        assertTrue(entries.containsKey("SKILL.md"));
        assertTrue(entries.get("scripts/run.sh").length > 0);
        org.junit.jupiter.api.Assertions.assertArrayEquals(binary, entries.get("scripts/asset.bin"));
    }

    @Test
    void exportNonexistentSkillShouldReturnEmpty() throws IOException {
        var service = createService();
        assertTrue(service.exportSkillZip("ghost").isEmpty());
    }

    // ========== 工具方法 ==========

    private static Map<String, byte[]> readZipEntries(byte[] zipBytes) throws IOException {
        var out = new java.util.LinkedHashMap<String, byte[]>();
        try (var zis = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    out.put(entry.getName(), zis.readAllBytes());
                }
                zis.closeEntry();
            }
        }
        return out;
    }

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
