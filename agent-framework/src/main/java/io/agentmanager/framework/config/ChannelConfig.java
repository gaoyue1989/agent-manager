package io.agentmanager.framework.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.agentmanager.framework.service.AgentRuntimeService;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;

@Configuration
public class ChannelConfig {

    /**
     * 启动期 Channel：保留兼容旧注入点；动态对话入口使用下方 provider。
     *
     * <p>必须用 {@link ChatUiChannel#perPeer()}（DmScope.PER_PEER）：默认 {@code create()}
     * 是 DmScope.MAIN——路由层把所有 DM 折叠成同一 canonicalKey（"chatui|x:agentId=main"），
     * SDK 网关按该 key 过 turn 闸门（每 key 一把整 turn 持有的单许可信号量），
     * 全进程所有会话的 turn 串行排队、每副本有效并发≈1（issue #87 实测 17s~169s）。
     * PER_PEER 下 canonicalKey = "chatui|r:{peerId}|x:agentId=main"，peerId 即平台
     * sessionId（ChatStreamController 传入），闸门随之变每会话一把。
     */
    @Bean
    public ChatUiChannel chatUiChannel(HarnessAgent agent) {
        return agent.channel(ChatUiChannel.perPeer());
    }

    /**
     * 当前 Agent 的 Channel 工厂：每轮对话从 AgentRuntimeService 当前引用创建，
     * 避免 OAF reload 后 ChatStreamController 继续复用启动期 Agent 的旧 Channel。
     * perPeer 的必要性见上方 chatUiChannel 的注释（issue #87）。
     */
    @Bean
    public ChatUiChannelProvider chatUiChannelProvider(AgentRuntimeService runtimeService) {
        return () -> {
            var agent = runtimeService.getAgent();
            return agent == null ? null : agent.channel(ChatUiChannel.perPeer());
        };
    }
}
