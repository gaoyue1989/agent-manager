"""A2A Client 测试"""

import pytest
from unittest.mock import AsyncMock, patch, MagicMock

from codegen.frameworks.deepagents.a2a_client import A2AClient, SubAgentRegistry


class TestA2AClient:
    """A2A 客户端测试"""
    
    @pytest.fixture
    def client(self):
        return A2AClient(
            slug="test/agent",
            endpoint="http://localhost:8001",
            version="1.0.0",
        )
    
    @pytest.mark.asyncio
    async def test_discover_agent_card(self, client):
        """测试发现 Agent Card"""
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json = lambda: {"name": "Test Agent"}
        mock_response.raise_for_status = lambda: None
        
        with patch("httpx.AsyncClient.get", new_callable=AsyncMock) as mock_get:
            mock_get.return_value = mock_response
            
            card = await client.discover()
            assert card["name"] == "Test Agent"
            assert client._agent_card == card
    
    @pytest.mark.asyncio
    async def test_send_message(self, client):
        """测试发送消息"""
        mock_response = MagicMock()
        mock_response.json = lambda: {
            "jsonrpc": "2.0",
            "result": {
                "id": "task-1",
                "status": {"state": "completed"},
                "artifacts": [],
            },
            "id": "test",
        }
        mock_response.raise_for_status = lambda: None
        
        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            mock_post.return_value = mock_response
            
            result = await client.send_message("Hello")
            assert "result" in result
            assert result["result"]["id"] == "task-1"
    
    @pytest.mark.asyncio
    async def test_get_task(self, client):
        """测试获取任务"""
        mock_response = MagicMock()
        mock_response.json = lambda: {
            "jsonrpc": "2.0",
            "result": {
                "id": "task-1",
                "status": {"state": "completed"},
            },
            "id": "test",
        }
        mock_response.raise_for_status = lambda: None
        
        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            mock_post.return_value = mock_response
            
            result = await client.get_task("task-1")
            assert "result" in result
    
    @pytest.mark.asyncio
    async def test_cancel_task(self, client):
        """测试取消任务"""
        mock_response = MagicMock()
        mock_response.json = lambda: {
            "jsonrpc": "2.0",
            "result": {
                "id": "task-1",
                "status": {"state": "canceled"},
            },
            "id": "test",
        }
        mock_response.raise_for_status = lambda: None
        
        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            mock_post.return_value = mock_response
            
            result = await client.cancel_task("task-1")
            assert "result" in result
    
    def test_as_tool(self, client):
        """测试包装为 Tool"""
        tool = client.as_tool()
        
        assert "name" in tool
        assert "delegate_to_test_agent" in tool["name"]
        assert "description" in tool
        assert "callable" in tool


class TestSubAgentRegistry:
    """子 Agent 注册表测试"""
    
    def test_register_sub_agent(self):
        """测试注册子 Agent"""
        registry = SubAgentRegistry()
        client = registry.register("test/agent", "http://localhost:8001")
        
        assert "test/agent" in registry.clients
        assert client.slug == "test/agent"
        assert client.endpoint == "http://localhost:8001"
    
    def test_get_sub_agent(self):
        """测试获取子 Agent"""
        registry = SubAgentRegistry()
        registry.register("test/agent", "http://localhost:8001")
        
        client = registry.get("test/agent")
        assert client is not None
        assert client.slug == "test/agent"
    
    def test_get_nonexistent_sub_agent(self):
        """测试获取不存在的子 Agent"""
        registry = SubAgentRegistry()
        
        client = registry.get("nonexistent/agent")
        assert client is None
    
    def test_as_tools(self):
        """测试转换为 Tool 列表"""
        registry = SubAgentRegistry()
        registry.register("test/agent", "http://localhost:8001")
        registry.register("test/agent2", "http://localhost:8002")
        
        tools = registry.as_tools()
        assert len(tools) == 2
        assert all("name" in t for t in tools)
        assert all("callable" in t for t in tools)
    
    @pytest.mark.asyncio
    async def test_delegate(self):
        """测试委托任务"""
        registry = SubAgentRegistry()
        client = registry.register("test/agent", "http://localhost:8001")
        
        mock_response = MagicMock()
        mock_response.json = lambda: {
            "jsonrpc": "2.0",
            "result": {"id": "task-1"},
            "id": "test",
        }
        mock_response.raise_for_status = lambda: None
        
        with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
            mock_post.return_value = mock_response
            
            result = await registry.delegate("test/agent", "Hello")
            assert "result" in result
    
    @pytest.mark.asyncio
    async def test_delegate_unknown_agent(self):
        """测试委托给未知的子 Agent"""
        registry = SubAgentRegistry()
        
        with pytest.raises(ValueError, match="Unknown sub-agent"):
            await registry.delegate("unknown/agent", "Hello")
