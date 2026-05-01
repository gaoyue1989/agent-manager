# Kubernetes (Kind) 部署记录

**日期**: 2026-05-01  
**执行者**: opencode

## 1. 环境信息

| 项目 | 内容 |
|------|------|
| 操作系统 | Ubuntu 24.04.3 LTS |
| 内核 | 6.17.0-22-generic |
| CPU | 4 cores |
| 内存 | 8GB |
| Docker | v29.4.0 |

## 2. 工具安装

### kubectl
```bash
# 通过阿里云镜像安装
curl -fsSL https://mirrors.aliyun.com/kubernetes/apt/doc/apt-key.gpg | gpg --dearmor -o /usr/share/keyrings/kubernetes-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/kubernetes-archive-keyring.gpg] https://mirrors.aliyun.com/kubernetes/apt/ kubernetes-xenial main" | tee /etc/apt/sources.list.d/kubernetes.list
apt-get update && apt-get install -y kubectl
```
版本: `v1.28.2`

### Kind
```bash
# 通过 Go 安装 (使用国内代理)
GOPROXY=https://goproxy.cn,direct go install sigs.k8s.io/kind@v0.27.0
cp ~/go/bin/kind /usr/local/bin/
```
版本: `v0.27.0`

## 3. 集群创建

### 配置文件
`docs/kind-config.yaml`:
```yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
- role: control-plane
  kubeadmConfigPatches:
  - |
    kind: InitConfiguration
    nodeRegistration:
      kubeletExtraArgs:
        node-labels: "ingress-ready=true"
  extraPortMappings:
  - containerPort: 30080
    hostPort: 30080
    protocol: TCP
  - containerPort: 30443
    hostPort: 30443
    protocol: TCP
```

### 创建命令
```bash
kind create cluster --name agent-manager --config docs/kind-config.yaml --wait 5m
```

## 4. 验证结果

### 节点状态
```
NAME                          STATUS   ROLES           AGE   VERSION
agent-manager-control-plane   Ready    control-plane   33s   v1.32.2
```

### 系统 Pod 状态
| Pod | 状态 |
|-----|------|
| coredns (x2) | Running |
| etcd | Running |
| kindnet | Running |
| kube-apiserver | Running |
| kube-controller-manager | Running |
| kube-proxy | Running |
| kube-scheduler | Running |
| local-path-provisioner | Running |

### 集群地址
- API Server: `https://127.0.0.1:39921`
- 上下文: `kind-agent-manager`
- 节点 IP: `172.20.0.2`

## 5. 端口规划 (避免与 Dify 冲突)

| 服务 | 端口 | 说明 |
|------|------|------|
| Dify Nginx | 80/443 | Dify Web |
| GreatSQL | 3307 | 数据库 |
| Docker MySQL | 3306 | Dify DB |
| MinIO | 9000/9001 | 对象存储 |
| **K8s NodePort** | **30080/30443** | **新规划，不冲突** |

## 6. 常用命令

```bash
# 查看集群
kubectl cluster-info --context kind-agent-manager

# 查看节点
kubectl get nodes -o wide

# 查看所有 Pod
kubectl get pods -A

# 设为默认上下文
kubectl config use-context kind-agent-manager

# 删除集群
kind delete cluster --name agent-manager
```
