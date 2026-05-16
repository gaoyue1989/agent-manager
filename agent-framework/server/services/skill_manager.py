import importlib.util
from pathlib import Path
from typing import Any, Optional

from server.models.oaf_types import SkillConfig


class SkillManager:
    """Skill 管理器

    动态加载 skills/ 下的 Python 脚本
    """

    def __init__(self, skills_dir: Path | str):
        self.skills_dir = Path(skills_dir)

    def load_all(self, skill_configs: list[SkillConfig]) -> list[dict]:
        loaded = []
        if not self.skills_dir.exists():
            return loaded

        for sc in skill_configs:
            if sc.source != "local":
                skill = self._load_remote(sc)
            else:
                skill = self._load_local(sc)
            if skill:
                loaded.append(skill)
        return loaded

    def _load_local(self, config: SkillConfig) -> Optional[dict]:
        skill_dir = self.skills_dir / config.name
        if not skill_dir.exists() or not skill_dir.is_dir():
            return None

        module = self._load_module(skill_dir)
        metadata = self._load_metadata(skill_dir, config)

        return {
            "name": config.name,
            "path": str(skill_dir),
            "metadata": metadata,
            "module": module,
            "config": config,
        }

    def _load_remote(self, config: SkillConfig) -> Optional[dict]:
        return {
            "name": config.name,
            "path": config.source,
            "metadata": {
                "name": config.name,
                "description": config.description,
                "version": config.version,
                "source": config.source,
            },
            "module": None,
            "config": config,
        }

    def _load_module(self, skill_dir: Path) -> Optional[Any]:
        scripts_dir = skill_dir / "scripts"
        if not scripts_dir.exists():
            return None

        for py_file in scripts_dir.glob("*.py"):
            if py_file.name.startswith("_"):
                continue
            try:
                module_name = f"skill_{skill_dir.name}_{py_file.stem}"
                spec = importlib.util.spec_from_file_location(module_name, str(py_file))
                if spec and spec.loader:
                    module = importlib.util.module_from_spec(spec)
                    spec.loader.exec_module(module)
                    return module
            except Exception:
                pass
        return None

    def _load_metadata(self, skill_dir: Path, config: SkillConfig) -> dict:
        skill_md = skill_dir / "SKILL.md"
        meta = {
            "name": config.name,
            "description": config.description,
            "version": config.version,
        }
        if skill_md.exists():
            content = skill_md.read_text(encoding="utf-8")
            if content.startswith("---"):
                parts = content.split("---", 2)
                if len(parts) >= 3:
                    import yaml
                    try:
                        fm = yaml.safe_load(parts[1].strip()) or {}
                        meta.update({
                            "name": fm.get("name", meta["name"]),
                            "description": fm.get("description", meta["description"]),
                            "license": fm.get("license", "MIT"),
                            "allowed_tools": fm.get("allowed-tools", []),
                        })
                        if "metadata" in fm:
                            meta.update(fm["metadata"])
                    except Exception:
                        pass
        return meta

    def invoke_skill(self, skill_name: str, input_data: str, loaded_skills: list[dict]) -> str:
        for skill in loaded_skills:
            if skill["name"] == skill_name:
                module = skill.get("module")
                if module is None:
                    return f"[Skill:{skill_name}] Remote skill - no local module loaded"
                if hasattr(module, "main"):
                    try:
                        result = module.main(input_data)
                        return str(result)
                    except Exception as e:
                        return f"[Skill:{skill_name}] Error: {e}"
                return f"[Skill:{skill_name}] Loaded but no main() entry point"
        return f"[Skill:{skill_name}] Not found"

    def get_skill_summaries(self, loaded_skills: list[dict]) -> list[dict]:
        summaries = []
        for skill in loaded_skills:
            meta = skill.get("metadata", {})
            summaries.append({
                "name": skill["name"],
                "description": meta.get("description", ""),
                "version": meta.get("version", ""),
                "has_module": skill.get("module") is not None,
            })
        return summaries
