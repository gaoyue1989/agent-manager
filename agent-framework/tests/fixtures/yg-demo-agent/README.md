# 瑶光打车服务 Agent (yg-demo-agent)

基于 **agent-framework** 创建的测试 Agent，集成瑶光演示 Demo (yg_demo) 命名空间下的技能和工具。

## 目录结构

```
yg-demo-agent/
├── AGENTS.md                           # Agent 配置 (OAF v0.8.0)
├── skills/
│   ├── real_time_ride_hailing/         # 打车服务技能
│   │   ├── SKILL.md
│   │   └── scripts/tool.py
│   └── yg_wo_are_you/                  # 自我介绍技能
│       ├── SKILL.md
│       └── scripts/tool.py
├── mcp-configs/
│   └── yg_demo_tools/                  # MCP 工具服务
│       ├── ActiveMCP.json              # 工具选择配置
│       ├── config.yaml                 # 连接配置
│       └── mcp_servers.py              # MCP SSE Server
└── custom-tools/                       # 自定义工具 (空)
```

## 技能列表

| 技能 | 描述 |
|------|------|
| real_time_ride_hailing | 打车服务管理 - 预约用车、查询订单 |
| yg_wo_are_you | 自我介绍 - 回答"你是谁" |

## MCP 工具列表

| 工具 | HTTP 方法 | 描述 |
|------|----------|------|
| address_search | GET | 地址查询 - 校验出发地/目的地 |
| cost_estimate | POST | 报价查询 - 查询车型及价格 |
| save_order | POST | 保存预订单 |
| order_create | POST | 发送订单确认 |

## 启动方式

### 1. 启动 MCP SSE Server

```bash
cd agent-framework
uvicorn tests.fixtures.yg-demo-agent.mcp-configs.yg_demo_tools.mcp_servers:app --host 0.0.0.0 --port 8820
```

或直接运行:
```bash
python tests/fixtures/yg-demo-agent/mcp-configs/yg_demo_tools/mcp_servers.py
```

### 2. 启动 Agent 服务

```bash
cd agent-framework
AGENT_CONFIG_DIR=tests/fixtures/yg-demo-agent python -m uvicorn server.app:create_app --factory --host 0.0.0.0 --port 8100
```

### 3. 访问调试页面

```
http://localhost:8100/debug
```

## MCP 协议

- **传输层**: SSE (Server-Sent Events)
- **协议层**: JSON-RPC 2.0
- **端点**: `http://localhost:8820/sse`

### 支持的 JSON-RPC 方法

| 方法 | 说明 |
|------|------|
| initialize | 初始化连接 |
| tools/list | 获取工具列表 |
| tools/call | 调用工具 |
| ping | 心跳 |

## 工具调用示例

### 地址查询

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "address_search",
    "arguments": {"keyword": "深圳平安金融中心"}
  }
}
```

### 报价查询

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "cost_estimate",
    "arguments": {
      "fromLat": 114.095586,
      "fromLng": 22.542896,
      "toLat": 113.93877,
      "toLng": 22.579709,
      "carType": ["1", "2", "3", "4"],
      "orderType": "1"
    }
  }
}
```

## 数据来源

工具 API 配置来自 AI Agent 管理系统:
- **URL**: https://ai-mgr.cenxt.cn
- **命名空间**: yg_demo (瑶光演示demo)
- **技能数**: 2
- **工具数**: 4
