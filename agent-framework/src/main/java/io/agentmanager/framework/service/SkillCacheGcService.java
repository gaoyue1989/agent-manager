package io.agentmanager.framework.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
        if (retentionDays <= 0) {
            return;
        }
        Path cacheDir = skillsCacheDir();
        int removed = gc(cacheDir, retentionDays);
        if (removed > 0) {
            log.info("[skill-cache-gc] removed {} stale scope dir(s) under {} (retention={}d)",
                removed, cacheDir, retentionDays);
        }
    }

    /** 宿主 {@code <base>/.agentscope/workspace/.skills-cache}（与 WorkspaceInitializer 同源布局）。 */
    private Path skillsCacheDir() {
        return Path.of(props.resolvedWorkspaceBaseDir())
            .resolve(".agentscope").resolve("workspace").resolve(".skills-cache");
    }

    /**
     * 清扫 {@code cacheDir} 下最新 mtime 早于保留期的直接子目录。
     *
     * @return 删除的 scope 目录数（package-private 供单测直调，不触发调度）
     */
    static int gc(Path cacheDir, int retentionDays) {
        if (retentionDays <= 0 || cacheDir == null || !Files.isDirectory(cacheDir)) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - Duration.ofDays(retentionDays).toMillis();
        int removed = 0;
        List<Path> scopeDirs;
        try (var stream = Files.list(cacheDir)) {
            scopeDirs = stream.filter(Files::isDirectory).toList();
        } catch (IOException e) {
            log.warn("[skill-cache-gc] list {} failed: {}", cacheDir, e.getMessage());
            return 0;
        }
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
        return removed;
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
