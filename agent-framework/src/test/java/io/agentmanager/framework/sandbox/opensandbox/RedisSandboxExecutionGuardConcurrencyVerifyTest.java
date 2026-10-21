package io.agentmanager.framework.sandbox.opensandbox;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentmanager.framework.config.AgentRedisProperties;
import io.agentmanager.framework.redis.RedisConnectionFacade;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 守卫并发行为实测（真实 Redis，e2e-redis :16379）：
 * 验证"同一用户两个会话并发提交长任务（如长程 PPT 制作）是否被串行化"。
 *
 * <p>模拟口径：一个线程 = 一个会话的一个回合（HarnessAgent 在回合入口 acquireForCall
 * 拿租约、回合收尾 releaseForCall 释放，字节码已核对 wrappedStreamEvents/wrappedCall）。
 * tryEnter 的阻塞等待即第二个会话用户感知到的排队时间。
 *
 * <p>防环境抖动：预检连通性；持有者拿锁/释放都经独立校验连接确认锁键真实落盘/消失；
 * B 的进入时刻必须落在预期窗口，否则判脏轮重试（Redis 瞬断会让 tryEnter 走
 * fail-open/超时路径，数字不可信，见首轮实测 649s 的教训）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisSandboxExecutionGuardConcurrencyVerifyTest {

    private static final String REDIS_URL = "redis://127.0.0.1:16379";

    /** 独立校验连接：与守卫的连接分离，避免同连接抖动互相污染判断 */
    private RedisConnectionFacade verifier;

    @BeforeAll
    void setUp() {
        verifier = RedisConnectionFacade.create(
            new AgentRedisProperties(REDIS_URL, 2000, 2000, 250_000));
    }

    @AfterAll
    void tearDown() {
        verifier.close();
    }

    /** A2A/invoke 链路语义：ctx.userId 即真实用户，不同 sessionId = 同用户的不同会话 */
    private static SandboxIsolationKey key(String userId, String sessionId) {
        var ctx = RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        return SandboxIsolationKey.resolve(IsolationScope.USER, ctx, "verify-agent").orElseThrow();
    }

    private boolean pingOk() {
        try {
            return "PONG".equalsIgnoreCase(verifier.sync().ping());
        } catch (Exception e) {
            return false;
        }
    }

    /** 锁键当前是否存在（校验持有/释放是否真实落盘） */
    private boolean lockExists(String user) {
        var val = verifier.sync().get("sbx:guard:user:" + user);
        return val != null && !val.isBlank();
    }

    private boolean awaitTrue(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(30);
        }
        return cond.getAsBoolean();
    }

    /**
     * 起一个"回合"线程：tryEnter → 持有 holdMs → close →（requireRelease 时）确认锁键消失。
     * 立即返回；拿锁等待 ms 写入 waitMs（-1 = 未完成/中断）。
     */
    private AtomicLong spawnTurn(RedisSandboxExecutionGuard guard, String user, String sid,
            long holdMs, boolean requireRelease, AtomicBoolean done) {
        AtomicLong waitMs = new AtomicLong(-1);
        Thread t = new Thread(() -> {
            try {
                long t0 = System.currentTimeMillis();
                SandboxLease lease = guard.tryEnter(key(user, sid));
                waitMs.set(System.currentTimeMillis() - t0);
                Thread.sleep(holdMs);
                lease.close();
                if (requireRelease) {
                    awaitTrue(() -> !lockExists(user), 3_000);
                }
                done.set(true);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        t.setDaemon(true);
        t.start();
        return waitMs;
    }

    @Test
    void sameUserTwoSessionsShouldSerialize() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            // 无 Redis 环境（CI 单测无 redis service）直接跳过：本类是真实 Redis 并发实测，
        // 生产行为由 e2e X20（mock 沙箱组自带 Redis）门禁兜底
        assumeTrue(pingOk(), "e2e-redis(:16379) 不可达，跳过真实 Redis 并发实测");
            String user = "verify-alice-" + System.nanoTime();
            // 守卫独立连接（与生产同构：所有会话共用一个 guard/facade）
            try (var facade = RedisConnectionFacade.create(
                    new AgentRedisProperties(REDIS_URL, 2000, 2000, 250_000))) {
                var guard = new RedisSandboxExecutionGuard(facade, 900_000L);
                var aDone = new AtomicBoolean(false);

                // 会话 A（s1）：先发起，持锁 3s（模拟长程任务回合）
                var aWait = spawnTurn(guard, user, "s1", 3_000, true, aDone);
                assertTrue(awaitTrue(() -> lockExists(user), 5_000),
                    "A 拿锁后锁键应存在（环境脏轮）");
                assertTrue(aWait.get() < 500, "A 首次拿锁不应等待（实际 " + aWait.get() + "ms）");

                // 会话 B（s2）：同用户另一会话并发发起回合，度量排队时长
                var bDone = new AtomicBoolean(false);
                var bWait = spawnTurn(guard, user, "s2", 100, false, bDone);
                assertTrue(awaitTrue(() -> bDone.get(), 20_000),
                    "B 未在 20s 内进入（环境脏轮：正常应在 A 释放后一个自旋周期内进入）");
                long wait = bWait.get();
                if (wait < 2_000 || wait > 8_000) {
                    System.out.printf("[verify] 第 %d 轮脏数据（B 等待 %dms 不在 [2s,8s] 窗口，疑 fail-open/抖动），重试%n",
                            attempt, wait);
                    continue;
                }
                assertTrue(awaitTrue(() -> aDone.get(), 5_000), "A 释放应完成");
                assertTrue(!lockExists(user), "A 释放后锁键应消失");
                System.out.printf("[verify] ✔ 同用户两会话串行：A 持锁 3s，B 排队 %dms 后才进入回合（第 %d 轮）%n",
                        wait, attempt);
                assertTrue(wait > 2_000, "同用户第二个会话应被串行化排队");
                return;
            }
        }
        throw new AssertionError("3 轮均未取得干净数据——e2e-redis 链路抖动，结果不可信，请检查测试环境");
    }

    @Test
    void differentUsersShouldRunInParallel() throws Exception {
        for (int attempt = 1; attempt <= 2; attempt++) {
        assumeTrue(pingOk(), "e2e-redis(:16379) 不可达，跳过真实 Redis 并发实测");
            String alice = "verify-carol-" + System.nanoTime();
            String bob = "verify-bob-" + System.nanoTime();
            try (var facade = RedisConnectionFacade.create(
                    new AgentRedisProperties(REDIS_URL, 2000, 2000, 250_000))) {
                var guard = new RedisSandboxExecutionGuard(facade, 900_000L);
                var aDone = new AtomicBoolean(false);

                // alice：先发起，持锁 3s
                var aWait = spawnTurn(guard, alice, "s1", 3_000, true, aDone);
                assertTrue(awaitTrue(() -> lockExists(alice), 5_000), "alice 拿锁后锁键应存在");
                assertTrue(aWait.get() < 500, "alice 拿锁不应等待");

                // bob：不同用户并发发起——不应被 alice 阻塞
                var bDone = new AtomicBoolean(false);
                var bobWait = spawnTurn(guard, bob, "s2", 100, false, bDone);
                assertTrue(awaitTrue(() -> bDone.get(), 10_000), "bob 应快速拿锁（跨用户无互斥）");
                long wait = bobWait.get();
                if (wait < 0 || wait > 1_500) {
                    System.out.printf("[verify] 第 %d 轮脏数据（bob 等待 %dms），重试%n", attempt, wait);
                    continue;
                }
                System.out.printf("[verify] ✔ 跨用户并行：alice 持锁 3s 期间 bob 仅等 %dms 即进入回合（第 %d 轮）%n",
                        wait, attempt);
                assertTrue(wait < 1_500, "跨用户不应串行");
                // 等 alice 回合完整收尾（含释放确认）再退出，避免 daemon 线程被 JVM 提前带走留下残键
                assertTrue(awaitTrue(() -> aDone.get(), 6_000), "alice 回合应完整收尾");
                assertTrue(!lockExists(alice), "alice 释放后锁键应消失");
                return;
            }
        }
        throw new AssertionError("2 轮均未取得干净数据——e2e-redis 链路抖动");
    }

    @Test
    void sameUserTwoSessionsShouldRunInParallelWhenSessionGranular() throws Exception {
        // sessionGranularLocks=true（per-session agent 隔离配套）：锁键用隔离键原值。
        // Channel 链路 ctx.userId=网关 peer=前端 sid → 两个会话两把锁，同用户并发不再排队；
        // 对照同文件 sameUserTwoSessionsShouldSerialize（flag=false 共享单例时代的每用户一把锁）。
        for (int attempt = 1; attempt <= 3; attempt++) {
        assumeTrue(pingOk(), "e2e-redis(:16379) 不可达，跳过真实 Redis 并发实测");
            // 生产 Channel 链路口径：ctx.userId = 前端 sid（peer-a/peer-b），session_user 登记其真实用户
            String peerA = "verify-peer-a-" + System.nanoTime();
            String peerB = "verify-peer-b-" + System.nanoTime();
            // 生产 4 参形态（store≠null + flag=true）：须证明反查分支被跳过（session_user 零打扰），
            // 防将来 resolveLockValue 前置判定换序导致误启反查（退回每用户一把锁）
            var store = org.mockito.Mockito.mock(io.agentmanager.framework.service.SessionUserStore.class);
            org.mockito.Mockito.when(store.findUserIdBySession(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("shared-alice");
            try (var facade = RedisConnectionFacade.create(
                    new AgentRedisProperties(REDIS_URL, 2000, 2000, 250_000))) {
                var guard = new RedisSandboxExecutionGuard(facade, 900_000L, store, true);
                var aDone = new AtomicBoolean(false);

                // 会话 A：先发起，持锁 3s
                var aWait = spawnTurn(guard, peerA, "s1", 3_000, true, aDone);
                assertTrue(awaitTrue(() -> lockExists(peerA), 5_000), "A 拿锁后锁键应存在");
                assertTrue(aWait.get() < 500, "A 拿锁不应等待");

                // 会话 B：同"真实用户"（登记层面）不同前端会话，锁键应不同 → 不排队
                var bDone = new AtomicBoolean(false);
                var bWait = spawnTurn(guard, peerB, "s1", 100, false, bDone);
                assertTrue(awaitTrue(() -> bDone.get(), 10_000), "B 应立即拿锁（会话粒度锁互不阻塞）");
                long wait = bWait.get();
                if (wait < 0 || wait > 1_500) {
                    System.out.printf("[verify] 第 %d 轮脏数据（B 等待 %dms），重试%n", attempt, wait);
                    continue;
                }
                assertTrue(awaitTrue(() -> aDone.get(), 6_000), "A 回合应完整收尾");
                assertTrue(!lockExists(peerA), "A 释放后锁键应消失");
                System.out.printf("[verify] ✔ 会话粒度锁：A 持锁 3s 期间同用户会话 B 仅等 %dms 即进入（第 %d 轮）%n",
                        wait, attempt);
                assertTrue(wait < 1_500, "会话粒度锁下同用户两会话不应串行");
                org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
                    .findUserIdBySession(org.mockito.ArgumentMatchers.anyString());
                return;
            }
        }
        throw new AssertionError("3 轮均未取得干净数据——e2e-redis 链路抖动");
    }

    @Test
    void leaseTtlExpiryShouldLetSecondTurnInWithoutRelease() throws Exception {
        // 复现 >TTL 长任务的竞态：持有者"永不释放"（模拟超长回合），TTL=1.5s 到期后
        // 同用户第二个会话可以直接拿锁——此时两回合并发共享同一沙箱 slot（#27 故障面）
        assumeTrue(pingOk(), "e2e-redis(:16379) 不可达，跳过真实 Redis 并发实测");
        String user = "verify-eve-" + System.nanoTime();
            try (var facade = RedisConnectionFacade.create(
                    new AgentRedisProperties(REDIS_URL, 2000, 2000, 250_000))) {
                var guard = new RedisSandboxExecutionGuard(facade, 1_500L);
                var holderAcquired = new AtomicBoolean(false);
                var holderDone = new AtomicBoolean(false);
                var secondWait = new AtomicLong(-1);
                var secondDone = new AtomicBoolean(false);

                // 持有者：拿锁后"工作"4s（>TTL）且【不】主动释放（模拟租约过期后仍在跑的超长回合）
                Thread holder = new Thread(() -> {
                    try {
                        guard.tryEnter(key(user, "s1"));
                        holderAcquired.set(true);
                        Thread.sleep(4_000);
                        holderDone.set(true);
                        if (lockExists(user)) {
                            awaitTrue(() -> !lockExists(user), 2_000); // 等自然过期收尾
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
                holder.setDaemon(true);

                Thread second = new Thread(() -> {
                    try {
                        long s = System.currentTimeMillis();
                        SandboxLease lease = guard.tryEnter(key(user, "s2"));
                        secondWait.set(System.currentTimeMillis() - s);
                        lease.close();
                        secondDone.set(true);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
                second.setDaemon(true);

                holder.start();
                assertTrue(awaitTrue(holderAcquired::get, 5_000), "持有者应先拿锁");
                second.start();
                assertTrue(awaitTrue(secondDone::get, 15_000), "第二会话 15s 内应拿到锁（TTL 过期后）");
                long wait = secondWait.get();
                assertTrue(wait >= 1_000 && wait <= 3_500,
                    "第二个会话应在租约 TTL 到期后进入（实际 " + wait + "ms）");
                assertTrue(!holderDone.get() || wait <= 3_500,
                    "复现前提：持有者回合（4s）未结束前第二会话已拿锁");
                System.out.printf("[verify] ✔ TTL 过期竞态复现：持有者未释放，第二会话在 %dms 后拿锁"
                        + "（≈TTL 1.5s，此时持有者仍在工作）%n", wait);
        }
}

}