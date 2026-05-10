"""完整工作流集成测试"""

import pytest
from pathlib import Path

from codegen.core.scaffold_generator import ScaffoldGenerator
from codegen.core.legacy_migrator import migrate_legacy_config
from codegen.frameworks.deepagents.agent_card_gen import AgentCardGenerator
from codegen.frameworks.deepagents.a2a_server import A2AServerGenerator


class TestFullWorkflow:
    """完整工作流测试"""
    
    def test_scaffold_to_agent_card(self, tmp_output_dir):
        """测试从脚手架到 Agent Card 的完整流程"""
        scaffold = ScaffoldGenerator()
        agent_dir = scaffold.create_scaffold(
            name="workflow-test-agent",
            output_dir=tmp_output_dir,
            description="Workflow Test Agent",
            a2ui_enabled=True,
        )
        
        assert agent_dir.exists()
        assert (agent_dir / "AGENTS.md").exists()
        
        card_gen = AgentCardGenerator(
            name="Workflow Test Agent",
            description="Workflow Test Agent",
            a2ui_enabled=True,
        )
        card = card_gen.generate()
        
        assert card["name"] == "Workflow Test Agent"
        assert len(card.get("extensions", [])) > 0
    
    def test_scaffold_with_skills(self, tmp_output_dir):
        """测试带技能的脚手架"""
        scaffold = ScaffoldGenerator()
        agent_dir = scaffold.create_scaffold(
            name="skill-test-agent",
            output_dir=tmp_output_dir,
            skills=[
                {"name": "web-search", "source": "local", "version": "1.0.0"},
            ],
        )
        
        skill_dir = scaffold.add_skill(
            agent_dir=agent_dir,
            skill_name="web-search",
            skill_description="Web search skill",
        )
        
        assert skill_dir.exists()
        assert (skill_dir / "SKILL.md").exists()
    
    def test_scaffold_with_mcp(self, tmp_output_dir):
        """测试带 MCP 的脚手架"""
        scaffold = ScaffoldGenerator()
        agent_dir = scaffold.create_scaffold(
            name="mcp-test-agent",
            output_dir=tmp_output_dir,
            mcp_servers=[
                {
                    "vendor": "block",
                    "server": "filesystem",
                    "version": "1.0.0",
                    "configDir": "mcp-configs/filesystem",
                }
            ],
        )
        
        mcp_dir = scaffold.add_mcp_config(
            agent_dir=agent_dir,
            mcp_name="filesystem",
            mcp_vendor="block",
            connection_url="http://localhost:8811/sse",
        )
        
        assert mcp_dir.exists()
        assert (mcp_dir / "ActiveMCP.json").exists()
        assert (mcp_dir / "config.yaml").exists()
    
    def test_legacy_migration_to_scaffold(self, tmp_output_dir, sample_legacy_config):
        """测试从旧格式迁移到脚手架"""
        agents_md, name = migrate_legacy_config(sample_legacy_config)
        
        agent_dir = tmp_output_dir / "migrated-agent"
        agent_dir.mkdir(parents=True, exist_ok=True)
        
        (agent_dir / "AGENTS.md").write_text(agents_md)
        
        assert (agent_dir / "AGENTS.md").exists()
        
        content = (agent_dir / "AGENTS.md").read_text()
        assert "name:" in content
        assert "Test Agent" in content
    
    def test_generate_a2a_server(self, tmp_output_dir):
        """测试生成 A2A Server"""
        server_gen = A2AServerGenerator(
            agent_name="server-test-agent",
            agent_description="Server Test Agent",
            a2ui_enabled=True,
        )
        
        output_dir = tmp_output_dir / "generated"
        files = server_gen.generate_all(output_dir)
        
        assert (output_dir / "main.py").exists()
        assert (output_dir / "a2a_routes.py").exists()
        assert (output_dir / "agent_card.py").exists()
        assert (output_dir / "requirements.txt").exists()
        assert (output_dir / "Dockerfile").exists()
    
    def test_full_generation_workflow(self, tmp_output_dir):
        """测试完整生成流程"""
        scaffold = ScaffoldGenerator()
        agent_dir = scaffold.create_scaffold(
            name="full-test-agent",
            output_dir=tmp_output_dir,
            description="Full Test Agent",
            a2ui_enabled=True,
            a2a_enabled=True,
        )
        
        scaffold.add_skill(
            agent_dir=agent_dir,
            skill_name="test-skill",
            skill_description="Test skill",
        )
        
        scaffold.add_mcp_config(
            agent_dir=agent_dir,
            mcp_name="test-mcp",
            connection_url="http://localhost:8080/sse",
        )
        
        server_gen = A2AServerGenerator(
            agent_name="Full Test Agent",
            agent_description="Full Test Agent",
            a2ui_enabled=True,
        )
        
        generated_dir = agent_dir / "generated"
        server_gen.generate_all(generated_dir)
        
        card_gen = AgentCardGenerator(
            name="Full Test Agent",
            description="Full Test Agent",
            a2ui_enabled=True,
        )
        card_gen.save(str(generated_dir / "agent_card.json"))
        
        assert (agent_dir / "AGENTS.md").exists()
        assert (agent_dir / "skills" / "test-skill" / "SKILL.md").exists()
        assert (agent_dir / "mcp-configs" / "test-mcp" / "ActiveMCP.json").exists()
        assert (generated_dir / "main.py").exists()
        assert (generated_dir / "agent_card.json").exists()
