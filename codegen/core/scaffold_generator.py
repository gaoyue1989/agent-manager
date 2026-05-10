#!/usr/bin/env python3
"""
OAF 脚手架生成器

生成符合 Open Agent Format (OAF) v0.8.0 规范的目录结构
"""

import os
from pathlib import Path
from typing import Optional
import yaml


class ScaffoldGenerator:
    """OAF 目录脚手架生成器"""
    
    def __init__(self):
        self.oaf_version = "0.8.0"
    
    def create_scaffold(
        self,
        name: str,
        output_dir: Path | str,
        description: str = "",
        vendor_key: str = "local",
        agent_key: Optional[str] = None,
        version: str = "1.0.0",
        author: str = "@local",
        license: str = "MIT",
        tags: Optional[list[str]] = None,
        model_provider: str = "ctyun",
        model_name: str = "${LLM_MODEL_ID}",
        model_endpoint: str = "${LLM_BASE_URL}",
        instructions: str = "",
        tools: Optional[list[str]] = None,
        skills: Optional[list[dict]] = None,
        mcp_servers: Optional[list[dict]] = None,
        sub_agents: Optional[list[dict]] = None,
        a2ui_enabled: bool = True,
        a2a_enabled: bool = True,
    ) -> Path:
        """
        创建 OAF 目录结构
        
        Args:
            name: Agent 显示名称
            output_dir: 输出目录
            description: Agent 描述
            vendor_key: 发布者命名空间
            agent_key: Agent 标识符 (默认从 name 派生)
            version: Agent 版本
            author: 作者
            license: 许可证
            tags: 标签列表
            model_provider: 模型提供者
            model_name: 模型名称
            model_endpoint: 模型端点
            instructions: 系统提示词 (Markdown body)
            tools: 工具列表
            skills: 技能列表
            mcp_servers: MCP 服务器列表
            sub_agents: 子 Agent 列表
            a2ui_enabled: 是否启用 A2UI
            a2a_enabled: 是否启用 A2A
        
        Returns:
            生成的 Agent 目录路径
        """
        output_dir = Path(output_dir)
        
        if agent_key is None:
            agent_key = name.lower().replace(" ", "-").replace("_", "-")
        
        if tags is None:
            tags = []
        
        if tools is None:
            tools = []
        
        if skills is None:
            skills = []
        
        if mcp_servers is None:
            mcp_servers = []
        
        if sub_agents is None:
            sub_agents = []
        
        slug = f"{vendor_key}/{agent_key}"
        agent_dir = output_dir / agent_key
        agent_dir.mkdir(parents=True, exist_ok=True)
        
        (agent_dir / "skills").mkdir(exist_ok=True)
        (agent_dir / "mcp-configs").mkdir(exist_ok=True)
        
        agents_md = self._render_agents_md(
            name=name,
            vendor_key=vendor_key,
            agent_key=agent_key,
            slug=slug,
            version=version,
            description=description,
            author=author,
            license=license,
            tags=tags,
            model_provider=model_provider,
            model_name=model_name,
            model_endpoint=model_endpoint,
            instructions=instructions,
            tools=tools,
            skills=skills,
            mcp_servers=mcp_servers,
            sub_agents=sub_agents,
            a2ui_enabled=a2ui_enabled,
            a2a_enabled=a2a_enabled,
        )
        
        (agent_dir / "AGENTS.md").write_text(agents_md, encoding="utf-8")
        
        readme_content = f"# {name}\n\n{description}\n"
        (agent_dir / "README.md").write_text(readme_content, encoding="utf-8")
        
        return agent_dir
    
    def _render_agents_md(
        self,
        name: str,
        vendor_key: str,
        agent_key: str,
        slug: str,
        version: str,
        description: str,
        author: str,
        license: str,
        tags: list[str],
        model_provider: str,
        model_name: str,
        model_endpoint: str,
        instructions: str,
        tools: list[str],
        skills: list[dict],
        mcp_servers: list[dict],
        sub_agents: list[dict],
        a2ui_enabled: bool,
        a2a_enabled: bool,
    ) -> str:
        """生成 AGENTS.md 内容"""
        
        frontmatter = {
            "name": name,
            "vendorKey": vendor_key,
            "agentKey": agent_key,
            "version": version,
            "slug": slug,
            "description": description,
            "author": author,
            "license": license,
            "tags": tags,
        }
        
        if skills:
            frontmatter["skills"] = skills
        
        if mcp_servers:
            frontmatter["mcpServers"] = mcp_servers
        
        if sub_agents:
            frontmatter["agents"] = sub_agents
        
        if tools:
            frontmatter["tools"] = tools
        
        frontmatter["model"] = {
            "provider": model_provider,
            "name": model_name,
            "endpoint": model_endpoint,
        }
        
        harness_config = {}
        
        if a2a_enabled:
            harness_config["a2a"] = {
                "protocol": "1.0.0",
                "bindings": ["jsonrpc", "rest"],
                "streaming": True,
                "push_notifications": False,
            }
        
        if a2ui_enabled:
            harness_config["a2ui"] = {
                "enabled": True,
                "version": "v0.8",
                "catalog_id": "https://a2ui.org/specification/v0_8/standard_catalog_definition.json",
            }
        
        if sub_agents:
            harness_config["sub_agents"] = [
                {
                    "slug": sa.get("slug", ""),
                    "endpoint": sa.get("endpoint", ""),
                    "version": sa.get("version", "1.0.0"),
                }
                for sa in sub_agents
            ]
        
        if harness_config:
            frontmatter["harnessConfig"] = {"deep-agents": harness_config}
        
        frontmatter_yaml = yaml.dump(
            frontmatter,
            allow_unicode=True,
            default_flow_style=False,
            sort_keys=False,
        )
        
        if not instructions:
            instructions = f"# Agent Purpose\n\n你是一个有用的 AI 助手。\n\n## Core Responsibilities\n\n- 帮助用户解决问题\n- 提供准确和有帮助的回答\n"
        
        return f"---\n{frontmatter_yaml}---\n\n{instructions}\n"
    
    def add_skill(
        self,
        agent_dir: Path | str,
        skill_name: str,
        skill_description: str = "",
        allowed_tools: Optional[list[str]] = None,
    ) -> Path:
        """
        添加本地技能到 Agent
        
        Args:
            agent_dir: Agent 目录
            skill_name: 技能名称
            skill_description: 技能描述
            allowed_tools: 允许的工具列表
        
        Returns:
            技能目录路径
        """
        agent_dir = Path(agent_dir)
        skill_dir = agent_dir / "skills" / skill_name
        skill_dir.mkdir(parents=True, exist_ok=True)
        
        if allowed_tools is None:
            allowed_tools = []
        
        skill_md_content = f'''---
name: "{skill_name}"
description: "{skill_description}"
license: "MIT"
metadata:
  author: "local"
  version: "1.0.0"
allowed-tools: {allowed_tools}
---

# {skill_name}

{skill_description}

## Usage

使用说明...
'''
        (skill_dir / "SKILL.md").write_text(skill_md_content, encoding="utf-8")
        
        scripts_dir = skill_dir / "scripts"
        scripts_dir.mkdir(exist_ok=True)
        
        (scripts_dir / "__init__.py").write_text("", encoding="utf-8")
        
        return skill_dir
    
    def add_mcp_config(
        self,
        agent_dir: Path | str,
        mcp_name: str,
        mcp_vendor: str = "local",
        mcp_version: str = "1.0.0",
        connection_type: str = "sse",
        connection_url: str = "",
        selected_tools: Optional[list[dict]] = None,
        excluded_tools: Optional[list[str]] = None,
    ) -> Path:
        """
        添加 MCP 配置到 Agent
        
        Args:
            agent_dir: Agent 目录
            mcp_name: MCP 名称
            mcp_vendor: MCP 提供者
            mcp_version: MCP 版本
            connection_type: 连接类型 (sse/http/stdio)
            connection_url: 连接 URL
            selected_tools: 选中的工具列表
            excluded_tools: 排除的工具列表
        
        Returns:
            MCP 配置目录路径
        """
        import json
        
        agent_dir = Path(agent_dir)
        mcp_dir = agent_dir / "mcp-configs" / mcp_name
        mcp_dir.mkdir(parents=True, exist_ok=True)
        
        if selected_tools is None:
            selected_tools = []
        
        if excluded_tools is None:
            excluded_tools = []
        
        active_mcp = {
            "vendor": mcp_vendor,
            "server": mcp_name,
            "version": mcp_version,
            "selectedTools": selected_tools,
            "excludedTools": excluded_tools,
            "contextStrategy": "subset",
        }
        (mcp_dir / "ActiveMCP.json").write_text(
            json.dumps(active_mcp, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )
        
        config_yaml = f'''vendor: "{mcp_vendor}"
server: "{mcp_name}"
version: "{mcp_version}"

connection:
  type: "{connection_type}"
  url: "{connection_url}"
  timeout: 60
'''
        (mcp_dir / "config.yaml").write_text(config_yaml, encoding="utf-8")
        
        return mcp_dir
