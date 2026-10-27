package io.agentmanager.framework.service;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * 会话 → 当前执行 agent 实例/运行时键的本地映射（停止请求的投递路由）。
 *
 * <h2>为什么需要这层</h2>
 * SDK 的 {@code interrupt(userId, sessionId)} 按 {@code (userId, sessionId)} 槽位寻址，
 * 且只对**本进程内持有该槽位 AgentState 的 agent 实例**生效。Channel 链路（/threads/chat
 * 经 ChatUiChannel 网关）下 SDK 会改写 RuntimeContext——{@code userId} 是网关 peer
 * （= 前端 sessionId）、{@code sessionId} 是网关 gw-hash（见 {@code SessionKeyResolver}
 * 类注释）——不能凭前端 sid 推导。因此由 {@link TurnInterruptMiddleware} 在 onAgent
 * 阶段把**生效后的**三元组登记进来。
 *
 * <h2>正确性定位</h2>
 * 这是**可丢弃缓存**：只服务「本 Pod 正在执行的 turn」。进程重启/实例驱逐后
 * 失效不影响语义——turn 本身也已不在该 Pod 上（租约 TTL 兜底）。因此不持久化、
 * 不做精确清理，仅做有界兜底（超上限整体清空，与 SessionAgentCache 同款防御）。
 *
 * <p>陈旧条目对 interrupt 无害：SDK 在新 call 开始时 {@code interruptControl().reset()}
 * 清掉旧信号（{@code ReActAgent} 实测注释），不会误伤下一轮。
 */
@Service
public class TurnControlRegistry {

    private static final Logger log = LoggerFactory.getLogger(TurnControlRegistry.class);

    /** 有界兜底：会话数远超该值时整体清空（条目可重建，不承担正确性） */
    private static final int MAX_ENTRIES = 4096;

    /** 一次登记的控制三元组：agent 实例 + 生效的运行时键（网关改写后） */
    public record Control(Agent agent, String userId, String sessionId) {}

    private final ConcurrentHashMap<String, Control> controls = new ConcurrentHashMap<>();

    /**
     * 会话 → 当前执行段 replyId（turn 代际栅栏）。
     *
     * <p>为什么需要：watcher 的轮询线程可能在「旧 turn 收尾、新 turn 起跑」的瞬间被调度
     * 延迟——若此时直接按 sessionId interrupt，会打到新 turn 的 agent 上。中断投递前比对
     * 代际，只有请求目标与当前执行段一致才生效（旧请求自然作废，由 turn 收尾清理）。
     */
    private final ConcurrentHashMap<String, String> boundTurns = new ConcurrentHashMap<>();

    /**
     * 登记本会话当前执行实例（每次 agent 调用覆盖，last-writer-wins）。
     *
     * @param canonicalSessionId 业务规范 sessionId（前端/A2A 调用方 sid）
     * @param agent             本次调用的 agent 实例
     * @param userId            生效 RuntimeContext.userId（Channel 链路 = 网关 peer）
     * @param sessionId         生效 RuntimeContext.sessionId（Channel 链路 = 网关 gw-hash）
     */
    public void register(String canonicalSessionId, Agent agent, String userId, String sessionId) {
        if (canonicalSessionId == null || canonicalSessionId.isBlank()
                || agent == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (controls.size() > MAX_ENTRIES) {
            log.warn("TurnControlRegistry size exceeded {} (possible session leak), clearing",
                MAX_ENTRIES);
            controls.clear();
        }
        controls.put(canonicalSessionId, new Control(agent, userId, sessionId));
    }

    /** 绑定当前执行段 replyId（turn 起跑时由 TurnStopWatcher.register 调用）。 */
    public void bindTurn(String canonicalSessionId, String replyId) {
        if (canonicalSessionId != null && replyId != null && !replyId.isBlank()) {
            boundTurns.put(canonicalSessionId, replyId);
        }
    }

    /** 解绑执行段（turn 收尾；只有仍是本轮代际时才移除，避免误删新 turn 的绑定）。 */
    public void unbindTurn(String canonicalSessionId, String replyId) {
        if (canonicalSessionId != null && replyId != null) {
            boundTurns.remove(canonicalSessionId, replyId);
        }
    }

    /**
     * 对指定会话的在途 turn 发起协作式中断（带 turn 代际栅栏）。
     *
     * @return true = 已把 interrupt 信号投递给本 Pod 上的 agent 实例；
     *         false = 本 Pod 没有该会话的当前执行实例（请求应由属主副本消费，
     *                 或目标 turn 已结束/已被下一轮取代）
     */
    public boolean interrupt(String canonicalSessionId, String replyId) {
        if (canonicalSessionId == null || replyId == null
                || !replyId.equals(boundTurns.get(canonicalSessionId))) {
            return false;
        }
        var control = controls.get(canonicalSessionId);
        if (control == null) {
            return false;
        }
        try {
            if (control.agent() instanceof HarnessAgent harness) {
                harness.interrupt(control.userId(), control.sessionId());
            } else if (control.agent() instanceof ReActAgent react) {
                react.interrupt(control.userId(), control.sessionId());
            } else {
                // 兼容路径（测试/嵌入式）：只有无参中断，退化为 agent 级
                control.agent().interrupt();
            }
            return true;
        } catch (Exception e) {
            log.warn("TurnControlRegistry: interrupt failed for sid={}: {}",
                canonicalSessionId, e.getMessage());
            return false;
        }
    }

    /** 移除会话条目（turn 收尾；不承担正确性，漏删由有界兜底处理） */
    public void remove(String canonicalSessionId) {
        if (canonicalSessionId != null) {
            controls.remove(canonicalSessionId);
            boundTurns.remove(canonicalSessionId);
        }
    }

    public int size() {
        return controls.size();
    }
}
