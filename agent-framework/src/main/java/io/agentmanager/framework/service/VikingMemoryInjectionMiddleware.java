package io.agentmanager.framework.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.config.VikingConfig;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * OpenViking 首推理记忆注入中间件（openviking-memory-integration-design §6/§4.6）。
 *
 * <p>替代 SDK 文件记忆的「MEMORY.md 全量注入」：onAgent 捕获本轮真实用户输入作为检索
 * query（RuntimeContext 为 turn 作用域状态容器，onSystemPrompt 同 ctx 读取），onSystemPrompt
 * 以 per-user user key 调 search(mode=context) 取注入就绪上下文追加到 system prompt。
 * 官方注入点 = MiddlewareBase.onSystemPrompt（带 RuntimeContext，可解析真实 userId）。
 *
 * <p>性能（§4.6）：检索超时 AGENT 级预算（VikingConfig.searchTimeoutMs，默认 2s）内
 * fail-soft 降级为无记忆；结果按用户缓存 injectCacheTtlSeconds（默认 300s）——同用户
 * 多 turn 复用首轮检索，摊薄后多数 turn 零额外延迟。
 *
 * <p>provider!=openviking 时不装配（HarnessAgentFactory 分支），本地记忆现状零影响。
 */
public class VikingMemoryInjectionMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(VikingMemoryInjectionMiddleware.class);

    /** turn 作用域 query 键（RuntimeContext.put(String)——同一 turn 内 onAgent→onSystemPrompt 传递） */
    static final String CTX_QUERY_KEY = "viking.memory.query";

    /** 注入段落标题（与 SDK MEMORY.md 注入区分，便于观测/调试） */
    static final String SECTION_HEADER = "\n\n# User long-term memory (OpenViking)\n";

    private final VikingMemoryClient client;
    private final SessionKeyResolver sessionKeyResolver;
    private final VikingConfig cfg;

    /** per-user 注入结果缓存（value = [text, expireAtMillis]），容量护栏超限整体清空 */
    private final Map<String, Object[]> cache = new ConcurrentHashMap<>();

    public VikingMemoryInjectionMiddleware(VikingMemoryClient client,
                                           SessionKeyResolver sessionKeyResolver,
                                           VikingConfig cfg) {
        this.client = client;
        this.sessionKeyResolver = sessionKeyResolver;
        this.cfg = cfg;
    }

    @Override
    public int order() {
        return 2; // 与 TurnInputArchive 同层（外层于默认 1）：纯观察/追加，不短路不替换
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        captureQuery(ctx, input);
        return next.apply(input);
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String systemPrompt) {
        if (systemPrompt == null || ctx == null) {
            return Mono.justOrEmpty(systemPrompt);
        }
        var userId = sessionKeyResolver.realUserId(ctx);
        if (userId == null || userId.isBlank()) {
            return Mono.just(systemPrompt);
        }
        var query = ctx.get(CTX_QUERY_KEY);
        if (!(query instanceof String q) || q.isBlank()) {
            return Mono.just(systemPrompt);
        }
        var text = cachedOrSearch(userId, q);
        if (text == null) {
            return Mono.just(systemPrompt);
        }
        log.debug("[viking] memory injected for user {} ({} chars)", userId, text.length());
        return Mono.just(systemPrompt + SECTION_HEADER + text);
    }

    /** 捕获最后一条真实用户输入文本（HITL 确认结果不算输入，与 TurnInputArchive 同口径） */
    private void captureQuery(RuntimeContext ctx, AgentInput input) {
        try {
            if (ctx == null || input == null || input.msgs() == null) {
                return;
            }
            List<Msg> msgs = input.msgs();
            for (int i = msgs.size() - 1; i >= 0; i--) {
                var msg = msgs.get(i);
                if (isRealUserInput(msg)) {
                    var text = msg.getTextContent();
                    if (text != null && !text.isBlank()) {
                        ctx.put(CTX_QUERY_KEY, text.length() > 500 ? text.substring(0, 500) : text);
                    }
                    return;
                }
            }
        } catch (Exception e) {
            log.debug("[viking] capture query skipped: {}", e.getMessage());
        }
    }

    private static boolean isRealUserInput(Msg msg) {
        if (msg == null || msg.getRole() != MsgRole.USER) {
            return false;
        }
        if (msg.getId() == null || msg.getId().isBlank()) {
            return false;
        }
        var metadata = msg.getMetadata();
        return metadata == null || !metadata.containsKey(Msg.METADATA_CONFIRM_RESULTS);
    }

    /** 缓存命中直接返回；未命中检索并回填（检索内部已 fail-soft + 超时预算） */
    private String cachedOrSearch(String userId, String query) {
        long now = System.currentTimeMillis();
        var hit = cache.get(userId);
        if (hit instanceof Object[] entry && entry.length == 2
                && entry[0] instanceof String text && entry[1] instanceof Long expireAt
                && expireAt >= now) {
            return text;
        }
        if (cache.size() > 200) {
            cache.clear(); // 容量护栏：防无界增长（正常规模远达不到）
        }
        var text = client.searchContext(userId, query);
        if (text != null) {
            cache.put(userId, new Object[]{text, now + cfg.injectCacheTtlSeconds() * 1000L});
        }
        return text;
    }
}
