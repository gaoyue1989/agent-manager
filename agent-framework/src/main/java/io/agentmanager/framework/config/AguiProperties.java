package io.agentmanager.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AG-UI 协议输出面配置（agui-native-parity-design §10，env 前缀 AGENT_AGUI_*）。
 *
 * <p>独立 {@code @ConfigurationProperties} record 而非并入 {@link AgentManagerProperties}：
 * 后者 javadoc 记载「加组件波及全部按位构造测试」——独立注册对存量配置零影响
 * （与 AgentProtocolSettings 同一取舍，但本组配置由 {@code @EnableConfigurationProperties}
 * 随 AguiRunController 条件装配注册，协议关闭时不实例化）。
 *
 * <p>不提供 {@code interrupt-on-disconnect}：自建控制层天然「断连不杀 run」
 * （事件照常落库、客户端经 /agui/threads/{id}/events 续传），语义固定无开关。
 *
 * @param enabled                  总开关，默认 false（关闭零装配）
 * @param emitTokenUsage           token 用量 → CUSTOM(token_usage)
 * @param emitToolCallArgs         TOOL_CALL_ARGS 参数增量帧
 * @param enableReasoning          THINKING_* → REASONING_MESSAGE_*
 * @param emitSubagentEventsAsNative  子 agent 事件是否映射为原生事件（false = subagent.* CUSTOM）
 */
@ConfigurationProperties(prefix = "agent.agui")
public record AguiProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("true") boolean emitTokenUsage,
    @DefaultValue("true") boolean emitToolCallArgs,
    @DefaultValue("true") boolean enableReasoning,
    @DefaultValue("false") boolean emitSubagentEventsAsNative
) {
}
