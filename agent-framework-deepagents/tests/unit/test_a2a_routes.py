import pytest
from fastapi.testclient import TestClient
from server.app import create_app


class TestA2ARoutes:
    """A2A 路由单元测试"""

    @pytest.fixture
    def client(self, app_config):
        app = create_app(app_config)
        return TestClient(app)

    def test_health_endpoint(self, client):
        resp = client.get("/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "healthy"
        assert "agent" in data

    def test_root_endpoint(self, client):
        resp = client.get("/")
        assert resp.status_code == 200
        data = resp.json()
        assert "agent" in data
        assert "protocols" in data
        assert "endpoints" in data
        assert "oaf" in data

    def test_agent_card_endpoint(self, client):
        resp = client.get("/.well-known/agent-card.json")
        assert resp.status_code == 200
        data = resp.json()
        assert "name" in data
        assert "description" in data
        assert "capabilities" in data

    def test_skills_endpoint(self, client):
        resp = client.get("/skills")
        assert resp.status_code == 200
        assert isinstance(resp.json(), list)

    def test_mcp_endpoint(self, client):
        resp = client.get("/mcp")
        assert resp.status_code == 200
        assert isinstance(resp.json(), list)

    def test_debug_page(self, client):
        resp = client.get("/debug")
        assert resp.status_code == 200
        assert "Agent Debug Console" in resp.text or "<html" in resp.text.lower()

    def test_jsonrpc_send_message(self, client):
        resp = client.post("/", json={
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {
                "message": {
                    "role": "user",
                    "parts": [{"text": "Hello"}],
                },
            },
            "id": "test-1",
        })
        assert resp.status_code == 200
        data = resp.json()
        assert data["jsonrpc"] == "2.0"
        assert "result" in data

        task = data["result"]
        assert "id" in task
        assert "status" in task
        assert task["status"]["state"] in ("completed", "working", "failed")  # failed is ok in unit test (no real LLM)

    def test_jsonrpc_method_not_found(self, client):
        resp = client.post("/", json={
            "jsonrpc": "2.0",
            "method": "nonexistent/method",
            "params": {},
            "id": "test-2",
        })
        assert resp.status_code == 200
        data = resp.json()
        assert "error" in data
        assert data["error"]["code"] == -32601

    def test_jsonrpc_send_message_with_a2ui(self, client):
        resp = client.post("/", json={
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {
                "message": {
                    "role": "user",
                    "parts": [{"text": "Hi there"}],
                },
                "metadata": {
                    "a2uiClientCapabilities": {
                        "supportedCatalogIds": ["https://a2ui.org/specification/v0_8/standard_catalog_definition.json"],
                    },
                },
            },
            "id": "test-a2ui",
        })
        assert resp.status_code == 200
        task = resp.json()["result"]
        assert "artifacts" in task

    def test_rest_tasks_endpoint(self, client):
        resp = client.post("/tasks", json={
            "message": {
                "role": "user",
                "parts": [{"text": "Test rest"}],
            },
        })
        assert resp.status_code == 200
        task = resp.json()
        assert "id" in task

    def test_tasks_list(self, client):
        resp = client.get("/tasks")
        assert resp.status_code == 200
        data = resp.json()
        assert "threads" in data
        assert isinstance(data["threads"], list)

    def test_task_get_not_found(self, client):
        resp = client.get("/tasks/nonexistent-id")
        assert resp.status_code == 200
        data = resp.json()
        assert "error" in data
