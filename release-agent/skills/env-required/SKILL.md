---
name: env-required
description: 发布或重新发布使用 agent-framework 镜像的服务时必须校验环境变量：先调 get_platform_defaults 取平台默认配置预填打底，LLM_API_KEY、LLM_MODEL_ID、LLM_BASE_URL、CHECKPOINT_JDBC_URL、CHECKPOINT_USERNAME、CHECKPOINT_PASSWORD 六项必填在合并结果中任一缺失或为空时先向用户询问补充，禁止以占位符或空值发布。
---

# 服务发布环境变量校验

发布 / 重新发布使用 agent-framework 镜像的服务（publish_service、republish_service）之前，先取平台默认配置打底，再逐项核对合并后的环境变量：

0. **预填平台默认**：调用 `get_platform_defaults`，`values` 中已配置的键直接作为 env 底座（用户显式提供的同名值优先），不要让用户重复提供；`fields` 可查各键是否必填/敏感。

| 变量 | 必填 | 说明 |
|------|------|------|
| `LLM_API_KEY` | ✓ | LLM API 密钥（回复中不得明文回显完整值） |
| `LLM_MODEL_ID` | ✓ | 模型 ID |
| `LLM_BASE_URL` | ✓ | LLM API 端点 |
| `CHECKPOINT_JDBC_URL` | ✓ | MySQL JDBC 连接串（oaf_checkpoint 库） |
| `CHECKPOINT_USERNAME` | ✓ | 数据库用户名 |
| `CHECKPOINT_PASSWORD` | ✓ | 数据库密码（回复中不得明文回显） |

## 校验规则

1. **合并平台默认配置后，任一必填项缺失、为空或值为占位符** → 不要调用 publish_service / republish_service；向用户列出缺失项并明确请求补充。
2. 用户拒绝提供 → 停止发布流程，说明服务无法正常运行。
3. 敏感键（LLM_API_KEY、CHECKPOINT_PASSWORD、AGENT_REDIS_URL、OPENSANDBOX_API_KEY 等）只出现在工具调用参数中（平台会自动路由进服务 Secret），在对话中只确认"已提供 / 来自平台默认"，不回显原文。
4. 其他非必填 env（如 SANDBOX_ENABLED、LOG_LEVEL 等）按用户说明原样透传。
5. 校验通过后再执行发布，并按 publish_service 工具要求轮询最终状态。
