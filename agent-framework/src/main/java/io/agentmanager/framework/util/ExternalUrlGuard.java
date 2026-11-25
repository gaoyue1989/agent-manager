package io.agentmanager.framework.util;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * 外部交付 URL 白名单裁决（present_url 登记 + /files/{id} 代理下载共用的单一真相源）。
 *
 * <p>裁决规则：URL 解析为 {@link URI} 后，scheme/authority 与前缀完全一致，且
 * 规范化（{@code normalize()}）后的路径落在前缀路径之下（段边界前缀）才放行；通过则
 * 返回规范化 URI（登记与请求一律用它——上游收到即裁决目标，无解析分歧）。路径中的
 * {@code .}/{@code ..} 段由 normalize + 前缀裁决处理（{@code .} 消除仍在界内则放行、
 * {@code ..} 越界必拒）；normalize 不解码 percent-encoding，穿越类编码段
 * （{@code %2e}/{@code %2f}/{@code %5c}）与 {@code \} 显式拒绝——字符串前缀比较会被
 * {@code <prefix>/../} 变体越出白名单（issue #483 SSRF），裁决必须基于解析与规范化后
 * 的 URL 而非原始字符串。
 */
public final class ExternalUrlGuard {

    private ExternalUrlGuard() {}

    /** 白名单裁决：通过返回规范化 URI，拒绝返回 null */
    public static URI allowlisted(String url, List<String> prefixes) {
        if (url == null || prefixes == null || prefixes.isEmpty()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        var scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return null;
        }
        var host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || !hasNoEncodedTraversal(uri.getRawPath())) {
            return null;
        }
        int port = effectivePort(scheme, uri.getPort());
        for (var prefix : prefixes) {
            URI p;
            try {
                p = URI.create(prefix);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!scheme.equalsIgnoreCase(p.getScheme())
                    || p.getHost() == null
                    || p.getRawUserInfo() != null
                    || port != effectivePort(p.getScheme(), p.getPort())
                    || !hostsMatch(host, p.getHost())) {
                continue;
            }
            var pPath = p.getRawPath();
            if (pPath == null || pPath.isEmpty() || "/".equals(pPath)) {
                return uri.normalize();
            }
            if (!hasNoEncodedTraversal(pPath)) {
                continue;
            }
            var base = pPath.endsWith("/") ? pPath.substring(0, pPath.length() - 1) : pPath;
            var path = uri.normalize().getRawPath();
            if (path.equals(base) || path.startsWith(base + "/")) {
                return uri.normalize();
            }
        }
        return null;
    }

    /** 路径编码规范性：normalize 不解码 percent-encoding，穿越类编码与反斜杠须显式拒绝 */
    private static boolean hasNoEncodedTraversal(String rawPath) {
        if (rawPath == null) {
            return true;
        }
        if (rawPath.indexOf('\\') >= 0) {
            return false;
        }
        var lower = rawPath.toLowerCase(Locale.ROOT);
        return !lower.contains("%2e") && !lower.contains("%2f") && !lower.contains("%5c");
    }

    private static int effectivePort(String scheme, int port) {
        if (port != -1) {
            return port;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static boolean hostsMatch(String a, String b) {
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        if (a.startsWith("[") && a.endsWith("]") && b.startsWith("[") && b.endsWith("]")) {
            try {
                return InetAddress.getByName(a.substring(1, a.length() - 1))
                    .equals(InetAddress.getByName(b.substring(1, b.length() - 1)));
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }
}
