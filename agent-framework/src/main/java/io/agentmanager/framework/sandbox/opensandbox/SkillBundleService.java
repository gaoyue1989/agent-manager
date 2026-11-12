package io.agentmanager.framework.sandbox.opensandbox;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.stereotype.Service;

import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.service.SkillManageService;

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
    private final Duration ttl;
    private final Clock clock;
    private final Object versionLock = new Object();
    private final Object buildLock = new Object();
    private volatile CachedVersion cachedVersion;

    public SkillBundleService(SkillManageService skillManageService, SandboxConfig sandboxConfig) {
        this(skillManageService, Path.of(sandboxConfig.skillsBundleCacheDir()),
            VERSION_TTL, Clock.systemUTC());
    }

    SkillBundleService(SkillManageService skillManageService, Path bundleCacheDir,
                       Duration ttl, Clock clock) {
        this.skillManageService = skillManageService;
        this.cache = new SkillBundleCache(bundleCacheDir);
        this.ttl = ttl;
        this.clock = clock;
    }

    public record SkillVersion(String version, int skillCount, long sizeBytes) {
    }

    public record BundlePayload(long size, String sha256, BundleStream stream) {

        @FunctionalInterface
        public interface BundleStream {
            InputStream open() throws IOException;
        }
    }

    private record BundleFile(Path file, String rel, long size) {
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
            return new SkillVersion(version, files.size(), total);
        } catch (Exception e) {
            log.warn("[skills-bundle] version fingerprint failed: {}", e.getMessage());
            return new SkillVersion(ERROR_VERSION_PREFIX + System.nanoTime(), 0, 0);
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
        var files = new ArrayList<BundleFile>();
        try (var top = Files.list(dir)) {
            for (var skillDir : top.filter(Files::isDirectory).toList()) {
                var name = skillDir.getFileName().toString();
                if (name.startsWith(".") || disabled.contains(name)) {
                    continue;
                }
                try (var walk = Files.walk(skillDir)) {
                    for (var f : walk.filter(Files::isRegularFile).toList()) {
                        files.add(new BundleFile(f,
                            dir.relativize(f).toString().replace('\\', '/'), Files.size(f)));
                    }
                }
            }
        }
        files.sort(Comparator.comparing(BundleFile::rel));
        return files;
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
                entry.setMode(0644);
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
