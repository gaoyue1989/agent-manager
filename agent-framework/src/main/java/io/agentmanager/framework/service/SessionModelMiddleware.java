package io.agentmanager.framework.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import reactor.core.publisher.Flux;

/**
 * 会话模型路由中间件：onModelCall 时按会话绑定替换主对话模型（会话级模型切换）。
 *
 * <p>只影响走 onModelCall 链的对话/子代理 LLM 调用。记忆 flush/整合与上下文压缩
 * 各自持有 Model 实例、直调 {@code model.stream()}（不经本链，见 TracingModelWrapper 注释），
 * 固定使用系统模型——所以"会话切换只作用于对话"无需额外防护。
 *
 * <p><b>会话 key 两级回退</b>：Channel 链路（/threads/chat）前端 sessionId 实为
 * RuntimeContext.userId（网关 peer，见 McpUserContextMiddleware 先例）；
 * A2A 链路则 sessionId 直接匹配。两个候选依次尝试（先 sessionId 再 userId）。
 *
 * <p><b>一致性</b>：每次模型调用实时查 session_user.model（PK 查询，无进程内缓存），
 * 多副本下 PATCH/chat 落库即生效；未设置/未知/已禁用 → 沿用链上的默认模型（warn 一次/id）。
 */
public class SessionModelMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SessionModelMiddleware.class);

    private final SessionUserStore sessionUserStore;
    private final ModelCatalog modelCatalog;

    /** 未知/已删模型 id 的告警去重（避免每轮 ReAct 反复打日志） */
    private final Set<String> warnedUnknownIds = ConcurrentHashMap.newKeySet();

    public SessionModelMiddleware(SessionUserStore sessionUserStore, ModelCatalog modelCatalog) {
        this.sessionUserStore = sessionUserStore;
        this.modelCatalog = modelCatalog;
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        var target = resolveSessionModel(ctx);
        if (target == null) {
            return next.apply(input);
        }
        // 整体替换 model 后再进链：下游（LLM 记录/OTel span/实际调用）看到的都是生效模型
        return next.apply(new ModelCallInput(input.messages(), input.tools(), input.options(), target));
    }

    /** 按会话解析目标模型；null = 沿用默认模型 */
    private Model resolveSessionModel(RuntimeContext ctx) {
        if (ctx == null || sessionUserStore == null || modelCatalog == null) {
            return null;
        }
        for (var key : candidateKeys(ctx)) {
            String modelId;
            try {
                modelId = sessionUserStore.findModelBySession(key);
            } catch (Exception e) {
                log.debug("[session-model] mapping lookup failed for '{}': {}", key, e.getMessage());
                continue;
            }
            if (modelId == null || modelId.isBlank()) {
                continue;
            }
            var resolved = modelCatalog.resolve(modelId);
            if (resolved.isPresent()) {
                log.debug("[session-model] session '{}' routed to model '{}'", key, modelId);
                return resolved.get();
            }
            if (warnedUnknownIds.add(modelId)) {
                log.warn("[session-model] session '{}' references unavailable model '{}' "
                    + "(deleted/disabled/unknown), falling back to default model", key, modelId);
            }
        }
        return null;
    }

    /** 会话 key 候选（去重去空）：A2A 用 sessionId；Channel 链路用 userId（= 前端 peer） */
    private Set<String> candidateKeys(RuntimeContext ctx) {
        var keys = new LinkedHashSet<String>();
        if (ctx.getSessionId() != null && !ctx.getSessionId().isBlank()) {
            keys.add(ctx.getSessionId());
        }
        if (ctx.getUserId() != null && !ctx.getUserId().isBlank()) {
            keys.add(ctx.getUserId());
        }
        return keys;
    }
}
