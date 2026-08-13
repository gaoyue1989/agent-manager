package io.agentmanager.framework.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;

/**
 * OtelConfig 采样策略解析测试（静态方法，无需 Spring 上下文）。
 */
class OtelConfigTest {

    @Test
    void shouldParseAlwaysOn() {
        assertSame(Sampler.alwaysOn(), OtelConfig.parseSampler("always_on"));
    }

    @Test
    void shouldParseAlwaysOff() {
        assertSame(Sampler.alwaysOff(), OtelConfig.parseSampler("always_off"));
    }

    @Test
    void shouldParseRatioSampler() {
        var sampler = OtelConfig.parseSampler("ratio:0.5");
        var random = new java.util.Random();
        var sampled = 0;
        for (var i = 0; i < 1000; i++) {
            // 用两个均匀随机 long 拼成 traceId：UUID v4 低位含变体位（恒 >= 2^62），
            // 在比例采样中永远不可能被采样；随机 long 无此偏差
            var traceId = String.format("%016x%016x", random.nextLong(), random.nextLong());
            var result = sampler.shouldSample(
                    io.opentelemetry.context.Context.root(),
                    traceId,
                    "span",
                    io.opentelemetry.api.trace.SpanKind.INTERNAL,
                    io.opentelemetry.api.common.Attributes.empty(),
                    java.util.List.of());
            if (result.getDecision() == SamplingDecision.RECORD_AND_SAMPLE) {
                sampled++;
            }
        }
        // 0.5 比例采样，1000 次样本约 500（容差 ±15%）
        assertTrue(sampled > 350 && sampled < 650, "ratio:0.5 采样率应约 50%，实际 " + sampled);
    }

    @Test
    void shouldClampRatioAboveOne() {
        var sampler = OtelConfig.parseSampler("ratio:2.0");
        var result = sampler.shouldSample(
                io.opentelemetry.context.Context.root(),
                "00000000000000000000000000000001",
                "span",
                io.opentelemetry.api.trace.SpanKind.INTERNAL,
                io.opentelemetry.api.common.Attributes.empty(),
                java.util.List.of());
        assertSame(SamplingDecision.RECORD_AND_SAMPLE, result.getDecision());
    }

    @Test
    void shouldFallbackToAlwaysOnForInvalidInput() {
        assertSame(Sampler.alwaysOn(), OtelConfig.parseSampler("ratio:abc"));
        assertSame(Sampler.alwaysOn(), OtelConfig.parseSampler("ratio:"));
        assertSame(Sampler.alwaysOn(), OtelConfig.parseSampler("garbage"));
        assertSame(Sampler.alwaysOn(), OtelConfig.parseSampler(null));
        assertInstanceOf(Sampler.class, OtelConfig.parseSampler("ratio:0.3"));
    }
}