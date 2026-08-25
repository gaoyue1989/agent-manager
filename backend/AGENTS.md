# Backend — AGENTS.md

## 模块概述

OAF 服务发布平台管理后端（Go + Gin + GORM + client-go）。同一 HTTP 进程暴露 REST(`/api/v1`) 与 MCP(streamableHttp `/mcp`) 两个协议门面，业务逻辑在 `internal/service.Core`（协议无关）。

核心链路：上传 OAF zip 包 → 校验解包落共享 PVC → 发布（Deployment+Service+Ingress，envFrom ConfigMap，包 subPath 只读挂 /config + 独立可写工作区卷 /workspace）→ 就绪后拉 `/.well-known/agent-card.json` 注册入库 → 列表/状态/重新发布/下线/删除。

## 目录结构

```
backend/
├── cmd/server/main.go          # 入口：装配 REST + MCP + K8s + DB
├── config/config.go            # 环境变量（MYSQL_DSN 必填无默认）
├── Dockerfile                  # golang:1.26 多阶段构建
├── internal/
│   ├── handler/                # Gin 薄层（respond/middleware/router）
│   ├── mcpsrv/server.go        # MCP 工具门面（go-sdk v1.3.1 streamableHttp）
│   ├── service/                # 业务层：package/publish/register/status/env
│   ├── k8s/                    # client-go typed 封装 + 对象构造（纯函数可测）
│   │   └── k8sfake/            # 测试用 fake Client 实现
│   ├── store/                  # GORM 模型（oaf_packages/services/service_events）+ PVC 文件操作
│   └── oaf/oaf.go              # OAG v0.8.0 frontmatter 解析校验（宽松模式 warnings）
```

## 启动

```bash
make test    # go vet + go test ./...
make image && make kind-load
kubectl apply -f manifests/platform.yaml manifests/platform-ingress.yaml manifests/frontend.yaml
```

本地开发：`KUBECONFIG=~/.kube/config DATA_ROOT=/tmp/oaf-data MYSQL_DSN=... go run ./cmd/server`

## 关键约定

- 平台保留键：AGENT_CONFIG_DIR / AGENT_WORKSPACE_DIR / SERVER_HOST / SERVER_PORT（用户 env 出现即 400）
- env 全量覆盖语义（PATCH /services/:id/env），上限 64 键 × 32KB
- 服务状态机：created→deploying→running|register_failed|deploy_failed；stopped/error 可再 publish
- WaitReady 要求完整滚动更新完成（generation 对齐 + updatedReplicas 达标 + unavailable=0），防止注册打到旧 Pod
- 业务 Ingress 注入 proxy-read/send-timeout=3600（A2A blocking 长对话必需）
- zip 安全校验：20MB/2000 条目/100MB 解压上限、zip-slip 与符号链接拒绝、文件最低 0644（业务 Pod 非 root 需可读）
