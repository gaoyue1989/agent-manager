package io.agentmanager.framework.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 外部访问基址解析：从代理层透传的 {@code X-Forwarded-*} 头推导「客户端实际使用的」
 * 访问前缀与基址（subpath-routing-design §3.1 契约）。
 *
 * <p>契约来源：path 时代的 ingress-nginx {@code x-forwarded-prefix} 注解与 platform-router
 * 的 {@code proxy_set_header X-Forwarded-Prefix} 语义同构——代理层剥掉前缀转发、并把外部
 * 前缀透传给后端，后端据此修正对外下发的自引用 URL（agent-card url、SSE file_ready 的
 * download_url、endpoints 广告）。
 *
 * <p>安全边界：返回值仅用于响应体内 URL 字符串拼接，不参与路由、鉴权、过滤逻辑；
 * 直连（无代理头）时两个方法均返回 null，调用方回落现状行为。
 */
public final class ExternalUrlSupport {

    private ExternalUrlSupport() {}

    /** 前缀规范化：去空白、多值取首、剥全部尾斜杠；"/" 与空值归一为 null（无前缀） */
    public static String normalizePrefix(String raw) {
        if (raw == null) return null;
        var s = raw.trim();
        int comma = s.indexOf(',');
        if (comma >= 0) s = s.substring(0, comma).trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() || "/".equals(s) ? null : s;
    }

    /** 规范化外部前缀（如 "/agent/foo"）；无 X-Forwarded-Prefix 头返回 null */
    public static String forwardedPrefix(HttpServletRequest req) {
        return normalizePrefix(req.getHeader("X-Forwarded-Prefix"));
    }

    /**
     * 外部基址（如 "http://host:30080/agent/foo"，无尾斜杠）；无前缀头返回 null。
     *
     * <p>scheme/host 优先取代理链上游声明（X-Forwarded-Proto / X-Forwarded-Host，
     * 多值取首），缺失回落本跳请求的观察值——保证 TLS 卸载等场景不丢 https。
     */
    public static String externalBase(HttpServletRequest req) {
        var prefix = forwardedPrefix(req);
        if (prefix == null) return null;
        var scheme = firstToken(req.getHeader("X-Forwarded-Proto"));
        if (scheme == null) scheme = req.getScheme();
        var host = firstToken(req.getHeader("X-Forwarded-Host"));
        if (host == null) host = req.getHeader("Host");
        if (host == null || host.isBlank()) {
            host = req.getServerName() + ":" + req.getServerPort();
        }
        return scheme + "://" + host + prefix;
    }

    /** 头值多值（逗号分隔）取首 token；空白视为缺失 */
    private static String firstToken(String header) {
        if (header == null) return null;
        var s = header.trim();
        int comma = s.indexOf(',');
        if (comma >= 0) s = s.substring(0, comma).trim();
        return s.isEmpty() ? null : s;
    }
}
