# E2E Testing — OAF 发布平台

## 场景矩阵

### 平台控制面（`console-split/`，PR 门禁套件）

| 脚本 | 覆盖 |
|------|------|
| `console-split/lib.sh` | 公共断言/登录/空间种子/`wait_status`/legacy `AUTH_TOKEN` 通道/`resolve_llm_env`/`runtime_is_real` |
| `m0-auth.sh` | 登录三分支、cookie 属性、me 双通道、路由分层、introspect、logout、权限撤销即时生效（#148） |
| `m1-governance.sh` | 用户/工作空间 CRUD、成员矩阵、bind 三态、删除保护、审计、owner 转移（#157）、长度上限（#149） |
| `m2-console-foundation.sh` | `resolveWorkspace` 矩阵、summary/agents 视图、templates/packages/images、proxy 身份注入 |
| `m3-agent-create.sh` | Agent 创建校验矩阵、preview-name、创建→running→A2A、包契约、env 分级、导入模式（#161）、模板导入、纳管（adopt）、子 Agent/远程凭证、多副本/非公开入口、编辑 copy-on-write、member 403 |
| `m4-agent-detail.sh` | 详情聚合（pods/events/envSecretKeys）、列表过滤、edit-form 回显、生命周期状态机、版本链/升级、非成员 404、member 只读矩阵、删除副作用（包保留/refCount=0/K8s 清理） |
| `m5-model-connection.sh` | CRUD/掩码/唯一性/校验、test 成功与失败、PATCH→pending→重新下发 synced、跨空间同名、usage、`/models` 回读、会话级模型切换（真实运行时）、默认连接删除保护 |
| `m6-mcp-connector.sh` | CRUD/长度/缺字段校验、平台 `/mcp` verify、坏端点 failed、toolScope 快照、PATCH timeout/permissions、sse 创建、member 403、bearer token 不落包/不回显→Secret、被引用删除 409 |
| `m7-integration.sh` | 集成候选三态、登记/重复 409/跨空间 400、askPolicy/enabled patch、引用保护（需 `AGENT_PROTOCOL_ENABLED=true` 服务） |
| `m8-workspace-assets.sh` | API Key 签发/作用域/吊销、邀请成员矩阵、模板开关、包技能 warnings/issues（#163/#164） |
| `m9-skill-center.sh` | Skill 中心：Markdown 创建/校验、版本状态机（draft→published→offline→reline）、zip 上传/下载、市场源/安装、Agent 引用 `source=skill`（同名去重 #173）、draft/停用引用 400、删除保护 409、skill-usages、member 403 |
| `m10-sandbox.sh` | 沙箱配置：enabled true/false/未声明、资源注入与空/负值归一、`SANDBOX_ENABLED` 不落 env、总览三层来源（env > package > default）、edit-form 回显、keyword/member/404 |
| `run-all.sh` | 顺序执行；子脚本 `exit 2` 记为 SKIP，FAIL 汇总后非零退出 |

> `console-split-e2e.sh` 已删除：其 22 条断言是 M0/M1/M2/M8 的子集。
> 旧的 `ui-e2e.js`（匿名 legacy 管理面）已由 `console-ui-e2e.js` 取代。

### 浏览器 UI（`console-ui-e2e.js`）

登录 → 工作空间 → Agent 列表/创建（import 模式）/详情/编辑 → 模型连接新建 → MCP 连接器新建/权限表 →
技能中心（我的/广场/使用情况）→ 沙箱配置总览。数据准备走 API（含沙箱 Agent + 技能引用），
浏览器只驱动 UI；`E2E_AGENT_RUNTIME=mock` 时跳过会话/调试类断言。

### 平台发布链路 / 业务运行时

