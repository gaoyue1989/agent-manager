# E2E Testing — OAF 发布平台

## 场景矩阵（对应 docs/design/REDESIGN.md §10）

| 脚本 | 场景 | 内容 |
|------|------|------|
| platform-e2e.sh | A+B | REST 主链路 50 断言：上传→发布→A2A 注册→env 更新（含全量覆盖清除旧键）→republish→上下线→异常路径（deploying/stopped 改 env 拒绝、被引用包删除 400）→零残留清理（增量对齐基线，保护 release-agent）；镜像经 GET /images 动态选取不写死 |
| package-edit-e2e.sh | A+ | 包在线预览/编辑 34 断言：单文件预览（文本/路径穿越/不存在）/整包与单文件下载→在线编辑生成新版本（派生溯源 sourcePackageId、基础包零修改、增删改生效、同 slug 过滤）→异常路径（无变更 400、删 AGENTS.md 400、乐观锁 409、非法 frontmatter 400）→发布→republish 换版（subPath 切换、引用计数 0→1、注册版本 1.0.0→1.1.0）→按包过滤服务→零残留清理 |
| mcpclient/ (go run .) | C | MCP client 经 streamableHttp 走完整发布链路 27 断言（含 list_images/list_packages/get_package_detail/register_service 与两步确认删除） |
| ui-e2e.js | D | Puppeteer UI 流程 10 断言（发布向导三步/详情轮询/重发布/删除） |
| agent-e2e.sh | E | release-agent 自然语言驱动第三方发布+删除全链路 8 断言；变更类走 /threads/chat + confirm-stream HITL 确认流（需 release-agent ≥ HITL 20260917 部署）；A2A 通道仅查询探针（HITL 后 A2A ask 挂起不落 confirm_context，变更无法批准——待运行时修复） |
| debug-console-e2e.js | 附 | Debug Console 子路径渲染（经 :30080） |
| chat-ui-e2e.js | 附 | /assistant 对话真实 LLM 流式回复（经 :8911） |
| file-support-e2e.sh | 附 | 文件上传下载：S1 上传文档/S2 上传图片(视觉)/S3 输出文档/S4 输出图片/X 异常/S8 生成 OAF 部署包（非沙箱 21 断言）；SANDBOX=1 启用沙箱专项 S-S1~S-S7（22 断言）+ S-S8/S-S9 生成包与发布全链路（合计 29 断言） |
| file-support-ui-e2e.js | 附 | /assistant 对话 UI 15 用例：附件上传/读文件/present_file 卡片/下载（U1~U10）+ 生成 OAF 包对话（U11~U13）+ 历史会话展示/切换回放/继续对话（U14~U16） |
| s3-file-e2e.sh | 附 | S3 存储档：切 release-agent 为 S3 后端（凭据读 .env.secrets）→ 跑非沙箱档全链路落 S3 → 自动恢复 local |
| skills-dynamic-e2e.sh | 附 | OAF skills 目录动态加载：发布带 skills 的包 → 基线/对话读技能 → 宿主直写 PVC 动态新增/修改/删除（**不重启**）→ L4 用户覆盖隔离（非沙箱 20 断言）；SANDBOX=1 加沙箱档 .skills-cache 物化+投影+容器内脚本执行（23 断言） |
| user-skill-admin-e2e.sh | 附 | 用户技能 L4 管理面：自建包/服务（P）→ PUT 个人覆盖 → 明细 GET（source/hasUserOverride）→ 索引（/skills/users 与 /debug/user-skills）→ **非沙箱档** A2A 生效性 + 用户隔离 → DELETE 回落包内基线（逐字节比对）→ sync-from-package（含 scripts/ 资源）→ 负例（非法 userId/name 400、不存在 404、>100KB 413），非沙箱合计 37 断言；SANDBOX=1 跑管理面（KV 读写/回落）并加 E8 沙箱回写（容器内 skill_manage → agent_fs 落 `agents/{agent}/users/{uid}/skills`，8.1~8.6）与 **E9 回写仲裁**（9.1~9.12：同 uid 连续 call 下 DELETE 的 tombstone 防「删除被复活」、PUT 的 admin-override 栅栏防「管理面写入被容器内旧副本改回」，含删除响应 tombstone 字段断言），**A2A 生效性断言在沙箱档不成立**（会话读容器内副本，见下节档位说明） |
| S3FileStorageIT（Java） | 附 | S3FileStorage 集成测试 3 用例（写入/哨兵回读/删除幂等/不存在语义）；`S3_IT=1 S3_IT_ENDPOINT=... S3_IT_ACCESS_KEY=... S3_IT_SECRET_KEY=... S3_IT_BUCKET=... mvn test -Dtest=S3FileStorageIT`，无 env 自动 skip |

