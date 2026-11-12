package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Flux;

/**
 * 压缩前上下文增量归档：修复「长 turn 中途触发压缩 → 压缩点之前的执行过程从 history 消失」
 * （oa-1110-repro-fixes-design §2.2，session-history-archive-design §12.4 预留后手落地）。
 *
 * <p><b>根因</b>：SDK 2.0.3 整轮只在 {@code doCall} 结束 {@code saveStateToSession} 一次，
 * {@link SessionMessageArchiveStateStore} 仅在 save 时 write-through 归档<b>当时的</b> context；
 * 而 {@code CompactionMiddleware.onReasoning} 在 turn 中途就地 {@code clear()+addAll([摘要]+尾部)}。
 * 单 turn 内工具轮次多、命中消息数/token 压缩阈值时，压缩点之前的 assistant(tool_use) /
 * TOOL(tool_result) 消息从未进入 session_message，也从 agent_state 物理消失——两条 history
 * 路径（归档合并 / state-only）都无从渲染，前端表现为「历史对话部分执行过程缺失」。
 *
 * <p><b>修复</b>：本中间件 {@link #onReasoning} 挂载在每步推理前（{@link #order()}=2，
 * 外层于压缩中间件的默认 order=1，Builder.build 按 order 降序排外包），赶在压缩改写上下文前
 * 把当前 context 与上次归档集做差量，经 {@link SessionMessageArchiveStateStore#toRecord} +
 * {@link SessionMessageStore#archiveBatch} 落库。归档键 {@code (session_id, msg_id)} 幂等，
 * 与 turn 末 write-through 落同一 id 时按「文本取长、状态取新」合并，不产生重复；压缩摘要
 * 消息（{@code __compaction_summary__}）同样归档，保证归档轨可渲染压缩分隔条。
 *
 * <p><b>状态源</b>：SDK 2.0.4 配置 AgentStateStore 后每次 call 经 per-call slot 新建
 * AgentState 注入 RuntimeContext，{@code agent.getAgentState()} 是 deprecated 默认槽访问器
 * （与当前会话无关、context 恒空——曾致归档静默空转，压缩前明细依旧缺失）——必须经
 * {@link RuntimeContext#resolveAgentState} 与 CompactionMiddleware 同源解析 per-call state。
 *
 * <p><b>开销</b>：每次推理仅内存遍历 context + id 集合差分 O(n)，只有新消息产生 DB 写。
 * 全程 fail-soft：任何归档异常只告警，绝不影响对话主链路。装配受
 * {@code AGENT_HISTORY_ARCHIVE_ENABLED} 门控（与 TurnInputArchiveMiddleware 同点装配）。
 */
public class PreCompactionArchiveMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(PreCompactionArchiveMiddleware.class);

    private final SessionMessageStore sessionMessageStore;

    /** 已归档 msgId 集（按 slotKey 分桶；实例随 agent 构建，通常单会话一桶） */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.Set<String>> archivedIds =
            new java.util.concurrent.ConcurrentHashMap<>();

    public PreCompactionArchiveMiddleware(SessionMessageStore sessionMessageStore) {
        this.sessionMessageStore = sessionMessageStore;
    }

    /**
     * order=2：外层于 SDK CompactionMiddleware（未覆写 order，接口默认 1），保证在压缩
     * clear 上下文之前完成归档；内层于 TurnInterruptMiddleware（3，仅 onAgent 登记）。
     */
    @Override
    public int order() {
        return 2;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        archiveCurrentContext(agent, ctx);
        return next.apply(input);
    }

    /** 差量归档当前 agent context（fail-soft，任何异常只告警） */
    private void archiveCurrentContext(Agent agent, RuntimeContext ctx) {
        if (sessionMessageStore == null || agent == null || ctx == null) {
            return;
        }
        var sessionId = ctx.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            // per-call slot state（CompactionMiddleware 同源解析）；deprecated 的
            // agent.getAgentState() 是默认槽，与本会话无关，严禁回退为直接调用
            AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
            if (state == null) {
                return;
            }
            var context = state.getContext();
            if (context == null || context.isEmpty()) {
                return;
            }
            String slot = SessionMessageArchiveStateStore.slotKey(ctx.getUserId(), sessionId);
            var seen = archivedIds.computeIfAbsent(slot,
                    k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
            var records = new ArrayList<SessionMessageStore.MessageRecord>(context.size());
            for (Msg msg : context) {
                if (msg == null || msg.getId() == null || msg.getId().isBlank()
                        || !seen.add(msg.getId())) {
                    continue;
                }
                var record = SessionMessageArchiveStateStore.toRecord(msg);
                if (record == null) {
                    // 序列化失败等：不入已归档集，下轮推理重试
                    seen.remove(msg.getId());
                    continue;
                }
                records.add(record);
            }
            if (!records.isEmpty()) {
                sessionMessageStore.archiveBatch(slot, records);
            }
        } catch (Exception e) {
            log.warn("PreCompactionArchiveMiddleware: archive failed (sid={}): {}",
                    sessionId, e.getMessage());
        }
    }
}
