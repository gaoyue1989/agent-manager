import pytest
from unittest.mock import patch, AsyncMock, MagicMock


class TestAgentRuntime:
    """Agent 运行时单元测试"""

    @pytest.fixture
    def runtime(self, oaf_config_full, app_config_full):
        from server.services.agent_runtime import AgentRuntime
        return AgentRuntime(
            oaf_config=oaf_config_full,
            llm_config=app_config_full.llm,
        )

    @pytest.fixture
    def runtime_minimal(self, oaf_config_minimal, app_config):
        from server.services.agent_runtime import AgentRuntime
        return AgentRuntime(
            oaf_config=oaf_config_minimal,
            llm_config=app_config.llm,
        )

    def test_init(self, runtime):
        assert runtime.name == "Full Test Agent"
        assert runtime.description == "A full-featured test agent with skills, MCP, and tools for E2E testing"

    def test_system_prompt_includes_instructions(self, runtime_minimal):
        prompt = runtime_minimal.system_prompt
        assert "minimal test agent" in prompt.lower()

    def test_invoke_without_llm(self, oaf_config_minimal):
        from server.services.agent_runtime import AgentRuntime
        from server.config import LLMConfig
        runtime = AgentRuntime(
            oaf_config=oaf_config_minimal,
            llm_config=LLMConfig(),  # no valid LLM
        )
        import asyncio
        result = asyncio.run(runtime.invoke("Hello"))
        assert "[Mock Agent:" in result
        assert "minimal" in result.lower()

    def test_invoke_with_skills_in_prompt(self, runtime):
        skill = {"name": "bash-tool", "metadata": {"description": "Execute bash commands"}}
        runtime.loaded_skills = [skill]
        prompt = runtime.system_prompt
        assert "Available Skills" in prompt
        assert "bash-tool" in prompt

    def test_invoke_with_mcp_in_prompt(self, runtime):
        runtime.mcp_configs = [{
            "server": "filesystem",
            "tools": {"selectedTools": [{"name": "read_file", "enabled": True}]}
        }]
        prompt = runtime.system_prompt
        assert "Available MCP Servers" in prompt
        assert "filesystem" in prompt

    def test_build_skills_context(self, runtime):
        skill = {"name": "test-skill", "metadata": {"description": "A test skill for testing"}}
        runtime.loaded_skills = [skill]
        ctx = runtime._build_skills_context()
        assert "test-skill" in ctx
        assert "A test skill" in ctx

    def test_build_mcp_context(self, runtime):
        runtime.mcp_configs = [{
            "server": "filesystem",
            "tools": {
                "selectedTools": [
                    {"name": "read_file", "enabled": True},
                    {"name": "write_file", "enabled": True},
                ],
            },
        }]
        ctx = runtime._build_mcp_context()
        assert "filesystem" in ctx
        assert "2 tools" in ctx

    @pytest.mark.asyncio
    async def test_invoke_direct_no_llm(self, oaf_config_minimal):
        from server.services.agent_runtime import AgentRuntime
        from server.config import LLMConfig
        runtime = AgentRuntime(
            oaf_config=oaf_config_minimal,
            llm_config=LLMConfig(),  # no valid LLM
        )
        result = await runtime._invoke_direct("Hello")
        assert "[Mock Agent:" in result

    @pytest.mark.asyncio
    async def test_invoke_skill_nonexistent(self, runtime):
        result = await runtime.invoke_skill("no-skill", "input")
        assert "Not found" in result
