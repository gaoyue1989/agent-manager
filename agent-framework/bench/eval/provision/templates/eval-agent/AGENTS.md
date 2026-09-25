---
name: "Eval Env Agent"
vendorKey: "agentmanager"
agentKey: "eval-env-agent"
version: "1.0.0"
slug: "agentmanager/eval-env-agent"
description: "评测环境按需供给的最小 OAF 基座（无 --oaf-base 时使用）"
author: "@bench-eval"
license: "MIT"

mcpServers:
  - vendor: "agentmanager"
    server: "platform-publisher"
    version: "1.0.0"
    configDir: "mcp-configs/platform"

config:
  require_confirmation: false
  permission:
    mode: default
---

# Eval Env Agent

评测专用最小 Agent：按供给的工具完成对话、工具调用与人工确认流评测，不做平台业务操作。
