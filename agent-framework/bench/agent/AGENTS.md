---
name: "Bench Agent"
vendorKey: "agentmanager"
agentKey: "bench-agent"
version: "1.0.0"
slug: "agentmanager/bench-agent"
description: "并发压测专用 agent（精简提示词，配合 mock LLM/MCP）"
author: "@bench"
license: "MIT"

mcpServers:
  - vendor: "bench"
    server: "bench"
    version: "1.0.0"
    configDir: "mcp-configs/bench"

config:
  require_confirmation: false
  permission:
    mode: bypass
---

# 压测助手

你是并发压测专用 agent。收到指令后直接执行所需操作并简洁回复，不要追问。
