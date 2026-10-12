package io.agentmanager.framework.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
            SkillZipSupport.extractZip(zipInputStream, tmpDir);
        } catch (Exception e) {
            SkillZipSupport.deleteRecursive(tmpDir);
            throw e;
        }

        try {
            // 2. 定位 SKILL.md 及 skill 根目录
            var located = SkillZipSupport.locateSkillRoot(tmpDir);
            if (located == null) {
                SkillZipSupport.deleteRecursive(tmpDir);
                throw new IllegalArgumentException("Zip 包中未找到 SKILL.md 文件");
            }
            var skillRoot = located.skillRoot();
            var skillMd = located.skillMd();

            // 3. 从 SKILL.md frontmatter 提取 name
            var skillName = SkillZipSupport.extractSkillName(skillMd);
            if (skillName == null || skillName.isBlank()) {
                // 降级：用包裹目录名或 zip 文件名
                if (!skillRoot.equals(tmpDir)) {
                    skillName = skillRoot.getFileName().toString();
                } else {
                    skillName = SkillZipSupport.stripExtension(zipFileName, "zip");
                }
            }

            // 4. 名称安全校验
            skillName = SkillZipSupport.sanitizeSkillName(skillName);
            if (skillName == null || skillName.isBlank()) {
                SkillZipSupport.deleteRecursive(tmpDir);
                throw new IllegalArgumentException("无效的 Skill 名称");
            }

            // 5. 检查是否已存在同名 skill（已存在则覆盖更新）
            var targetDir = skillsDir.resolve(skillName);
            if (Files.exists(targetDir)) {
                log.info("Skill '{}' already exists, will be updated", skillName);
                SkillZipSupport.deleteRecursive(targetDir);
            }

            // 6. 移动到 skills 目录
            //    ATOMIC_MOVE 和 REPLACE_EXISTING 都无法跨文件系统移动非空目录，
            //    因此先尝试 move，失败则回退到递归 copy + 删除源
            Files.createDirectories(skillsDir);
            try {
                Files.move(skillRoot, targetDir, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                log.info("ATOMIC_MOVE not supported ({} → {}), trying REPLACE_EXISTING",
                         skillRoot, targetDir);
                try {
                    Files.move(skillRoot, targetDir, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.FileSystemException ex) {
                    log.info("REPLACE_EXISTING also failed (cross-device?), falling back to copy: {}",
                             ex.getMessage());
                    SkillZipSupport.copyRecursive(skillRoot, targetDir);
                    SkillZipSupport.deleteRecursive(skillRoot);
                }
            } catch (java.nio.file.FileSystemException e) {
                log.info("ATOMIC_MOVE failed ({} → {}), trying copy fallback: {}",
                         skillRoot, targetDir, e.getMessage());
                SkillZipSupport.copyRecursive(skillRoot, targetDir);
                SkillZipSupport.deleteRecursive(skillRoot);
            }
            log.info("Skill '{}' uploaded to {}", skillName, targetDir);

            return skillName;
        } finally {
            // 清理临时目录（如果 skillRoot == tmpDir 则已 move 走）
            SkillZipSupport.deleteRecursive(tmpDir);
        }
    }

    // ========== 下载（导出为 zip） ==========

    /**
     * 导出指定 Skill 的整目录为 zip（供下载）。
     *
     * <p>以 {@code /config/skills/{name}} 目录为事实来源：递归打包全部文件，保留技能内相对路径
     * 与<b>原始字节</b>（二进制资源不被破坏，区别于 L4 用户技能的纯文本导出）。
     * 技能目录不存在（如 frontmatter 仅声明未落地）返回 empty，由调用方映射 404。
     *
     * @return zip 字节；技能目录不存在返回 {@link Optional#empty()}
     * @throws IllegalArgumentException 名称越出 skills 目录（路径遍历防护）
     * @throws IOException              读取或打包失败
     */
    public Optional<byte[]> exportSkillZip(String name) throws IOException {
        var dir = skillsDir.resolve(name).normalize();
        if (!dir.startsWith(skillsDir.normalize())) {
            throw new IllegalArgumentException("路径遍历攻击防护");
        }
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        var baos = new java.io.ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(baos)) {
            List<Path> files;
            try (var stream = Files.walk(dir)) {
                files = stream.filter(Files::isRegularFile).sorted().toList();
            }
            for (var path : files) {
                var rel = dir.relativize(path).toString().replace('\\', '/');
                zos.putNextEntry(new java.util.zip.ZipEntry(rel));
                Files.copy(path, zos);
                zos.closeEntry();
            }
        }
        return Optional.of(baos.toByteArray());
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
        SkillZipSupport.deleteRecursive(dir);
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
}
