# OAF 打包工具自 agent-framework 迁出方案（MCP 化 + 直建包 + URL 卡交付）

> **状态: 📝 设计已评审（2026-09-24），随本 PR 一期+二期一次落地**
> 目标：agent-framework 回归纯通用 Agent 框架（运行时配置解析与通用文件交付除外），
> OAF 打包领域语义（frontmatter 校验、组包、平台登记）收归平台 backend，经 platform-publisher MCP 提供；
> 交付链路零 base64 经 LLM 搬运。

---

## 一、背景与边界

### 1.1 问题

`agent-framework/tool/OafPackageTools.java`（check_oaf_package / create_oaf_zip）是「替用户产出发布物」的
平台业务功能，不是框架能力，寄居在通用运行时里带来三个实际代价：

1. **校验规则双实现漂移**：Java 手工行解析 + 正则 vs backend `internal/oaf.ParseOAF/Validate`（权威、真 YAML）。
   实测已漂移：kebab 正则（Java 允许数字开头 / Go 须字母开头但允许尾连字符）、semver（Java 允许 `+build`）、
   `name` 字段（Java 校验 kebab / 平台 Validate 不校验）。
2. **打包产物要经 LLM 二进制搬运**：create_oaf_zip 返回 content_base64，LLM 再传给 upload_package ——
   token 成本与出错面随包体积线性增长（ChatStreamController 64KB 结果桶 + 正则兜底即为该问题的补丁）。
3. **框架纯净性**：任何 OAF 打包需求变更都要重建 agent-framework 镜像并滚动全部业务 Agent。

### 1.2 保留在框架内的 OAF 代码（明确不迁）

`OafConfig` / `OafConfigLoader` / `WorkspaceInitializer` / `SkillCatalogService` / `McpToolRegistrar` 等
是 agent-framework 作为 **OAF v0.8.0 运行时读取自身部署配置**（AGENTS.md frontmatter）的本职——
等价于 Spring Boot 读 application.yml，与「替用户打包发布」无关。若未来要把「OAF 命名的运行时配置层」
也泛化（OafConfig→AgentConfig 改名级），属独立课题。

## 二、目标架构

```
发布助手对话流（迁移后）：
  撰写 AGENTS.md（LLM 参数）
    → check_oaf_package      (MCP, backend)     聚合校验 {valid, missing, invalid, present}
    → create_oaf_zip         (MCP, backend)     校验 + 组 zip + PackageService.Upload 直建包
                                                返回 {packageId, slug, version, warnings,
                                                       file_name, size, download_url}
    → present_url            (框架自定义工具)     登记外部交付物（file_asset: storage_type=external,
                                                storage_key=download_url）→ file_ready 卡片
    → publish_service(packageId) (MCP, backend)  人工确认卡 → 发布 → 轮询

下载（实时与历史回放同一 URL）：
  前端卡片 href = AGENT_BASE + /files/{file_id}
    → agent-framework GET /files/{id}
        storage_type=local/s3 → FileStorage 流式转发（原链路）
        storage_type=external → 前缀白名单校验 → 服务端代理拉取 storage_key 指向的 URL
```

- **upload_package 与 content_base64 从生成流程中消失**；upload_package 仍保留（用户上传已有 zip 的场景）。
- 前端**零改动**：`MessageItem.tsx` 已支持非 `/files` 前缀 download_url 直接使用；回放侧恒走
  `AGENT_BASE + /files/{file_id}`（external 行经代理回源，两路径合流）。

## 三、backend 侧

### 3.1 MCP 工具（internal/mcpsrv/oaf_package.go）

| 工具 | 语义 |
|---|---|
| `check_oaf_package(agents_md)` | `oaf.CheckAgentsMD` 聚合校验：必填 7 字段 + name/vendorKey/agentKey kebab + version semver，`present` 键列表独立 YAML 解析 |
| `create_oaf_zip(package_name, agents_md, extra_files[])` | CheckAgentsMD 前置 → 组 zip（AGENTS.md 固定首项、条目 0644、路径清洗拒 `..`/绝对路径/重复）→ 20MB 上限 → `PackageService.Upload` 入库落盘 → 返回 packageId/download_url |

- `download_url = PackageDownloadBase + /api/v1/packages/{id}/download`；
  基地址来自 `PACKAGE_DOWNLOAD_BASE`（默认 `http://platform-backend.agent-platform.svc.cluster.local:8080`，
  集群内 svc DNS，消费方是业务 Agent Pod 的代理下载）。
- **校验语义平移而非合并**：`oaf_check.go` 的正则/检查项按原 Java 工具平移（含 name kebab、
  semver build 后缀），`Validate()`（平台上传门禁）一字不动。两套正则的已知偏差（§1.1.1）**有意保留**，
  统一属后续独立课题。
- extra_files 从 Java 的 JSON 字符串参数改为结构化数组（jsonschema 自动派生）。

### 3.2 权限

`release-agent/mcp-configs/platform/config.yaml` 增 `check_oaf_package: allow`、`create_oaf_zip: allow`
（打包登记与 upload_package 同类：无破坏性、平台可信内网）。

