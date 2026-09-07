package io.agentmanager.framework.sandbox.opensandbox;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * 同时作为上传文件注入兜底（acquire 可能先于本 middleware 拿不到 userKey）。
 */
public class SandboxUserKeyMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SandboxUserKeyMiddleware.class);

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
            // 注入兜底：acquire（create/resume）可能先于本 middleware 且拿不到 userKey，
            // 此处沙箱实例已就绪 + userKey 已确定 → 绑定并注入（幂等，见 file-upload-download-plan §6.2）
            var sandbox = spec.getLatestSandbox();
            log.info("[sandbox-userkey] onAgent: key={}, latestSandbox={}", userId, sandbox != null ? "present" : "null");
            if (sandbox != null) {
                var key = userId != null && !userId.isBlank() ? userId : ctx.getSessionId();
                sandbox.setUserKey(key);
                var store = spec.getFileAssetStore();
                var sandboxId = sandbox.getOsbState().getSandboxId();
                if (store != null) {
                    // reset 仅在新沙箱代执行（每次 create 即换代）：旧容器注入状态回滚 pending →
                    // 本 turn 重新注入。同沙箱代重复 onAgent（多实例竞争）不再 reset，防循环。
                    if (!spec.isInjectedOnSandbox(key, sandboxId)) {
                        store.resetInjectedToPending(key);
                        spec.markInjectedOnSandbox(key, sandboxId);
                    }
                }
                sandbox.injectPendingUploads();
            }
        }
        return next.apply(input);
    }
}
