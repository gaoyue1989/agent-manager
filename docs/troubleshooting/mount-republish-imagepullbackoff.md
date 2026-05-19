# 挂载模式重新发布出现 ImagePullBackOff

**日期**: 2026-05-19

## 问题

挂载模式 Agent 先下线再重新发布后，Pod 处于 `ImagePullBackOff`，Pod 镜像为 `172.20.0.1:5001/agent-{id}:v1`（构建模式镜像）而非 `172.20.0.1:5001/agent-framework:latest`。

## 根因

`Publish` 方法中挂载模式逻辑有缺陷：

```go
// 修复前
if agent.RuntimeMode == model.RuntimeModeMount {
    if agent.Status == model.StatusDraft {
        dep, err = s.DeployWithMount(agentID)  // 仅 draft 状态走挂载部署
    } else {
        dep, err = s.Deploy(agentID)  // unpublished 状态走到了构建模式部署
    }
}
```

下线后 Agent 状态变为 `unpublished`，重新发布时 `agent.Status != model.StatusDraft`，导致走了 `Deploy()` (构建模式) 而非 `DeployWithMount()` (挂载模式)。

## 解决

修改 `backend/internal/service/deploy.go:304-305`，挂载模式始终使用 `DeployWithMount`：

```go
if agent.RuntimeMode == model.RuntimeModeMount {
    dep, err = s.DeployWithMount(agentID)
}
```

## 影响范围

所有挂载模式 Agent 的重新发布操作。

## 相关文件

- `backend/internal/service/deploy.go` (Publish 方法)