## 四、agent-framework 侧

### 4.1 删除

`tool/OafPackageTools.java`、`OafPackageToolsTest.java`、`AgentScopeConfig.oafPackageTools` Bean 与
`customTools` 聚合项、`ChatStreamController` 三处 `create_oaf_zip` 硬编码（交付工具名单回到
`{present_file, present_url}`）。

### 4.2 新增：present_url（tool/FileTools.java）

```
present_url(file_name, url, mime_type?, size?)
  → 前缀白名单（FILE_EXTERNAL_URL_PREFIXES，逗号分隔；空=禁用）+ http(s) 校验
  → file_asset 插入 {storage_type: "external", storage_key: url, origin: "generated", status: "injected"}
  → 返回 {file_id, file_name, mime_type, size}（与 present_file 同构，走同一条 file_ready 合成）
```

- 幂等复用：`uk_storage(storage_type, storage_key)` 全局唯一，同 URL 重复交付时
  `findByStorage` 复用既有 file_id（file_ready 合成回写最新 reply/session，卡片关联跟随最近交付；
  副作用：更早会话的回放卡片关联被移走——记录为已知语义）。
- `parseFileResult` 正则兜底保留（通用大结果截尾救回），其原始动机（content_base64 撑爆结果桶）已消失。

### 4.3 新增：/files/{id} external 代理分支（FileController）

- `storage_type=external` 行：URL 前缀白名单校验（与 present_url 同一配置，SSRF 收敛）→
  `java.net.http.HttpClient`（5s 连接 / 60s 请求超时，跟随重定向）拉取并流式转发；
  上游非 2xx → 502，白名单不匹配 → 403。
- Content-Type/Length 取上游响应头（缺失回落 asset 元数据）；Disposition 规则与本地下载一致。

### 4.4 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `FILE_EXTERNAL_URL_PREFIXES` | 空（禁用） | present_url 与代理下载共用的 URL 前缀白名单（逗号分隔） |

**发布助手部署要求**：服务 env 需配置
`FILE_EXTERNAL_URL_PREFIXES=http://platform-backend.agent-platform.svc.cluster.local:8080`
（经平台 PATCH /services/:id/env 或发布向导设置；与 `AGENT_REDIS_URL` 同类「集群内必配」项）。

## 五、e2e

- bench mock MCP（`bench/mock-mcp/server.js`）增 `check_oaf_package` / `create_oaf_zip` 两工具 +
  `GET /packages/7/download` 固定 zip 端点（无压缩 stored zip，确定性字节，含 AGENTS.md 与附加文件）。
- `oaf-package.json` 夹具改为三段调用链：MCP create_oaf_zip → present_url → 收尾文本；
  `download_url` 以 `{{BENCH_MCP_BASE}}` 占位符书写，`llm-server.mjs` 按实际 bench 端口替换。
- agent 启动注入 `FILE_EXTERNAL_URL_PREFIXES=http://127.0.0.1:{BENCH_MCP_PORT}`（start-agent.sh）。
- F12 断言升级：toolNames 同时含 `create_oaf_zip`（MCP 裸名）与 `present_url`；
  下载经 /files/{id} 代理断言 PK 魔数；历史回放会话绑定不变式保留。U13 同链路锁 UI 渲染与回放。
- 根 e2e（file-support-e2e.sh S8/S9、file-support-ui-e2e.js）提示词改为新流程；S9 的
  upload_package 步骤删除（create_oaf_zip 直建包，平台包列表按 name 客观验证）。

## 六、发布时序（P0：同名工具冲突规避）

custom @Tool 与 MCP 裸名同名在同一 Toolkit 内双注册行为未定义，且 MCP 工具随 tools/list 自动注册
（permissions 只管执行不管注册）。因此**必须按序发布，一个维护窗口内完成**：

1. **agent-framework 新镜像先行**（删 OafPackageTools）——窗口期助手暂无打包能力（工具不存在，
   LLM 不会调用），对话可用、无损坏；
2. **backend 新镜像紧随**（platform-backend rollout）——MCP 打包能力恢复；
3. **release-agent 走平台 create_package_version + republish** 更新包（新提示词 + permissions +
   version 1.1.0），并在服务 env 补 `FILE_EXTERNAL_URL_PREFIXES`；
4. 跑根 e2e S8/S9 真实集群验证。

禁止 backend 先发（旧框架 + 新 MCP 同名双注册，行为不可控）。

## 七、已知偏差与后续课题

1. 校验双正则偏差（§3.1）有意保留；统一需评估平台门禁语义变化，独立课题。
2. present_url 同 URL 重复交付的卡片关联「最近一次交付」语义（§4.2）。
3. 每次 create_oaf_zip 即产生 OafPackage 记录（含未发布的草稿包），与 upload_package 行为一致，
   无引用时可平台删除；如需「草稿态」包管理属平台产品化课题。
4. 20MB 级 MCP 大结果（如未来工具返回大内容）未做专项压测——当前 create_oaf_zip 返回体不含字节，
   不受影响。
