package io.agentmanager.framework.sandbox.opensandbox;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 沙箱瞬时故障分类器（oa-1110-repro-fixes-design §2.3）：
 * <ul>
 *   <li>create 重试（SandboxLifecycleManager）：仅瞬时类（超时/连接/网关不可达）重试，
 *       配额/身份/参数类失败照常直抛；</li>
 *   <li>SSE error 可读化（ChatStreamController）：cause 链识别 SDK 沙箱异常 →
 *       结构化 error 帧（code=sandbox_create_failed + retryable + 中文文案），
 *       替代 "Lifecycle acquire failed: Request timed out: timeout" 生硬透出。</li>
 * </ul>
 *
 * <p><b>包装层次</b>：SDK 2.0.3 的 {@code com.alibaba.opensandbox...SandboxTimeoutException}
 * 等在 harness 侧被 {@code OpenSandboxClient.create} 的 catch(Exception) 包成
 * harness SandboxException（message 前缀 "Lifecycle acquire failed:"），两层无类型映射——
 * 因此除 instanceof 外还对 message 形态（"Request timed out"）做兜底识别。
 */
public final class SandboxFailureClassifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 分类结果：NONE = 非沙箱瞬时故障 */
    public enum Kind {
        /** 建箱/请求超时（上游排队或容量紧张） */
        TIMEOUT,
        /** 连接失败（网络/代理不可达） */
        CONNECTION,
        /** 网关不可达（502/503/504，数据面端点失联的典型形态） */
        UNREACHABLE,
        /** 非瞬时故障（配额/身份/参数等）或非沙箱异常 */
        NONE
    }

    private SandboxFailureClassifier() {
    }

    /**
     * cause 链分类：SDK 沙箱异常（Timeout/Connection/Api-5xx）优先，其次网络 IO 异常，
     * 最后 harness 包装形态的 message 兜底（"Request timed out"）。
     */
    public static Kind kindOf(Throwable root) {
        for (Throwable t = root; t != null; t = t.getCause()) {
            if (t instanceof com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxTimeoutException
                    || t instanceof com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxReadyTimeoutException) {
                return Kind.TIMEOUT;
            }
            if (t instanceof com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxConnectionException) {
                return Kind.CONNECTION;
            }
            if (t instanceof com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException api) {
                int status = api.getStatusCode() != null ? api.getStatusCode() : 0;
                if (status == 502 || status == 503 || status == 504) {
                    return Kind.UNREACHABLE;
                }
            }
            if (t instanceof java.net.SocketTimeoutException) {
                return Kind.TIMEOUT;
            }
            if (t instanceof java.net.ConnectException) {
                return Kind.CONNECTION;
            }
            String msg = t.getMessage();
            if (msg != null && msg.contains("Request timed out")) {
                return Kind.TIMEOUT;
            }
        }
        return Kind.NONE;
    }

    /** create 重试判定：仅瞬时类（TIMEOUT/CONNECTION/UNREACHABLE） */
    public static boolean transientForCreate(Throwable t) {
        return kindOf(t) != Kind.NONE;
    }

    /** 瞬时类故障的中文可读文案（SSE 帧 error 字段） */
    public static String readableMessage(Kind kind) {
        return switch (kind) {
            case TIMEOUT -> "沙箱创建超时（上游排队或容量紧张），请稍后重试；若持续失败请联系管理员";
            case CONNECTION -> "沙箱连接失败，请稍后重试；若持续失败请联系管理员";
            case UNREACHABLE -> "沙箱服务暂不可达（数据面连接失败），请稍后重试；若持续失败请联系管理员";
            case NONE -> "";
        };
    }

    /**
     * 沙箱类异常的 SSE error 帧载荷：{@code {"type":"error","error":<中文文案>,
     * "code":"sandbox_create_failed","retryable":true,"detail":<原始信息>}}。
     * 非沙箱类（kindOf == NONE）返回 null（调用方保持原样透出）。
     * 组装失败兜底为纯文本帧（可用性优先，同 UserQuotaExhaustedException.sseFrameJson）。
     */
    public static String sseErrorFrame(Throwable root) {
        Kind kind = kindOf(root);
        if (kind == Kind.NONE) {
            return null;
        }
        String readable = readableMessage(kind);
        String detail = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("type", "error");
        m.put("error", readable);
        m.put("code", "sandbox_create_failed");
        m.put("retryable", true);
        m.put("detail", detail);
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            try {
                return "{\"type\":\"error\",\"error\":" + MAPPER.writeValueAsString(readable) + "}";
            } catch (Exception ignored) {
                return "{\"type\":\"error\",\"error\":\"sandbox_create_failed\"}";
            }
        }
    }
}
