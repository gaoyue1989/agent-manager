package io.agentmanager.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 历史回放配置（GET /threads/{sid}/history）。
 * 环境变量前缀：AGENT_HISTORY_*（如 AGENT_HISTORY_TOOL_OUTPUT_MAX_CHARS）
 *
 * <p><b>为什么是独立类而不是 {@link AgentManagerProperties} 的嵌套 record：</b>
 * {@code AgentManagerProperties} 有 16 处测试按位置传参构造，往里加组件会波及全部 16 处；
 * 仓库已有独立配置类的先例（{@link AgentRedisProperties}、{@link SandboxConfig}）。
 * 三者都在 {@code AgentFrameworkApplication} 的 {@code @EnableConfigurationProperties} 里注册。
 */
@ConfigurationProperties(prefix = "agent.history")
public record HistoryConfig(
    /**
     * 单个工具结果文本（tool_result.output）在 history 响应里的最大字符数，默认 8000。
     * 工具输出可能很大（文件内容、长日志），全量返回会显著撑大响应体；
     * 截断后附 output_truncated / output_full_length 供前端提示。
     * {@code <= 0} 表示不截断。
     */
    @DefaultValue("8000") int toolOutputMaxChars
) {
    /** 代码默认值兜底：配置节缺失（如测试直接构造）时使用 */
    public static HistoryConfig defaults() {
        return new HistoryConfig(io.agentmanager.framework.service.StateDataParser.DEFAULT_TOOL_OUTPUT_MAX_CHARS);
    }
}
