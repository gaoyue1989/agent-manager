package io.agentmanager.framework.sandbox.opensandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * bundle 产物磁盘缓存（D-3）：确定性打包保证同 version 字节一致，缓存即内容寻址——
 * 按 {@code <version>.tar.gz + <version>.sha256} 落盘，keep-2 LRU（按 mtime，超出删除
 * 最旧者及其 sidecar）。查询以 tar.gz + sidecar 齐备且 sha 合法为命中条件，任一缺失
 * （写入中途崩溃残留）按未命中处理，调用方重建自愈。
 */
class SkillBundleCache {

    private static final Logger log = LoggerFactory.getLogger(SkillBundleCache.class);
    private static final int KEEP_VERSIONS = 2;

    private final Path dir;

    SkillBundleCache(Path dir) {
        this.dir = dir;
    }

    record Entry(Path file, String sha256, long size) {
    }

    Optional<Entry> get(String version) {
        var bundle = dir.resolve(version + ".tar.gz");
        var sidecar = dir.resolve(version + ".sha256");
        if (!Files.isRegularFile(bundle) || !Files.isRegularFile(sidecar)) {
            return Optional.empty();
        }
        try {
            var sha = Files.readString(sidecar, StandardCharsets.UTF_8).trim();
            if (sha.length() != 64) {
                return Optional.empty();
            }
            return Optional.of(new Entry(bundle, sha, Files.size(bundle)));
        } catch (IOException e) {
            log.warn("[skills-bundle] cache read failed for {}: {}", version, e.getMessage());
            return Optional.empty();
        }
    }

    void put(String version, byte[] gz, String sha256) throws IOException {
        Files.createDirectories(dir);
        writeFileAtomic(dir.resolve(version + ".tar.gz"), gz);
        writeFileAtomic(dir.resolve(version + ".sha256"),
            sha256.getBytes(StandardCharsets.UTF_8));
        evictBeyondKeep();
    }

    private void writeFileAtomic(Path target, byte[] data) throws IOException {
        var tmp = Files.createTempFile(dir, "bundle-", ".tmp");
        try {
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    private void evictBeyondKeep() throws IOException {
        record Dated(Path file, long mtime) {
        }
        var bundles = new ArrayList<Dated>();
        try (var stream = Files.list(dir)) {
            for (var p : stream.filter(p -> p.getFileName().toString().endsWith(".tar.gz"))
                    .toList()) {
                try {
                    bundles.add(new Dated(p, Files.getLastModifiedTime(p).toMillis()));
                } catch (IOException e) {
                    bundles.add(new Dated(p, Long.MIN_VALUE));
                }
            }
        }
        bundles.sort(Comparator.comparingLong(Dated::mtime).reversed());
        for (int i = KEEP_VERSIONS; i < bundles.size(); i++) {
            var victim = bundles.get(i).file();
            log.info("[skills-bundle] cache evict (keep-{}): {}", KEEP_VERSIONS,
                victim.getFileName());
            Files.deleteIfExists(victim);
            Files.deleteIfExists(dir.resolve(
                victim.getFileName().toString().replace(".tar.gz", ".sha256")));
        }
    }
}
