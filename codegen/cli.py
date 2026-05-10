#!/usr/bin/env python3
"""
Codegen CLI - OAF 脚手架生成 + 迁移工具

Usage:
    python cli.py scaffold --name <name> [--output <dir>]
    python cli.py migrate --config <legacy.json> [--output <dir>]
    python cli.py generate --oaf <agent-dir> [--output <dir>]
"""

import sys
import json
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))


def cmd_scaffold(args: list[str]):
    """创建 OAF 脚手架"""
    name = ""
    output_dir = "."
    description = ""
    a2ui = True
    a2a = True

    i = 0
    while i < len(args):
        if args[i] == "--name" and i + 1 < len(args):
            name = args[i + 1]; i += 2
        elif args[i] == "--output" and i + 1 < len(args):
            output_dir = args[i + 1]; i += 2
        elif args[i] == "--description" and i + 1 < len(args):
            description = args[i + 1]; i += 2
        elif args[i] == "--no-a2ui":
            a2ui = False; i += 1
        elif args[i] == "--no-a2a":
            a2a = False; i += 1
        else:
            i += 1

    if not name:
        print("Error: --name is required", file=sys.stderr)
        sys.exit(1)

    from codegen.core.scaffold_generator import ScaffoldGenerator
    from codegen.core.skill_packager import SkillPackager

    gen = ScaffoldGenerator()
    agent_dir = gen.create_scaffold(
        name=name, output_dir=output_dir, description=description,
        a2ui_enabled=a2ui, a2a_enabled=a2a,
    )
    print(f"Scaffold generated: {agent_dir}")

    from codegen.frameworks.deepagents.a2a_server import A2AServerGenerator
    server_gen = A2AServerGenerator(agent_name=name, agent_description=description, a2ui_enabled=a2ui)
    generated_dir = agent_dir / "generated"
    server_gen.generate_all(generated_dir)
    print(f"A2A Server files generated: {generated_dir}")

    from codegen.frameworks.deepagents.agent_card_gen import AgentCardGenerator
    card_gen = AgentCardGenerator(name=name, description=description, a2ui_enabled=a2ui)
    card_gen.save(str(generated_dir / "agent_card.json"))
    print(f"Agent Card generated: {generated_dir / 'agent_card.json'}")


def cmd_migrate(args: list[str]):
    """迁移旧 JSON 配置到 OAF"""
    config_file = ""
    output_dir = "."

    i = 0
    while i < len(args):
        if args[i] == "--config" and i + 1 < len(args):
            config_file = args[i + 1]; i += 2
        elif args[i] == "--output" and i + 1 < len(args):
            output_dir = args[i + 1]; i += 2
        else:
            i += 1

    if not config_file:
        print("Error: --config is required", file=sys.stderr)
        sys.exit(1)

    from codegen.core.legacy_migrator import migrate_legacy_file
    output_path = Path(output_dir) / "AGENTS.md"
    name = migrate_legacy_file(config_file, str(output_path))
    print(f"Migrated to: {output_path}")
    print(f"Agent name: {name}")


def cmd_generate(args: list[str]):
    """从 OAF 目录生成 A2A Server 代码"""
    oaf_dir = "."
    output_dir = "generated"

    i = 0
    while i < len(args):
        if args[i] == "--oaf" and i + 1 < len(args):
            oaf_dir = args[i + 1]; i += 2
        elif args[i] == "--output" and i + 1 < len(args):
            output_dir = args[i + 1]; i += 2
        else:
            i += 1

    oaf_path = Path(oaf_dir)
    if not (oaf_path / "AGENTS.md").exists():
        print(f"Error: {oaf_path}/AGENTS.md not found", file=sys.stderr)
        sys.exit(1)

    import yaml
    config = {}
    content = (oaf_path / "AGENTS.md").read_text(encoding="utf-8")
    if content.startswith("---"):
        parts = content.split("---", 2)
        if len(parts) >= 3:
            config = yaml.safe_load(parts[1].strip()) or {}
            config["instructions"] = parts[2].strip()

    name = config.get("name", "unnamed-agent")
    description = config.get("description", "")
    harness = config.get("harnessConfig", {}).get("deep-agents", {})
    a2ui_enabled = harness.get("a2ui", {}).get("enabled", True)

    from codegen.core.skill_packager import package_skills_from_oaf
    packaged = package_skills_from_oaf(oaf_path, config)
    if packaged:
        print(f"Packaged {len(packaged)} skills")

    from codegen.frameworks.deepagents.skill_code_gen import SkillCodeGenerator
    skill_gen = SkillCodeGenerator(oaf_path / "skills")
    skill_gen.generate_all(oaf_path, config.get("skills", []))
    print(f"Generated skill implementations")

    from codegen.frameworks.deepagents.a2a_server import A2AServerGenerator
    server_gen = A2AServerGenerator(agent_name=name, agent_description=description, a2ui_enabled=a2ui_enabled)
    output_path = Path(output_dir)
    server_gen.generate_all(output_path)
    print(f"Generated: {output_path}")

    from codegen.frameworks.deepagents.agent_card_gen import AgentCardGenerator
    card_gen = AgentCardGenerator(name=name, description=description, a2ui_enabled=a2ui_enabled,
        skills=[{"id": s.get("name","unknown"),"name": s.get("name","Unknown"),"description":s.get("description",""),
        "tags":[],"examples":[],"inputModes":["text"],"outputModes":["text","a2ui/v0.8"]} for s in config.get("skills",[])])
    card_gen.save(str(output_path / "agent_card.json"))
    print(f"Generated: {output_path}/agent_card.json")


def print_usage():
    print("""Codegen CLI v2.0.0

Usage:
  codegen scaffold --name <name> [--output <dir>] [--description <desc>]
  codegen migrate  --config <legacy.json> [--output <dir>]
  codegen generate --oaf <agent-dir> [--output <dir>]

Options:
  scaffold    Create OAF directory structure with A2A Server boilerplate
  migrate     Convert legacy JSON config to OAF AGENTS.md
  generate    Generate A2A Server code from OAF directory
""")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print_usage()
        sys.exit(1)

    cmd = sys.argv[1]
    rest = sys.argv[2:]

    if cmd == "scaffold":
        cmd_scaffold(rest)
    elif cmd == "migrate":
        cmd_migrate(rest)
    elif cmd == "generate":
        cmd_generate(rest)
    elif cmd in ("--help", "-h", "help"):
        print_usage()
    else:
        print(f"Unknown command: {cmd}", file=sys.stderr)
        print_usage()
        sys.exit(1)
