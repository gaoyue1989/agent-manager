package io.agentmanager.framework.sandbox.opensandbox;

import com.alibaba.opensandbox.sandbox.Sandbox;

/**
 * 定位结果：沙箱 id 与已连接的 SDK 句柄（{@link #close()} 仅释放 HTTP 客户端，不会 kill 沙箱）。
 */
public record LocatedSandbox(String sandboxId, Sandbox sandbox) implements AutoCloseable {

    @Override
    public void close() {
        sandbox.close();
    }
}
