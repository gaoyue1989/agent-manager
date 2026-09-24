package io.agentmanager.framework.config;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;

/**
 * ChatModel 构建工厂：默认（系统）模型、托管会话模型共用同一套装配口径。
 *
 * <p>从 {@code AgentScopeConfig.buildChatModel} 抽出——ModelCatalog（托管模型实例）与
 * 系统模型实例（标题生成 / 连接测试）都需要按 LLMConfig 构建模型，
 * 避免两处装配逻辑漂移（温度/maxTokens/enable_thinking/contextLength/HTTP 超时）。
 */
public final class ChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatModelFactory.class);

    private ChatModelFactory() {}

    /** 按 LLM 配置构建 OpenAI 兼容 ChatModel（不发网络请求，构建期即可完成） */
    public static OpenAIChatModel build(AgentManagerProperties.LLMConfig llm,
                                        AgentManagerProperties.HarnessConfig harness) {
        var optionsBuilder = io.agentscope.core.model.GenerateOptions.builder()
            .temperature(llm.temperature())
            .maxTokens(llm.maxTokens());
        // Qwen3 / vLLM: enableThinking=false → chat_template_kwargs.enable_thinking=false
        // 关闭深度思考模式，避免响应中包含 <think>...</think> 冗余内容
        if (!llm.enableThinking()) {
            optionsBuilder.additionalBodyParam("chat_template_kwargs",
                java.util.Map.of("enable_thinking", false));
            log.info("Deep thinking disabled: chat_template_kwargs.enable_thinking=false");
        }
        var modelBuilder = OpenAIChatModel.builder()
            .apiKey(llm.apiKey())
            .modelName(llm.modelId())
            .baseUrl(llm.baseUrl());
        // 模型上下文窗口（LLM_CONTEXT_LENGTH / 托管模型 contextLength）：> 0 才传入，未配置保持框架默认行为
        if (llm.contextLength() > 0) {
            modelBuilder.contextWindowSize(llm.contextLength());
        }
        return modelBuilder
            .generateOptions(optionsBuilder.build())
            .httpTransport(JdkHttpTransport.builder()
                .client(java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(harness.httpConnectTimeoutSeconds()))
                    .build())
                .config(HttpTransportConfig.builder()
                    .connectTimeout(Duration.ofSeconds(harness.httpConnectTimeoutSeconds()))
                    .readTimeout(Duration.ofSeconds(harness.httpReadTimeoutSeconds()))
                    .writeTimeout(Duration.ofSeconds(harness.httpWriteTimeoutSeconds()))
                    .build())
                .build())
            .build();
    }
}
