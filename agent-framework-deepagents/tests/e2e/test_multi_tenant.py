"""多租户隔离端到端测试

验证基于 checkpoint_ns 的多租户数据隔离：
- 不同 Agent 使用相同 thread_id 时数据完全隔离
- list_threads 只返回当前 Agent 的 threads
- delete_thread 只删除当前 Agent 的 thread
"""

import os
import sys
import json
import time
import pytest
import httpx
import threading
import asyncio
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))
PROJECT_ROOT = Path(__file__).resolve().parent.parent

import uvicorn
from server.app import create_app
from server.config import AppConfig, ServerConfig, LLMConfig, MySQLCheckpointConfig
from server.services.checkpoint_manager import CheckpointManager
from dotenv import load_dotenv

load_dotenv(PROJECT_ROOT / ".env.test")


def get_llm_config():
    return LLMConfig(
        api_key=os.getenv("LLM_API_KEY", ""),
        model_id=os.getenv("LLM_MODEL_ID", ""),
        base_url=os.getenv("LLM_BASE_URL", ""),
        provider=os.getenv("LLM_PROVIDER", "ctyun"),
        temperature=float(os.getenv("LLM_TEMPERATURE", "0.7")),
        max_tokens=int(os.getenv("LLM_MAX_TOKENS", "4096")),
    )


def get_checkpoint_dsn():
    return os.getenv(
        "CHECKPOINT_MYSQL_DSN",
        "mysql+asyncmy://agent_manager:Agent%40Manager2026@127.0.0.1:3307/agent_manager_test",
    )


class MultiTenantTestServer:
    def __init__(self, config_dir: str, port: int, name: str):
        self.config_dir = config_dir
        self.port = port
        self.name = name
        self.app = None
        self.thread = None
        self.config = None

    def start(self):
        self.config = AppConfig(
            config_dir=self.config_dir,
            server=ServerConfig(host="127.0.0.1", port=self.port),
            llm=get_llm_config(),
            checkpoint=MySQLCheckpointConfig(dsn=get_checkpoint_dsn()),
        )
        self.app = create_app(self.config)
        self.thread = threading.Thread(
            target=uvicorn.run,
            args=(self.app,),
            kwargs={"host": "127.0.0.1", "port": self.port, "log_level": "error"},
            daemon=True,
        )
        self.thread.start()
        time.sleep(3)

    def url(self):
        return f"http://127.0.0.1:{self.port}"

    def stop(self):
        pass


@pytest.fixture(scope="module")
def servers():
    if not get_llm_config().is_valid():
        pytest.skip("LLM not configured, skipping multi-tenant E2E test")

    server_a = MultiTenantTestServer(
        config_dir=str(PROJECT_ROOT / "fixtures" / "tenant-a-agent"),
        port=8111,
        name="agent-a",
    )
    server_b = MultiTenantTestServer(
        config_dir=str(PROJECT_ROOT / "fixtures" / "tenant-b-agent"),
        port=8112,
        name="agent-b",
    )

    server_a.start()
    server_b.start()

    yield {"a": server_a, "b": server_b}


