# 标准 Deployment 部署方式

**日期**: 2026-05-20
**版本**: v1.0

## 1. 概述

Agent Manager 支持两种 Kubernetes 部署方式：

| 部署方式 | DEPLOY_METHOD | K8s 资源 | 适用场景 |
|---------|--------------|---------|---------|
| **Sandbox CRD** | `sandbox` (默认) | Sandbox CRD + Service + Ingress | 需要 agent-sandbox Controller |
| **标准 Deployment** | `deployment` | Deployment + Service + Ingress | 标准 K8s 集群，无需额外 CRD |

## 2. 配置

### 2.1 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DEPLOY_METHOD` | `sandbox` | 部署方式: `sandbox` 或 `deployment` |
| `DEPLOY_TEMPLATE_DIR` | (空) | 外部模板目录，覆盖内置 Deployment/Service/Ingress 模板 |
| `KUBE_CONFIG` | (空) | kubeconfig 认证文件路径 (支持远端集群) |
| `KUBE_NAMESPACE` | `default` | K8s 目标命名空间 |
| `KUBE_INGRESS_CLASS` | `nginx` | Ingress Controller 类型 |
| `K8S_CLIENT_MODE` | `kubectl` | K8s 接入方式: `kubectl` (CLI) 或 `api` (client-go SDK) |
| `INGRESS_HOST` | `localhost` | 对外发布的 Ingress 主机名 |
| `INGRESS_ENABLED` | `true` | 是否创建 Ingress |

### 2.2 启用标准 Deployment 模式

```bash
export DEPLOY_METHOD=deployment
```

### 2.3 远端集群部署

```bash
export DEPLOY_METHOD=deployment
export KUBE_CONFIG=/path/to/remote-cluster.kubeconfig
export KUBE_NAMESPACE=agent-prod
export LOCAL_REGISTRY=registry.example.com/agent-images
export INGRESS_HOST=agents.example.com
```

## 3. 资源对比

### Sandbox 模式创建的 K8s 资源

```
Sandbox CRD (agents.x-k8s.io/v1alpha1)
  └── Pod (由 Controller 管理)
Service (ClusterIP)
Ingress (nginx)
  + ConfigMap (挂载模式)
  + Secret (挂载模式)
```

### Deployment 模式创建的 K8s 资源

```
Deployment (apps/v1)
  └── ReplicaSet → Pod
Service (ClusterIP)
Ingress (nginx)
  + ConfigMap (挂载模式)
  + Secret (挂载模式)
```

## 4. 模板系统

### 4.1 内置模板 (Go embed)

内置三种模板，编译时嵌入二进制文件：

```
backend/internal/k8s/templates/
├── deployment.yaml.tmpl     # Deployment 模板
├── service.yaml.tmpl         # Service 模板
└── ingress.yaml.tmpl         # Ingress 模板
```

### 4.2 自定义模板

创建模板目录并放置同名 `.yaml.tmpl` 文件覆盖内置模板：

```bash
mkdir -p /etc/agent-manager/templates

# 自定义 Deployment 模板
cat > /etc/agent-manager/templates/deployment.yaml.tmpl << 'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{.Name}}
  namespace: {{.Namespace}}
  labels:
    app: {{.Name}}
spec:
  replicas: {{.Replicas}}
  selector:
    matchLabels:
      app: {{.Name}}
  template:
    metadata:
      labels:
        app: {{.Name}}
    spec:
      containers:
      - name: agent
        image: {{.Image}}
        ports:
        - containerPort: {{.Port}}
{{- if .EnvYAML}}
        env:
{{.EnvYAML}}
{{- end}}
{{- if .Resources}}
        resources:
{{.Resources}}
{{- end}}
{{- if .LivenessProbe}}
        livenessProbe:
{{.LivenessProbe}}
{{- end}}
{{- if .ReadinessProbe}}
        readinessProbe:
{{.ReadinessProbe}}
{{- end}}
{{- if .VolumeMounts}}
        volumeMounts:
{{.VolumeMounts}}
{{- end}}
{{- if .Volumes}}
      volumes:
{{.Volumes}}
{{- end}}
EOF

export DEPLOY_TEMPLATE_DIR=/etc/agent-manager/templates
```

### 4.3 模板变量

