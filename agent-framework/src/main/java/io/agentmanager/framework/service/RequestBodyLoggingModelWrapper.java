package io.agentmanager.framework.service;

import java.util.List;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;
import reactor.core.publisher.Flux;

/**
 * Model 装饰器：当 LLM 调用返回 HTTP 400 时，打印实际发送的 JSON 请求体，
 * 用于诊断 vLLM 端 orjson 报 {@code unexpected content after document} 的根因。
 *
 * <h3>背景</h3>
 * Higress 网关可能在请求体前注入路由元数据 JSON，导致 vLLM 收到两个拼接的 JSON 文档。
 * 本包装器捕获我们这侧实际发出的 JSON，与 vLLM 端收到的做对比——
 * 如果我们发出的 JSON 合法但 vLLM 端报错，则确认是网关注入问题。
 *
 * <h3>实现原理</h3>
 * OpenAIChatModel.doStream0() 内部会构建 OpenAIRequest 并通过 JsonCodec 序列化为 JSON 发送。
 * 我们无法拦截那个序列化过程（在依赖 JAR 内），但可以用相同的方式重建请求并序列化，
 * 打印出和我们发出的内容等价的 JSON。
 *
 * <p>仅在 {@code io.agentscope.extensions.model.openai} 包的 BadRequestException 时触发日志，
 * 不影响正常流程。
 */
public class RequestBodyLoggingModelWrapper implements Model {

    private final Model delegate;

    public RequestBodyLoggingModelWrapper(Model delegate) {
        this.delegate = delegate;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                     GenerateOptions options) {
        return delegate.stream(messages, tools, options)
                .doOnError(e -> logRequestBodyOnError(e, messages, tools, options));
    }

    private void logRequestBodyOnError(Throwable e, List<Msg> messages,
                                       List<ToolSchema> tools, GenerateOptions options) {
        // 只在 BadRequestException（HTTP 400 + orjson 解析错误）时触发
        String msg = e.getMessage();
        if (msg == null || !msg.contains("unexpected content after document")) {
            return;
        }

        // 尝试重建 OpenAIRequest 并序列化——和 OpenAIClient 发送时用的完全相同的 codec
        try {
            var formatter = new OpenAIChatFormatter();
            var openaiMessages = formatter.format(messages);

            // 先 build 出 request，再 mutate（与 OpenAIChatModel.doStream0 逻辑对齐）
            var request = OpenAIRequest.builder()
                    .model(delegate.getModelName())
                    .messages(openaiMessages)
                    .stream(true)
                    .build();

            // 应用 GenerateOptions（与 OpenAIChatModel.doStream0 逻辑对齐）
            if (options != null) {
                formatter.applyOptions(request, options, null);
            }
            // 应用 tools
            if (tools != null && !tools.isEmpty()) {
                formatter.applyTools(request, tools);
            }
            var jsonBody = JsonUtils.getJsonCodec().toJson(request);

            // 严格校验：我们这边序列化的 JSON 是否合法
            boolean isValid = JsonUtils.isValidJsonObject(jsonBody);

            // 打印诊断信息
            var log = org.slf4j.LoggerFactory.getLogger(RequestBodyLoggingModelWrapper.class);
            log.error("""
                    
                    ===== LLM 400 诊断：请求体 JSON 诊断 =====
                    错误信息: {}
                    模型: {}
                    我们序列化的 JSON 长度: {} 字节
                    我们序列化的 JSON 是否合法: {}
                    JSON 前 500 字符: [{}]
                    JSON 前 200 字符(hex): [{}]
                    ===========================================
                    """,
                    msg,
                    delegate.getModelName(),
                    jsonBody.length(),
                    isValid,
                    jsonBody.substring(0, Math.min(500, jsonBody.length())),
                    hexDump(jsonBody.substring(0, Math.min(200, jsonBody.length())))
            );

            // 如果 JSON 不合法（不该发生），额外打印完整内容
            if (!isValid) {
                log.error("!!! 我们序列化的 JSON 居然不合法！完整内容：{}", jsonBody);
            }

        } catch (Exception rebuildEx) {
            var log = org.slf4j.LoggerFactory.getLogger(RequestBodyLoggingModelWrapper.class);
            log.warn("重建请求体用于诊断时失败: {}", rebuildEx.getMessage(), rebuildEx);
        }
    }

    /** 打印字符串的 hex dump，用于检测是否有不可见字符或 BOM */
    private static String hexDump(String s) {
        var sb = new StringBuilder(s.length() * 4);
        var bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            sb.append(String.format("%02x", bytes[i] & 0xFF));
            if (i < bytes.length - 1) sb.append(' ');
        }
        return sb.toString();
    }

    // ---- 委托方法 ----

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
