package io.agentmanager.framework.sandbox.opensandbox;

import java.util.function.Function;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;

/**
 * 沙箱用户 key 注入 middleware。
 *
 * 框架内部文件操作（memory_save 等）调用沙箱 exec 时 RuntimeContext 为空（实测），
 * OpenSandbox 无法从 exec 获取 userId。本 middleware 在 agent 调用链（onAgent）上
 * 把请求的 userId 注入 OpenSandboxFilesystemSpec 的 ThreadLocal，
 * 与 SandboxLifecycleMiddleware.acquire（创建/恢复沙箱）在同一订阅链顺序执行，
 * OpenSandboxClient.create/resume 时读取并绑定到沙箱实例，供 stop() 回写使用。
 */
public class SandboxUserKeyMiddleware implements MiddlewareBase {

    private final OpenSandboxFilesystemSpec spec;

    public SandboxUserKeyMiddleware(OpenSandboxFilesystemSpec spec) {
        this.spec = spec;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (ctx != null) {
            var userId = ctx.getUserId();
            if (userId != null && !userId.isBlank()) {
                spec.setPendingUserKey(userId);
            } else {
                spec.setPendingUserKey(ctx.getSessionId());
            }
        }
        return next.apply(input);
    }
}
