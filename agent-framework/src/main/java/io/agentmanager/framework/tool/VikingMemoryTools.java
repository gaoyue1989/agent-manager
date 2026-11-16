package io.agentmanager.framework.tool;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.config.VikingConfig;
import io.agentmanager.framework.service.SessionKeyResolver;
import io.agentmanager.framework.service.VikingMemoryClient;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * OpenViking 记忆工具桥（openviking-memory-integration-design §6）：provider=openviking
 * 时以同名工具替代 Harness 内置 memory_search/memory_get/memory_save（SDK 内置经
 * disableMemoryTools 关闭），模型侧工具面保持不变；实现走 VikingMemoryClient
 * （per-user user key 身份）。
 *
 * <p>注册：仅在 provider=openviking 分支由 HarnessAgentFactory 手动
 * toolkit.registerTool（非常驻 Bean——customTools 列表是静态装配面，记忆供给是
 * 发布期固化配置，运行期不变）；工具名在 BUILT_IN_TOOL_NAMES 白名单内，权限自动 ALLOW。
 *
 * <p>userId 解析：@Tool 方法支持 RuntimeContext 框架参数注入（BusinessTools 同机制），
 * 经 SessionKeyResolver 反查真实用户（Channel 链路 ctx.userId 是网关 peer）。
 */
public class VikingMemoryTools implements CustomTool {

    private static final Logger log = LoggerFactory.getLogger(VikingMemoryTools.class);

    private final VikingMemoryClient client;
    private final SessionKeyResolver sessionKeyResolver;
    private final VikingConfig cfg;

    public VikingMemoryTools(VikingMemoryClient client, SessionKeyResolver sessionKeyResolver,
                             VikingConfig cfg) {
        this.client = client;
        this.sessionKeyResolver = sessionKeyResolver;
        this.cfg = cfg;
    }

    @Tool(
        name = "memory_search",
        description = "Search the user's long-term memory (OpenViking). Returns matched memory context.",
        readOnly = true,
        concurrencySafe = true)
    public String memorySearch(
            @ToolParam(name = "query", description = "What to look up in memory") String query,
            RuntimeContext ctx) {
        var userId = sessionKeyResolver.realUserId(ctx);
        var text = client.searchContext(userId, query);
        return text != null ? text : "No matching memory found.";
    }

    @Tool(
        name = "memory_save",
        description = "Save a piece of information into the user's long-term memory (OpenViking). "
            + "Use for durable user preferences, facts, and decisions worth remembering across sessions.",
        readOnly = false,
        concurrencySafe = true)
    public String memorySave(
            @ToolParam(name = "content", description = "The memory content to save, one fact per call") String content,
            RuntimeContext ctx) {
        var userId = sessionKeyResolver.realUserId(ctx);
        client.remember(userId, List.of(Map.entry("user", "Please remember this: " + content)));
        return "Memory submitted for extraction.";
    }

    @Tool(
        name = "memory_get",
        description = "Read back the user's recently saved memory (preferences, facts) for verification.",
        readOnly = true,
        concurrencySafe = true)
    public String memoryGet(RuntimeContext ctx) {
        var userId = sessionKeyResolver.realUserId(ctx);
        var text = client.searchContext(userId, "recently saved memories preferences facts");
        return text != null ? text : "No memory found.";
    }
}
