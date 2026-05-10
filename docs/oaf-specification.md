# Open Agent Format (OAF) Specification v0.8.0

**Version:** 0.8.0  
**Date:** 2026-01-21  
**Status:** Draft  
**Source:** https://openagentformat.com/spec.html

## Overview

The Open Agent Format (OAF) is an open, interoperable format for defining AI agents with their instructions, compositions, and configurations. OAF is designed to work across multiple agent harnesses while maintaining a single canonical representation.

### Design Principles

1. **Filesystem as Source of Truth** - The directory structure and files define the agent
2. **Interoperable** - Works across Claude Code, Goose, Deep Agents, Letta, and other harnesses
3. **Composable** - Agents reference skills, packs, weblets, MCPs, and other agents
4. **Version-aware** - Supports semantic versioning and version history
5. **Human-readable** - Markdown for instructions, YAML for metadata
6. **Harness-agnostic** - Core format independent of any specific harness

---

## Directory Structure

An agent in OAF is a **directory** containing multiple files and subdirectories.

### Complete Structure (with all optional directories)

```
agent-name/
├── AGENTS.md                    # Main manifest (required)
├── README.md                    # Human documentation (generated if absent)
├── LICENSE                      # License file (generated if absent)
│
├── versions/                    # Version history (optional)
│   ├── v1.0.0/
│   │   └── AGENTS.md
│   ├── v1.1.0/
│   │   └── AGENTS.md
│   └── v2.0.0/
│       └── AGENTS.md
│
├── skills/                      # Local/custom skills (optional)
│   ├── custom-skill-1/
│   │   ├── SKILL.md            # Skill manifest (required)
│   │   ├── resources/          # Data files, configs (optional)
│   │   ├── scripts/            # Executable scripts (optional)
│   │   └── assets/             # Images, diagrams (optional)
│   └── custom-skill-2/
│       └── SKILL.md
│
├── mcp-configs/                 # MCP server configurations (optional)
│   ├── filesystem/
│   │   ├── ActiveMCP.json      # Tool subset selection
│   │   └── config.yaml         # Connection config
│   ├── database/
│   │   ├── ActiveMCP.json
│   │   └── config.yaml
│   └── stripe-api/
│       ├── ActiveMCP.json
│       └── config.yaml
│
├── examples/                    # Usage examples (optional)
├── tests/                       # Test scenarios (optional)
├── docs/                        # Additional documentation (optional)
└── assets/                      # Agent-level media files (optional)
```

### Minimal Structure

At minimum, an OAF agent requires only:

```
agent-name/
└── AGENTS.md
```

---

## AGENTS.md Format

The `AGENTS.md` file is the primary manifest for an OAF agent.

### Structure

```yaml
---
# === IDENTITY (Required) ===

name: "Display Name"
vendorKey: "vendor-namespace"
agentKey: "agent-identifier"
version: "1.0.0"
slug: "vendor-namespace/agent-identifier"

# === METADATA (Required) ===

description: "Brief description of agent purpose and capabilities"
author: "@vendor-handle"
license: "MIT"
tags: ["tag1", "tag2", "tag3"]

# === COMPOSITION (Optional) ===

skills:
  - name: "web-search"
    source: "https://anthropic.com/.well-known/skills/web-search"
    version: "1.2.0"
    required: true
  - name: "custom-tool"
    source: "local"
    version: "1.0.0"
    required: false

packs:
  - vendor: "langchain"
    pack: "python-dev-tools"
    version: "1.0.0"
    required: false

weblets:
  - vendor: "stripe"
    weblet: "payment-api"
    version: "2.0.0"
    launch: "onDemand"

mcpServers:
  - vendor: "block"
    server: "filesystem"
    version: "1.0.0"
    configDir: "mcp-configs/filesystem"
    required: true

agents:
  - vendor: "openai"
    agent: "code-reviewer"
    version: "1.5.0"
    role: "reviewer"
    delegations: ["code-quality", "security-check"]
    required: false

# === ORCHESTRATION (Optional) ===

orchestration:
  entrypoint: "main"
  fallback: "error-handler"
  triggers:
    - event: "code-change"
      action: "review"

# === TOOLS (Optional) ===

tools: ["Read", "Edit", "Bash", "Glob", "Grep"]

# === CONFIGURATION (Optional) ===

config:
  temperature: 0.7
  max_tokens: 4096
  require_confirmation: false

# === MEMORY (Optional) ===

memory:
  type: "editable"
  blocks:
    personality: "default"

# === MODEL (Optional) ===

model:
  provider: "anthropic"
  name: "claude-sonnet-4-5"
  embedding: "voyage-2"

# OR simplified alias format
# model: "sonnet"

# === HARNESS-SPECIFIC (Optional) ===

harnessConfig:
  deep-agents:
    skills-middleware: true
    auto-load: true
---

# Agent Purpose

Describe the agent's primary role, expertise, and what problems it solves.

## Core Responsibilities

- Primary responsibility 1
- Primary responsibility 2

## Capabilities

### Domain Knowledge
What the agent knows about.

## Communication Style

How the agent interacts with users.
```

---

## Field Definitions

### Identity Fields (Required)

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Display name (1-100 chars) |
| `vendorKey` | string | Publisher namespace (kebab-case) |
| `agentKey` | string | Agent identifier (kebab-case) |
| `version` | string | Semantic version (e.g., "1.0.0") |
| `slug` | string | Unique identifier: `vendorKey/agentKey` |

### Metadata Fields (Required)

| Field | Type | Description |
|-------|------|-------------|
| `description` | string | Brief description (50-500 chars) |
| `author` | string | Author handle or name (e.g., "@vendor") |
| `license` | string | SPDX license identifier (e.g., "MIT") |
| `tags` | array[string] | Categorization tags |

