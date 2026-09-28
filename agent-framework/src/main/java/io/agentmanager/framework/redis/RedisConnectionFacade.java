package io.agentmanager.framework.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.config.AgentRedisProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.cluster.ClusterClientOptions;

/**
 * Redis 连接门面：按 {@link AgentRedisProperties#mode()} 建 standalone / cluster 客户端，
 * 对上层（{@code RedisEventLog} / {@code RedisSandboxExecutionGuard}）统一暴露
 * **cluster 形状的命令接口**——Lettuce 6.3.2 中 standalone 的命令接口继承自 cluster 接口
 * （{@code RedisAsyncCommands extends RedisClusterAsyncCommands}、
 * {@code RedisCommands extends RedisClusterCommands}，已用 javap 核对），
 * 所以面向 cluster 接口编程对 standalone 零成本。
 *
 * <p><b>职责收拢</b>：客户端构建（mode 分流）、ClientOptions（命令超时 / 断线 REJECT_COMMANDS）、
 * 惰性连接 + 建连失败退避（自 {@code RedisEventLog} 迁入，守卫与事件日志共享）、
 * key 前缀、首连自检钩子。上层不再持有 {@code RedisClient}，也不再各自维护连接缓存。
 *
 * <p><b>与既有语义的对应</b>：
 * <ul>
 *   <li>「配置错 vs 不可达」：URI 非法在 {@link #create} 期抛出 → 启动失败早暴露；
 *       Redis 不可达不影响启动（惰性连接），首次使用时才暴露。</li>
 *   <li>断线期间命令立刻失败而不是缓冲（REJECT_COMMANDS）：agent 线程要立刻拿到失败
 *       （append 返回 -1、回放报错），而不是阻塞到重连成功。</li>
 *   <li>日志只打 host/port 摘要，**不打原始 URL**——URL 里可能带密码。</li>
 * </ul>
 *
 * <p><b>cluster 特有</b>：{@code ClusterClientOptions.builder()} 继承 {@code ClientOptions.Builder}，
 * socket/timeout/disconnected 三项设置与 standalone 完全同名；拓扑自动发现，种子节点只求其一可达。
 * CONFIG GET 类自检在集群上只代表被路由到的单节点——自检本就是 advisory 日志，可接受。
 */
