#!/usr/bin/env python3
"""
LLM 配置管理

从环境变量读取 LLM 配置
"""

import os
from typing import Optional
from pydantic import BaseModel


class LLMConfig(BaseModel):
    """LLM 配置 - 从环境变量读取"""
    
    api_key: str = ""
    model_id: str = ""
    base_url: str = ""
    provider: str = "ctyun"
    temperature: float = 0.7
    max_tokens: int = 4096
    timeout: int = 60
    
    def __init__(self, **data):
        super().__init__(**data)
        if not self.api_key:
            self.api_key = os.getenv("LLM_API_KEY", "")
        if not self.model_id:
            self.model_id = os.getenv("LLM_MODEL_ID", "")
        if not self.base_url:
            self.base_url = os.getenv("LLM_BASE_URL", "")
        if self.provider == "ctyun":
            env_provider = os.getenv("LLM_PROVIDER")
            if env_provider:
                self.provider = env_provider
    
    @classmethod
    def from_env(cls) -> "LLMConfig":
        """从环境变量加载配置"""
        return cls(
            api_key=os.getenv("LLM_API_KEY", ""),
            model_id=os.getenv("LLM_MODEL_ID", ""),
            base_url=os.getenv("LLM_BASE_URL", ""),
            provider=os.getenv("LLM_PROVIDER", "ctyun"),
            temperature=float(os.getenv("LLM_TEMPERATURE", "0.7")),
            max_tokens=int(os.getenv("LLM_MAX_TOKENS", "4096")),
            timeout=int(os.getenv("LLM_TIMEOUT", "60")),
        )
    
    def to_langchain_config(self) -> dict:
        """转换为 LangChain 配置格式"""
        return {
            "model": self.model_id,
            "model_provider": "openai",
            "openai_api_key": self.api_key,
            "openai_api_base": self.base_url,
            "temperature": self.temperature,
            "max_tokens": self.max_tokens,
        }
    
    def validate(self) -> list[str]:
        """验证配置"""
        errors = []
        if not self.api_key:
            errors.append("LLM_API_KEY 未设置")
        if not self.model_id:
            errors.append("LLM_MODEL_ID 未设置")
        if not self.base_url:
            errors.append("LLM_BASE_URL 未设置")
        return errors
    
    def is_valid(self) -> bool:
        """检查配置是否有效"""
        return len(self.validate()) == 0


llm_config = LLMConfig.from_env()
