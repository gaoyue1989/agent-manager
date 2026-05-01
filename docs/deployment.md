# Agent Manager 环境部署总文档

**日期**: 2026-05-01  
**版本**: v1.0  
**状态**: ✅ 环境搭建完成

---

## 一、系统架构

```
┌─────────────────────────────────────────────────────────────┐
│              开发机 (Ubuntu 24.04 / 4C8G)                    │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────────┐ │
│  │  Dify    │  │ GreatSQL │  │  MinIO   │  │ K8s (Kind)  │ │
│  │ (Docker) │  │ (3307)   │  │ (9000)   │  │             │ │
│  │          │  │          │  │          │  │ agent-      │ │
│  │ nginx:80 │  │ agent_   │  │ agent-   │  │ sandbox     │ │
│  │ mysql:   │  │ manager   │  │ manager  │  │ Controller  │ │
│  │  3306    │  │  DB       │  │  Bucket  │  │ Sandbox CRD │ │
│  └──────────┘  └──────────┘  └──────────┘  └─────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## 二、端口规划

| 端口 | 服务 | 说明 |
|------|------|------|
| 80 | Dify Nginx | Dify Web 入口 |
| 443 | Dify Nginx | Dify HTTPS |
| 2222 | Dify agentbox | SSH |
| 3306 | Docker MySQL | Dify 数据库 |
| 3307 | GreatSQL | 主数据库 |
| 5001 | Local Registry | Docker 镜像中转 |
| 5003 | Dify Plugin | Dify 插件 |
| 9000 | MinIO API | 对象存储 API |
| 9001 | MinIO Console | 对象存储控制台 |
| 30080 | K8s NodePort | 预留，K8s 入口 HTTP |
| 30443 | K8s NodePort | 预留，K8s 入口 HTTPS |
| 39921 | K8s API | K8s API Server |

## 三、组件版本

| 组件 | 版本 | 运行方式 |
|------|------|---------|
| Kind | v0.27.0 | 宿主机二进制 |
| Kubernetes | v1.32.2 | Kind 容器 |
| kubectl | v1.28.2 | 宿主机 |
| agent-sandbox | v0.4.3 | K8s Deployment |
| containerd | v2.0.2 | Kind 节点内 |
| GreatSQL | 8.0.32-27 | 宿主机 systemd |
| MinIO | 2025-04-08 | Docker 容器 |

## 四、数据库信息

### GreatSQL (端口3307)

| 数据库 | 用户 | 密码 | 用途 |
|--------|------|------|------|
| dify | dify | - | Dify 业务 (勿动) |
| dify_alembic_test | - | - | Dify 测试 (勿动) |
| dify_orm_test | - | - | Dify 测试 (勿动) |
| dify_plugin | - | - | Dify 插件 (勿动) |
| **agent_manager** | **agent_manager** | **Agent@Manager2026** | **Agent Manager** |

### MinIO (端口9000/9001)

| Bucket | 用途 |
|--------|------|
| dify | Dify 文件存储 (勿动) |
| **agent-manager** | Agent Manager 代码/文件存储 |

## 五、K8s 集群信息

```
集群名称: agent-manager
上下文:   kind-agent-manager
节点:     agent-manager-control-plane (1个)
网络:     172.20.0.0/16 (Kind bridge)
Pod CIDR: 10.244.0.0/16
存储类:   standard (local-path)
```

### agent-sandbox 组件

```
命名空间:   agent-sandbox-system
Deployment: agent-sandbox-controller (1/1 Running)
CRDs:
  - sandboxes.agents.x-k8s.io
  - sandboxclaims.extensions.agents.x-k8s.io
  - sandboxtemplates.extensions.agents.x-k8s.io
  - sandboxwarmpools.extensions.agents.x-k8s.io
```

## 六、镜像管理策略

所有 Agent 镜像通过本地 Docker Registry (`172.20.0.1:5001`) 中转：

```
docker build  →  tag/push localhost:5001  →  Kind 从 172.20.0.1:5001 拉取
```

## 七、快速验证命令

```bash
# 验证 K8s
kubectl get nodes
kubectl get pods -A

# 验证 agent-sandbox
kubectl get crd | grep sandbox
kubectl get pods -n agent-sandbox-system

# 验证 MySQL
/usr/local/greatsql/bin/mysql --socket=/data/greatsql/mysql.sock -u agent_manager -pAgent@Manager2026 -e "SELECT 1"

# 验证 MinIO
curl -s http://localhost:9001

# 验证本地 Registry
curl -s http://localhost:5001/v2/_catalog
```

## 八、子文档索引

| 文档 | 路径 |
|------|------|
| K8s 部署记录 | `docs/01-k8s-deployment.md` |
| agent-sandbox 部署记录 | `docs/02-agent-sandbox-deployment.md` |
| MySQL 准备记录 | `docs/03-mysql-setup.md` |
| MinIO 准备记录 | `docs/04-minio-setup.md` |
| 环境总文档 (本文) | `docs/deployment.md` |

## 九、注意事项

1. **不要删除/修改** dify 系列数据库和 bucket
2. **Kind 集群的网络**：Kind 节点 IP 为 `172.20.0.2`，通过 `172.20.0.1` 访问宿主机
3. **镜像拉取**：Custom 镜像需先推送到 `localhost:5001`，在 K8s 中引用 `172.20.0.1:5001/xxx`
4. **资源限制**：当前 4C8G，Dify 全家桶 + K8s 需要合理分配
5. **持久化**：Kind 节点删除后数据丢失，重要数据应挂载宿主机目录
