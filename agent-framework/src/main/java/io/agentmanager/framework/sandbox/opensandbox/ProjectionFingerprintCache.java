package io.agentmanager.framework.sandbox.opensandbox;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;

/**
 * 投影新鲜度检查 stat 指纹缓存（设计 ①，docs/design/sandbox-skill-sync-optimization-design.md §3）。
 *
 * <p>背景：{@code OpenSandbox.refreshProjectionIfStale} 每 call 一次比对宿主投影内容，
 * 现状直接调 SDK {@code WorkspaceProjectionApplier.build()}——它会读投影根全部文件字节、
 * sha256、并在堆里构建完整 tar，hash 一致时整个丢弃（实测 4.5MB 工作区 17.9ms、18.5MB
 * 47.9ms + 每次同量堆分配）。本类用不读文件内容的 stat 指纹
 * （相对路径 + mtime 毫秒 + size，排序后 sha256）作缓存键：指纹未变 → 宿主投影内容未变
 * → 直接复用缓存的 SDK hash 与 {@code state.workspaceProjectionHash} 比对，跳过 build。
 *
 * <p>正确性边界：指纹的盲点是「原位改内容且 mtime 与 size 都不变」。宿主投影根的写者
 * （SDK MarketplaceStager 的 writeIfChanged / WorkspaceInitializer / OAF reload 重写）
 * 全部会更新 mtime，ext4/PVC 的 mtime 为纳秒级，生产写者集合不存在该盲点；即便人为构造，
 * 后果止步于漏一次投影补水合（下个指纹变化点自愈），不损坏数据。
 *
 * <p>进程级缓存（pod 本地）：宿主工作区本来就是 Pod 本地 stage 缓存，无跨副本一致性问题。
 */
public final class ProjectionFingerprintCache {

    private ProjectionFingerprintCache() {
    }

    private record Entry(String statFingerprint, String sdkHash) {}

    /** key = sourceRoot + '\0' + join(includeRoots)；单 pod 实际只有 1 个工作区，超限整体清空 */
    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final int MAX_ENTRIES = 16;

    /**
     * 与 SDK collectProjectedFiles 同文件集的 stat 指纹：
     * 每个 includeRoot（存在即参与）下所有 regular file（含隐藏文件，Files.walk 不跟随软链），
     * 按相对路径排序后对 {@code relPath|mtimeMillis|size} 逐行摘要。
     * 任一文件 stat 失败抛 IOException（调用方回落 build() 慢路径，不缓存）。
     */
    public static String statFingerprint(WorkspaceSpec spec) throws Exception {
        var lines = new ArrayList<String>();
        for (var entryObj : spec.getEntries().values()) {
            if (!(entryObj instanceof WorkspaceProjectionEntry entry)) {
                continue;
            }
            if (entry.getSourceRoot() == null || entry.getSourceRoot().isBlank()) {
                continue;
            }
            var root = Path.of(entry.getSourceRoot()).toAbsolutePath().normalize();
            if (entry.getIncludeRoots() == null) {
                continue;
            }
            for (var include : entry.getIncludeRoots()) {
                if (include == null || include.isBlank()) {
                    continue;
                }
                var p = root.resolve(include).normalize();
                if (!p.startsWith(root) || !Files.exists(p)) {
                    continue;
                }
                if (Files.isRegularFile(p)) {
                    lines.add(statLine(root, p));
                } else if (Files.isDirectory(p)) {
                    try (var stream = Files.walk(p)) {
                        stream.filter(Files::isRegularFile)
                            .forEach(x -> lines.add(statLine(root, x)));
                    }
                }
            }
        }
        lines.sort(Comparator.naturalOrder());
        var md = MessageDigest.getInstance("SHA-256");
        for (var line : lines) {
            md.update((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return hex(md.digest());
    }

    private static String statLine(Path root, Path p) {
        try {
            var attrs = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
            return root.relativize(p) + "|" + attrs.lastModifiedTime().toMillis() + "|" + attrs.size();
        } catch (Exception e) {
            // stat 失败让指纹值随机化，指纹必然不命中 → 调用方走 build() 慢路径
            return root.relativize(p) + "|ERR|" + System.nanoTime();
        }
    }

    /**
     * 稳态短路判定：spec 指纹与缓存一致 且 缓存的 SDK hash 与 state 当前 hash 一致 → 跳过 build。
     * 任一不满足返回 false（调用方走 build() 慢路径）。
     */
    public static boolean skipBuild(WorkspaceSpec spec, String stateHash) {
        var key = cacheKey(spec);
        if (key == null || stateHash == null) {
            return false;
        }
        var cached = CACHE.get(key);
        if (cached == null) {
            return false;
        }
        try {
            if (!statFingerprint(spec).equals(cached.statFingerprint())) {
                // 投影内容已变：失效条目，走 build() 后由 record 回填
                CACHE.remove(key, cached);
                return false;
            }
        } catch (Exception e) {
            // stat 失败：保守走慢路径
            return false;
        }
        // 指纹未变 → 宿主投影内容未变 → SDK hash 也不变，与 state 比对即可
        return cached.sdkHash().equals(stateHash);
    }

    /** build() 成功后回填缓存（内部自算 stat 指纹：慢路径多付一次 ~3-5ms walk，可忽略） */
    public static void record(WorkspaceSpec spec, String sdkHash) {
        var key = cacheKey(spec);
        if (key == null || sdkHash == null) {
            return;
        }
        try {
            if (CACHE.size() >= MAX_ENTRIES && !CACHE.containsKey(key)) {
                CACHE.clear();
            }
            CACHE.put(key, new Entry(statFingerprint(spec), sdkHash));
        } catch (Exception e) {
            // 指纹计算失败不缓存：下次仍走慢路径（与无缓存等价）
        }
    }

    private static String cacheKey(WorkspaceSpec spec) {
        if (spec == null || spec.getEntries() == null) {
            return null;
        }
        for (var entryObj : spec.getEntries().values()) {
            if (entryObj instanceof WorkspaceProjectionEntry entry
                && entry.getSourceRoot() != null && !entry.getSourceRoot().isBlank()) {
                return entry.getSourceRoot() + "\0"
                    + (entry.getIncludeRoots() == null ? "" : String.join(",", entry.getIncludeRoots()));
            }
        }
        return null;
    }

    private static String hex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 测试辅助：清空缓存（生产不调用） */
    static void clearForTest() {
        CACHE.clear();
    }
}
