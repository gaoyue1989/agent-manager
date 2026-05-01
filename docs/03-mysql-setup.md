# MySQL 数据库准备记录

**日期**: 2026-05-01  
**执行者**: opencode

## 1. 概述

在 GreatSQL 8.0.32-27 (端口3307) 中创建 `agent_manager` 数据库，不影响已有的 `dify` 系列数据库。

## 2. 操作

### 2.1 创建数据库和用户

```sql
CREATE DATABASE IF NOT EXISTS agent_manager CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'agent_manager'@'%' IDENTIFIED BY 'Agent@Manager2026';
GRANT ALL PRIVILEGES ON agent_manager.* TO 'agent_manager'@'%';
FLUSH PRIVILEGES;
```

### 2.2 连接验证

```bash
mysql -u agent_manager -pAgent@Manager2026 --socket=/data/greatsql/mysql.sock -e "SELECT DATABASE();"
# 输出: agent_manager
```

## 3. 配置信息

| 参数 | 值 |
|------|-----|
| 数据库名 | `agent_manager` |
| 字符集 | utf8mb4 |
| 排序规则 | utf8mb4_0900_ai_ci |
| 用户名 | `agent_manager` |
| 密码 | `Agent@Manager2026` |
| 主机 | `%` (任意主机) |
| 数据库地址 | `127.0.0.1:3307` |

## 4. 安全性说明

- 现有 `dify`, `dify_alembic_test`, `dify_orm_test`, `dify_plugin` 库未受影响
- `agent_manager` 用户仅对 `agent_manager` 库有权限
- 生产环境建议：
  - 限制 `agent_manager` 用户的主机为具体 IP
  - 使用更强的密码
  - 启用 SSL 连接

## 5. 连接字符串

Go (GORM):
```go
dsn := "agent_manager:Agent@Manager2026@tcp(127.0.0.1:3307)/agent_manager?charset=utf8mb4&parseTime=True&loc=Local"
```
