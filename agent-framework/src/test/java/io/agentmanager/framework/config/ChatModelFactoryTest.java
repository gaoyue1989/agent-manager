package io.agentmanager.framework.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.model.GenerateOptions;

/**
 * ChatModelFactory 采样参数方言矩阵测试（docs/model-params-design.md §2/§5）：
 * 思考开关 / 推理强度按 provider 方言映射到正确载荷位置；三采样参数全引擎顶层通用。
 */
class ChatModelFactoryTest {

    // ===== vllm / sglang（合并方言）：思考两参合并进同一个 chat_template_kwargs =====

    @Test
    void vllmShouldMergeThinkingAndEffortIntoSameTemplateKwargs() {
        var options = dialect("vllm", false, "high");

        var kwargs = templateKwargs(options);
        assertEquals(Map.of("enable_thinking", false, "reasoning_effort", "high"), kwargs);
        assertNull(options.getReasoningEffort()); // 不走顶层一等字段
    }

    @Test
    void sglangShouldShareTheSameMergedDialectAsVllm() {
        var options = dialect("sglang", false, "low");

        assertEquals(Map.of("enable_thinking", false, "reasoning_effort", "low"),
            templateKwargs(options));
    }

    @Test
    void vllmShouldSendEffortAloneWhenThinkingKeptOn() {
        var options = dialect("vllm", true, "medium");

        assertEquals(Map.of("reasoning_effort", "medium"), templateKwargs(options));
    }

    @Test
    void vllmShouldInjectNothingWhenThinkingOnAndNoEffort() {
        var options = dialect("vllm", true, null);

        assertTrue(options.getAdditionalBodyParams().isEmpty());
    }

    // ===== openai（严格端点兜底）：effort 走顶层，思考开关不下发 =====

    @Test
    void openaiShouldSendEffortTopLevelAndNeverInjectTemplateKwargs() {
        var options = dialect("openai", false, "minimal");

        assertEquals("minimal", options.getReasoningEffort());
        assertTrue(options.getAdditionalBodyParams().isEmpty());
    }

    @Test
    void unknownProviderShouldFallBackToOpenaiDialect() {
        var options = dialect("some-new-engine", true, "high");

        assertEquals("high", options.getReasoningEffort());
        assertTrue(options.getAdditionalBodyParams().isEmpty());
    }

    // ===== glm：thinking.type 嵌套对象 + 顶层 effort =====

    @Test
    void glmShouldDisableThinkingViaNestedObjectAndEffortTopLevel() {
        var options = dialect("glm", false, "high");

        assertEquals(Map.of("type", "disabled"), options.getAdditionalBodyParams().get("thinking"));
        assertEquals("high", options.getReasoningEffort());
        assertNull(templateKwargs(options)); // 不走 chat_template_kwargs
    }

    // ===== deepseek：官方 API 无对应参数，一律不下发 =====

    @Test
    void deepseekShouldNeverSendThinkingNorEffort() {
        var options = dialect("deepseek", false, "high");

        assertNull(options.getReasoningEffort());
        assertTrue(options.getAdditionalBodyParams().isEmpty());
    }

    // ===== buildOptions：采样三项顶层装配 + 方言映射一体断言 =====

    @Test
    void buildOptionsShouldAssembleSamplingParamsAndDialectTogether() {
        var llm = new AgentManagerProperties.LLMConfig("k", "qwen3", "http://vllm:1/v1", "vllm",
            0.2, 4096, 60, false, 0, "low", 0.5);

        var options = ChatModelFactory.buildOptions(llm);

        // 三采样参数：顶层一等字段（设计文档 §2 结论 1）
        assertEquals(0.2, options.getTemperature());
        assertEquals(4096, options.getMaxTokens());
        assertEquals(0.5, options.getFrequencyPenalty());
        // 思考两参数：vllm 方言合并进同一个 chat_template_kwargs（§2 结论 2）
        assertEquals(Map.of("enable_thinking", false, "reasoning_effort", "low"),
            templateKwargs(options));
        assertNull(options.getReasoningEffort()); // vllm 方言不走顶层
    }

    @Test
    void buildOptionsShouldOmitFrequencyPenaltyWhenAbsent() {
        var llm = new AgentManagerProperties.LLMConfig("k", "m", "http://x/v1", "openai",
            0.3, 16384, 120, false, 0, "", null);

        var options = ChatModelFactory.buildOptions(llm);

        assertNull(options.getFrequencyPenalty()); // NULL = 不下发（D1）
        assertTrue(options.getAdditionalBodyParams().isEmpty());
    }

    // ===== helpers =====

    private static AgentManagerProperties.LLMConfig llm(String provider, boolean enableThinking,
                                                        String effort, Double frequencyPenalty) {
        return new AgentManagerProperties.LLMConfig("k", "m", "http://x/v1", provider,
            0.3, 16384, 120, enableThinking, 0, effort, frequencyPenalty);
    }

    private static GenerateOptions dialect(String provider, boolean enableThinking, String effort) {
        var builder = GenerateOptions.builder();
        ChatModelFactory.applyDialect(builder, llm(provider, enableThinking, effort, null));
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> templateKwargs(GenerateOptions options) {
        return (Map<String, Object>) options.getAdditionalBodyParams().get("chat_template_kwargs");
    }
}
