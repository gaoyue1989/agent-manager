"""旧格式迁移测试"""

import pytest

from codegen.core.legacy_migrator import (
    migrate_legacy_config,
    migrate_legacy_file,
    get_migration_mapping,
)


class TestLegacyMigrator:
    """迁移脚本测试"""
    
    def test_migrate_basic_config(self, sample_legacy_config):
        """测试迁移基础配置"""
        agents_md, name = migrate_legacy_config(sample_legacy_config)
        
        assert name == "Test Agent"
        assert "name:" in agents_md
        assert "Test Agent" in agents_md
        assert "Test Description" in agents_md
        assert "You are a helpful assistant." in agents_md
    
    def test_migrate_preserves_tools(self):
        """测试迁移保留工具列表"""
        legacy = {
            "name": "Test",
            "description": "Test",
            "system_prompt": "Test",
            "tools": ["Read", "Edit", "Bash"],
        }
        
        agents_md, _ = migrate_legacy_config(legacy)
        assert "Read" in agents_md
        assert "Edit" in agents_md
        assert "Bash" in agents_md
    
    def test_migrate_memory_config(self):
        """测试迁移记忆配置"""
        legacy = {
            "name": "Test",
            "description": "Test",
            "system_prompt": "Test",
            "memory": True,
        }
        
        agents_md, _ = migrate_legacy_config(legacy)
        assert "memory:" in agents_md
        assert "editable" in agents_md
    
    def test_migrate_memory_disabled(self):
        """测试迁移禁用记忆"""
        legacy = {
            "name": "Test",
            "description": "Test",
            "system_prompt": "Test",
            "memory": False,
        }
        
        agents_md, _ = migrate_legacy_config(legacy)
        assert "read-only" in agents_md
    
    def test_migrate_model_config(self):
        """测试迁移模型配置"""
        legacy = {
            "name": "Test",
            "description": "Test",
            "system_prompt": "Test",
            "model": "gpt-4",
            "model_endpoint": "https://api.example.com/v1",
        }
        
        agents_md, _ = migrate_legacy_config(legacy)
        assert "model:" in agents_md
        assert "gpt-4" in agents_md
    
    def test_migrate_sub_agents(self):
        """测试迁移子 Agent"""
        legacy = {
            "name": "Test",
            "description": "Test",
            "system_prompt": "Test",
            "sub_agents": [
                {"name": "data-analyst", "endpoint": "http://localhost:8001"}
            ],
        }
        
        agents_md, _ = migrate_legacy_config(legacy)
        assert "agents:" in agents_md
        assert "data-analyst" in agents_md
    
    def test_migrate_skills(self):
        """测试迁移技能"""
        legacy = {
            "name": "Test",
            "description": "Test",
            "system_prompt": "Test",
            "skills": [
                {"name": "web-search", "description": "Web search"}
            ],
        }
        
        agents_md, _ = migrate_legacy_config(legacy)
        assert "skills:" in agents_md
        assert "web-search" in agents_md
    
    def test_migrate_generates_valid_yaml(self, sample_legacy_config):
        """测试迁移生成有效的 YAML"""
        import yaml
        
        agents_md, _ = migrate_legacy_config(sample_legacy_config)
        
        lines = agents_md.split("\n")
        yaml_lines = []
        in_yaml = False
        
        for line in lines:
            if line.strip() == "---":
                if in_yaml:
                    break
                in_yaml = True
                continue
            if in_yaml:
                yaml_lines.append(line)
        
        yaml_content = "\n".join(yaml_lines)
        parsed = yaml.safe_load(yaml_content)
        
        assert parsed is not None
        assert "name" in parsed
        assert "vendorKey" in parsed
    
    def test_migrate_includes_a2a_config(self, sample_legacy_config):
        """测试迁移包含 A2A 配置"""
        agents_md, _ = migrate_legacy_config(sample_legacy_config)
        
        assert "harnessConfig:" in agents_md
        assert "a2a:" in agents_md
        assert "protocol:" in agents_md
    
    def test_migrate_includes_a2ui_config(self, sample_legacy_config):
        """测试迁移包含 A2UI 配置"""
        agents_md, _ = migrate_legacy_config(sample_legacy_config)
        
        assert "a2ui:" in agents_md
        assert "catalog_id:" in agents_md
    
    def test_get_migration_mapping(self):
        """测试获取迁移映射"""
        mapping = get_migration_mapping()
        
        assert "name" in mapping
        assert "description" in mapping
        assert "model" in mapping
        assert "system_prompt" in mapping
        assert "tools" in mapping
    
    def test_migrate_legacy_file(self, tmp_path, sample_legacy_config):
        """测试迁移文件"""
        import json
        
        input_file = tmp_path / "legacy.json"
        input_file.write_text(json.dumps(sample_legacy_config))
        
        output_file = tmp_path / "AGENTS.md"
        
        name = migrate_legacy_file(str(input_file), str(output_file))
        
        assert name == "Test Agent"
        assert output_file.exists()
        
        content = output_file.read_text()
        assert "name:" in content
        assert "Test Agent" in content