| 变量 | 类型 | 说明 |
|------|------|------|
| `{{.Name}}` | string | 资源名称 (如 `agent-67`) |
| `{{.Namespace}}` | string | K8s 命名空间 |
| `{{.Image}}` | string | 容器镜像完整路径 |
| `{{.Port}}` | int | 容器端口 (8000 build / 8100 mount) |
| `{{.Replicas}}` | int | 副本数 (默认 1) |
| `{{.EnvYAML}}` | string | 环境变量 YAML 片段 |
| `{{.Resources}}` | string | 资源限制 requests/limits |
| `{{.LivenessProbe}}` | string | 存活探针配置 |
| `{{.ReadinessProbe}}` | string | 就绪探针配置 |
| `{{.VolumeMounts}}` | string | 卷挂载配置 |
| `{{.Volumes}}` | string | 卷定义 |

## 5. K8s 客户端接入方式

### 5.1 kubectl CLI (默认)

无需额外依赖，要求系统安装 `kubectl`：

```bash
export K8S_CLIENT_MODE=kubectl
```

### 5.2 client-go SDK (可选)

纯 Go 实现，无外部依赖。需网络下载 client-go 库后启用：

```bash
# 启用方式：
# 1. 删除 backend/internal/k8s/api_client.go 第一行 "//go:build ignore"
# 2. go get k8s.io/client-go@v0.30.2 k8s.io/api@v0.30.2 k8s.io/apimachinery@v0.30.2
# 3. go mod tidy
# 4. export K8S_CLIENT_MODE=api
```

## 6. 部署流程

### 6.1 build 模式 + 标准 Deployment

```
创建 Agent → 代码生成 → 构建 Docker 镜像 → Deploy (Deployment + Service) → Publish (+ Ingress)
```

### 6.2 mount 模式 + 标准 Deployment

```
创建 Agent → Publish (ConfigMap + Secret + Deployment + Service + Ingress)
```

## 7. 生命周期管理

| 操作 | API | Deployment 模式行为 |
|------|-----|-------------------|
| 创建 | `POST /agents` | 无 K8s 操作 |
| 生成代码 | `POST /agents/:id/generate` | 无 K8s 操作 |
| 构建镜像 | `POST /agents/:id/build` | Docker build + push |
| 部署 | `POST /agents/:id/deploy` | 创建 Service + Deployment |
| 发布 | `POST /agents/:id/publish` | Deploy + 创建 Ingress → status=published |
| 下线 | `POST /agents/:id/unpublish` | 删除 Ingress → Service → Deployment |
| 删除 | `DELETE /agents/:id` | K8s 全清理 + Docker + MinIO + MySQL |

## 8. 常见问题

### Q: 如何从 Sandbox 模式迁移到 Deployment 模式？

1. 下线所有已发布 Agent (`unpublish`)
2. 设置 `DEPLOY_METHOD=deployment`
3. 重新发布 Agent (`publish`)

### Q: Deployment 模式下 Pod 命名规则？

Pod 名称格式: `{deployment-name}-{replicaset-hash}-{pod-hash}`
通过 `app={name}` 标签筛选。

### Q: 模板文件语法错误导致启动失败？

检查 `DEPLOY_TEMPLATE_DIR` 下的 `.yaml.tmpl` 文件，确保 Go template 语法正确。
使用 `go template` 语法，参考 [text/template](https://pkg.go.dev/text/template)。

### Q: CHECKPOINT_MYSQL_DSN 为空时 Pod 崩溃？

agent-framework 默认 DSN 指向 `127.0.0.1:3307`，Pod 内无法访问宿主机。
必须设置有效的 `checkpoint_dsn`，指向 Docker 网关地址：

```
mysql+asyncmy://agent_manager:Agent%40Manager2026@172.20.0.1:3307/agent_manager_test
```

密码中 `@` 需 URL 编码为 `%40`。

## 9. 架构图

```
┌─────────────────────────────────────────────────────┐
│                    DeployService                     │
├─────────────────────────────────────────────────────┤
│  deployMethod ──→ 决定创建 Deployment 还是 Sandbox   │
│  templateEngine ──→ 渲染 Deployment/Service/Ingress  │
└────────────────────┬────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────┐
│                 K8sClient (interface)                │
│  ApplyYAML / DeleteResource / GetPodStatus ...       │
├──────────────────────┬──────────────────────────────┤
│  KubectlClient       │  K8sAPIClient                 │
│  (exec kubectl CLI)  │  (client-go SDK)              │
│  ── kubeconfig 透传  │  ── rest.Config 认证          │
│  ── namespace 参数   │  ── dynamic Client 操作       │
└──────────────────────┴──────────────────────────────┘
```
