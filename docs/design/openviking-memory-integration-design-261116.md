# OpenViking 记忆接管方案 - 261116

## 1. 背景与目标

当前 agent-framework 的长期记忆是 AgentScope Harness SDK 内置的文件式记忆:`MEMORY.md` + `memory/*.md` 存于 per-service checkpoint 库的 `agent_fs` KV 表(`agents/{agent}/users/{userId}` 命名空间,per-service × per-user 粒度,跨会话共享)。该方案将其整体迁移至 OpenViking(火山引擎开源的 AI Agent 上下文数据库)托管,平台不再维护本地记忆链路,并解决:

1. 平台多用户(users/workspaces)与 OpenViking 多租户(account/user)的自动映射对接
2. 「每个 agent 服务 = 一个 OpenViking 租户,每用户一个 user key」方案的可行性裁决
3. OpenViking MCP 对接的可行性

## 2. OpenViking 能力摘要(事实源)

| 维度 | 事实 |
|---|---|
| 定位 | 上下文数据库:知识(resources)/记忆(memories)/技能(skills)统一为 `viking://` 虚拟文件系统,支持 L0/L1/L2 分层加载与目录级语义检索 |
| 形态 | 单容器 Python 服务 `:1933`,REST 与 MCP `/mcp` 同进程;数据落本地卷(SQLite + 文件),无外部 DB 依赖;healthcheck `/health` |
| 租户模型 | `account_id`(外层租户边界,ROOT 建/删)+ `user_id`(账户内用户边界);ROOT/ADMIN/USER 角色;存储层自动加 `/local/{account_id}/` 前缀 |
| 隔离边界 | memories/sessions/user 级隔离;`viking://resources` 与 `viking://agent/skills` account 内共享(ACL 可收紧);跨 account 一律隔离 |
| 认证 | `api_key`(root key 管 Admin API,user key 管数据面,key 三段式 `b64(account).b64(user).b64(secret)`,服务端从 key 解析身份)/ `trusted`(网关注入 `X-OpenViking-Account`/`X-OpenViking-User` 头 + 部署级 `root_api_key` 认证)/ OIDC / LDAP / dev(仅 loopback) |
| Admin API | `POST /api/v1/admin/accounts`(建账户+首个 admin,返回 admin user_key)、`POST .../accounts/{id}/users`(注册用户,返回明文 user_key)、`GET/DELETE .../users/{uid}`、`PUT .../users/{uid}/role`、`POST .../users/{uid}/key`(轮换;支持 seed 确定性派生) |
| trusted 模式 | 数据面**无需预注册用户/预配 key**,身份头即时生效,身份异步批量落库(默认 5 分钟);Admin API 凭 root key 仍可用;要求仅部署于可信网络边界内 |
| 记忆管线 | `remember(messages)` 提交后台提取任务;session commit 归档会话并提取记忆为 Markdown(可检视/编辑);`search(mode=context)` 组装注入就绪上下文;`find` 快速检索;`read/write/edit/grep/tree` 文件系统语义 |
| MCP | 原生 `/mcp` streamable HTTP,鉴权同 REST(Bearer/X-API-Key);工具含 find/search/read/write/edit/remember/forget/add_resource/add_skill/rule_search/privacy_*/get_acl/set_acl 等 |
| 依赖 | 必须 embedding 模型 + VLM(`processing_mode=vectors_only` 可跳过 VLM);支持 Volcengine/OpenAI/GLM/Kimi/Ollama |
| 许可 | AGPLv3 + 禁止整体重写再开源;仅部署使用、API 集成、不改源码 → 不触发开源义务 |

## 3. 租户映射模型(问题一:多用户自动对接)

