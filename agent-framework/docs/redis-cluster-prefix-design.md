# Redis 集群与前缀支持设计（2026-09-28）

> 需求：agent-framework 使用的 Redis 要支持**集群（Redis Cluster）**与**前缀（key 隔离）**配置。
> 结论先行：新增 `agent.redis.mode / cluster-nodes / prefix` 三个配置叶子与一个连接门面
> `RedisConnectionFacade`，默认配置下行为与既有部署**逐字节一致**。

## 1. 背景

Redis 在框架内有两处使用（一个客户端 bean）：

| 用法 | 类 | key 形态 |
|---|---|---|
| session_event 事件流（durable SSE） | `service/RedisEventLog` | `sess:{sid}:events`（Stream）、`sess:{sid}:replies`（ZSET） |
| 沙箱并发守卫 | `sandbox/opensandbox/RedisSandboxExecutionGuard` | `sbx:guard:{scope}:{value}` |

两个驱动因素：

1. **Redis Cluster**：托管环境可能只提供集群模式；Lettuce 自带 cluster 支持，不引任何新依赖。
2. **key 前缀**：多个 OAF 服务各自起 agent-framework 实例**共用一个 oaf-redis** 时，
   `sbx:guard:user:{uid}` 会跨服务互锁（A 服务的沙箱会话锁住 B 服务同用户），`sess:*`
   事件流也可能串扰——各服务配不同前缀即可切分，无需后端改动（env 经既有 ConfigMap 透传）。

## 2. 配置（`AgentRedisProperties`）

| 字段 | env / yml | 默认 | 说明 |
|---|---|---|---|
| `mode` | `AGENT_REDIS_MODE` | `standalone` | `standalone` \| `cluster`（**sentinel 暂不支持**，枚举留位） |
| `clusterNodes` | `AGENT_REDIS_CLUSTER_NODES` | 空 | 逗号分隔种子节点；**空 = 回落 `url` 作单种子**（拓扑自动发现） |
| `prefix` | `AGENT_REDIS_PREFIX` | 空 | key 统一前缀；非空不以 `:` 结尾自动补；**拒绝空白与 `{ }`**（hash tag 语法） |

- `prefix` 默认空 = 行为与现状完全一致；**它是切分手段不是迁移工具**——改前缀后旧 key
  不可见（靠 TTL 自然消亡），须在首次部署时定好。
- 每个叶子都有 `@DefaultValue`（配置节缺失也能起，既有纪律）。
- record 新增组件 + 4 参兼容构造器（`RedisEventLogIT` 等 3 处按位置构造零改动）；
  **第二个构造器出现后必须显式 `@ConstructorBinding`**，否则 Spring 绑定报
  「No default constructor found」（测试守着这条）。

## 3. 连接门面（`redis/RedisConnectionFacade`）

```java
RedisConnectionFacade.create(props)          // mode 分流建 standalone/cluster 客户端
  .sync()   // RedisClusterCommands —— cluster 形状命令接口（standalone 是其子接口）
  .async()  // RedisClusterAsyncCommands —— 管道写入
  .key(raw) // 统一前缀拼接，所有 key 必经此入口
  .onFirstConnect(hook)                        // 首连自检钩子（RedisEventLog 持久性自检迁入）
  .close()                                     // @Bean(destroyMethod = "close")
```

- **接口同源是零成本的关键**（javap 核对 lettuce-core 6.3.2）：
  `RedisAsyncCommands extends RedisClusterAsyncCommands`、`RedisCommands extends RedisClusterCommands`，
  `flushCommands()` 在共有的 `BaseRedisAsyncCommands` 上。唯一类型分叉是连接对象
  （`StatefulRedisClusterConnection` 不继承 `StatefulRedisConnection`），门面内部分字段持有。
- 惰性连接 + 1s 建连退避（自 `RedisEventLog` 迁入）由两处共享；ClientOptions 语义不变
  （命令超时 / 断线 REJECT_COMMANDS；cluster 用 `ClusterClientOptions.Builder`，同名方法继承）。
- 纪律保留：URI 非法创建期抛 → 启动失败早暴露；不可达不影响启动；日志不打原始 URL。

## 4. cluster 特有的两个坑（规避方式）

1. **CROSSSLOT**：cluster 下多 key 命令要求同 slot。`deleteSession` 的 `DEL` 两条 key
   改为**两次单 key DEL**（standalone 等价，cluster 天然合法）。
2. **管道跨节点序**：`appendBatch` 依赖「ZADD 索引先于 XADD 数据」的管道顺序（截断安全的关键）。
   cluster 上不同 slot 的命令走不同节点连接，跨节点无顺序保证。规避：**cluster 模式下
   sid 加 hash tag**——`sess:{<sid>}:events`，两 key 必落同一 slot，管道序恢复成立；
   standalone 保持原 key 名（存量数据零迁移，滚动升级中活跃会话回放不中断）。

**hash tag 对 API 零影响**：key 名不出现在任何 API/SSE 响应里，事件对象只带
seq/type/payload/replyId，`<seq>-0` 事件 ID 与游标语义不动。唯一理论边缘是 sid 含 `}`
时 hash tag 收窄（只影响分布均匀性不影响正确性），本系统 sid 是 `tenant:uuid` 格式，不含 `}`。

## 5. 改动清单

| 文件 | 改动 |
|---|---|
| `config/AgentRedisProperties.java` | +mode/clusterNodes/prefix + `@ConstructorBinding` + 4 参兼容构造器 + prefix 校验 |
| `redis/RedisConnectionFacade.java` | **新增**：mode 分流 + ClientOptions + 惰性连接/退避 + key 前缀 + 首连钩子 |
| `service/RedisEventLog.java` | 构造器换门面；key 实例化（前缀 + cluster hash tag）；`deleteSession` 拆两次 DEL；自检改首连钩子 |
| `sandbox/opensandbox/RedisSandboxExecutionGuard.java` | 构造器换门面（`sbx:guard` 前缀段内聚，前缀经 `facade.key()`）；删内部连接缓存 |
| `config/AgentScopeConfig.java` | `redisClient` bean → `redisConnectionFacade` bean；守卫注入点同步 |
| `src/main/resources/application.yml` | `agent.redis` 节 +3 叶子（env 占位符） |
| 测试 | `AgentRedisPropertiesTest`（新叶子默认/覆盖/prefix 校验/构造绑定）；新增 `RedisConnectionFacadeTest`（key 组合 × 模式、失败语义）；`RedisEventLogIT`/`SessionEventStoreCrossReplicaIT` 改门面构造；守卫测试改门面构造 |

不改动：`SessionEventStore`（语义层零感知）、`SessionEventBus/Tailer`、`SessionCleanupService`。

## 6. 运维语义

- **平台多 Agent 部署**：每个 OAF 服务的发布 env 里配各自 `AGENT_REDIS_PREFIX`（如 `ag1:`）。
- **cluster 切换**：`AGENT_REDIS_MODE=cluster`，单种子通常够（拓扑自动发现）；
  自检（CONFIG GET）在集群上只代表被路由到的单节点——自检是 advisory 日志，可接受。
- **TLS**：`rediss://` 由 Lettuce URI 原生支持，零代码。

## 7. 验证

- `mvn test`：1101 用例 / 0 失败 / 0 错误 / 4 跳过（2026-09-28 实跑）。
- `RedisEventLogIT` / `SessionEventStoreCrossReplicaIT`（`REDIS_IT=1` + 真 Redis）走
  standalone 路径，key 格式不变，原样适用；cluster 路径的 key 形态由
  `RedisConnectionFacadeTest` + hash tag 语义单测覆盖。
