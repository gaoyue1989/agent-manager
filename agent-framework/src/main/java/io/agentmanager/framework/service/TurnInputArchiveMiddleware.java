package io.agentmanager.framework.service;

import java.util.function.Function;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;

/**
 * 回合起始用户输入归档：让 {@code GET /threads/{sid}/history} 在 turn 执行中即可见用户提问。
 *
 * <p><b>背景（2026-10-12 修复）</b>：官方 SDK 只在整轮 {@code doCall} 结束后
 * {@code saveStateToSession}，agent_state 与 session_message 归档在 turn 完成前一直是空。
 * 用户发送消息后立即刷新页面、从历史列表进入会话时，详情为空——前端乐观气泡随刷新丢失，
 * 而服务端此刻还没有任何可回放的消息。本中间件在 {@link #onAgent}（输入已确定、尚未执行）
 * 把本回合的**真实用户输入**直接归档进 session_message，使刷新后 history 至少能看到用户
 * 提问；助手回复待 turn 结束时由既有 write-through 补齐。
 *
 * <p><b>键一致（不产生重复）</b>：slot key = {@code normalizeUser(ctx.userId) + ":" +
 * ctx.getSessionId()}，与 SDK 落库 agent_state / {@link SessionMessageArchiveStateStore}
 * 完全同构（Channel 链路 {@code ctx.userId} 是 peer=前端 sid、{@code ctx.sessionId} 是
 * 网关 gw-hash）。{@link io.agentscope.core.message.Msg} 的 id 由 SDK {@code Msg.Builder()}
 * 生成且 turn 全程稳定，归档 upsert 键 {@code (session_id, msg_id)} 幂等——turn 结束
 * write-through 落同一 id，按 {@link SessionMessageStore#mergeMsgJson} 合并，不产生重复消息。
 *
 * <p><b>边界</b>：沙箱 acquire 在 middleware 链之外（HarnessAgent.wrappedStream 的
 * Flux.using 资源构造），建失败时本中间件不会执行——聊天/AG-UI 入口在 dispatch 前已用
 * {@link SessionMessageArchiveStateStore#archiveUserInput} 前置归档兜住该路径。
 *
 * <p>归档逻辑与入口侧前置归档同源（同一 helper），全程 fail-soft，异常只告警，绝不影响
 * 对话主链路；归档关闭（AGENT_HISTORY_ARCHIVE_ENABLED=false）时本中间件不装配。
 */
public class TurnInputArchiveMiddleware implements MiddlewareBase {

    private final SessionMessageStore sessionMessageStore;

    public TurnInputArchiveMiddleware(SessionMessageStore sessionMessageStore) {
        this.sessionMessageStore = sessionMessageStore;
    }

    /** 更大 = 更外层：确保在任何可能短路 onAgent 的中间件之前完成归档 */
    @Override
    public int order() {
        return 2;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (ctx != null && input != null && input.msgs() != null) {
            var sessionId = ctx.getSessionId();
            if (sessionId != null && !sessionId.isBlank()) {
                SessionMessageArchiveStateStore.archiveUserInput(sessionMessageStore,
                    SessionMessageArchiveStateStore.slotKey(ctx.getUserId(), sessionId),
                    input.msgs());
            }
        }
        return next.apply(input);
    }
}
