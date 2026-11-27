# OAF Agent 完整配置示例（全字段带注释）

本目录提供一个与当前 `agent-framework` 实现严格对齐的 OAF 配置包示例，**每个配置字段均附有中文注释说明其作用、默认值与常见注意事项**，可直接作为新建 Agent 的模板拷贝使用。

## 目录结构

```text
full-config-example/
├── AGENTS.md                           # 【必需】Agent 元数据清单（YAML frontmatter）+ 系统提示词（Markdown 正文）
├── mcp-configs/
│   └── example-server/
│       ├── config.yaml                 # 【可选】MCP Server 连接、认证、用户头透传、权限、直返与 UI 卡片配置
│       └── ActiveMCP.json              # 【可选】MCP 工具子集启停过滤清单
└── skills/
    └── example-skill/
        └── SKILL.md                    # 【可选】技能清单与执行规范（运行时每轮热扫描，无需重启）
```

## 快速打包

```bash
cd agent-framework/example/full-config-example
zip -r example-agent.zip AGENTS.md mcp-configs skills
```

运行时环境变量完整示例（同样逐项带注释）见 `agent-framework/.env.example`，详细部署说明见 `agent-framework/docs/agent-framework-deploy.md`。
