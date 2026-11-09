package io.agentmanager.framework.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.config.AgentManagerProperties;

/**
 * 宿主技能缓存（{@code <wsRoot>/.skills-cache}）过期 scope 目录定时清扫。
 *
 * <p>背景：SDK {@code MarketplaceStager} 按隔离 scope 把市场技能物化到
 * {@code .skills-cache/<scopeSegment>/<source-ns>/<skill>/}，其孤儿 GC 只清理<b>当前 scope 内</b>
 * 的过期技能——跨 scope 目录（每个会话一个）永不回收，宿主工作区随会话数无限膨胀
 * （生产实测一个工作区堆积数十个历史 scope 目录）。本服务按龄清扫这些目录。
 *
 * <p>安全性：
 * <ul>
 *   <li>宿主工作区是 <b>Pod 本地 stage 缓存</b>（非共享存储），各副本各自清扫自身目录，
 *       无跨副本竞态；删除后下轮该会话推理会由 SDK 重新 stage（内容确定、可重建）。</li>
 *   <li>按<b>子树最新 mtime</b>判定新鲜度：SDK 每轮 stage 会对技能目录 {@code touch}
 *       （{@code Files.setLastModifiedTime}），故正在使用/近期使用的 scope 目录最新 mtime 新，
 *       不会误删；保留期（默认 2 天）远大于单轮时长。</li>
 *   <li>只扫 {@code .skills-cache} 的<b>直接子目录</b>并逐目录删除，不触碰工作区其它内容。</li>
 * </ul>
 *
 * <p>配置：{@code AGENT_CLEANUP_SKILL_CACHE_GC_ENABLED}（默认 true）、
 * {@code AGENT_CLEANUP_SKILL_CACHE_RETENTION_DAYS}（默认 2，≤0 关闭）。
 */
@Service
public class SkillCacheGcService {

    private static final Logger log = LoggerFactory.getLogger(SkillCacheGcService.class);

    private final AgentManagerProperties props;

    public SkillCacheGcService(AgentManagerProperties props) {
        this.props = props;
    }

    /** 每 6 小时清扫一次（Pod 本地，各副本独立跑；幂等/按龄，无 leader 语义）。 */
    @Scheduled(cron = "0 30 */6 * * ?")
    public void gc() {
        var cleanup = props.cleanup();
        if (cleanup == null || !cleanup.skillCacheGcEnabled()) {
            return;
        }
        int retentionDays = cleanup.skillCacheRetentionDays();
        int maxTotalMb = cleanup.skillCacheMaxTotalMb();
        if (retentionDays <= 0 && maxTotalMb <= 0) {
            return;
        }
        Path cacheDir = skillsCacheDir();
        int removed = gc(cacheDir, retentionDays, maxTotalMb);
        if (removed > 0) {
            log.info("[skill-cache-gc] removed {} stale/oversized scope dir(s) under {} (retention={}d, max={}MB)",
                removed, cacheDir, retentionDays, maxTotalMb);
        }
    }

    /** 宿主 {@code <base>/.agentscope/workspace/.skills-cache}（与 WorkspaceInitializer 同源布局）。 */
    private Path skillsCacheDir() {
        return Path.of(props.resolvedWorkspaceBaseDir())
            .resolve(".agentscope").resolve("workspace").resolve(".skills-cache");
    }

