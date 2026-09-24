package io.agentmanager.framework.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.agentscope.harness.agent.HarnessAgent;

/**
 * A2A 链路的 agent 引用中转（OAF reload 原子切换用）：
 * {@code HarnessAgentRunner} 持有本 holder 引用而非直接持有 HarnessAgent，
 * reload 重建 agent 后 {@link #update(HarnessAgent)} 切换，A2A 请求下一事件流即走新 agent
 * （AgentScopeA2aServer 的 agentCard/transport 为不可变构建产物，name/url 本就不随包更新变化，
 * 可变部分由 REST {@code /.well-known/agent-card.json} 经 OafConfigHolder 动态输出）。
 *
 * <p>agent 经 {@link ObjectProvider} 惰性获取（不能字段/构造直注 HarnessAgent——本 Bean 会被
 * {@code List<Object> customTools} 的泛型收集扫为候选，eager 注入会形成
 * holder → harnessAgent → factory → customTools → holder 循环依赖）；首次 {@link #get()}
 * 时若尚未显式 update 且 provider 可用则固化引用。
 */
@Service
public class A2aAgentRefHolder {

    private final ObjectProvider<HarnessAgent> provider;
    private volatile HarnessAgent agent;

    public A2aAgentRefHolder(ObjectProvider<HarnessAgent> provider) {
        this.provider = provider;
    }

    /** 当前 A2A 链路应使用的 agent（显式 update 的引用优先，否则惰性解析初始 Bean）。 */
    public HarnessAgent get() {
        var current = agent;
        if (current == null) {
            current = provider.getIfAvailable();
            if (current != null) {
                agent = current;
            }
        }
        return current;
    }

    /** 原子切换（仅 OafReloadService 调用）。 */
    public void update(HarnessAgent next) {
        this.agent = next;
    }
}
