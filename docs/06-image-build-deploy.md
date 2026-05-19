# 镜像构建 & agent-sandbox 部署

**日期**: 2026-05-19
**执行者**: opencode

## 1. 概述

验证从生成的 DeepAgents 代码 → Docker 镜像构建 → agent-sandbox 部署的完整链路。支持两种运行模式。

## 2. 运行模式

### 2.1 构建模式 (Build Mode)

生成代码 → 构建自定义镜像 → K8s 部署。

#### 镜像构建

| 属性 | 值 |
|------|-----|
| 镜像名 | `{registry}/agent-{id}:v{version}` |
| 基础镜像 | `{registry}/agent-base:latest` (替代 `python:3.12-slim`) |
| 构建加速 | 基础镜像预装所有 pip 依赖，Agent 镜像跳过 pip install |
| 首次构建 | ~60s (含基础镜像构建) |
| 后续构建 | ~5s (缓存命中) |

#### Sandbox CRD (构建模式)

```yaml
apiVersion: agents.x-k8s.io/v1alpha1
kind: Sandbox
metadata:
  name: agent-{id}
spec:
  podTemplate:
    spec:
      containers:
      - name: agent
        image: 172.20.0.1:5001/agent-{id}:v{version}
        ports:
        - containerPort: 8000
        env:
        - name: LLM_API_KEY
          value: "sk-..."
        - name: LLM_MODEL
          value: "qwen3.6-plus"
        - name: LLM_ENDPOINT
          value: "https://dashscope.aliyuncs.com/compatible-mode/v1"
```

### 2.2 挂载模式 (Mount Mode)

使用预构建 `agent-framework` 镜像，配置通过 ConfigMap 挂载。

#### 优势

- 无需构建镜像，部署速度更快
- 使用统一 `agent-framework` 镜像，便于批量更新
- 配置 (AGENTS.md + skills + mcp-configs) 通过 ConfigMap 挂载到 `/config`
- LLM API Key 通过 K8s Secret 注入
- 支持独立的 Checkpoint DSN

#### Sandbox CRD (挂载模式)

```yaml
apiVersion: agents.x-k8s.io/v1alpha1
kind: Sandbox
metadata:
  name: agent-{id}
spec:
  podTemplate:
    spec:
      containers:
      - name: agent
        image: 172.20.0.1:5001/agent-framework:latest
        ports:
        - containerPort: 8100
        envFrom:
        - secretRef:
            name: agent-{id}-secret
        env:
        - name: LLM_MODEL_ID
          value: "..."
        - name: LLM_BASE_URL
          value: "..."
        - name: CHECKPOINT_MYSQL_DSN
          value: "mysql+asyncmy://..."
        volumeMounts:
        - name: config
          mountPath: /config
      volumes:
      - name: config
        configMap:
          name: agent-{id}-config
```

#### 镜像 Registry 自动补全

挂载模式下，若镜像名不含 `/`，自动补全 registry 前缀：

```go
// deploy.go:210-212
if agent.RuntimeMode == model.RuntimeModeMount && s.registry != "" && !strings.Contains(image, "/") {
    image = fmt.Sprintf("%s/%s", s.registry, image)
}
```

如 `agent-framework:latest` → `172.20.0.1:5001/agent-framework:latest`

#### 重新发布注意事项

挂载模式重新发布时必须走 `DeployWithMount` 而非 `Deploy`。`Publish` 方法已修复为挂载模式始终调用 `DeployWithMount`。

## 3. 部署结果

| 资源 | 构建模式 | 挂载模式 |
|------|---------|---------|
| Sandbox CRD | ✅ | ✅ |
| ConfigMap | — | ✅ agent-{id}-config |
| Secret | — | ✅ agent-{id}-secret |
| Pod | ✅ Running (1/1) | ✅ Running (1/1) |
| Service | ✅ NodePort 8000 | ✅ ClusterIP 8100 |
| Ingress | ✅ /agent/{id}/ | ✅ /agent/{id}/ |

## 4. 遇到的问题与解决

| 问题 | 原因 | 解决 |
|------|------|------|
| Pod ImagePullBackOff | 镜像未推送到 K8s 可访问的 registry | 推送到 `172.20.0.1:5001`，配置 containerd `insecure_skip_verify` |
| 挂载模式重发布用错镜像 | Publish 在 status≠draft 时走了 Deploy (构建模式) | 修改 Publish 逻辑：挂载模式始终走 DeployWithMount |
| Debug 页面 API 404 | `BASE=window.location.origin` 未考虑 `/agent/{id}/` 前缀 | 改为从 pathname 提取路径前缀 |
| Chat 调用 `/chat` 404 | agent-framework 无 `/chat` 端点 | 改用 JSON-RPC `message/send` 发到 `POST /` |
| Pod CrashLoopBackOff | CHECKPOINT_MYSQL_DSN 为空或 `127.0.0.1` 不可达 | 设置 DSN 并使用 `172.20.0.1` 作为 MySQL 地址 |
| 展示的 endpoint URL 错误 | `INGRESS_HOST` 默认 `localhost` | 设置为 `127.0.0.1:8911` |

## 5. 关键配置

| 项 | 值 |
|------|-----|
| Docker Registry | `172.20.0.1:5001` (HTTP) |
| Kind 网络 | `172.20.0.1` 访问宿主机服务 |
| docker-proxy | `socat TCP-LISTEN:80 TCP:nginx:80` |
| Host nginx | `:8911` → frontend:3000 / backend:8080 / ingress:30080 |
| agent-framework 端口 | **8100** |
| 构建模式端口 | 8000 |