## 运行前置

- 集群内 platform-backend/frontend/release-agent 全部 running
- fixtures/demo-agent-v{1,2}.zip、release-agent.zip、bad-no-agentsmd.zip
- `.env.secrets` 提供 LLM_API_KEY/LLM_MODEL/LLM_ENDPOINT（场景 C/D/E 需要）
- 涉及 L4/沙箱的场景（user-skill-admin-e2e.sh、skills-dynamic-e2e.sh SANDBOX=1）需**新 agent-framework 镜像已导入 kind 节点并在服务上生效**

```bash
./platform-e2e.sh                       # BASE 默认 http://localhost:30080/api/v1
./package-edit-e2e.sh                   # 包在线预览/编辑/换版发布 34 断言
cd mcpclient && go run . -base http://localhost:30080/mcp -zip ../fixtures/demo-agent-v1.zip
FRONTEND=http://172.20.0.3:30881 node ui-e2e.js
node chat-ui-e2e.js                      # 默认 http://100.66.1.5:8911
node debug-markdown-e2e.js               # Debug 页 markdown 渲染
./file-support-e2e.sh                    # 非沙箱档（release-agent 默认部署）
SANDBOX=1 ./file-support-e2e.sh          # 沙箱档（release-agent env 含 SANDBOX_ENABLED=true，跑完恢复）
FRONTEND=http://100.66.1.5:8911 node file-support-ui-e2e.js   # UI 档（随 release-agent 当前模式）
./s3-file-e2e.sh                         # S3 存储档（凭据在 .env.secrets，跑完自动恢复 local）
./skills-dynamic-e2e.sh                  # skills 动态加载（非沙箱）；SANDBOX=1 加沙箱档
./user-skill-admin-e2e.sh                # 用户技能 L4 管理面 + 非沙箱档 A2A 生效性（自建包/服务，脚本自清理）
SANDBOX=1 ./user-skill-admin-e2e.sh      # 沙箱档：管理面 + E8 沙箱回写 + E9 回写仲裁（tombstone/admin-override）
                                         # （A2A 生效性断言跳过，见档位说明；E9 需同 uid 连续 call，约多 3 次 A2A）
                                         # 前置：新 agent-framework:latest 已导入节点
                                         # （agent-framework: make docker-build && docker save | docker exec -i agent-manager-control-plane ctr --namespace k8s.io images import -）
```

### 档位说明（沙箱 vs 非沙箱的 L4 生效范围）

`user-skill-admin-e2e.sh` 默认（非沙箱）档推理直接读 agent_fs 的 L4，管理面写入/删除下轮会话即生效；
`SANDBOX=1` 档会话读的是容器内 `/workspace/skills` 副本（容器不物化 L4，会话看到的就是容器内那份），
管理面写入的 L4 需「会话开始物化 L4」能力（尚未实现）才对会话生效，故 A2A 生效性断言仅在非沙箱档成立；
反向（会话内 skill_manage 写入 → 回写 agent_fs）由 E8 断言；**回写仲裁**由 E9 断言——管理面写入/删除会在
KV 留标记（`/{name}/.admin-override` / `/{name}/.deleted`），回写命中即跳过同名技能，使「管理面内容被同代
容器内旧副本改回」「删除被容器内副本复活」都不再发生；代价是该技能在容器内的 skill_manage 修改在标记清除
前不落库（清除方式见接口响应与调试页提示）。同档位口径见 agent-framework/AGENTS.md 端点表与
[docs/design/user-skill-admin-design.md](../docs/design/user-skill-admin-design.md)。

## 断言原则

每场景结束断言 DB 记录 / K8s 资源 / PVC 目录三方一致；E2E 会话用运行级唯一 userId（防 HITL 暂停态复放）；LLM 相关超时窗口 ≥300s。