### Composition Fields (Optional)

#### Skills

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Skill identifier |
| `source` | string | `"local"` or well-known URL |
| `version` | string | Semantic version |
| `required` | boolean | Whether skill is mandatory |

#### MCP Servers

| Field | Type | Description |
|-------|------|-------------|
| `vendor` | string | MCP vendor namespace |
| `server` | string | Server identifier |
| `version` | string | Semantic version |
| `configDir` | string | Path to config directory |
| `required` | boolean | Whether MCP server is mandatory |

#### Sub-Agents

| Field | Type | Description |
|-------|------|-------------|
| `vendor` | string | Agent vendor namespace |
| `agent` | string | Agent identifier |
| `version` | string | Semantic version |
| `role` | string | Role in composition (e.g., "reviewer") |
| `delegations` | array[string] | Tasks delegated to this agent |
| `required` | boolean | Whether sub-agent is mandatory |

### Tools Field (Optional)

| Field | Type | Description |
|-------|------|-------------|
| `tools` | array[string] | Explicit tool access list. Example: `["Read", "Edit", "Bash", "Glob", "Grep"]` |

### Model Fields (Optional)

#### Full Format (Object):

| Field | Type | Description |
|-------|------|-------------|
| `provider` | string | Model provider (e.g., "anthropic") |
| `name` | string | Model name (e.g., "claude-sonnet-4-5") |
| `embedding` | string | Embedding model name |

#### Simplified Format (String Alias):

| Value | Description |
|-------|-------------|
| `"sonnet"` | Claude Sonnet (latest) |
| `"opus"` | Claude Opus (latest) |
| `"haiku"` | Claude Haiku (latest) |

### Configuration Fields (Optional)

| Field | Type | Description |
|-------|------|-------------|
| `temperature` | number | Model temperature (0.0-1.0) |
| `max_tokens` | number | Maximum output tokens |
| `require_confirmation` | boolean | Require user confirmation for actions |

---

## Skills Directory Format (AgentSkills.io)

When an agent includes local/custom skills, they are placed in the `skills/` directory.

### Skill Directory Structure

```
skills/skill-name/
├── SKILL.md                    # Skill manifest (required)
├── resources/                  # Data files, configs (optional)
├── scripts/                    # Executable scripts (optional)
└── assets/                     # Images, diagrams (optional)
```

### SKILL.md Format

```yaml
---
name: "skill-name"
description: "Brief description of what this skill does"
license: "MIT"
metadata:
  author: "vendor-name"
  version: "1.0.0"
allowed-tools: ["bash", "python", "edit"]
---

# Skill Purpose

Instructions for using this skill.
```

---

## MCP Configs Directory

MCP server configurations are stored in `mcp-configs/` with each MCP server having its own subdirectory:

1. **ActiveMCP.json** - Tool subset selection (avoids context overflow)
2. **config.yaml** - Connection and permission configuration

### ActiveMCP.json Format

```json
{
  "vendor": "block",
  "server": "filesystem",
  "version": "1.0.0",
  "selectedTools": [
    {
      "name": "read_file",
      "enabled": true,
      "description": "Read contents of a file",
      "required": true
    },
    {
      "name": "write_file",
      "enabled": true,
      "description": "Write content to a file",
      "required": true
    }
  ],
  "excludedTools": ["delete_file", "move_file"],
  "contextStrategy": "subset"
}
```

### config.yaml Format

```yaml
vendor: "block"
server: "filesystem"
version: "1.0.0"

connection:
  type: "sse"
  url: "http://localhost:8811/sse"
  timeout: 60

auth:
  type: "bearer"
  token: "${FILESYSTEM_TOKEN}"

permissions:
  allow_paths: ["/workspace", "/tmp"]
  deny_paths: ["/system", "/etc"]
  read_only: false
```

---

## Examples

### Minimal Agent

```yaml
---
name: "Simple Assistant"
vendorKey: "acme"
agentKey: "simple"
version: "1.0.0"
slug: "acme/simple"
description: "A simple helpful assistant"
author: "@acme"
license: "MIT"
tags: ["assistant"]
---

I am a simple helpful assistant.
```

### Full-Featured Agent

```yaml
---
name: "Research Assistant"
vendorKey: "acme"
agentKey: "research"
version: "1.0.0"
slug: "acme/research"
description: "A research assistant with web search and file access"
author: "@acme"
license: "MIT"
tags: ["research", "web-search"]

skills:
  - name: "web-search"
    source: "local"
    version: "1.0.0"
    required: true

mcpServers:
  - vendor: "block"
    server: "filesystem"
    version: "1.0.0"
    configDir: "mcp-configs/filesystem"
    required: true

tools: ["Read", "Edit", "Bash", "Glob", "Grep"]

model:
  provider: "ctyun"
  name: "5df2c9ff4ad347cb95ea42ad6e9e1729"

config:
  temperature: 0.7
  max_tokens: 4096
---

# Agent Purpose

You are a research assistant specialized in finding and analyzing information.

## Core Responsibilities

- Search the web for information
- Read and analyze documents
- Provide accurate, well-sourced answers
```

---

## Compliance & Standards

The Open Agent Format complies with and builds upon:

- **[AGENTS.md](https://agents.md/)** - OpenAI's universal agent standard
- **[AgentSkills.io](https://agentskills.io/)** - Anthropic/OpenAI skills specification
- **Semantic Versioning** - Version constraints follow [semver.org](https://semver.org/)
- **SPDX Licenses** - License identifiers follow [spdx.org](https://spdx.org/)

---

## License

This specification is released under CC0 1.0 Universal (Public Domain).
