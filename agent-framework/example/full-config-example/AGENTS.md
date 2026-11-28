---
# ==============================================================================
# OAF Agent 主配置文件（AGENTS.md Frontmatter 全量字段示例）
# 说明：
#   1. 本文件位于 OAF 包根目录（通过 AGENT_CONFIG_DIR 指向，默认 /config）
#   2. YAML frontmatter 由 agent-framework 的 OafConfig.java 解析
#   3. YAML frontmatter 之后的正文将作为 Agent 的 System Prompt（系统提示词）注入模型
#   4. 标注「仅元信息回显」的字段不会被运行时执行引擎直接消费，运行时 LLM/参数由环境变量或 /models 驱动
# ==============================================================================

# [必填] Agent 显示名称（展示于 AgentCard、管理平台及对话头）
name: full-config-example-agent

# [必填] 厂商/组织标识（小写 kebab-case，与 agentKey 共同构成平台唯一坐标）
vendorKey: example-vendor

# [必填] Agent 唯一标识键（小写 kebab-case，用于目录检索、A2A 路由及平台注册）
agentKey: full-config-example

# [必填] OAF 包语义化版本号（semver）
version: "1.0.0"

# [可选] URL 友好短标识（用于路由或展示）
slug: example-vendor/full-config-example

# [必填] Agent 职责与能力简述（展示于 AgentCard 与多 Agent 委派提示）
description: "全量配置参考示例 Agent：演示 OAF frontmatter、Skills、MCP、Sub-Agent 委派、权限控制与沙箱配置。"

# [可选] 作者或维护团队
author: platform-team

# [可选] 开源或内部许可证标识
license: Apache-2.0

# [可选] 分类标签列表（用于平台检索与分组）
tags:
  - example
  - reference
  - sandbox

# ------------------------------------------------------------------------------
# Skills 声明（技能包位于 ${AGENT_CONFIG_DIR}/skills/<name>/SKILL.md）
# 运行时会加载技能元信息到系统提示，并在启用沙箱时同步至沙箱工作区
# ------------------------------------------------------------------------------
skills:
  # [必填] 技能目录名，对应 skills/<name>/SKILL.md（建议小写 kebab-case）
  - name: example-skill
    # [可选] 技能来源标识（本地路径、仓库或平台市场坐标）
    source: local
    # [可选] 技能版本号（与共享存储/同步缓存指纹校验联动）
    version: "1.0.0"
    # [可选] 是否为必需技能（仅声明/校验标识，默认 false）
    required: true
    # [可选] 技能一句话描述（覆盖或补充 SKILL.md 摘要）
    description: "演示技能配置、工具白名单及沙箱脚本同步"
    # [可选] 该技能允许调用的工具白名单（⚠️ 必须为空格分隔的字符串，不可写为 YAML 列表或 allowedTools）
    allowed-tools: "read_file write_file execute"

# ------------------------------------------------------------------------------
# MCP Server 声明（详细连接与鉴权配置位于 ${AGENT_CONFIG_DIR}/<configDir>/config.yaml，默认 mcp-configs/<server>/config.yaml）
# ------------------------------------------------------------------------------
mcpServers:
  # [必填] MCP 厂商/命名空间
  - vendor: example-vendor
    # [必填] MCP 服务名（默认匹配 mcp-configs/<server>/ 目录）
    server: example-server
    # [可选] MCP 版本号
    version: "1.0.0"
    # [可选] 相对 ${AGENT_CONFIG_DIR} 的配置目录路径（留空默认 mcp-configs/<server>）
    configDir: mcp-configs/example-server
    # [可选] 仅回显声明；真正控制启动期连接失败是否阻断的是对应 config.yaml 中的 startup.required
    required: false

# ------------------------------------------------------------------------------
# Sub-Agent 委派声明（A2A / Agent Protocol 多 Agent 协作）
# 声明后框架会自动注册对应委派工具，将子任务转发至目标 Agent
# ------------------------------------------------------------------------------
agents:
  # [必填] 目标子 Agent 厂商标识
  - vendor: example-vendor
    # [必填] 目标子 Agent 名称
    agent: helper-agent
    # [可选] 目标子 Agent 版本
    version: "1.0.0"
    # [可选] 子 Agent 角色说明（注入主 Agent 提示词，帮助判断是否委派）
    role: "负责专项数据检索与结构化汇总"
    # [可选] 允许委派的任务类型列表
    delegations:
      - research
      - summarize
    # [可选] 是否强依赖声明
    required: false
    # [可选] 子 Agent 的远程端点 URL（如配合 Agent Protocol，鉴权 Header 通过 AGENT_REMOTE_HEADERS_JSON 注入）
    endpoint: "http://helper-agent:8100"

