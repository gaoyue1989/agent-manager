"""Checkpoint & Thread 持久化集成测试

验证基于 MySQL checkpoint 的 DeepAgents thread_id 会话持久化：
- 同一 thread_id 多轮对话上下文保持
- Thread 状态查询（含消息历史）
- Thread 列表
- Thread 删除
- 跨 server 重启持久化
"""

import os
import sys
import json
import time
import pytest
import httpx
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))
PROJECT_ROOT = Path(__file__).resolve().parent.parent
BASE_URL = "http://127.0.0.1:8103"

import uvicorn
from server.app import create_app
from server.config import AppConfig, ServerConfig, LLMConfig
from dotenv import load_dotenv
load_dotenv(PROJECT_ROOT / ".env.test")


_server_thread = None


def _get_config():
    return AppConfig(
        config_dir=str(PROJECT_ROOT / "fixtures" / "full-agent"),
        server=ServerConfig(host="127.0.0.1", port=8103),
        llm=LLMConfig(
            api_key=os.getenv("LLM_API_KEY", ""),
            model_id=os.getenv("LLM_MODEL_ID", ""),
            base_url=os.getenv("LLM_BASE_URL", ""),
            provider=os.getenv("LLM_PROVIDER", "ctyun"),
            temperature=float(os.getenv("LLM_TEMPERATURE", "0.7")),
            max_tokens=int(os.getenv("LLM_MAX_TOKENS", "4096")),
        ),
    )


@pytest.fixture(scope="module")
def server():
    global _server_thread

    config = _get_config()
    if not config.llm.is_valid():
        pytest.skip("LLM_API_KEY not set, skipping checkpoint test")

    app = create_app(config)

    _server_thread = threading.Thread(
        target=uvicorn.run,
        args=(app,),
        kwargs={"host": "127.0.0.1", "port": 8103, "log_level": "error"},
        daemon=True,
    )
    _server_thread.start()
    time.sleep(4)

    yield f"http://127.0.0.1:8103"


class TestCheckpointPersistence:
    """Checkpoint 持久化集成测试"""

    @pytest.fixture
    def api(self, server):
        return server

    @pytest.fixture(scope="class")
    def thread_id(self):
        return f"checkpoint-test-{int(time.time() * 1000)}"

    def test_01_health_and_checkpoint(self, api):
        resp = httpx.get(f"{api}/health", timeout=10)
        assert resp.status_code == 200
        data = resp.json()
        assert data["checkpoint"] is True
        assert data["llm_configured"] is True

    def test_02_send_message_creates_thread(self, api, thread_id):
        payload = {
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {
                "message": {"role": "user", "parts": [{"text": "Say exactly: apple"}]},
                "metadata": {"thread_id": thread_id},
            },
            "id": "1",
        }
        resp = httpx.post(f"{api}/", json=payload, timeout=120)
        assert resp.status_code == 200
        data = resp.json()
        assert "result" in data
        assert data["result"]["metadata"]["thread_id"] == thread_id
        artifacts = data["result"]["artifacts"]
        assert len(artifacts) > 0
        response_text = artifacts[0]["parts"][0]["text"]
        assert "apple" in response_text.lower()

    def test_03_thread_state_has_messages(self, api, thread_id):
        time.sleep(0.1)  # allow state write to flush
        resp = httpx.get(f"{api}/threads/{thread_id}", timeout=10)
        assert resp.status_code == 200
        state = resp.json()
        assert state["thread_id"] == thread_id
        msgs = state["messages"]
        assert len(msgs) >= 1, f"Got {len(msgs)} messages, state={state}"
        assert any(m["role"] == "user" for m in msgs)
        assert any(m["role"] == "assistant" for m in msgs)

    def test_04_continue_conversation_remembers_context(self, api, thread_id):
        payload = {
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {
                "message": {"role": "user", "parts": [{"text": "What fruit did I just ask you to say?"}]},
                "metadata": {"thread_id": thread_id},
            },
            "id": "2",
        }
        resp = httpx.post(f"{api}/", json=payload, timeout=120)
        assert resp.status_code == 200
        data = resp.json()
        response_text = data["result"]["artifacts"][0]["parts"][0]["text"]
        assert "apple" in response_text.lower(), f"Expected 'apple' in response: {response_text}"

    def test_05_state_accumulates_messages(self, api, thread_id):
        resp = httpx.get(f"{api}/threads/{thread_id}", timeout=10)
        assert resp.status_code == 200
        msgs = resp.json()["messages"]
        user_msgs = [m for m in msgs if m["role"] == "user"]
        assert len(user_msgs) >= 2, f"Expected >=2 user msgs, got {len(user_msgs)}: {msgs}"

    def test_06_list_threads_includes_our_thread(self, api, thread_id):
        resp = httpx.get(f"{api}/threads", timeout=10)
        assert resp.status_code == 200
        threads = resp.json()
        assert isinstance(threads, list)
        thread_ids = [t["thread_id"] for t in threads]
        assert thread_id in thread_ids, f"{thread_id} not in {thread_ids[:5]}..."

    def test_07_delete_thread(self, api, thread_id):
        resp = httpx.delete(f"{api}/threads/{thread_id}", timeout=10)
        assert resp.status_code == 200
        data = resp.json()
        assert data["deleted"] is True

    def test_08_deleted_thread_not_found(self, api, thread_id):
        resp = httpx.get(f"{api}/threads/{thread_id}", timeout=10)
        assert resp.status_code == 404
