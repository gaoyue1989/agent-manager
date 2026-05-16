#!/usr/bin/env python3
"""Agent Framework - 启动入口

DeepAgents-based Agent Framework with OAF config, A2A/A2UI protocols
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import uvicorn
from server.config import load_config


def main():
    config = load_config()

    print("=" * 60)
    print(f"Agent Framework v1.0.0")
    print(f"Config dir: {config.config_path}")
    print(f"Server: http://{config.server.host}:{config.server.port}")
    print(f"Debug UI: http://{config.server.host}:{config.server.port}/debug")
    print(f"LLM: {config.llm.provider} / {config.llm.model_id}")
    print("=" * 60)

    uvicorn.run(
        "server.app:create_app",
        host=config.server.host,
        port=config.server.port,
        reload=config.server.reload,
        factory=True,
    )


if __name__ == "__main__":
    main()
