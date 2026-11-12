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
    /**
     * 定位 SKILL.md 时允许的最大包裹层级（相对于 zip 根）。
     *
     * <p>用户常「对整个文件夹压缩」，zip 内会带一层或多层父目录；层级越大误取深层示例
     * SKILL.md 的风险越高，故设上限（扁平=0，一层包裹=1）。
     */
    public static final int MAX_WRAP_DEPTH = 4;

    private SkillZipSupport() {}

    /** 定位到的技能根目录与主文件 */
    public record LocatedSkill(Path skillRoot, Path skillMd) {}

    /**
     * 解压 zip 到目标目录（调用方负责创建/清理临时目录）。
     *
     * <p><b>编码兼容</b>：条目名先按 UTF-8 严格解码；失败则整包回退 GBK 重试
     * （Windows 自带压缩/旧版 WinRAR 的中文文件名是 GBK 字节且不带 UTF-8 标志，
     * JDK 直接解会抛 "malformed input off : N, length : 1"）。已带 UTF-8 标志的条目
     * 在 GBK 回退轮仍按 UTF-8 解码（JDK 尊重 EFS 标志），因此两类文件名可共存于同一包。
     * 两轮都失败才拒绝。包体先读入内存（HTTP 层已有 10/20MB 上限）以支持重试。
     *
     * <p><b>内容编码</b>：文件内容也按 {@link #decodeText} 处理——UTF-8 原样落盘，文本嗅探通过
     * 的 GBK 内容转码为 UTF-8（用户分不清 UTF-8/GBK），二进制原样写盘（L2 保留资源，L4 由
     * 上层跳过）。
     *
     * @throws IllegalArgumentException 条目数/总大小超限、含非法（穿越）路径、
     *                                  条目名既非 UTF-8 也非 GBK
     * @throws IOException              解压 IO 失败
     */
    public static void extractZip(InputStream is, Path targetDir) throws IOException {
        var zipBytes = readZipBytes(is);
        try {
            extractZipOnce(new java.io.ByteArrayInputStream(zipBytes), targetDir, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            if (!isEntryNameCodingError(e)) {
                throw e;
            }
            // 首轮可能已解出部分文件（其名字是合法 UTF-8），回退前清空避免残留半包
            log.warn("zip 条目名不是合法 UTF-8（{}），回退 GBK 重新解析", e.getMessage());
            clearDirectory(targetDir);
            try {
                extractZipOnce(new java.io.ByteArrayInputStream(zipBytes), targetDir,
                    java.nio.charset.Charset.forName("GBK"));
            } catch (IllegalArgumentException gbkError) {
                if (!isEntryNameCodingError(gbkError)) {
                    throw gbkError;
                }
                clearDirectory(targetDir);
                log.warn("zip 条目名 UTF-8/GBK 均解码失败（utf8={}, gbk={}）",
                    e.getMessage(), gbkError.getMessage());
                throw new IllegalArgumentException(
                    "zip 包内文件名既不是 UTF-8 也不是 GBK 编码，请把文件名/目录名改成英文后重试",
                    gbkError);
            }
        }
    }

    /** 单轮解压（指定条目名字符集）；条目名解码失败抛 IllegalArgumentException 交给外层回退 */
    private static void extractZipOnce(InputStream is, Path targetDir, java.nio.charset.Charset charset)
            throws IOException {
        try (var zis = new ZipInputStream(is, charset)) {
            ZipEntry entry;
            int entryCount = 0;
            long totalSize = 0;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new IllegalArgumentException(
                        "zip 包内文件数量超过 " + MAX_ENTRIES + " 个，请精简后重试");
                }

                // 路径遍历防护
                var dest = targetDir.resolve(entry.getName()).normalize();
                if (!dest.startsWith(targetDir.normalize())) {
                    throw new IllegalArgumentException("zip 包含非法路径：" + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    // Mac 的 __MACOSX 目录和 .DS_Store 文件跳过
                    if (entry.getName().contains("__MACOSX") || entry.getName().endsWith(".DS_Store")) {
                        continue;
                    }
                    Files.createDirectories(dest.getParent());
                    var bytes = readEntryBytes(zis);
                    // 文本条目统一落成 UTF-8：GBK 文本（Windows 记事本/ANSI）自动转码，
                    // 二进制（图片等）原样写盘，由上层决定保留（L2）或跳过（L4）。
                    var text = decodeText(bytes);
                    long size;
                    if (text != null) {
                        var utf8 = text.getBytes(StandardCharsets.UTF_8);
                        Files.write(dest, utf8);
                        size = utf8.length;
                    } else {
                        Files.write(dest, bytes);
                        size = bytes.length;
                    }
                    totalSize += size;
                    if (totalSize > MAX_EXTRACTED_BYTES) {
                        throw new IllegalArgumentException(
                            "解压后总大小超过 " + (MAX_EXTRACTED_BYTES / 1024 / 1024)
                                + "MB，请精简技能资源后重试");
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /** 读取 zip 包体（上限按解压后上限兜底，防止无界内存占用） */
    private static byte[] readZipBytes(InputStream is) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        var buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = is.read(buf)) != -1) {
            total += n;
            if (total > MAX_EXTRACTED_BYTES) {
                throw new IllegalArgumentException(
                    "zip 包体超过 " + (MAX_EXTRACTED_BYTES / 1024 / 1024) + "MB，请精简后重试");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 读取当前 zip 条目内容（受解压总大小上限约束，防 zip 炸弹单条目撑爆内存） */
    private static byte[] readEntryBytes(InputStream zis) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        var buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = zis.read(buf)) != -1) {
            total += n;
            if (total > MAX_EXTRACTED_BYTES) {
                throw new IllegalArgumentException(
                    "解压后单文件超过 " + (MAX_EXTRACTED_BYTES / 1024 / 1024) + "MB，请精简后重试");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 文本解码（UTF-8 优先，GBK 兜底）：用户普遍分不清 UTF-8 与 GBK（Windows 记事本另存 ANSI
     * 即 GBK），所以文本条目不能只认 UTF-8。顺序：
     * <ol>
     *   <li>严格 UTF-8 成功 → 原样返回（不重编码，无损）；</li>
     *   <li>失败且内容「看起来像文本」（无 NUL、除 \t\n\r\f 外无 C0 控制符）→ 严格 GBK 解码；</li>
     *   <li>仍失败 → 返回 null（调用方按二进制/未知编码处理：L2 原样保存，L4 跳过）。</li>
     * </ol>
     *
     * <p>先做文本嗅探再试 GBK，是因为 GBK 解码器能接受大量随机字节：不嗅探会把 PNG/JPEG
     * 等二进制「解码」成乱码文本。图片的压缩数据几乎必然含 NUL 或控制字节，会被挡在这里。
     */
    public static String decodeText(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        var utf8 = decodeStrict(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        if (!looksLikeText(bytes)) {
            return null;
        }
        return decodeStrict(bytes, java.nio.charset.Charset.forName("GBK"));
    }

    /** 严格解码（非法字节即失败返回 null），不做 U+FFFD 替换 */
    private static String decodeStrict(byte[] bytes, java.nio.charset.Charset charset) {
        try {
            return charset.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }

    /** 文本嗅探：无 NUL，且控制字符仅允许 \t \n \r \f（二进制通常很快命中 NUL/控制字节） */
    private static boolean looksLikeText(byte[] bytes) {
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v == 0) {
                return false;
            }
            if (v < 0x20 && v != '\t' && v != '\n' && v != '\r' && v != '\f') {
                return false;
            }
        }
        return true;
    }

    /** 清空目录内容但保留目录本身（编码回退重试前使用） */
    private static void clearDirectory(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (var p : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(dir)) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /**
     * 是否为 JDK 严格解码 zip 条目名失败抛出的 IllegalArgumentException。
     *
     * <p>判据是 cause 为 {@link java.nio.charset.CharacterCodingException}：UTF-8 轮 message 形如
     * {@code "malformed input off : N, length : M"}，GBK 轮则是
     * {@code "java.nio.charset.MalformedInputException: Input length = 1"}，不能只按前者匹配。
     * 业务校验（路径穿越等）抛出的 IllegalArgumentException 没有该 cause，不会被误改写。
     */
    private static boolean isEntryNameCodingError(IllegalArgumentException e) {
        return e.getCause() instanceof java.nio.charset.CharacterCodingException;
    }

    /**
     * 定位技能根目录：支持扁平（zip 根即含 SKILL.md）与包裹（一层或多层子目录含 SKILL.md）两种形态。
     *
     * <p>用户常「对整个文件夹压缩」，zip 内会带上一层甚至多层父目录（如 {@code skills/my-skill/SKILL.md}），
     * 故按层级自上而下（BFS）查找，返回<b>最浅</b>的含 SKILL.md 的目录（扁平优先），最多下探
     * {@link #MAX_WRAP_DEPTH} 层；找不到返回 null。同一目录下的子目录按路径排序，保证结果确定性。
     */
    public static LocatedSkill locateSkillRoot(Path tmpDir) throws IOException {
        var queue = new java.util.ArrayDeque<Path>();
        queue.add(tmpDir);
        for (int depth = 0; depth <= MAX_WRAP_DEPTH && !queue.isEmpty(); depth++) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                var dir = queue.poll();
                var skillMd = dir.resolve("SKILL.md");
                if (Files.isRegularFile(skillMd)) {
                    return new LocatedSkill(dir, skillMd);
                }
                if (depth == MAX_WRAP_DEPTH) {
                    continue; // 已达上限：不再入队更深层的子目录
                }
                var subDirs = new java.util.ArrayList<Path>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                    for (var entry : stream) {
                        if (Files.isDirectory(entry)
                                && !entry.getFileName().toString().startsWith(".")) {
                            subDirs.add(entry);
                        }
                    }
                }
                subDirs.sort(java.util.Comparator.comparing(Path::toString));
                queue.addAll(subDirs);
            }
        }
        return null;
    }

    /**
     * 从 SKILL.md frontmatter 提取 name 字段（简单解析：首个 --- 之间的 name: 行）。
     * 无 frontmatter/无 name 返回 null。
     *
     * <p>SKILL.md 文本解码走 {@link #decodeText}：UTF-8 优先、GBK 兜底（解压阶段已把 GBK 文本
     * 转成 UTF-8，这里兜底用于直接读目录的场景）。二进制/未知编码抛
     * {@link IllegalArgumentException}——zip 上传两条路径（L2 全局 / L4 用户）控制器都映射 400
     * （issue #115：此前经 {@code Files.readString} 抛 IOException 被包装成 500 upload_failed）。
     */
    public static String extractSkillName(Path skillMd) throws IOException {
        var bytes = Files.readAllBytes(skillMd);
        var content = decodeText(bytes);
        if (content == null) {
            throw new IllegalArgumentException(
                "SKILL.md 既不是 UTF-8 也不是 GBK 文本（二进制/未知编码文件不允许）");
        }
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

    /**
     * 递归删除目录（best-effort，不抛异常）。
     *
     * <p>逐个条目删除，单个条目失败只告警不中断（临时目录清理等场景无需感知失败）。
     * 调用方若需区分「是否真的删干净」，必须检查返回值——删除类接口据此对
     * 「只读挂载 / 权限不足」显式报错，不得吞掉失败后谎报成功。
     *
     * @return true = 目录已不存在（删除成功或本就不存在）；false = 目录仍存在（删除未完全成功）
     */
    public static boolean deleteRecursive(Path dir) {
        if (dir == null) {
            return true;
        }
        if (!Files.exists(dir)) {
            return true;
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
        boolean gone = !Files.exists(dir);
        if (!gone) {
            log.warn("deleteRecursive: {} 仍存在（只读挂载/权限不足？删除未成功，调用方须显式报错）", dir);
        }
        return gone;
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
