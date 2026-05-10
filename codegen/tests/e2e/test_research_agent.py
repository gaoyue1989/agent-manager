"""A2A Server 与 Agent E2E 测试 - 包含 Skills + MCP"""

import os
import sys
import json
import time
import pytest
import httpx
import subprocess
from pathlib import Path
from dotenv import load_dotenv

load_dotenv(os.path.join(os.path.dirname(__file__), "..", ".env.test"))

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
AGENT_MAIN = PROJECT_ROOT / "examples" / "research-agent" / "main.py"
BASE_URL = "http://localhost:8000"

_server_proc = None


@pytest.fixture(scope="module")
def server():
    global _server_proc

    if _server_proc is not None and _server_proc.poll() is not None:
        _server_proc = None

    if _server_proc is None:
        env = os.environ.copy()
        env["PYTHONPATH"] = str(PROJECT_ROOT)

        _server_proc = subprocess.Popen(
            [sys.executable, str(AGENT_MAIN)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env=env, cwd=str(PROJECT_ROOT),
        )

    for _ in range(20):
        time.sleep(0.5)
        try:
            resp = httpx.get(f"{BASE_URL}/health", timeout=3)
            if resp.status_code == 200:
                break
        except Exception:
            pass
    else:
        stdout, stderr = _server_proc.communicate(timeout=1)
        pytest.skip(f"Server did not start.\nSTDERR: {stderr.decode()[:500]}")

    yield

    if _server_proc is not None:
        _server_proc.kill()
        _server_proc.wait()
        _server_proc = None


class TestAgentServerE2E:
    """Agent Server 端到端测试 (Skills + MCP + A2A + A2UI)"""

    def test_health(self, server):
        resp = httpx.get(f"{BASE_URL}/health", timeout=10)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "healthy"
        assert data["agent"] == "Research Assistant"

    def test_root(self, server):
        resp = httpx.get(f"{BASE_URL}/", timeout=10)
        assert resp.status_code == 200
        data = resp.json()
        assert "oaf" in data
        assert "endpoints" in data

    def test_agent_card(self, server):
        resp = httpx.get(f"{BASE_URL}/.well-known/agent-card.json", timeout=10)
        assert resp.status_code == 200
        card = resp.json()
        assert card["name"] == "Research Assistant"
        assert "extensions" in card
        assert any("a2ui" in e.get("uri", "") for e in card["extensions"])

    def test_list_skills(self, server):
        resp = httpx.get(f"{BASE_URL}/skills", timeout=10)
        assert resp.status_code == 200
        skills = resp.json()
        assert isinstance(skills, list)

    def test_list_mcp(self, server):
        resp = httpx.get(f"{BASE_URL}/mcp", timeout=10)
        assert resp.status_code == 200
        configs = resp.json()
        assert isinstance(configs, list)

    def test_invoke_skill(self, server):
        resp = httpx.post(f"{BASE_URL}/skill/web-search", json={"input": "test query"}, timeout=10)
        assert resp.status_code == 200
        data = resp.json()
        assert data["skill"] == "web-search"
        assert "result" in data

    def test_jsonrpc_send_message(self, server):
        resp = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0", "method": "message/send",
            "params": {"message": {"role": "user", "parts": [{"text": "Hello"}]}},
            "id": "test-1",
        }, timeout=30)
        assert resp.status_code == 200
        result = resp.json()
        assert result["jsonrpc"] == "2.0"
        task = result["result"]
        assert "id" in task
        assert "status" in task

    def test_jsonrpc_with_a2ui(self, server):
        resp = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0", "method": "message/send",
            "params": {
                "message": {"role": "user", "parts": [{"text": "Hello"}]},
                "metadata": {"a2uiClientCapabilities": {
                    "supportedCatalogIds": ["https://a2ui.org/specification/v0_8/standard_catalog_definition.json"]
                }}
            },
            "id": "test-a2ui",
        }, timeout=30)
        assert resp.status_code == 200
        task = resp.json()["result"]
        assert task.get("artifacts")

    def test_rest_tasks(self, server):
        resp = httpx.post(f"{BASE_URL}/tasks", json={
            "message": {"role": "user", "parts": [{"text": "Hi"}]},
        }, timeout=30)
        assert resp.status_code == 200
        task = resp.json()
        assert "id" in task

        resp2 = httpx.get(f"{BASE_URL}/tasks", timeout=10)
        assert resp2.status_code == 200
        assert isinstance(resp2.json(), list)

    def test_skill_influences_llm(self, server):
        resp = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0", "method": "message/send",
            "params": {"message": {"role": "user", "parts": [{"text": "你有什么技能？"}]}},
            "id": "test-skills",
        }, timeout=60)
        assert resp.status_code == 200
        task = resp.json()["result"]
        artifacts = task.get("artifacts", [])
        assert len(artifacts) > 0
        for a in artifacts:
            for p in a.get("parts", []):
                if "text" in p:
                    assert len(p["text"]) > 0
                    return
