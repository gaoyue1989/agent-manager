import os
import pytest
from pathlib import Path


class TestConfig:
    """配置加载单元测试"""

    def test_load_config_defaults(self, monkeypatch):
        from server.config import load_config
        # Clear env vars that .env.test may have set
        monkeypatch.delenv("AGENT_CONFIG_DIR", raising=False)
        monkeypatch.delenv("SERVER_PORT", raising=False)
        monkeypatch.delenv("SERVER_HOST", raising=False)
        config = load_config()
        assert config.config_dir == "/config"
        assert config.server.host == "0.0.0.0"
        assert config.server.port == 8000

    def test_load_config_from_env(self, monkeypatch):
        monkeypatch.setenv("AGENT_CONFIG_DIR", "/my/agent/config")
        monkeypatch.setenv("SERVER_PORT", "9000")
        monkeypatch.setenv("LLM_API_KEY", "my-key")
        monkeypatch.setenv("LLM_MODEL_ID", "my-model")
        monkeypatch.setenv("LLM_BASE_URL", "https://api.example.com/v1")

        from server.config import load_config
        config = load_config()
        assert config.config_dir == "/my/agent/config"
        assert config.server.port == 9000
        assert config.llm.api_key == "my-key"
        assert config.llm.model_id == "my-model"
        assert config.llm.base_url == "https://api.example.com/v1"

    def test_llm_config_is_valid(self):
        from server.config import LLMConfig
        config = LLMConfig(
            api_key="key", model_id="model", base_url="https://api.test.com/v1"
        )
        assert config.is_valid() is True
        assert config.validate_with_errors() == []

    def test_llm_config_is_invalid_when_missing_fields(self):
        from server.config import LLMConfig
        config = LLMConfig()
        assert config.is_valid() is False
        errors = config.validate_with_errors()
        assert len(errors) == 3

    def test_llm_config_to_openai(self):
        from server.config import LLMConfig
        config = LLMConfig(
            api_key="key", model_id="model", base_url="https://api.test.com/v1"
        )
        result = config.to_openai_config()
        assert result["model"] == "model"
        assert result["openai_api_key"] == "key"
        assert result["openai_api_base"] == "https://api.test.com/v1"

    def test_app_config_paths(self):
        from server.config import AppConfig, ServerConfig, LLMConfig
        config = AppConfig(
            config_dir="/my/config",
            server=ServerConfig(),
            llm=LLMConfig(),
        )
        assert str(config.config_path) == "/my/config"
        assert str(config.agents_md_path).endswith("AGENTS.md")
        assert str(config.skills_dir).endswith("skills")
        assert str(config.mcp_configs_dir) == "/my/config"
