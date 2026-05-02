# Agent API 测试验证

**日期**: 2026-05-01
**执行者**: opencode

## 1. 概述

验证部署后的 Agent 通过 API 接口的可用性。

## 2. 测试结果

### 2.1 健康检查

```
GET /health → 200 OK
{"status":"healthy","agent":"customer-service-agent"}
```

### 2.2 信息端点

```
GET / → 200 OK
{"agent":"customer-service-agent","description":"智能客服助手...","model":"qwen3.6-plus","endpoint":"/chat","health":"/health"}
```

### 2.3 LLM 对话

```
POST /chat → 200 OK
Request:  {"message":"用中文简单介绍一下你自己"}
Response: {"success":true,"data":{"response":"你好！很高兴见到你，有什么我可以帮你的吗？"},"error":null}

POST /chat → 200 OK
Request:  {"message":"1+1等于几"}
Response: {"success":true,"data":{"response":"1+1等于2。"},"error":null}
```

## 3. 测试方式

| 方式 | 命令 | 结果 |
|------|------|------|
| Pod 内直接调用 | `kubectl exec` + curl | ✅ 正常 |
| Port-forward | `kubectl port-forward` + 宿主机 curl | ✅ 正常（需重启 port-forward） |

## 4. LLM 配置

| 参数 | 值 |
|------|-----|
| 模型 | qwen3.6-plus |
| 接口地址 | https://dashscope.aliyuncs.com/compatible-mode/v1 |
| API Key | sk-**** (通过环境变量 LLM_API_KEY 配置) |
| 代理 | http://172.20.0.1:7890 |

## 5. 结论

Agent 完整链路验证通过：
1. ✅ JSON 配置 → DeepAgents 代码生成
2. ✅ Docker 镜像构建
3. ✅ agent-sandbox K8s 部署
4. ✅ LLM API 调用正常响应
5. ✅ FastAPI 端点全部正常