    /**
     * 清扫 {@code cacheDir} 直接子目录：① 按龄——最新 mtime 早于保留期的删除；
     * ② 按容量（P1 护栏，{@code maxTotalMb} &gt; 0 时启用）——总大小超限后按最新 mtime
     * <b>最旧优先</b>逐目录删除直至回落限内。时间护栏防「不再使用的会话」堆积，
     * 容量护栏防「2 天窗口内高会话量写满宿主盘」（66MB/会话 scope，两者叠加生效）。
     *
     * @return 删除的 scope 目录数（package-private 供单测直调，不触发调度）
     */
    static int gc(Path cacheDir, int retentionDays, int maxTotalMb) {
        if (cacheDir == null || !Files.isDirectory(cacheDir)) {
            return 0;
        }
        List<Path> scopeDirs;
        try (var stream = Files.list(cacheDir)) {
            scopeDirs = stream.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            log.warn("[skill-cache-gc] list {} failed: {}", cacheDir, e.getMessage());
            return 0;
        }
        int removed = 0;
        if (retentionDays > 0) {
            long cutoff = System.currentTimeMillis() - Duration.ofDays(retentionDays).toMillis();
            for (Path scopeDir : scopeDirs) {
                try {
                    if (newestMtime(scopeDir) >= cutoff) {
                        continue; // 近期 stage/touch 过：保留
                    }
                    deleteRecursively(scopeDir);
                    removed++;
                } catch (Exception e) {
                    // 单目录失败不影响其余（下次清扫重试）
                    log.warn("[skill-cache-gc] failed to remove {}: {}", scopeDir, e.getMessage());
                }
            }
        }
        if (maxTotalMb > 0) {
            removed += gcByCapacity(cacheDir, maxTotalMb);
        }
        return removed;
    }

    /**
     * 容量护栏：统计现存 scope 目录大小，超限按「子树最新 mtime 最旧优先」逐目录删除
     * 直至总大小回落限内。统计失败（部分目录不可读）按已统计部分裁决——宁可多删不炸盘。
     */
    private static int gcByCapacity(Path cacheDir, int maxTotalMb) {
        long maxBytes = (long) maxTotalMb * 1024 * 1024;
        record ScopeSize(Path dir, long bytes, long newestMtime) {}
        var sized = new ArrayList<ScopeSize>();
        long total = 0;
        try (var stream = Files.list(cacheDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                try {
                    long bytes = sizeOf(dir);
                    long mtime = newestMtime(dir);
                    sized.add(new ScopeSize(dir, bytes, mtime));
                    total += bytes;
                } catch (Exception e) {
                    log.warn("[skill-cache-gc] size of {} failed: {}", dir, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[skill-cache-gc] capacity list {} failed: {}", cacheDir, e.getMessage());
            return 0;
        }
        if (total <= maxBytes) {
            return 0;
        }
        sized.sort(Comparator.comparingLong(ScopeSize::newestMtime)); // 最旧优先
        int removed = 0;
        for (ScopeSize scope : sized) {
            if (total <= maxBytes) {
                break;
            }
            try {
                deleteRecursively(scope.dir());
                total -= scope.bytes();
                removed++;
                log.info("[skill-cache-gc] capacity exceeded (max={}MB): removed {} ({}MB)",
                    maxTotalMb, scope.dir().getFileName(), scope.bytes() / 1024 / 1024);
            } catch (Exception e) {
                log.warn("[skill-cache-gc] failed to remove {}: {}", scope.dir(), e.getMessage());
            }
        }
        return removed;
    }

    /** 子树字节总量（含目录条目开销不精确统计，只累加 regular file 大小）。 */
    private static long sizeOf(Path root) throws IOException {
        long total = 0;
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                try {
                    if (Files.isRegularFile(p)) {
                        total += Files.size(p);
                    }
                } catch (IOException ignore) {
                    // 单个条目 stat 失败忽略
                }
            }
        }
        return total;
    }

    /** 子树内最新 lastModifiedTime（毫秒）；含目录与文件，任一近期 touch 即视为新鲜。 */
    private static long newestMtime(Path root) throws IOException {
        long newest = 0;
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                try {
                    long m = Files.getLastModifiedTime(p).toMillis();
                    if (m > newest) {
                        newest = m;
                    }
                } catch (IOException ignore) {
                    // 单个条目 stat 失败忽略（不因此判为新鲜/过期）
                }
            }
        }
        return newest;
    }

    /** 逆序遍历（叶到根）删除整棵子树。 */
    private static void deleteRecursively(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
