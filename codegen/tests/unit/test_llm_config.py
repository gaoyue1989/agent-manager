"""LLM 配置测试"""

import os
import pytest

from codegen.frameworks.deepagents.llm_config import LLMConfig


class TestLLMConfig:
    """LLM 配置测试"""
    
    def test_from_env(self, monkeypatch):
        """测试从环境变量加载"""
        monkeypatch.setenv("LLM_API_KEY", "test-api-key")
        monkeypatch.setenv("LLM_MODEL_ID", "test-model-id")
        monkeypatch.setenv("LLM_BASE_URL", "https://api.example.com/v1")
        monkeypatch.setenv("LLM_PROVIDER", "test-provider")
        monkeypatch.setenv("LLM_TEMPERATURE", "0.8")
        monkeypatch.setenv("LLM_MAX_TOKENS", "2048")
        
        config = LLMConfig.from_env()
        
        assert config.api_key == "test-api-key"
        assert config.model_id == "test-model-id"
        assert config.base_url == "https://api.example.com/v1"
        assert config.provider == "test-provider"
        assert config.temperature == 0.8
        assert config.max_tokens == 2048
    
    def test_validate_valid_config(self, monkeypatch):
        """测试验证有效配置"""
        monkeypatch.setenv("LLM_API_KEY", "test-api-key")
        monkeypatch.setenv("LLM_MODEL_ID", "test-model-id")
        monkeypatch.setenv("LLM_BASE_URL", "https://api.example.com/v1")
        
        config = LLMConfig.from_env()
        errors = config.validate()
        
        assert len(errors) == 0
    
    def test_validate_missing_api_key(self, monkeypatch):
        """测试验证缺少 API Key"""
        monkeypatch.delenv("LLM_API_KEY", raising=False)
        monkeypatch.setenv("LLM_MODEL_ID", "test-model-id")
        monkeypatch.setenv("LLM_BASE_URL", "https://api.example.com/v1")
        
        config = LLMConfig.from_env()
        errors = config.validate()
        
        assert len(errors) > 0
        assert any("LLM_API_KEY" in e for e in errors)
    
    def test_validate_missing_model_id(self, monkeypatch):
        """测试验证缺少 Model ID"""
        monkeypatch.setenv("LLM_API_KEY", "test-api-key")
        monkeypatch.delenv("LLM_MODEL_ID", raising=False)
        monkeypatch.setenv("LLM_BASE_URL", "https://api.example.com/v1")
        
        config = LLMConfig.from_env()
        errors = config.validate()
        
        assert len(errors) > 0
        assert any("LLM_MODEL_ID" in e for e in errors)
    
    def test_validate_missing_base_url(self, monkeypatch):
        """测试验证缺少 Base URL"""
        monkeypatch.setenv("LLM_API_KEY", "test-api-key")
        monkeypatch.setenv("LLM_MODEL_ID", "test-model-id")
        monkeypatch.delenv("LLM_BASE_URL", raising=False)
        
        config = LLMConfig.from_env()
        errors = config.validate()
        
        assert len(errors) > 0
        assert any("LLM_BASE_URL" in e for e in errors)
    
    def test_is_valid(self, monkeypatch):
        """测试 is_valid 方法"""
        monkeypatch.setenv("LLM_API_KEY", "test-api-key")
        monkeypatch.setenv("LLM_MODEL_ID", "test-model-id")
        monkeypatch.setenv("LLM_BASE_URL", "https://api.example.com/v1")
        
        config = LLMConfig.from_env()
        assert config.is_valid() is True
    
    def test_is_valid_false(self, monkeypatch):
        """测试 is_valid 返回 False"""
        monkeypatch.delenv("LLM_API_KEY", raising=False)
        monkeypatch.delenv("LLM_MODEL_ID", raising=False)
        monkeypatch.delenv("LLM_BASE_URL", raising=False)
        
        config = LLMConfig.from_env()
        assert config.is_valid() is False
    
    def test_to_langchain_config(self, monkeypatch):
        """测试转换为 LangChain 配置"""
        monkeypatch.setenv("LLM_API_KEY", "test-api-key")
        monkeypatch.setenv("LLM_MODEL_ID", "test-model-id")
        monkeypatch.setenv("LLM_BASE_URL", "https://api.example.com/v1")
        monkeypatch.setenv("LLM_TEMPERATURE", "0.8")
        monkeypatch.setenv("LLM_MAX_TOKENS", "2048")
        
        config = LLMConfig.from_env()
        lc_config = config.to_langchain_config()
        
        assert lc_config["model"] == "test-model-id"
        assert lc_config["model_provider"] == "openai"
        assert lc_config["openai_api_key"] == "test-api-key"
        assert lc_config["openai_api_base"] == "https://api.example.com/v1"
        assert lc_config["temperature"] == 0.8
        assert lc_config["max_tokens"] == 2048
    
    def test_default_values(self, monkeypatch):
        """测试默认值"""
        monkeypatch.delenv("LLM_API_KEY", raising=False)
        monkeypatch.delenv("LLM_MODEL_ID", raising=False)
        monkeypatch.delenv("LLM_BASE_URL", raising=False)
        monkeypatch.delenv("LLM_PROVIDER", raising=False)
        monkeypatch.delenv("LLM_TEMPERATURE", raising=False)
        monkeypatch.delenv("LLM_MAX_TOKENS", raising=False)
        monkeypatch.delenv("LLM_TIMEOUT", raising=False)
        
        config = LLMConfig.from_env()
        
        assert config.provider == "ctyun"
        assert config.temperature == 0.7
        assert config.max_tokens == 4096
        assert config.timeout == 60
