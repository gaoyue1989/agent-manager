"""E2E 测试: 自定义工具真实 LLM 调用

使用天翼云 GLM-5 模型测试 Agent 的自定义工具调用能力
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
BASE_URL = "http://127.0.0.1:8006"


def _get_config():
    return AppConfig(
        config_dir=str(PROJECT_ROOT / "fixtures" / "full-agent"),
        server=ServerConfig(host="127.0.0.1", port=8006),
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
        kwargs={"host": "127.0.0.1", "port": 8006, "log_level": "error"},
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
class TestCustomToolE2E:
    """自定义工具 E2E 测试"""

    def test_health(self, server):
        resp = httpx.get(f"{BASE_URL}/health", timeout=10)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "healthy"

    def test_tools_endpoint(self, server):
        """测试 /tools 端点返回自定义工具"""
        resp = httpx.get(f"{BASE_URL}/tools", timeout=10)
        assert resp.status_code == 200
        data = resp.json()

        assert "builtin" in data
        assert "custom" in data
        assert "mcp" in data

        assert "echo" in data["builtin"], f"echo should be in tools list, got: {data['builtin']}"

        custom_tools = data["custom"]
        echo_found = any(t["name"] == "echo" for t in custom_tools)
        assert echo_found, f"echo should be in custom tools, got: {custom_tools}"

    def test_root_endpoint_tools(self, server):
        """测试根端点显示工具信息"""
        resp = httpx.get(f"{BASE_URL}/", timeout=10)
        assert resp.status_code == 200
        data = resp.json()
        assert "oaf" in data
        assert "tools" in data["oaf"]
        assert "echo" in data["oaf"]["tools"]

    def test_echo_tool_invoke(self, server):
        """测试 LLM 调用 echo 工具"""
        resp = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {"message": {"role": "user", "parts": [{"text": "请使用 echo 工具，输入 'hello_custom_tool'，告诉我结果"}]}},
            "id": "custom-tool-test-1",
        }, timeout=120)
        assert resp.status_code == 200
        task = resp.json()["result"]
        assert "status" in task

        artifacts = task.get("artifacts", [])
        all_text = ""
        for art in artifacts:
            for part in art.get("parts", []):
                if "text" in part:
                    all_text += part["text"]

        assert len(all_text) > 0, "Agent should respond to echo tool request"

    def test_echo_tool_streaming(self, server):
        """测试流式调用 echo 工具"""
        resp = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0",
            "method": "message/stream",
            "params": {"message": {"role": "user", "parts": [{"text": "用 echo 工具返回 'streaming_test'"}]}},
            "id": "custom-tool-test-2",
        }, timeout=120)

        assert resp.status_code == 200
        content_type = resp.headers.get("content-type", "")
        assert "text/event-stream" in content_type

        body = resp.text
        assert "data:" in body

        tokens = []
        tool_calls = []
        for line in body.split("\n"):
            if line.startswith("data: "):
                data_str = line[6:]
                if data_str == "[DONE]":
                    continue
                try:
                    data = json.loads(data_str)
                    if "token" in data:
                        tokens.append(data["token"])
                    if "tool_call" in data:
                        tool_calls.append(data["tool_call"])
                except json.JSONDecodeError:
                    pass

        full_text = "".join(tokens)
        assert len(full_text) > 0 or len(tool_calls) > 0, "Should have response or tool call"

    def test_custom_tool_with_context(self, server):
        """测试自定义工具在对话上下文中工作"""
        thread_id = f"test-thread-{int(time.time())}"

        resp1 = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {
                "message": {"role": "user", "parts": [{"text": "我的名字是 Alice"}]},
                "thread_id": thread_id,
            },
            "id": "custom-tool-test-3a",
        }, timeout=60)
        assert resp1.status_code == 200

        resp2 = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {
                "message": {"role": "user", "parts": [{"text": "用 echo 工具重复我的名字"}]},
                "thread_id": thread_id,
            },
            "id": "custom-tool-test-3b",
        }, timeout=120)
        assert resp2.status_code == 200

        task = resp2.json()["result"]
        artifacts = task.get("artifacts", [])
        all_text = ""
        for art in artifacts:
            for part in art.get("parts", []):
                if "text" in part:
                    all_text += part["text"]

        assert len(all_text) > 0, "Agent should respond with context"
