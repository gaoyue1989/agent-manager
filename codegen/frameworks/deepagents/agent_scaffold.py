#!/usr/bin/env python3
"""
Agent 脚手架生成器

生成完整的 Agent 项目结构
"""

from pathlib import Path
from typing import Optional

from .agent_card_gen import AgentCardGenerator
from .a2a_server import A2AServerGenerator


class AgentScaffold:
    """Agent 完整脚手架生成器"""
    
    def __init__(
        self,
        name: str,
        description: str = "",
        output_dir: Path | str = ".",
    ):
        self.name = name
        self.description = description
        self.output_dir = Path(output_dir)
    
    def generate(
        self,
        a2ui_enabled: bool = True,
        streaming: bool = True,
    ) -> Path:
        """生成完整 Agent 项目"""
        agent_dir = self.output_dir / self.name.lower().replace(" ", "-")
        agent_dir.mkdir(parents=True, exist_ok=True)
        
        server_gen = A2AServerGenerator(
            agent_name=self.name,
            agent_description=self.description,
            streaming=streaming,
            a2ui_enabled=a2ui_enabled,
        )
        
        generated_dir = agent_dir / "generated"
        server_gen.generate_all(generated_dir)
        
        card_gen = AgentCardGenerator(
            name=self.name,
            description=self.description,
            a2ui_enabled=a2ui_enabled,
        )
        card_gen.save(str(generated_dir / "agent_card.json"))
        
        return agent_dir
