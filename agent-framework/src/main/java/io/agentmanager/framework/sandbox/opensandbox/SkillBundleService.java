package io.agentmanager.framework.sandbox.opensandbox;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.Deflater;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.service.SkillManageService;
import io.agentmanager.framework.service.SkillZipSupport;

/**
 * skills-syncd 版本协商服务（sandbox-skills-syncd-design §4.3/§6.1）：对包技能目录
 * （{@link SkillManageService#getSkillsDir()}，即 /config/skills）产出 stat 指纹版本
 * 与确定性 bundle。
 *
 * <p>版本指纹 = {@link PackageSourceFingerprint}（relPath|mtime|size 排序遍历）+ 服务级
 * 禁用集 overlay（B-2：全局禁用变更 → 指纹变 → 守护重拉新 bundle），singleflight +
 * TTL 内进程缓存（D-4）。bundle 打包确定性：条目按路径排序、mtime 固定 0、gzip 无时间戳
 * ——同 version 在任意副本产出字节一致（X-Bundle-Sha256 头稳定）；被服务级禁用的技能
 * 内容不进 bundle。产物按 version 落盘磁盘缓存（D-3：keep-2 LRU），缓存目录不可用时
 * 退化为内存即时打包直出。
 */
@Service
public class SkillBundleService {

    private static final Logger log = LoggerFactory.getLogger(SkillBundleService.class);
    static final Duration VERSION_TTL = Duration.ofSeconds(5);
    /** 指纹/枚举计算失败时的版本前缀（computeVersion 全 catch 输出约定）：版本端点据此 503、
     * 农场判定据此归 {@code degraded_endpoint_down}——ERR-* 不得作为有效版本流出到守护侧 */
    public static final String ERROR_VERSION_PREFIX = "ERR-";

    private final SkillManageService skillManageService;
    private final SkillBundleCache cache;
    /** 解析后的 bundle 磁盘缓存目录（D-3 keep-2 LRU；issue #358 默认落 workspace 卷内） */
    private final Path bundleCacheDir;
    private final Duration ttl;
    private final Clock clock;
    private final Object versionLock = new Object();
    private final Object buildLock = new Object();
    private volatile CachedVersion cachedVersion;

    @Autowired
    public SkillBundleService(SkillManageService skillManageService, SandboxConfig sandboxConfig,
            io.agentmanager.framework.config.AgentManagerProperties properties) {
        this(skillManageService, Path.of(resolveBundleCacheDir(sandboxConfig.skillsBundleCacheDir(), properties)),
            VERSION_TTL, Clock.systemUTC());
    }

    /**
     * bundle 磁盘缓存目录解析（issue #358）：显式配置（{@code SKILLSYNC_BUNDLE_CACHE_DIR}）
     * 优先；未配置回落 workspace 卷内 {@code {workspaceBase}/tmp/skills-bundle-cache}——
     * 业务 Pod /tmp 不可写实测（issue #345 同源结论，{@code AgentManagerProperties
     * .resolvedSpoolDir} 同款回落），旧默认 /tmp 会让 D-3 keep-2 LRU 磁盘缓存静默失效
     * （每次版本同步退化为内存即时打包 + WARN 刷屏）。
     */
    private static String resolveBundleCacheDir(String configured,
            io.agentmanager.framework.config.AgentManagerProperties properties) {
        return configured == null || configured.isBlank()
            ? Path.of(properties.resolvedWorkspaceBaseDir(), "tmp", "skills-bundle-cache").toString()
            : configured;
    }

