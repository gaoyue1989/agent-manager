package io.agentmanager.framework.mcp;

import java.util.List;
import java.util.Map;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * 可原地换芯的 MCP 连接句柄（issue #348 断联恢复）：delegate 可被 {@link #swap}
 * 替换的 {@link McpClientWrapper}。McpToolRegistrar 的共享连接缓存（sharedWrappers）
 * 缓存本句柄而非裸连接——单例 agent 与全部 per-session 会话的 toolkit 注册的都是
 * 同一个句柄实例，服务端重启导致连接失效后，看门狗对句柄换芯即可让所有持有方
 * （存量会话 + 单例 + 后续新会话）下一步调用自动走新连接，无需任何重注册。
 *
 * <p>世代语义：句柄是 reload 世代内的稳定对象。OAF reload 前清空共享缓存（evictSharedWrappers）
 * 后新 build 产生全新句柄，旧句柄随旧注册收尾路径关闭（close 后拒绝再换芯，防
 * reload 收尾与换芯竞态把新连接关掉——看门狗对 reload 让位是第一道防线，此处兜底）。
 */
public class ReconnectableMcpClientWrapper extends McpClientWrapper {

    private volatile McpClientWrapper delegate;
    private volatile boolean closed;

    public ReconnectableMcpClientWrapper(String name, McpClientWrapper delegate) {
        super(name);
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
    }

    /**
     * 原子换芯：装新关旧。旧 delegate 是已失联的死连接，安装后关闭不留空窗。
     *
     * @return false 表示拒绝换芯（next 为 null/与当前同一实例/句柄已关闭），调用方需自行 close(next)
     */
    public synchronized boolean swap(McpClientWrapper next) {
        if (next == null || next == delegate || closed) {
            return false;
        }
        var old = delegate;
        delegate = next;
        closeQuietly(old);
        return true;
    }

    /** 当前生效连接（看门狗探测/诊断用，不外逃——外部一律经句柄调用） */
    public McpClientWrapper delegate() {
        return delegate;
    }

    @Override
    public Mono<Void> initialize() {
        return delegate.initialize();
    }

    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        return delegate.listTools();
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return delegate.callTool(toolName, arguments);
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(
            String toolName, Map<String, Object> arguments, Map<String, Object> meta) {
        return delegate.callTool(toolName, arguments, meta);
    }

    @Override
    public McpSchema.Tool getCachedTool(String toolName) {
        return delegate.getCachedTool(toolName);
    }

    @Override
    public boolean isInitialized() {
        return delegate.isInitialized();
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(delegate);
    }

    private static void closeQuietly(McpClientWrapper wrapper) {
        try {
            wrapper.close();
        } catch (Exception ignore) {
            // 关闭失败不阻断换芯：死连接的关闭异常无行动价值
        }
    }
}
