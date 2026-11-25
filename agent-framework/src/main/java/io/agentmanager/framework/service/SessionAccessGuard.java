package io.agentmanager.framework.service;

import org.springframework.stereotype.Component;

/**
 * 会话归属裁决（issue #158，IDOR 修复）：所有按 sessionId 操作的端点
 * （history / 详情 / 删除 / PATCH / llm-calls / subscribe / status / confirm / chat）
 * 在读写前必须校验 session_user 中该会话与请求者的归属关系。
 *
 * <p>裁决口径：
 * <ul>
 *   <li>请求者身份 = {@code X-User-Id} 头 &gt; 匿名 {@code debug-user}
 *       （与 ChatStreamController.chat 的 userId 解析同口径：无头请求视作匿名调试身份，
 *        只能访问无身份会话，不能触碰真实用户会话——fail-closed）；</li>
 *   <li>保留命名空间（用户身份 {@code u-} 前缀 / {@code debug-user}、网关运行时 {@code gw-}）
 *       → 拒绝：这些值不是合法会话键，客户端不得以受害者 userId 认领会话（issue #558）；</li>
 *   <li>session_user 无映射行 → 放行（存量/legacy 会话无归属记录，行为与修复前一致；
 *        正常链路创建的会话必写映射，见 ChatStreamController / A2AController 的 upsert）；</li>
 *   <li>有映射且 owner ≠ 请求者 → 拒绝，端点统一返回 404（不泄露会话存在性）。</li>
 *   <li>浏览器接管/VNC 等实时控制端点走 {@link #establishedOwner}：无映射行/占位行
 *       不构成归属证明（fail-closed，issue #559）。</li>
 * </ul>
 *
 * <p>静态工具形态：ThreadController / ConfirmController / ChatStreamController 已注入
 * SessionUserStore，直接传入复用；SessionStreamController 经构造注入 store 后同样调用。
 */
@Component
public class SessionAccessGuard {

    /** 无 X-User-Id 头时的匿名身份（与 chat 的缺省 userId 一致） */
    public static final String ANONYMOUS_USER = "debug-user";

    private final SessionUserStore sessionUserStore;

    public SessionAccessGuard(SessionUserStore sessionUserStore) {
        this.sessionUserStore = sessionUserStore;
    }

    /** 解析请求者身份：头优先，缺省回落匿名调试身份 */
    public String resolveRequester(String headerUserId) {
        return (headerUserId == null || headerUserId.isBlank()) ? ANONYMOUS_USER : headerUserId;
    }

    /** 实例便捷方法：当前装配的 store 上做归属裁决 */
    public boolean ownedBy(String sessionId, String headerUserId) {
        return ownedBy(sessionUserStore, sessionId, headerUserId);
    }

    /**
     * 归属裁决：true = 放行。
     *
     * @param sessionId     已 PathSafe.sanitize 的会话 ID
     * @param headerUserId  X-User-Id 头原值（可空）
     */
    public static boolean ownedBy(SessionUserStore store, String sessionId, String headerUserId) {
        if (SessionKeyNamespace.isReservedSessionId(sessionId)) {
            return false;
        }
        var requester = (headerUserId == null || headerUserId.isBlank()) ? ANONYMOUS_USER : headerUserId;
        return isOwner(store.findUserIdBySession(sessionId), requester);
    }

    /**
     * 归属判定：true = 放行。
     *
     * @param owner      session_user 中该会话的归属用户（可空）
     * @param requester  请求者身份
     */
    public static boolean isOwner(String owner, String requester) {
        // 无映射行放行（存量会话）；占位行（PATCH 先于首聊经 upsertModel/upsertRemark
        // 补建，user_id='unknown'，MOD6 场景）不构成归属主张，同样放行——首个真实身份
        // 的 chat 会经 upsert 认领该行；其余有映射要求严格相等
        return owner == null || SessionUserStore.PLACEHOLDER_USER.equals(owner)
            || owner.equals(requester);
    }

    /**
     * 已建立归属的会话所有者（fail-closed，issue #559）：浏览器接管/VNC 等实时控制端点
     * 只面向有明确归属且归属者 = 请求者的会话。
     *
     * <p>与 {@link #ownedBy} 的差别：无映射行/占位行不构成归属证明，一律返回 null；
     * 保留命名空间（用户身份/网关运行时键）同样拒绝。
     *
     * @return 会话归属用户；未建立归属返回 null
     */
    public static String establishedOwner(SessionUserStore store, String sessionId, String headerUserId) {
        if (SessionKeyNamespace.isReservedSessionId(sessionId)) {
            return null;
        }
        var requester = (headerUserId == null || headerUserId.isBlank()) ? ANONYMOUS_USER : headerUserId;
        var owner = store.findUserIdBySession(sessionId);
        if (owner == null || SessionUserStore.PLACEHOLDER_USER.equals(owner) || !owner.equals(requester)) {
            return null;
        }
        return owner;
    }
}
