package io.agentmanager.framework.controller;

import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;

import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.TurnLeaseGuard;
import io.agentmanager.framework.service.TurnUsageTracker;
import reactor.core.publisher.FluxSink;

/**
 * turn 收尾共用工具（A5：ChatStreamController 与 ConfirmController 完全相同的收尾成员收口于此）。
 *
 * <p>与 {@link AgentEventSseSerializer} 同一先例：{@code final class} + 私有构造 + 静态方法。
 *
 * <p>职责边界：只收口「行为完全相同」的成员——两侧的 {@code handleEventAndEmit} 存在真实差异
 * （chat 侧有工具名登记、write_file 截获、present_file 累积、audit、file_ready 合成；
 * confirm 侧是 storeConfirmContext + 先 release 后 emit 的次序），不在此强行模板化。
 */
public final class TurnFinalizer {

    private static final Logger log = LoggerFactory.getLogger(TurnFinalizer.class);

    private TurnFinalizer() {
    }

    /**
     * 带「只收尾一次」保护的 turn 收尾：晚到的 complete/error 回调不得重复执行。
     *
     * <p>本 turn 的收尾只做一次。AGENT_END 处理与源 flux 的 complete/error 回调可能各自
     * 触发一次收尾，且 harness 的 flux 可能在 AGENT_END 事件之后**数秒**才 complete——
     * 迟到的那次若再走 closeSession，会把**下一个** turn 刚建好的 sink 拆掉
     * （实测：0.4s 内前后脚的两轮对话，第二轮的 permission_ask 被迟到关闭吞掉，
     * 前端收不到 HITL 确认卡；approval-forms e2e 2026-09-17 复现）。
     *
     * @return compareAndSet 是否首胜（收尾权是否由本次调用抢到）；调用方可据返回值追加
     *         只该做一次的收尾动作（如 ChatStreamController 的按 turn 清桶，见其封装方法）
     */
    public static boolean endTurn(SessionEventBus eventBus, TurnLeaseGuard lease,
                                  String sessionId, AtomicBoolean turnEnded) {
        if (!turnEnded.compareAndSet(false, true)) {
            return false;
        }
        endTurn(eventBus, lease, sessionId);
        return true;
    }

    /**
     * turn 收尾：丢锁走 abandon（**丢弃**缓冲），正常走 closeSession（刷缓冲）。
     *
     * <p>顺序上先收尾再放锁：刷缓冲必须在仍持有租约时做完，否则另一个副本可能已经
     * 接管并按新的 MAX(seq) 播种、开始写，而我们这时才把按旧区间分配的缓冲行写下去
     * ——正是 C1 要防的重叠。代价是流结束与租约释放之间有一个极短窗口（客户端已看到
     * 结束、锁还在我们手上），抢锁方按 ACQUIRE_TIMEOUT 排队等一拍即可。
     *
     * <p>{@code isLost()} 必须在 {@code release()} **之前**求值：release 之后
     * {@code released} 参与判断，时间判据会被短路成 false，丢锁的 turn 就误走刷缓冲了。
     */
    public static void endTurn(SessionEventBus eventBus, TurnLeaseGuard lease, String sessionId) {
        boolean lost = lease.isLost();
        if (lost) {
            eventBus.abandonSession(sessionId);
        } else {
            eventBus.closeSession(sessionId);
        }
        lease.release();
    }

    /**
     * 租约已失去：本副本不再拥有该 session 的写入权。
     *
     * <p>继续 append 会与新 owner 的 seq 区间重叠——这正是 C1 要防的事——所以立刻停手、
     * 丢弃缓冲、把控制权交还客户端。终态帧**只发本连接的客户端、不落库**：此刻任何
     * append 都会占用可能与新 owner 重叠的 seq（这也是不能用 emitSynthetic 的原因）。
     *
     * @param logTag 日志前缀，取 {@code "[chat]"} / {@code "[confirm]"}——拼出的日志行与
     *               收口前两侧各自的实现逐字节一致，运维 grep 行为不变
     * @return true = 本事件已被丢弃，调用方必须直接返回
     */
    public static boolean stopIfLeaseLost(SessionEventBus eventBus, TurnLeaseGuard lease,
                                          String sessionId,
                                          FluxSink<ServerSentEvent<String>> sink,
                                          String logTag) {
        if (!lease.isLost()) {
            return false;
        }
        if (lease.tryMarkLostNotified()) {
            log.error("{} turn lease lost, stopping writer (sid={})", logTag, sessionId);
            sink.next(interruptedSSE(sessionId, "lease_lost"));
            eventBus.abandonSession(sessionId);
            lease.release();
        }
        return true;
    }

    /**
     * turn 终态分类（{@code turn_finished.state} 词表，chat-trace-observability-design §2.1）。
     *
     * <p>同一 turn 的收尾只可能落一个分类：AGENT_END 按「本轮是否见过 permission_ask」
     * 分叉 completed / waiting_confirm；error 回调、用户停止、孤儿看护强收各有专属分类。
     */
    public enum TurnOutcome {
        /** AGENT_END 正常收尾 */
        COMPLETED("completed"),
        /** 本轮 HITL 挂起（permission_ask 后租约让出）；确认恢复轮是独立 turn、另发自己的帧 */
        WAITING_CONFIRM("waiting_confirm"),
        /** 源流异常收尾 */
        ERROR("error"),
        /** 用户停止（cancelled 帧之后） */
        CANCELLED("cancelled"),
        /** 断连后孤儿看护强收（agent 仍在跑，本连接先行收尾） */
        INTERRUPTED("interrupted");

