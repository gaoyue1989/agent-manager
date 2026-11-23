package io.agentmanager.framework.sandbox.opensandbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.lettuce.core.RedisException;

/**
 * 换代 reset 记账（「(会话键, sandboxId) 已执行过 reset」）的跨副本共享层（issue #413 评审 g1）。
 *
 * <p>背景：Pod 本地换代索引（{@link OpenSandboxFilesystemSpec#isInjectedOnSandbox} 的
 * {@code sessionInjectedSandbox}，LRU(256)）不跨副本、不跨 Pod 重启、可被 LRU 淘汰——丢标记后
 * 同代容器在 middleware 误判换代：DB 行回滚 pending → invalidatePendingUploadMemo →
 * injectPendingUploads 以<b>原始上传字节</b>覆写容器内已被 agent 修改的上传件（静默数据丢失，
 * 且「任意请求可落任意副本」下必然发生）。把「该代已 reset」事实放共享 Redis 后，会话轮转到
 * 新副本/重启/淘汰后命中即跳过 reset（保留 DB 驱动方向，仅去重）。
 *
 * <p>key = {@code sbx:resetmark:{sha256(sessionId)}:{sha256(sandboxId)}}（经门面
 * {@code facade.key} 统一加前缀；两段全摘要——sessionId 来自前端可含任意字符，摘要组键免
 * 分隔符歧义），值恒 {@code "1"}。
 *
 * <p>语义与降级（与 {@link ScopedFingerprintStore} 同姿态）：
 * <ul>
 *   <li>读取未命中/不可达/未装配 → false（=未记账，走 DB 驱动 reset——重复 reset 是幂等的
 *       （条件 UPDATE + 同字节覆写），漏 reset 才会丢注入，宁可重不可漏）；</li>
 *   <li>写入失败仅告警（后果=他副本误判换代多一次 reset+重注入，与无共享层等价）；</li>
 *   <li>TTL 7 天：对齐 sandbox_binding 保留期（AGENT_CLEANUP_SANDBOX_BINDING_RETENTION_DAYS
 *       默认 7 天）——绑定行已清理则该 (会话, 沙箱代) 不复存在，标记自然消亡防泄漏。</li>
 * </ul>
 */
public class ResetMarkStore {

    private static final Logger log = LoggerFactory.getLogger(ResetMarkStore.class);

    /** 记账 TTL：对齐绑定行保留期上界（沙箱代随绑定行消亡，7 天远超 TTL/GC 生命周期） */
    private static final Duration TTL = Duration.ofDays(7);

    /** null 实例（无 Redis / 未装配时）——所有操作 no-op，行为回到纯 Pod 级 */
    public static final ResetMarkStore NOOP = new ResetMarkStore(null);

    private final io.agentmanager.framework.redis.RedisConnectionFacade facade;

    public ResetMarkStore(io.agentmanager.framework.redis.RedisConnectionFacade facade) {
        this.facade = facade;
    }

    /** 该 (会话, 沙箱代) 是否已执行过换代 reset：命中=跨副本/重启后同代不重 reset */
    public boolean marked(String sessionId, String sandboxId) {
        if (facade == null || sessionId == null || sessionId.isBlank()
                || sandboxId == null || sandboxId.isBlank()) {
            return false;
        }
        try {
            return "1".equals(facade.sync().get(key(sessionId, sandboxId)));
        } catch (RedisException | IllegalStateException e) {
            // fail-open：共享层不可用回落 Pod 级语义（多一次 reset+重注入，不出漏注入）
            log.debug("[reset-mark-store] get failed: {}", e.getMessage());
            return false;
        }
    }

    /** 记录已完成换代 reset（middleware reset 决策落定后调用）；失败仅告警 */
    public void mark(String sessionId, String sandboxId) {
        if (facade == null || sessionId == null || sessionId.isBlank()
                || sandboxId == null || sandboxId.isBlank()) {
            return;
        }
        try {
            facade.sync().setex(key(sessionId, sandboxId), TTL.toSeconds(), "1");
        } catch (RedisException | IllegalStateException e) {
            log.warn("[reset-mark-store] put failed: {}", e.getMessage());
        }
    }

    private String key(String sessionId, String sandboxId) {
        return facade.key("sbx:resetmark:" + sha256Hex(sessionId) + ":" + sha256Hex(sandboxId));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
