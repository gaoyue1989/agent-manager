"""OAF 脚手架生成器测试"""

import pytest
from pathlib import Path

from codegen.core.scaffold_generator import ScaffoldGenerator


class TestScaffoldGenerator:
    """脚手架生成器测试"""
    
    def test_create_oaf_directory_structure(self, tmp_output_dir):
        """测试创建 OAF 目录结构"""
        generator = ScaffoldGenerator()
        agent_dir = generator.create_scaffold(
            name="test-agent",
            output_dir=tmp_output_dir,
            description="Test agent description",
        )
        
        assert agent_dir.exists()
        assert agent_dir.is_dir()
        assert (agent_dir / "AGENTS.md").exists()
        assert (agent_dir / "skills").is_dir()
        assert (agent_dir / "mcp-configs").is_dir()
        assert (agent_dir / "README.md").exists()
    
    def test_agents_md_has_required_fields(self, tmp_output_dir):
        """测试 AGENTS.md 包含必填字段"""
        generator = ScaffoldGenerator()
        agent_dir = generator.create_scaffold(
            name="test-agent",
            output_dir=tmp_output_dir,
            description="Test agent description",
            vendor_key="test-vendor",
        )
        
        content = (agent_dir / "AGENTS.md").read_text()
        assert "name:" in content
        assert "test-agent" in content
        assert "vendorKey:" in content
        assert "test-vendor" in content
        assert "description:" in content
        assert "Test agent description" in content
    
    def test_agents_md_has_model_config(self, tmp_output_dir):
        """测试 AGENTS.md 包含模型配置"""
        generator = ScaffoldGenerator()
        agent_dir = generator.create_scaffold(
            name="test-agent",
            output_dir=tmp_output_dir,
            model_provider="ctyun",
            model_name="test-model-id",
            model_endpoint="https://api.example.com/v1",
        )
        
        content = (agent_dir / "AGENTS.md").read_text()
        assert "model:" in content
        assert "provider:" in content
        assert "ctyun" in content
    
    def test_agents_md_has_a2a_config(self, tmp_output_dir):
        """测试 AGENTS.md 包含 A2A 配置"""
        generator = ScaffoldGenerator()
        agent_dir = generator.create_scaffold(
            name="test-agent",
            output_dir=tmp_output_dir,
            a2a_enabled=True,
        )
        
        content = (agent_dir / "AGENTS.md").read_text()
        assert "harnessConfig:" in content
        assert "a2a:" in content
        assert "protocol:" in content
    
    def test_agents_md_has_a2ui_config(self, tmp_output_dir):
        """测试 AGENTS.md 包含 A2UI 配置"""
        generator = ScaffoldGenerator()
        agent_dir = generator.create_scaffold(
            name="test-agent",
            output_dir=tmp_output_dir,
            a2ui_enabled=True,
        )
        
        content = (agent_dir / "AGENTS.md").read_text()
        assert "a2ui:" in content
        assert "catalog_id:" in content
    
    def test_add_skill(self, tmp_output_dir):
        """测试添加技能"""
        generator = ScaffoldGenerator()
        agent_dir = generator.create_scaffold(
            name="test-agent",
            output_dir=tmp_output_dir,
        )
        
        skill_dir = generator.add_skill(
            agent_dir=agent_dir,
            skill_name="test-skill",
            skill_description="Test skill description",
        )
        
        assert skill_dir.exists()
        assert (skill_dir / "SKILL.md").exists()
        assert (skill_dir / "scripts").is_dir()
        
        skill_content = (skill_dir / "SKILL.md").read_text()
        assert "test-skill" in skill_content
        assert "Test skill description" in skill_content
    
    def test_add_mcp_config(self, tmp_output_dir):
        """测试添加 MCP 配置"""
        generator = ScaffoldGenerator()
        agent_dir = generator.create_scaffold(
            name="test-agent",
            output_dir=tmp_output_dir,
        )
        
        mcp_dir = generator.add_mcp_config(
            agent_dir=agent_dir,
            mcp_name="test-mcp",
            connection_url="http://localhost:8080/sse",
        )
        
        assert mcp_dir.exists()
        assert (mcp_dir / "ActiveMCP.json").exists()
        assert (mcp_dir / "config.yaml").exists()