| 平台概念 | OpenViking 概念 | 派生规则 |
|---|---|---|
| 业务 agent 服务(`services` 行) | `account_id` | 与 per-service checkpoint 库名/Redis 前缀派生同源(复用 issue #142 的 k8s 名隔离键派生,SSOT) |
| 平台用户(users.id,网关注入的 X-User-Id) | `user_id` | 原值透传(格式 `[a-zA-Z0-9_.@-]`,平台自增/雪花 ID 兼容,PoC 验证) |
| OAF 包 knowledge/ 知识库 | `viking://resources/`(account 内共享) | 二期:发布时导入 |
| 服务级共享技能 | `viking://agent/skills/`(account 内共享) | 二期 |
| 用户长期记忆 | `viking://user/{userId}/memories/` | 一期核心,替代 agent_fs KV |

映射后隔离粒度与现状完全同构(per-service × per-user),且服务间隔离从「per-service checkpoint 库」升级为「account 边界」——OpenViking 服务端强制,不再依赖派生库名约定。

## 4. 身份对接:三凭证分层(修订:采纳「每 agent 一个 account key」,root key 不下发)

### 硬约束:api_key 模式下身份绑定在 key 内

`X-OpenViking-Account/User` 身份头仅在 trusted 模式生效;api_key 模式下服务端从 key 解析身份,且「an ADMIN key accesses data as its own user and **cannot switch identity**」。因此:

- **account 级 ADMIN key 不能独自承载业务流量**——直接用它读写记忆会把所有用户数据混入 admin 用户空间,per-user 隔离失效;
- per-user 身份必须用 **per-user user key** 表达。关键在于:Admin API 允许 **ADMIN 在本 account 内注册用户并生成/轮换 user key**(`POST /admin/accounts/{id}/users` — ROOT, ADMIN),且支持 **seed 确定性派生**(`key secret = sha256(user_id + "\0" + seed)`),业务面可本地推导、无需存储 key 表。

### 推荐路线:root key 只留控制面,业务面三凭证分层

| 凭证 | 持有者 | 能力面 | 来源 |
|---|---|---|---|
| `root_api_key` | **仅 backend**(控制面) | 建户/删户(`POST/DELETE /admin/accounts`)、运维诊断;设计上不能访问租户数据 | OpenViking 部署配置(平台 `.env.secrets`) |
| `ADMIN key`(account 级) | 仅该服务 Pod(服务 Secret) | 本 account 内用户管理(开户/轮换)+ 以 admin 自己身份访问数据(服务身份,承载共享 resources/skills 与 MCP 连接) | 发布时 backend 建户响应中的 `user_key`(admin_user_id = `svc-system`) |
| `USER key`(per-user) | 业务面本地推导,不落库 | 该 (account, user) 的记忆/会话数据 | 首次对话 lazy 开户(seed 派生) |

数据面调用流程(agent-framework 内部,非业务 agent 可控):

```
platform-router(introspect)→ X-User-Id → agent-framework Pod
  VikingMemoryClient:
    user_key = b64(account).b64(userId).b64(sha256(userId+"\0"+OV_USER_SEED))   ← 本地确定性推导
    请求带 X-API-Key: user_key → 服务端按 key 解析 (account, user)
    首个请求 401(user 未注册)→ 持 ADMIN key POST /admin/accounts/{account}/users
                                  {user_id, seed: OV_USER_SEED} → 重试(幂等)
    服务身份操作(共享 resources/skills/MCP 连接)→ X-API-Key: ADMIN key(svc-system)
                                                    ↓
                                       外部 OpenViking 服务(网络可达,不经 backend/Ingress)
```

- **自动化创建:可以,且全部 lazy 化**——发布时 backend 只建 account(1 次 Admin 调用);用户 key 由业务面在首次对话时按需开户(幂等),平台无需预建用户、无需同步 workspace 成员、无 key 存储表;新用户开户仅发生在该用户真的与该服务对话时
- **root_api_key 对每个 agent 确实不需要**(你的判断成立):它只在发布/删除服务这两个控制面动作出现,业务面永远见不到
- **隔离全部由 OpenViking 服务端强制**:服务间 = key 绑定 account(伪造其他 account 的 key 在服务端校验不过);用户间 = key 绑定 user。相比 trusted 模式「框架层约定固定 account 头」的应用层隔离,这是硬边界
- **凭证泄露影响面收敛到单 account**:admin key / seed 走平台敏感键自动路由服务 Secret 机制(与 AUTH_TOKEN 同路);泄露仅波及该 agent 服务的记忆,可轮换(admin key 自轮换 + 换 seed 全量重开户,均为 Admin API 幂等操作)

### 备选(已降级):trusted 模式 + 网关注入身份头

`X-API-Key: root_api_key + X-OpenViking-Account(env 固化) + X-OpenViking-User(X-User-Id)`。优点是数据面零 key 管理(身份异步自动落库);缺点是部署级 root key 必须下发到每个业务 Pod,任何持有者可注入任意 account/user 头,服务间隔离退化为应用层约定。仅在 seed 派生 PoC 验证失败时作为回退(回退时需配 Redis key 缓存表,开户响应的明文 user_key 落 per-service 前缀缓存,丢失可 ADMIN regenerate 恢复)。

**结论:问题二的回答(修订)**——「每个 agent 按 OpenViking 租户管理」✅;「使用 account key 后自动化创建」✅ 完全可行且是推荐路线(root key 留控制面、ADMIN key 管账户、user key 管数据);per-user key 从上版的「二期 opt-in」上调为一期主路线——其此前的架构错配(root key 下发/控制面依赖/key 表存储)在三凭证分层 + seed 派生下全部消除。

## 4.5 PoC 验证结果(261116 实测,宿主 docker openviking :1933,v0.5.5,api_key 模式)

| 验证项 | 结果 | 实测延迟 |
|---|---|---|
| root key 建户(`POST /admin/accounts`)→ ADMIN key | ✅ | ~60 ms |
| ADMIN 开户带 seed(`POST /admin/accounts/{a}/users`) | ✅ | 57 ms |
| **seed 派生本地推导 key 与服务端一致** | ✅ **PASS**:`secret = b64url(hex(sha256(user_id + "\0" + seed)))` 三段式 | — |
| 纯本地推导 key(不经服务端返回)直接通过数据面认证 | ✅ 身份完全来自 key,免存储方案成立 | ls 8 ms |
| 数据面 write(异步)/ read / ls | ✅ | 20 / 7 / 8-16 ms |
| MCP `/mcp`(streamable HTTP,SSE):initialize / tools/list(27 工具)/ tools/call | ✅ | 8 / 14 / — ms |
| MCP `remember` 提交 → 后台提取(VLM mimo)→ `memories/entities/{实体}/` 落库 | ✅ 提交即返,提取 30.5 s 全后台 | 提交 66 ms |
| 语义检索(search/find) | ⚠️ **未能实测**:embedding/rerank 走 openrouter 免费档(50 次/日)已耗尽,429 | 降级路径 1148 ms |
| mimo(xiaomimimo)作 embedding 备选 | ❌ 无 `/embeddings` 端点(404) | — |

两个影响设计的实测发现:

1. **search `mode=context` 在 embedding 上游 429 时静默返回 `ok` + 空 entries**(`stats.retrieval_error` 里才有错误);`find` 则显式 429 fail-fast。集成时 fail-soft 判据必须检查 `stats.retrieval_error`,否则上游故障会被误判为「用户没有记忆」——比报错更危险
2. 建户响应含明文 admin key、轮换 key 即时生效(幂等),控制面重试安全

**生产前置条件**:embedding/rerank 供给由 OpenViking 侧部署配置保障(属 OpenViking 运维配置,**不在本设计范围**);实测免费档(50 次/日)不可用,OpenViking 运维方需自行切换。集成侧不感知 provider 选型,以「超时预算 + fail-soft(含 `stats.retrieval_error` 判据)」兜底任何上游供给中断。VLM(mimo)已验证可用。

## 4.6 性能影响分析(基于实测)

对比基线(现状):首推理 = agent_fs KV 点查读穿(集群内 MySQL,~1-5 ms)+ 沙箱全量注入;turn 写 = SDK 节流 10 min 后台 flush。

| 路径 | 现状 | 接管后 | 判定 |
|---|---|---|---|
| 首推理(热路径) | KV 点查 ~ms | `search(mode=context)`:外部 embedding + 向量检索 + rerank + 组装;降级路径实测 1.15 s(含 429 重试 3 次),真实语义路径预估 p50 0.5-1.5 s | **唯一负面项:TTFT +0.5~2 s** |
| turn 结束 | SDK 后台 flush | `remember` 提交实测 66 ms 即返,提取 30 s 全后台 | 持平(均异步) |
| 记忆写入 | MySQL KV 写 | `write` 异步 20 ms(实测) | 同量级 |
| MySQL 负载 | 记忆命名空间读写/枚举(memory/ 枚举曾是专项优化 L4) | 消失 | **正向减负** |
| 沙箱注入 | 全量注入 MEMORY.md/memory/*.md | 取消,按需检索 | **正向简化** |
| 横向扩展 | per-session KV | key 本地推导、客户端无状态 | 不影响双副本门禁 |

首推理延迟缓解(按优先级):

1. **session 内缓存**:同 session 第 2 turn 起复用首轮检索结果(TTL 或消息数失效),摊薄后多数 turn 为 0 额外延迟
2. **超时预算 2 s + fail-soft**:超时降级为无记忆继续对话(含 `stats.retrieval_error` 判据)
3. **上游供给**:OpenViking 侧模型配置(含延迟/重试参数)不在本文档范围;集成侧按最坏情况设 2 s 超时,供给劣化由 fail-soft 吸收
4. **轻量路径**:非首推理场景用 `find`(fail-fast)而非 `search(context)`

容量:单副本 OpenViking(Python)承接全部服务的检索 QPS + 每次检索 1 个外部 embedding 调用;成本 = 每 write/remember/search ≥1 次 embedding + remember 每次提取多轮 VLM(实测 30 s 处理)。上线前需以目标并发压测,并把「embedding 供给中断」纳入 fail-soft 演练(实测证明它真的会发生)。

## 5. 控制面自动对接(backend 改动)

挂在发布状态机生命周期内,全部同步、显式、失败即发布失败(记忆是服务能力的一部分):

| 事件 | OpenViking 动作 | 说明 |
|---|---|---|
| Publish 成功(服务就绪) | `POST /admin/accounts` `{account_id, admin_user_id: "svc-system"}`(root key) | 响应中的 `user_key`(ADMIN key)与平台生成的 `OV_USER_SEED`(随机)经敏感键自动路由服务 Secret;非敏感 env 注入 `OPENVIKING_URL` / `OPENVIKING_ACCOUNT`。admin_user(`svc-system`)即服务身份,承载共享 resources/skills 与 MCP 连接 |
| Republish / update_env | 无动作 | account 幂等已存在(409 视为成功),Secret 不变 |
| Unpublish(下线) | 无动作 | 数据保留,与「下线不删数据」语义一致 |
| Delete(删除) | `DELETE /admin/accounts/{id}`(root key) | 与服务数据同生命周期;删除前可选导出(二期) |
| workspace 成员变更 | 无动作 | 用户开户由业务面 lazy 完成(见 §4),无需成员同步 |
| 用户首次对话 | 业务面 lazy 开户(ADMIN key,seed 派生) | 非 backend 动作;控制面不参与数据面身份 |

实现:backend 新增 `VikingAdminClient`(Go,官方 go-sdk 或裸 HTTP,Admin API 仅 8 个端点,裸 HTTP 即可);K8s manifest 新增 openviking Deployment(单副本)+ PVC + Service(`manifests/` 自举清单)。

## 6. 业务面记忆接管(agent-framework 改动)

关闭 SDK 内置记忆(`AGENT_MEMORY_ENABLED=false` 已有总开关),以 `VikingMemoryClient` 组件(HTTP 直连,Java 无官方 SDK,REST 完备)替换记忆链路,装配进 `HarnessAgentFactory`:

| 现链路(SDK 文件记忆) | 新链路(OpenViking) |
|---|---|
| MemoryFlushMiddleware 定时 flush memory/*.md → agent_fs KV | turn 结束异步 `remember(messages)`,后台提取 |
| MemoryConsolidator 合并 MEMORY.md | OpenViking 记忆管线(提取/去重/整合),`viking://user/{uid}/memories/` |
| 首推理全量注入 MEMORY.md + KV 读穿 | 首推理 `search(mode=context, max_tokens 预算)` 组装注入上下文 |
| 内置工具 memory_search/get/save | 同名工具桥接 `find` / `read` / `write`(viking://~/memories) |
| 沙箱全量注入 /workspace 记忆文件 | 取消记忆文件注入,按需检索(注入链路简化) |

工程约束:

- **凭证与身份**:`VikingMemoryClient` 从服务 Secret 读 `OV_ADMIN_KEY`/`OV_USER_SEED`,按 X-User-Id 本地推导 user_key(§4);开户幂等(已存在视为成功),401 重试一次。seed 派生编码细节(sha256 hex/raw → base64url)为 PoC 首验证项,失败则回退 trusted/Redis 缓存方案
- **fail-soft**:OpenViking 不可用时对话降级为无记忆(对齐 McpToolRegistrar fail-soft 语义与原则一「平台内部故障不波及业务面」),检索超时预算 ~2s
- 会话历史(`agent_state`/`session_message`)不迁移:那是业务面正确性状态,继续按原则二存共享 MySQL;OpenViking 只接管长期记忆/知识/技能。二期可选 session commit 归档
- 共享连接池 per-Pod,VikingMemoryClient 无状态(key 本地推导、不落共享存储),多副本可横向扩展(与 e2e-multi 双副本门禁兼容)

## 7. MCP 对接可行性(问题三)

**可行,且现机制零改动即可用——但必须分层使用,per-user 记忆不能走 MCP**:

| 用途 | 通道 | 原因 |
|---|---|---|
| 服务级共享上下文(resources 知识库、agent/skills、rule_search) | MCP:`/mcp` streamable HTTP + 服务身份固定 Bearer 头(`svc-system` 的 ADMIN key),OAF 包 `mcp-configs/config.yaml` 声明,现有 `McpToolRegistrar` + permissions(allow/ask)/read_only/sharedWrappers 全套复用 | MCP 连接头是连接级静态配置,一个连接 = 一个身份;服务身份恰好是 account 级共享数据的正确主体 |
| per-user 记忆 | **不走 MCP**,走第 6 节 REST 中间件(per-user user_key per-request 认证) | 共享 MCP 连接(sharedWrappers 复用)无法 per-user 换身份;每用户每会话独立建连则连接爆炸且 OAF 包配置无法表达动态身份。我们的 `McpUserContextMiddleware` 写的 McpMeta 是自定义元数据,OpenViking 不识别 |

MCP 工具中 `write/edit/forget/add_resource` 等具备写能力,per-OAF `permissions.tools` 控制(ask 走 HITL 确认流,与现有体系兼容);`read_only` 不适用于会短路 ask 流的服务(同 release-agent 约束)。

**实现现状回填(2026-11-23,issue #415)**:上表「全套复用」以**程序化合成注册**落地——OpenViking 地址/凭据来自平台注入 env(OVK_ADMIN_KEY 走服务 Secret),无法走文件声明的 connection 节,故不声明于 frontmatter mcpServers,由 `HarnessAgentFactory.registerVikingSharedContextMcp` 构建期经 `McpToolRegistrar.registerSyntheticClient(toolkit, mcp, baseFactory)` 注入合成 server `openviking`(唯一注入点,frontmatter 已声明同名 server 时 WARN 跳过,declared 声明优先)。与声明的差异与共性:

- **权限**:包内 `mcp-configs/openviking/config.yaml` 仅 `permissions.tools` 节(permissions-only)即生效,注册时装载、`collectPermissionRules` 与 declared server 同款翻译为 `mcp__openviking__{tool}` 规则——上表「permissions 全套复用」的兑现;frontmatter `config.permission.tools` 不承载 MCP 工具(声明 MCP 注册名忽略并告警)
- **句柄**:工厂产物 `UserScoped(EMPTY)` 装饰的 base,包 `ReconnectableMcpClientWrapper` 入共享缓存(与 declared 的 `buildBaseClient` 产物形态对称;注册与换芯共用同一工厂);McpConnectionWatchdog 探测名单含合成注册表,失联对句柄原地换芯,无句柄按 agent 有无分流补偿(单例在→工厂+registerSyntheticClient 补注册;纯会话隔离→仅 `cacheSyntheticClient` 入缓存,新会话 build 经 putIfAbsent 复用)
- **生命周期**:A 路径 reload(`reloadMcpAll`)「声明已删除」循环豁免合成 server(不由 frontmatter 声明/撤销,下线唯一入口是 OVK_MCP_SHARED_CONTEXT env 变更 + rollout);B 路径整包重建前 evict 共享缓存不清合成注册表——build 失败回滚窗口内旧 agent 的合成句柄仍可被看门狗自愈;名字被 frontmatter 转为同名声明时由 `reloadAgent` 在新代建立成功后移除注册表残留项(`removeSyntheticServers`,该名此后完全归属 declared 链路,杜绝注册表优先分流串台 declared 连接/双探测/陈旧权限覆盖;collectPermissionRules 构建期同步跳过 declared 同名兜底)
- **补偿路径权限快照边界(平台既有行为,非合成特有)**:启动期/补偿期注册成功的工具,权限规则不在既有 agent 的 PermissionContextState 内(SDK 引擎构造期快照),DEFAULT 模式按未覆盖兜底 ASK——declared server 的 compensate 同样如此,不为它扩权限系统运行期可变语义

## 8. 部署形态(裁决 261116:OpenViking 由外部提供服务,平台不部署)

平台是接入方,与外部 MySQL/Redis 同一模式。接入契约(四项):

1. 服务可达:OPENVIKING_BASE_URL 指向外部地址,业务 Pod 网络可直连(不经 backend)
2. 认证:server.auth_mode="api_key",root key 交 backend(OPENVIKING_ROOT_API_KEY),与外部运维约定后仅控制面持有
3. 模型供给:VLM/embedding(检索热路径)由外部运维保障——平台侧仅以 2s 超时 + fail-soft(含 stats.retrieval_error 判据)兜底
4. 集群内部署清单不进平台交付(曾起草 manifests/openviking.yaml,真机部署验证后按本裁决移除;验证期以集群内实例充当外部服务替身完成全链路)

验证环境注记(261116):kind 集群内曾按清单部署 OpenViking v0.5.5 完成 16/0 全链路验证;真机抓出并修正清单机制错误(OPENVIKING_CONF_CONTENT 为真实配置机制、维度变更须清向量存储、卷内 conf 已存在时跳过重写)——经验保留在本节备查,清单文件随裁决移除。

## 9. 存量迁移

一次性脚本(运维流程,非代码常驻):遍历各 per-service checkpoint 库 `agent_fs` → 按 namespace 解析 (agent, userId) → 对应 (account, user) → `write viking://user/{u}/memories/import/MEMORY.md` 等。未派生存量服务(共享 oaf_checkpoint)按服务名归入各自 account。迁移后 SDK 记忆链路关闭。

## 10. 风险与缓解

| 风险 | 缓解 |
|---|---|
| ~~seed 派生 key 的编码细节未经官方端到端确认~~ | **PoC 已验证(261116)**:`b64url(hex(sha256(user_id+"\0"+seed)))`,本地推导 key 通过认证,免存储方案成立 |
| embedding/rerank 上游供给中断(openrouter 免费档已实测耗尽;供给保障属 OpenViking 部署配置,不在本文档范围) | 集成侧兜底:fail-soft 判据含 `stats.retrieval_error`(search 静默降空,实测)、首推理超时 2 s 降级;OpenViking 运维方自行保障供给 |
| ADMIN key / seed 泄露波及单 account 全部用户记忆 | 敏感键路由服务 Secret + 集群内网络边界;轮换 = admin key 自轮换 + 换 seed 全量重开户(Admin API 幂等);影响面仅该服务 |
| OpenViking 单点(单副本 + PVC) | 与平台单副本水位一致;fail-soft 降级保证业务面可用性;恢复即自愈 |
| 首次对话多一次开户 RPC(新用户冷启动延迟) | 幂等 + 失败不阻塞对话(fail-soft 无记忆继续);开户后本地推导零查询 |
| 记忆检索延迟/成本(热路径调模型) | 检索超时预算 + fail-soft;`min_score/limit/max_tokens` 收敛;容量实测后定 provider 规格 |
| userId/account_id 格式约束不匹配 | PoC 验证(平台 ID 均为字母数字,预期兼容) |
| 记忆质量未经自场景验证(基准为 OpenViking 官方 LoCoMo/tau2) | PoC 用 release-agent/demo 服务实测记忆召回质量后再全面切换 |

## 11. 实施范围(261116 裁决:一期二期合并实施,配置可切换)

一次性落地全部能力,通过配置矩阵控制启用面——默认全关(行为零变化),逐服务/逐能力开启:

### 11.1 配置矩阵

**平台层(backend 启动 env / manifests)**:

| 配置 | 默认 | 说明 |
|---|---|---|
| `OPENVIKING_ENABLED` | `false` | 平台级总开关;false 时发布链路完全不感知 OpenViking(现状) |
| `OPENVIKING_BASE_URL` | — | 外部 OpenViking 服务地址 |
| `OPENVIKING_ROOT_API_KEY` | — | root key,仅控制面(建/删 account、per-user key 签发),来源 `.env.secrets` |

**服务层(publish 时 backend 注入,固化于服务 env,业务面不依赖 backend)**:

| 配置 | 默认 | 说明 |
|---|---|---|
| `AGENT_MEMORY_PROVIDER` | `local` | `local`=现状 SDK 文件记忆(agent_fs KV);`openviking`=记忆全链路走 OpenViking。切换 = update_env + rollout(标准发布流,不做运行时热切换) |
| `OPENVIKING_URL` | 平台值 | 数据面地址 |
| `OPENVIKING_ACCOUNT` | 派生 | 本服务 account_id(与隔离键派生同源) |
| `OVK_ADMIN_KEY`(Secret) | 建户响应 | svc-system 的 ADMIN key:lazy 开户 + 服务身份(MCP 连接/共享 resources 写入) |
| `OVK_USER_SEED`(Secret) | 随机生成 | per-user key 本地推导种子 |
| `OVK_SESSION_COMMIT` | `false` | `true`=会话归档+自动提取(建 OV session + 逐 turn append + auto_commit_policy);false=逐 turn `remember()` 提取。二者互斥 |
| `OVK_KNOWLEDGE_IMPORT` | `false` | 发布时 backend 将 OAF 包 `knowledge/` 导入 `viking://resources/`(account 共享) |
| `OVK_SHARED_SKILLS` | `false` | 发布时 backend 将包 `skills/` 导入 `viking://agent/skills/`(account 共享) |
| `OVK_MCP_SHARED_CONTEXT` | `false` | `true`=agent-framework 启动时向 toolkit 注入合成 MCP server `openviking`(Bearer=OVK_ADMIN_KEY,服务身份),暴露 find/search/read/rule_search 等服务级工具 |
| `OVK_PER_USER_KEY`(平台能力) | — | backend `POST /api/v1/services/{id}/memory-keys`:root key 注册用户并签发个人 user_key(供 Studio/外部 MCP 凭证访问),签发一次明文返回 |

**兼容矩阵**:`AGENT_MEMORY_ENABLED=false` 时两个 provider 全关(无记忆),优先级最高;`local` 路径代码零改动。

> `OVK_SESSION_COMMIT` 实现现状(2026-11-23 回填,issue #418):append 链路异步化——`VikingSessionCommitStateStore` 先同步完成 `delegate.save`(agent_state 落库零延迟),新增消息整批投递单线程 worker 有界队列(500)异步 append,队列满整批丢弃并告警;`createSession` 失败负缓存 30s(冷却期快速丢弃,杜绝 OV 不可用时的 N×timeout 重试风暴);OV session 映射键 = slot 复合会话键 `userId:sessionId`(A2A 同用户多会话不再按 userId 串写);装配门控 = VikingMemoryClient 在位 + OVK_SESSION_COMMIT + AGENT_MEMORY_ENABLED 三条件齐备(`AgentScopeConfig.vikingSessionCommitEnabled`,记忆总开关关闭不再被绕过)。

> `OVK_MCP_SHARED_CONTEXT` 实现现状(2026-11-23 回填,issue #415):合成 server `openviking` 经 `HarnessAgentFactory.registerVikingSharedContextMcp` 程序化注入,权限声明位置 = 包内 `mcp-configs/openviking/config.yaml` 的 `permissions.tools`(permissions-only),句柄可换芯、看门狗探测/补偿自愈、A 路径 reload 豁免、frontmatter 同名声明守卫——细节见 §7 实现现状回填。

> 首推理注入缓存 + 本地记忆关闸实现现状(2026-11-23 回填,issue #419):首推理注入缓存键 = slot 复合会话键 `userId:sessionId`(§4.6 缓解①「session 内缓存」语义落地,实现期曾误为 per-user 键——同用户 5 分钟内新会话直接复用上一会话检索结果,与当前问题无关的旧记忆被注入、刚写入的记忆不可见);`AGENT_MEMORY_PROVIDER=openviking`(VikingMemoryClient 在位)时 `AgentScopeConfig.workspaceReader` 装配 `memoryEnabled = AGENT_MEMORY_ENABLED && VikingMemoryClient 不在位`——沙箱对 `MEMORY.md`/`memory/` 不注入(`readRuntimeFiles` 空集)、不回写(`WorkspaceSyncService.syncBack` 同一开关),与 SDK hooks/tools 双关共同消除双记忆源。

### 11.2 实施拆分(单分支多 commit,一个 PR)

1. **backend**:VikingAdminClient(建/删户、注册用户、知识/技能导入)+ 发布/删除生命周期挂钩(OPENVIKING_ENABLED 门控)+ 服务 env/Secret 注入 + per-user key 签发端点
2. **agent-framework**:VikingMemoryClient(HTTP,seed 推导,lazy 开户,超时+fail-soft 含 retrieval_error 判据)+ AGENT_MEMORY_PROVIDER 分叉装配 + memory_search/get/save 工具桥接 + 首推理 search(context) 注入 + session commit 链路 + 合成 MCP server 注入
3. **manifests**:platform.yaml 平台开关 env(OpenViking 本体外部提供,平台不部署)
4. **e2e**:记忆链路用例(双 provider、切换、隔离、fail-soft)+ 双副本门禁保持
5. **文档**:设计文档归档 docs/design + deployment.md 运维备忘(切换操作、per-user key 签发、导出用 ov CLI/snapshot API 手工操作不进代码)

rule_search 经 OVK_MCP_SHARED_CONTEXT 的 MCP 工具自动可用,无额外开发;account 数据导出为运维手工流程(ov CLI),不做代码。

## 12. 决策点(全部关闭)

1. ~~身份路线:三凭证分层 vs trusted 回退~~ — **已裁决(261116 PoC)**:三凭证分层,seed 派生验证通过
2. ~~embedding/rerank provider 选型~~ — **移出本文档范围**:OpenViking 内部部署配置,由其运维方决定;本设计仅约束集成契约(超时预算 + fail-soft 含 `stats.retrieval_error` 判据)
3. ~~会话历史是否二期推 session commit 归档~~ / ~~knowledge resources 化~~ — **已裁决(261116):均进二期,一期不做**
4. ~~存量记忆是否迁移~~ — **已裁决(261116):不迁移,新起点**
5. 服务身份是否与账户管理身份分权(GLOBAL 角色)——默认不分(ADMIN key 兼任,KISS),留待实际需求再评估

## 13. 参考

- OpenViking:README、docs/en/concepts/11-multi-tenant.md、guides/04-authentication.md、guides/06-mcp-integration.md、api/08-admin.md、docker-compose.yml
- 本仓库:agent-framework/docs/checkpoint-design.md(现状记忆权威)、docs/design/console-user-workspace-split-design.md(用户模型/网关鉴权权威)、docs/deployment.md(隔离键派生)
