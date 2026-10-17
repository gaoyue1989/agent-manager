# 控制台拆分 · 实装截图画廊（2026-10-17）

> 对应实施：[../implementation/](../implementation/)（M0–M8 全量 + M9 backend/router 配置）
> 设计基准：[../design/demo/console-user-workspace-demo.html](../design/demo/console-user-workspace-demo.html)
> 环境：kind 集群实拍（backend v14 / frontend v11，http://100.66.1.5:8911），1440×900。

## 登录与用户侧骨架

| 截图 | 说明 |
|------|------|
| [01-login.png](01-login.png) | 登录页：左品牌区 + 右登录卡（首个 admin 由 `ADMIN_INIT_USER` 种子创建）；未登录访问任意页 307 跳转至此 |
| [02-console-overview.png](02-console-overview.png) | 用户侧概览：工作空间统计卡（Agent 总数/运行/异常/已下线）+ 最近发布时间线 + 快捷入口 |
| [25-admin-ws-switcher-all.png](25-admin-ws-switcher-all.png) | 平台管理员的工作空间切换器：可见全部工作空间（含未加入的与 default 存量池，设计 §5.1 列表侧落地；普通用户仅见自己的成员空间） |
| [03-console-agents.png](03-console-agents.png) | Agent 列表：状态/关键字筛选 + 卡片网格（头像字/状态徽标/模型·副本·版本 tag/调试·详情入口） |

## AGENT 构建

| 截图 | 说明 |
|------|------|
| [04-agent-create.png](04-agent-create.png) | 创建 Agent 单页分区表单：选择创建方式三卡片（托管 Harness/导入/纳管）+ 五区块（基本信息/文件配置/资产配置/资源配置/网络配置），右上与底部双「立即创建」 |
| [05-console-templates.png](05-console-templates.png) | Agent 模板：平台公共包（workspace_id=0 + is_template）卡片，「去导入」走 copy-on-write 派生 |
| [06-console-integrations.png](06-console-integrations.png) | 集成 Agent：已建立集成关系（lead→member，ask 策略/被引用）+ 可注册候选端点（probe 在线探测） |

## Agent 详情六 Tab（概览/调试/版本/调用凭证；会话/技能见 M4 文档）

| 截图 | 说明 |
|------|------|
| [16-agent-detail-overview.png](16-agent-detail-overview.png) | 详情 Tab1 概览：基本信息 kv（模型/规格副本/镜像/注册状态）+ 实时 Pod 表 + 事件时间线 |
| [17-agent-detail-debug.png](17-agent-detail-debug.png) | 详情 Tab2 调试：ChatPane 直连业务运行时（SSE 不经控制面），X-User-Id 注入、会话按用户隔离 |
| [18-agent-detail-versions.png](18-agent-detail-versions.png) | 详情 Tab5 版本：包版本链（current 标注/派生溯源）+「编辑」（表单级 copy-on-write）+「升级到此版本」 |
| [19-agent-detail-credentials.png](19-agent-detail-credentials.png) | 详情 Tab6 调用凭证：本 Agent 作用域 API Key + 一次性明文签发 + curl 示例（二期网关生效提示） |

## 资产管理

| 截图 | 说明 |
|------|------|
| [07-assets-models.png](07-assets-models.png) | 模型连接：连接表（掩码/状态/已下发数）+ Agent 使用情况表（默认连接/下发状态） |
| [08-assets-models-drawer.png](08-assets-models-drawer.png) | 模型连接新建/编辑右侧抽屉（slide-over）：遮罩/Esc 关闭；编辑支持 apiKey 留空保持、空格清除 |
| [09-assets-tools.png](09-assets-tools.png) | 工具管理：MCP 连接器表（platform-mcp 平台内置置顶）+ 三态权限策略表 + 「保存并验证」 |
| [10-assets-skills.png](10-assets-skills.png) | Skill 管理：L4 用户技能（所选 Agent 直连，per-user 如实标注）+ 包级技能只读聚合 |
| [11-assets-memory.png](11-assets-memory.png) | 记忆空间：按 Agent×用户只读会话聚合；API 调用身份占位说明（不造假数据） |
| [12-assets-credentials.png](12-assets-credentials.png) | 凭证管理：工作空间级 API Key 表 + 签发弹窗（作用域全空间/单 Agent + 有效期） |
| [15-console-knowledge.png](15-console-knowledge.png) | 知识库占位页（规划中，一期经 MCP 连接器接入外部检索） |

## 身份与权限 / 观测

| 截图 | 说明 |
|------|------|
| [13-console-members.png](13-console-members.png) | 成员管理：成员表（owner 保护）+ 邀请弹窗（invitable-users）+ 角色变更/移除 |
| [14-console-observability.png](14-console-observability.png) | 观测大盘占位（一期不做，eval-studio 外链保留） |

## 运维侧

| 截图 | 说明 |
|------|------|
| [20-admin-services.png](20-admin-services.png) | 服务列表（存量功能迁移 + 新增工作空间列/筛选/未归属纳管） |
| [21-admin-workspaces.png](21-admin-workspaces.png) | 工作空间管理：列表聚合（成员/Agent 计数/配额）+ 创建 + 停用 + 空间内 Agent 视图与纳管 |
| [22-admin-users.png](22-admin-users.png) | 用户管理：列表（角色/所属空间/最近登录）+ 建号 + 改角色/重置密码/禁用 |
| [23-admin-audit.png](23-admin-audit.png) | 审计日志：service_events + platform_config_events 聚合分页（publish/workspace.*/user.*/api_key.*） |
| [24-admin-overview.png](24-admin-overview.png) | 平台概览：服务/用户/包聚合统计卡 + 最近事件 + 运维入口 |

## e2e 用例

按功能 e2e 套件：[../../e2e/console-split/](../../e2e/console-split/)（lib.sh + m0-auth～m8-workspace-assets + run-all.sh，
180+ 断言，kind 集群实跑九模块全 PASS；运行方式见 [run-all.sh](../../e2e/console-split/run-all.sh) 头注释）。
