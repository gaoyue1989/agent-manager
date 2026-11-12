package io.agentmanager.framework.sandbox.opensandbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 目录 stat 指纹的单一实现（SSOT）：对目录做 {@code relPath|mtime|size} 排序遍历摘要，
 * SHA-256 取 16 hex。消费方：
 * <ul>
 *   <li>{@link OpenSandbox} 包内源目录（configDir/skills）指纹（PVC 同挂载跨副本一致）；</li>
 *   <li>{@link SkillBundleService} 版本指纹（叠加服务级禁用集 overlay，B-2）。</li>
 * </ul>
 *
 * <p>继承 stat 指纹固有口径：mtime/size 不变而内容变化的场景不敏感（PVC tar 发布下稳定，
 * 可接受）；目录不可读等异常返回每次调用不同的 {@code ERR-<nanos>}（安全侧：恒判定为已变化）。
 * 目录缺失视为空源（稳定摘要，仅对"无技能目录"的服务给出稳定空版本）。
 */
public final class PackageSourceFingerprint {

    private PackageSourceFingerprint() {
    }

    /** 纯 stat 指纹（16 hex），无附加行 */
    public static String of(Path dir) {
        return digest(dir, List.of());
    }

    /**
     * stat 指纹 + 附加行统一摘要（16 hex）：附加行（如 {@code disabled|<name>} 禁用集 overlay）
     * 与 stat 行合并排序后一并入哈希，任一变化 → 指纹变。
     */
    public static String digest(Path dir, Collection<String> extraLines) {
        try {
            var lines = new ArrayList<String>();
            if (Files.isDirectory(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.filter(Files::isRegularFile).forEach(p ->
                        lines.add(dir.relativize(p) + "|" + mtimeOf(p) + "|" + sizeOf(p)));
                }
            }
            lines.addAll(extraLines);
            lines.sort(String::compareTo);
            var md = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                md.update((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return hex16(md.digest());
        } catch (Exception e) {
            return "ERR-" + System.nanoTime();
        }
    }

    private static long mtimeOf(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return -1;
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return -1;
        }
    }

    static String hex16(byte[] digest) {
        var sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.substring(0, 16);
    }
}
