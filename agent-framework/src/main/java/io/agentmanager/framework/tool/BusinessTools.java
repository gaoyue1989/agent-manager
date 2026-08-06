package io.agentmanager.framework.tool;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 自定义业务工具示例（阶段三：@Tool 注解注册机制）。
 *
 * <p>注册方式：将实现类声明为 Spring Bean（@Component 或 @Bean），
 * AgentScopeConfig 的 customTools 列表会自动收集并注册到 Toolkit。
 *
 * <p>每个 @Tool 方法会通过反射自动生成 JSON Schema 暴露给 LLM，
 * 支持 RuntimeContext / AgentState 等框架参数自动注入。
 */
public class BusinessTools {

    @Tool(
        name = "get_current_time",
        description = "Returns the current time in a given IANA timezone.",
        readOnly = true,
        concurrencySafe = true)
    public String getCurrentTime(
            @ToolParam(name = "timezone", description = "IANA timezone, e.g. Asia/Shanghai")
                    String timezone) {
        return LocalDateTime.now(ZoneId.of(timezone))
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Tool(
        name = "echo",
        description = "Echo back the input text. Useful for testing tool invocation.",
        readOnly = true,
        concurrencySafe = true)
    public String echo(
            @ToolParam(name = "text", description = "Text to echo back") String text) {
        return "echo: " + text;
    }
}
