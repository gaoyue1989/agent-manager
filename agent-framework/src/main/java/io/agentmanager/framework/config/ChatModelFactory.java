package io.agentmanager.framework.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;

/**
 * ChatModel 构建工厂：默认（系统）模型、托管会话模型共用同一套装配口径。
 *
 * <p>从 {@code AgentScopeConfig.buildChatModel} 抽出——ModelCatalog（托管模型实例）与
 * 系统模型实例（标题生成 / 连接测试）都需要按 LLMConfig 构建模型，
 * 避免两处装配逻辑漂移（温度/maxTokens/频率惩罚/思考参数/contextLength/HTTP 超时）。
 *
 * <p><b>推理参数方言</b>（设计见 docs/model-params-design.md §2/§5）：思考开关与推理强度的
 * 序列化位置因推理引擎而异，统一在 {@link #applyDialect} 单点收口——
 * vllm/sglang（内网自托管，合并方言）走 {@code chat_template_kwargs}；glm 官方走
 * {@code thinking.type} 嵌套对象 + 顶层 effort；openai 及未知 provider 兜底走顶层一等字段；
 * deepseek 官方无对应参数，一律不下发。
 */
public final class ChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatModelFactory.class);

    private ChatModelFactory() {}

    /** 按 LLM 配置构建 OpenAI 兼容 ChatModel（不发网络请求，构建期即可完成） */
    public static OpenAIChatModel build(AgentManagerProperties.LLMConfig llm,
                                        AgentManagerProperties.HarnessConfig harness) {
        var modelBuilder = OpenAIChatModel.builder()
            .apiKey(llm.apiKey())
            .modelName(llm.modelId())
            .baseUrl(llm.baseUrl());
        // 模型上下文窗口（LLM_CONTEXT_LENGTH / 托管模型 contextLength）：> 0 才传入，未配置保持框架默认行为
        if (llm.contextLength() > 0) {
            modelBuilder.contextWindowSize(llm.contextLength());
        }
        return modelBuilder
            .generateOptions(buildOptions(llm))
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

    /** 模型级 GenerateOptions 装配（包级可见供单测断言请求载荷）：采样三项顶层 + 方言映射 */
    static GenerateOptions buildOptions(AgentManagerProperties.LLMConfig llm) {
        // temperature / maxTokens / frequencyPenalty：OpenAI 标准顶层参数，各引擎兼容层原生支持，无方言分支
        var optionsBuilder = GenerateOptions.builder()
            .temperature(llm.temperature())
            .maxTokens(llm.maxTokens());
        if (llm.frequencyPenalty() != null) {
            optionsBuilder.frequencyPenalty(llm.frequencyPenalty());
        }
        applyDialect(optionsBuilder, llm);
        return optionsBuilder.build();
    }

    /**
     * 思考开关 / 推理强度的方言映射（唯一收口点，可单测）：
     * <ul>
     *   <li>vllm/sglang：合并进同一个 {@code chat_template_kwargs} Map（Builder 同 key 覆盖，
     *       两参数必须一次 put）；模板不认领的 kwarg 被 Jinja 无害忽略，一个分支覆盖全部内网模型品类</li>
     *   <li>glm：思考关闭走 {@code thinking: {type: "disabled"}}；effort 走顶层（仅思考开启时生效）</li>
     *   <li>openai / 未知 provider（存量数据兜底）：effort 走顶层一等字段；思考开关不下发（严格端点会 400）</li>
     *   <li>deepseek：官方 API 无思考开关与 effort 参数，均不下发</li>
     * </ul>
     * enable_thinking=true 时所有方言都不注入（端点默认行为）；effort 与开关正交，不做联动校验。
     */
    static void applyDialect(GenerateOptions.Builder optionsBuilder,
                             AgentManagerProperties.LLMConfig llm) {
        var effort = llm.reasoningEffort() != null && !llm.reasoningEffort().isBlank()
            ? llm.reasoningEffort().trim() : null;
        // env 路径（LLM_PROVIDER）无值域校验，归一化大小写防 "VLLM" 静默落错方言
        var provider = llm.provider() != null && !llm.provider().isBlank()
            ? llm.provider().trim().toLowerCase(java.util.Locale.ROOT) : "openai";
        switch (provider) {
            case "vllm", "sglang" -> {
                Map<String, Object> kwargs = new HashMap<>();
                if (!llm.enableThinking()) {
                    kwargs.put("enable_thinking", false);
                }
                if (effort != null) {
                    kwargs.put("reasoning_effort", effort);
                }
                if (!kwargs.isEmpty()) {
                    optionsBuilder.additionalBodyParam("chat_template_kwargs", kwargs);
                    log.info("Sampling dialect [{}]: chat_template_kwargs={}", provider, kwargs);
                }
            }
            case "glm" -> {
                if (!llm.enableThinking()) {
                    optionsBuilder.additionalBodyParam("thinking", Map.of("type", "disabled"));
                    log.info("Sampling dialect [glm]: thinking.type=disabled");
                }
                if (effort != null) {
                    optionsBuilder.reasoningEffort(effort);
                }
            }
            case "deepseek" -> {
                // 官方 API 无思考开关与 effort 参数：均不下发（避免 Unknown parameter 400）
            }
            default -> {
                // openai 及未知 provider 兜底：effort 走顶层一等字段
                if (effort != null) {
                    optionsBuilder.reasoningEffort(effort);
                }
            }
        }
    }
}
