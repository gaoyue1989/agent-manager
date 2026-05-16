import os
import sys
from pathlib import Path

import pytest
from dotenv import load_dotenv

sys.path.insert(0, str(Path(__file__).parent.parent))

env_test_path = Path(__file__).parent / ".env.test"
load_dotenv(env_test_path)


FIXTURES_DIR = Path(__file__).parent / "fixtures"
MINIMAL_AGENT_DIR = FIXTURES_DIR / "minimal-agent"
FULL_AGENT_DIR = FIXTURES_DIR / "full-agent"


@pytest.fixture
def minimal_agent_dir():
    return MINIMAL_AGENT_DIR


@pytest.fixture
def full_agent_dir():
    return FULL_AGENT_DIR


@pytest.fixture
def app_config():
    from server.config import AppConfig, ServerConfig, LLMConfig
    return AppConfig(
        config_dir=str(MINIMAL_AGENT_DIR),
        server=ServerConfig(host="127.0.0.1", port=0),
        llm=LLMConfig(
            api_key="test-key",
            model_id="test-model",
            base_url="https://api.test.com/v1",
        ),
    )


@pytest.fixture
def app_config_full():
    from server.config import AppConfig, ServerConfig, LLMConfig
    return AppConfig(
        config_dir=str(FULL_AGENT_DIR),
        server=ServerConfig(host="127.0.0.1", port=0),
        llm=LLMConfig(
            api_key="test-key",
            model_id="test-model",
            base_url="https://api.test.com/v1",
        ),
    )


@pytest.fixture
def oaf_loader_minimal():
    from server.services.oaf_loader import OAFLoader
    return OAFLoader(MINIMAL_AGENT_DIR)


@pytest.fixture
def oaf_loader_full():
    from server.services.oaf_loader import OAFLoader
    return OAFLoader(FULL_AGENT_DIR)


@pytest.fixture
def oaf_config_minimal(oaf_loader_minimal):
    return oaf_loader_minimal.load()


@pytest.fixture
def oaf_config_full(oaf_loader_full):
    return oaf_loader_full.load()


@pytest.fixture
def real_llm_config():
    from server.config import LLMConfig
    return LLMConfig(
        api_key=os.getenv("LLM_API_KEY", ""),
        model_id=os.getenv("LLM_MODEL_ID", ""),
        base_url=os.getenv("LLM_BASE_URL", ""),
        provider=os.getenv("LLM_PROVIDER", "ctyun"),
        temperature=float(os.getenv("LLM_TEMPERATURE", "0.7")),
        max_tokens=int(os.getenv("LLM_MAX_TOKENS", "4096")),
        timeout=int(os.getenv("LLM_TIMEOUT", "120")),
    )
