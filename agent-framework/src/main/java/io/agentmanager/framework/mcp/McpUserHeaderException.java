package io.agentmanager.framework.mcp;

/**
 * MCP 用户级 header 注入失败（映射 key 缺值且 on-missing=deny，或映射值无法作为 HTTP header）。
 *
 * <p>错误信息只含 header 名 / meta key 名与失败原因，绝不包含值——该异常可能经
 * ToolErrorHandlingMiddleware 进入模型可见的 ToolMessage。
 */
public class McpUserHeaderException extends RuntimeException {

    public McpUserHeaderException(String message) {
        super(message);
    }
}
