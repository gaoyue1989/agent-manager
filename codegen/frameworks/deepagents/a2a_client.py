#!/usr/bin/env python3
"""
A2A Client

用于调用远程子 Agent 的 A2A 客户端
"""

import json
import uuid
from typing import Optional
import httpx


class A2AClient:
    """A2A Client (JSON-RPC binding)"""
    
    def __init__(
        self,
        slug: str,
        endpoint: str,
        version: str = "1.0.0",
        timeout: int = 60,
    ):
        """
        初始化 A2A 客户端
        
        Args:
            slug: Agent slug (vendor/agent)
            endpoint: Agent 端点 URL
            version: A2A 协议版本
            timeout: 请求超时时间
        """
        self.slug = slug
        self.endpoint = endpoint.rstrip("/")
        self.version = version
        self.timeout = timeout
        self._agent_card: Optional[dict] = None
    
    async def discover(self) -> dict:
        """获取子 Agent 的 Agent Card"""
        url = f"{self.endpoint}/.well-known/agent-card.json"
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            self._agent_card = resp.json()
        return self._agent_card
    
    async def send_message(
        self,
        text: str,
        context_id: Optional[str] = None,
        metadata: Optional[dict] = None,
    ) -> dict:
        """通过 A2A 发送消息给子 Agent (JSON-RPC)"""
        payload = {
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {
                "message": {
                    "role": "user",
                    "messageId": str(uuid.uuid4()),
                    "parts": [{"text": text}],
                },
                "configuration": {},
                "metadata": metadata or {},
            },
            "id": str(uuid.uuid4()),
        }
        
        if context_id:
            payload["params"]["configuration"]["contextId"] = context_id
        
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            resp = await client.post(
                f"{self.endpoint}/",
                json=payload,
                headers={"A2A-Version": self.version},
            )
            resp.raise_for_status()
            return resp.json()
    
    async def get_task(self, task_id: str, history_length: Optional[int] = None) -> dict:
        """获取任务状态"""
        params = {"id": task_id}
        if history_length is not None:
            params["historyLength"] = history_length
        
        payload = {
            "jsonrpc": "2.0",
            "method": "tasks/get",
            "params": params,
            "id": str(uuid.uuid4()),
        }
        
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            resp = await client.post(
                f"{self.endpoint}/",
                json=payload,
                headers={"A2A-Version": self.version},
            )
            resp.raise_for_status()
            return resp.json()
    
    async def cancel_task(self, task_id: str) -> dict:
        """取消任务"""
        payload = {
            "jsonrpc": "2.0",
            "method": "tasks/cancel",
            "params": {"id": task_id},
            "id": str(uuid.uuid4()),
        }
        
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            resp = await client.post(
                f"{self.endpoint}/",
                json=payload,
                headers={"A2A-Version": self.version},
            )
            resp.raise_for_status()
            return resp.json()
    
    def as_tool(self) -> dict:
        """包装为 DeepAgents Tool"""
        tool_name = self.slug.replace("/", "_").replace("-", "_")
        return {
            "name": f"delegate_to_{tool_name}",
            "description": f"委托任务给 {self.slug} 子 Agent",
            "callable": self._sync_send_message,
        }
    
    def _sync_send_message(self, task: str, **kwargs) -> str:
        """同步发送消息 (用于 Tool 包装)"""
        import asyncio
        try:
            loop = asyncio.get_event_loop()
        except RuntimeError:
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
        
        result = loop.run_until_complete(self.send_message(task, **kwargs))
        
        if "result" in result:
            task_obj = result["result"]
            if "artifacts" in task_obj and task_obj["artifacts"]:
                for artifact in task_obj["artifacts"]:
                    if "parts" in artifact:
                        for part in artifact["parts"]:
                            if "text" in part:
                                return part["text"]
            return json.dumps(task_obj, ensure_ascii=False)
        
        return json.dumps(result, ensure_ascii=False)


class SubAgentRegistry:
    """子 Agent 注册表"""
    
    def __init__(self):
        self.clients: dict[str, A2AClient] = {}
    
    def register(
        self,
        slug: str,
        endpoint: str,
        version: str = "1.0.0",
        timeout: int = 60,
    ) -> A2AClient:
        """注册子 Agent"""
        client = A2AClient(slug, endpoint, version, timeout)
        self.clients[slug] = client
        return client
    
    def get(self, slug: str) -> Optional[A2AClient]:
        """获取子 Agent 客户端"""
        return self.clients.get(slug)
    
    async def delegate(
        self,
        agent_slug: str,
        task: str,
        context_id: Optional[str] = None,
    ) -> dict:
        """委托任务给子 Agent"""
        client = self.clients.get(agent_slug)
        if not client:
            raise ValueError(f"Unknown sub-agent: {agent_slug}")
        return await client.send_message(task, context_id=context_id)
    
    def as_tools(self) -> list[dict]:
        """将所有子 Agent 转换为 Tool 列表"""
        return [c.as_tool() for c in self.clients.values()]
