import pytest
from pathlib import Path
from server.models.oaf_types import SkillConfig

FIXTURES_DIR = Path(__file__).parent.parent / "fixtures"


class TestSkillManager:
    """Skill 管理器单元测试"""

    @pytest.fixture
    def skill_manager(self):
        from server.services.skill_manager import SkillManager
        return SkillManager(FIXTURES_DIR / "full-agent" / "skills")

    def test_load_all_skills(self, skill_manager):
        configs = [
            SkillConfig(
                name="bash-tool", source="local", version="1.0.0", required=True,
                description="Execute bash commands",
            ),
        ]
        loaded = skill_manager.load_all(configs)
        assert len(loaded) == 1
        assert loaded[0]["name"] == "bash-tool"
        assert loaded[0]["module"] is not None

    def test_load_nonexistent_skill(self, skill_manager):
        configs = [
            SkillConfig(name="nonexistent", source="local"),
        ]
        loaded = skill_manager.load_all(configs)
        assert len(loaded) == 0

    def test_load_remote_skill(self, skill_manager):
        configs = [
            SkillConfig(
                name="remote-skill", source="https://example.com",
                version="2.0.0", description="Remote skill",
            ),
        ]
        loaded = skill_manager.load_all(configs)
        assert len(loaded) == 1
        assert loaded[0]["name"] == "remote-skill"
        assert loaded[0]["module"] is None
        assert loaded[0]["metadata"]["source"] == "https://example.com"

    def test_invoke_skill(self, skill_manager):
        configs = [
            SkillConfig(name="bash-tool", source="local"),
        ]
        loaded = skill_manager.load_all(configs)
        result = skill_manager.invoke_skill("bash-tool", "echo hello", loaded)
        assert "hello" in result.lower()

    def test_invoke_nonexistent_skill(self, skill_manager):
        result = skill_manager.invoke_skill("no-such-skill", "input", [])
        assert "Not found" in result

    def test_get_skill_summaries(self, skill_manager):
        configs = [
            SkillConfig(name="bash-tool", source="local", description="Test tool"),
        ]
        loaded = skill_manager.load_all(configs)
        summaries = skill_manager.get_skill_summaries(loaded)
        assert len(summaries) == 1
        assert summaries[0]["name"] == "bash-tool"
        assert summaries[0]["has_module"] is True

    def test_empty_skills_dir(self, tmp_path):
        from server.services.skill_manager import SkillManager
        sm = SkillManager(tmp_path / "nonexistent")
        loaded = sm.load_all([])
        assert loaded == []
