# OAF 包在线预览 / 在线编辑生成新版本 / 发布更新 — 设计与实施记录

**日期**：2026-09-22　**状态**：已实施（同日合并，E2E 34 断言全绿）

## 1. 背景与目标

平台此前对 OAF 配置包只有「上传 → 选择发布」，包内容不可见也不可改；改包必须本地重打 zip 再上传，再对服务 republish。本次在前后端补齐三条能力：

1. **在线预览**：文件树 + 单文件内容查看（md 渲染/代码高亮）+ 单文件与整包下载；
2. **在线编辑生成新包**：网页编辑器直接改包内文件，保存生成**新版本包**；
3. **发布更新**：新版本包一键 republish 到引用旧包的服务，引用计数自动迁移。

## 2. 核心设计决策

### 2.1 包不可变，编辑一律 copy-on-write

包目录 `packages/{id}` 通过 subPath **只读**挂载进运行中的业务 Pod（`/config`）。原地修改会让运行中服务的配置视图直接变化，破坏「包被引用期间内容稳定」的语义。因此：

- 编辑结果**永不写原目录**：`CreateVersion` 基于某基础包 + 变更集生成新的 `OafPackage` 记录 + 新 PVC 目录 `packages/{newID}`；
- `SourcePackageID` 字段记录派生链路（0 = 上传原始包）。

### 2.2 版本生成复用上传管线

`CreateVersion` 不另写校验落盘逻辑：在内存把「基础包文件 + upserts − deletes」合成 zip 字节流，再走与上传完全相同的 `InspectZip → ParseOAF/Validate → 事务入库 → ExtractZipTo` 管线。20MB/2000 条目/100MB 上限、zip-slip、权限归一、warnings 计算自动复用。

### 2.3 编辑器选型

CodeMirror 6（`@uiw/react-codemirror`）：支持 React 19、体积小、YAML/JSON/Markdown 语言包齐全。只读渲染复用既有 react-syntax-highlighter 与 assistant Markdown 组件。

## 3. API 契约（backend/internal/handler/router.go）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/packages/:id/files?path=` | 单文件预览：文本返回 `{path,size,binary:false,content}`；二进制或超 512KB 返回 `binary:true`（内容走下载） |
| GET | `/api/v1/packages/:id/files/download?path=` | 单文件下载（attachment） |
| GET | `/api/v1/packages/:id/download` | 整包 zip 在线打包下载 |
| POST | `/api/v1/packages/:id/versions` | 生成新版本，body `{upserts:[{path,content,encoding}],deletes:[],expectedBaseChecksum?}` |
| GET | `/api/v1/packages?slug=` | slug 精确过滤（版本历史） |
| GET | `/api/v1/services?packageId=` | 按包过滤服务（引用列表） |

MCP 对齐新增工具：`get_package_file`、`create_package_version`（配合既有 `republish_service` 构成对话式闭环）。

### 3.1 CreateVersion 校验链

1. 乐观锁：`expectedBaseChecksum` 与基础包不符 → **409**；
2. 路径合法性：逐条走 `store.CleanSubPath`（`..`/`\`/绝对/超长 → `ErrZipSlip` → 400）；
3. 单文件 256KB 上限；encoding 支持 utf8/base64；
4. 根级 `AGENTS.md` 不可删除（前后端双重提示）；
5. upsert 与 delete 同路径冲突时 upsert 优先；
6. 与基础包逐字节对比，无有效变更 → 400（`no effective changes`）;
7. 新 `AGENTS.md` 重新 `ParseOAF + Validate`（name/slug/version 取自新 frontmatter）；
8. 同 slug+version 已有其他包 → 追加 warning 不阻断；warnings（mcpServer configDir 缺失等）按上传规则重算。

## 4. 前端结构

```
frontend/src/app/packages/
├── page.tsx            # 包列表：预览/编辑/发布/下载/删除（refCount>0 禁删）
├── [id]/page.tsx       # 详情：文件树+查看器（md→Markdown 组件；其余→高亮；二进制→下载）
│                       #   版本历史（同 slug）+ 引用服务「重新发布到此版本」
└── [id]/edit/page.tsx  # 编辑：CodeMirror + 变更集跟踪（新/改/删标记）+ 确认弹窗 → 生成新版本 → 跳新包
```

其余接入点：服务列表页头部「配置包」入口；服务详情基本信息区显示 `slug@version` 链接 + 同 slug「升级到…」下拉（`republish(packageId)`）；发布页支持 `?packageId=` 深链并提示「查看包详情」；`lib/file-tree.tsx` 为详情/编辑共用文件树组件。

## 5. 安全与边界

- 全部新端点位于 `/api/v1` 组内，套既有 Auth/CORS 中间件；
- 预览/下载只读；单文件 512KB 预览上限，整包打包产物可再过 `InspectZip` 复检；
- `fs.ErrNotExist` → 404，`ErrZipSlip`/`ErrNoEffectiveChanges`/`ErrAgentsMDUndeletable` → 400，`ErrChecksumMismatch` → 409；
- 运行中服务不受编辑影响（copy-on-write），refCount 原子迁移机制不变。

## 6. 实施与验证记录（2026-09-22）

- 后端四层单测：store（IsTextContent/CleanSubPath/ZipPackage/WriteZipTo）、service（CreateVersion 主链路/无变更/乐观锁/非法输入/重复版本 warning/FileContent/Zip/slug 过滤）、handler（预览/下载/版本生成/状态码映射）、mcpsrv（两新工具）；`go vet` + `go test ./...` 全绿；
- 前端：eslint 0 error、tsc 0、next build 成功、lib 单测 25 pass；
- E2E：新增 `e2e/package-edit-e2e.sh`（34 断言）：预览/下载/编辑生成版本（含派生溯源、基础包未被修改、增删改生效）/异常路径（无变更 400、删 AGENTS.md 400、乐观锁 409、非法 frontmatter 400）/发布→republish 换版（subPath 切换、引用计数 0→1、注册版本 1.0.0→1.1.0）/按包过滤/零残留清理，全绿；存量 `platform-e2e.sh` 回归无破坏；
- E2E 初版暴露并修复两处后端问题：文件不存在错误未映射 404（`fs.ErrNotExist` 补映射）、zip 头部 Modified 时间取 `d.Info()` 双返回值编译错误修复；镜像更新后需 `rollout restart` 方能生效（kind load 缓存）。
