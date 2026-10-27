package io.agentmanager.framework.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.config.TurnStopSettings;

/**
 * 属主副本的停止请求派发器（turn_stop 表 → 本 Pod 活跃 turn 的 SDK interrupt）。
 *
 * <h2>为什么是「全局轮询 + 本地匹配」</h2>
 * 每轮一个轮询线程会随并发 turn 线性放大 DB 查询；这里每个副本只跑一个固定周期任务，
 * 一次 {@code SELECT ... FROM turn_stop}（表里只有在途停止请求，极小）后按 reply_id
 * 匹配本 Pod 登记的活跃 turn。查询成本与并发 turn 数解耦。
 *
 * <h2>为什么重复投递</h2>
 * 停止请求可能在 SDK 调用真正开始前到达（租约已到手、onAgent 尚未执行）：此时
 * interrupt 打在旧/新加载的 AgentState 上都可能被 call 起始的 {@code reset()} 清掉。
 * 因此在 turn 收尾前每拍都重新投递（幂等），保证进入执行段后一定能命中。
 *
 * <h2>协作式语义</h2>
 * SDK 的中断检查点在模型流 chunk 之间与每次 reasoning/acting 前；**正在执行中的工具
 * 不会被强杀**。超过 {@link TurnStopSettings#graceMs()} 仍无终态时只打 WARN（通常是
 * 阻塞中的工具调用），不做破坏性收尾——提前放租约会让旧循环晚到的 state 保存覆盖新
 * turn（见 docs/turn-stop-design.md 风险 §1）。
 */
@Service
public class TurnStopWatcher {

    private static final Logger log = LoggerFactory.getLogger(TurnStopWatcher.class);

    private final TurnStopStore stopStore;
    private final TurnControlRegistry registry;
    private final TurnStopSettings settings;

    /** replyId → 本 Pod 活跃 turn 登记（可丢弃缓存；有界由活跃 turn 数天然约束） */
    private final ConcurrentHashMap<String, Registration> active = new ConcurrentHashMap<>();

    public TurnStopWatcher(TurnStopStore stopStore,
                           TurnControlRegistry registry,
                           TurnStopSettings settings) {
        this.stopStore = stopStore;
        this.registry = registry;
        this.settings = settings;
    }

    private static final class Registration {
        final String sessionId;
        final String replyId;
        /** true = 排队中（尚无 agent 执行：只置停止标记并消费行，不调 interrupt） */
        final boolean queued;
        final AtomicBoolean interruptedLogged = new AtomicBoolean(false);
        volatile long firstRequestedAtMs;
        /** interrupt 是否已成功投递（终态帧判定用：未投递的请求不得标成「已停止」） */
        volatile boolean interrupted;
        volatile boolean graceWarned;

        Registration(String sessionId, String replyId, boolean queued) {
            this.sessionId = sessionId;
            this.replyId = replyId;
            this.queued = queued;
        }
    }

    /** 登记一个进入执行段的 turn（在租约到手、启动 agent 之前调用）。 */
    public void register(String sessionId, String replyId) {
        if (sessionId == null || replyId == null || replyId.isBlank()) {
            return;
        }
        active.put(replyId, new Registration(sessionId, replyId, false));
        // turn 代际栅栏：中断投递前比对 replyId，防止旧请求误打新 turn
        registry.bindTurn(sessionId, replyId);
    }

    /**
     * 登记一个排队中的 turn（尚无事件/agent 实例）。命中停止时只置标记并消费行，
     * 由 {@code ChatStreamController} 的排队 loop 读到标记后收流——避免排队期每拍一次
     * DB 查询（全局轮询已经把所有行读回来了）。
     */
    public void registerQueued(String sessionId, String replyId) {
        if (sessionId == null || replyId == null || replyId.isBlank()) {
            return;
        }
        active.put(replyId, new Registration(sessionId, replyId, true));
    }

    /** 仅移除登记、不消费停止行（排队 → 执行的交接；行留给执行态派发）。 */
    public void unregisterRegistrationOnly(String replyId) {
        if (replyId != null) {
            active.remove(replyId);
        }
    }

    /**
     * turn 收尾注销：移除登记并清理本轮的停止行（幂等）。
     *
     * <p>清理必须带 replyId：若迟到收尾时同会话已开始下一轮，行里的 replyId 已不同，
     * 不能误删新请求（store.consume 自带栅栏校验）。
     */
    public void unregister(String sessionId, String replyId) {
        if (replyId != null) {
            active.remove(replyId);
        }
        if (sessionId != null && replyId != null) {
            registry.unbindTurn(sessionId, replyId);
            stopStore.consume(sessionId, replyId);
        }
    }

    /** 本 turn 是否已收到并**成功投递**停止请求（终态帧/恢复消息抑制用）。 */
    public boolean isStopRequested(String replyId) {
        var reg = replyId != null ? active.get(replyId) : null;
        return reg != null && reg.interrupted;
    }

    /** 固定周期派发（周期由 AGENT_TURN_STOP_POLL_MS 控制，默认 1s）。 */
    @Scheduled(fixedDelayString = "#{@turnStopSettings.pollIntervalMs}",
               initialDelayString = "1000")
    public void poll() {
        if (!settings.enabled() || active.isEmpty()) {
            return;
        }
        try {
            var rows = stopStore.findAll();
            if (rows.isEmpty()) {
                return;
            }
            for (var row : rows) {
                var reg = active.get(row.replyId());
                if (reg != null) {
                    dispatch(reg);
                }
            }
        } catch (Exception e) {
            // 轮询失败只影响本次派发，下一拍重试（停止请求仍在表里）
            log.warn("[turn-stop] poll failed: {}", e.getMessage());
        }
    }

    private void dispatch(Registration reg) {
        if (reg.queued) {
            // 排队 turn：没有 agent 实例可中断——消费停止行并置标记，排队 loop 下一拍收流
            if (stopStore.consume(reg.sessionId, reg.replyId)) {
                reg.interrupted = true;
                if (reg.interruptedLogged.compareAndSet(false, true)) {
                    log.info("[turn-stop] queued turn cancelled: sid={}, rid={}",
                        reg.sessionId, reg.replyId);
                }
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (reg.firstRequestedAtMs == 0) {
            reg.firstRequestedAtMs = now;
        }
        boolean sent = registry.interrupt(reg.sessionId, reg.replyId);
        if (sent) {
            reg.interrupted = true;
        }
        if (sent && reg.interruptedLogged.compareAndSet(false, true)) {
            log.info("[turn-stop] interrupt delivered: sid={}, rid={}",
                reg.sessionId, reg.replyId);
        } else if (!sent && reg.interruptedLogged.compareAndSet(false, true)) {
            log.warn("[turn-stop] stop requested but no local agent control registered yet "
                + "(sid={}, rid={}) — will retry next tick", reg.sessionId, reg.replyId);
        }
        long waited = now - reg.firstRequestedAtMs;
        if (!reg.graceWarned && settings.graceMs() > 0 && waited >= settings.graceMs()) {
            reg.graceWarned = true;
            log.warn("[turn-stop] turn still running {}ms after stop request "
                + "(sid={}, rid={}) — likely blocked in a tool call; SDK interrupt "
                + "checkpoints only run between steps",
                waited, reg.sessionId, reg.replyId);
        }
    }

    /** 当前活跃登记数（观测/单测） */
    public int activeCount() {
        return active.size();
    }
}
