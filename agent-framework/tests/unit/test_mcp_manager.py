import pytest
from pathlib import Path
from server.models.oaf_types import MCPServerConfig

FIXTURES_DIR = Path(__file__).parent.parent / "fixtures"


class TestMCPManager:
    """MCP 管理器单元测试"""

    @pytest.fixture
    def mcp_manager(self):
        from server.services.mcp_manager import MCPManager
        return MCPManager(FIXTURES_DIR / "full-agent" / "mcp-configs")

    def test_load_configs(self, mcp_manager):
        servers = [
            MCPServerConfig(
                vendor="block", server="filesystem", version="1.0.0",
                configDir="filesystem", required=False,
            ),
        ]
        configs = mcp_manager.load_configs(servers)
        assert len(configs) == 1
        cfg = configs[0]
        assert cfg["vendor"] == "block"
        assert cfg["server"] == "filesystem"
        assert "tools" in cfg
        assert "connection" in cfg

    def test_get_enabled_tools(self, mcp_manager):
        servers = [
            MCPServerConfig(server="filesystem", configDir="filesystem"),
        ]
        configs = mcp_manager.load_configs(servers)
        tools = mcp_manager.get_enabled_tools(configs)
        assert len(tools) >= 2
        names = [t["name"] for t in tools]
        assert "read_file" in names
        assert "list_directory" in names

    def test_get_excluded_tools(self, mcp_manager):
        servers = [
            MCPServerConfig(server="filesystem", configDir="filesystem"),
        ]
        configs = mcp_manager.load_configs(servers)
        excluded = mcp_manager.get_excluded_tools(configs)
        assert "delete_file" in excluded
        assert "move_file" in excluded
        assert "chmod" in excluded

    def test_empty_mcp_dir(self, tmp_path):
        from server.services.mcp_manager import MCPManager
        mm = MCPManager(tmp_path / "nonexistent")
        configs = mm.load_configs([])
        assert configs == []

    def test_get_mcp_summaries(self, mcp_manager):
        servers = [
            MCPServerConfig(server="filesystem", configDir="filesystem"),
        ]
        configs = mcp_manager.load_configs(servers)
        summaries = mcp_manager.get_mcp_summaries(configs)
        assert len(summaries) == 1
        assert summaries[0]["server"] == "filesystem"
        assert summaries[0]["tool_count"] >= 2
