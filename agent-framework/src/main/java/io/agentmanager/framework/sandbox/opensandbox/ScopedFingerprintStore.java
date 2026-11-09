package io.agentmanager.framework.sandbox.opensandbox;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.lettuce.core.RedisException;

/**
 * scoped 技能缓存指纹的<b>跨副本共享层</b>（first-llm-latency 缺口④，多副本稳态零投放）。
 *
 * <p>背景：Pod 级指纹表（{@link OpenSandboxFilesystemSpec} 三表）不跨副本——同会话轮转到
 * 新副本时 gate 全 miss，50MB/2000f 场景重复付整套慢路径（build+gzip+hydrate + Pod 级
 * 载荷缓存同样 miss）。把 {@code sandboxId|scope → 指纹} 放共享 Redis 后，任意副本首次
 * 读到即可短路（容器本身已有该 scope 内容——指纹记录的就是「已投放进该沙箱代」的事实）。
 *
 * <p>语义与降级：
 * <ul>
 *   <li>读取未命中/Redis 不可达 → 返回 null（调用方回落 Pod 级表 → 慢路径），fail-open；</li>
 *   <li>写入失败仅告警（漏写的后果=他副本多走一次慢路径，与无共享层等价）；</li>
 *   <li>TTL 7 天：沙箱代生命周期上界（Server TTL/GC 远小于此），过期即视为未知。</li>
 * </ul>
 *
 * <p>key 布局：{@code <agentRedisPrefix>sbx:scopedfp:{sandboxId}|{scope}}（经门面前缀统一，
 * 多服务共享 Redis 时天然隔离——对齐 AGENT_REDIS_PREFIX 既有约定）。值=宿主 scope 目录
 * stat 指纹（内容无关用户数据，非敏感）。
 */
public class ScopedFingerprintStore {

    private static final Logger log = LoggerFactory.getLogger(ScopedFingerprintStore.class);

    /** 指纹记录 TTL：沙箱代生命周期上界（Server TTL 默认 30min~60min；7 天远超且防泄漏积累） */
    private static final Duration TTL = Duration.ofDays(7);

    /** null 实例（无 Redis / 装配关闭时）——所有操作 no-op，行为回到纯 Pod 级 */
    public static final ScopedFingerprintStore NOOP = new ScopedFingerprintStore(null);

    private final io.agentmanager.framework.redis.RedisConnectionFacade facade;

    public ScopedFingerprintStore(io.agentmanager.framework.redis.RedisConnectionFacade facade) {
        this.facade = facade;
    }

    private String key(String sandboxId, String scope) {
        return "sbx:scopedfp:" + sandboxId + "|" + scope;
    }

    /**
     * 读某沙箱代某 scope 的已投放指纹。
     *
     * @return 指纹值；未命中/不可用/未装配返回 null（调用方按「未知走慢路径」处理）
     */
    public String get(String sandboxId, String scope) {
        if (facade == null || sandboxId == null || sandboxId.isBlank() || scope == null || scope.isBlank()) {
            return null;
        }
        try {
            return facade.sync().get(key(sandboxId, scope));
        } catch (RedisException | IllegalStateException e) {
            // fail-open：共享层不可用回落 Pod 级语义（多付一次慢路径，不出正确性问题）
            log.debug("[scoped-fp-store] get failed ({}|{}): {}", sandboxId, scope, e.getMessage());
            return null;
        }
    }

    /** 记录已投放指纹（投放成功后调用）；失败仅告警 */
    public void put(String sandboxId, String scope, String fingerprint) {
        if (facade == null || sandboxId == null || sandboxId.isBlank() || scope == null
                || scope.isBlank() || fingerprint == null) {
            return;
        }
        try {
            facade.sync().setex(key(sandboxId, scope), TTL.toSeconds(), fingerprint);
        } catch (RedisException | IllegalStateException e) {
            log.warn("[scoped-fp-store] put failed ({}|{}): {}", sandboxId, scope, e.getMessage());
        }
    }
}