    SkillBundleService(SkillManageService skillManageService, Path bundleCacheDir,
                       Duration ttl, Clock clock) {
        this.skillManageService = skillManageService;
        this.cache = new SkillBundleCache(bundleCacheDir);
        this.bundleCacheDir = bundleCacheDir;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** 解析后的 bundle 磁盘缓存目录（D-3 keep-2 LRU 落点；issue #358 默认落 workspace 卷内） */
    Path bundleCacheDir() {
        return bundleCacheDir;
    }

    /**
     * 版本指纹 + bundle 概览。{@code skills} 为与 bundle 打包同源的 enabled 技能名有序清单
     * （skills-syncd 端点 manifest：farm 建链候选来自它，宿主 stage 目录遍历退役；
     * version 响应加字段对守护透明，D-9 可选字段预留）。
     */
    public record SkillVersion(String version, int skillCount, int fileCount, long sizeBytes,
                               List<String> skills) {
    }

    public record BundlePayload(long size, String sha256, BundleStream stream) {

        @FunctionalInterface
        public interface BundleStream {
            InputStream open() throws IOException;
        }
    }

    private record BundleFile(Path file, String rel, long size, boolean executable) {
    }

    private record Packed(byte[] gz, String sha256, int fileCount) {
    }

    private record CachedVersion(SkillVersion version, long atMillis) {
    }

    /** 当前版本指纹（D-4：TTL 内命中进程缓存，并发首个计算、其余共享结果） */
    public SkillVersion currentVersion() {
        var hit = cachedVersion;
        if (isFresh(hit)) {
            return hit.version();
        }
        synchronized (versionLock) {
            hit = cachedVersion;
            if (isFresh(hit)) {
                return hit.version();
            }
            var computed = new CachedVersion(computeVersion(), clock.millis());
            cachedVersion = computed;
            return computed.version();
        }
    }

    /**
     * 取指定版本 bundle：仅当前指纹版本可取（不匹配返回 empty → 端点 404）；
     * 磁盘缓存命中走文件流，未命中确定性打包一次并落盘（不可落盘则内存直出）。
     */
    public Optional<BundlePayload> bundle(String version) throws IOException {
        var current = currentVersion();
        if (!current.version().equals(version)) {
            return Optional.empty();
        }
        var hit = cache.get(version);
        if (hit.isPresent()) {
            return Optional.of(cachedPayload(hit.get()));
        }
        synchronized (buildLock) {
            hit = cache.get(version);
            if (hit.isPresent()) {
                return Optional.of(cachedPayload(hit.get()));
            }
            var packed = pack(bundleFiles(disabledSet()));
            try {
                cache.put(version, packed.gz(), packed.sha256());
                log.info("[skills-bundle] packed version {} ({} files, {} bytes, sha {})",
                    version, packed.fileCount(), packed.gz().length, packed.sha256());
            } catch (IOException e) {
                log.warn("[skills-bundle] disk cache unavailable ({}), streaming in-memory",
                    e.getMessage());
            }
            return Optional.of(new BundlePayload(packed.gz().length, packed.sha256(),
                () -> new ByteArrayInputStream(packed.gz())));
        }
    }

    private boolean isFresh(CachedVersion hit) {
        return hit != null && clock.millis() - hit.atMillis() < ttl.toMillis();
    }

    private SkillVersion computeVersion() {
        try {
            var disabled = disabledSet();
            var overlay = disabled.stream().sorted().map(n -> "disabled|" + n).toList();
            var version = PackageSourceFingerprint.digest(skillManageService.getSkillsDir(),
                overlay);
            var files = bundleFiles(disabled);
            long total = 0;
            for (var f : files) {
                total += f.size();
            }
            var skills = files.stream()
                .map(f -> f.rel().split("/")[0])
                .distinct()
                .sorted()
                .toList();
            return new SkillVersion(version, skills.size(), files.size(), total, skills);
        } catch (Exception e) {
            log.warn("[skills-bundle] version fingerprint failed: {}", e.getMessage());
            return new SkillVersion(ERROR_VERSION_PREFIX + System.nanoTime(), 0, 0, 0, List.of());
        }
    }

    private Set<String> disabledSet() {
        return skillManageService.getDisabledSet();
    }

    private BundlePayload cachedPayload(SkillBundleCache.Entry entry) {
        return new BundlePayload(entry.size(), entry.sha256(),
            () -> Files.newInputStream(entry.file()));
    }

    private List<BundleFile> bundleFiles(Set<String> disabled) throws IOException {
        var dir = skillManageService.getSkillsDir();
        if (!Files.isDirectory(dir)) {
            // 空源语义对齐 PackageSourceFingerprint（issue #397）：无技能包（目录缺失或为普通
            // 文件）返回空名单——稳定空版本 + 合法空 bundle，守护/农场健康稳态；真正不可读
            // （目录存在但 list 抛 IOException，如权限拒绝）才上抛走 ERR-* 输出约定
            return List.of();
        }
        var files = new ArrayList<BundleFile>();
        try (var top = Files.list(dir)) {
            // 打包清单经 canonicalDirIndex 统一裁决（issue #504）：目录名字典序首个保留、
            // 同规范名冲突目录整体跳过（WARN）——tar 无重复条目，同 version 跨副本字节一致。
            // 打包规范名 = SKILL.md frontmatter name（issue #398）：SDK 渲染路径与
            // farm 建链/解包顶层三方对齐；无 frontmatter/不安全名回落目录名。
            // version.skills 清单由 rel 顶层段派生，自动同口径。
            // 禁用集同为规范名口径（issue #480，getDisabledSet 已归一化）
            for (var entry : SkillZipSupport.canonicalDirIndex(top.filter(Files::isDirectory).toList())
                    .entrySet()) {
                var canonical = entry.getKey();
                var skillDir = entry.getValue();
                if (disabled.contains(canonical)) {
                    continue;
                }
                try (var walk = Files.walk(skillDir)) {
                    for (var f : walk.filter(Files::isRegularFile).toList()) {
                        var rel = dir.relativize(f).toString().replace('\\', '/');
                        var name = skillDir.getFileName().toString();
                        if (!canonical.equals(name)) {
                            rel = canonical + rel.substring(name.length());
                        }
                        files.add(new BundleFile(f, rel, Files.size(f), isExecutable(f)));
                    }
                }
            }
        }
        files.sort(Comparator.comparing(BundleFile::rel));
        return files;
    }

    // 源文件可执行位（POSIX fs 探测，非 POSIX fs 视为不可执行）：bundle 确定性不变——
    // /config 只读挂载下 stat 恒定，同版本字节恒一致。
    private static boolean isExecutable(Path f) {
        try {
            return Files.getPosixFilePermissions(f).contains(PosixFilePermission.OWNER_EXECUTE);
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    private Packed pack(List<BundleFile> files) throws IOException {
        var bos = new ByteArrayOutputStream(1 << 20);
        var params = new GzipParameters();
        params.setModificationTime(0);
        params.setCompressionLevel(Deflater.BEST_SPEED);
        try (var tar = new TarArchiveOutputStream(
                new GzipCompressorOutputStream(bos, params), 1 << 20)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (var f : files) {
                var entry = new TarArchiveEntry(f.rel());
                entry.setSize(f.size());
                entry.setModTime(0);
                entry.setMode(f.executable() ? 0755 : 0644);
                entry.setUserId(0);
                entry.setGroupId(0);
                entry.setUserName("");
                entry.setGroupName("");
                tar.putArchiveEntry(entry);
                try (var in = Files.newInputStream(f.file())) {
                    in.transferTo(tar);
                }
                tar.closeArchiveEntry();
            }
        }
        var gz = bos.toByteArray();
        return new Packed(gz, sha256Hex(gz), files.size());
    }

    private static String sha256Hex(byte[] data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(data);
            var sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
