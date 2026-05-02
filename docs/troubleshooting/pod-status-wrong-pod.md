# Pod Status 返回错误的 Pod

## 问题描述

调用 `/api/v1/agents/22/pod-status` 返回了 `agent-15` 的信息，而非 `agent-22`：

```json
{
  "pod_name": "agent-15",
  "status": "Pending",
  "sandbox_name": "agent-22"
}
```

## 根因分析

`GetPodStatus` 和 `GetPodStatusJSON` 的 label selector 只用了 `agents.x-k8s.io/sandbox-name-hash`（无值），匹配了所有 Pod，返回第一个。

原代码 (`backend/internal/k8s/sandbox.go`):

```go
cmd := exec.Command("kubectl", "get", "pods", "-n", s.namespace, "-l",
    fmt.Sprintf("agents.x-k8s.io/sandbox-name-hash"), "-o", "json")
```

## 解决方案

改用 `app=<sandboxName>` 精确匹配：

```go
cmd := exec.Command("kubectl", "get", "pods", "-n", s.namespace, "-l",
    fmt.Sprintf("app=%s", sandboxName), "-o", "json")
```

## 验证

```bash
curl -s http://localhost:8080/api/v1/agents/22/pod-status | jq '.'
# {
#   "pod_name": "agent-22",
#   "status": "Running",
#   "ready": "true",
#   "pod_ip": "10.244.0.22",
#   "sandbox_name": "agent-22"
# }
```

## 相关文件

- `backend/internal/k8s/sandbox.go:189` — GetPodStatus 函数
- `backend/internal/k8s/sandbox.go:218` — GetPodStatusJSON 函数
