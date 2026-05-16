import pytest
import httpx
from fastapi.testclient import TestClient
from server.app import create_app
from server.config import AppConfig, ServerConfig, LLMConfig
from pathlib import Path

FIXTURES_DIR = Path(__file__).parent.parent / "fixtures"


@pytest.fixture
def app():
    config = AppConfig(
        config_dir=str(FIXTURES_DIR / "full-agent"),
        server=ServerConfig(host="127.0.0.1", port=0),
        llm=LLMConfig(
            api_key="test-key",
            model_id="test-model",
            base_url="https://api.test.com/v1",
        ),
    )
    return create_app(config)


@pytest.fixture
def client(app):
    return TestClient(app)


class TestServerIntegration:
    """服务器集成测试（使用 TestClient）"""

    def test_health(self, client):
        resp = client.get("/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "healthy"
        assert "Full Test Agent" in data["agent"]

    def test_root(self, client):
        resp = client.get("/")
        assert resp.status_code == 200
        data = resp.json()
        assert "oaf" in data
        assert "protocols" in data
        assert data["protocols"]["a2a"] == "1.0.0"

    def test_agent_card(self, client):
        resp = client.get("/.well-known/agent-card.json")
        assert resp.status_code == 200
        card = resp.json()
        assert card["name"] == "Full Test Agent"
        assert "extensions" in card
        has_a2ui = any("a2ui" in e.get("uri", "") for e in card.get("extensions", []))
        assert has_a2ui, "Agent Card should advertise A2UI support"

    def test_skills(self, client):
        resp = client.get("/skills")
        assert resp.status_code == 200
        skills = resp.json()
        assert isinstance(skills, list)
        assert any(s["name"] == "bash-tool" for s in skills)

    def test_mcp(self, client):
        resp = client.get("/mcp")
        assert resp.status_code == 200
        mcps = resp.json()
        assert isinstance(mcps, list)
        assert any(m["server"] == "filesystem" for m in mcps)

    def test_debug_page(self, client):
        resp = client.get("/debug")
        assert resp.status_code == 200
        assert "Agent Debug Console" in resp.text

    def test_jsonrpc_message_send(self, client):
        resp = client.post("/", json={
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {"message": {"role": "user", "parts": [{"text": "Hello"}]}},
            "id": "int-test-1",
        })
        assert resp.status_code == 200
        data = resp.json()
        assert data["jsonrpc"] == "2.0"
        assert "result" in data
        task = data["result"]
        assert "id" in task
        assert "status" in task
        assert "metadata" in task
        assert "thread_id" in task["metadata"]

    def test_rest_tasks(self, client):
        resp = client.post("/tasks", json={
            "message": {"role": "user", "parts": [{"text": "Test"}]},
        })
        assert resp.status_code == 200
        task = resp.json()
        assert "id" in task
        assert "metadata" in task
        assert "thread_id" in task["metadata"]

    def test_tasks_list(self, client):
        resp = client.get("/tasks")
        assert resp.status_code == 200
        data = resp.json()
        assert "threads" in data
        assert isinstance(data["threads"], list)

    def test_threads_list_rest(self, client):
        resp = client.get("/threads")
        assert resp.status_code == 200
        data = resp.json()
        assert isinstance(data, list)

    def test_threads_get_not_found(self, client):
        resp = client.get("/threads/nonexistent-id-12345")
        assert resp.status_code == 404

    def test_threads_delete_not_found(self, client):
        resp = client.delete("/threads/nonexistent-id-12345")
        assert resp.status_code == 404
