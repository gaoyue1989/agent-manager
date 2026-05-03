# Nginx Ingress Controller 部署文档

**日期**: 2026-05-03  
**版本**: v1.0  
**状态**: 待部署

---

## 一、问题背景

### 1.1 当前状态

Agent Manager 已实现 Ingress 创建逻辑：
- `backend/internal/k8s/sandbox.go` 包含 `CreateIngress()` / `DeleteIngress()` 方法
- Agent 发布时自动创建 Ingress，路径 `/agent/{id}`
- Ingress 配置 `ingressClassName: nginx`

### 1.2 问题描述

**Nginx Ingress Controller 未部署**，导致：
- Ingress 资源创建成功，但无 Controller 处理
- 无法通过 `http://localhost/agent/{id}` 访问 Agent API
- 只能通过 `kubectl port-forward` 临时测试

### 1.3 验证命令

```bash
# 检查 Ingress Controller (当前无输出)
kubectl get svc -A | grep ingress
kubectl get pods -A | grep ingress

# 检查已创建的 Ingress 资源
kubectl get ingress -n default
```

---

## 二、解决方案

### 2.1 部署 Nginx Ingress Controller (Kind)

Kind 集群需要使用 Kind 特定的 Ingress 部署方式：

**方案 A: 使用 Kind 内置的 Ingress 配置 (推荐)**

```bash
# 1. 部署 Nginx Ingress Controller
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.10.1/deploy/static/provider/kind/deploy.yaml

# 2. 等待 Controller 就绪
kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=90s
```

**方案 B: 使用 Manifest 文件**

创建 `sandbox/ingress-nginx.yaml`:

```yaml
---
apiVersion: v1
kind: Namespace
metadata:
  labels:
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
  name: ingress-nginx
---
apiVersion: v1
automountServiceAccountToken: true
kind: ServiceAccount
metadata:
  labels:
    app.kubernetes.io/component: controller
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
  name: ingress-nginx
  namespace: ingress-nginx
---
apiVersion: v1
kind: Service
metadata:
  labels:
    app.kubernetes.io/component: controller
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
  name: ingress-nginx-controller
  namespace: ingress-nginx
spec:
  ipFamilies:
  - IPv4
  ipFamilyPolicy: SingleStack
  ports:
  - appProtocol: http
    name: http
    port: 80
    protocol: TCP
    targetPort: http
  - appProtocol: https
    name: https
    port: 443
    protocol: TCP
    targetPort: https
  selector:
    app.kubernetes.io/component: controller
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
  type: LoadBalancer
---
apiVersion: apps/v1
kind: Deployment
metadata:
  labels:
    app.kubernetes.io/component: controller
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
  name: ingress-nginx-controller
  namespace: ingress-nginx
spec:
  minReadySeconds: 0
  revisionHistoryLimit: 10
  selector:
    matchLabels:
      app.kubernetes.io/component: controller
      app.kubernetes.io/instance: ingress-nginx
      app.kubernetes.io/name: ingress-nginx
  template:
    metadata:
      labels:
        app.kubernetes.io/component: controller
        app.kubernetes.io/instance: ingress-nginx
        app.kubernetes.io/name: ingress-nginx
    spec:
      containers:
      - args:
        - --controller-class=k8s.io/ingress-nginx
        - --ingress-class=nginx
        - --configmap=$(POD_NAMESPACE)/ingress-nginx-controller
        - --tcp-services-configmap=$(POD_NAMESPACE)/ingress-nginx-tcp
        - --udp-services-configmap=$(POD_NAMESPACE)/ingress-nginx-udp
        - --validating-webhook=:8443
        - --validating-webhook-certificate=/usr/local/certificates/tls.crt
        - --validating-webhook-key=/usr/local/certificates/tls.key
        env:
        - name: POD_NAME
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        - name: POD_NAMESPACE
          valueFrom:
            fieldRef:
              fieldPath: metadata.namespace
        - name: LD_PRELOAD
          value: /usr/local/lib/libmimalloc.so
        image: registry.k8s.io/ingress-nginx/controller:v1.10.1
        imagePullPolicy: IfNotPresent
        lifecycle:
          preStop:
            exec:
              command:
              - /wait-shutdown
        livenessProbe:
          failureThreshold: 5
          httpGet:
            path: /healthz
            port: 10254
            scheme: HTTP
          initialDelaySeconds: 10
          periodSeconds: 10
          successThreshold: 1
          timeoutSeconds: 1
        name: controller
        ports:
        - containerPort: 80
          name: http
          protocol: TCP
        - containerPort: 443
          name: https
          protocol: TCP
        - containerPort: 8443
          name: webhook
          protocol: TCP
        readinessProbe:
          failureThreshold: 3
          httpGet:
            path: /healthz/ready
            port: 10254
            scheme: HTTP
          initialDelaySeconds: 0
          periodSeconds: 1
          successThreshold: 1
          timeoutSeconds: 1
        resources:
          requests:
            cpu: 100m
            memory: 90Mi
        securityContext:
          allowPrivilegeEscalation: true
          capabilities:
            add:
            - NET_BIND_SERVICE
            drop:
            - ALL
          runAsNonRoot: true
          runAsUser: 101
        volumeMounts:
        - mountPath: /usr/local/certificates/
          name: webhook-cert
          readOnly: true
      dnsPolicy: ClusterFirst
      nodeSelector:
        kubernetes.io/os: linux
      serviceAccountName: ingress-nginx
      terminationGracePeriodSeconds: 300
      volumes:
      - name: webhook-cert
        secret:
          secretName: ingress-nginx-admission
```

