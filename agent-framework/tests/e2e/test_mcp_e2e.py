"""E2E 测试: MCP 集成

使用 Mock MCP Server 测试 Agent 的 MCP 工具调用能力
"""

import os
import sys
import json
import time
import pytest
import httpx
import threading
from pathlib import Path
from dotenv import load_dotenv

load_dotenv(Path(__file__).parent.parent / ".env.test")

import uvicorn
from server.app import create_app
from server.config import AppConfig, ServerConfig, LLMConfig

PROJECT_ROOT = Path(__file__).resolve().parent.parent
AGENT_BASE_URL = "http://127.0.0.1:8003"

_server_thread = None


def _get_config():
    return AppConfig(
        config_dir=str(PROJECT_ROOT / "fixtures" / "full-agent"),
        server=ServerConfig(host="127.0.0.1", port=8003),
        llm=LLMConfig(
            api_key=os.getenv("LLM_API_KEY", ""),
            model_id=os.getenv("LLM_MODEL_ID", ""),
            base_url=os.getenv("LLM_BASE_URL", ""),
            provider=os.getenv("LLM_PROVIDER", "ctyun"),
        ),
    )


@pytest.fixture(scope="module")
def server():
    global _server_thread

    config = _get_config()
    if not config.llm.is_valid():
        pytest.skip("LLM_API_KEY not set, skipping E2E test")

    app = create_app(config)

    _server_thread = threading.Thread(
        target=uvicorn.run,
        args=(app,),
        kwargs={"host": "127.0.0.1", "port": 8003, "log_level": "error"},
        daemon=True,
    )
    _server_thread.start()

    for _ in range(20):
        time.sleep(0.3)
        try:
            resp = httpx.get(f"{AGENT_BASE_URL}/health", timeout=3)
            if resp.status_code == 200:
                break
        except Exception:
            pass
    else:
        pytest.skip("Server did not start in time")

    yield


@pytest.mark.e2e
class TestMCPE2E:
    """MCP E2E 测试"""

    def test_health(self, server):
        resp = httpx.get(f"{AGENT_BASE_URL}/health", timeout=10)
        assert resp.status_code == 200
        assert resp.json()["status"] == "healthy"

    def test_mcp_endpoint(self, server):
        resp = httpx.get(f"{AGENT_BASE_URL}/mcp", timeout=10)
        assert resp.status_code == 200
        mcps = resp.json()
        assert isinstance(mcps, list)
        assert any(m.get("server") == "filesystem" for m in mcps)

    def test_agent_aware_of_mcp(self, server):
        resp = httpx.post(f"{AGENT_BASE_URL}/", json={
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {"message": {"role": "user", "parts": [{"text": "What tools do you have for file operations? Answer briefly in English."}]}},
            "id": "mcp-test-1",
        }, timeout=60)
        assert resp.status_code == 200
        task = resp.json()["result"]
        all_text = ""
        for art in task.get("artifacts", []):
            for part in art.get("parts", []):
                if "text" in part:
                    all_text += part["text"]
        assert len(all_text) > 10, "Agent should respond about its tools"
