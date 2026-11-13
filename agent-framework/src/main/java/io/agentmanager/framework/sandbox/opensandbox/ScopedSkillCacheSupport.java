package io.agentmanager.framework.sandbox.opensandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.service.WorkspaceReader;

/**
 * scoped {@code .skills-cache} 投放的宿主侧共享原语（tar 物化与软链农场两路同源，SSOT）：
 * 内容指纹、禁用目录解析、容器内禁用清理命令、shell 安全相对路径。禁止任一路径重写
 * 这些规则——指纹跨模式比对（降级↔farm 翻转稳态，D-11）与禁用过滤（B-2）的正确性
 * 都依赖两侧实现逐字节一致。
 */
final class ScopedSkillCacheSupport {

    private static final Logger log = LoggerFactory.getLogger(ScopedSkillCacheSupport.class);

    private ScopedSkillCacheSupport() {
    }

    /**
     * scoped 投放指纹（P0-1，tar/farm 两路同源）：内容身份组合
     * {@code hash(包内源目录 stat 指纹) + "|" + L4 revision}，目录缺失返回空串
     * （空串也参与比对——空→空即“两侧都无内容”的稳态）。
     * stat 失败返回空串以外的随机值会破坏幂等，这里直接抛给上层走慢路径。
     */
    static String contentFingerprint(OpenSandboxFilesystemSpec spec, WorkspaceReader reader,
                                     String uid, Path scopeDir) throws Exception {
        // 指纹源（缺口④修正）：stage 副本目录的 mtime 各副本不同（各自 staging 时刻），
        // 直接对它做 stat 指纹则跨副本指纹天然不等、共享层永不命中。改为<b>内容身份</b>组合：
        //   指纹 = hash(包内源目录 stat 指纹 + "|" + L4 revision)
        // - 包内源（configDir/skills）生产为同 PVC 只读挂载——跨副本同文件同 mtime，指纹一致；
        // - L4 revision 在 KV 共享（用户技能变化自增）——覆盖用户级差异；
        // - stage 目录是 SDK 对上述两源的确定性物化（writeIfChanged），只用于「有无可投内容」
        //   的空载荷判定，不参与指纹。
        // 本地/单副本语义不变：任一源变化 → 指纹变 → 慢路径重投。
        if (!Files.isDirectory(scopeDir)) {
            return "";
        }
        String packageFp = "";
        var pkgDir = spec != null ? spec.packageSkillsDir() : null;
        if (pkgDir != null && Files.isDirectory(pkgDir)) {
            packageFp = PackageSourceFingerprint.of(pkgDir);
        }
        String revision = "";
        if (reader != null && uid != null && !uid.isBlank()) {
            var rev = reader.readUserSkillRevision(uid);
            revision = rev == null ? "" : rev;
        }
        return packageFp + "|" + revision;
    }

    /**
     * 禁用技能目录解析（oa-1110-repro-fixes-design §2.1a）：包级禁用名 + 用户级禁用，
     * 返回相对 scopeDir 的路径集。checker 缺失/目录缺失返回空集；用户级判定异常按
     * 未禁用处理（fail-soft，不中断投放）。
     */
    static Set<Path> disabledSkillDirs(SkillDisabledChecker checker, String uid, Path scopeDir) {
        if (checker == null || !Files.isDirectory(scopeDir)) {
            return Set.of();
        }
        var disabled = new LinkedHashSet<Path>();
        Set<String> pkgDisabled;
        try {
            pkgDisabled = checker.packageDisabled();
        } catch (Exception e) {
            pkgDisabled = Set.of();
        }
        try (var nsStream = Files.list(scopeDir)) {
            for (var ns : nsStream.toList()) {
                if (!Files.isDirectory(ns)) {
                    continue;
                }
                try (var skillStream = Files.list(ns)) {
                    for (var skillDir : skillStream.toList()) {
                        if (!Files.isDirectory(skillDir)) {
                            continue;
                        }
                        String name = skillDir.getFileName().toString();
                        if (pkgDisabled.contains(name)) {
                            disabled.add(scopeDir.relativize(skillDir));
                            continue;
                        }
                        try {
                            if (checker.isUserDisabled(uid, name)) {
                                disabled.add(scopeDir.relativize(skillDir));
                            }
                        } catch (Exception e) {
                            log.warn("[sandbox-open] disabled-check failed for {}: {}", name, e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[sandbox-open] resolve disabled skill dirs failed: {}", e.getMessage());
            return Set.of();
        }
        return disabled;
    }

    /**
     * 容器内禁用技能目录的清理命令段（当前 scope 内；空集返回空串）。路径单引号包裹，
     * 含单引号/控制字符/{@code ..} 的相对路径直接跳过（防御性：技能名来自 SDK stage 落盘）。
     */
    static String disabledRmCmd(String scope, Set<Path> disabledDirs, String workspaceRoot) {
        if (disabledDirs.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        String scopeRoot = workspaceRoot + "/" + SkillCacheScope.CACHE_DIR + "/" + scope;
        for (var rel : disabledDirs) {
            String joined = rel.toString().replace('\\', '/');
            if (!isShellSafeRelPath(joined)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" && ");
            }
            sb.append("rm -rf '").append(scopeRoot).append("/").append(joined).append("'");
        }
        return sb.toString();
    }

    /** 相对路径是否可安全内插 shell 单引号段：拒绝空白/控制字符/单引号/路径穿越 */
    static boolean isShellSafeRelPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/")) {
            return false;
        }
        if (path.contains("'") || path.contains("..")) {
            return false;
        }
        return path.chars().allMatch(c -> c > 32);
    }
}
