# MinIO Bucket 准备记录

**日期**: 2026-05-01  
**执行者**: opencode

## 1. 概述

在现有 MinIO 实例 (端口9000/9001, 用户 minioadmin) 中创建 `agent-manager` bucket，不与 Dify 的 bucket 冲突。

## 2. 操作

```bash
# 创建 bucket
docker run --rm --network host minio/mc mb myminio/agent-manager

# 验证
docker run --rm --network host minio/mc ls myminio/
```

## 3. 配置信息

| 参数 | 值 |
|------|-----|
| MinIO 端点 | `http://127.0.0.1:9000` |
| 控制台 | `http://127.0.0.1:9001` |
| Access Key | `minioadmin` |
| Secret Key | `minioadmin` |
| Bucket 名称 | `agent-manager` |
| 已有存储桶 | `agent-manager`, `dify` |

## 4. Bucket 存储路径规划

```
agent-manager/
├── agents/
│   └── {agent_id}/
│       └── {version}/
│           ├── agent.py          # 生成的 Agent 脚本
│           ├── Dockerfile        # Docker 构建文件
│           └── requirements.txt  # Python 依赖
└── builds/
    └── {agent_id}/
        └── {version}/
            └── build.log         # 构建日志
```

## 5. 连接示例

Go (minio-go SDK):
```go
client, _ := minio.New("127.0.0.1:9000", &minio.Options{
    Creds: credentials.NewStaticV4("minioadmin", "minioadmin", ""),
    Secure: false,
})
```

Python (boto3):
```python
import boto3
s3 = boto3.client("s3",
    endpoint_url="http://127.0.0.1:9000",
    aws_access_key_id="minioadmin",
    aws_secret_access_key="minioadmin",
)
```

## 6. 安全性说明

- 现有 Dify bucket 未受影响
- `agent-manager` bucket 独立，权限隔离
- 生产环境建议：
  - 为 agent-manager 创建专用 MinIO 用户
  - 限制 bucket 访问策略
  - 使用独立 MinIO 实例
