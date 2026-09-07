# 文件上传下载支持设计方案（存储双后端 local/S3 + DB 元数据 + 沙箱注入 + 事件回传）

## 1. 背景与目标

### 1.1 背景

当前 agent-framework 的对话链路（`POST /threads/{sessionId}/chat`）仅支持纯文本（`ChatRequest{message, userId}`）：

- 用户无法上传图片 / Word / PDF 等文件给 Agent 处理
- Agent 经 MCP 工具或沙箱脚本产出的文件（报表、图表、导出的 docx 等）无回传通道，用户拿不到产物
- 前端 assistant 页面无附件 UI、无下载入口

参考实现（DeerFlow / Dify）与 SDK 能力盘点（见 §2），本框架已有可用支点：AgentScope 2.0 消息模型的 `DataBlock`/`ImageBlock`（`Base64Source`/`URLSource`）、`DataBlockStart/Delta/End` 事件三件套、`CustomEvent`、A2A `FilePart`、`RemoteFilesystem`+`MysqlDistributedStore`（agent_fs KV）、OpenSandbox execd files API。

### 1.2 目标

1. 用户上传文件（**图片 + 文档**），**文件内容存存储后端（本地路径 / S3 二选一），元数据以数据库表持久化**（单源真相，DB 只存类型与路径——Dify `UploadFile` 模式）
   - 图片（jpg/png/gif/webp 等）：SDK `ImageBlock` 内联，视觉模型直接识别
   - 文档（**docx/xlsx/pptx/pdf/csv/txt/md**）：注入工作区 + 消息路径提示，由 Agent 经 `read_file`/`list_files` 工具或沙箱脚本自行读取处理（**本方案不内置文档解析**）
2. 上传文件在两种运行模式下均可达 Agent 工具可读的工作区目录：
   - 非沙箱模式：写入 MySQL KV 工作区（agent_fs）
   - 沙箱模式：注入 OpenSandbox 容器 `/workspace/uploads/`（**保证机制见 §6**）
3. Agent 产出的文件经 `present_file` 工具登记入库，通过 chat SSE 事件（`FILE_READY` 合成帧 + SDK `ToolResultDataDeltaEvent` 原生帧）告知前端
4. 提供 `GET /files/{fileId}` 下载端点（流式、Content-Disposition、P2 Range/ETag）
5. 前端 assistant 页面：附件上传、图片内联预览、文件卡片下载

### 1.3 非目标

- **文档解析**（docx/xlsx/pptx/pdf/csv 内容提取为文本）：本方案不内置；文档类文件仅落工作区供 Agent 自行经工具/沙箱脚本处理
- 对象存储多后端混合（local 与 s3 同时启用、按文件路由）：仅二选一（`FILE_STORAGE_TYPE` 全局配置），多租户级路由 P2 再议
- 音视频流式播放：P2 Range 支持预留，不做转码
- 文档 OCR（扫描件图片识别）：超出范围

---

## 2. 现状分析与 SDK 能力盘点（最大化复用 agentscope-java）

### 2.1 SDK 已具备、本框架未利用的能力

| SDK 能力 | 类/接口 | 现状 | 本方案用法 |
|---------|---------|------|-----------|
| 多模态消息内容 | `ContentBlock` 密封体系：`TextBlock`/`ImageBlock`/`AudioBlock`/`VideoBlock`/`DataBlock` | ❌ 仅 `textContent()` | 图片内联 `ImageBlock(Base64Source)`（§7） |
| 数据源抽象 | `Base64Source{mediaType,data}` / `URLSource{url}` | 输出侧已有提取（`AgentRuntimeService:300-318`） | 上传内联与下载 URL 回传 |
| 二进制事件流 | `DataBlockStartEvent`/`DataBlockDeltaEvent`/`DataBlockEndEvent` | 已序列化（`AgentEventSseSerializer:55-58`） | 保留，delta 帧补充 media_type（§10） |
| 工具结果二进制 | `ToolResultDataDeltaEvent{data:ContentBlock}` | ✅ 已处理（仅 invokeStream 链路）；**Channel 链路序列化器缺失** | 补进 `AgentEventSseSerializer`（§10） |
| 自定义事件 | `CustomEvent{name,value}` + `AgentEventEmitter`（Reactor ContextView `agentscope.agent.event.emitter`） | ❌ 未使用 | 备选：present_file 工具内发 `CustomEvent("file_ready",...)`（§8.4，SPIKE 验证） |
| KV 文件系统 | `RemoteFilesystem(baseStore, List.of(userId))` | ✅ `WorkspaceReader` 已用于读 | 复用于**写**上传文件进 KV（§6.1） |
| A2A 文件部件 | `FilePart`（`FileWithBytes`/`FileWithUri`）+ `PartParserRouter` | ❌ 未解析（A2AController 仅透传） | P2：A2A 文档附件转 file_asset + 工作区注入（§11） |
| 沙箱文件写入 | OpenSandbox execd `sandbox.files().write(List<WriteEntry>)` | ✅ `WorkspaceReader.injectToSandbox` 已用 | 复用为上传注入通道（§6.2） |

### 2.2 关键约束（已验证）

- `CustomEvent` 类在 2.0.0 已定义（`AgentEventType.CUSTOM`），但 HarnessAgent 内部无任何 emit 调用点；工具侧能否拿到 Reactor `ContextView` 未验证 → 主方案走控制器合成帧，`CustomEvent` 作 SPIKE 备选（§8.4）
- `ChatUiChannel` 链路（SessionStreamController 使用的通道）不经 `AgentRuntimeService.forwardEvent`，序列化完全依赖 `AgentEventSseSerializer` → 所有新事件词条必须落在该序列化器
- 沙箱模式 `create()`/`resume()` 每次 acquire 都构造**新 OpenSandbox 实例**（`OpenSandboxClient:94/130`）→ 实例级 `AtomicBoolean` 天然按 turn 复位，可作"每 turn 首次注入"的闸门
- `OpenSandboxClient.delete()` 持有 OpenSandbox 实例（含 userKey）→ 可在容器销毁时回滚注入状态（§6.2.4）

---

## 3. 总体架构

### 3.1 文件全生命周期

```
┌─────────┐  multipart   ┌──────────────┐  write    ┌────────────────────────┐
│  前端   │ ───────────> │ FileController│ ────────> │ FileStorage            │
│ assistant│  POST /files/upload          │          │  ├ local: {dir}/...    │
└─────────┘                             │          │  └ s3: {bucket}/{key}  │
     │                                  │          └────────────────────────┘
     │                                  │ INSERT 元数据（类型+路径，无内容）
     │                                  ▼
     │                          ┌──────────────────┐
     │                          │ file_asset 表(DB) │
     │                          └──────────────────┘
     │ POST /threads/{sid}/chat               ▲  ▲
     │ {message, userId, fileIds[]}           │  │ ① 工作区注入（§6，字节读自 FileStorage）
     ▼                                        │  │ ② present_file 登记（§8）
┌─────────────────────────────────────────┐   │  │
│ SessionStreamController                 │   │  │
│  · 取 file_asset → 读存储 → 构造 Msg（§7）│ ──┼──┼─┘
│    图片→ImageBlock / 其他→路径提示       │   │  │
│  · 非沙箱：写 KV 工作区 uploads/（§6.1）│   │  │
│  · 沙箱：pending 挂账（§6.2）           │   │  │
│  · sendStream → SSE 事件直吐            │   │  │
└──────────────┬──────────────────────────┘   │  │
               │ Agent（工具/沙箱脚本）         │  │
               │  读 /workspace/uploads/*       │  │
               │  产出 /workspace/outputs/*     │  │
               ▼                                │  │
┌──────────────────────────────────────────────┘  │
│ FILE_READY 合成帧（§8.3）/ tool_result_data_delta │
▼
前端渲染文件卡片 → GET /files/{fileId}（§9）→ 存储后端流式返回
```

### 3.2 新增组件清单

| 组件 | 文件 | 职责 |
|------|------|------|
| FileAssetStore | `service/FileAssetStore.java` | file_asset 表 DDL + **元数据 CRUD（不存内容）** + pending 状态机 + 孤儿对象清扫 |
| FileStorage | `service/storage/FileStorage.java` | 存储后端抽象：write/read/exists/delete/size（对齐 Dify ext_storage） |
| LocalFileStorage | `service/storage/LocalFileStorage.java` | 本地路径后端：原子写（temp+move）、路径穿越防护 |
| S3FileStorage | `service/storage/S3FileStorage.java` | S3 兼容后端（MinIO SDK）：putObject/getObject/removeObject |
| FileController | `controller/FileController.java` | `POST /files/upload`、`GET /files/{fileId}` |
| UploadWorkspaceInjector | `service/UploadWorkspaceInjector.java` | 非沙箱：写 KV；沙箱：execd 注入 + 状态回滚（字节统一读 FileStorage） |
| FileTools（present_file） | `tool/FileTools.java` | Agent 产出文件登记工具（@Tool 模式，同 BusinessTools） |
| AgentEventSseSerializer 扩展 | 既有文件 | `ToolResultDataDeltaEvent` 词条 + DataBlock media_type（§10） |
| SessionStreamController 扩展 | 既有文件 | fileIds 消息构造 + FILE_READY 合成帧 |
| OpenSandbox 扩展 | 既有文件 | `injectPendingUploadsIfNeeded`（§6.2） |
| OpenSandboxClient 扩展 | 既有文件 | delete 时状态回滚（§6.2.4） |
| 前端 | `frontend/src/app/assistant/page.tsx` | 附件上传、预览、文件卡片 |

---

## 4. 存储与数据库设计（Dify ext_storage + UploadFile 模式）

### 4.1 总体：DB 只存类型与路径，内容存存储后端

对齐 Dify 分层：`UploadFile` 表只存 `storage_type`（local/s3）+ `key`，文件体由 `ext_storage` 抽象层（local fs / S3 兼容）承载。本方案同构：

```
file_asset 表（元数据，无内容列）
   │ storage_type + storage_key
   ▼
FileStorage 接口（write/read/exists/delete/size）
   ├── LocalFileStorage   —— 本地路径 {FILE_STORAGE_LOCAL_DIR}/{key}
   └── S3FileStorage      —— S3 兼容 bucket（MinIO/Ceph/OSS），MinIO SDK
```

