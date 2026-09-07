# E2E Testing — OAF 发布平台

## 场景矩阵（对应 REDESIGN §10）

| 脚本 | 场景 | 内容 |
|------|------|------|
| platform-e2e.sh | A+B | REST 主链路 42 断言：上传→发布→A2A 注册→env 更新→republish→上下线→异常路径→零残留清理（保护 release-agent） |
| mcpclient/ (go run .) | C | MCP client 经 streamableHttp 走完整发布链路 20 断言 |
| ui-e2e.js | D | Puppeteer UI 流程 10 断言（发布向导三步/详情轮询/重发布/删除） |
| agent-e2e.sh | E | release-agent 自举 + 自然语言驱动第三方发布 + 确认式删除 6 断言 |
| debug-console-e2e.js | 附 | Debug Console 子路径渲染（经 :30080） |
| chat-ui-e2e.js | 附 | /assistant 对话真实 LLM 流式回复（经 :8911） |
| file-support-e2e.sh | 附 | 文件上传下载：S1 上传文档/S2 上传图片(视觉)/S3 输出文档/S4 输出图片/X 异常/S8 生成 OAF 部署包（非沙箱 21 断言）；SANDBOX=1 启用沙箱专项 S-S1~S-S7（22 断言）+ S-S8/S-S9 生成包与发布全链路（合计 29 断言） |
| file-support-ui-e2e.js | 附 | /assistant 对话 UI 15 用例：附件上传/读文件/present_file 卡片/下载（U1~U10）+ 生成 OAF 包对话（U11~U13）+ 历史会话展示/切换回放/继续对话（U14~U16） |
| s3-file-e2e.sh | 附 | S3 存储档：切 release-agent 为 S3 后端（凭据读 .env.secrets）→ 跑非沙箱档全链路落 S3 → 自动恢复 local |
| skills-dynamic-e2e.sh | 附 | OAF skills 目录动态加载：发布带 skills 的包 → 基线/对话读技能 → 宿主直写 PVC 动态新增/修改/删除（**不重启**）→ L4 用户覆盖隔离（非沙箱 20 断言）；SANDBOX=1 加沙箱档 .skills-cache 物化+投影+容器内脚本执行（23 断言） |
| S3FileStorageIT（Java） | 附 | S3FileStorage 集成测试 3 用例（写入/哨兵回读/删除幂等/不存在语义）；`S3_IT=1 S3_IT_ENDPOINT=... S3_IT_ACCESS_KEY=... S3_IT_SECRET_KEY=... S3_IT_BUCKET=... mvn test -Dtest=S3FileStorageIT`，无 env 自动 skip |

## 运行前置

- 集群内 platform-backend/frontend/release-agent 全部 running
- fixtures/demo-agent-v{1,2}.zip、release-agent.zip、bad-no-agentsmd.zip
- `.env.secrets` 提供 LLM_API_KEY/LLM_MODEL/LLM_ENDPOINT（场景 C/D/E 需要）

```bash
./platform-e2e.sh                       # BASE 默认 http://localhost:30080/api/v1
cd mcpclient && go run . -base http://localhost:30080/mcp -zip ../fixtures/demo-agent-v1.zip
FRONTEND=http://172.20.0.3:30881 node ui-e2e.js
node chat-ui-e2e.js                      # 默认 http://100.66.1.5:8911
./file-support-e2e.sh                    # 非沙箱档（release-agent 默认部署）
SANDBOX=1 ./file-support-e2e.sh          # 沙箱档（release-agent env 含 SANDBOX_ENABLED=true，跑完恢复）
FRONTEND=http://100.66.1.5:8911 node file-support-ui-e2e.js   # UI 档（随 release-agent 当前模式）
./s3-file-e2e.sh                         # S3 存储档（凭据在 .env.secrets，跑完自动恢复 local）
./skills-dynamic-e2e.sh                  # skills 动态加载（非沙箱）；SANDBOX=1 加沙箱档
                                         # 前置：新 agent-framework:latest 已导入节点
                                         # （agent-framework: make docker-build && docker save | docker exec -i agent-manager-control-plane ctr --namespace k8s.io images import -）
```

## 断言原则

每场景结束断言 DB 记录 / K8s 资源 / PVC 目录三方一致；E2E 会话用运行级唯一 userId（防 HITL 暂停态复放）；LLM 相关超时窗口 ≥300s。
