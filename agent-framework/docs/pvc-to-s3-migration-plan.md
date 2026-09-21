# agent-framework PVC 使用盘点与 S3 切换影响评估

>
> **文档状态**：评估稿（2026-09-20），**尚未实施**。本文只做现状盘点、影响分析与方案设计，
> 不包含任何代码改动。实施前一节 [§10 待确认事项](#10-待确认事项) 的问题需先有结论。
>
> **目标背景**（2026-09-20 复核修订）：两地 K8s 各挂自己的 PVC 以实现高可用，**控制面按 K8s 集群套数部署**
> （每集群一套 platform-backend），改造主体为 agent-framework。可用资源：公有云对象存储（OSS/S3 兼容）；
> **MySQL 两集群可连一套，也可各自独立；Redis（session_event）同理**。
>
> **结论摘要**：文件业务 `FILE_STORAGE_TYPE=local → s3` 是已设计、已实现、低风险的开关切换（SDK 与集成测试均已就绪）。
> 但新拓扑复核后，**初稿有两个结论站不住**：① OAF 包"留在各集群本地 PVC 即可"在**共享 MySQL** 下会直接导致
> 业务 Pod 启动失败（见 [§2.5](#25-双集群拓扑下的隐藏约束)）；② 文件元数据的跨集群复制延迟缺口，可通过
> "两集群连一套 MySQL"消除。此外控制面侧有一个文档完全未覆盖的冲突：`services` 表缺集群维度
> （详见 §2.5.2），这是**共享 MySQL 方案的前置改造项**。
>
> **三块存储的决策依据（判断顺序，勿倒置）**：
> **先看是否有低成本替代 + 是否有真实收益，再看技术难度。** 技术难度只决定"做起来贵不贵"，
> 不决定"该不该做"——本文初稿曾用"技术上难"论证"OAF 包不迁"，该理由同样能推出"技能不该迁"，
> 自相矛盾；修订说明见 [§4.2](#42-oaf-包为何不迁-s3)。

---

## 1. 盘点范围与方法

- 代码基线：`agent-framework/`（`master`，提交 `f2b45e1` 附近）
- 平台侧：`backend/`（`internal/k8s/objects.go`、`internal/store/fs.go`）、`manifests/platform.yaml`
- 方法：按「容器内的挂载点」倒查消费方（`AGENT_CONFIG_DIR` / `AGENT_WORKSPACE_DIR` / `FILE_STORAGE_LOCAL_DIR`
  / logback 日志路径），逐个确认读写语义与是否可替代

---

## 2. PVC 使用现状总览

平台唯一的数据卷是 `platform-data` PVC（`manifests/platform.yaml:198-208`，`ReadWriteOnce`，10Gi）。
业务 Pod 的挂载由 `backend/internal/k8s/objects.go:135-171` 构造。**注意：并非所有业务目录都落在 PVC 上**，

| 容器内路径 | 卷来源 | 读写 | 平台构造处 | 是否本次范围 |
|---|---|---|---|---|
| `/config` | `platform-data` subPath `packages/{id}` | **只读** | `objects.go:137` | ⚠️ **需迁**（初稿"不迁"已推翻，见 §2.5.1） |
| `/data/files` | `platform-data` subPath `files/` | 可写 | `objects.go:141` | ✅ **本次搬迁对象** |
| `/workspace` | **emptyDir** | 可写 | `objects.go:138/168` | ❌ 已是临时卷，非 PVC |
| `/applog` | **emptyDir** | 可写 | `objects.go:143/170` | ❌ 已是临时卷，非 PVC |
| 后端 `packages/{id}` | `platform-data`（backend 自身挂载 `/data`） | 可写 | `platform.yaml:285` + `store.FS` | ⚠️ **需迁**（与 `/config` 同源，见 §2.5.1） |

**关键澄清（2026-09-20 修订）**：初稿把 `/config` 与后端包目录标为"不迁"，理由是"各集群本地 PVC 即可"。
**该结论在共用一份 MySQL 的拓扑下不成立**——包目录 `packages/{id}` 的 id 来自共享自增主键，而 PVC 各自独立，
会导致**在集群 B 发布时业务 Pod 找不到包、起不来**。详见 §2.5。
`/workspace`、`/applog` 本来就是 emptyDir，不存在"迁"的问题，处理建议
见 [§6.4](#64-workspace--applog-emptyDir-保持现状的理由与注意项)。

### 2.5 两集群共用一份 MySQL 引出的约束（2026-09-20 复核新增）

**已确认拓扑**：两集群连同一套 MySQL（`oaf_platform`、`oaf_checkpoint` 共用一份持久化数据）；
Redis（session_event）两集群可连一套也可各自独立，取舍见 §2.5.3。

#### 2.5.1 OAF 包的 subPath 是共享自增 ID，与"各集群本地 PVC"直接冲突

包上传由接收请求的那一套 backend 落盘，路径为 `packages/{id}`，`id` 来自**共享库**自增主键：

```
用户在集群 A 上传 → PackageService.Create 只在 A 的 PVC 落盘 ExtractZipTo("packages/{id}")
                    package.go:56-58（id 由 tx.Create(rec) 自增产生）
服务发布         → pkg.DirPath 作为 PVC subPath 注入业务 Pod 只读挂载
                    publish.go:91 → k8s/objects.go:137
```

在集群 B 发布同一服务（`services` 行共享，`package_id` 指向同一个包）时：

- B 的 PVC 上**不存在** `packages/{id}` 目录 → 业务 Pod 的 `/config` 挂载为空
- `OafConfigLoader` 找不到 `AGENTS.md` 直接抛 `IllegalStateException`（`OafConfigLoader.java:28-30`）
- 结果：**业务 Pod 在 B 集群起不来**（CrashLoopBackOff）

这条链否定了初稿「包随发布流程在各集群重传即可」的说法：**重传会生成新的 id，而 `services.package_id`
仍指向旧包行**；且 id 由共享库自增，两集群各传一次也对齐不上。

结论：OAF 包与文件存储一样，**必须脱离集群本地 PVC**。落地方式（技术清单见 §4.2）：

| 方案 | 做法 | 评价 |
|---|---|---|
| **P1（推荐）** | 包迁对象存储，`/config` 改为启动时拉取（initContainer 拉 zip 解压到 emptyDir），或自研 S3 SkillRepository | 复用同一套 S3 凭证与跨集群语义；代价是失去"PVC 原位修改即时生效"的热加载（§4.3 第 2 点） |
| P2 | 两集群各自独立 MySQL，包与本集群发布绑定 | 与已确认的"共用一份持久化数据"冲突，且把不可共享面扩大到全业务数据 |
| P3 | 保留本地 PVC + 发布前把包 rsync 到目标集群 | id 漂移与并发问题难解，不建议 |

#### 2.5.2 `services` 表缺集群维度 —— 共享库的前置改造项

与 S3 无关，但**同样会让"两集群连一套 MySQL"当场失败**，初稿完全未覆盖：

- `ServiceEntity.K8sName` 是 **uniqueIndex**（`store/model.go:39-41`），`uniqName()` 靠**查库去重**追加 `-2/-3`（`publish.go:373-386`）
- `Endpoint` 硬拼本集群 `IngressHost`：`fmt.Sprintf("http://%s:%d/agent/%s/", c.Cfg.IngressHost, ...)`（`publish.go:102`）
- `ClusterURL` 拼集群内 DNS（`...svc.cluster.local`，`publish.go:103`），A2A 注册用 `fetchCard(svc.ClusterURL)`（`register.go:34`）
- 但表里**没有任何集群归属字段**（全表仅 `ClusterURL` 带 "Cluster" 字样，是 URL 不是标识）

后果：两套 backend 连同一库时，同名服务在第二个集群发布会撞 `uniqueIndex`；即便强行插入，
`Endpoint`/`ClusterURL` 里存的也是**创建它的那个集群**的地址，另一集群的 A2A 注册会连错目标。

**这是"共用一份 MySQL"的前置改造项**（加 `cluster` 维度、或改造 Endpoint/ClusterURL 语义），
不属于本次 S3 工作，但必须排在前面，否则共享库方案无法落地。

#### 2.5.3 Redis（session_event）连一套 vs 各自独立

两条路都可行但语义不同，明确一点：**共享一套 Redis 不等于会话跨集群可续传**。

- 各自独立：A 集群的事件流在 B 不可见 → 切换后 SSE 断线无法回放 A 上的历史事件
- 连一套：事件流全局可见；但写入变成跨集群写，**延迟直接落在每条 SSE 帧的落库路径上**
  （`SessionEventStore` 是写路径组件），且该 Redis 成为新的共享依赖

建议：**先各自独立**（改动小、无跨集群写延迟），接受"切换后正在进行的对话无法续传"；
若产品要求续传，再改共享并重设 `AGENT_REDIS_COMMAND_TIMEOUT_MS`（当前默认 2000ms，需按实际 RTT 取值）。

> 另注：该文件 `# MySQL 两集群连一套 MySQL` 相关背景已并入本小节，避免重复表述。

---

## 3. `/data/files` —— 文件上传/产出（本次核心搬迁对象）

### 3.1 现状：双后端已实现，只待开关

`FileStorage` 抽象 + 两个实现**都已落地**（`service/storage/`）：

| 实现 | 类 | 触发条件 | 原子写语义 |
|---|---|---|---|
| 本地路径 | `LocalFileStorage.java` | `FILE_STORAGE_TYPE=local`（默认） | tmp + `Files.move(ATOMIC_MOVE)`，失败降级 `REPLACE_EXISTING`（`LocalFileStorage.java:49-62`） |
| S3 兼容 | `S3FileStorage.java` | `FILE_STORAGE_TYPE=s3` | `putObject` 对象级天然原子 |

- 依赖已就位：`io.minio:minio:8.5.17`（`pom.xml:166-170`）
- 集成测试已就位：`S3FileStorageIT`（七牛云 S3 网关实测过），默认由 `@EnabledIfEnvironmentVariable(S3_IT)`
  跳过，设 `S3_IT=1` 并给 4 个环境变量即可跑真端点
- 绑定回归测试就位：`S3EnvBindingTest` 锁住"平铺键名"结构（防止 `application.yml` 写成嵌套
  `storage:` 导致 S3 参数静默回退为空的历史坑复发）
- 切到 S3 后 `FileStorage` 的四个方法调用点全部经过抽象，**业务代码零改动**

消费方（全部走抽象，无本地路径假设）：

| 场景 | 调用点 | 说明 |
|---|---|---|
| 用户上传 | `FileController.upload`（写） | 先写后端成功 → 落 `file_asset` 行 → 落库失败回滚删除对象 |
| 下载/预览 | `FileController.download`（读） | 流式转发，`Content-Disposition` + `nosniff`；对象缺失 → `502` |
| 图片内联、注入 | `UploadWorkspaceInjector.buildContentBlocks` / `readAll`（读） | 图片 ≤ `FILE_IMAGE_MAX_MB` 内联 Base64 |
| Agent 产出登记 | `FileTools.presentFile`（写） | key 前缀 `generated/{yyyyMM}/{uuid}-{name}` |
| OAF 包生成 | `OafPackageTools.createOafZip`（写） | 同上抽象 |
| 过期清理 | `SessionCleanupService.cleanupExpiredUploads`（删） | 先删行后删对象，删对象失败仅告警、下轮重试 |

`file_asset` 表（`FileAssetStore.java:70-88`）**只存元数据**（`storage_type` + `storage_key` + 大小/状态），
字节在后端。这一设计是本次切换成本极低的原因：`storage_type` 本就是列数据，切换后端不需要迁移表。

### 3.2 切换动作

```
FILE_STORAGE_TYPE=s3
FILE_STORAGE_S3_ENDPOINT=<云厂商 endpoint>   # 强制 HTTPS
FILE_STORAGE_S3_ACCESS_KEY=<占位符，入 .env.secrets>
FILE_STORAGE_S3_SECRET_KEY=<占位符，入 .env.secrets>
FILE_STORAGE_S3_BUCKET=agent-files
```

由平台在业务 Pod 的 env ConfigMap 注入；同时 `objects.go` 的 `/data/files` volumeMount 在切换完成后
可以移除（见 [§7](#7-实施步骤建议)）。

---

## 4. `/config` —— OAF 包（需迁对象存储，另有一处现存缺陷必须记录）

### 4.1 它是只读挂载，却被当作可写目录使用

这是盘点中最重要的发现，**与 S3 无关，但会被任何"统一存储"改造放大**：

- 平台以 `ReadOnly: true` 挂载 `/config`（`backend/internal/k8s/objects.go:137`）
- 但 `SkillManageService` 构造器直接对它 `Files.createDirectories(skillsDir)`（`SkillManageService.java:49`），
  上传 zip（`:127`）、写启停状态 `.skill-states.json`（`:375/393`）、编辑 `SKILL.md`（`writeSkillContent:229`）全部写向它
- 后果：集群里这些写会**直接失败**——`/config` 只读。技能上传/启停/编辑能力在容器部署下不成立

同时 `-- /config/skills` 被注册为 L2 `FileSystemSkillRepository(skillsDir, false, "oaf-package")`
（`AgentScopeConfig.java:403`，`writeable=false`），SDK 仓库层会拒绝写，因此**只有 SkillManageService
绕过 SDK 的那条写路径踩了坑**，且这一路径损坏时被 `catch` 降级或未显式报错，容易长期不被发现。

### 4.2 OAF 包迁 S3：必要性来自 §2.5.1，不是技术偏好

> **2026-09-20 修订**：本节初稿结论是"不迁"，理由是"各集群保留本地 PVC 即可"。
> **该结论已被 §2.5.1 推翻**——共享自增 id + 各自 PVC 会导致 B 集群业务 Pod 起不来。
> 因此本节改为论证"怎么迁"，决策依据从"收益"转为"必须"。

前文 §4.2/§4.3 曾就"技术难度能否作为不迁的理由"做过一次论证口径修订（见 §9）。
**那次修订的结论依然成立**：技术难度不决定该不该做。本次是**必要性事实变了**，不是论证方式变了——
既然包必须脱离本地 PVC，§4.3 列出的技术账单就要如实承担。

需要一并处理的消费方（即"落地范围"）：

| 消费方 | 位置 | 读取方式 | 迁移影响 |
|---|---|---|---|
| OAF frontmatter 解析 | `OafConfigLoader.java:23,32` | `Files.readString(configDir/AGENTS.md)` | 需保证 `/config` 启动时已就绪 |
| 包内声明技能的 SKILL.md | `OafConfigLoader.java:176,228` | `configDir/skills/{name}/SKILL.md` | 与技能写路径同批改造 |
| L2 技能仓库 | `AgentScopeConfig.java:403` | `FileSystemSkillRepository`（`Files.list` + `readAttributes` 做 mtime/size 快照短路） | 若 `/config` 为启动时拉取的 emptyDir，本项可保留不变 |
| 技能目录服务 | `SkillCatalogService.java:48` | 复用同一 `FileSystemSkillRepository` | 同上 |
| MCP 配置加载 | `McpToolRegistrar.java:79` / `McpManager.java:44` | 读 `mcp-configs/{server}/config.yaml` | 同上（纯启动期读，无热加载需求） |

**推荐落地方式：initContainer 拉取解压到 emptyDir**

- 后端上传包时**同时**写对象存储（`packages/{id}.zip`），保留本地 PVC 落盘作为过渡期双写或直接替换为对象写
- 业务 Pod 增加 initContainer：按 `packages/{id}.zip` 从 S3 拉取 → 解压到共享 emptyDir → 主容器挂 `/config`
- 上表中"L2 技能仓库 / 技能目录服务 / MCP 配置"三项**全部无需改动**——它们看到的仍是本地目录
- 代价：失去"PVC 原位修改即时生效"（`oaf-skills-dynamic-loading-plan` §7 的热加载），
  变更包需走重新发布；这与 §4.3 技能写路径的代价同源，应一并评估

### 4.3 技能管理写路径迁 S3：这是"修缺陷"，不是"换优化"

驱动因素与 §4.2 完全不同：**当前技能管理是坏功能**（写只读挂载），必须给它一个可写位置；
用户选定 S3（`skills/` 前缀），顺带让技能内容获得与文件存储一致的跨集群可见性。

**必须说清的代价**（与 §4.2 的技术难点同源，此处如实承担，不再回避）：

1. **要自研 `SkillRepository`**。实测 `AgentSkillRepository` 只有 9 个方法，SDK 未提供 S3 实现；
   `FileSystemSkillRepository` 内部走 `SkillFileSystemHelper` 的静态 `Files.*` 调用，无法改造成对象后端
2. **热加载语义要重建**。现有"PVC 原位修改→下一轮生效"依赖 bind mount 的实时性（`AgentScopeConfig.java:400`
   注释所述每轮重扫 + mtime/size 短路）。对象存储没有目录扫描，须用 `listObjects` + `statObject`
   模拟版本签名，既引入 API 调用成本，也把"实时"降级为"轮询间隔内"
3. **`OafConfigLoader` 的声明技能读取要一并处理**（`:176`/`:228`）——它不是只影响仓库层

结论：**§4.2 与 §4.3 不矛盾，因为它们的决策依据不同**。§4.2 有低成本替代且无收益 → 不迁；
§4.3 无替代且现状已坏 → 迁，并接受上面三项代价。方案见 [§6.1](#61-技能管理写路径迁-s3)。

---

## 5. 影响评估

### 5.1 逐项结论

| 项 | 影响 | 等级 | 依据 |
|---|---|---|---|
| 上传/下载/内联/产出/清理的文件字节 | **无业务影响** | 无 | 全部经 `FileStorage` 抽象；S3 实现 + IT 已就绪 |
| `file_asset` 表结构与数据 | **无影响** | 无 | 表只存元数据，`storage_type` 本就是列 |
| 非沙箱模式上传注入工作区 | **无影响** | 无 | `UploadWorkspaceInjector.injectToWorkspace` 从存储读回字节后仍写本地 `Files.write`（`:70`），与后端类型无关 |
| 技能管理写 `/config/skills` | **现存缺陷，必须修** | 高 | 写只读挂载，见 §4.1 |
| OAF 包在 B 集群发布 | **业务 Pod 起不来** | 高 | 共享自增 id + 各自 PVC，见 §2.5.1（**本次复核最大发现**） |
| `services` 表缺集群维度 | 共享库方案落地阻塞 | 高 | 见 §2.5.2（非 S3 工作，但是前置项） |
| 文件跨集群切换的可见性 | **已消除** | — | 两集群共用一套 MySQL，见 §5.2 |
| S3 成为新的跨集群依赖 | 新增可用性假设 | 中 | 见 §5.3 |
| **沙箱：OpenSandbox 部署形态选择** | 决定会话能否跨集群续 | 中 | 自身 HA 已确认；共用一套则可续，见 §6.4b① |
| 沙箱文件处理跨集群 | 四类中三类天然可用，第四类视部署形态 | 中 | 逐类判定见 §6.4b② |
| `/workspace`、`/applog` | 不在范围 | — | 本就是 emptyDir，见 §6.4 |

### 5.2 文件元数据的跨集群可见性 —— 共用一套 MySQL 后该缺口已消除

> **2026-09-20 修订**：初稿本节假设"MySQL 跨集群异步复制"，据此推出复制延迟窗口内备集群 404 的缺口。
> **该前提已不成立**——两集群连同一套 MySQL（`file_asset` 是同一份数据），所谓"复制延迟窗口"不存在。

`GET /files/{fileId}` 的读路径是：`file_asset` 查行 → 拿 `storage_key` → 对象存储读字节。
现在两个集群看到的是**同一行、同一个 S3 对象**，因此：

| 情形 | 结果 |
|---|---|
| 常规情况 | 行与对象均全局一致 → 下载正常 ✓ |
| 上传后立刻切换 | 两集群读同一库 → 无延迟窗口，下载正常 ✓ |

仍需留意的**残留项**（与复制无关，属多副本并发语义）：

- `FileController.upload` 的 pending 软限制（`uploadMaxPending`）是"查库计数后写入"的非原子序列，
  两集群并发上传时可能少量超发——与本次改造无关，单集群多副本下同样存在
- `SessionCleanupService.cleanupExpiredUploads` 是每实例定时触发，两套 backend 会**并发清理同一份数据**，
  先删行后删对象的顺序使重复执行安全（第二次查不到行），但对象删除失败仅告警、下轮重试；
  建议清理任务加个轻量租约避免两集群重复扫描，属于可选优化

### 5.3 S3 是新增的跨集群强依赖

`S3FileStorage` 构造器**启动时 fail-fast**：构造 `MinioClient` 后立刻 `bucketExists` 校验，
失败即 `IllegalStateException`（`S3FileStorage.java:40-47`）。这在当地 S3 不可达时会让
**业务 Pod 起不来**（`CrashLoopBackOff`），而不是"文件功能降级"。

这与当前 local 后端的失败域不同：local 后端坏了只影响文件端点。迁 S3 后，对象存储的可用性
与两集群到它的网络链路，都被纳入了业务 Pod 的启动路径——**这与"高可用"目标同向的前提是
S3 本身比单集群 PVC 更可靠**（公有云对象存储通常满足），但需要显式接受这个新的依赖关系。

---

## 6. 风险与解决方案

### 6.1 技能管理写路径迁 S3

**目标**：技能内容存 S3 `skills/{skillName}/...`，管理接口（上传 zip / 启停 / 读改 SKILL.md）改为对象读写，
同时让 L2 仓库能读到它们。

**难点**：动态技能加载的核心机制是 DynamicSkillMiddleware **每轮推理重扫目录**
（`FileSystemSkillRepository` 内部用 `Files.readAttributes` 取 mtime+size 做快照短路，
未变则不重新解析）。S3 没有原子"扫描目录"语义，需要用 `listObjects` + `statObject` 模拟。

**推荐方案：自研 `S3SkillRepository`（实现 SDK 的 `AgentSkillRepository` 接口）**

```
skills/{skillName}/SKILL.md          → 必选
skills/{skillName}/<资源文件>         → 可选（脚本/模板等）
skills/.states/{skillName}            → 启停状态（替代本地 .skill-states.json）
```

要点：

1. **接口只有 9 个方法**（`getSkill`/`getAllSkillNames`/`getAllSkills`/`save`/`delete`/`skillExists`/
   `getRepositoryInfo`/`getSource`/`setWriteable`+`isWriteable`），实现面可控
2. **保住热加载语义**：`listObjects(skills/)` 拿前缀，对每个 `{skillName}/SKILL.md` 用 `statObject` 取
   `lastModified` + `size` 作版本号；与本地快照同款短路策略，**skill 名+etag/size+lastModified 构成签名**，
   未变则跳过重新解析。注意要为它单独设一个重扫节流窗口（建议沿用现有"每轮一次"+ 短 TTL 缓存），
   避免每轮都打 S3 list API 造成延迟与成本
3. **写能力下沉到对象 API**：`uploadSkill` 的解压产物改为 `putObject` 逐文件写；`writeSkillContent`
   改为 `putObject`（S3 覆盖写天然原子，比现在"本地 exists→edit/write 分支"更简单）
4. **启停状态独立前缀** `skills/.states/`，避免与技能内容混在同一个 list 结果里（对应现在本地以
   `.` 开头规避扫描的做法）
5. **多源合并**：若需要同时保留包内携带的技能（`/config/skills`，只读 PVC）与 S3 上用户上传的技能，
   可用 builder 的 `skillRepositories(List<AgentSkillRepository>)` 一次注册多个仓库（该重载已在 SDK
   2.0.3 harness builder 上核实存在），此时需明确定义同名技能的冲突优先级

**改动清单**：新增 `service/skill/S3SkillRepository.java`；改造 `SkillManageService`（写路径换成对象 API）、
`SkillCatalogService`（数据源换成新仓库）、`AgentScopeConfig`（按开关装配仓库）。
**这不是"换存储"，是"换一个仓库实现"**，工作量显著大于 §3 的开关切换，建议独立立项。

### 6.2 跨集群文件可见性 —— 共用一套 MySQL 后无需处理

> **2026-09-20 修订**：初稿本节给出 A/B/C 三档方案来处理"MySQL 异步复制导致的元数据缺口"。
> **该前提已不成立**（两集群共用一套 MySQL，`file_asset` 是同一份数据），故本节降级为说明。

结论：**文件功能在切换 S3 后天然具备跨集群可见性**，无需为此做额外设计：唯一需要读到的
`file_asset` 行两集群看到同一份，字节在对象存储上全局可读。

保留一条后续增强（原 C 方案的非必需部分，非本次范围）：让 `FileStorage.write()` 把 `size`/`mime`
写入**对象 metadata**，使 `file_asset` 行意外丢失时可从对象存储侧重建元数据。这是韧性增强，
不是本次迁移的必需项。

真正需要投入的是下面两条，而非本节。

### 6.3 S3 启动依赖导致的 fail-fast 放大

现状 `S3FileStorage` 构造器 `bucketExists` 失败即抛 `IllegalStateException`，业务 Pod 起不来。
目标导向高可用，建议**改造启动语义**（属于必需变更，非可选优化）：

1. 构造器改为**惰性可达性校验**：bucket 探测失败时记录 WARN 并以"未就绪"状态构造完成，
   让 `/health` 起得来（业务探针为 readiness 15s/5s、liveness 60s/15s，见 `objects.go:145-146`）
2. 首次 `write/read` 时才真正初始化 client；失败按调用点现有错误处理返回（`FileController.upload`
   已有 `500 storage_write_failed`，`download` 已有 `502`）
3. 就绪探针可选地暴露存储后端状态（建议新增独立 readyz 语义，避免 S3 抖动把整个 Deployment 打挂）
4. **超时必须显式配置**：MinIO SDK 默认不带命令超时，`AgentRedisProperties` 那次教训
   （默认关闭超时导致 Tomcat 线程被无限期占住）同样适用——`FileConfig` 需新增
   `FILE_STORAGE_S3_TIMEOUT_MS`（建议值：connect 2s / read 30s，大文件 `present_file` 上限 50MB 需按
   吞吐反算，勿拍脑袋）

### 6.4 `/workspace` / `/applog`（emptyDir）保持现状的理由与注意项

两者都不是 PVC，**不需要迁**；但要注意它们与"高可用"目标的关系：

- `/workspace`（emptyDir）：实际工作区数据并不在这里终老。非沙箱模式下 `RemoteFilesystemSpec(IsolationScope.USER)`
  把 `MEMORY.md` / `memory/` / `skills/` / `subagents/` 等路由到 MySQL `agent_fs` 表
  （见 `mysql-filesystem-plan.md`），所以 **Pod 重建丢失 emptyDir 不会丢业务数据**；
  但 `UploadWorkspaceInjector` 注入的 `uploads/` 副本确实是本地的——重启后文件本身仍可从 S3 重新读回，
  只是 `file_asset.workspace_path` 需要能重建
- `/applog`（emptyDir）：logback 固定写 `/applog/${HOST_NAME}/trace.log`（`logback-spring.xml:13`），
  是容器云日志采集的唯一来源。**对象存储不满足 rolling file appender 的 POSIX 语义**（追加写、轮转、
  rename），不要迁；日志要跨集群留存应走采集侧汇聚

---

### 6.4b 沙箱模式（`SANDBOX_ENABLED=true`）对本方案的影响（2026-09-20 复核新增）

沙箱模式当前**默认关闭**（`SANDBOX_ENABLED=false`），但一旦开启，工作区与文件注入路径整体改变。
**OpenSandbox 自身高可用已确认**，因此本节不讨论其可用性，只讨论**部署形态选择及其对跨集群语义的影响**。

#### ① OpenSandbox 自身高可用 → 沙箱可跨集群续传（结论更新）

**前提确认**：OpenSandbox Server 自身按高可用部署，**不作为本次方案的单点风险项**（消除 §6.4b 初稿的顾虑）。

那么真正要判断的只剩一件事：**两地是共用同一个 OpenSandbox 端点，还是各部署一套？**
这个选择直接决定沙箱会话能否跨集群续。答案是**共用则能续**，依据如下：

- 沙箱状态**不在 Server 独占内存里**，而是持久化进 MySQL `agent_state`——
  `SandboxAwareMysqlAgentStateStore`（`AgentScopeConfig.java:232`）放宽了官方校验，放行形如
  `sandbox/user/{agentId}/{userId}` 的 slot ID（`SandboxAwareMysqlAgentStateStore.java` 注释所述）
- `resume()` 是按 `osbState.getSandboxId()` 走 OpenSandbox SDK `connector().connect()` 重连，**不依赖本地 PVC**
  （`OpenSandboxClient.java:140-151`）
- 而 MySQL 是两集群共用的同一份数据

因此，只要两地配置同一个 `OPENSANDBOX_SERVER_URL`，B 集群就能从共享库读出 A 集群写入的 sandboxId 并重连：

| 部署选择 | 沙箱会话跨集群 | 说明 |
|---|---|---|
| **共用一套 HA OpenSandbox** | ✅ 可续 | 推荐：沙箱状态在共享 MySQL，resume 不依赖本地磁盘；上传文件的 pending→injected 状态也在共享库，切换后可重新注入 |
| 各地部署一套 | ❌ 不可续 | B 集群拿 A 集群的 sandboxId 去连自己那套会 404 → 框架 `SandboxManager.acquire()` 捕获后降级 **create**（新沙箱） |

降级 create 不是灾难——`create()` 会走 `injectPendingUploads()` + KV 运行时文件注入，
**用户侧的感受是"沙箱重置"（历史记忆仍在，因为是共享 MySQL，但容器内中间产物丢失）**。
若产品接受这个降级语义，两地各部署一套也可行（且能避免两地到同一 OpenSandbox 的跨地域 RTT）；
若要求"切换后沙箱继续用"，则必须共用同一套。

> 建议：**共用一套 HA OpenSandbox**。理由是 §6.4b② 提到的 pending 注入状态在共享库——
> 若两地各自一套，A 标记 `injected` 的行在 B 不会再注入，用户切换到 B 后发现附件没进新沙箱，
> 这个不一致比 RTT 更难排查。

#### ② 沙箱文件处理的跨集群可用性：按文件类别逐项判定（2026-09-20 追问复核）

「沙箱的文件处理是否支持跨集群可用」不能一概而论——沙箱 `/workspace` 里同时存在四类文件，
**数据通道完全不同**，跨集群结论也不同。逐类判定如下：

| 文件类别 | 数据通道 | 跨集群可用 | 依据 |
|---|---|---|---|
| **① 用户上传**（`/workspace/uploads/`） | S3 字节 + 共享库 `file_asset` 状态 → execd 注入 | ✅ **可用** | 字节从 `FileStorage`（S3）重读；`pending→injected` 状态在共享 MySQL；`OpenSandboxClient.create/resume` 后都调 `injectPendingUploads()`（`OpenSandboxClient.java:120/160` 附近），新沙箱代还有 `resetInjectedToPending` 兜底（`:118-121`） |
| **② 运行时记忆**（`MEMORY.md`、`memory/*.md`） | agent_fs（共享 MySQL）⇄ 沙箱双向同步 | ✅ **可用** | 首 exec 前注入（`injectRuntimeFilesIfNeeded`），每次 call 结束 `stop()→syncBack` 回写 KV（`OpenSandbox.java:96-115`、`WorkspaceSyncService.syncBack`）；两集群读同一份 `agent_fs` |
| **③ Agent 产出并登记的文件**（present_file） | 沙箱 `/workspace` → `FileStorage`（S3）→ `file_asset` | ✅ **可用** | `FileTools.presentFile` 从沙箱直读字节写入存储后端，`file_ready` 卡片 + 下载端点走 S3；登记后即与沙箱容器解耦 |
| **④ 未登记的中间产物**（脚本输出、临时文件等） | **仅存于沙箱容器** `/workspace` | ⚠️ **取决于部署形态** | 无任何回写通道：`syncBack` 只拉 MEMORY.md/memory/，`NoopSnapshotSpec` 不做快照（`OpenSandboxFilesystemSpec.java:135-136`）；唯一出口是 present_file 显式登记 |

**第④类的判定依据**（这是唯一受部署形态影响的类别）：

- 沙箱状态（含 `sandboxId`）由 `SessionSandboxStateStore` 持久化进共享 MySQL `agent_state`
  （slot ID 形如 `sandbox/user/{agentId}/{userId}`，`SandboxAwareMysqlAgentStateStore` 放行）
- `resume()` 按 sandboxId 走 `connector().connect()` 重连，且 `connectionConfig` 配置了
  `useServerProxy(true)`——**所有 execd/文件请求经 OpenSandbox Server 代理转发**，
  客户端不直连沙箱容器（`OpenSandboxClient.java:63-68`），存储的 `sandboxEndpoint` 仅作参考
- 因此：**两地共用同一套 HA OpenSandbox Server 时**，B 集群能从共享库读到 sandboxId 并经同一 Server 重连
  → 沙箱容器连同其中间产物**原样存活**，第④类**跨集群可用**
- **两地各部署一套时**，B 拿 A 的 sandboxId 连自己那套 → 404 → 框架 `SandboxManager.acquire()`
  捕获后降级 `create` 新沙箱 → 第④类**丢失**（①②③类靠 S3/共享库自动恢复，用户感知为"沙箱重置"）

**总结论**：沙箱文件处理**四类中三类天然跨集群可用**（上传、记忆、已登记产出），
第四类（未登记中间产物）在**共用一套 OpenSandbox** 时也可用；只有"各地各一套"时才丢，
且丢的仅是未登记的临时文件。注意共用一套时跨集群 RTT 会落在每次 `exec`/文件调用上，
属性能项而非可用性项。

**工程建议**：对用户有价值的沙箱产物应引导走 `present_file` 登记后再交付（现有工具描述已是这个导向），
使第④类缩小到"真正的临时文件"——这样即使将来切换到各地各部署一套，损失也可控。

#### ③ 上传文件注入路径变了，但**不影响 S3 迁移结论**

沙箱模式下上传不走本地工作区，改为 execd files API 注入（见 `ChatStreamController:284` 的条件分支：
非沙箱才调 `injectToWorkspace`）：

```
非沙箱：FileStorage.read → Files.write 到本地 {workspace}/.agentscope/workspace/{sessionId}/uploads/
沙箱：  FileStorage.read → osbSandbox.files().write 注入 /workspace/uploads/   [OpenSandbox.java:196-215]
        status: pending → injected（FileAssetStore.markInjected）
```

**两条路径的字节来源都是 `FileStorage`**，所以 §3 的 `local → s3` 切换对沙箱链路同样零改动、同样受益。
且沙箱模式**反而是受益方**：非沙箱模式下注入的本地副本会随 Pod 重建丢失，而沙箱模式的字节始终从
`FileStorage` 重新读取，S3 化后跨集群重新注入天然可行。

需注意的一点：沙箱注入依赖 `file_asset.status = pending`，而 `listPending` 查的是**共享库**
（`FileAssetStore:222-228`）。若两集群共用一套 MySQL 且各自连各自的 OpenSandbox，A 集群标记
`injected` 后 B 集群不会再注入——**这正是期望行为**（同一文件不重复注入），但需确认
`resetInjectedToPending`（新沙箱代重新注入，`:302`）的触发范围不会跨集群误重置。

#### ④ 记忆回写机制：跨集群可见性的依据

`WorkspaceSyncService.syncBack` 每次请求结束后把沙箱 `/workspace` 的 `MEMORY.md` + `memory/*.md`
回写 `agent_fs`（MySQL）。**MySQL 两集群共用一套，所以记忆跨集群可见** ✓。
新会话在 B 集群能读到 A 集群积累的记忆；容器内未回写的中间文件则按 §6.4b② 第④类判定处理。

---

## 7. 实施步骤建议（尚未执行）

分三条独立线。**线 0 是阻塞项，必须先于其他线完成**（否则共享库方案无法落地）：

**线 0：`services` 表补集群维度（阻塞项，非 S3 工作）**

按 §2.5.2 为 `ServiceEntity` 增加集群归属字段，并使 `K8sName` 唯一性、`Endpoint`、`ClusterURL`
按集群隔离或语义化。两套 backend 连同一库前必须完成，否则同名服务撞 `uniqueIndex`、A2A 注册连错目标。

**线 A：文件存储切 S3（低风险）**

1. 按 §6.3 改 `S3FileStorage` 启动语义（fail-fast → 惰性 + 超时配置）
2. 业务 Pod env 注入 S3 四参数（凭证走 Secret 而非 ConfigMap，对齐现有"禁止硬编码密钥"约定）
3. 跑真端点集成测试：`S3_IT=1 ... mvn test -Dtest=S3FileStorageIT`（现有 IT 已覆盖 write/read/exists/delete）
4. 集群验证：上传 → `/files/{id}` 下载 → SSE `file_ready` 卡 → 过期清理
5. `objects.go` 移除 `FilesVolumeName` 对 `/data/files` 的挂载（`/config` 的挂载随后由线 C 处理）

**线 B：技能管理写路径迁 S3（中风险，独立立项）**

按 §6.1 实施 `S3SkillRepository`，并同步修 §4.1 的只读写入矛盾。

**线 C：OAF 包迁对象存储（中风险，与线 B 同源）**

按 §4.2 落地：包上传改为写对象存储 + initContainer 拉取解压到 emptyDir。**这条是共享库拓扑下的必需项**
（见 §2.5.1，不做则 B 集群业务 Pod 起不来），建议与线 B 合并立项以复用 S3 接入与技能仓库改造。

---

## 8. 需要同步更新的文档

按项目惯例（`agent-framework/docs/README.md` 头部约定：代码大改后先给受影响文档补「现状核对」再更新索引）：

| 文档 | 更新点 |
|---|---|
| `docs/file-upload-download-plan.md` | 头部现状核对：默认后端由 local 改为 s3；§13 P1 的 PVC `files/` volumeMount 已下线 |
| `docs/agent-framework-deploy.md` | 环境变量表补 S3 四项 + 超时项，注明必配与敏感项存放位置 |
| `AGENTS.md` | 「环境变量」表与「文件存储」相关描述；技能管理写路径描述（线 B 完成后） |
| `docs/oaf-skills-dynamic-loading-plan.md` | 线 B/C 完成后：L2 仓库事实来源与"原位修改即时生效"前提（§7 K8s 事实）已变，需重写头部现状核对 |
| `backend/internal/k8s/objects.go` 注释 + `docs/deployment.md` | volumeMount 变更（含 initContainer）、跨集群约束声明、共享 MySQL 前提 |
| `backend/internal/store/model.go` | 线 0：新增集群归属字段（§2.5.2） |

---

## 9. 附：判断顺序 —— 必要性优先，技术难度只决定贵不贵

> **2026-09-20 二次修订**：本节初稿的结论框架（先看替代与收益，再看难度）**依然成立**，
> 但其中"包不迁"的举例已因 §2.5.1 的新事实失效。框架保留，结论更新如下。

- **文件走的是抽象**（`FileStorage` 接口，4 个方法全经它），换后端只是换实现类，业务零改动；
  且 S3 实现**早就写好并用真实云端点验证过** → 成本低 + 有收益 → **做**
- **包走的是具体**（多个消费方直接持有 `java.nio.file.Path` 并调用 `Files.*`），技术账单重；
  它**曾经**有零成本替代（各集群本地 PVC），但共享同一套 MySQL 后该替代失效——
  id 由共享自增主键产生，B 集群 PVC 上没有 `packages/{id}` → **必须做**（§2.5.1）
- **技能写路径**同样走具体、同样要自研仓库，且**没有替代**（现写向只读挂载，功能已坏，§4.1）
  → **做，并承担代价**

判断顺序应当是：**先看有没有低成本替代、现状是否已坏，再看技术难度**。
本文两次修订都源于"事实前提变了"，而不是框架变了：

1. 第一次：承认"技术难度"不能单独作为不迁的理由（否则与技能迁 S3 自相矛盾）
2. 第二次：共享 MySQL 使"各集群本地 PVC"这个替代方案失效，包从"不迁"翻转为"必须迁"

---

## 10. 待确认事项

1. **【新增·阻塞】线 0 的集群维度改造怎么定？** `services` 加 `cluster` 字段后，语义需明确：
   同一服务在两集群是"两个独立实例"（各发布一次、各占一行）还是"一个服务两个部署单元"？
   这决定 `K8sName` 唯一索引是否要改成 `(cluster, k8s_name)` 联合唯一，以及 `Endpoint` 的展示方式。
   **此项必须在两套 backend 连同一库之前定案**
2. **【新增】沙箱：两地共用一套 HA OpenSandbox 还是各部署一套？** 自身 HA 已确认，
   故本节只问部署形态——**共用一套才能跨集群续传沙箱会话**（状态在共享 MySQL，
   见 §6.4b①）。建议共用；若选各地一套，需接受"切换后沙箱重置"降级语义。
   **当前 `SANDBOX_ENABLED=false`，不启用则本项可延后**
3. **【新增】Redis 连一套还是各自独立？** 建议先各自独立（§2.5.3）；若产品要求"切换后 SSE 可续传"，
   再改共享并接受写入延迟落在 SSE 落库路径上。请确认产品侧是否接受"切换后正在进行中的对话无法续传"
4. **§6.3 是否纳入本次？** 我认为将 fail-fast 改为惰性是**必需项**（否则 S3 抖动会打挂业务 Pod，
   与高可用目标冲突），需确认是否同意改 `S3FileStorage` 构造器
5. **S3 超时值**：`present_file` 上限 50MB、`FILE_UPLOAD_MAX_MB` 20MB，读超时需按最坏吞吐反算，
   建议确认目标带宽后再定值
6. **线 C（OAF 包迁对象存储）何时启动？** 它是共享库拓扑下的必需项（§2.5.1），建议与线 B 合并立项。
   需确认采用哪种落地方式（推荐 initContainer 拉取，见 §4.2）
7. **线 B 是否现在启动**：技能写路径迁 S3 涉及自研 SkillRepository，工作量远大于线 A；
   若暂缓，则 §4.1 的只读写入缺陷**仍需以其他方式修**（否则它继续是一个已损坏功能）
8. **凭证分发方式**：当前 Postgres/Redis 凭证写在 `platform.yaml` env 明文（集群现状）。S3 AK/SK 更敏感，
   建议改走 Secret + `envFrom.secretRef`，需确认是否一并改 `objects.go`

> ~~原第 1 条（§6.2 选哪档）~~ 已因两集群共用一套 MySQL 失效，缺口消除，见 §5.2。
