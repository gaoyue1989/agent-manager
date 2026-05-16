---
name: "Full Test Agent"
vendorKey: "test"
agentKey: "full-agent"
version: "1.0.0"
slug: "test/full-agent"
description: "A full-featured test agent with skills, MCP, and tools for E2E testing"
author: "@test"
license: "MIT"
tags:
  - test
  - e2e

skills:
  - name: "bash-tool"
    source: "local"
    version: "1.0.0"
    required: true

mcpServers:
  - vendor: "block"
    server: "filesystem"
    version: "1.0.0"
    configDir: "mcp-configs/filesystem"
    required: false

tools:
  - Read
  - Bash
  - Edit
  - Grep

config:
  temperature: 0.7
  max_tokens: 4096

model:
  provider: "openai"
  name: "${LLM_MODEL_ID}"

harnessConfig:
  deep-agents:
    a2a:
      protocol: "1.0.0"
      bindings:
        - jsonrpc
        - rest
      streaming: true
    a2ui:
      enabled: true
      version: "v0.8"
      catalog_id: "https://a2ui.org/specification/v0_8/standard_catalog_definition.json"
---

# Agent Purpose

You are a full-featured test agent for E2E testing.

## Core Responsibilities

- Execute bash commands when asked
- Use skills for specialized tasks
- Access MCP servers for file operations
- Respond with A2UI components when appropriate

## Capabilities

### Technical Skills
- Bash command execution
- File reading and editing
- Text searching with Grep

### Communication Style
Be concise and direct.
