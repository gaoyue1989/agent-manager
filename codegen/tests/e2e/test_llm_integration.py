"""LLM 集成测试 - 使用真实 API"""

import os
import pytest
import httpx

from dotenv import load_dotenv

load_dotenv(os.path.join(os.path.dirname(__file__), "..", ".env.test"))


class TestLLMIntegration:
    """LLM 真实调用测试"""
    
    @pytest.fixture
    def llm_config(self):
        """加载 LLM 配置"""
        return {
            "api_key": os.getenv("LLM_API_KEY"),
            "model_id": os.getenv("LLM_MODEL_ID"),
            "base_url": os.getenv("LLM_BASE_URL"),
            "provider": os.getenv("LLM_PROVIDER"),
        }
    
    @pytest.mark.asyncio
    async def test_llm_connection(self, llm_config):
        """测试 LLM 连接"""
        headers = {
            "Authorization": f"Bearer {llm_config['api_key']}",
            "Content-Type": "application/json",
        }
        
        payload = {
            "model": llm_config["model_id"],
            "messages": [{"role": "user", "content": "Hello"}],
            "max_tokens": 10,
        }
        
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{llm_config['base_url']}/chat/completions",
                headers=headers,
                json=payload,
            )
            
            assert resp.status_code == 200
            data = resp.json()
            assert "choices" in data
            assert len(data["choices"]) > 0
            assert "message" in data["choices"][0]
    
    @pytest.mark.asyncio
    async def test_llm_chat_completion(self, llm_config):
        """测试 LLM 对话完成"""
        headers = {
            "Authorization": f"Bearer {llm_config['api_key']}",
            "Content-Type": "application/json",
        }
        
        payload = {
            "model": llm_config["model_id"],
            "messages": [
                {"role": "system", "content": "你是一个有用的助手。"},
                {"role": "user", "content": "请说你好"},
            ],
            "max_tokens": 50,
            "temperature": 0.7,
        }
        
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{llm_config['base_url']}/chat/completions",
                headers=headers,
                json=payload,
            )
            
            assert resp.status_code == 200
            data = resp.json()
            
            msg = data["choices"][0]["message"]
            content = msg.get("content", "") or msg.get("reasoning_content", "")
            assert len(content) > 0
    
    @pytest.mark.asyncio
    async def test_llm_multi_turn(self, llm_config):
        """测试 LLM 多轮对话"""
        headers = {
            "Authorization": f"Bearer {llm_config['api_key']}",
            "Content-Type": "application/json",
        }
        
        messages = [
            {"role": "system", "content": "你是一个有用的助手。"},
            {"role": "user", "content": "我的名字是Alice"},
        ]
        
        payload = {
            "model": llm_config["model_id"],
            "messages": messages,
            "max_tokens": 50,
        }
        
        async with httpx.AsyncClient(timeout=30) as client:
            resp1 = await client.post(
                f"{llm_config['base_url']}/chat/completions",
                headers=headers,
                json=payload,
            )
            
            assert resp1.status_code == 200
            data1 = resp1.json()
            assistant_msg = data1["choices"][0]["message"]
            
            messages.append(assistant_msg)
            messages.append({"role": "user", "content": "我叫什么名字？"})
            
            payload["messages"] = messages
            
            resp2 = await client.post(
                f"{llm_config['base_url']}/chat/completions",
                headers=headers,
                json=payload,
            )
            
            assert resp2.status_code == 200
            data2 = resp2.json()
            msg2 = data2["choices"][0]["message"]
            content = msg2.get("content", "") or msg2.get("reasoning_content", "")
            
            if content:
                assert "Alice" in content or "alice" in content.lower()
