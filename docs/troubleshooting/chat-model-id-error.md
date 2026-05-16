# Chat 接口返回模型 ID 错误

## 问题描述

调用 `/api/v1/agents/22/chat` 返回：

```json
{
  "detail": "Error code: 400 - {'code': 500002, 'detail': '模型id错误，无法与AppKey对应，请联系管理员'}"
}
```

## 根因分析

Agent 配置中 `model` 字段误填为 API Key，而非模型 ID：

```json
{
  "model": "<api_key>",  // ❌ 这是 API Key
  "api_key": "<api_key>"
}
```

生成的 `agent.py`:

```python
MODEL_NAME = "<api_key>"  # ❌ 错误
```

## 解决方案

更新 Agent 配置，将 `model` 改为正确的模型 ID：

```bash
curl -X PUT http://localhost:8080/api/v1/agents/22 \
  -H "Content-Type: application/json" \
  -d '{"config": "{\"model\": \"your_model_id_here\", ...}"}'
```

然后重新生成代码、构建镜像、部署：

```bash
curl -X POST http://localhost:8080/api/v1/agents/22/generate
curl -X POST http://localhost:8080/api/v1/agents/22/build
kubectl delete sandbox agent-22
curl -X POST http://localhost:8080/api/v1/agents/22/deploy
```

## 验证

```bash
kubectl exec agent-22 -- env | grep LLM_MODEL
# LLM_MODEL=<model_id>
```

## 配置字段说明

| 字段 | 说明 | 示例 |
|------|------|------|
| `model` | 模型 ID | `<model_id>` (如 GLM-5) |
| `api_key` | API Key | `<api_key>` |
| `model_endpoint` | API 端点 | `https://wishub-x6.ctyun.cn/v1` |

## 相关文件

- `codegen/generator.py:33` — 从 config 读取 model
- `backend/internal/service/deploy.go:320` — parseLLMConfig 解析配置
