package io.agentmanager.framework.mcp;

import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.service.SessionUserStore;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.mcp.McpMeta;
import reactor.core.publisher.Flux;

/**
 * MCP 用户上下文注入 middleware（唯一注入点，覆盖 Channel / invoke / A2A / HITL confirm 全链路）。
 *
 * <p>在每次 agent 调用的 onAgent 阶段，把本次调用的生效 userId 写入 RuntimeContext 的
 * McpMeta，使两条通道同时生效：
 * <ul>
 *   <li>协议通道：agentscope McpTool 每次工具调用把 McpMeta 写入 CallToolRequest._meta；</li>
 *   <li>HTTP header 通道：{@link UserScopedMcpClientWrapper} 按 config.yaml userHeaders
 *       映射为下游请求 header。</li>
 * </ul>
 *
 * <p>userId 解析：Channel 链路（/threads/chat 经 ChatUiChannel 网关）RuntimeContext.userId
 * 为网关 peer（DmScope.MAIN 下即会话 sessionId，见 AgentRuntimeService 注释实测），按
 * session_user 表反查真实 userId；A2A / invoke 链路的 userId 已是真实用户，反查未命中时
 * 原样使用。反查失败不阻断调用（fallback 原值）。
 */
public class McpUserContextMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(McpUserContextMiddleware.class);

    /** McpMeta 中用户 id 的 key（config.yaml userHeaders 映射的目标 key） */
    public static final String KEY_USER_ID = "userId";

    private final SessionUserStore sessionUserStore;

    public McpUserContextMiddleware(SessionUserStore sessionUserStore) {
        this.sessionUserStore = sessionUserStore;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (ctx != null) {
            var userId = resolveUserId(ctx);
            if (userId != null && !userId.isBlank()) {
                // 合并语义：保留调用方已放入的其他 McpMeta entries，userId 以本次生效值为准
                var existing = ctx.get(McpMeta.class);
                var mine = new McpMeta(Map.of(KEY_USER_ID, userId));
                ctx.put(McpMeta.class, existing == null ? mine : McpMeta.merge(existing, mine));
            }
        }
        return next.apply(input);
    }

    /** 解析生效 userId：优先 session→user 反查（Channel 链路），未命中用 RuntimeContext 原值 */
    private String resolveUserId(RuntimeContext ctx) {
        var raw = ctx.getUserId();
        if (raw == null || raw.isBlank() || sessionUserStore == null) {
            return raw;
        }
        try {
            var mapped = sessionUserStore.findUserIdBySession(raw);
            if (mapped != null && !mapped.isBlank()) {
                return mapped;
            }
        } catch (Exception e) {
            log.warn("[mcp-user] session->user lookup failed for '{}': {}", raw, e.getMessage());
        }
        return raw;
    }
}
