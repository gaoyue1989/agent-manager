package io.agentmanager.framework.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Flux;

/**
 * agent_spawn 工具结果抓取中间件（RemoteConfirmBridge 的在途任务登记入口，设计 §5.5）。
 *
 * <p>捕获 acting 阶段 {@code agent_spawn} 的工具结果文本，解析其中的 task_id
 * （SDK AgentSpawnTool 结果形态 "task_id: %s"），连同工具入参（agent_key/label）
 * 一并交 {@link RemoteConfirmBridge#onAgentSpawnResult} 登记 (session → endpoint, taskId)。
 * 会话键经 {@link SessionKeyResolver} 翻译为**规范 sid**（Channel 链路 ctx.sessionId 是
 * 全进程共享的 gw-hash，直接用会让远程确认行落错桶——设计 §5.6 落卡以规范 sid 为键）。
 *
 * <p>为何用 Middleware 而非 Hook：PostActingEvent 不携带 RuntimeContext，拿不到会话键；
 * onActing 中间件是唯一同时有 (ctx, 工具入参, 结果文本) 的位置。ToolResultEndEvent 只带
 * 状态不带文本，结果文本须经 TOOL_RESULT_TEXT_DELTA 按 toolCallId 累积（同
 * ChatStreamController 的 present_file 累积先例）。
 *
 * <p>注册点：{@code HarnessAgentFactory.build} 的 builder 链（RemoteUserIdMiddleware 之后，
 * 复用工厂级 {@link SessionKeyResolver} 实例共享 turn 级 memo）：
 * {@code .middleware(new RemoteSpawnCaptureMiddleware(remoteConfirmBridge, sessionKeyResolver))}
 * ——本类不进 Spring 容器（随 agent 构建逐实例创建，reload 自然随新 agent 生效）。
 * 抓取/解析/登记全程 fail-soft，任何异常只降级可观测性，不影响父流执行。
 */
public class RemoteSpawnCaptureMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(RemoteSpawnCaptureMiddleware.class);

    static final String SPAWN_TOOL_NAME = "agent_spawn";

    private final RemoteConfirmBridge bridge;
    private final SessionKeyResolver sessionKeyResolver;

    public RemoteSpawnCaptureMiddleware(RemoteConfirmBridge bridge,
                                        io.agentmanager.framework.service.SessionUserStore sessionUserStore) {
        this.bridge = bridge;
        this.sessionKeyResolver = new SessionKeyResolver(sessionUserStore);
    }

    /** 测试注入解析器（免查库） */
    RemoteSpawnCaptureMiddleware(RemoteConfirmBridge bridge, SessionKeyResolver sessionKeyResolver) {
        this.bridge = bridge;
        this.sessionKeyResolver = sessionKeyResolver;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        var spawnCalls = spawnToolCalls(input);
        if (spawnCalls.isEmpty()) {
            return next.apply(input);
        }
        var canonicalSid = sessionKeyResolver != null ? sessionKeyResolver.canonicalSessionId(ctx) : null;
        // toolCallId → 入参快照 + 结果文本累积桶（turn 内有效； acting 串行下无并发互踩）
        var inputsByCallId = new LinkedHashMap<String, Map<String, Object>>();
        var textBuffers = new ConcurrentHashMap<String, StringBuilder>();
        for (var tc : spawnCalls) {
            inputsByCallId.put(tc.getId(), tc.getInput() != null ? tc.getInput() : Map.of());
        }

        return next.apply(input).doOnNext(event -> {
            try {
                if (event instanceof ToolResultTextDeltaEvent delta
                        && inputsByCallId.containsKey(delta.getToolCallId())) {
                    textBuffers.computeIfAbsent(delta.getToolCallId(), k -> new StringBuilder())
                        .append(delta.getDelta());
                } else if (event instanceof ToolResultEndEvent end
                        && inputsByCallId.containsKey(end.getToolCallId())) {
                    var resultText = drain(textBuffers.remove(end.getToolCallId()));
                    bridge.onAgentSpawnResult(canonicalSid, inputsByCallId.get(end.getToolCallId()), resultText);
                }
            } catch (Exception e) {
                log.warn("[RemoteSpawnCapture] capture failed (sid={}): {}", canonicalSid, e.getMessage());
            }
        }).doFinally(sig -> textBuffers.clear());
    }

    /** acting 入参中的 agent_spawn 调用（无则空列表，快路径零开销） */
    static List<ToolUseBlock> spawnToolCalls(ActingInput input) {
        var calls = input != null ? input.toolCalls() : null;
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        return calls.stream()
            .filter(tc -> SPAWN_TOOL_NAME.equals(tc.getName()))
            .toList();
    }

    private static String drain(StringBuilder buf) {
        return buf == null ? "" : buf.toString();
    }
}