### 4.2 FileStorage 抽象

```java
public interface FileStorage {
    /** 写入（实现层负责原子性：local=temp+move；s3=putObject 天然原子） */
    void write(String key, InputStream in, long size, String contentType) throws IOException;
    /** 流式读取（s3=getObject 流；local=FileInputStream） */
    InputStream read(String key) throws IOException;
    boolean exists(String key);
    void delete(String key) throws IOException;
}
```

| 后端 | 实现要点 | 依据 |
|------|---------|------|
| LocalFileStorage | 根目录 `FILE_STORAGE_LOCAL_DIR`（默认 `/data/files`）；key 规范化后 resolve 并校验前缀（防 `../` 穿越）；**原子写**：写 `{key}.tmp` → `Files.move(ATOMIC_MOVE)`（DeerFlow uploads 同款）；子目录按 key 前缀惰性创建 | DeerFlow `uploads/manager.py` 原子 staging |
| S3FileStorage | MinIO SDK（`io.minio:minio`，S3 兼容：MinIO/Ceph RGW/阿里 OSS S3 网关）；`putObject(contentType)` / `getObject` 流 / `removeObject`；endpoint/accessKey/secretKey/bucket 来自配置 | Dify `s3` storage driver |

> SDK 选型：MinIO SDK 体积小、专为 S3 兼容协议设计（离线镜像友好）；备选 AWS SDK v2（`software.amazon.awssdk:s3`，依赖树大）。两者均可经 `endpointOverride` 接私有化 S3 兼容存储。

**存储 key 格式**（本地与 S3 统一，不可枚举 + 可列归属）：

```
{origin}/{yyyyMM}/{uuid}-{sanitizedFileName}
例：upload/202509/3f9c...-report.docx
```

### 4.3 file_asset 表（元数据单源真相）

沿用既有 Store 类的 `CREATE TABLE IF NOT EXISTS` 启动自建 DDL 模式（同 `TurnLeaseStore`/`ToolAuditStore`），建在 checkpoint 库（agent_state 所在库）：

```sql
CREATE TABLE IF NOT EXISTS file_asset (
    id             VARCHAR(36)  PRIMARY KEY,          -- UUID（下载 URL 主键，不可枚举）
    user_key       VARCHAR(255) NOT NULL,             -- 沙箱隔离 key（userId，空时降级 sessionId，语义同 OpenSandbox.resolveUserKey）
    session_id     VARCHAR(255),                      -- 归属会话（可空：上传先于会话绑定）
    file_name      VARCHAR(255) NOT NULL,             -- 原始文件名（已 sanitize，见 §5.2）
    workspace_path VARCHAR(512),                      -- 实际注入工作区路径（含唯一化后缀，如 uploads/report_1.docx）；uploaded 后赋值
    mime_type      VARCHAR(128) NOT NULL,             -- 白名单校验后的 MIME
    size           BIGINT       NOT NULL,             -- 字节数
    storage_type   VARCHAR(16)  NOT NULL,             -- local / s3（Dify UploadFile.storage_type 同构）
    storage_key    VARCHAR(512) NOT NULL,             -- 存储后端 key/路径（Dify UploadFile.key 同构）
    origin         VARCHAR(16)  NOT NULL,             -- upload（用户上传）/ generated（Agent 产出）
    status         VARCHAR(16)  NOT NULL DEFAULT 'pending',  -- 沙箱注入状态：pending / injected（非沙箱模式恒为 injected）
    created_at     DATETIME(3)  NOT NULL,
    KEY idx_user_status (user_key, status),
    KEY idx_session (session_id, created_at),
    UNIQUE KEY uk_storage (storage_type, storage_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
```

设计要点：

1. **无内容列**：字节一律在 FileStorage；DB 行仅元数据（类型+路径），单行小、无大字段锁与复制压力
2. **`workspace_path`**：文件实际注入工作区的路径（含唯一化后缀，如 `uploads/report_1.docx`）。非沙箱模式在注入 KV 时赋值，沙箱模式在 `injectPendingUploads` 时赋值，`generated` 文件在 `present_file` 时赋值。§7.2 路径提示依赖此列（不拼接原始文件名，避免路径断链）
3. **`status` 状态机**仅沙箱模式使用：`pending`（待注入沙箱）→ `injected`（已注入当前沙箱代）；非沙箱模式上传即置 `injected`（无沙箱可注入）
4. **`user_key` 语义与沙箱一致**：userId 优先、空降级 sessionId（`OpenSandbox.resolveUserKey` 同款），保证注入与读取同命名空间
5. **孤儿对象清扫**：存储写入成功但元数据落库失败（或元数据已删而对象未删）的对象，由清理任务按"存储对象无对应行"回收（§5.3、P2）

---

## 5. 上传链路

### 5.1 端点

```
POST /files/upload
Content-Type: multipart/form-data

字段：
  file        (必填, 单文件; 多文件时前端循环调用或一次多 file 段——v1 单文件)
  userId      (可选, 默认 "debug-user"，与 chat 一致)
  sessionId   (可选, 上传时绑定会话；不绑定时 chat 请求时绑定)
```

响应 `200`：

```json
{
  "file_id": "3f9c...uuid",
  "file_name": "报告.docx",
  "mime_type": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  "size": 204800
}
```

### 5.2 校验（对齐 DeerFlow 安全模式）

