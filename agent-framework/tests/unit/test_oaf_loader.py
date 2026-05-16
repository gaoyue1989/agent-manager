import pytest
from pathlib import Path


class TestOAFLoader:
    """OAF 配置加载器单元测试"""

    def test_load_minimal_agent(self, oaf_config_minimal):
        assert oaf_config_minimal.name == "Minimal Agent"
        assert oaf_config_minimal.vendor_key == "test"
        assert oaf_config_minimal.agent_key == "minimal"
        assert oaf_config_minimal.version == "1.0.0"
        assert oaf_config_minimal.slug == "test/minimal"
        assert oaf_config_minimal.description == "A minimal test agent"
        assert oaf_config_minimal.author == "@test"
        assert oaf_config_minimal.license == "MIT"
        assert "test" in oaf_config_minimal.tags
        assert "minimal test agent" in oaf_config_minimal.system_prompt.lower()

    def test_load_full_agent(self, oaf_config_full):
        assert oaf_config_full.name == "Full Test Agent"
        assert oaf_config_full.vendor_key == "test"
        assert len(oaf_config_full.skills) == 1
        assert oaf_config_full.skills[0].name == "bash-tool"
        assert oaf_config_full.skills[0].source == "local"
        assert oaf_config_full.skills[0].required is True
        assert len(oaf_config_full.mcp_servers) == 1
        assert oaf_config_full.mcp_servers[0].server == "filesystem"
        assert oaf_config_full.mcp_servers[0].vendor == "block"
        assert "Read" in oaf_config_full.tools
        assert "Bash" in oaf_config_full.tools
        assert "Edit" in oaf_config_full.tools
        assert oaf_config_full.runtime_config.temperature == 0.7
        assert oaf_config_full.runtime_config.max_tokens == 4096

    def test_has_skills(self, oaf_config_minimal, oaf_config_full):
        assert oaf_config_minimal.has_skills is False
        assert oaf_config_full.has_skills is True

    def test_has_mcp(self, oaf_config_minimal, oaf_config_full):
        assert oaf_config_minimal.has_mcp is False
        assert oaf_config_full.has_mcp is True

    def test_local_skills(self, oaf_config_full):
        local = oaf_config_full.local_skills
        assert len(local) == 1
        assert local[0].name == "bash-tool"

    def test_remote_skills(self, oaf_config_full):
        remote = oaf_config_full.remote_skills
        assert len(remote) == 0

    def test_load_skill_description(self, oaf_loader_full):
        desc = oaf_loader_full._load_skill_description("bash-tool")
        assert "Bash Tool Skill" in desc
        assert "bash commands" in desc.lower()

    def test_load_mcp_configs_full(self, oaf_loader_full):
        configs = oaf_loader_full.load_mcp_configs()
        assert len(configs) >= 1
        fs_config = configs[0]
        assert "tools" in fs_config
        assert "connection" in fs_config
        assert fs_config["connection"]["server"] == "filesystem"

    def test_load_mcp_configs_empty_for_minimal(self, oaf_loader_minimal):
        configs = oaf_loader_minimal.load_mcp_configs()
        assert configs == []

    def test_missing_agents_md_raises_error(self, tmp_path):
        from server.services.oaf_loader import OAFLoader
        empty_dir = tmp_path / "empty"
        empty_dir.mkdir()
        loader = OAFLoader(empty_dir)
        with pytest.raises(FileNotFoundError):
            loader.load()

    def test_system_prompt_parsing(self, oaf_config_minimal):
        prompt = oaf_config_minimal.system_prompt
        assert "Agent Purpose" in prompt
        assert "Core Responsibilities" in prompt
        assert "You are a minimal test agent" in prompt

    def test_a2ui_config(self, oaf_config_full):
        assert oaf_config_full.get_a2ui_enabled() is True
        assert "a2ui.org" in oaf_config_full.get_catalog_id()

    def test_parse_frontmatter_without_frontmatter(self, oaf_loader_minimal):
        content = "Just plain text\nNo frontmatter"
        fm, body = oaf_loader_minimal._parse_frontmatter(content)
        assert fm == {}
        assert body == content

    def test_parse_frontmatter_with_empty_frontmatter(self, oaf_loader_minimal):
        content = "---\n---\nBody text here"
        fm, body = oaf_loader_minimal._parse_frontmatter(content)
        assert body == "Body text here"


class TestOAFTypes:
    """OAF Pydantic 类型单元测试"""

    def test_skill_config_defaults(self):
        from server.models.oaf_types import SkillConfig
        s = SkillConfig(name="test-skill")
        assert s.source == "local"
        assert s.version == "1.0.0"
        assert s.required is False

    def test_mcp_server_config_defaults(self):
        from server.models.oaf_types import MCPServerConfig
        m = MCPServerConfig()
        assert m.vendor == ""
        assert m.server == ""
        assert m.version == "1.0.0"

    def test_oaf_config_default_values(self):
        from server.models.oaf_types import OAFConfig
        c = OAFConfig(name="test")
        assert c.vendor_key == "local"
        assert c.version == "1.0.0"
        assert c.license == "MIT"
        assert c.skills == []
        assert c.tools == []

    def test_model_config_parse_string(self):
        from server.models.oaf_types import ModelConfig
        m = ModelConfig(provider="openai", name="gpt-4")
        assert m.provider == "openai"
        assert m.name == "gpt-4"