# ------------------------------------------------------------------------------
# 内置工具白名单 / 黑名单
# ------------------------------------------------------------------------------
# [可选] 显式允许的内置工具列表（留空表示按默认策略启用全部内置工具）
# 常见内置工具：read_file, write_file, edit_file, list_files, glob_files, grep_files, execute, web_search, web_fetch, plan_write
tools:
  - read_file
  - write_file
  - edit_file
  - list_files
  - glob_files
  - grep_files
  - execute

# [可选] 显式禁用的工具列表（优先级高于 tools，适用于禁用危险或不需要的工具）
deniedTools:
  - web_search

# ------------------------------------------------------------------------------
# 模型元信息（⚠️ 仅元信息回显：运行时实际模型由环境变量 LLM_MODEL_ID 或 /models 托管模型决定）
# ------------------------------------------------------------------------------
model: claude-sonnet-4-20250514

# ------------------------------------------------------------------------------
# 运行时行为与安全策略配置
# ------------------------------------------------------------------------------
config:
  # [仅元信息回显] 温度参数示例（运行时由环境变量 LLM_TEMPERATURE 驱动，默认 0.3）
  temperature: 0.3
  # [仅元信息回显] 最大输出 Token 示例（运行时由环境变量 LLM_MAX_TOKENS 驱动，默认 16384）
  max_tokens: 16384

  # [可选] MCP 工具未在 permissions.tools 声明时的默认兜底：true 一律 ask，false 一律 allow（默认 false）
  require_confirmation: false

  # [可选] 细粒度工具权限控制（驱动 HITL 人工确认与拒绝拦截）
  permission:
    # 全局兜底权限模式：
    #   - default      : 默认模式（写文件、执行命令等敏感工具触发人工确认）
    #   - accept_edits : 自动放行文件编辑类工具，命令执行等仍需确认
    #   - explore      : 只读探索模式（禁止修改类工具）
    #   - bypass       : 全部自动放行（仅建议受控自动化环境使用）
    #   - dont_ask     : 不弹窗询问，未显式允许的受限工具直接拒绝
    mode: default
    # 按精确工具名覆盖权限策略（不支持通配符）：allow（自动放行）| ask（弹窗确认）| deny（直接拒绝）
    # 适用于自定义工具（@Tool）与 SDK 内置工具；MCP 工具请在对应 config.yaml 的 permissions.tools 配置
    tools:
      read_file: allow
      list_files: allow
      glob_files: allow
      grep_files: allow
      write_file: ask
      execute: ask

  # [可选] 包级沙箱开关（三层裁决：环境变量 SANDBOX_ENABLED > 本项 > 默认 false）
  sandbox:
    # true 表示启用 OpenSandbox（需配合部署侧 OPENSANDBOX_SERVER_URL 使用）
    enabled: true

# ------------------------------------------------------------------------------
# 记忆配置（可选；运行时记忆开关与后端由 AGENT_MEMORY_ENABLED 与 AGENT_MEMORY_PROVIDER 驱动）
# ------------------------------------------------------------------------------
memory:
  # [可选] 记忆块模式（默认 editable）
  type: editable

# ------------------------------------------------------------------------------
# Harness 扩展配置（驱动前端动态渲染协议等高级能力）
# ------------------------------------------------------------------------------
harnessConfig:
  deep-agents:
    a2ui:
      # [可选] A2UI 组件目录 ID（用于约束 Agent 生成的前端动态卡片/表单 Schema 版本）
      catalog_id: "default-v1"
---

# System Prompt（系统提示词正文）

你是 `full-config-example-agent`，用于演示标准 OAF Agent 的全量配置结构。

## 工作原则
1. 优先阅读工作区与技能说明，再执行工具调用。
2. 对任何外部写入或命令执行操作保持谨慎，遵守配置的 `permission` 策略。
3. 回复简洁、准确、结构化。
