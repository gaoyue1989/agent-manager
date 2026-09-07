package io.agentmanager.framework.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;

/**
 * OAF skills 动态加载仓库测试：锁定 AgentScopeConfig 注册参数组合
 * （writeable=false, source="oaf-package"）下官方 FileSystemSkillRepository 的动态感知行为。
 *
 * 对应设计文档 oaf-skills-dynamic-loading-plan.md §8.1 OafSkillRepositoryTest：
 * PVC 上 /config/skills 原位新增/修改/删除 → getAllSkills() 下次调用即时反映。
 */
class OafSkillRepositoryTest {

    @TempDir
    Path tempDir;

    private Path skillsDir;
    private FileSystemSkillRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        // 与 AgentScopeConfig.harnessAgent 注册参数一致
        repository = new FileSystemSkillRepository(skillsDir, false, "oaf-package");
    }

    private void writeSkill(String name, String description) throws IOException {
        var dir = skillsDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
            "---\nname: " + name + "\ndescription: " + description + "\n---\n# " + name + "\n");
    }

    private List<String> names() {
        return repository.getAllSkills().stream().map(AgentSkill::getName).toList();
    }

    @Test
    void newSkillDirectoryShouldBeVisibleOnNextScan() throws IOException {
        writeSkill("base", "Base skill");
        assertEquals(List.of("base"), names());

        // 模拟 PVC 原位新增（不重启、不重建仓库）
        writeSkill("added", "Added at runtime");
        assertTrue(names().contains("added"));
        assertEquals(2, names().size());
    }

    @Test
    void modifiedSkillMdShouldBeReRead() throws IOException {
        writeSkill("a", "Old desc");
        assertEquals("Old desc", repository.getSkill("a").getDescription());

        var skillMd = skillsDir.resolve("a").resolve("SKILL.md");
        Files.writeString(skillMd, "---\nname: a\ndescription: New desc\n---\n# a\n");
        // 显式推进 mtime：规避文件系统 mtime 粒度导致的缓存短路误判
        Files.setLastModifiedTime(skillMd, FileTime.fromMillis(System.currentTimeMillis() + 2000));

        assertEquals("New desc", repository.getSkill("a").getDescription());
    }

    @Test
    void deletedSkillDirectoryShouldDisappear() throws IOException {
        writeSkill("a", "Skill A");
        writeSkill("b", "Skill B");
        assertEquals(2, names().size());

        try (var stream = Files.walk(skillsDir.resolve("a"))) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 测试清理，忽略
                }
            });
        }

        var after = names();
        assertFalse(after.contains("a"));
        assertTrue(after.contains("b"));
    }

    @Test
    void readOnlyRepositoryShouldRejectWrites() throws IOException {
        writeSkill("a", "Skill A");
        var skill = repository.getSkill("a");

        assertFalse(repository.isWriteable());
        // save/delete 被仓库层语义化拒绝（PVC 只读防御：skill_manage 误写不落盘）
        assertFalse(repository.save(List.of(skill), true));
        assertFalse(repository.delete("a"));
        assertTrue(Files.exists(skillsDir.resolve("a").resolve("SKILL.md")));
    }

    @Test
    void sourceShouldBeOafPackage() {
        assertEquals("oaf-package", repository.getSource());
        assertFalse(repository.isWriteable());
    }
}
