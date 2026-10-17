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
 *   <li>session_user 无映射行 → 放行（存量/legacy 会话无归属记录，行为与修复前一致；
 *        正常链路创建的会话必写映射，见 ChatStreamController / A2AController 的 upsert）；</li>
 *   <li>有映射且 owner ≠ 请求者 → 拒绝，端点统一返回 404（不泄露会话存在性）。</li>
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
        var requester = (headerUserId == null || headerUserId.isBlank()) ? ANONYMOUS_USER : headerUserId;
        var owner = store.findUserIdBySession(sessionId);
        // 无映射行放行（存量会话）；有映射要求严格相等
        return owner == null || owner.equals(requester);
    }
}
