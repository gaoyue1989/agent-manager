#!/usr/bin/env python3
"""
旧格式迁移脚本

将旧的 JSON 配置格式转换为 OAF v0.8.0 格式
"""

import json
from typing import Tuple
import yaml


def migrate_legacy_config(legacy_config: dict) -> Tuple[str, str]:
    """
    将旧 JSON 配置转换为 OAF AGENTS.md 格式
    
    Args:
        legacy_config: 旧格式配置字典
            - name: Agent 名称
            - description: Agent 描述
            - model: 模型名称
            - model_endpoint: 模型端点
            - api_key: API 密钥 (将被忽略，使用环境变量)
            - system_prompt: 系统提示词
            - tools: 工具列表
            - enabled_tools: 启用的工具列表
            - excluded_tools: 排除的工具列表
            - memory: 是否启用记忆
            - max_iterations: 最大迭代次数
            - sub_agents: 子 Agent 列表
            - mcp_config: MCP 配置
            - skills: 技能列表
    
    Returns:
        (agents_md_content, agent_name)
    """
    name = legacy_config.get("name", "unnamed-agent")
    description = legacy_config.get("description", "")
    model = legacy_config.get("model", "${LLM_MODEL_ID}")
    model_endpoint = legacy_config.get("model_endpoint", "${LLM_BASE_URL}")
    system_prompt = legacy_config.get("system_prompt", "You are a helpful AI assistant.")
    
    tools = legacy_config.get("tools", [])
    if not tools:
        tools = legacy_config.get("enabled_tools", [])
    
    excluded_tools = legacy_config.get("excluded_tools", [])
    
    memory = legacy_config.get("memory", True)
    max_iterations = legacy_config.get("max_iterations", 50)
    
    sub_agents = legacy_config.get("sub_agents", [])
    
    mcp_config = legacy_config.get("mcp_config", {})
    
    skills = legacy_config.get("skills", [])
    
    agent_key = name.lower().replace(" ", "-").replace("_", "-")
    
    frontmatter = {
        "name": name,
        "vendorKey": "local",
        "agentKey": agent_key,
        "version": "1.0.0",
        "slug": f"local/{agent_key}",
        "description": description,
        "author": "@local",
        "license": "MIT",
        "tags": ["migrated"],
    }
    
    migrated_skills = []
    for skill in skills:
        migrated_skills.append({
            "name": skill.get("name", "unnamed-skill"),
            "source": "local",
            "version": "1.0.0",
            "required": False,
        })
    if migrated_skills:
        frontmatter["skills"] = migrated_skills
    
    if mcp_config and mcp_config.get("url"):
        frontmatter["mcpServers"] = [
            {
                "vendor": "local",
                "server": "mcp-server",
                "version": "1.0.0",
                "configDir": "mcp-configs/mcp-server",
                "required": True,
            }
        ]
    
    migrated_sub_agents = []
    for sa in sub_agents:
        migrated_sub_agents.append({
            "vendor": "local",
            "agent": sa.get("name", "unnamed-sub-agent"),
            "version": "1.0.0",
            "role": "sub-agent",
            "protocol": "a2a",
            "endpoint": sa.get("endpoint", ""),
        })
    if migrated_sub_agents:
        frontmatter["agents"] = migrated_sub_agents
    
    if tools:
        frontmatter["tools"] = tools
    
    frontmatter["config"] = {
        "temperature": 0.7,
        "max_tokens": 4096,
    }
    if max_iterations != 50:
        frontmatter["config"]["max_iterations"] = max_iterations
    
    frontmatter["memory"] = {
        "type": "editable" if memory else "read-only",
    }
    
    frontmatter["model"] = {
        "provider": "ctyun",
        "name": model,
        "endpoint": model_endpoint,
    }
    
    harness_config = {
        "a2a": {
            "protocol": "1.0.0",
            "bindings": ["jsonrpc", "rest"],
            "streaming": True,
            "push_notifications": False,
        },
        "a2ui": {
            "enabled": True,
            "version": "v0.8",
            "catalog_id": "https://a2ui.org/specification/v0_8/standard_catalog_definition.json",
        },
    }
    
    if migrated_sub_agents:
        harness_config["sub_agents"] = [
            {
                "slug": f"local/{sa.get('name', 'unnamed')}",
                "endpoint": sa.get("endpoint", ""),
                "version": "1.0.0",
            }
            for sa in sub_agents
        ]
    
    frontmatter["harnessConfig"] = {"deep-agents": harness_config}
    
    frontmatter_yaml = yaml.dump(
        frontmatter,
        allow_unicode=True,
        default_flow_style=False,
        sort_keys=False,
    )
    
    instructions = f"""# Agent Purpose

{system_prompt}

## Core Responsibilities

- 帮助用户解决问题
- 提供准确和有帮助的回答
"""
    
    agents_md = f"---\n{frontmatter_yaml}---\n\n{instructions}\n"
    
    return agents_md, name


def migrate_legacy_file(input_path: str, output_path: str) -> str:
    """
    迁移旧格式配置文件
    
    Args:
        input_path: 旧格式 JSON 文件路径
        output_path: 输出 AGENTS.md 文件路径
    
    Returns:
        Agent 名称
    """
    with open(input_path, "r", encoding="utf-8") as f:
        legacy_config = json.load(f)
    
    agents_md, name = migrate_legacy_config(legacy_config)
    
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(agents_md)
    
    return name


def get_migration_mapping() -> dict:
    """
    获取旧格式到 OAF 的字段映射说明
    
    Returns:
        字段映射字典
    """
    return {
        "name": "frontmatter.name",
        "description": "frontmatter.description",
        "model": "frontmatter.model.name",
        "model_endpoint": "frontmatter.model.endpoint",
        "api_key": "环境变量 LLM_API_KEY (不写入配置)",
        "system_prompt": "Markdown body (作为 instructions)",
        "tools": "frontmatter.tools",
        "enabled_tools": "frontmatter.tools",
        "excluded_tools": "忽略 (OAF 通过 tools 列表控制)",
        "memory": "frontmatter.memory.type (true→editable, false→read-only)",
        "max_iterations": "frontmatter.config.max_iterations",
        "sub_agents": "frontmatter.agents[]",
        "mcp_config": "mcp-configs/ 目录 + frontmatter.mcpServers",
        "skills": "skills/ 目录 + frontmatter.skills",
    }
