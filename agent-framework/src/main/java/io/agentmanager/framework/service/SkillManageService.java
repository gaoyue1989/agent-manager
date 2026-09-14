package io.agentmanager.framework.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentmanager.framework.config.AgentManagerProperties;

/**
 * Skill 包管理服务：zip 上传/解压、删除、启停状态管理。
 *
 * <p>启停状态持久化于 {configDir}/skills/.skill-states.json（Set&lt;String&gt; 格式，
 * 存放被禁用的 skill 名称）。该文件不在 SDK 扫描范围内（以 . 开头）。
 *
 * <p>所有文件操作直接作用于 /config/skills 目录——这是 HarnessSkillMiddleware
 * L2 仓库（FileSystemSkillRepository）扫描的事实来源。文件变化后，下轮推理
 * 自动重扫，无需重启 Agent。
 */
@Service
public class SkillManageService {
    private static final Logger log = LoggerFactory.getLogger(SkillManageService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STATES_FILE = ".skill-states.json";

    private final Path skillsDir;

    public SkillManageService(AgentManagerProperties props) {
        this.skillsDir = Path.of(props.resolvedConfigDir()).resolve("skills");
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            log.warn("Failed to create skills dir: {}", e.getMessage());
        }
        log.info("SkillManageService initialized (skillsDir: {})", skillsDir);
    }

    /** 返回 skills 目录路径 */
    public Path getSkillsDir() {
        return skillsDir;
    }

    // ========== 上传 ==========

    /**
     * 上传并解压 zip 格式的 Skill 包。
     *
     * <p>zip 结构支持两种形态：
     * <ul>
     *   <li>扁平：zip 根目录直接包含 SKILL.md</li>
     *   <li>包裹：zip 内一层子目录包含 SKILL.md</li>
     * </ul>
     *
     * <p>skill 名称优先取 SKILL.md frontmatter 中的 name 字段，
     * 缺省取包裹目录名或 zip 文件名（去扩展名）。
     *
     * @return 解压后的 skill 名称
     * @throws IllegalArgumentException 校验失败
     * @throws IOException IO 异常
     */
    public String uploadSkill(InputStream zipInputStream, String zipFileName) throws IOException {
        // 1. 解压到临时目录
        var tmpDir = Files.createTempDirectory("skill-upload-");
        try {
            extractZip(zipInputStream, tmpDir);
        } catch (Exception e) {
            deleteRecursive(tmpDir);
            throw e;
        }

        try {
            // 2. 定位 SKILL.md 及 skill 根目录
            var located = locateSkillRoot(tmpDir);
            if (located == null) {
                deleteRecursive(tmpDir);
                throw new IllegalArgumentException("Zip 包中未找到 SKILL.md 文件");
            }
            var skillRoot = located.skillRoot;
            var skillMd = located.skillMd;

            // 3. 从 SKILL.md frontmatter 提取 name
            var skillName = extractSkillName(skillMd);
            if (skillName == null || skillName.isBlank()) {
                // 降级：用包裹目录名或 zip 文件名
                if (!skillRoot.equals(tmpDir)) {
                    skillName = skillRoot.getFileName().toString();
                } else {
                    skillName = stripExtension(zipFileName, "zip");
                }
            }

            // 4. 名称安全校验
            skillName = sanitizeSkillName(skillName);
            if (skillName == null || skillName.isBlank()) {
                deleteRecursive(tmpDir);
                throw new IllegalArgumentException("无效的 Skill 名称");
            }

            // 5. 检查是否已存在同名 skill（已存在则覆盖更新）
            var targetDir = skillsDir.resolve(skillName);
            if (Files.exists(targetDir)) {
                log.info("Skill '{}' already exists, will be updated", skillName);
                deleteRecursive(targetDir);
            }

            // 6. 移动到 skills 目录
            //    ATOMIC_MOVE 和 REPLACE_EXISTING 都无法跨文件系统移动非空目录，
            //    因此先尝试 move，失败则回退到递归 copy + 删除源
            Files.createDirectories(skillsDir);
            try {
                Files.move(skillRoot, targetDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                log.info("ATOMIC_MOVE not supported ({} → {}), trying REPLACE_EXISTING",
                         skillRoot, targetDir);
                try {
                    Files.move(skillRoot, targetDir, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.FileSystemException ex) {
                    log.info("REPLACE_EXISTING also failed (cross-device?), falling back to copy: {}",
                             ex.getMessage());
                    copyRecursive(skillRoot, targetDir);
                    deleteRecursive(skillRoot);
                }
            } catch (java.nio.file.FileSystemException e) {
                log.info("ATOMIC_MOVE failed ({} → {}), trying copy fallback: {}",
                         skillRoot, targetDir, e.getMessage());
                copyRecursive(skillRoot, targetDir);
                deleteRecursive(skillRoot);
            }
            log.info("Skill '{}' uploaded to {}", skillName, targetDir);

            return skillName;
        } finally {
            // 清理临时目录（如果 skillRoot == tmpDir 则已 move 走）
            deleteRecursive(tmpDir);
        }
    }

    // ========== 删除 ==========

    /**
     * 删除指定名称的 Skill。
     *
     * @return true 如果成功删除，false 如果 skill 不存在
     */
    public boolean deleteSkill(String name) throws IOException {
        var dir = skillsDir.resolve(name);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        // 安全检查：确保在 skills 目录下
        if (!dir.normalize().startsWith(skillsDir.normalize())) {
            throw new IllegalArgumentException("路径遍历攻击防护");
        }
        deleteRecursive(dir);
        // 同时清除禁用状态
        removeDisabledState(name);
        log.info("Skill '{}' deleted", name);
        return true;
    }

    // ========== 启停 ==========

    /**
     * 切换 skill 启停状态。
     *
     * @return 切换后的状态：true=启用, false=禁用
     */
    public boolean toggleSkill(String name) throws IOException {
        var disabled = loadDisabledSet();
        var dir = skillsDir.resolve(name);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Skill '" + name + "' 不存在");
        }

        boolean nowEnabled;
        if (disabled.contains(name)) {
            disabled.remove(name);
            nowEnabled = true;
            log.info("Skill '{}' enabled", name);
        } else {
            disabled.add(name);
            nowEnabled = false;
            log.info("Skill '{}' disabled", name);
        }
        saveDisabledSet(disabled);
        return nowEnabled;
    }

    /** 获取被禁用的 skill 名称集合 */
    public Set<String> getDisabledSet() {
        return loadDisabledSet();
    }

    /** 判断指定 skill 是否被禁用 */
    public boolean isDisabled(String name) {
        return loadDisabledSet().contains(name);
    }

    // ========== 读取/修改 SKILL.md 内容 ==========

    /** 读取指定 skill 的 SKILL.md 内容 */
    public String readSkillContent(String name) throws IOException {
        var skillMd = skillsDir.resolve(name).resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) {
            throw new IllegalArgumentException("Skill '" + name + "' 的 SKILL.md 不存在");
        }
        return Files.readString(skillMd, StandardCharsets.UTF_8);
    }

