import json
import yaml
from pathlib import Path
from typing import Any, Optional

from server.models.oaf_types import MCPServerConfig


class MCPManager:
    """MCP 管理器

    管理 MCP Server 连接配置和工具子集过滤
    """

    def __init__(self, mcp_configs_dir: Path | str):
        self.mcp_configs_dir = Path(mcp_configs_dir)

    def load_configs(self, mcp_servers: list[MCPServerConfig]) -> list[dict]:
        configs = []
        if not self.mcp_configs_dir.exists():
            return configs

        for ms in mcp_servers:
            config = self._load_single_config(ms)
            if config:
                configs.append(config)
        return configs

    def _load_single_config(self, ms: MCPServerConfig) -> Optional[dict]:
        mcp_dir = self.mcp_configs_dir / (ms.config_dir or ms.server)
        if not mcp_dir.exists():
            mcp_dir = self.mcp_configs_dir / ms.server
        if not mcp_dir.exists():
            return None

        config = {
            "vendor": ms.vendor,
            "server": ms.server,
            "version": ms.version,
            "required": ms.required,
        }

        active_mcp = mcp_dir / "ActiveMCP.json"
        if active_mcp.exists():
            try:
                config["tools"] = json.loads(active_mcp.read_text())
            except json.JSONDecodeError:
                pass

        config_yaml = mcp_dir / "config.yaml"
        if config_yaml.exists():
            try:
                yaml_data = yaml.safe_load(config_yaml.read_text())
                config["connection"] = yaml_data.get("connection", {})
            except yaml.YAMLError:
                pass

        return config

    def get_enabled_tools(self, mcp_configs: list[dict]) -> list[dict]:
        tools = []
        for mc in mcp_configs:
            tool_config = mc.get("tools", {})
            selected = tool_config.get("selectedTools", [])
            for t in selected:
                if t.get("enabled", True):
                    tools.append({
                        "mcp_server": mc.get("server", "unknown"),
                        "name": t["name"],
                        "description": t.get("description", ""),
                        "required": t.get("required", False),
                    })
        return tools

    def get_excluded_tools(self, mcp_configs: list[dict]) -> list[str]:
        excluded = set()
        for mc in mcp_configs:
            tool_config = mc.get("tools", {})
            excluded.update(tool_config.get("excludedTools", []))
        return list(excluded)

    async def create_mcp_client(self, mcp_configs: list[dict]) -> Optional[Any]:
        """创建 MCP 客户端连接

        使用 langchain-mcp-adapters 连接 MCP 服务器
        返回 MultiServerMCPClient 实例
        """
        servers = {}
        for mc in mcp_configs:
            connection = mc.get("connection", {})
            if not connection:
                continue

            conn_type = connection.get("type", "sse")
            url = connection.get("url", "")
            if not url:
                continue

            server_key = mc.get("server", "mcp_server")
            servers[server_key] = {
                "url": url,
                "transport": conn_type,
                "headers": connection.get("headers", {}),
            }

        if not servers:
            return None

        try:
            from langchain_mcp_adapters.client import MultiServerMCPClient
            client = MultiServerMCPClient(servers)
            return client
        except ImportError:
            print("langchain_mcp_adapters not installed")
        except Exception as e:
            print(f"MCP client creation failed: {e}")

        return None

    def get_mcp_summaries(self, mcp_configs: list[dict]) -> list[dict]:
        summaries = []
        for mc in mcp_configs:
            conn = mc.get("connection", {})
            tools_cfg = mc.get("tools", {})
            summaries.append({
                "server": mc.get("server", "unknown"),
                "vendor": mc.get("vendor", ""),
                "connection_type": conn.get("type", "N/A"),
                "url": conn.get("url", "N/A"),
                "tool_count": len(tools_cfg.get("selectedTools", [])),
            })
        return summaries
