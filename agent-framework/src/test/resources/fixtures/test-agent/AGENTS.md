---
name: test-agent
vendorKey: acme
agentKey: test-agent
version: 1.0.0
slug: acme-test-agent
description: A test agent for OAF config loader tests
author: Agent Manager Team
license: MIT
tags:
  - test
  - oaf
  - fixture
skills:
  - name: bash-tool
    source: local
    version: "1.0.0"
    required: true
    description: Execute bash commands
    allowedTools:
      - Bash
      - Execute
mcpServers:
  - vendor: weather
    server: weather-service
    version: "1.0.0"
    configDir: mcp-configs/weather
    required: true
tools:
  - Read
  - Bash
  - Edit
model:
  provider: openai
  name: gpt-4
config:
  temperature: 0.7
  max_tokens: 4096
memory:
  type: editable
---

# Test Agent

This is a test agent used for OAF config loader validation.
