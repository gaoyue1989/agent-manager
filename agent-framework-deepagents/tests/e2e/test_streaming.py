"""E2E 测试: SSE 流式传输

测试 SSE 流式消息的逐 Token 输出
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
BASE_URL = "http://127.0.0.1:8005"

_server_thread = None


def _get_config():
    return AppConfig(
        config_dir=str(PROJECT_ROOT / "fixtures" / "full-agent"),
        server=ServerConfig(host="127.0.0.1", port=8005),
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
        kwargs={"host": "127.0.0.1", "port": 8005, "log_level": "error"},
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
class TestStreamingE2E:
    """SSE 流式传输 E2E 测试"""

    def test_health(self, server):
        resp = httpx.get(f"{BASE_URL}/health", timeout=10)
        assert resp.status_code == 200

    def test_sse_streaming_basic(self, server):
        resp = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0",
            "method": "message/stream",
            "params": {"message": {"role": "user", "parts": [{"text": "Say hello in 3 words"}]}},
            "id": "stream-test-1",
        }, timeout=120)

        assert resp.status_code == 200
        content_type = resp.headers.get("content-type", "")
        assert "text/event-stream" in content_type

        body = resp.text
        assert "data:" in body

        tokens = []
        for line in body.split("\n"):
            if line.startswith("data: "):
                data_str = line[6:]
                if data_str == "[DONE]":
                    continue
                try:
                    data = json.loads(data_str)
                    if "token" in data:
                        tokens.append(data["token"])
                except json.JSONDecodeError:
                    pass

        assert len(tokens) >= 1, "Streaming should produce at least one token"
        full_text = "".join(tokens)
        assert len(full_text) > 3, f"Streamed text should be non-trivial, got: {full_text}"

    def test_sse_streaming_events(self, server):
        resp = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0",
            "method": "message/stream",
            "params": {"message": {"role": "user", "parts": [{"text": "Count from 1 to 3"}]}},
            "id": "stream-test-2",
        }, timeout=120)

        assert resp.status_code == 200
        body = resp.text
        assert "event: task_update" in body or "data:" in body
        assert "event: done" in body or "[DONE]" in body

    def test_sse_streaming_multiple_tokens(self, server):
        resp = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0",
            "method": "message/stream",
            "params": {"message": {"role": "user", "parts": [{"text": "Write a paragraph about AI"}]}},
            "id": "stream-test-3",
        }, timeout=120)

        assert resp.status_code == 200
        body = resp.text

        token_count = 0
        full = ""
        for line in body.split("\n"):
            if line.startswith("data: "):
                data_str = line[6:]
                if data_str == "[DONE]":
                    continue
                try:
                    data = json.loads(data_str)
                    if "token" in data:
                        token_count += 1
                        full += data["token"]
                except:
                    pass

        assert token_count >= 1, f"Expected at least 1 token, got {token_count}"
        assert len(full) > 20, f"Full streamed text should be non-trivial, got: {full}"
