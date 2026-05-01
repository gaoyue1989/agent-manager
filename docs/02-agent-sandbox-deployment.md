# agent-sandbox 部署记录

**日期**: 2026-05-01  
**执行者**: opencode

## 1. 概述

部署 `kubernetes-sigs/agent-sandbox` v0.4.3 到 Kind (agent-manager) 集群。

agent-sandbox 提供 Kubernetes Sandbox CRD 和 Controller，用于管理隔离的、有状态的单容器工作负载。

## 2. 安装

### 2.1 下载 Manifest

```bash
# 核心组件 (Sandbox CRD + Controller)
curl -fsSL -o sandbox/manifest.yaml \
  "https://github.com/kubernetes-sigs/agent-sandbox/releases/download/v0.4.3/manifest.yaml"

# 扩展组件 (SandboxTemplate, SandboxClaim, SandboxWarmPool)
curl -fsSL -o sandbox/extensions.yaml \
  "https://github.com/kubernetes-sigs/agent-sandbox/releases/download/v0.4.3/extensions.yaml"
```

### 2.2 镜像问题解决

Kind 节点无法直接访问 `registry.k8s.io`（Google 托管）。解决方法：

1. **宿主机拉取镜像：**
```bash
docker pull registry.k8s.io/agent-sandbox/agent-sandbox-controller:v0.4.3
```

2. **启动本地 Registry：**
```bash
docker run -d --restart=always -p 5001:5000 --name local-registry registry:2
```

3. **推送镜像到本地 Registry：**
```bash
docker tag registry.k8s.io/agent-sandbox/agent-sandbox-controller:v0.4.3 \
  localhost:5001/agent-sandbox-controller:v0.4.3
docker push localhost:5001/agent-sandbox-controller:v0.4.3
```

4. **配置 containerd 信任本地 HTTP Registry：**
```bash
# 编辑 /etc/containerd/config.toml，添加：
[plugins."io.containerd.grpc.v1.cri".registry.mirrors."172.20.0.1:5001"]
  endpoint = ["http://172.20.0.1:5001"]
[plugins."io.containerd.grpc.v1.cri".registry.configs."172.20.0.1:5001".tls]
  insecure_skip_verify = true

# 重启 containerd
systemctl restart containerd
```

5. **更新 Deployment 镜像引用：**
```bash
kubectl set image deployment/agent-sandbox-controller -n agent-sandbox-system \
  agent-sandbox-controller=172.20.0.1:5001/agent-sandbox-controller:v0.4.3
```

### 2.3 应用部署

```bash
kubectl apply -f sandbox/manifest.yaml
kubectl apply -f sandbox/extensions.yaml
```

## 3. 验证结果

### 3.1 已安装的 CRD

| CRD | API Group | 状态 |
|-----|-----------|------|
| sandboxes | agents.x-k8s.io/v1alpha1 | ✅ |
| sandboxclaims | extensions.agents.x-k8s.io/v1alpha1 | ✅ |
| sandboxtemplates | extensions.agents.x-k8s.io/v1alpha1 | ✅ |
| sandboxwarmpools | extensions.agents.x-k8s.io/v1alpha1 | ✅ |

### 3.2 Controller 状态

```
NAMESPACE              NAME                                  READY   STATUS
agent-sandbox-system   agent-sandbox-controller-f4db46b6d    1/1     Running
```

### 3.3 测试 Sandbox 创建

```bash
kubectl apply -f - << 'EOF'
apiVersion: agents.x-k8s.io/v1alpha1
kind: Sandbox
metadata:
  name: test-sandbox
spec:
  podTemplate:
    spec:
      containers:
      - name: test-container
        image: 172.20.0.1:5001/busybox:latest
        command: ["sh", "-c", "while true; do echo 'running'; sleep 60; done"]
EOF
```

结果：
- Pod `test-sandbox` → Running (1/1)
- Sandbox Status: `Pod is Ready; Service Exists`

## 4. 后续镜像管理策略

所有 Agent 镜像将通过以下流程部署：

```bash
# 1. 构建镜像
docker build -t agent-manager/agent-{name}:{version} .

# 2. 推送到本地 Registry
docker tag agent-manager/agent-{name}:{version} localhost:5001/agent-{name}:{version}
docker push localhost:5001/agent-{name}:{version}

# 3. 创建 Sandbox (引用 172.20.0.1:5001/agent-{name}:{version})
kubectl apply -f sandbox-definition.yaml
```

## 5. 关键配置

| 参数 | 值 | 说明 |
|------|-----|------|
| 本地 Registry 地址 | `172.20.0.1:5001` | Kind 节点可通过此 IP 访问宿主机 |
| Controller 命名空间 | `agent-sandbox-system` | Operator 运行命名空间 |
| Sandbox 命名空间 | `default` | 用户 Sandbox 所在的命名空间 |
| Sandbox 网络模式 | Headless Service (ClusterIP: None) | 稳定网络标识 |
| 存储类 | `local-path` | 本地路径持久存储 |
| 关闭策略 | `Retain` | 关闭后保留资源 |

## 6. 常用命令

```bash
# 查看 Sandbox 列表
kubectl get sandboxes

# 查看 Sandbox 详情
kubectl describe sandbox <name>

# 查看 Sandbox Pod
kubectl get pods -l sandbox-name=<name>

# 删除 Sandbox
kubectl delete sandbox <name>

# 查看 Controller 日志
kubectl logs -n agent-sandbox-system deploy/agent-sandbox-controller

# 查看本地 Registry 中的镜像
curl -s http://localhost:5001/v2/_catalog
```
