import json
import yaml
from pathlib import Path
from typing import Optional

from server.models.oaf_types import (
    OAFConfig, SkillConfig, MCPServerConfig, SubAgentConfig,
    ModelConfig, RuntimeConfig, MemoryConfig,
)


class OAFLoader:
    """OAF v0.8.0 配置加载器

    从目录结构加载 AGENTS.md、skills/、mcp-configs/
    """

    def __init__(self, config_dir: Path | str):
        self.config_dir = Path(config_dir)
        self.agents_md_path = self.config_dir / "AGENTS.md"

    def load(self) -> OAFConfig:
        if not self.agents_md_path.exists():
            raise FileNotFoundError(f"AGENTS.md not found at {self.agents_md_path}")

        content = self.agents_md_path.read_text(encoding="utf-8")
        frontmatter, body = self._parse_frontmatter(content)

        name = frontmatter.get("name", self.config_dir.name)
        vendor_key = frontmatter.get("vendorKey", "local")
        agent_key = frontmatter.get("agentKey", self.config_dir.name)
        version = frontmatter.get("version", "1.0.0")
        slug = frontmatter.get("slug", f"{vendor_key}/{agent_key}")
        description = frontmatter.get("description", "")
        author = frontmatter.get("author", "@local")
        license_val = frontmatter.get("license", "MIT")
        tags = frontmatter.get("tags", [])
        tools = frontmatter.get("tools", [])

        skills = self._parse_skills(frontmatter.get("skills", []))
        mcp_servers = self._parse_mcp_servers(frontmatter.get("mcpServers", []))
        sub_agents = self._parse_sub_agents(frontmatter.get("agents", []))

        model_raw = frontmatter.get("model", {})
        if isinstance(model_raw, str):
            model = ModelConfig(provider="openai", name=model_raw)
        elif isinstance(model_raw, dict):
            model = ModelConfig(
                provider=model_raw.get("provider", "openai"),
                name=model_raw.get("name", ""),
                embedding=model_raw.get("embedding", ""),
            )
        else:
            model = ModelConfig()

        config_raw = frontmatter.get("config", {})
        if isinstance(config_raw, dict):
            runtime_config = RuntimeConfig(
                temperature=config_raw.get("temperature", 0.7),
                max_tokens=config_raw.get("max_tokens", 4096),
                require_confirmation=config_raw.get("require_confirmation", False),
            )
        else:
            runtime_config = RuntimeConfig()

        memory_raw = frontmatter.get("memory", {})
        if isinstance(memory_raw, dict):
            memory = MemoryConfig(
                type=memory_raw.get("type", "editable"),
                blocks=memory_raw.get("blocks", {}),
            )
        else:
            memory = MemoryConfig()

        return OAFConfig(
            name=name,
            vendorKey=vendor_key,
            agentKey=agent_key,
            version=version,
            slug=slug,
            description=description,
            author=author,
            license=license_val,
            tags=tags,
            system_prompt=body,
            skills=skills,
            mcpServers=mcp_servers,
            agents=sub_agents,
            tools=tools,
            model=model,
            config=runtime_config,
            memory=memory,
            raw_frontmatter=frontmatter,
        )

    def _parse_frontmatter(self, content: str) -> tuple[dict, str]:
        """解析 YAML frontmatter + Markdown body"""
        if not content.startswith("---"):
            return {}, content

        parts = content.split("---", 2)
        if len(parts) < 3:
            return {}, content

        frontmatter = yaml.safe_load(parts[1].strip()) or {}
        body = parts[2].strip()
        return frontmatter, body

    def _parse_skills(self, skills_raw: list) -> list[SkillConfig]:
        skills = []
        for s in skills_raw:
            if isinstance(s, dict):
                name = s.get("name", "")
                skill_config = SkillConfig(
                    name=name,
                    source=s.get("source", "local"),
                    version=s.get("version", "1.0.0"),
                    required=s.get("required", False),
                    description=self._load_skill_description(name),
                    allowed_tools=["bash", "python"],
                )
                skills.append(skill_config)
        return skills

    def _load_skill_description(self, skill_name: str) -> str:
        skill_md = self.config_dir / "skills" / skill_name / "SKILL.md"
        if skill_md.exists():
            content = skill_md.read_text(encoding="utf-8")
            _, body = self._parse_frontmatter(content)
            return body.strip()[:500]
        return ""

    def _parse_mcp_servers(self, mcp_raw: list) -> list[MCPServerConfig]:
        servers = []
        for m in mcp_raw:
            if isinstance(m, dict):
                servers.append(MCPServerConfig(
                    vendor=m.get("vendor", ""),
                    server=m.get("server", ""),
                    version=m.get("version", "1.0.0"),
                    config_dir=m.get("configDir", ""),
                    required=m.get("required", False),
                ))
        return servers

    def _parse_sub_agents(self, agents_raw: list) -> list[SubAgentConfig]:
        agents = []
        for a in agents_raw:
            if isinstance(a, dict):
                agents.append(SubAgentConfig(
                    vendor=a.get("vendor", ""),
                    agent=a.get("agent", ""),
                    version=a.get("version", "1.0.0"),
                    role=a.get("role", ""),
                    delegations=a.get("delegations", []),
                    required=a.get("required", False),
                    endpoint=a.get("endpoint", ""),
                ))
        return agents

    def load_mcp_configs(self) -> list[dict]:
        """加载 mcp-configs/ 下的所有 MCP 配置"""
        configs = []
        mcp_dir = self.config_dir / "mcp-configs"
        if not mcp_dir.exists():
            return configs

        for item in mcp_dir.iterdir():
            if not item.is_dir():
                continue
            mcp_config = {"name": item.name}

            active_mcp = item / "ActiveMCP.json"
            if active_mcp.exists():
                try:
                    mcp_config["tools"] = json.loads(active_mcp.read_text())
                except json.JSONDecodeError:
                    pass

            config_yaml = item / "config.yaml"
            if config_yaml.exists():
                try:
                    mcp_config["connection"] = yaml.safe_load(config_yaml.read_text())
                except yaml.YAMLError:
                    pass

            if "tools" in mcp_config or "connection" in mcp_config:
                configs.append(mcp_config)

        return configs