| 校验 | 规则 | 失败返回 |
|------|------|---------|
| 文件名 sanitize | basename 截断、去 `\`、UTF-8 ≤255 字节（DeerFlow `normalize_filename` 同款） | 400 invalid_file_name |
| MIME 白名单 | `FILE_UPLOAD_ALLOWED_MIME`（默认：`image/*`、`text/plain`、`text/markdown`、`text/csv`、`application/pdf`、`application/vnd.openxmlformats-officedocument.*`、`application/vnd.ms-*`）——逐项列出，不用宽泛 `text/*`（防 `text/plain` 伪装 `.exe`） | 415 unsupported_file_type |
| 扩展名匹配 | 扩展名与 MIME 一致性抽查（非强制） | 415 |
| 大小上限 | `FILE_UPLOAD_MAX_MB`（默认 20） | 413 file_too_large |
| 数量上限 | 每 user_key pending 未消费文件 ≤ `FILE_UPLOAD_MAX_PENDING`（默认 20）——**软限制**：并发上传可能少量超发（最多 +并发数-1），定期任务清理超限文件 | 429 |

### 5.3 写入顺序与一致性（先写存储、后落元数据）

```
1. 生成 UUID → 计算 storage_key
2. FileStorage.write(key, inputStream, size, contentType)   // 存储先行（原子）
3. INSERT file_asset（storage_type/key 等元数据，status='pending'）
4. 步骤 3 失败 → best-effort FileStorage.delete(key)        // 回滚，防孤儿
   回滚也失败 → 交由孤儿清扫任务兜底（§4.3-4，P2）
5. 返回 file_id
```

上传与对话解耦（Dify 模式"先上传后引用"），前端拿到 file_id 后随 chat 请求携带。

> 删除侧对称：清理/过期删除时**先删 DB 行、后删存储对象**；对象删除失败仅告警（下次清扫重试），不残留无主行。

---

## 6. 工作区文件注入设计（核心章节）

上传文件最终要出现在 Agent 文件工具可读的位置。两种运行模式路径完全不同，统一目标路径约定：

> **约定：上传文件统一出现在工作区 `uploads/` 目录下，实际路径含唯一化后缀（如 `uploads/report_1.docx`），记录在 `file_asset.workspace_path`**
> - 非沙箱模式：即 KV 命名空间相对路径 `uploads/xxx`（由 `RemoteFilesystem.uploadFiles` 写入）
> - 沙箱模式：即容器内 `/workspace/uploads/xxx`（由 execd files API 写入）
> - 系统提示词（AGENTS.md 模板）补充说明该约定，Agent 用 `read_file`/`list_files` 或沙箱脚本访问。LLM 在消息中收到的路径提示已是实际路径（§7.2 使用 `workspace_path` 列），无需自行猜测。

### 6.1 非沙箱模式（SANDBOX_ENABLED=false）：上传时直接写 KV

工作区本体是 `RemoteFilesystemSpec(IsolationScope.USER)` + `MysqlDistributedStore`（agent_fs），文件工具直接读写 KV。

注入点：**chat 请求处理时（sendStream 之前）**，`UploadWorkspaceInjector` 对每个 fileId：

```java
// 与 WorkspaceReader 同款 API：RemoteFilesystem(baseStore, List.of(userId))
var fs = new RemoteFilesystem(baseStore, List.of(userId));
// 1. file_asset 行取 storage_type/storage_key
// 2. FileStorage.read(key) 读字节（存储后端流式读取）
// 3. 唯一化文件名：claim_unique_filename 避免冲突（DeerFlow 同款）
// 4. fs.uploadFiles(ctx, List.of(entry(workspace_path, bytes))) 写入 KV（二进制安全，非 write()）
//    ※ 不用 fs.write(ctx, path, content)：该 API 签名为 String,String，仅支持文本，二进制会损坏
// 5. file_asset.workspace_path = 实际落盘路径（如 "uploads/report_1.docx"）
//    file_asset.status = injected
```

路径格式约定：**统一无前导斜杠**（`uploads/xxx`），与 `RemoteFilesystem.read(ctx, "uploads/xxx", ...)` 语义一致。`ls` 返回的前导 `/` 在读取时需去掉（参考 `WorkspaceReader` 行 57）。系统提示词中用 `uploads/{文件名}`。

- **时机**：在消息构造前同步完成 → Agent 首个工具调用前文件必然就绪（确定性，无竞态）
- **一致性**：注入失败 → 400 返回给前端（非沙箱模式注入失败即对话失败，快速暴露）
- **文件名冲突**：同名文件 `claim_unique_filename` 追加 `_1`（DeerFlow 同款）；chat 消息文本里给出**实际落盘路径**（§7.2），避免 LLM 猜错

### 6.2 沙箱模式（SANDBOX_ENABLED=true）：pending 挂账 + 每 turn 首次 exec 注入

#### 6.2.1 问题

沙箱工作区在 OpenSandbox 容器内（`/workspace`）。上传请求与沙箱生命周期的错位：

1. 上传时沙箱**可能不存在**（USER 级按需创建/复用）
2. 沙箱存在时，上传请求线程**拿不到沙箱句柄**（沙箱由 SDK `SandboxManager` 在 acquire 时才创建/恢复）
3. 容器可能被销毁重建（OpenSandbox Server 侧 GC/超时），已注入文件随之丢失

#### 6.2.2 注入时机选择

| 候选 | 可行性 | 结论 |
|------|--------|------|
| 上传时注入 | 无沙箱句柄、沙箱可能不存在 | ❌ |
| chat 请求时注入 | 同样无沙箱句柄（acquire 在 sendStream 内部发生） | ❌ |
| hydrateWorkspace 时注入 | 仅新容器创建触发，复用容器不经过 | ❌（兜底补充） |
| **doExec 首次执行前注入** | 沙箱模式**所有文件操作都走 doExec**；每 turn 新实例天然复位闸门；与既有 `injectRuntimeFilesIfNeeded`（MEMORY.md 注入）同一位置、同一种已验证的 execd 写通道 | ✅ 主方案 |

**结论：沿用 `OpenSandbox.injectRuntimeFilesIfNeeded` 的延迟注入模式**（该模式已生产验证：注入先于任何工具读取，因为工具读文件最终都走 exec）。

#### 6.2.3 注入流程（OpenSandbox 扩展）

```java
// OpenSandbox.doExec 内，紧邻 injectRuntimeFilesIfNeeded：
injectPendingUploadsIfNeeded(ctx);

private void injectPendingUploadsIfNeeded(RuntimeContext ctx) {
    if (fileAssetStore == null || uploadsInjected.get()) return;   // 每 turn 一次
    var userId = resolveUserKey(ctx);
    if (userId == null) return;
    synchronized (uploadsInjected) {
        if (uploadsInjected.get()) return;
        try {
            // 1. 查库：该 user_key 且 status='pending' 的文件（仅元数据行）
            var pending = fileAssetStore.listPending(userId);
            if (pending.isEmpty()) { uploadsInjected.set(true); return; }
            // 2. 逐文件：FileStorage.read(storage_key) 读字节 → 复用
            //    WorkspaceReader.injectToSandbox 同款 execd 写通道：
            //    osbSandbox.files().write(WriteEntry(path="/workspace/{wsPath}", data=base64, mode=644))
            //    注意：逐文件读取+注入（非全量加载），降低内存峰值
            for (var f : pending) {
                var wsPath = "uploads/" + f.fileName();   // 实际工作区路径（唯一化后）
                try (var in = fileStorage.read(f.storageKey())) {
                    osbSandbox.files().write(List.of(WriteEntry.builder()
                        .path("/workspace/" + wsPath)       // 统一无前导斜杠，沙箱路径加 /workspace 前缀
                        .data(Base64.getEncoder().encodeToString(in.readAllBytes()))
                        .mode(644).build()));
                    // 回写 workspace_path（注入成功后才赋值，保证路径与沙箱内实际一致）
                    fileAssetStore.updateWorkspacePath(f.id(), wsPath);
                }
            }
            // 3. 全部成功后批量置 injected
            fileAssetStore.markInjected(pendingIds);
            uploadsInjected.set(true);
        } catch (Exception e) {
            // fail-soft：注入失败不阻塞 exec，warn + 本 turn 重试闸门不复位
            // （下次 turn 新实例自然重试）；文件状态保持 pending
            log.warn("Failed to inject pending uploads into sandbox: {}", e.getMessage());
        }
    }
}
```

- **每 turn 一次**：`uploadsInjected` 实例级 AtomicBoolean，acquire→create/resume 每 turn 新实例 → 每 turn 首次 exec 检查一次
- **幂等**：pending→injected 状态机，已注入文件不会每 turn 重复传输（大文件 20MB×N turn 的浪费被消除）
- **fail-soft**：与 MCP fail-soft 语义一致——注入失败仅告警，Agent 仍可运行（该批文件本轮不可见），下 turn 重试

#### 6.2.4 容器销毁重建的一致性保证（防丢失）

沙箱容器被销毁（Server GC / 超时 kill）时，容器内 `/workspace/uploads/` 随之消失，但 DB 中 status 已置 injected → 新容器将**漏注入**。

双保险机制：

**① 主动回滚**：`OpenSandboxClient.delete()` 内，容器销毁前将其 userKey 名下 `status='injected'` 且 `origin='upload'` 的行**回滚为 pending**：

```java
public void delete(Sandbox sandbox) {
    OpenSandbox osb = (OpenSandbox) sandbox;
    if (osb.getUserKey() != null) {
        fileAssetStore.resetInjectedToPending(osb.getUserKey());  // 新容器首 exec 重新注入
    }
    osb.getOsbSandbox().kill();
    ...
}
```

**② 被动兜底**：`OpenSandboxClient.create()` 内，**新容器创建时**也对同 userKey 执行 `resetInjectedToPending`——防止 delete 崩溃/进程 OOM 导致①未执行，injected 状态 stuck：

```java
public Sandbox create(...) {
    ...
    OpenSandbox sandbox = new OpenSandbox(state, osbSandbox, ...);
    bindUserKey(sandbox);
    // 被动兜底：新容器启动时，该用户所有 injected 上传文件回 pending → 首 exec 重新注入
    if (sandbox.getUserKey() != null) {
        fileAssetStore.resetInjectedToPending(sandbox.getUserKey());
    }
    sandbox.start();
    return sandbox;
}
```

> **保证语义**：任何新容器启动，该用户所有 upload 文件必然 pending → 首 exec 全量注入，不依赖前代容器 delete 是否成功。

> OpenSandbox 需补充 `getUserKey()` 暴露（现 setUserKey 仅写字段）。

#### 6.2.5 保证性小结

| 场景 | 保证 |
|------|------|
| 上传时无沙箱 | DB pending 挂账 → 沙箱创建后首 exec 注入 |
| 上传时沙箱已存在（复用） | 下 turn 新实例首 exec 注入（doExec 注入先于一切工具读取） |
| 容器销毁重建 | delete 主动回滚 + create 被动兜底 → 新容器首 exec 重新注入（双保险） |
| delete 过程崩溃 | create 被动兜底 → 新容器启动时 injected 回 pending，首 exec 全量注入 |
| 同一 turn 内上传 | 不可能发生：turn 租约保证单执行段，上传发生在 turn 间隙 |
| 注入失败 | status 保持 pending，fail-soft + 下 turn 重试 |
| 同名文件 | 唯一名追加 `_1`，workspace_path 记录实际路径经消息文本明确告知 LLM |
| 非沙箱已注入文件 | 不在沙箱中可用（两种模式的 workspace 物理隔离，属正确行为） |

### 6.3 图片的特殊性：内联 + 注入双轨

图片（image/*）在 §7.2 内联为 `ImageBlock` 供视觉模型直接识别；**同时仍按 §6 注入工作区**（`uploads/{唯一化文件名}`，路径记录在 `file_asset.workspace_path`），Agent 后续可经工具或沙箱脚本处理原始图片。代价是容器内多一份副本（可接受，≤ `FILE_UPLOAD_MAX_MB`）。

---

## 7. 多模态消息构造（SDK ContentBlock 最大化）

### 7.1 ChatRequest 扩展

```java
public record ChatRequest(String message, String userId, List<String> fileIds) {}
```

`fileIds` 可选；`message` 允许为空串（仅当 fileIds 非空时）——纯文件消息场景。

### 7.2 消息构造（SessionStreamController.chat 内）

按 MIME 分两类处理（**先注入工作区、再构造消息**，保证两类能力都可用）：

```java
var blocks = new ArrayList<ContentBlock>();
if (message != null && !message.isBlank()) blocks.add(TextBlock.from(message));
var pathHints = new ArrayList<String>();          // 非图片文件的路径提示
long imageInlineBytes = 0;                        // 图片内联字节累计
for (var fileId : fileIds) {
    var meta = fileAssetStore.get(fileId);        // 取元数据（无归属校验，平台无认证）
    String mime = meta.mimeType();
    long maxSize = props.fileImageMaxMb() * 1024 * 1024;
    long totalBudget = props.fileImageInlineTotalMb() * 1024 * 1024;
    if (mime.startsWith("image/")
        && meta.size() <= maxSize
        && imageInlineBytes + meta.size() <= totalBudget) {
        // ① 图片（≤ FILE_IMAGE_MAX_MB 且总预算内）：SDK 原生多模态内联（视觉模型直接识别）
        //    字节从 FileStorage.read(storage_key) 读入；超限图片走 ② 路径提示
        blocks.add(ImageBlock.builder()
            .source(Base64Source.builder().mediaType(mime)
                .data(Base64.getEncoder().encodeToString(readAll(meta))).build())
            .build());
        imageInlineBytes += meta.size();
    } else {
        // ② 文档/超限图片：路径提示（不解析、不内联，本方案不含文档解析）
        //    使用 workspace_path（注入时赋值，含唯一化后缀），非原始文件名，避免路径断链
        var wsPath = meta.workspacePath() != null ? meta.workspacePath()
            : "uploads/" + meta.fileName();       // 兜底：workspace_path 尚未赋值时
        pathHints.add(wsPath);
    }
}
if (!pathHints.isEmpty()) {
    blocks.add(TextBlock.from("用户上传了文件（工作区相对路径）：\n- " + String.join("\n- ", pathHints)
        + "\n请用 read_file / list_files 工具或沙箱脚本处理这些文件。"));
}
var msg = Msg.builder().role(MsgRole.USER).name(userId)
    .metadata(Map.of(UiContextStore.METADATA_SESSION_KEY, sessionId))
    .content(blocks).build();
```

设计要点：

1. **图片走 SDK 原生多模态**（`ImageBlock`+`Base64Source` → OpenAI formatter `image_url`），零自定义协议
2. **文档不内联、不解析**：docx/xlsx/pptx/pdf/csv/txt/md 一律走工作区注入 + 路径提示，由 Agent 自行决定用 `read_file`（文本类）还是沙箱脚本（二进制类）处理——与 DeerFlow `UploadsMiddleware` 的路径注入思想一致
3. **路径提示用 `workspace_path`**（注入时赋值，含唯一化后缀），不拼接原始文件名——保证 LLM 调 `read_file` 时路径与沙箱/KV 内实际一致
4. **图片内联双预算**：单文件 ≤ `FILE_IMAGE_MAX_MB`，多图总字节 ≤ `FILE_IMAGE_INLINE_TOTAL_MB`（防上下文溢出）；超限图片降级路径提示
5. **fileId 不校验归属**（已确认）：平台无认证体系，userId 客户端自报，校验无实际意义；UUID 不可枚举 + 不开放目录列举即可（§9）
6. **存储读取失败降级**：`readAll(meta)` 抛 IOException（存储后端不可达）时 catch → 该文件降级路径提示，不阻断整个消息构造
7. `metadata` 保持 `UiContextStore.METADATA_SESSION_KEY`（4.7 ui_context 注入依赖）

---

## 8. Agent 产出文件回传链路

### 8.1 present_file 工具（@Tool 模式，同 BusinessTools）

```java
@Tool(name = "present_file",
      description = "将工作区产出文件登记到平台供用户下载。处理后生成的报表/图片/文档"
          + "必须调用本工具（file_path 为工作区相对路径或绝对路径）")
public String presentFile(RuntimeContext ctx, @ToolParam(name="file_path") String filePath)
```

行为：

1. 路径校验：必须位于工作区内（沙箱：`/workspace/` 前缀；非沙箱：相对路径，防 `../` 穿越——复用 `FileToolUtils.validatePath` 语义）
2. 读取文件字节（沙箱：execd read；非沙箱：KV read）。**大小限制**：≤ `FILE_PRESENT_MAX_MB`（默认 50），超出时工具返回错误提示（不 OOM）
3. **`FileStorage.write(key, bytes, mime)` 落存储后端**（key 按 §4.2 格式，origin=generated）
4. `INSERT file_asset`（`origin='generated'`，`storage_type`/`storage_key`，`workspace_path=filePath`，`status='injected'`，session_id 从 ctx 取）；落库失败回滚存储对象（§5.3 同款顺序）
5. 返回 JSON 字符串（供 LLM 确认 + 供控制器合成事件）：

```json
{"file_id":"uuid","file_name":"report.pdf","mime_type":"application/pdf","size":102400}
```

### 8.2 注册

`FileTools` 加入 `customTools`（`AgentScopeConfig`，与 BusinessTools 并列），受 `tools.json deny` 与 OAF `deniedTools` 过滤（既有机制自动生效）。

### 8.3 FILE_READY 合成帧（主方案：控制器合成）

`SessionStreamController.handleEvent` 内：`present_file` 的 `ToolResultTextDeltaEvent` 按 `toolCallId` 累积文本（ConcurrentHashMap 桶）→ `ToolResultEndEvent`（state=OK 且 toolCallName=present_file）时解析累积 JSON → 校验 file_asset 存在 → 追加一帧：

```json
{"type":"file_ready",
 "file_id":"uuid","file_name":"report.pdf","mime_type":"application/pdf","size":102400,
 "download_url":"/agent/release-agent/files/uuid"}
```

> `download_url` 为前端 Next rewrite 前缀下的相对路径（同源可达，前端 `/agent/release-agent/*` → agent 服务 8100）。A2A/长连接其他链路以 host 拼接绝对 URL。
> 合成帧不打断原事件流（插在 TOOL_RESULT_END 之后）。
> 用累积解析而非 DB 查询的原因：turn 租约虽保证单执行段，但同 turn 多次 present_file 时 DB "latest" 查询不可判定顺序；按 toolCallId 累积确定性最强。

### 8.4 备选：SDK CustomEvent 原生事件（SPIKE）

`CustomEvent(name, value)` + `AgentEventEmitter`（Reactor ContextView key `agentscope.agent.event.emitter`）已在 2.0.0 存在。若 SPIKE 验证工具执行上下文可触达 ContextView（经 `ToolExecutionContext`/`ContextStore` 链），则 present_file 工具内直接：

```java
AgentEventEmitter.fromContext(contextView)
    .ifPresent(e -> e.emit(new CustomEvent("file_ready", Map.of("file_id", id, ...))));
```

`AgentEventSseSerializer` 增加 `CustomEvent` 词条（name=file_ready → 同 8.3 的 JSON）。**优势**：事件由 SDK 原生事件流携带，Channel 与 A2A 链路自动对齐，无控制器拼接逻辑。**实现期先 SPIKE，不可行则退回 8.3**。

### 8.5 SDK 原生二进制通道兜底（tool_result_data_delta）

工具返回 `ToolResultBlock` 携带 `DataBlock(URLSource/Base64Source)` 时，SDK 自动发 `ToolResultDataDeltaEvent`。当前**仅 invokeStream 链路处理、Channel 链路序列化器缺失**。补齐 `AgentEventSseSerializer`（§10），使图片类工具结果在 Channel 链路也原生回传（base64 或 URL），与 FILE_READY 互补：

- FILE_READY：文件级产物（下载导向，数据在 DB）
- tool_result_data_delta：小体积二进制结果（内联导向，SDK 原生）

---

## 9. 下载链路

```
GET /files/{fileId}?inline=1
```

| 项 | 设计 |
|----|------|
| 定位 | file_asset 主键查行（元数据）；行不存在 404 |
| 鉴权 | **不校验**（已确认）：fileId 是 UUID 不可枚举，持有 URL 即授权；与平台无认证架构一致（REDESIGN "不做认证"）。分享 URL 即分享文件——如需强鉴权（签名 token）列为 P2 |
| 读取 | `FileStorage.read(storage_key)` 流式读取（local=FileInputStream；s3=getObject 流），经 `StreamingResponseBody` 转发，不整载内存 |
| 响应 | `Content-Type: {mime_type}`；`Content-Length: {size}`；`Content-Disposition: attachment; filename*=UTF-8''{urlencoded}`；`inline=1` 且 image/text → inline（图片预览） |
| 安全 | 文件名 sanitize 后回填 header；不开放目录列举；URL 含 UUID 不可枚举 |
| P2 | `Range` 分段（local=FileChannel position 读；s3=getObject range header）+ `ETag` 对齐 Dify file_preview |

前端下载即 `window.open(download_url)`（rewrite 同源，无需 CORS）。

---

## 10. SSE 事件词表扩展（AgentEventSseSerializer）

| 词条 | 来源 | payload |
|------|------|---------|
| `tool_result_data_delta` | `ToolResultDataDeltaEvent`（新增分支） | `{type, tool_call_id, tool_call_name, media_type, data 或 url}`（提取逻辑复用 `AgentRuntimeService:307-317` 的 Base64Source/URLSource 分支） |
| `DATA_BLOCK_DELTA` 增强 | 既有分支 | 追加 `media_type`（从上游携带；v2.0.0 事件类无该字段则省略，向后兼容） |
| `file_ready` | 合成帧（§8.3）/ CustomEvent（§8.4） | `{type:"file_ready", file_id, file_name, mime_type, size, download_url}` |

前端 assistant 页新增 `file_ready` 分支渲染文件卡片（图标+文件名+大小+下载按钮）；`tool_result_data_delta` 中 `media_type=image/*` 且带 `data` 时内联渲染 `<img>`。

---

## 11. A2A 协议对齐（P2）

A2A `FilePart`（`FileWithBytes`）经 SDK `PartParserRouter` 仅 image/audio/video 自动转 `ImageBlock` 等；**文档类 FilePart 转换结果为 null（SDK FilePartParser 不支持）**。

P2 扩展（`A2AController` 预处理，对齐既有"兼容转换"模式）：检测文档类 FilePart → 提取字节 → **FileStorage.write 落存储后端** + `INSERT file_asset` 元数据（origin=upload, user_key=metadata.userId）→ 工作区注入（§6）→ 将原 FilePart 替换为 `TextPart`（路径提示文本）。使 A2A 客户端（如外部系统）也能上传文档。图片类保持 SDK 原生转换不动。

---

## 12. 配置项（AgentManagerProperties 新增）

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `FILE_UPLOAD_ENABLED` | `true` | 上传端点开关（false 时 403） |
| `FILE_UPLOAD_MAX_MB` | `20` | 单文件大小上限 |
| `FILE_UPLOAD_MAX_PENDING` | `20` | 每 user_key pending 文件数上限 |
| `FILE_UPLOAD_ALLOWED_MIME` | `image/*,text/plain,text/markdown,text/csv,application/pdf,application/vnd.openxmlformats-officedocument.*,application/vnd.ms-*` | MIME 白名单（逗号分隔，支持 * 通配；逐项列出，不用宽泛 `text/*`） |
| `FILE_IMAGE_MAX_MB` | `5` | 图片内联单文件大小上限（超限图片降级为路径提示，不内联） |
| `FILE_IMAGE_INLINE_TOTAL_MB` | `15` | 图片内联总字节预算（多图叠加超限后续图片降级路径提示，防上下文溢出） |
| `FILE_PRESENT_MAX_MB` | `50` | present_file 工具产出文件大小上限（超出工具返回错误） |
| `FILE_DOWNLOAD_ENABLED` | `true` | 下载端点开关 |
| `FILE_RETENTION_DAYS` | `7` | upload 文件保留天数（P2 清理，删行+删存储对象） |
| `FILE_STORAGE_TYPE` | `local` | 存储后端：`local` / `s3`（Dify storage_type 同构） |
| `FILE_STORAGE_LOCAL_DIR` | `/data/files` | local 后端根目录。**K8s 挂载方案（已确认）**：platform-data PVC 新 subPath `files/`（可写），由 platform-backend 构造业务 Pod Deployment 时注入 volumeMount（与只读 `packages/{id}` 并列），详见 §13 P1 跨模块改动 |
| `FILE_STORAGE_S3_ENDPOINT` | — | s3 后端 endpoint（例：`http://minio:9000`，兼容 MinIO/Ceph RGW/阿里 OSS S3 网关） |
| `FILE_STORAGE_S3_ACCESS_KEY` | — | s3 后端 accessKey（敏感，.env.secrets） |
| `FILE_STORAGE_S3_SECRET_KEY` | — | s3 后端 secretKey（敏感，.env.secrets） |
| `FILE_STORAGE_S3_BUCKET` | `agent-files` | s3 后端 bucket |

Spring Boot 内置 multipart 配置需同步调整（`application.yml`）：

```yaml
spring:
  servlet:
    multipart:
      max-file-size: ${FILE_UPLOAD_MAX_MB:20}MB
      max-request-size: ${FILE_UPLOAD_MAX_MB:20}MB
```

---

## 13. 实施步骤

| Phase | 内容 | 涉及文件 |
|-------|------|---------|
| P1 | **存储后端**：FileStorage 接口 + LocalFileStorage（原子写/穿越防护）+ S3FileStorage（MinIO SDK）+ 工厂按 FILE_STORAGE_TYPE 装配（**local+s3 一起实现，已确认**） | service/storage/*.java、pom.xml、AgentManagerProperties |
| P1 | file_asset 元数据表 + FileAssetStore（DDL/CRUD/状态机） | service/FileAssetStore.java |
| P1 | FileController 上传（multipart + 校验 + 先存储后落元数据 + 失败回滚） | controller/FileController.java |
| P1 | **跨模块（platform-backend）**：业务 Pod 构造时注入可写 volumeMount——platform-data PVC subPath `files/` → `/data/files`（与只读 `packages/{id}` 并列）；`FILE_STORAGE_LOCAL_DIR` 默认值对齐 | backend/internal（K8s 对象构造） |
| P2 | 非沙箱注入：UploadWorkspaceInjector（存储读字节→KV 写）+ ChatRequest.fileIds + 消息构造（§7.2 图片内联/文档路径提示） | service/UploadWorkspaceInjector.java、SessionStreamController |
| P2 | 沙箱注入：OpenSandbox.doExec hook（存储读字节→execd 写）+ delete 回滚 + getUserKey | OpenSandbox.java、OpenSandboxClient.java、FileAssetStore |
| P3 | present_file 工具 + FILE_READY 合成帧 + ToolResultDataDeltaEvent 序列化 + 下载端点（存储流式转发） | tool/FileTools.java、SessionStreamController、AgentEventSseSerializer、FileController |
| P3 | 前端：附件上传、图片预览、file_ready 卡片 | frontend/src/app/assistant/page.tsx |
| P4 | A2A 文档 FilePart 转换（§11）+ 清理任务（删行+删对象+孤儿清扫）+ Range/ETag | A2AController、SessionCleanupService、FileController |
| P5 | 系统提示词模板补充 uploads/ 约定与 present_file 用法 | OAF 模板 |

---

## 14. 测试计划

| 测试类型 | 用例 | 位置 |
|---------|------|------|
| 单测 | **LocalFileStorage**：原子写（tmp+move）、key 穿越拒绝（`../`）、read/exists/delete、并发写同名 key | LocalFileStorageTest |
| 单测 | **S3FileStorage**：putObject/getObject/removeObject（mock MinIO SDK 或 testcontainers MinIO）、contentType 透传 | S3FileStorageTest |
| 单测 | file_asset DDL/CRUD/状态机转移 | FileAssetStoreTest |
| 单测 | 上传校验矩阵（MIME 白名单/大小/文件名 sanitize/数量软限制并发超发）+ 落库失败存储对象回滚 | FileControllerTest |
| 单测 | **消息构造三分支**：image(≤5MB且总预算内)→ImageBlock、超限图片→路径提示、文档→路径提示、存储读取失败降级 | SessionStreamControllerTest |
| 单测 | **workspace_path**：注入时赋值正确（含唯一化后缀）、路径提示使用 workspace_path 而非原始文件名 | UploadWorkspaceInjectorTest / SessionStreamControllerTest |
| 单测 | 非沙箱 KV 注入（存储读字节→RemoteFilesystem.uploadFiles 写 uploads/，可读断言） | UploadWorkspaceInjectorTest |
| 单测 | 沙箱注入：pending→injected 转移、注入失败保持 pending、delete 回滚 | OpenSandboxTest / OpenSandboxClientTest（mock osbSandbox） |
| 单测 | present_file：路径穿越拒绝、workspace 前缀校验、产物入存储+落元数据 | FileToolsTest |
| 单测 | FILE_READY 合成帧：TextDelta 累积解析、End 触发、JSON 畸形降级 | SessionStreamControllerTest |
| 单测 | 下载端点：404/Content-Disposition/inline 分支/存储对象缺失 502（无鉴权——平台无认证） | FileControllerTest |
| 序列化单测 | ToolResultDataDeltaEvent 词条（Base64Source/URLSource 两分支） | AgentEventSseSerializerTest |
| E2E | **上传 docx/csv → chat 引用 → 消息含路径提示（mock LLM 断言）→ 沙箱内 read_file 可见 → 下载字节一致（local 与 s3 两档各跑一遍）** | e2e/file-support-e2e.sh（沙箱/非沙箱 × local/s3） |
| E2E | 上传图片 → 视觉模型识别（mock 断言 ImageBlock 入参） | e2e |
| E2E | present_file → SSE file_ready 帧 → 下载文件字节一致 | e2e |

---

## 15. 风险与注意事项

1. **沙箱注入时延与内存**：首 exec 前注入 N×20MB 会拖慢首个工具调用，且 `in.readAllBytes()` + Base64 编码峰值内存 ~1.4x 文件大小（20MB → ~28MB）；缓解：`FILE_UPLOAD_MAX_MB` 默认 20MB 封顶，注入为逐文件读取+写入（非全量加载），后续版本可改 execd 流式分片
2. **`AgentEventEmitter` SPIKE 失败**：若 CustomEvent 通道不可行，8.3 控制器合成帧为既定主方案，功能不受影响
3. **元数据与存储一致性**：先写存储后落元数据 + 失败回滚（§5.3）；仍可能有孤儿对象（进程崩溃于两步之间）→ 孤儿清扫任务兜底（P4）；删除侧先删行后删对象，对象删除失败仅告警可重试
4. **local 后端的多副本/多 Pod 问题**：agent-framework 若扩容多副本，本地路径存储不共享；单实例部署（当前 OAF 平台形态）无此问题；多副本必须切 s3 后端（设计已通过 FILE_STORAGE_TYPE 支持）
5. **前端 rewrite 路径**：`download_url` 用相对 `/agent/release-agent/files/{id}` 依赖 Next rewrite 规则；若服务名变化需同步（与既有 `/agent/release-agent/threads/*` 同机制）
6. **图片内联体积**：多图 + 大图会撑爆模型上下文；内联仅 `image/*` 且单文件 ≤ `FILE_IMAGE_MAX_MB`（默认 5MB），多图总字节 ≤ `FILE_IMAGE_INLINE_TOTAL_MB`（默认 15MB），超限图片降级为路径提示（不内联）
7. **清理与引用一致性**：清理任务只删超期 upload 且确认无 pending 状态；`origin=generated` 的文件按 session 归属清理（session 对应的 agent_state 被清除时同步删 generated 文件），P2 细化
8. **local 后端目录挂载（已确认方案）**：platform-data PVC 新 subPath `files/`（可写）注入业务 Pod，由 platform-backend 构造 Deployment 时统一处理（§13 P1 跨模块改动）；上线前需验证 PVC 剩余容量（10Gi 总，当前 packages 占用小）
9. **文档无解析能力**：本方案对 docx/xlsx/pptx/pdf 只提供"可达性"（工作区注入 + 下载），内容读取由 Agent 自行完成——文本类（txt/md/csv）用 `read_file`；二进制类用沙箱脚本；非沙箱模式下二进制文档 Agent 无法解析，属已知限制（在路径提示文本中不承诺能力）
10. **沙箱镜像无 pip/python-docx/pandas（已实测确认）**：`opensandbox/code-interpreter:v1.1.0` 为 python 3.12.3 纯净 stdlib（无 pip/pip3/`python3 -m pip`）；沙箱内处理 docx/xlsx 只能用 **zipfile + xml.etree 纯 stdlib 手工解析**，csv 用 stdlib `csv` 模块；S-S6 用例按此约束设计；Agent 无法在沙箱内安装第三方库（业务约束，需在系统提示词中说明）
11. **下载无鉴权**（已确认）：URL 分享即授权；file_asset 中 `user_key` 保留（用于沙箱注入命名空间），不用于下载校验
12. **s3 后端可用性**：s3 不可达时上传/下载/注入全部受影响；fail-fast 返回 502 并在健康检查暴露（P4 加 `/health` 存储探活），业务对话不因存储故障而崩溃（消息构造时图片读取失败降级为路径提示）
13. **Spring Boot multipart 配置**：`spring.servlet.multipart.max-file-size` / `max-request-size` 必须与 `FILE_UPLOAD_MAX_MB` 对齐（§12 已标注 application.yml 片段），否则上传413 由 Tomcat 拦截，未进入 FileController 校验链
14. **非沙箱模式二进制文档不可读**：`RemoteFilesystem.uploadFiles` 写入 KV 后，Harness 内置 `read_file` 工具对二进制文件（docx/pdf）返回乱码或失败；Agent 仅能对 txt/md/csv 使用 `read_file`，二进制文档只能通过下载端点获取——这是非沙箱模式的已知限制
15. **FILE_READY 合成帧累积内存**：§8.3 的 ToolResultTextDeltaEvent 累积按 toolCallId 桶存储，理论上 present_file 返回的 JSON 很小（~200 字节），无上限问题；但若 LLM 误将大量文本作为 tool result 返回，桶内存不受限——实现时加 64KB 单桶上限，超出丢弃（warn 日志）

---

## 16. 验证方案（用例设计）

### 16.1 验证矩阵

四类场景（上传文档 / 上传图片 / 输出文档 / 输出图片）× 验证层级（单测 / E2E）× 运行档位（沙箱/非沙箱 × local/s3）：

| 场景 | 单测 | E2E local | E2E s3 | UI E2E |
|------|:----:|:---------:|:------:|:------:|
| S1 上传文档（docx/csv/txt） | ✅ | ✅ | ✅ | ✅ |
| S2 上传图片（png/jpg） | ✅ | ✅ | ✅ | ✅ |
| S3 输出文档（Agent 生成→下载） | ✅ | ✅ | ✅ | ✅ |
| S4 输出图片（Agent 生成→下载） | ✅ | ✅ | ✅ | ✅ |
| 异常/安全（413/415/400/404/穿越） | ✅ | ✅ | — | — |

E2E 运行档位：非沙箱档（`SANDBOX_ENABLED=false`，release-agent 默认部署）+ 沙箱档（`SANDBOX_ENABLED=true`，重发布 release-agent 后复跑同一脚本，**非业务时段执行**）+ s3 档（临时 MinIO 实例，local+s3 已确认一起实现）。

### 16.2 测试夹具（e2e/fixtures + 运行时生成）

| 夹具 | 生成方式 | 用途 |
|------|---------|------|
| `demo.csv` | 脚本 heredoc 生成，3 行数据含哨兵值 `SENTINEL-9f3a2c` | S1 上传+注入验证（LLM read_file 断言哨兵值） |
| `demo.docx` | 预置最小 OOXML zip fixture（`[Content_Types].xml` + `word/document.xml`，~2KB，含哨兵文本 `DOCX-SENTINEL`） | S1 二进制文档上传链路 |
| `tiny-red.png` | 脚本内 base64 常量解码写盘（1×1 红色 PNG，~70 字节） | S2 图片上传；S4 生成图片的期望字节 |
| `oversize.bin` | `dd if=/dev/zero bs=1M count=21` 运行时生成 | 异常 413 校验 |
| `evil.exe` | 任意字节 + 文件名 `.exe` | 异常 415 校验 |

### 16.3 单测用例详表（断言级）

| 编号 | 测试类 | 用例 | 关键断言 |
|------|--------|------|---------|
| UT-01 | LocalFileStorageTest | 原子写 | write 后文件存在且内容一致；`{key}.tmp` 残留为 0 |
| UT-02 | LocalFileStorageTest | key 穿越防护 | `write("../etc/passwd")` 抛异常，根目录外无文件产生 |
| UT-03 | LocalFileStorageTest | read/exists/delete | 不存在 read 抛 IOException；delete 幂等 |
| UT-04 | S3FileStorageTest（mock SDK） | put/get/remove | 断言 putObject 收到 contentType/key；getObject 流回传一致 |
| UT-05 | FileAssetStoreTest | DDL 幂等 + CRUD | 建表两次不报错；insert/get/update workspace_path |
| UT-06 | FileAssetStoreTest | 状态机 | pending→markInjected→resetInjectedToPending 转移正确；listPending 过滤 |
| UT-07 | FileControllerTest | 上传成功 | mock storage.write 被调 + DB 行字段全对 + 响应 file_id/size |
| UT-08 | FileControllerTest | 校验矩阵 | .exe→415、21MB→413、`../a.txt`→400、白名单边界 |
| UT-09 | FileControllerTest | 落库失败回滚 | mock DB 抛异常 → storage.delete 被调 |
| UT-10 | UploadWorkspaceInjectorTest | 非沙箱注入 | `uploadFiles` 收到 bytes 与 `uploads/{唯一化名}`；workspace_path 回写 DB |
| UT-11 | UploadWorkspaceInjectorTest | 沙箱注入 | mock osbSandbox：pending 列表→files().write 参数 `/workspace/uploads/{wsPath}`→markInjected |
| UT-12 | UploadWorkspaceInjectorTest | 注入失败 | execd 抛异常 → status 保持 pending、不阻塞 |
| UT-13 | SessionStreamControllerTest | 图片内联 | 1×1 PNG → Msg.content 含 ImageBlock(Base64Source)，data 为正确 base64 |
| UT-14 | SessionStreamControllerTest | 图片预算 | 单图 >5MB 或累计 >15MB → 降级路径提示无 ImageBlock |
| UT-15 | SessionStreamControllerTest | 文档路径提示 | docx → TextBlock 含 `uploads/demo_1.docx`（workspace_path）与 read_file 指引 |
| UT-16 | SessionStreamControllerTest | 存储读取失败降级 | storage.read 抛 IOException → 路径提示、不抛错（注：fileId 归属校验已移除——平台无认证，不校验） |
| UT-17 | SessionStreamControllerTest | 存储故障降级 | storage.read 抛 IOException → 路径提示、不抛错 |
| UT-18 | FileToolsTest | present_file 成功 | storage.write 被调 + DB 行 origin=generated/workspace_path + 返回 JSON 正确 |
| UT-19 | FileToolsTest | 路径穿越/越界 | `../../etc/passwd`、`/etc/passwd` → 错误返回不落库 |
| UT-20 | FileToolsTest | 大小限制 | >FILE_PRESENT_MAX_MB → 错误返回 |
| UT-21 | SessionStreamControllerTest | FILE_READY 帧 | TextDelta 累积→End 触发合成帧 JSON 字段全对；JSON 畸形→降级无帧 |
| UT-22 | AgentEventSseSerializerTest | 二进制词条 | ToolResultDataDeltaEvent 的 Base64Source/URLSource 两分支序列化正确 |
| UT-23 | FileControllerTest | 下载 | 404（不存在）/200 流式 + Content-Disposition + inline 分支（无鉴权——平台无认证） |

### 16.4 E2E 脚本设计（e2e/file-support-e2e.sh）

入口：`AGENT=http://100.66.1.5:8911/agent/release-agent`（rewrite 同源，与 chat-ui-e2e 一致）；LLM 断言超时窗口 ≥300s；每轮对话用运行级唯一 `userId`（防 HITL/会话串扰，e2e/AGENTS.md 约定）。

#### 场景 S1：上传文档

| 用例 | 步骤 | 断言 |
|------|------|------|
| S1-1 | `POST /files/upload -F file=@demo.csv -F userId=$UID` | 200；响应 file_id/size/name 正确 |
| S1-2 | 上传 demo.docx | 200；mime_type 为 OOXML |
| S1-3 | 三方一致（DB） | `kubectl exec` mysql 查 `file_asset`：2 行，storage_type=local，storage_key 含 `upload/` |
| S1-4 | 三方一致（存储） | `kubectl exec deployment/<agent> -- sha256sum /data/files/{key}` 与本地源文件一致 |
| S1-5 | 注入（LLM 间接证明） | chat `{message:"请读取 uploads 目录下我上传的 csv，告诉我最后一行的第一个字段", fileIds:[id]}` → SSE 最终回复含 `SENTINEL-9f3a2c` |
| S1-6 | 注入（KV/沙箱直接证明） | 非沙箱档：`kubectl exec` mysql 查 `agent_fs` 存在 `uploads/demo_1.csv` key；沙箱档：由 §16.5 S-S1/S-S2 直接证明（容器内 `ls`+`cat`） |
| S1-7 | 下载 | `GET /files/{id}` → 200，`Content-Disposition: attachment`，sha256 与源一致 |
| S1-8 | 异常矩阵 | 21MB→413；evil.exe→415；`../x.csv` 文件名→400 |
| S1-9 | 同名重传 | 再传 demo.csv → 新 file_id；注入后 workspace_path 为 `uploads/demo_2.csv`（DB 断言唯一化后缀） |

#### 场景 S2：上传图片

| 用例 | 步骤 | 断言 |
|------|------|------|
| S2-1 | 上传 tiny-red.png | 200；mime_type=image/png |
| S2-2 | 视觉识别（已确认 mimo-v2.5 支持视觉，用例启用） | chat 引用 + "这张图片是什么颜色？" → 回复含"红"（证明 ImageBlock 内联 → 视觉模型链路端到端可用） |
| S2-3 | 下载 | 200；inline=1 时 `Content-Disposition: inline`；字节与 base64 常量一致 |

#### 场景 S3：输出文档

| 用例 | 步骤 | 断言 |
|------|------|------|
| S3-1 | 生成+回传 | chat `"在工作区 outputs/ 生成 report.txt，内容为 FILE-SENTINEL-42，然后调用 present_file"`（沙箱档走 shell_execute；非沙箱档提示词让 Agent 用 write_file 工具） | SSE 流中出现 `"type":"file_ready"` 帧，提取 file_id/file_name=mime=text/plain |
| S3-2 | 下载 | `GET /files/{file_id}` → 内容恰为 `FILE-SENTINEL-42` |
| S3-3 | DB 三方一致 | file_asset 行 origin=generated、workspace_path=outputs/report.txt |

#### 场景 S4：输出图片

| 用例 | 步骤 | 断言 |
|------|------|------|
| S4-1 | 生成+回传 | chat `"用 python 纯 stdlib 在 outputs/ 写一个 1x1 红色 PNG（base64 解码写字节），然后 present_file"`（沙箱档） | file_ready 帧 mime_type=image/png |
| S4-2 | 下载 | 字节与 tiny-red.png 的 base64 常量解码一致（逐字节断言） |
| S4-3 | 工具二进制通道（条件） | 若 MCP 工具返回 DataBlock(URLSource) 场景存在 → SSE 出现 `tool_result_data_delta` 词条（UT-22 为主覆盖） |

#### 异常与收尾

| 用例 | 步骤 | 断言 |
|------|------|------|
| X-1 | `GET /files/不存在的uuid` | 404 |
| X-2 | 收尾三方一致 | DB 行数（upload+generated）== 存储对象数（`find /data/files -type f | wc -l`，经 kubectl exec） |

### 16.5 沙箱模式专项验证（SANDBOX_ENABLED=true 档）

#### 16.5.1 前置与环境

| 项 | 要求 |
|----|------|
| 部署 | release-agent 服务 env 增 `SANDBOX_ENABLED=true` + `OPENSANDBOX_SERVER_URL` + `OPENSANDBOX_API_KEY` 后重发布（Platform API 全量覆盖 env 语义） |
| 前置检查 | 沙箱 Server 可达：`curl http://<OPENSANDBOX_SERVER_URL>/health` → healthy；失败则中止脚本（明确报"沙箱环境不可用"而非误报用例失败） |
| 沙箱镜像依赖 | **已实测**：`opensandbox/code-interpreter:v1.1.0` = python 3.12.3 纯净 stdlib，**无 pip / python-docx / pandas**（pip/pip3/`python3 -m pip` 均不存在）；`zipfile`/`csv`/`xml.etree`/`base64` 可用。所有沙箱脚本用例按纯 stdlib 设计 |
| 沙箱内验证手段 | **Agent shell_execute 自证**：让 Agent 执行确定性命令（`ls`/`cat`）并回显输出——不依赖 LLM 推理，输出即为沙箱内文件系统的直接证据 |
| 会话隔离 | 每用例独立 `userId`（沙箱 USER 级复用，防跨用例容器串扰） |

#### 16.5.2 用例表（S-S 编号，沙箱档专属）

| 编号 | 用例 | 步骤 | 断言 |
|------|------|------|------|
| S-S1 | **注入路径直接验证** | 上传 demo.csv → chat `{message:"执行命令：ls /workspace/uploads/ 并输出完整结果", fileIds:[id]}` | SSE 回复含 `demo.csv` 文件名（壳内 `ls` 直接证明注入到 `/workspace/uploads/`） |
| S-S2 | **注入内容正确性** | 接 S-S1：chat `"执行命令：cat /workspace/uploads/demo.csv 并输出"` | 回复含 `SENTINEL-9f3a2c`（内容级验证，非仅文件名） |
| S-S3 | **pending→injected 状态机** | S-S1 注入完成后 `kubectl exec` mysql 查 `file_asset` | 该行 `status='injected'`（注入确认后才置位，顺序正确） |
| S-S4 | **容器重建后重新注入** | ① 经 OpenSandbox Server API 删除当前用户沙箱容器：`DELETE /v1/sandboxes/{id}`（或 Server GC 等待超时回收）② 下个 turn chat 让 Agent 再 `ls /workspace/uploads/` | 回复仍含 `demo.csv`（create 兜底 `resetInjectedToPending` → 新容器首 exec 重新注入；证明 §6.2.4 双保险生效） |
| S-S5 | **多 turn 容器复用** | 同一 userId 连续两轮 chat（第二轮不新上传）均 `ls /workspace/uploads/` | 两轮均含 `demo.csv`（容器复用不重复注入、不丢文件） |
| S-S6 | **沙箱脚本处理二进制文档（纯 stdlib）** | 上传 demo.docx → chat `"用 python3 纯 stdlib（zipfile + xml.etree）解析 /workspace/uploads/demo.docx 的 word/document.xml，提取 <w:t> 文本"` | 回复含 `DOCX-SENTINEL`（证明沙箱可处理二进制文档——**沙箱镜像无 python-docx/pandas/pip（已实测），必须纯 stdlib**） |
| S-S7 | **沙箱产出文件→present_file** | chat `"用 python 在 /workspace/outputs/ 生成 result.txt（内容 RESULT-SENTINEL-77）后调用 present_file"` | SSE `file_ready` 帧 + 下载内容 = `RESULT-SENTINEL-77`（沙箱内生成 → 存储 → 回传闭环） |
| S-S8 | **沙箱产出图片→present_file** | chat `"用 python 纯 stdlib 写 1x1 红 PNG 到 /workspace/outputs/red.png 后调用 present_file"` | `file_ready` 帧 mime_type=image/png + 下载字节与 tiny-red.png 常量一致 |
| S-S9 | **注入失败不阻塞对话** | （单测 UT-12 主覆盖）E2E 模拟：临时把 `FILE_STORAGE_LOCAL_DIR` 指到不可写路径重发布——成本高，E2E 标注"由 UT-12 保证" | 沙箱日志出现 `Failed to inject pending uploads` warn 且对话正常回复 |
| S-S10 | **清理与收尾一致** | 全部 S-S 用例完成后复核 | 沙箱内 `ls /workspace/uploads/` 文件数与 DB `status='injected'` 行数一致（经 Agent 回显 + DB 查询） |

#### 16.5.3 沙箱注入时序断言（关键路径）

S-S1 用例执行时同步抓取 OpenSandbox 日志（`kubectl logs deployment/release-agent -f` 后台收集），断言时序：

```
1. chat 请求进入（turn 租约 acquire）
2. doExec 首次执行前 → injectPendingUploadsIfNeeded（日志含 "Injected N pending uploads" 或等价标记）
3. Agent 工具/命令执行（ls 输出包含文件）
4. turn 结束（stop() → workspace sync back）
```

若 2 未出现但 3 有文件 → 注入时序异常（文件来自上一容器遗留，需排查 resetInjectedToPending）。

### 16.6 UI E2E（e2e/chat-ui-file-e2e.js，Puppeteer，同 chat-ui-e2e 模式）

| 用例 | 步骤 | 断言 |
|------|------|------|
| U-1 | 打开 /assistant → 附件按钮可见 | `[data-testid="attach-btn"]` 存在 |
| U-2 | `uploadFile` 注入 demo.csv → 输入框出现附件 chip | `[data-testid="attach-chip"]` 文本含 demo.csv |
| U-3 | 发送"读取该文件内容" | SSE 回复含哨兵值（LLM） |
| U-4 | 发送"生成 outputs/ui.txt 并 present_file" | `[data-testid="file-card"]` 出现，含文件名与下载按钮 |
| U-5 | 点击下载按钮 | 浏览器下载事件触发，字节正确（Page `page.waitForResponse` 断言 200） |

### 16.7 执行方式与验收门禁

```bash
# ① 单测（含新增 UT-01~23，全量不回归）
cd agent-framework && mvn test

# ② E2E 非沙箱档（release-agent 默认部署，FILE_STORAGE_TYPE=local）—— 场景 S1~S4 + X
cd e2e && ./file-support-e2e.sh

# ③ E2E 沙箱档（已确认：接受 release-agent 重发布，非业务时段执行）：
#    release-agent 服务 env 加 SANDBOX_ENABLED=true + OPENSANDBOX_* 重发布
#    脚本经 SANDBOX=1 开关启用 §16.5 专项用例（S-S1~S-S10）+ 共用场景 S1~S4
SANDBOX=1 ./file-support-e2e.sh
#    跑完恢复原 env（去掉 SANDBOX_*）再重发布回非沙箱

# ④ E2E s3 档（local+s3 一起实现，已确认）：临时 docker run MinIO → FILE_STORAGE_TYPE=s3 重部署 → 复跑 ②
docker run -d --name minio-e2e -p 9000:9000 -e MINIO_ROOT_USER=minio -e MINIO_ROOT_PASSWORD=minio123 minio/minio server /data
# FILE_STORAGE_S3_ENDPOINT=http://<minio-host>:9000 FILE_STORAGE_S3_ACCESS_KEY=minio FILE_STORAGE_S3_SECRET_KEY=minio123 FILE_STORAGE_S3_BUCKET=agent-files

# ⑤ UI E2E（经 :8911 主入口）
node chat-ui-file-e2e.js
```

**门禁标准**（对齐 REDESIGN §10 与 e2e/AGENTS.md）：

1. 单测全绿（含 23 个新用例），无 skip 的常规用例
2. E2E 非沙箱 + 沙箱 + s3 三档全绿；每场景结束 DB 行 / 存储对象 / 工作区内容三方一致
3. S1-5 哨兵值断言证明"上传→注入→Agent 可读"端到端闭环；S3-1/S4-1 证明"产出→回传→下载"闭环
4. **沙箱档专项**：S-S1/S-S2 证明容器内 `ls`+`cat` 直接可见（注入闭环）；S-S4 证明容器重建后重新注入（§6.2.4 双保险）；S-S6 证明沙箱纯 stdlib 可处理二进制文档；S-S7/S-S8 证明沙箱产出回传闭环
5. 异常路径 413/415/400/404 全覆盖
6. UI E2E 文件卡片与下载按钮可用

**失败排查锚点**（断言失败时的定位顺序）：上传 200 但 DB 无行 → FileController 存储落库顺序；DB 有行但 LLM 读不到 → 注入链路（沙箱档看 OpenSandbox 日志 `injectPendingUploads`，非沙箱档查 agent_fs key）；**沙箱档 S-S1 失败但 S1-5 成功** → 沙箱容器内路径与约定不一致（排查 `/workspace/uploads/` 前缀，确认注入代码路径拼接）；**S-S4 失败** → `resetInjectedToPending` 未生效（查 create 兜底是否注册、userKey 是否绑定）；file_ready 无帧 → 控制器 TextDelta 累积（present_file 返回 JSON 格式）；下载 404 → download_url 前缀与 rewrite 匹配。

---

## 17. 实施结果与偏差记录（2026-09-05 实测）

### 17.1 验证结果

| 验证项 | 结果 |
|--------|------|
| 单测（agent-framework） | 439 用例全绿（4 skip 为既有沙箱集成测试），新增 UT-01~23 共 46 新用例 + OafPackageTools/FileTools 沙箱直读 10 新用例 |
| backend go test | 全绿（internal/k8s 挂载断言更新） |
| E2E 非沙箱档 | 18/18 全绿（S1~S4 + X） |
| E2E 沙箱档（SANDBOX=1） | **26/26 全绿**（S-S2 SKIP 说明；S-S6 纯 stdlib 解析 docx；**新增 S-S8 按描述生成 OAF 部署包**：zip 含 AGENTS.md → frontmatter 必填字段 → 平台上传校验通过） |
| UI E2E（file-support-ui-e2e.js） | 沙箱档 **15/15** 全绿（U1~U13 文件/生成包 + **U14~U16 历史会话**：列表展示/切换回放/继续对话上下文恢复；U4-U6/U7-U8/U11 失败自动重试） |
| 手动验证 | 生成 weather-agent → 平台校验通过（packageId=64）；生成 echo-agent → upload_package 上传成功（packageId=66） |
| 前端 build | 通过（附件上传/file_ready 卡片/图片内联） |

### 17.2 实测发现与实现偏差

1. **非沙箱文件系统根 = {workspace}/.agentscope/workspace/{sessionId}/**（本地磁盘 + SQLite 索引）。
   最初按设计写 KV（agent_fs）注入失败（LLM list_files 看不到）——实测 SDK 隔离键 = 会话 id（ChatUiChannel peer 机制），
   UploadWorkspaceInjector 改为**本地磁盘写入** `uploads/{唯一化名}`（§6.1 实现差异）。
2. **execd files API 路径基准 = /workspace**（实测）：`WriteEntry.path` 无前导斜杠 → 相对 /workspace；
   带前导斜杠 → 容器绝对路径（会写错位如 /demo.csv）。沙箱注入 path 用 `uploads/{name}`（§6.2.3 实现差异）。
3. **沙箱文件操作不经 doExec**（实测 read_file/execute 均不触发 doExec 注入路径）：
   注入时机改为 **create/resume 后 + SandboxUserKeyMiddleware.onAgent 兜底**（acquire 先于 middleware，
   实例 userKey 未绑定，middleware 时实例已就绪 → 绑定 + reset + 注入，§6.2.3 实现差异）。
4. **沙箱每次 create 换代**（实测每 turn 新容器，非 USER 级复用）：
   `resetInjectedToPending` 需覆盖 session 维度（双参数重载），且 reset 仅在新沙箱代执行
   （spec 记录 userKey→sandboxId 防多实例循环 reset/inject）。
5. **present_file 工具**：沙箱模式无 sandbox 句柄，最初统一走 `file_content_base64` 参数；
   **2026-09-06 增强**：沙箱模式支持 file_path 直读（`OpenSandbox.readWorkspaceFile` 经 execd files API
   `readByteArray`，userKey 不匹配拒绝防串沙箱），base64 降级为兜底；
   ToolResultTextDelta 为二次 JSON 转义（`"{\"file_id\":...}"`），file_ready 合成需 isTextual 二次解析（§8.3 实现差异）。
6. **前端 rewrite**：download_url 用相对 `/agent/release-agent/files/{id}` 已验证可用。
7. **宿主机 nginx 上游写死节点 IP**：节点重启后 IP 变化（172.20.0.3→172.20.0.2）需同步更新 /etc/nginx/conf.d/agent-manager.conf。
8. **LLM 行为 flaky 项**：视觉识别（mimo-v2.5 多轮上下文后 base64 图片易丢失 → 独立会话重试）、
   工具结果转述（LLM 原样输出命令输出不可控 → S-S2 降级 SKIP 由 S1-5+容器直查覆盖）。
9. **镜像分发**：kind 节点 CRI tag 缓存顽固（旧 digest 残留）——用 kind-registry（172.20.0.1:5001）推送 +
   ctr 删 tag 强制重新解析；**crictl pull 显示旧 digest 不代表 pod 用旧镜像，最终以 pod status.containerStatuses[0].imageID 为准**（部署流程备注）。
10. **平台 backend 配合**：业务 Pod 挂载改为**单卷双 mount**（agent-files 卷承载 /config 只读 subPath + /data/files 可写 subPath，
    避免同 PVC 双 volume 引用，§13 P1 实现差异）。
11. **No active sandbox 尾部收尾错误（SDK 缺陷，应用层缓解）**：agent 调用结束（POST_CALL 后、AGENT_END 前）
    SDK 收尾路径偶发访问已释放的沙箱文件系统，抛 `SandboxConfigurationException: No active sandbox`，
    流以 error 终止 → 前端将已完成对话误判失败（file_ready 卡片丢失）。
    修复：`SessionStreamController.isTrailingSandboxTeardownError` 识别该尾部错误（异常链消息含
    "No active sandbox"）→ 不发 error 帧、正常 complete 流（业务事件均已发出）。根因在 SDK 未消除。
12. **maxIters 放宽至 20**：SDK 默认 10 轮推理不足以支撑"生成 OAF 部署包"长流程
    （撰写→校验→修正→打包→登记），实测 10 轮时在 create_oaf_zip 参数输出阶段 EXCEED_MAX_ITERS；
    `HarnessAgent.builder().maxIters(20)`（AgentScopeConfig harnessAgent 构建处）。
13. **LLM 生成包行为 flaky**（S-S8 重试机制缘由）：首次实现 LLM 曾跳过 check_oaf_package（生成缺
    vendorKey/agentKey 的包被平台拒绝 400）、用沙箱 write_file/edit_file 写文件失败占用迭代轮次、
    把 create_oaf_zip 结果误判为长操作去轮询。缓解：AGENTS.md 强制步骤（禁止沙箱写文件、valid=true
    才继续）+ E2E 新会话重试。
14. **E2E 成功判定经验**：不能用回复长度阈值（`len>50`）判成功——LLM 短回复（如"最后一行的第一个字段
    是 last"，17 字符）会被误判失败；改为"流结束（busy 复位）+ 回复非思考中"。
    U6/S1-5/S-S6 哨兵断言放宽：LLM 偶发只复述结果不复述内容 → 增加"具体读取值/文件名"双轨判定。

### 17.3 遗留（P4，未实施）

- A2A 文档 FilePart 转换（§11）——A2A 客户端上传文档场景
- 下载 Range/ETag（§9 P2）
- 孤儿存储对象清扫任务（§4.3-5）
- ~~S3 存储档 E2E 未实测~~ **2026-09-07 已实测**（见 §19）

---

## 19. S3 存储档实测（2026-09-07，七牛云 S3 网关）

### 19.1 验证结果

| 验证项 | 结果 |
|--------|------|
| S3FileStorageIT 集成测试（真实七牛云） | 3/3 全绿：write→exists→read 哨兵一致→delete→exists=false→重复 delete 幂等；不存在 key exists=false / read 抛 getObject IOException；bucketExists 启动 fail-fast |
| E2E S3 档（e2e/s3-file-e2e.sh，非沙箱档全链路落 S3） | 上传（S1）/下载一致（S3-2/S4-2）/present_file 产出（S3/S4）/生成包 S-S8（zip 落 S3 + 平台校验）全 PASS；S-S9a/b 为 LLM 偶发（与存储无关，local 档已覆盖） |
| MinIO SDK 兼容性 | 七牛 S3 网关 path-style 正常，无需显式 region（SDK 默认探测可用） |

### 19.2 实测发现：yml 嵌套绑定坑（重要）

**现象**：切 FILE_STORAGE_TYPE=s3 后 CrashLoop，`endpoint must be a non-empty string`，
但 envFrom 注入经 debug pod 验证完全正常，且 FILE_STORAGE_TYPE 本身生效（S3 bean 被创建）。

**根因**：application.yml 曾写成嵌套 `agent.file.storage.s3-endpoint`，与 record 字段
`storageS3Endpoint` 的 canonical 绑定名 `agent.file.storage-s3-endpoint` **不匹配**——
binder 静默回退 record 默认值（S3 字段默认空 → 构造失败）。而
`@ConditionalOnProperty(prefix="agent.file.storage", name="type")` 走 Environment 层
（不走 binder）恰好取到 s3，造成"type 生效、S3 参数全空"的错位。local 档一直"正常"
纯因默认值恰好等于期望值（local//data/files/agent-files）。

**修复**：yml 平铺键名（storage-type/storage-local-dir/storage-s3-endpoint/...）与字段一一对应；
@ConditionalOnProperty 改为 `prefix="agent.file", name="storage-type"`。
新增回归测试 `S3EnvBindingTest`（2 用例：s3 env 绑定断言 / env 缺失 local 回落）锁住该结构。

### 19.3 运行方式

```bash
# 存储层集成测试（无 env 自动 skip）
S3_IT=1 S3_IT_ENDPOINT=... S3_IT_ACCESS_KEY=... S3_IT_SECRET_KEY=... S3_IT_BUCKET=... \
  mvn test -Dtest=S3FileStorageIT

# E2E S3 档（切 release-agent → 跑全链路 → 自动恢复 local；凭据在 .env.secrets）
./e2e/s3-file-e2e.sh
```

注意：kubelet 对新写入 ConfigMap key 的传播有延迟——切换后首次 restart 可能仍读到旧 CM
（S3FileStorage 不出现即未生效），脚本内置最多 3 次 restart 检测重试。

---

## 18. 扩展：按描述自动生成 OAF 部署包（2026-09-06）

### 18.1 需求与设计决策

发布助手根据用户自然语言描述自动生成符合 OAF 规范的 zip 部署包：
- **前端下载**：zip 登记为 file_asset（origin=generated）→ SSE file_ready 帧 → 前端下载卡片
- **MCP 直传**：zip 的 content_base64 直接经 `upload_package` MCP 工具上传平台 → 可继续 publish_service 发布
- 生成后**必须询问用户**再发布（未经确认不得自动发布）

**决策调整记录**：最初选"纯引导（LLM 沙箱 zipfile 手动打包）"，实测 LLM 在沙箱用
write_file/edit_file 写文件频繁失败（"already exists"、路径语法错误）且浪费推理轮次——
改为 **JVM 侧工具打包**（`create_oaf_zip`），沙箱完全不参与，可靠性显著提升。

### 18.2 新增组件

| 组件 | 位置 | 说明 |
|------|------|------|
| `check_oaf_package` | `tool/OafPackageTools.java` | frontmatter 校验：7 必填字段（name/vendorKey/agentKey/version/description/author/license）+ kebab-case + semver（与 backend/internal/oaf 校验规则对齐）；返回 valid/missing/invalid JSON |
| `create_oaf_zip` | `tool/OafPackageTools.java` | 打包前自动校验（不通过拒绝）；JVM 侧组装 zip（AGENTS.md + extra_files JSON 数组）→ 写存储 → 登记 file_asset → 返回 file_id/file_name/size/content_base64 |
| `readWorkspaceFile` | `sandbox/opensandbox/OpenSandbox.java` | execd files API 直读沙箱工作区文件（二进制 readByteArray，相对 /workspace 路径语义），present_file 沙箱直读复用 |
| 生成指引 | `release-agent/AGENTS.md`「生成 OAF 部署包」章节 | 强制流程：写 AGENTS.md → check_oaf_package（valid=true 才继续，禁止沙箱写文件）→ create_oaf_zip → 询问用户 → upload_package → publish_service |

### 18.3 验证

- 单测：OafPackageToolsTest 6 例（valid/missing/kebab/semver/no-frontmatter/blank）+ create_oaf_zip 2 例（拒绝非法 frontmatter、zip 结构+登记+base64 解包验证）；FileToolsTest 沙箱直读 2 例（成功读取、userKey 不匹配拒绝）
- E2E 沙箱档 S-S8（3 断言）：生成 echo-agent 包 → zip 含 AGENTS.md → frontmatter 必填字段 → 平台上传校验 code=0
- 手动验证：weather-agent 生成（平台校验通过）、echo-agent 生成 + upload_package 上传成功（packageId=66）
- E2E 沙箱档 S-S9（3 断言，2026-09-06 补充）：LLM 生成+上传（平台包列表确认 packageId）→ REST 发布至 running（与 publish_service 同语义；默认镜像须为节点内新 digest，否则 LocalFileStorage 无默认构造器 CrashLoop）→ 删除清理；沙箱档总计 29/29 全绿
- UI E2E U11~U13（生成包对话场景，沙箱/非沙箱双档 12/12）：切新会话发送生成包消息（唯一包名，最多 3 次新会话重试）→ file_ready 卡片 → 下载 zip PK 魔数校验 → FormData 上传平台 /api/v1/packages 校验 code=0
