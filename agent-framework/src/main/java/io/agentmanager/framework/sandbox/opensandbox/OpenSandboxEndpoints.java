package io.agentmanager.framework.sandbox.opensandbox;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxEndpoint;
import com.alibaba.opensandbox.sandbox.infrastructure.factory.AdapterFactory;

/**
 * execd endpoint 端口保真（issue #514）：useServerProxy 模式下 endpoint 由 OpenSandbox
 * server 依据请求 Host 反射生成（server 侧 {@code request.base_url}），前置 nginx 若用
 * {@code proxy_set_header Host $host} 会丢端口，endpoint 变成 {@code host/v1/sandboxes/{id}/proxy/44772}，
 * SDK 按 80 端口发起全部 execd 请求 → 落到 nginx 默认站点 HTML 404。
 *
 * <p>判据只针对「server proxy 路径且 authority 缺端口且 host 与配置的 server 一致」：
 * 直连 endpoint（容器 IP:端口）、显式端口 endpoint、外部 eip（host 不同）一律原样保留，
 * 正常部署（k8s server :80 / docker server 带端口）零行为变化。
 */
public final class OpenSandboxEndpoints {

    private static final Logger log = LoggerFactory.getLogger(OpenSandboxEndpoints.class);

    static final int EXECD_PORT = 44772;

    static final int EGRESS_PORT = 18080;

    private static final AtomicBoolean PORT_DROP_WARNED = new AtomicBoolean();

    private OpenSandboxEndpoints() {
    }

    record EndpointHandle(Sandbox sandbox, String execdEndpoint) {}

    static EndpointHandle withPortFidelity(Sandbox handle, String serverUrl) {
        if (handle == null) {
            return null;
        }
        SandboxEndpoint raw;
        SandboxEndpoint rawEgress;
        try {
            raw = handle.getEndpoint(EXECD_PORT);
            rawEgress = handle.getEndpoint(EGRESS_PORT);
        } catch (Exception e) {
            log.debug("[sandbox-open] execd endpoint lookup failed, skip port fidelity check: {}", e.getMessage());
            return new EndpointHandle(handle, null);
        }
        String rawEndpoint = raw == null ? null : raw.getEndpoint();
        if (rawEndpoint == null || rawEndpoint.isBlank() || rawEgress == null) {
            return new EndpointHandle(handle, rawEndpoint);
        }
        String corrected = correct(rawEndpoint, serverUrl);
        if (corrected.equals(rawEndpoint)) {
            return new EndpointHandle(handle, rawEndpoint);
        }
        if (PORT_DROP_WARNED.compareAndSet(false, true)) {
            log.warn("[sandbox-open] execd endpoint lost its port behind server proxy ({} -> {}); "
                    + "fix the fronting proxy Host header (proxy_set_header Host $http_host) "
                    + "or set [server] eip on the OpenSandbox server",
                rawEndpoint, corrected);
        }
        var factory = new AdapterFactory(handle.httpClientProvider());
        var endpoint = new SandboxEndpoint(corrected, raw.getHeaders());
        var egress = factory.createEgressStack(new SandboxEndpoint(
            correct(rawEgress.getEndpoint(), serverUrl), rawEgress.getHeaders()));
        Sandbox sandbox = new Sandbox(handle.getId(), factory.createSandboxes(),
            factory.createFilesystem(endpoint), factory.createCommands(endpoint),
            factory.createHealth(endpoint), factory.createMetrics(endpoint),
            egress.getEgress(), egress.getCredentialVault(),
            factory.createIsolatedSessions(endpoint), null,
            handle.httpClientProvider(), handle.diagnostics());
        return new EndpointHandle(sandbox, corrected);
    }

    public static String correct(String endpoint, String serverUrl) {
        if (endpoint == null || endpoint.isBlank() || serverUrl == null || serverUrl.isBlank()) {
            return endpoint;
        }
        int slash = endpoint.indexOf('/');
        String authority = slash < 0 ? endpoint : endpoint.substring(0, slash);
        String path = slash < 0 ? "" : endpoint.substring(slash);
        if (!isServerProxyPath(path) || portOf(authority) != null) {
            return endpoint;
        }
        String serverAuthority = serverAuthority(serverUrl);
        if (serverAuthority == null
                || !hostOf(authority).equalsIgnoreCase(hostOf(serverAuthority))
                || serverAuthority.equals(authority)) {
            return endpoint;
        }
        return serverAuthority + path;
    }

    private static boolean isServerProxyPath(String path) {
        return path.contains("/sandboxes/") && path.contains("/proxy/");
    }

    private static String serverAuthority(String serverUrl) {
        String value = serverUrl.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        return value.isEmpty() ? null : value;
    }

    private static String hostOf(String authority) {
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            return close < 0 ? authority : authority.substring(0, close + 1);
        }
        int colon = authority.lastIndexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }

    private static String portOf(String authority) {
        int colon;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0 || close + 1 >= authority.length() || authority.charAt(close + 1) != ':') {
                return null;
            }
            colon = close + 1;
        } else {
            colon = authority.lastIndexOf(':');
            if (colon < 0) {
                return null;
            }
        }
        String port = authority.substring(colon + 1);
        return port.isEmpty() ? null : port;
    }
}
