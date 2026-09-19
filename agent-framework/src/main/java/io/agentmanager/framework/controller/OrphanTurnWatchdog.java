package io.agentmanager.framework.controller;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 孤儿 turn 看护：SSE 订阅取消后，若 turn 在宽限期内仍未走到终态，强制收尾（释放租约）。
 *
 * <h2>为什么必须独立线程</h2>
 * 触发点是 Reactor 的取消回调（{@code sink.onCancel}），跑在 boundedElastic 线程上。
 * 在那里做任何阻塞（哪怕 {@code Thread.sleep}）都会占死该线程，进而与 SDK 的会话闸门
 * （{@code LocalSessionTurnGate} 的 Semaphore）释放路径纠缠，导致**后续所有 turn 永久阻塞**
 * （2026-09-19 e2e 实测：一次 F5 用例即可让整个实例失去响应）。因此这里用独立单线程
 * 调度器承载等待，回调线程只做一次非阻塞提交。
 *
 * <h2>与 durable-sse 的关系</h2>
 * 断连不杀任务是既有不变量（agent 继续执行、事件继续落库、客户端可 {@code /subscribe} 续传）。
 * 本看护**不改**该语义：它只在宽限期（远超任何正常 turn 时长）耗尽后才动手，属于
 * 「客户端已走且 turn 明显卡死」场景的最后兜底，防止租约与闸门许可永久泄漏。
 */
class OrphanTurnWatchdog {

    private static final Logger log = LoggerFactory.getLogger(OrphanTurnWatchdog.class);

    /** 宽限期：超过则视为卡死。远大于正常 turn 尾段，避免误伤长工具链。 */
    static final long GRACE_MS = Long.getLong("agent.e2e.orphan-turn-grace-ms", 180_000L);

    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "orphan-turn-watchdog");
            t.setDaemon(true);
            return t;
        });

    /**
     * 提交一次延迟收尾检查。非阻塞——调用方（Reactor 回调线程）立即返回。
     *
     * @param onExpired 宽限期到达且 turn 仍未结束时执行（通常为 {@code endTurn}）
     */
    void schedule(Runnable onExpired, String sessionId, String replyId) {
        try {
            scheduler.schedule(() -> {
                try {
                    onExpired.run();
                } catch (Exception e) {
                    log.warn("orphan watchdog task failed (sid={}, rid={}): {}",
                        sessionId, replyId, e.getMessage());
                }
            }, GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // 调度器已关闭（应用停机）：忽略，此时也无需看护
            log.debug("orphan watchdog schedule skipped (sid={}): {}", sessionId, e.getMessage());
        }
    }

    void shutdown() {
        scheduler.shutdownNow();
    }
}