        private final String value;

        TurnOutcome(String value) {
            this.value = value;
        }

        /** 帧内 state 字段值（snake_case，与合成帧词表同风格） */
        public String value() {
            return value;
        }
    }

    /**
     * turn 收尾汇总帧 {@code turn_finished}（chat-trace-observability-design §2）：chat 与
     * confirm 两侧完全相同的帧构造与发射，收口于此（TurnFinalizer「行为完全相同才收口」
     * 边界）。经 emitSynthetic 落库广播——实时、订阅续传、刷新回放三路同帧；session_id /
     * replyId / turnId 由写路径注入机制统一附加，本方法不重复构造。
     *
     * <p><b>调用时序约束</b>（调用方负责，见两侧收尾漏斗）：必须在终态帧（AGENT_END /
     * error / cancelled）之后、closeSession 刷缓冲之前调用——拿到紧邻终态帧的 seq 且落库
     * 可回放；且必须在收尾 CAS 首胜之后（终态只收尾一次）。丢锁路径不得调用（abandon
     * 语义禁止再写行）。
     *
     * @param usage            本轮用量累积器（TurnUsageTracker）
     * @param turnStartedAtMs  turn 起点墙钟（两侧控制器的 turnStartedAt，租约到手时刻）；
     *                         elapsed_ms 语义为 agent 执行段耗时，不含排队等租约时间
     * @param outcome          终态分类（state 字段）
     */
    public static void emitTurnFinished(SessionEventBus eventBus, String sessionId, String replyId,
                                        TurnUsageTracker usage, long turnStartedAtMs,
                                        TurnOutcome outcome) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", "turn_finished");
        payload.put("state", outcome.value());
        var usagePayload = new LinkedHashMap<String, Object>();
        usagePayload.put("input_tokens", usage.inputTokens());
        usagePayload.put("output_tokens", usage.outputTokens());
        usagePayload.put("total_tokens", usage.totalTokens());
        usagePayload.put("model_calls", usage.modelCalls());
        payload.put("usage", usagePayload);
        payload.put("elapsed_ms", Math.max(0, System.currentTimeMillis() - turnStartedAtMs));
        try {
            eventBus.emitSynthetic(sessionId, replyId, "turn_finished",
                AgentEventSseSerializer.payload(payload));
        } catch (Exception e) {
            // 汇总帧属可观测性增强：失败只记日志，不影响既有收尾次序（closeSession/release）
            log.debug("turn_finished emit failed (sid={}, rid={}): {}",
                sessionId, replyId, e.getMessage());
        }
    }

    /** 租约丢失的终态帧：不落库、不占 seq，只给本连接的客户端；带会话归属（§3.2） */
    public static ServerSentEvent<String> interruptedSSE(String sessionId, String reason) {
        return ServerSentEvent.<String>builder()
            .data("{\"type\":\"interrupted\",\"reason\":\"" + reason + "\",\"session_id\":"
                + AgentEventSseSerializer.jsonEsc(sessionId) + "}")
            .build();
    }

    /**
     * 准备段异常回滚：error 帧 &rarr; closeSession &rarr; release &rarr; complete（四个副作用按此固定次序）。
     *
     * <p>准备阶段的异常必须回滚已获取的 turn_lease。TurnLeaseGuard 的后台续租线程
     * 不看本段是否还活着——只要 token 仍匹配就持续续期，因此漏放租约意味着该
     * session 被**永久**锁死：后续每个请求都拿不到租约，观察者也会一直 probe
     * 到 RUNNING。构造消息这一步会因用户输入抛异常（chat 侧是 fileId 失效 &rarr; 工作区注入
     * 失败；confirm 侧是恢复流构建同步抛出：未知 tool_call_id &rarr; IllegalArgumentException、
     * 上下文已被并发消费 &rarr; ConfirmContextNotFound），所以这不是理论路径。
     *
     * <p>error 帧由调用方构造好后**整帧传入**：两侧控制器的 errorSSE 工厂字节不等价——
     * chat 手工字符串拼接（恒定键序）；confirm 走 {@code payload(Map.of(...))}（JDK 9+ 不可变
     * Map 迭代序受每 JVM 随机 SALT 影响）。统一到任一工厂都会使另一侧的回滚 error 帧字节
     * 偏离现状，因此帧构造留在各控制器，本方法只按固定次序执行副作用。
     *
     * @param errorFrame 已构造好的 error SSE 帧，原样发给本连接
     */
    public static void abortSetup(SessionEventBus eventBus, TurnLeaseGuard lease,
                                  FluxSink<ServerSentEvent<String>> sink, String sessionId,
                                  ServerSentEvent<String> errorFrame) {
        sink.next(errorFrame);
        eventBus.closeSession(sessionId);   // 刷缓冲 + 释放 seq 计数器 + 关 sink
        lease.release();
        sink.complete();
    }
}
