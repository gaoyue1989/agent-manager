package io.agentmanager.framework.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 会话键命名空间裁决（issue #558）：session_user 的 session_id 与业务用户身份共用字符串空间，
 * 客户端可控的 sessionId 若占用用户身份/网关运行时命名空间，即可把「按 userId 反查会话」的
 * 链路（SessionKeyResolver / Viking 归档 / MCP / 沙箱守卫）指向攻击者抢注的行。
 *
 * <p>三类键的唯一权威定义：
 * <ul>
 *   <li>用户身份：平台网关注入的 {@code u-{uid}}（前缀 {@code u-}）与匿名 {@code debug-user}
 *       ——只能作为 user_id 使用，禁止作为 session_id 认领；</li>
 *   <li>网关会话：{@code gw-} + SHA-256(canonicalKey) 前 6 字节 hex（12 字符），Channel 链路
 *       RuntimeContext.sessionId 的派生形态——内部运行时键，客户端 sessionId 不得占用；</li>
 *   <li>业务会话：其余值，即 session_user.session_id 的合法空间。</li>
 * </ul>
 */
public final class SessionKeyNamespace {

    private static final Pattern USER_IDENTITY = Pattern.compile("^u-\\d+$");
    private static final String GATEWAY_SESSION_PREFIX = "gw-";
    private static final Pattern GATEWAY_SESSION = Pattern.compile("^gw-[0-9a-f]{12}$");

    private SessionKeyNamespace() {
    }

    public static boolean isUserIdentity(String value) {
        return value != null && isUserIdentityLowerCase(value.toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean isUserIdentityLowerCase(String lowered) {
        return USER_IDENTITY.matcher(lowered).matches()
            || SessionAccessGuard.ANONYMOUS_USER.equals(lowered);
    }

    /** Channel 链路网关派生的会话键（严格形态，用于识别 peer 链路） */
    public static boolean isGatewaySessionId(String value) {
        return value != null && GATEWAY_SESSION.matcher(value).matches();
    }

    /** 保留命名空间：客户端 sessionId 不得认领（用户身份 + 网关运行时键） */
    public static boolean isReservedSessionId(String value) {
        if (value == null) {
            return false;
        }
        var lowered = value.toLowerCase(java.util.Locale.ROOT);
        return lowered.startsWith(GATEWAY_SESSION_PREFIX) || isUserIdentityLowerCase(lowered);
    }

    /** Channel 链路网关会话键派生（与 HarnessGateway 一致） */
    static String channelGatewaySessionId(String peerId) {
        return gatewaySessionId("chatui|r:" + peerId + "|x:agentId=main");
    }

    /** 4db16ba~#87 修复前的 MAIN 形态共享网关会话 id（canonicalKey = "chatui|x:agentId=main"） */
    static final String LEGACY_SHARED_GW_SESSION_ID = gatewaySessionId("chatui|x:agentId=main");

    private static String gatewaySessionId(String canonicalKey) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(canonicalKey.getBytes(StandardCharsets.UTF_8));
            return GATEWAY_SESSION_PREFIX + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