### 2.2 配置 NodePort (可选)

如需通过 NodePort 访问，修改 Service:

```bash
# 修改 Service 类型为 NodePort
kubectl patch svc ingress-nginx-controller -n ingress-nginx -p '{"spec":{"type":"NodePort"}}'

# 查看分配的端口
kubectl get svc ingress-nginx-controller -n ingress-nginx
```

### 2.3 Kind 集群特殊配置

如果 Kind 集群创建时未配置 extraPortMapping，需要重建集群：

```yaml
# kind-config.yaml (参考)
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
  - containerPort: 80
    hostPort: 80
    protocol: TCP
  - containerPort: 443
    hostPort: 443
    protocol: TCP
```

**当前集群检查**:
```bash
# 检查 Kind 节点端口映射
docker ps --filter "name=agent-manager-control-plane" --format "table {{.Ports}}"
```

---

## 三、部署步骤

### 3.1 执行部署

```bash
# Step 1: 部署 Nginx Ingress Controller
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.10.1/deploy/static/provider/kind/deploy.yaml

# Step 2: 等待 Controller 就绪
kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=120s

# Step 3: 验证部署
kubectl get pods -n ingress-nginx
kubectl get svc -n ingress-nginx
```

### 3.2 验证 IngressClass

```bash
# 检查 IngressClass
kubectl get ingressclass

# 预期输出:
# NAME    CONTROLLER             PARAMETERS   AGE
# nginx   k8s.io/ingress-nginx   <none>       XXs
```

### 3.3 测试 Agent Ingress

```bash
# 检查 Agent Ingress
kubectl get ingress -n default

# 测试访问 (假设 agent-23 已发布)
curl http://localhost/agent/23/chat

# 或通过 NodePort (如果配置了)
curl http://localhost:30080/agent/23/chat
```

---

## 四、验证清单

| 检查项 | 命令 | 预期结果 |
|--------|------|---------|
| Ingress Controller Pod | `kubectl get pods -n ingress-nginx` | STATUS=Running |
| Ingress Controller Service | `kubectl get svc -n ingress-nginx` | TYPE=LoadBalancer/NodePort |
| IngressClass | `kubectl get ingressclass` | NAME=nginx |
| Agent Ingress | `kubectl get ingress -n default` | agent-{id}-ingress |
| Agent 访问 | `curl http://localhost/agent/{id}` | HTTP 200 |

---

## 五、故障排查

### 5.1 Ingress Controller 无法启动

```bash
# 检查 Pod 日志
kubectl logs -n ingress-nginx -l app.kubernetes.io/component=controller

# 检查事件
kubectl get events -n ingress-nginx --sort-by='.lastTimestamp'
```

### 5.2 Ingress 404 Not Found

```bash
# 检查 Ingress 配置
kubectl describe ingress agent-23-ingress -n default

# 检查 Service 是否存在
kubectl get svc agent-23-svc -n default

# 检查 Service Endpoint
kubectl get endpoints agent-23-svc -n default
```

### 5.3 Kind 端口映射问题

如果 `curl http://localhost/agent/{id}` 无响应：

```bash
# 方案 1: 使用 kubectl port-forward
kubectl port-forward svc/ingress-nginx-controller -n ingress-nginx 80:80 &

# 方案 2: 使用 Kind 节点 IP
KIND_IP=$(docker inspect agent-manager-control-plane --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
curl http://$KIND_IP/agent/23/chat
```

---

## 六、更新 deployment.md

在 `docs/deployment.md` 中追加：

### 6.1 端口规划

| 端口 | 服务 | 说明 |
|------|------|------|
| 80 | Nginx Ingress | K8s Ingress HTTP 入口 |
| 443 | Nginx Ingress | K8s Ingress HTTPS 入口 |
| 30080 | NodePort (可选) | K8s Ingress NodePort HTTP |
| 30443 | NodePort (可选) | K8s Ingress NodePort HTTPS |

### 6.2 组件版本

| 组件 | 版本 | 运行方式 |
|------|------|---------|
| Nginx Ingress Controller | v1.10.1 | K8s Deployment |

### 6.3 验证命令

```bash
# 验证 Ingress Controller
kubectl get pods -n ingress-nginx
kubectl get svc -n ingress-nginx

# 验证 Agent Ingress
kubectl get ingress -n default
curl http://localhost/agent/{id}/chat
```

---

## 七、回滚方案

如需删除 Ingress Controller:

```bash
kubectl delete -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.10.1/deploy/static/provider/kind/deploy.yaml
```

---

## 八、参考文档

- [Kind Ingress 部署指南](https://kind.sigs.k8s.io/docs/user/ingress/)
- [Nginx Ingress Controller 文档](https://kubernetes.github.io/ingress-nginx/)
- [Agent Manager 设计文档](docs/13-feature-plan.md#2-k8s-ingress-agent-对外暴露--注册删除)