public final class RedisConnectionFacade implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisConnectionFacade.class);

    /**
     * 建连失败后的退避窗口（毫秒）。Redis 不可达时，若每次操作都去 connect，
     * 每次都会阻塞到 connectTimeout（默认 2s）——等于把 Redis 的故障放大成
     * 「每条 append 卡 2 秒」。窗口内的调用立刻抛上一次的异常，不再尝试建连。
     */
    private static final long CONNECT_BACKOFF_MS = 1000;

    private final AgentRedisProperties props;
    private final String prefix;
    private final boolean clusterMode;

    /** standalone：RedisClient + StatefulRedisConnection；cluster：RedisClusterClient + StatefulRedisClusterConnection。二者类型无继承关系，分字段持有。 */
    private final RedisClient standaloneClient;          // cluster 模式下为 null
    private final RedisClusterClient clusterClient;      // standalone 模式下为 null

    /** 惰性连接：启动时不建连，所以 Redis 可达与否不影响服务启动 */
    private volatile StatefulRedisConnection<String, String> standaloneConn;
    private volatile StatefulRedisClusterConnection<String, String> clusterConn;

    /** 上一次建连失败的原因（退避窗口内直接复用它抛出，避免造 Lettuce 异常对象） */
    private final java.util.concurrent.atomic.AtomicReference<RedisException> lastConnectFailure =
        new java.util.concurrent.atomic.AtomicReference<>();
    private volatile long lastConnectFailureAt = 0L;

    /** 首连成功后的自检钩子（如 RedisEventLog 的 appendonly/noeviction 持久性自检） */
    private final List<Consumer<RedisClusterCommands<String, String>>> firstConnectHooks =
        new ArrayList<>();

    private RedisConnectionFacade(AgentRedisProperties props, RedisClient standaloneClient,
                                  RedisClusterClient clusterClient) {
        this.props = props;
        this.prefix = props.normalizedPrefix();
        this.clusterMode = props.isCluster();
        this.standaloneClient = standaloneClient;
        this.clusterClient = clusterClient;
    }

    /**
     * 按配置建门面。URI 非法抛 {@link IllegalArgumentException} → bean 创建失败 → 启动失败
     * （打包/发布错误早失败早发现；Redis **不可达**则不影响启动，走惰性连接）。
     */
    public static RedisConnectionFacade create(AgentRedisProperties props) {
        if (props.isCluster()) {
            var client = RedisClusterClient.create(seedUris(props));
            client.setOptions(clusterOptions(props));
            logClusterSummary(props, client);
            return new RedisConnectionFacade(props, null, client);
        }
        var client = RedisClient.create(RedisURI.create(props.url()));
        client.setOptions(standaloneOptions(props));
        log.info("RedisClient configured (mode=standalone, host={}:{}, db={}, commandTimeout={}ms, "
                + "connectTimeout={}ms, maxLenPerStream={}, prefix=\"{}\")",
            RedisURI.create(props.url()).getHost(), RedisURI.create(props.url()).getPort(),
            RedisURI.create(props.url()).getDatabase(),
            props.commandTimeoutMs(), props.connectTimeoutMs(), props.maxLenPerStream(),
            props.normalizedPrefix());
        return new RedisConnectionFacade(props, client, null);
    }

    private static List<RedisURI> seedUris(AgentRedisProperties props) {
        var nodeStrings = props.clusterNodeList();
        if (nodeStrings.isEmpty()) {
            // 空 = 回落 url 作单种子（拓扑自动发现）
            nodeStrings = List.of(props.url());
        }
        var uris = new ArrayList<RedisURI>(nodeStrings.size());
        for (var s : nodeStrings) {
            uris.add(RedisURI.create(s));
        }
        return uris;
    }

    /** standalone 客户端选项（与迁移前 AgentScopeConfig.redisClient 逐项一致）。 */
    private static ClientOptions standaloneOptions(AgentRedisProperties props) {
        return baseOptionsBuilder(props).build();
    }

    /** cluster 客户端选项：Builder 继承自 ClientOptions.Builder，基础项同名复用。 */
    private static ClusterClientOptions clusterOptions(AgentRedisProperties props) {
        return (ClusterClientOptions) baseOptionsBuilder(props).build();
    }

    private static ClientOptions.Builder baseOptionsBuilder(AgentRedisProperties props) {
        var builder = props.isCluster()
            ? ClusterClientOptions.builder()
            : ClientOptions.builder();
        return builder
            .socketOptions(SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(props.connectTimeoutMs()))
                .build())
            // 必须显式开命令超时：Lettuce 的 TimeoutOptions.DEFAULT_TIMEOUT_COMMANDS 是 false
            // （命令超时默认关闭），不钳住就是一条命令占住一个请求线程 60 秒（servlet/Tomcat 线程有限）。
            .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(props.commandTimeoutMs())))
            .autoReconnect(true)
            // 断线期间**拒绝**命令而不是缓冲：agent 线程要立刻拿到失败（append 返回 -1、回放报错），
            // 而不是阻塞到重连成功。缓冲还会在重连后一次性灌出一批陈旧写入。
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }

    private static void logClusterSummary(AgentRedisProperties props, RedisClusterClient client) {
        var seeds = props.clusterNodeList();
        if (seeds.isEmpty()) {
            seeds = List.of(props.url());
        }
        // 只打种子节点数与首个种子的 host:port 摘要；含凭据的完整 URL 不落日志
        log.info("RedisClusterClient configured (mode=cluster, seeds={}, first={}:{}:{}, "
                + "commandTimeout={}ms, connectTimeout={}ms, maxLenPerStream={}, prefix=\"{}\")",
            seeds.size(),
            RedisURI.create(seeds.get(0)).getHost(), RedisURI.create(seeds.get(0)).getPort(),
            RedisURI.create(seeds.get(0)).getDatabase(),
            props.commandTimeoutMs(), props.connectTimeoutMs(), props.maxLenPerStream(),
            props.normalizedPrefix());
    }

    // ---------------------------------------------------------------- key 前缀

    /**
     * 统一 key 组装入口：{@code <prefix><原始key>}。所有上层 key 必经此处，
     * 禁止自行拼接。前缀为空时原样返回（与既有部署逐字节一致）。
     */
    public String key(String rawKey) {
        return prefix + rawKey;
    }

    // ---------------------------------------------------------------- 连接

    /**
     * 同步命令视图（cluster 形状；standalone 的 {@code RedisCommands} 是它的子接口）。
     * 惰性建连 + 退避（逻辑自 {@code RedisEventLog} 迁入，语义不变）。
     */
    public RedisClusterCommands<String, String> sync() {
        if (clusterMode) {
            return clusterConnection().sync();
        }
        return standaloneConnection().sync();
    }

    /**
     * 底层 standalone 连接（惰性建连）。**生命周期归门面所有，调用方不得关闭**——
     * 仅供测试读连接级命令（TTL/XLEN/CONFIG 等）或诊断；常规命令走
     * {@link #sync()}/{@link #async()}。cluster 模式抛
     * {@link IllegalStateException}（cluster 连接是节点分区视图，单连接语义不成立）。
     */
    public StatefulRedisConnection<String, String> syncConnection() {
        if (clusterMode) {
            throw new IllegalStateException("syncConnection() 仅 standalone 模式可用");
        }
        return standaloneConnection();
    }

    /**
     * 前缀是否已配置（诊断用；key 组装本身只经 {@link #key(String)}）。
     */
    public boolean hasPrefix() {
        return !prefix.isEmpty();
    }

    /**
     * 异步命令视图（管道写入用）。两种模式同源 {@code RedisClusterAsyncCommands}，
     * {@code flushCommands()} 在共有基接口上。
     */
    public RedisClusterAsyncCommands<String, String> async() {
        if (clusterMode) {
            return clusterConnection().async();
        }
        return standaloneConnection().async();
    }

    /** 当前连接是否存活（供上层探测；未建连返回 false，不触发建连）。 */
    public boolean isOpen() {
        var c = clusterMode ? clusterConn : standaloneConn;
        return c != null && c.isOpen();
    }

    /** 是否 cluster 模式（上层 key 组装需要感知：hash tag 只在 cluster 下加）。 */
    public boolean isClusterMode() {
        return clusterMode;
    }

    /**
     * 注册首连自检钩子（成功跑一次；失败不置位、下次建连重试）。
     * 必须在建连前注册——由装配期 bean 构造顺序保证。
     */
    public void onFirstConnect(Consumer<RedisClusterCommands<String, String>> hook) {
        firstConnectHooks.add(hook);
    }

    /**
     * 取连接，必要时建连（并在建连成功后跑一次自检钩子）。
     * 退避窗口内的调用立刻抛上一次的异常，不再尝试建连。
     */
    private StatefulRedisConnection<String, String> standaloneConnection() {
        var c = standaloneConn;
        if (c != null && c.isOpen()) {
            return c;
        }
        synchronized (this) {
            if (standaloneConn != null && standaloneConn.isOpen()) {
                return standaloneConn;
            }
            checkBackoff();
            try {
                var fresh = standaloneClient.connect();
                standaloneConn = fresh;
                lastConnectFailure.set(null);
                runFirstConnectHooks(fresh.sync());
                return fresh;
            } catch (RedisException e) {
                recordFailure(e);
                throw e;
            }
        }
    }

    private StatefulRedisClusterConnection<String, String> clusterConnection() {
        var c = clusterConn;
        if (c != null && c.isOpen()) {
            return c;
        }
        synchronized (this) {
            if (clusterConn != null && clusterConn.isOpen()) {
                return clusterConn;
            }
            checkBackoff();
            try {
                var fresh = clusterClient.connect();
                clusterConn = fresh;
                lastConnectFailure.set(null);
                runFirstConnectHooks(fresh.sync());
                return fresh;
            } catch (RedisException e) {
                recordFailure(e);
                throw e;
            }
        }
    }

    private void checkBackoff() {
        var failure = lastConnectFailure.get();
        if (failure != null
                && System.currentTimeMillis() - lastConnectFailureAt < CONNECT_BACKOFF_MS) {
            throw failure;   // 退避窗口内：立刻失败，不阻塞调用线程
        }
    }

    private void recordFailure(RedisException e) {
        lastConnectFailure.set(e);
        lastConnectFailureAt = System.currentTimeMillis();
    }

    private void runFirstConnectHooks(RedisClusterCommands<String, String> sync) {
        if (firstConnectHooks.isEmpty()) {
            return;
        }
        for (var hook : firstConnectHooks) {
            try {
                hook.accept(sync);
            } catch (Exception e) {
                // 自检失败不影响功能（如 CONFIG GET 被托管 Redis 禁用）
                log.warn("RedisConnectionFacade: 首连自检钩子异常（忽略）: {}", e.toString());
            }
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void close() {
        if (standaloneClient != null) {
            standaloneClient.shutdown();
        }
        if (clusterClient != null) {
            clusterClient.shutdown();
        }
    }
}
