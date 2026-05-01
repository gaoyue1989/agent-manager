# 镜像构建 & agent-sandbox 部署验证

**日期**: 2026-05-01
**执行者**: opencode

## 1. 概述

验证从生成的 DeepAgents 代码 → Docker 镜像构建 → agent-sandbox 部署的完整链路。

## 2. Docker 镜像构建

### 2.1 配置

- Docker Hub 账号: `gaoyue1989`
- 宿主机代理: mihomo (端口 7890)，Docker 服务已配置代理
- 本地 Registry: `localhost:5001` (供 K8s 拉取镜像)

### 2.2 构建过程

```bash
# 拉取基础镜像（通过代理）
docker pull python:3.12-slim

# 构建 Agent 镜像
docker build -t agent-manager/customer-service-agent:v1 codegen/test/output/

# 推送到本地 Registry
docker tag agent-manager/customer-service-agent:v1 localhost:5001/agent-customer-service-agent:v1
docker push localhost:5001/agent-customer-service-agent:v1
```

### 2.3 镜像信息

| 属性 | 值 |
|------|-----|
| 镜像名 | `agent-manager/customer-service-agent:v1` |
| 基础镜像 | `python:3.12-slim` |
| 镜像大小 | ~84 MB |

## 3. agent-sandbox 部署

### 3.1 Sandbox CRD

```yaml
apiVersion: agents.x-k8s.io/v1alpha1
kind: Sandbox
metadata:
  name: agent-customer-service
spec:
  podTemplate:
    spec:
      containers:
      - name: agent
        image: 172.20.0.1:5001/agent-customer-service-agent:v1
        ports:
        - containerPort: 8000
        env:
        - name: LLM_API_KEY
          value: "sk-0440b76852944f019bb142a715bc2cab"
        - name: HTTP_PROXY
          value: "http://172.20.0.1:7890"
        - name: HTTPS_PROXY
          value: "http://172.20.0.1:7890"
```

### 3.2 Pod 标签

Sandbox Controller 自动为 Pod 设置标签 `agents.x-k8s.io/sandbox-name-hash`。Service selector 需使用此标签而非自定义标签。

### 3.3 部署结果

| 资源 | 状态 |
|------|------|
| Sandbox CRD | ✅ Created |
| Pod | ✅ Running (1/1) |
| Service (Headless) | ✅ ClusterIP: None (自动创建) |
| Service (NodePort) | ✅ 手动创建，selector 需修正 |

## 4. 遇到的问题与解决

| 问题 | 原因 | 解决 |
|------|------|------|
| Docker build 无法拉取基础镜像 | 需要代理 | `docker pull` 先拉取缓存 |
| Service 端点为空 | Label selector 不匹配 | 使用 `agents.x-k8s.io/sandbox-name-hash` |
| Pod 内网络不通 | Kind 节点无代理 | 配置 `HTTP_PROXY` 环境变量到 `172.20.0.1:7890` |
| Kubectl port-forward 超时 | 后台进程被杀 | 使用 `kubectl exec` 直接测试 |

## 5. 关键发现

- **Sandbox Controller 的服务标签**: 自动生成的 headless service 使用 `agents.x-k8s.io/sandbox-name-hash` 作为 selector
- **Kind 网络**: 节点内通过 `172.20.0.1` 访问宿主机（包括代理和本地 Registry）
- **镜像流程**: `docker build` → `localhost:5001` → `172.20.0.1:5001` (K8s 内引用)
- **代理配置**: K8s pod 需要显式设置 `HTTP_PROXY`/`HTTPS_PROXY` 环境变量
