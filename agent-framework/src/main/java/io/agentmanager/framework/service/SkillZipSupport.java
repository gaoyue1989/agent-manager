package io.agentmanager.framework.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 技能 zip 包解析工具：从 {@link SkillManageService} 提取的纯函数式 helper，供
 * 全局技能上传（/config/skills）与用户技能 zip 上传（agent_fs L4）共用，避免两处解压/命名逻辑漂移。
 *
 * <p>安全边界：路径穿越防护（解压目标必须落在临时目录内）、条目数上限、解压总大小上限
 * （防 zip 炸弹）。Mac 的 {@code __MACOSX}/.DS_Store 直接跳过。
 */
public final class SkillZipSupport {

    private static final Logger log = LoggerFactory.getLogger(SkillZipSupport.class);

    /** 单包条目数上限 */
    public static final int MAX_ENTRIES = 1000;
    /** 解压后总大小上限（防 zip 炸弹；上传的压缩包本体大小由调用方另行限制） */
    public static final long MAX_EXTRACTED_BYTES = 50L * 1024 * 1024;

    private SkillZipSupport() {}

    /** 定位到的技能根目录与主文件 */
    public record LocatedSkill(Path skillRoot, Path skillMd) {}

    /**
     * 解压 zip 到目标目录（调用方负责创建/清理临时目录）。
     *
     * @throws IllegalArgumentException 条目数/总大小超限、含非法（穿越）路径
     * @throws IOException              解压 IO 失败
     */
    public static void extractZip(InputStream is, Path targetDir) throws IOException {
        try (var zis = new ZipInputStream(is)) {
            ZipEntry entry;
            int entryCount = 0;
            long totalSize = 0;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new IllegalArgumentException("Zip 包内文件数量超过 " + MAX_ENTRIES + " 限制");
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
                    if (totalSize > MAX_EXTRACTED_BYTES) {
                        throw new IllegalArgumentException(
                            "解压后总大小超过 " + (MAX_EXTRACTED_BYTES / 1024 / 1024) + "MB 限制");
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * 定位技能根目录：支持扁平（zip 根即含 SKILL.md）与包裹（一层子目录含 SKILL.md）两种形态。
     * 找不到返回 null。
     */
    public static LocatedSkill locateSkillRoot(Path tmpDir) throws IOException {
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
     * 从 SKILL.md frontmatter 提取 name 字段（简单解析：首个 --- 之间的 name: 行）。
     * 无 frontmatter/无 name 返回 null。
     */
    public static String extractSkillName(Path skillMd) throws IOException {
        var content = Files.readString(skillMd, StandardCharsets.UTF_8);
        if (!content.startsWith("---")) {
            return null;
        }

        var end = content.indexOf("---", 3);
        if (end < 0) {
            return null;
        }

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

    /** 技能名安全化：只允许字母、数字、中文、下划线、连字符、点；不允许以 . 开头 */
    public static String sanitizeSkillName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        var sanitized = name.replaceAll("[^\\w\\u4e00-\\u9fff.\\-]", "_");
        // 不允许以 . 开头（隐藏文件/目录）
        while (sanitized.startsWith(".")) {
            sanitized = "_" + sanitized.substring(1);
        }
        return sanitized.isBlank() ? null : sanitized;
    }

    /** 去掉文件扩展名；空/无扩展名回退 unnamed-skill */
    public static String stripExtension(String fileName, String ext) {
        if (fileName == null) {
            return "unnamed-skill";
        }
        var name = fileName;
        if (name.toLowerCase().endsWith("." + ext.toLowerCase())) {
            name = name.substring(0, name.length() - ext.length() - 1);
        }
        return name.isBlank() ? "unnamed-skill" : name;
    }

    /** 递归删除目录（best-effort，不抛异常） */
    public static void deleteRecursive(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("Failed to delete {}: {}", p, e.getMessage());
                        }
                    });
            }
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", dir, e.getMessage());
        }
    }

    /** 递归复制目录（跨文件系统回退方案） */
    public static void copyRecursive(Path source, Path target) throws IOException {
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
