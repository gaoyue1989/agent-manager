"""pytest 配置"""

import os
import sys
from pathlib import Path

import pytest
from dotenv import load_dotenv

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

env_test_path = Path(__file__).parent / ".env.test"
load_dotenv(env_test_path)


@pytest.fixture
def tmp_output_dir(tmp_path):
    """临时输出目录"""
    return tmp_path


@pytest.fixture
def llm_config():
    """LLM 配置"""
    from codegen.frameworks.deepagents.llm_config import LLMConfig
    return LLMConfig.from_env()


@pytest.fixture
def sample_legacy_config():
    """示例旧格式配置"""
    return {
        "name": "Test Agent",
        "description": "Test Description",
        "model": "test-model",
        "model_endpoint": "https://api.example.com/v1",
        "system_prompt": "You are a helpful assistant.",
        "tools": ["Read", "Edit"],
        "memory": True,
        "max_iterations": 50,
    }
