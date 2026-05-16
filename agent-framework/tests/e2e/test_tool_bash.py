"""E2E 测试: Tool (Bash) 真实 LLM 调用

使用天翼云 GLM-5 模型测试 Agent 的 Bash 工具调用能力
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
BASE_URL = "http://127.0.0.1:8002"


def _get_config():
    return AppConfig(
        config_dir=str(PROJECT_ROOT / "fixtures" / "full-agent"),
        server=ServerConfig(host="127.0.0.1", port=8002),
        llm=LLMConfig(
            api_key=os.getenv("LLM_API_KEY", ""),
            model_id=os.getenv("LLM_MODEL_ID", ""),
            base_url=os.getenv("LLM_BASE_URL", ""),
            provider=os.getenv("LLM_PROVIDER", "ctyun"),
            temperature=float(os.getenv("LLM_TEMPERATURE", "0.7")),
            max_tokens=int(os.getenv("LLM_MAX_TOKENS", "4096")),
        ),
    )


_server_thread = None


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
        kwargs={"host": "127.0.0.1", "port": 8002, "log_level": "error"},
        daemon=True,
    )
    _server_thread.start()

    for _ in range(20):
        time.sleep(0.3)
        try:
            resp = httpx.get(f"{BASE_URL}/health", timeout=3)
            if resp.status_code == 200:
                break
        except Exception:
            pass
    else:
        pytest.skip("Server did not start in time")

    yield


@pytest.mark.e2e
class TestToolBashE2E:
    """Bash Tool E2E 测试"""

    def test_health(self, server):
        resp = httpx.get(f"{BASE_URL}/health", timeout=10)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "healthy"

    def test_agent_card(self, server):
        resp = httpx.get(f"{BASE_URL}/.well-known/agent-card.json", timeout=10)
        assert resp.status_code == 200
        card = resp.json()
        assert "name" in card

    def test_simple_query(self, server):
        """简单查询 - 验证 LLM 连接"""
        resp = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {"message": {"role": "user", "parts": [{"text": "你好，请用中文回复，说一个数字"}]}},
            "id": "tool-test-1",
        }, timeout=60)
        assert resp.status_code == 200
        task = resp.json()["result"]
        assert "status" in task
        artifacts = task.get("artifacts", [])
        assert len(artifacts) > 0
        has_text = False
        for art in artifacts:
            for part in art.get("parts", []):
                if "text" in part and len(part["text"]) > 0:
                    has_text = True
        assert has_text, "Agent should return text response"

    def test_bash_echo(self, server):
        """测试 Bash echo 命令提示"""
        resp = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {"message": {"role": "user", "parts": [{"text": "请用 Bash 执行 echo 'e2e_test_pass' 并告诉我结果"}]}},
            "id": "tool-test-2",
        }, timeout=120)
        assert resp.status_code == 200
        task = resp.json()["result"]
        artifacts = task.get("artifacts", [])
        all_text = ""
        for art in artifacts:
            for part in art.get("parts", []):
                if "text" in part:
                    all_text += part["text"]
        assert len(all_text) > 0, "Agent should respond to bash command request"

    def test_bash_ls(self, server):
        """测试 Bash ls 命令"""
        resp = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {"message": {"role": "user", "parts": [{"text": "请用 Bash 执行 ls /tmp 并列出结果"}]}},
            "id": "tool-test-3",
        }, timeout=120)
        assert resp.status_code == 200
        task = resp.json()["result"]
        assert task["status"]["state"] in ("completed", "working")
