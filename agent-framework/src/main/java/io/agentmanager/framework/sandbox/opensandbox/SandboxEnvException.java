package io.agentmanager.framework.sandbox.opensandbox;

/**
 * 沙箱 env 设置异常：携带结构化错误码与 HTTP 状态，供
 * {@link io.agentmanager.framework.controller.SandboxEnvController} 统一映射为
 * {@code {"error": ..., "message": ...}} 响应（docs/sandbox-env-update-design.md §4.4）。
 */
public class SandboxEnvException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;

    public SandboxEnvException(String errorCode, int httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
