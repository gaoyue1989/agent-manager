package io.agentmanager.framework.config;

import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;

/**
 * 当前生效 Agent 的 ChatUiChannel 工厂。
 *
 * <p>OAF 动态 reload 会替换 {@code AgentRuntimeService} 中的 HarnessAgent 引用；Channel 在
 * {@code agent.channel(...)} 时绑定 Agent，因此不能在 Controller 中长期缓存启动期 Channel。
 * 该工厂让每个 turn 从当前 Agent 创建 Channel，避免 reload 后继续使用旧 Agent 的连接和权限状态。
 *
 * <p>{@code current(sessionId)}：per-session agent 隔离（SANDBOX_SESSION_AGENT_ISOLATION）
 * 开启时按会话取/建 agent 实例再建 Channel，使并行 turn 各持独立的 SandboxBackedFilesystem
 * （上游 #2849 的 app 级 workaround）；关闭时与 {@link #current()} 等价。
 */
@FunctionalInterface
public interface ChatUiChannelProvider {
    /** 返回当前 Agent 的新 Channel；无可用 Agent 时返回 null。 */
    ChatUiChannel current();

    /** 返回指定会话的 Agent 的新 Channel；无可用 Agent 时返回 null。默认委托 current()（隔离关闭语义）。 */
    default ChatUiChannel current(String sessionId) {
        return current();
    }
}
