from typing import Optional, Any
from pydantic import BaseModel, Field


class SkillConfig(BaseModel):
    name: str
    source: str = "local"
    version: str = "1.0.0"
    required: bool = False
    description: str = ""
    allowed_tools: list[str] = Field(default_factory=lambda: ["bash", "python"])


class MCPServerConfig(BaseModel):
    vendor: str = ""
    server: str = ""
    version: str = "1.0.0"
    config_dir: str = ""
    required: bool = False


class SubAgentConfig(BaseModel):
    vendor: str = ""
    agent: str = ""
    version: str = "1.0.0"
    role: str = ""
    delegations: list[str] = Field(default_factory=list)
    required: bool = False
    endpoint: str = ""


class ModelConfig(BaseModel):
    provider: str = "openai"
    name: str = ""
    embedding: str = ""


class RuntimeConfig(BaseModel):
    temperature: float = 0.7
    max_tokens: int = 4096
    require_confirmation: bool = False


class MemoryConfig(BaseModel):
    type: str = "editable"
    blocks: dict[str, str] = Field(default_factory=dict)


class OrchestrationConfig(BaseModel):
    entrypoint: str = "main"
    fallback: str = ""
    triggers: list[dict] = Field(default_factory=list)


class HarnessConfig(BaseModel):
    a2a: dict = Field(default_factory=dict)
    a2ui: dict = Field(default_factory=dict)


class OAFConfig(BaseModel):
    name: str = "Unnamed Agent"
    vendor_key: str = Field(default="local", alias="vendorKey")
    agent_key: str = Field(default="unnamed", alias="agentKey")
    version: str = "1.0.0"
    slug: str = "local/unnamed"
    description: str = ""
    author: str = "@local"
    license: str = "MIT"
    tags: list[str] = Field(default_factory=list)
    system_prompt: str = ""
    skills: list[SkillConfig] = Field(default_factory=list)
    mcp_servers: list[MCPServerConfig] = Field(default_factory=list, alias="mcpServers")
    sub_agents: list[SubAgentConfig] = Field(default_factory=list, alias="agents")
    tools: list[str] = Field(default_factory=list)
    model: ModelConfig = Field(default_factory=ModelConfig)
    runtime_config: RuntimeConfig = Field(default_factory=RuntimeConfig, alias="config")
    memory: MemoryConfig = Field(default_factory=MemoryConfig)
    orchestration: OrchestrationConfig = Field(default_factory=OrchestrationConfig)
    harness_config: HarnessConfig = Field(default_factory=HarnessConfig, alias="harnessConfig")
    raw_frontmatter: dict = Field(default_factory=dict, exclude=True)

    @property
    def has_skills(self) -> bool:
        return len(self.skills) > 0

    @property
    def has_mcp(self) -> bool:
        return len(self.mcp_servers) > 0

    @property
    def has_sub_agents(self) -> bool:
        return len(self.sub_agents) > 0

    @property
    def local_skills(self) -> list[SkillConfig]:
        return [s for s in self.skills if s.source == "local"]

    @property
    def remote_skills(self) -> list[SkillConfig]:
        return [s for s in self.skills if s.source != "local"]

    def get_a2ui_enabled(self) -> bool:
        hc = self.raw_frontmatter.get("harnessConfig", {})
        da = hc.get("deep-agents", {})
        a2ui = da.get("a2ui", {})
        return a2ui.get("enabled", True)

    def get_catalog_id(self) -> str:
        hc = self.raw_frontmatter.get("harnessConfig", {})
        da = hc.get("deep-agents", {})
        a2ui = da.get("a2ui", {})
        return a2ui.get("catalog_id", "https://a2ui.org/specification/v0_8/standard_catalog_definition.json")