class TestMultiTenantIsolation:
    """多租户隔离测试"""

    @pytest.fixture
    def api_a(self, servers):
        return servers["a"].url()

    @pytest.fixture
    def api_b(self, servers):
        return servers["b"].url()

    @pytest.fixture(scope="class")
    def shared_thread_id(self):
        return f"shared-thread-{int(time.time() * 1000)}"

    def test_01_health_check_shows_tenant_prefix(self, api_a, api_b):
        resp_a = httpx.get(f"{api_a}/health", timeout=10)
        resp_b = httpx.get(f"{api_b}/health", timeout=10)

        assert resp_a.status_code == 200
        assert resp_b.status_code == 200

        data_a = resp_a.json()
        data_b = resp_b.json()

        assert data_a["checkpoint"] is True
        assert data_b["checkpoint"] is True
        assert "tenant_prefix" in data_a
        assert "tenant_prefix" in data_b
        assert data_a["tenant_prefix"] == "tenant-a/agent-x"
        assert data_b["tenant_prefix"] == "tenant-b/agent-y"

        print(f"Agent A tenant_prefix: {data_a['tenant_prefix']}")
        print(f"Agent B tenant_prefix: {data_b['tenant_prefix']}")

    def test_02_send_message_same_thread_id(self, api_a, api_b, shared_thread_id):
        payload = {
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {
                "message": {"role": "user", "parts": [{"text": "Remember: I am Agent A"}]},
                "metadata": {"thread_id": shared_thread_id},
            },
            "id": "1",
        }
        resp_a = httpx.post(f"{api_a}/", json=payload, timeout=120)
        assert resp_a.status_code == 200
        data_a = resp_a.json()
        assert "result" in data_a
        print(f"Agent A response: {data_a['result']['artifacts'][0]['parts'][0]['text'][:100]}")

        payload["params"]["message"]["parts"][0]["text"] = "Remember: I am Agent B"
        resp_b = httpx.post(f"{api_b}/", json=payload, timeout=120)
        assert resp_b.status_code == 200
        data_b = resp_b.json()
        assert "result" in data_b
        print(f"Agent B response: {data_b['result']['artifacts'][0]['parts'][0]['text'][:100]}")

    def test_03_thread_state_isolated(self, api_a, api_b, shared_thread_id):
        time.sleep(0.5)

        resp_a = httpx.get(f"{api_a}/threads/{shared_thread_id}", timeout=10)
        resp_b = httpx.get(f"{api_b}/threads/{shared_thread_id}", timeout=10)

        assert resp_a.status_code == 200
        assert resp_b.status_code == 200

        state_a = resp_a.json()
        state_b = resp_b.json()

        msgs_a = state_a.get("messages", [])
        msgs_b = state_b.get("messages", [])

        assert len(msgs_a) >= 1, f"Agent A should have messages, got: {state_a}"
        assert len(msgs_b) >= 1, f"Agent B should have messages, got: {state_b}"

        user_msgs_a = [m for m in msgs_a if m.get("role") == "user"]
        user_msgs_b = [m for m in msgs_b if m.get("role") == "user"]

        assert len(user_msgs_a) >= 1
        assert len(user_msgs_b) >= 1

        content_a = user_msgs_a[0].get("content", "")
        content_b = user_msgs_b[0].get("content", "")

        assert "Agent A" in content_a, f"Agent A should remember 'Agent A', got: {content_a}"
        assert "Agent B" in content_b, f"Agent B should remember 'Agent B', got: {content_b}"

        print(f"Agent A first user message: {content_a}")
        print(f"Agent B first user message: {content_b}")

    def test_04_list_threads_shows_own_threads(self, api_a, api_b, shared_thread_id):
        resp_a = httpx.get(f"{api_a}/threads", timeout=10)
        resp_b = httpx.get(f"{api_b}/threads", timeout=10)

        assert resp_a.status_code == 200
        assert resp_b.status_code == 200

        threads_a = resp_a.json()
        threads_b = resp_b.json()

        thread_ids_a = [t["thread_id"] for t in threads_a]
        thread_ids_b = [t["thread_id"] for t in threads_b]

        assert shared_thread_id in thread_ids_a, f"Agent A should see thread {shared_thread_id}"
        assert shared_thread_id in thread_ids_b, f"Agent B should see thread {shared_thread_id}"

        print(f"Agent A threads: {thread_ids_a[:5]}")
        print(f"Agent B threads: {thread_ids_b[:5]}")

    def test_05_delete_thread_isolated(self, api_a, api_b, shared_thread_id):
        resp_a = httpx.delete(f"{api_a}/threads/{shared_thread_id}", timeout=10)
        assert resp_a.status_code == 200
        data_a = resp_a.json()
        assert data_a["deleted"] is True
        print(f"Agent A deleted thread: {data_a}")

        time.sleep(0.5)

        resp_b = httpx.get(f"{api_b}/threads/{shared_thread_id}", timeout=10)
        assert resp_b.status_code == 200
        state_b = resp_b.json()
        msgs_b = state_b.get("messages", [])
        assert len(msgs_b) >= 1, "Agent B thread should still exist after Agent A deletes its thread"
        print(f"Agent B thread still exists with {len(msgs_b)} messages")

    def test_06_deleted_thread_not_found_for_a(self, api_a, shared_thread_id):
        resp = httpx.get(f"{api_a}/threads/{shared_thread_id}", timeout=10)
        assert resp.status_code == 404
        print("Agent A thread correctly returns 404 after deletion")


class TestCheckpointNamespaceDirect:
    """直接测试 CheckpointManager 的多租户方法"""

    @pytest.mark.asyncio
    async def test_delete_thread_by_ns(self):
        dsn = get_checkpoint_dsn()
        cm = CheckpointManager(dsn)

        await cm.start()
        assert cm.saver is not None

        thread_id = f"test-ns-{int(time.time() * 1000)}"
        checkpoint_ns = "test-namespace-direct"

        deleted = await cm.delete_thread_by_ns(thread_id, checkpoint_ns)
        assert deleted is True

        await cm.close()
        print(f"delete_thread_by_ns returned: {deleted}")