| 脚本 | 内容 |
|------|------|
| `platform-e2e.sh` | legacy REST 主链路：上传→发布→A2A 注册→env 更新（全量覆盖）→republish→上下线→异常路径→零残留；镜像经 `GET /images` 动态选取 |
| `package-edit-e2e.sh` | 包在线预览/编辑/新版本→republish 换版→引用计数→零残留 |
| `mcpclient/` (go run .) | MCP client 经 streamableHttp 走完整发布链路 |
| `platform-config-secret-e2e.sh` | 平台默认配置 schema/掩码/defaults、env 隔离、默认填入发布、改配置不影响存量服务 |
| `agent-e2e.sh` | release-agent 自然语言驱动发布+删除（HITL confirm-stream；A2A 仅查询探针） |
| `file-support-e2e.sh` | 文件上传下载/视觉/`present_file`/生成 OAF 包；`SANDBOX=1` 加沙箱专项 |
| `file-support-ui-e2e.js` | `/assistant` 对话 UI：附件/读文件/present_file/下载/生成包/历史回放 |
| `skills-dynamic-e2e.sh` | OAF `skills/` 动态加载与 L4 覆盖；`SANDBOX=1` 加沙箱投影/脚本执行 |
| `user-skill-admin-e2e.sh` | 用户技能 L4 管理面 + 非沙箱 A2A 生效性 + 沙箱回写 E8/仲裁 E9 |
| `s3-file-e2e.sh` | S3 存储档：切 release-agent 为 S3 → 跑 file-support → 恢复 local |
| `debug-console-e2e.js` / `debug-markdown-e2e.js` | Debug Console 子路径渲染 / markdown 渲染 |
| `chat-ui-e2e.js` | `/assistant` 真实 LLM 流式回复 |
| `S3FileStorageIT`（Java） | S3 集成测试；`S3_IT=1 ... mvn test -Dtest=S3FileStorageIT`，无 env 自动 skip |

## 运行前置

- 平台集群：platform-backend/frontend running；`console-split` 需要 `JWT_SECRET` + `ADMIN_INIT_*`
- 业务镜像：真实 agent-framework（推荐）或 CI 的 `mock-agent`（仅 A2A 面）
- LLM：`console-split` 的 Agent 创建/模型连接链路需要 verified 连接；CI 用集群内 mock LLM，
  本地可显式传 `LLM_API_KEY/LLM_BASE_URL/LLM_MODEL`，或从 release-agent Secret 回落
- `fixtures/demo-agent-v{1,2}.zip`、`bad-no-agentsmd.zip`
- 涉及 L4/沙箱的场景需新 agent-framework 镜像已导入节点并在服务上生效

```bash
# 平台控制面（API）
BASE=http://localhost:30080/api/v1 FRONTEND_BASE=http://localhost:30881 \
  bash e2e/console-split/run-all.sh m0 m1 m2 m3 m4 m5 m6 m7 m8 m9 m10

# 浏览器 UI
cd e2e && npm ci && BASE=http://localhost:30080/api/v1 FRONTEND=http://localhost:30881 \
  E2E_LLM_BASE_URL=http://<mock-llm>/v1 E2E_LLM_API_KEY=e2e-dummy E2E_LLM_MODEL=e2e-mock \
  node console-ui-e2e.js

# 发布主链路 / 包编辑
./platform-e2e.sh
./package-edit-e2e.sh
```

## CI（`.github/workflows/platform-e2e-ci.yml`）

- 触发：指向 master 的 PR + master push；路径过滤 `backend/**`、`frontend/**`、`manifests/**`、`e2e/**`
- 运行环境：`ubuntu-latest` + `kind` 单节点（NodePort 30080/30880/30881 映射到 localhost）+
  `manifests/ci` overlay（本地构建镜像、mock LLM、`oaf-platform-backend` 别名 Service）
- 业务镜像解析顺序：`E2E_AGENT_IMAGE` → Docker Hub `gaoyue1989/agent-framework` 最新
  `agentscope-*` tag（拉取/导入各 300s 超时）→ 失败回落 `mock-agent:ci`（`E2E_AGENT_RUNTIME=mock`，
  跳过会话类断言）
- 执行：`platform-e2e.sh` → `console-split/run-all.sh m0..m10` → `console-ui-e2e.js`；
  失败上传集群状态与 UI 截图
- **必需检查**（2026-10-22 起）：按目录过滤，不相关变更 Skipped 视为通过

## 断言原则

每场景结束断言 DB 记录 / K8s 资源 / PVC 目录三方一致；E2E 会话用运行级唯一 userId（防 HITL 暂停态复放）；
LLM 相关超时窗口 ≥300s；子脚本缺前置时 `exit 2`（SKIP），不得以 exit 0 伪装 PASS。
