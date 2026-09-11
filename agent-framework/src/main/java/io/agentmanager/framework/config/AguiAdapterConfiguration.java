package io.agentmanager.framework.config;

import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.model.ToolMergeMode;
import io.agentmanager.framework.agui.OafAguiEventEnrichers;
import io.agentmanager.framework.service.McpToolRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AguiAgentAdapter 装配（agui-migration-plan Phase 1）。
 *
 * <p>直接使用 agentscope-extensions-agui 官方无状态 adapter（D2，绕开 spring-boot-starter）：
 * 复用现有 HarnessAgent bean（AgentScopeConfig，不注册 AguiAgentRegistry——单 agent 直用 bean）。
 * 自定义 enricher 注册：oaf.mcp_ui / oaf.file_ready / oaf.tool_image（纯追加，不覆盖内置
 * converter 语义）。
 */
@Configuration
public class AguiAdapterConfiguration {

    @Bean
    public AguiEventEncoder aguiEventEncoder() {
        return new AguiEventEncoder();
    }

    @Bean
    public AguiAgentAdapter aguiAgentAdapter(
        io.agentscope.harness.agent.HarnessAgent agent,
        McpToolRegistrar mcpToolRegistrar,
        AguiProperties props
    ) {
        var builder = AguiAdapterConfig.builder()
            // 思考流（REASONING_MESSAGE_*）
            .enableReasoning(true)
            // token 用量 CUSTOM 事件（Debug Console token 统计）
            .emitTokenUsage(true)
            // 平台无前端工具：传入 tools 以 MERGE_FRONTEND_PRIORITY 合并（§5.1，暂不使用）
            .toolMergeMode(ToolMergeMode.MERGE_FRONTEND_PRIORITY)
            // 子 agent 事件归并为 CUSTOM（默认值，显式声明表意）
            .emitSubagentEventsAsNative(false)
            // 与 FINISHED 互斥终态（§5.1 词表）
            .emitRunFinishedAfterError(false)
            // run 级超时（要点 8）：配置化，0/负数禁用，防误杀发布/长工具场景
            .addEventEnricher(OafAguiEventEnrichers.mcpUi(mcpToolRegistrar))
            .addEventEnricher(OafAguiEventEnrichers.fileReady())
            .addEventEnricher(OafAguiEventEnrichers.toolImage());
        var runTimeout = props.resolvedRunTimeout();
        if (runTimeout != null) {
            builder.runTimeout(runTimeout);
        }
        return new AguiAgentAdapter(agent, builder.build());
    }
}
