"""E2E 测试: Skill 集成

使用真实 LLM 测试 Agent 的 Skill 调用能力
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
BASE_URL = "http://127.0.0.1:8004"

_server_thread = None


def _get_config():
    return AppConfig(
        config_dir=str(PROJECT_ROOT / "fixtures" / "full-agent"),
        server=ServerConfig(host="127.0.0.1", port=8004),
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
        kwargs={"host": "127.0.0.1", "port": 8004, "log_level": "error"},
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
class TestSkillE2E:
    """Skill E2E 测试"""

    def test_health(self, server):
        resp = httpx.get(f"{BASE_URL}/health", timeout=10)
        assert resp.status_code == 200
        assert resp.json()["status"] == "healthy"

    def test_skills_endpoint(self, server):
        resp = httpx.get(f"{BASE_URL}/skills", timeout=10)
        assert resp.status_code == 200
        skills = resp.json()
        assert isinstance(skills, list)
        assert len(skills) >= 1
        names = [s["name"] for s in skills]
        assert "bash-tool" in names

    def test_agent_aware_of_skills(self, server):
        resp = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {"message": {"role": "user", "parts": [{"text": "What skills are available to you? Answer briefly."}]}},
            "id": "skill-test-1",
        }, timeout=60)
        assert resp.status_code == 200
        task = resp.json()["result"]
        all_text = ""
        for art in task.get("artifacts", []):
            for part in art.get("parts", []):
                if "text" in part:
                    all_text += part["text"]
        assert len(all_text) > 10, "Agent should respond about its skills"

    def test_bash_skill_via_agent(self, server):
        resp = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {"message": {"role": "user", "parts": [{"text": "请使用 bash-tool skill 执行命令 'echo skill_test_pass'，并告诉我结果"}]}},
            "id": "skill-test-2",
        }, timeout=120)
        assert resp.status_code == 200
        task = resp.json()["result"]
        artifacts = task.get("artifacts", [])
        all_text = ""
        for art in artifacts:
            for part in art.get("parts", []):
                if "text" in part:
                    all_text += part["text"]
        assert len(all_text) > 0, "Agent should respond with skill execution result"

    def test_pwd_skill(self, server):
        resp = httpx.post(f"{BASE_URL}/", json={
            "jsonrpc": "2.0",
            "method": "message/send",
            "params": {"message": {"role": "user", "parts": [{"text": "请用 bash 执行 pwd 命令，告诉我当前工作目录"}]}},
            "id": "skill-test-3",
        }, timeout=120)
        assert resp.status_code == 200
        task = resp.json()["result"]
        assert task["status"]["state"] in ("completed", "working")
