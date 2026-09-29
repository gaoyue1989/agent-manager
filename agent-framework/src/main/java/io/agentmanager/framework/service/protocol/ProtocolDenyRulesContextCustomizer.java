package io.agentmanager.framework.service.protocol;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.agentprotocol.AgentRequest;
import io.agentscope.extensions.agentprotocol.RuntimeContextCustomizer;

/**
 * 把提交上下文 {@code context.deny_rules} 挂上协议任务 RuntimeContext 的定制器
 * （travel-fulfillment 设计 F16：扩展不消费该字段，member 侧自实现的第一环）。
 *
 * <p>SDK 扩展 {@code AgentProtocolTaskStore.buildRuntimeContext} 对每次协议任务
 * （提交 + 每次 resume）收集 Spring 容器内全部 {@code RuntimeContextCustomizer} bean
 * 依次调用——本类注册进 {@code AgentProtocolConfig.ProtocolEnabledAssembly} 即生效。
 * 解析 fail-soft：解析不出即不挂载，工具调用回落既有 fail-closed（ask）路径。
 */
public class ProtocolDenyRulesContextCustomizer implements RuntimeContextCustomizer {

    @Override
    public void customize(AgentRequest request, RuntimeContext.Builder builder) {
        if (request == null || builder == null) {
            return;
        }
        var names = ProtocolDenyRules.parseToolNames(
            request.contextValue(ProtocolDenyRules.CONTEXT_FIELD));
        if (!names.isEmpty()) {
            builder.put(ProtocolDenyRules.RUNTIME_ATTRIBUTE_KEY, names);
        }
    }
}
