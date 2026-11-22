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
 * 载荷缓存同样 miss）。把指纹放共享 Redis 后，任意副本首次读到即可短路。
 *
 * <p>单槽语义（issue #393）：key = {@code sbx:scopedfp:{sandboxId}}（经门面 {@code facade.key}
 * 统一加前缀），值 = {@code scope|mode|指纹}，put 原子覆盖。单槽 = 「本沙箱代最后成功收敛
 * scope」的跨副本 SSOT——容器内 prune 只保留当前 scope（{@code .skills-cache} 同代仅一个
 * 有效 scope 目录），记账必须与之对齐：Pod 切 scope 收敛后槽被新 scope 覆盖即旧 scope 记账
 * 自然失效，其他副本不得再凭陈旧记录伪造稳态跳过收敛（per-scope 独立 key 的旧布局做不到，
 * 已废弃，存量 ≤7 天 TTL 自然消亡）。scope 段无 {@code |} 由 {@link SkillCacheScope#sanitize}
 * 保证（仅 {@code [A-Za-z0-9._-]} + sha256hex 后缀），指纹段可含 {@code |}（farm 指纹
 * =version|revision）按首个 {@code |} 拆分即无损。
 *
 * <p>语义与降级：
 * <ul>
 *   <li>读取未命中/scope 不匹配/Redis 不可达 → 返回 null（调用方回落 Pod 级表 → 慢路径），
 *       fail-open；</li>
 *   <li>写入失败仅告警（漏写的后果=他副本多走一次慢路径，与无共享层等价）；</li>
 *   <li>TTL 7 天：沙箱代生命周期上界（Server TTL/GC 远小于此），过期即视为未知。</li>
 * </ul>
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

    private String key(String sandboxId) {
        return facade.key("sbx:scopedfp:" + sandboxId);
    }

    /** 读单槽原始值（fail-open）：槽缺失/不可用/未装配/入参非法返回 null（未知） */
    private String slot(String sandboxId) {
        if (facade == null || sandboxId == null || sandboxId.isBlank()) {
            return null;
        }
        try {
            return facade.sync().get(key(sandboxId));
        } catch (RedisException | IllegalStateException e) {
            // fail-open：共享层不可用回落 Pod 级语义（多付一次慢路径，不出正确性问题）
            log.debug("[scoped-fp-store] get failed ({}): {}", sandboxId, e.getMessage());
            return null;
        }
    }

    /**
     * 读单槽当前记录的 scope 段——「同代现役 scope」的跨副本裁决值：他副本 prune+切 scope
     * 收敛后本方法即返回新 scope，权威否定其他 Pod 仍持有的陈旧 Pod 级记录（Pod 级两表
     * 无跨副本失效机制，见 {@code OpenSandboxFilesystemSpec} 交叉核验）。
     *
     * @return 现役 scope；槽缺失/不可用/未装配返回 null（未知，调用方不得据此清除本地信念）
     */
    public String slotScope(String sandboxId) {
        String slot = slot(sandboxId);
        if (slot == null) {
            return null;
        }
        int sep = slot.indexOf('|');
        return sep < 0 ? null : slot.substring(0, sep);
    }

    /**
     * 读某沙箱代某 scope 的已投放指纹：取单槽值，按首个 {@code |} 拆分——scope 段≠请求 scope
     * 返回 null（槽已被其他 scope 覆盖=陈旧记账），否则返回余段（{@code mode|指纹}）。
     *
     * @return 记账值；未命中/不可用/未装配返回 null（调用方按「未知走慢路径」处理）
     */
    public String get(String sandboxId, String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        String slot = slot(sandboxId);
        if (slot == null) {
            return null;
        }
        int sep = slot.indexOf('|');
        if (sep < 0 || !slot.substring(0, sep).equals(scope)) {
            return null;
        }
        return slot.substring(sep + 1);
    }

    /** 记录已投放指纹（投放成功后调用）：单槽 {@code scope|value} 原子覆盖；失败仅告警 */
    public void put(String sandboxId, String scope, String value) {
        if (facade == null || sandboxId == null || sandboxId.isBlank() || scope == null
                || scope.isBlank() || value == null) {
            return;
        }
        try {
            facade.sync().setex(key(sandboxId), TTL.toSeconds(), scope + "|" + value);
        } catch (RedisException | IllegalStateException e) {
            log.warn("[scoped-fp-store] put failed ({}|{}): {}", sandboxId, scope, e.getMessage());
        }
    }
}
