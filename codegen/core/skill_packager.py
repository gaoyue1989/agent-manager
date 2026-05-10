#!/usr/bin/env python3
"""
远程技能构建时打包器

在镜像构建阶段下载并打包远程技能，避免运行时网络依赖。
支持 well-known URI 和自定义 URL。
"""

import os
import shutil
import json
from pathlib import Path
from typing import Optional
from urllib.parse import urlparse, urljoin
import httpx


class SkillPackager:
    """远程技能打包器 - 构建时下载并打包技能"""

    def __init__(self, agent_dir: Path | str, timeout: int = 30):
        self.agent_dir = Path(agent_dir)
        self.timeout = timeout
        self.skills_dir = self.agent_dir / "skills"

    def fetch_and_package(self, skill_source: str, skill_name: str, version: str = "latest") -> Path:
        """从远程 URL 下载技能并打包到本地 skills 目录"""
        self.skills_dir.mkdir(parents=True, exist_ok=True)

        skill_dir = self.skills_dir / skill_name
        skill_dir.mkdir(parents=True, exist_ok=True)

        if skill_source.startswith(("http://", "https://")):
            return self._fetch_remote_skill(skill_dir, skill_source, skill_name)
        elif skill_source == "local":
            return skill_dir
        else:
            raise ValueError(f"Unsupported skill source: {skill_source}")

    def _fetch_remote_skill(self, skill_dir: Path, source: str, skill_name: str) -> Path:
        """从远程 URL 下载技能文件"""
        base_url = source.rstrip("/")

        with httpx.Client(timeout=self.timeout, follow_redirects=True) as client:
            skill_md_url = f"{base_url}/SKILL.md"
            resp = client.get(skill_md_url)
            if resp.status_code == 200:
                (skill_dir / "SKILL.md").write_text(resp.text, encoding="utf-8")

            script_files = ["setup.sh", "run.py", "main.py", "__init__.py"]
            scripts_dir = skill_dir / "scripts"
            scripts_dir.mkdir(exist_ok=True)

            for filename in script_files:
                script_url = f"{base_url}/scripts/{filename}"
                try:
                    resp = client.get(script_url)
                    if resp.status_code == 200:
                        (scripts_dir / filename).write_text(resp.text, encoding="utf-8")
                except Exception:
                    pass

            resources_dir = skill_dir / "resources"
            resources_dir.mkdir(exist_ok=True)

            index_url = f"{base_url}/resources/index.json"
            try:
                resp = client.get(index_url)
                if resp.status_code == 200:
                    (resources_dir / "index.json").write_text(resp.text, encoding="utf-8")
            except Exception:
                pass

        return skill_dir

    def package_all_skills(self, skills_config: list[dict]) -> list[Path]:
        """根据 OAF skills 配置批量打包"""
        packaged = []

        for skill in skills_config:
            source = skill.get("source", "local")
            name = skill.get("name", "unknown")
            version = skill.get("version", "latest")
            required = skill.get("required", False)

            if source != "local":
                try:
                    skill_dir = self.fetch_and_package(source, name, version)
                    packaged.append(skill_dir)
                except Exception as e:
                    if required:
                        raise RuntimeError(f"Failed to package required skill '{name}': {e}")
                    print(f"Warning: Failed to package optional skill '{name}': {e}")

        return packaged

    @staticmethod
    def resolve_well_known_url(domain: str, skill_name: str) -> str:
        """解析 well-known URI"""
        return f"https://{domain}/.well-known/skills/{skill_name}"


def package_skills_from_oaf(agent_dir: Path | str, oaf_config: dict) -> list[Path]:
    """从 OAF 配置中打包所有远程技能"""
    packager = SkillPackager(agent_dir)
    skills = oaf_config.get("skills", [])
    return packager.package_all_skills(skills)
