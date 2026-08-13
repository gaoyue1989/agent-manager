package io.agentmanager.framework.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

/**
 * OTel SDK 初始化 + Bean 注册。仅在 OTEL_TRACES_EXPORTER=otlp 时激活；
 * none（默认）时 SDK 不注册，SDK 内置 OtelTracingMiddleware 使用 no-op tracer。
 */
@Configuration
@ConditionalOnProperty(name = "otel.traces.exporter", havingValue = "otlp")
public class OtelConfig {

    @Bean(destroyMethod = "close")
    public SdkTracerProvider sdkTracerProvider(
            @Value("${otel.exporter.otlp.endpoint:http://localhost:4318}") String endpoint,
            @Value("${otel.exporter.otlp.headers:}") String headers,
            @Value("${otel.traces.sampler:always_on}") String sampler,
            @Value("${otel.service.name:agent-framework}") String serviceName
    ) {
        var exporterBuilder = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint + "/v1/traces");

        // 支持自定义 HTTP 头（Langfuse 等需要 Authorization）
        if (headers != null && !headers.isBlank()) {
            for (String header : headers.split(",")) {
                String[] kv = header.split("=", 2);
                if (kv.length == 2) {
                    exporterBuilder.addHeader(kv[0].trim(), kv[1].trim());
                }
            }
        }

        // 采样策略：always_on / always_off / ratio:0.5（比例采样，生产高流量场景）
        // 提取为静态方法便于单元测试（OtelConfigTest）
        var samplerObj = parseSampler(sampler);

        var provider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporterBuilder.build()).build())
                .setSampler(samplerObj)
                .setResource(Resource.getDefault().toBuilder()
                        .put("service.name", serviceName)
                        .build())
                .build();

        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build());

        return provider;
    }

    /** 采样策略解析（static，可单测）：always_on / always_off / ratio:0.5，非法输入回退 always_on */
    static Sampler parseSampler(String sampler) {
        if ("always_off".equals(sampler)) {
            return Sampler.alwaysOff();
        }
        if (sampler != null && sampler.startsWith("ratio:")) {
            try {
                double ratio = Double.parseDouble(sampler.substring("ratio:".length()));
                return Sampler.traceIdRatioBased(Math.max(0.0, Math.min(1.0, ratio)));
            } catch (NumberFormatException e) {
                return Sampler.alwaysOn();
            }
        }
        return Sampler.alwaysOn();
    }
}