package io.agentmanager.framework.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
 * 存放被禁用 skill 的规范名（{@link SkillZipSupport#canonicalSkillName}，frontmatter
 * name 优先、回落目录名；issue #480）。该文件不在 SDK 扫描范围内（以 . 开头）。
 * 读取时对历史目录名条目按现存目录归一化为规范名，SDK SkillFilter / 沙箱投放
 * / 目录合并视图三方与 SDK 的 skill.getName() 同键比对。
 *
 * <p>所有文件操作直接作用于 /config/skills 目录——这是 HarnessSkillMiddleware
 * L2 仓库（FileSystemSkillRepository）扫描的事实来源。文件变化后，下轮推理
 * 自动重扫，无需重启 Agent。管理面入参按 {@link #resolveSkillDir} 解析：
 * 规范名与历史目录名均可寻址（catalog 展示的是规范名）。
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
     *   <li>包裹：zip 内一层或多层子目录包含 SKILL.md（用户常对整个文件夹压缩）</li>
     * </ul>
     *
     * <p>skill 名称优先取 SKILL.md frontmatter 中的 name 字段，
     * 缺省取包裹目录名（最深一层）或 zip 文件名（去扩展名）。
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

            var targetDir = skillsDir.resolve(skillName);
            if (Files.isDirectory(skillsDir)) {
                try (var stream = Files.list(skillsDir)) {
                    for (var existing : stream.filter(Files::isDirectory).toList()) {
                        var canonical = SkillZipSupport.canonicalSkillName(existing);
                        if (skillName.equals(canonical) || existing.equals(targetDir)) {
                            log.info("Skill '{}' existing dir '{}' removed for update", skillName, existing.getFileName());
                            SkillZipSupport.deleteRecursive(existing);
                        }
                    }
                }
            }
            if (Files.exists(targetDir)) {
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
     * <p>以技能目录为事实来源：递归打包全部文件，保留技能内相对路径
     * 与<b>原始字节</b>（二进制资源不被破坏，区别于 L4 用户技能的纯文本导出）。
     * 技能目录不存在（如 frontmatter 仅声明未落地）返回 empty，由调用方映射 404。
     *
     * @param name 规范名（历史目录名亦可寻址）
     * @return zip 字节；技能目录不存在返回 {@link Optional#empty()}
     * @throws IllegalArgumentException 名称越出 skills 目录（路径遍历防护）
     * @throws IOException              读取或打包失败
     */
    public Optional<byte[]> exportSkillZip(String name) throws IOException {
        var dir = resolveSkillDir(name);
        if (dir == null) {
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
     * @param name 规范名（历史目录名亦可寻址）
     * @return true 如果成功删除，false 如果 skill 不存在
     */
    public boolean deleteSkill(String name) throws IOException {
        var dir = resolveSkillDir(name);
        if (dir == null) {
            return false;
        }
        var canonical = SkillZipSupport.canonicalSkillName(dir);
        var disabled = loadDisabledSet();
        disabled.remove(canonical);
        if (!SkillZipSupport.deleteRecursive(dir)) {
            // 目录未删净（如 /config 为 PVC 只读挂载、权限不足）：必须显式失败——
            // 此前吞掉 IOException 后无条件返回 true，接口谎报 200 而已删，文件其实还在。
            // 文案不重复「删除失败」前缀：上层（控制器/前端）已自行加前缀。
            throw new IOException("目录仍存在（" + dir + "），可能是挂载点只读或无删除权限");
        }
        saveDisabledSet(disabled);
        log.info("Skill '{}' deleted", name);
        return true;
    }

    // ========== 启停 ==========

    /**
     * 切换 skill 启停状态。禁用集键为规范名（issue #480），写入前对历史目录名
     * 条目整体归一化迁移。
     *
     * @param name 规范名（历史目录名亦可寻址）
     * @return 切换后的状态：true=启用, false=禁用
     */
    public boolean toggleSkill(String name) throws IOException {
        var dir = resolveSkillDir(name);
        if (dir == null) {
            throw new IllegalArgumentException("Skill '" + name + "' 不存在");
        }
        var canonical = SkillZipSupport.canonicalSkillName(dir);
        var disabled = loadDisabledSet();

        boolean nowEnabled;
        if (disabled.contains(canonical)) {
            disabled.remove(canonical);
            nowEnabled = true;
            log.info("Skill '{}' enabled", name);
        } else {
            disabled.add(canonical);
            nowEnabled = false;
            log.info("Skill '{}' disabled", name);
        }
        saveDisabledSet(disabled);
        return nowEnabled;
    }

    /** 获取被禁用的 skill 规范名集合（历史目录名条目按现存目录归一化） */
    public Set<String> getDisabledSet() {
        return loadDisabledSet();
    }

    /** 判断指定 skill 是否被禁用 */
    public boolean isDisabled(String name) {
        var dir = resolveSkillDir(name);
        if (dir == null) {
            return false;
        }
        return loadDisabledSet().contains(SkillZipSupport.canonicalSkillName(dir));
    }

    // ========== 读取/修改 SKILL.md 内容 ==========

    /** 读取指定 skill 的 SKILL.md 内容（规范名或历史目录名寻址） */
    public String readSkillContent(String name) throws IOException {
        var dir = resolveSkillDir(name);
        if (dir == null) {
            throw new IllegalArgumentException("Skill '" + name + "' 不存在");
        }
        var skillMd = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) {
            throw new IllegalArgumentException("Skill '" + name + "' 的 SKILL.md 不存在");
        }
        // 文本 UTF-8 优先、GBK 兜底（兼容历史 GBK 技能）；两者都不是则拒绝而不是返回乱码
        var text = SkillZipSupport.decodeText(Files.readAllBytes(skillMd));
        if (text == null) {
            throw new IllegalArgumentException("Skill '" + name + "' 的 SKILL.md 不是 UTF-8/GBK 文本");
        }
        return text;
    }

    /** 修改指定 skill 的 SKILL.md 内容（规范名或历史目录名寻址） */
    public void writeSkillContent(String name, String content) throws IOException {
        var dir = resolveSkillDir(name);
        if (dir == null) {
            throw new IllegalArgumentException("Skill '" + name + "' 不存在");
        }
        var skillMd = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) {
            throw new IllegalArgumentException("Skill '" + name + "' 的 SKILL.md 不存在");
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

    // ========== 规范名解析（issue #480） ==========

    /**
     * 管理面统一寻址入口：{@code name} 优先按目录名直取（兼容历史目录名调用与
     * 规范名==目录名的常见形态），未命中再按 {@link SkillZipSupport#canonicalSkillName}
     * 索引解析（catalog/SDK 暴露的是 frontmatter 规范名；重复规范名按
     * {@link SkillZipSupport#canonicalDirIndex} 目录名字典序裁决，issue #504——
     * 与 bundle 打包收录同一目录）。路径越出 skills 目录
     * 抛 {@link IllegalArgumentException}；无匹配返回 null，由调用方按各自契约处理。
     */
    private Path resolveSkillDir(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        var root = skillsDir.normalize();
        var direct = skillsDir.resolve(name).normalize();
        if (!direct.startsWith(root)) {
            throw new IllegalArgumentException("路径遍历攻击防护");
        }
        boolean directChild = direct.getParent() != null && direct.getParent().equals(root)
            && !name.startsWith(".") && Files.isDirectory(direct);
        try (var stream = Files.list(skillsDir)) {
            var matched = SkillZipSupport.canonicalDirIndex(stream.filter(Files::isDirectory).toList())
                .get(name);
            if (matched != null) {
                return matched;
            }
        } catch (IOException e) {
            log.warn("Failed to resolve skill dir '{}': {}", name, e.getMessage());
        }
        return directChild ? direct : null;
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
            return normalizeToCanonicalNames(new HashSet<>(list));
        } catch (Exception e) {
            log.warn("Failed to load skill states: {}", e.getMessage());
            return new HashSet<>();
        }
    }

    /** 历史目录名条目经现存目录索引归一为规范名；目录已消失的条目原样保留（删除时清） */
    private Set<String> normalizeToCanonicalNames(Set<String> stored) {
        var index = canonicalNameIndex();
        var canonicalNames = new HashSet<>(index.values());
        var out = new HashSet<String>();
        for (var entry : stored) {
            out.add(canonicalNames.contains(entry) ? entry : index.getOrDefault(entry, entry));
        }
        return out;
    }

    /** 现存技能目录的 dirName → canonicalSkillName 索引（隐藏目录跳过） */
    private Map<String, String> canonicalNameIndex() {
        var index = new HashMap<String, String>();
        if (!Files.isDirectory(skillsDir)) {
            return index;
        }
        try (var stream = Files.list(skillsDir)) {
            for (var dir : stream.filter(Files::isDirectory).toList()) {
                var dirName = dir.getFileName().toString();
                if (dirName.startsWith(".")) {
                    continue;
                }
                index.put(dirName, SkillZipSupport.canonicalSkillName(dir));
            }
        } catch (IOException e) {
            log.warn("Failed to index skills dir for canonical names: {}", e.getMessage());
        }
        return index;
    }

    private void saveDisabledSet(Set<String> disabled) throws IOException {
        Files.createDirectories(skillsDir);
        var stateFile = skillsDir.resolve(STATES_FILE);
        var json = MAPPER.writerWithDefaultPrettyPrinter()
            .writeValueAsString(new ArrayList<>(disabled));
        Files.writeString(stateFile, json, StandardCharsets.UTF_8);
    }
}
