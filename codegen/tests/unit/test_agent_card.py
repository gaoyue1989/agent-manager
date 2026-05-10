"""Agent Card 生成器测试"""

import pytest
import json

from codegen.frameworks.deepagents.agent_card_gen import AgentCardGenerator


class TestAgentCardGenerator:
    """Agent Card 生成器测试"""
    
    def test_generate_valid_agent_card(self):
        """测试生成有效的 Agent Card"""
        generator = AgentCardGenerator(
            name="Test Agent",
            description="Test Description",
            host="localhost",
            port=8000,
        )
        card = generator.generate()
        
        assert card["name"] == "Test Agent"
        assert card["description"] == "Test Description"
        assert "url" in card
        assert "http://localhost:8000/" in card["url"]
        assert "capabilities" in card
        assert "skills" in card
    
    def test_agent_card_has_capabilities(self):
        """测试 Agent Card 包含能力声明"""
        generator = AgentCardGenerator(
            name="Test Agent",
            description="Test",
            streaming=True,
            push_notifications=False,
        )
        card = generator.generate()
        
        capabilities = card.get("capabilities", {})
        assert capabilities.get("streaming") is True
        assert capabilities.get("pushNotifications") is False
    
    def test_agent_card_has_a2ui_extension(self):
        """测试 Agent Card 包含 A2UI 扩展"""
        generator = AgentCardGenerator(
            name="Test Agent",
            description="Test",
            a2ui_enabled=True,
        )
        card = generator.generate()
        
        extensions = card.get("extensions", [])
        assert len(extensions) > 0
        
        a2ui_ext = next(
            (e for e in extensions if "a2ui" in e.get("uri", "")),
            None
        )
        assert a2ui_ext is not None
        assert "supportedCatalogIds" in a2ui_ext["params"]
    
    def test_agent_card_without_a2ui(self):
        """测试 Agent Card 不包含 A2UI 扩展"""
        generator = AgentCardGenerator(
            name="Test Agent",
            description="Test",
            a2ui_enabled=False,
        )
        card = generator.generate()
        
        extensions = card.get("extensions", [])
        a2ui_ext = next(
            (e for e in extensions if "a2ui" in e.get("uri", "")),
            None
        )
        assert a2ui_ext is None
    
    def test_agent_card_json_serializable(self):
        """测试 Agent Card 可序列化为 JSON"""
        generator = AgentCardGenerator(
            name="Test Agent",
            description="Test",
        )
        card = generator.generate()
        
        json_str = json.dumps(card, ensure_ascii=False)
        parsed = json.loads(json_str)
        assert parsed == card
    
    def test_agent_card_to_json(self):
        """测试 to_json 方法"""
        generator = AgentCardGenerator(
            name="Test Agent",
            description="Test",
        )
        
        json_str = generator.to_json()
        parsed = json.loads(json_str)
        
        assert parsed["name"] == "Test Agent"
    
    def test_agent_card_with_skills(self):
        """测试 Agent Card 包含技能"""
        generator = AgentCardGenerator(
            name="Test Agent",
            description="Test",
            skills=[
                {
                    "id": "research",
                    "name": "Research",
                    "description": "Research skill",
                    "tags": ["search"],
                    "examples": [],
                    "inputModes": ["text"],
                    "outputModes": ["text"],
                }
            ],
        )
        card = generator.generate()
        
        assert "skills" in card
        assert len(card["skills"]) == 1
        assert card["skills"][0]["name"] == "Research"
    
    def test_agent_card_has_security_schemes(self):
        """测试 Agent Card 包含安全方案"""
        generator = AgentCardGenerator(
            name="Test Agent",
            description="Test",
        )
        card = generator.generate()
        
        assert "securitySchemes" in card
        assert "bearer" in card["securitySchemes"]