    /** 修改指定 skill 的 SKILL.md 内容 */
    public void writeSkillContent(String name, String content) throws IOException {
        var skillMd = skillsDir.resolve(name).resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) {
            throw new IllegalArgumentException("Skill '" + name + "' 的 SKILL.md 不存在");
        }
        // 安全检查：确保路径在 skills 目录下
        if (!skillMd.normalize().startsWith(skillsDir.normalize())) {
            throw new IllegalArgumentException("路径遍历攻击防护");
        }
        Files.writeString(skillMd, content, StandardCharsets.UTF_8);
        log.info("Skill '{}' content updated", name);
    }

    // ========== 列出目录下实际存在的 skill 名 ==========

    /** 列出 skillsDir 下所有子目录（即实际存在的 skill） */
    public List<String> listSkillDirs() {
        var names = new ArrayList<String>();
        if (!Files.isDirectory(skillsDir)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
            for (var entry : stream) {
                if (Files.isDirectory(entry) && !entry.getFileName().toString().startsWith(".")) {
                    names.add(entry.getFileName().toString());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list skills dir: {}", e.getMessage());
        }
        return names;
    }

    // ========== 内部方法 ==========

    private void extractZip(InputStream is, Path targetDir) throws IOException {
        try (var zis = new ZipInputStream(is)) {
            ZipEntry entry;
            int entryCount = 0;
            long totalSize = 0;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > 1000) {
                    throw new IllegalArgumentException("Zip 包内文件数量超过 1000 限制");
                }

                // 路径遍历防护
                var dest = targetDir.resolve(entry.getName()).normalize();
                if (!dest.startsWith(targetDir.normalize())) {
                    throw new IllegalArgumentException("Zip 包含非法路径: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    // Mac 的 __MACOSX 目录和 .DS_Store 文件跳过
                    if (entry.getName().contains("__MACOSX") || entry.getName().endsWith(".DS_Store")) {
                        continue;
                    }
                    Files.createDirectories(dest.getParent());
                    var size = Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING);
                    totalSize += size;
                    if (totalSize > 50 * 1024 * 1024) { // 50MB 上限
                        throw new IllegalArgumentException("解压后总大小超过 50MB 限制");
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private record LocatedSkill(Path skillRoot, Path skillMd) {}

    private LocatedSkill locateSkillRoot(Path tmpDir) throws IOException {
        // 情况1：zip 根目录直接包含 SKILL.md
        var rootSkillMd = tmpDir.resolve("SKILL.md");
        if (Files.isRegularFile(rootSkillMd)) {
            return new LocatedSkill(tmpDir, rootSkillMd);
        }

        // 情况2：zip 内一层子目录包含 SKILL.md
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmpDir)) {
            for (var entry : stream) {
                if (Files.isDirectory(entry) && !entry.getFileName().toString().startsWith(".")) {
                    var subSkillMd = entry.resolve("SKILL.md");
                    if (Files.isRegularFile(subSkillMd)) {
                        return new LocatedSkill(entry, subSkillMd);
                    }
                }
            }
        }
        return null;
    }

    /**
     * 从 SKILL.md frontmatter 提取 name 字段。
     * 简单解析：找 --- 之间的 name: 行
     */
    private String extractSkillName(Path skillMd) throws IOException {
        var content = Files.readString(skillMd, StandardCharsets.UTF_8);
        if (!content.startsWith("---")) return null;

        var end = content.indexOf("---", 3);
        if (end < 0) return null;

        var frontmatter = content.substring(3, end);
        for (var line : frontmatter.split("\n")) {
            var trimmed = line.trim();
            if (trimmed.startsWith("name:")) {
                var value = trimmed.substring(5).trim();
                // 去除引号
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
                    value = value.substring(1, value.length() - 1);
                }
                if (value.startsWith("'") && value.endsWith("'") && value.length() > 1) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }

    /** skill 名称安全化：只允许字母、数字、中文、下划线、连字符、点 */
    private String sanitizeSkillName(String name) {
        if (name == null || name.isBlank()) return null;
        var sanitized = name.replaceAll("[^\\w\\u4e00-\\u9fff.\\-]", "_");
        // 不允许以 . 开头（隐藏文件/目录）
        while (sanitized.startsWith(".")) {
            sanitized = "_" + sanitized.substring(1);
        }
        return sanitized.isBlank() ? null : sanitized;
    }

    private String stripExtension(String fileName, String ext) {
        if (fileName == null) return "unnamed-skill";
        var name = fileName;
        if (name.toLowerCase().endsWith("." + ext.toLowerCase())) {
            name = name.substring(0, name.length() - ext.length() - 1);
        }
        return name.isBlank() ? "unnamed-skill" : name;
    }

    // ========== 启停状态持久化 ==========

    private Set<String> loadDisabledSet() {
        var stateFile = skillsDir.resolve(STATES_FILE);
        if (!Files.isRegularFile(stateFile)) {
            return new HashSet<>();
        }
        try {
            var json = Files.readString(stateFile, StandardCharsets.UTF_8);
            var type = MAPPER.getTypeFactory()
                .constructCollectionType(List.class, String.class);
            List<String> list = MAPPER.readValue(json, type);
            return new HashSet<>(list);
        } catch (Exception e) {
            log.warn("Failed to load skill states: {}", e.getMessage());
            return new HashSet<>();
        }
    }

    private void saveDisabledSet(Set<String> disabled) throws IOException {
        Files.createDirectories(skillsDir);
        var stateFile = skillsDir.resolve(STATES_FILE);
        var json = MAPPER.writerWithDefaultPrettyPrinter()
            .writeValueAsString(new ArrayList<>(disabled));
        Files.writeString(stateFile, json, StandardCharsets.UTF_8);
    }

    private void removeDisabledState(String name) throws IOException {
        var disabled = loadDisabledSet();
        if (disabled.remove(name)) {
            saveDisabledSet(disabled);
        }
    }

    // ========== 文件工具 ==========

    private void deleteRecursive(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException e) { /* best-effort */ }
                    });
            }
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", dir, e.getMessage());
        }
    }

    /** 递归复制目录（跨文件系统回退方案） */
    private void copyRecursive(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            for (var path : stream.toList()) {
                var dest = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
