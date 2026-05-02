#!/usr/bin/env python3
"""
DeepAgents 代码生成器

输入: Agent 配置 JSON
输出: 完整的 DeepAgents Python 脚本 + Dockerfile + requirements.txt
"""

import json
import os
import sys
from pathlib import Path

TEMPLATE_DIR = Path(__file__).parent / "templates"

DEEPAGENTS_BUILTIN_TOOLS = [
    {"name": "write_todos", "source": "TodoListMiddleware", "desc": "管理任务清单"},
    {"name": "ls", "source": "FilesystemMiddleware", "desc": "列出目录内容"},
    {"name": "read_file", "source": "FilesystemMiddleware", "desc": "读取文件"},
    {"name": "write_file", "source": "FilesystemMiddleware", "desc": "写入/创建文件"},
    {"name": "edit_file", "source": "FilesystemMiddleware", "desc": "编辑文件（查找替换）"},
    {"name": "glob", "source": "FilesystemMiddleware", "desc": "按模式匹配文件"},
    {"name": "grep", "source": "FilesystemMiddleware", "desc": "搜索文件内容"},
    {"name": "execute", "source": "FilesystemMiddleware", "desc": "执行 Shell 命令（需 Sandbox 后端）"},
    {"name": "task", "source": "SubAgentMiddleware", "desc": "调用子 Agent"},
]


def generate_agent_code(config: dict) -> dict:
    """根据配置生成 DeepAgents 代码文件"""
    name = config["name"]
    description = config.get("description", "")
    model = config.get("model", "qwen3.6-plus")
    endpoint = config.get("model_endpoint", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    api_key = config.get("api_key", os.environ.get("LLM_API_KEY", "sk-****"))
    system_prompt = config.get("system_prompt", "You are a helpful AI assistant.")
    enabled_tools = config.get("enabled_tools", [t["name"] for t in DEEPAGENTS_BUILTIN_TOOLS])
    excluded_tools = config.get("excluded_tools", [])
    mcp_config = config.get("mcp_config")
    sub_agents = config.get("sub_agents", [])
    enable_memory = config.get("memory", True)
    max_iterations = config.get("max_iterations", 50)
    skills = config.get("skills", [])
    has_skills = bool(skills)
    has_mcp = bool(mcp_config and mcp_config.get("url"))
    base_image = config.get("base_image", "")

    agent_py = _render_agent_py(
        name=name,
        description=description,
        model=model,
        endpoint=endpoint,
        api_key=api_key,
        system_prompt=system_prompt,
        enabled_tools=enabled_tools,
        excluded_tools=excluded_tools,
        mcp_config=mcp_config,
        sub_agents=sub_agents,
        enable_memory=enable_memory,
        max_iterations=max_iterations,
        has_skills=has_skills,
        skills=skills,
        has_mcp=has_mcp,
    )

    dockerfile = _render_dockerfile(
        name=name,
        has_skills=has_skills,
        base_image=base_image,
    )

    requirements = _render_requirements(has_mcp=has_mcp)

    result = {
        "agent.py": agent_py,
        "Dockerfile": dockerfile,
        "requirements.txt": requirements,
    }

    if has_skills:
        result["skills/"] = None

    return result


def _render_mcp_section(mcp_config: dict | None) -> str:
    if not mcp_config or not mcp_config.get("url"):
        return "", ""

    url = mcp_config.get("url", "")
    transport = mcp_config.get("transport", "sse")
    headers = mcp_config.get("headers", {})

    headers_str = json.dumps(headers, indent=4)

    init_code = f"""
from langchain_mcp_adapters.client import MultiServerMCPClient

mcp_client = MultiServerMCPClient({{
    "mcp_server": {{
        "url": "{url}",
        "transport": "{transport}",
        "headers": {headers_str},
    }}
}})

async def get_mcp_tools():
    tools = await mcp_client.get_tools()
    return tools
"""

    health_code = """
@app.get("/mcp/health")
async def mcp_health():
    try:
        await mcp_client.get_tools()
        return {"status": "healthy", "mcp_server": MCP_CONFIG["url"]}
    except Exception as e:
        return {"status": "error", "error": str(e)}
"""

    return init_code, health_code


def _render_tools_config(enabled_tools: list, excluded_tools: list) -> str:
    tools_list_str = json.dumps(enabled_tools, ensure_ascii=False)
    excluded_list_str = json.dumps(excluded_tools, ensure_ascii=False)
    return f"""ENABLED_TOOLS = {tools_list_str}
EXCLUDED_TOOLS = {excluded_list_str}"""


def _render_skills_config(skills: list, has_skills: bool) -> str:
    if not has_skills:
        return """SKILLS_ENABLED = False
SKILLS_SOURCES = []"""

    sources = ["/skills/"]
    sources_str = json.dumps(sources)
    skills_meta = json.dumps([{
        "name": s.get("name", ""),
        "description": s.get("description", ""),
    } for s in skills], ensure_ascii=False, indent=4)

    return f"""SKILLS_ENABLED = True
SKILLS_SOURCES = {sources_str}
SKILLS_METADATA = {skills_meta}"""


def _render_agent_py(
    name: str,
    description: str,
    model: str,
    endpoint: str,
    api_key: str,
    system_prompt: str,
    enabled_tools: list,
    excluded_tools: list,
    mcp_config: dict | None,
    sub_agents: list,
    enable_memory: bool,
    max_iterations: int,
    has_skills: bool,
    skills: list,
    has_mcp: bool,
) -> str:
    mcp_init, mcp_health = _render_mcp_section(mcp_config)
    tools_config = _render_tools_config(enabled_tools, excluded_tools)
    skills_config = _render_skills_config(skills, has_skills)
    sub_agents_str = json.dumps(sub_agents, ensure_ascii=False)

    imports = "import os\nfrom fastapi import FastAPI, HTTPException\nfrom fastapi.middleware.cors import CORSMiddleware\nfrom pydantic import BaseModel\nimport uvicorn\nfrom langchain.chat_models import init_chat_model\nfrom deepagents import create_deep_agent"
    if has_skills:
        imports += "\nfrom deepagents.backends.filesystem import FilesystemBackend"
    if has_mcp:
        imports += mcp_init

    header = f'''#!/usr/bin/env python3
"""
Agent: {name}
Description: {description}
Generated by Agent Manager
"""

{imports}
# ============================================================
# Configuration
# ============================================================
MODEL_NAME = "{model}"
MODEL_ENDPOINT = "{endpoint}"
API_KEY = os.environ.get("LLM_API_KEY", "{api_key}")
SYSTEM_PROMPT = """{system_prompt}"""
{tools_config}
SUB_AGENTS = {sub_agents_str}
ENABLE_MEMORY = {enable_memory}
MAX_ITERATIONS = {max_iterations}
{skills_config}

# ============================================================
# Initialize Model and Agent
# ============================================================
model = init_chat_model(
    model=MODEL_NAME,
    model_provider="openai",
    openai_api_key=API_KEY,
    openai_api_base=MODEL_ENDPOINT,
)'''

    # Backend setup
    body = ""
    if has_skills:
        body += '\n\nbackend = FilesystemBackend(root_dir="/skills")'

    # Tools setup with MCP
    if has_mcp:
        body += f"""

TOOLS = []

import asyncio
loop = asyncio.new_event_loop()
asyncio.set_event_loop(loop)
mcp_tools = loop.run_until_complete(get_mcp_tools())
all_tools = TOOLS + mcp_tools
loop.close()

agent = create_deep_agent(
    model=model,
    system_prompt=SYSTEM_PROMPT,
    tools=all_tools,
    {"skills=SKILLS_SOURCES," if has_skills else ""}subagents=SUB_AGENTS if SUB_AGENTS else None,
)"""
    else:
        body += f"""

TOOLS = []
all_tools = TOOLS

agent = create_deep_agent(
    model=model,
    system_prompt=SYSTEM_PROMPT,
    tools=all_tools if all_tools else None,
    {"skills=SKILLS_SOURCES," if has_skills else ""}subagents=SUB_AGENTS if SUB_AGENTS else None,
)"""

    # FastAPI app
    fastapi_code = f'''
# ============================================================
# FastAPI Application
# ============================================================
app = FastAPI(
    title="{name}",
    description="{description}",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class ChatRequest(BaseModel):
    message: str
    history: list = []
    stream: bool = False


class ChatResponse(BaseModel):
    success: bool
    data: dict | str
    error: str | None = None


@app.get("/health")
async def health_check():
    return {{"status": "healthy", "agent": "{name}"}}
'''

    if has_mcp:
        fastapi_code += mcp_health

    fastapi_code += f'''
@app.get("/tools")
async def list_tools():
    return {{
        "enabled_tools": ENABLED_TOOLS,
        "excluded_tools": EXCLUDED_TOOLS,
        "skills_enabled": SKILLS_ENABLED,
        "mcp_enabled": {str(has_mcp).lower()},
    }}


@app.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    try:
        messages = []
        for h in request.history:
            messages.append({{"role": h["role"], "content": h["content"]}})
        messages.append({{"role": "user", "content": request.message}})

        result = agent.invoke({{"messages": messages}})

        last_msg = ""
        if "messages" in result:
            for m in reversed(result["messages"]):
                if hasattr(m, "content"):
                    last_msg = m.content
                    break

        return ChatResponse(
            success=True,
            data={{"response": last_msg}},
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/")
async def root():
    return {{
        "agent": "{name}",
        "description": "{description}",
        "model": MODEL_NAME,
        "enabled_tools": ENABLED_TOOLS,
        "skills": SKILLS_METADATA if SKILLS_ENABLED else [],
        "mcp": {str(has_mcp).lower()},
        "endpoint": "/chat",
        "health": "/health",
    }}


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
'''

    return header + body + fastapi_code


def _render_dockerfile(name: str, has_skills: bool = False, base_image: str = "") -> str:
    from_image = base_image if base_image else "python:3.12-slim"
    skills_copy = ""
    if has_skills:
        skills_copy = "\nCOPY skills/ /skills/"

    if base_image:
        return f'''FROM {from_image}

WORKDIR /app

COPY agent.py .
{skills_copy}

EXPOSE 8000

ENV PYTHONUNBUFFERED=1

HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \\
    CMD curl -f http://localhost:8000/health || exit 1

CMD ["python", "-u", "agent.py"]
'''

    return f'''FROM {from_image}

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \\
    curl ca-certificates \\
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY agent.py .
{skills_copy}

EXPOSE 8000

ENV PYTHONUNBUFFERED=1

HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \\
    CMD curl -f http://localhost:8000/health || exit 1

CMD ["python", "-u", "agent.py"]
'''


def _render_requirements(has_mcp: bool = False) -> str:
    deps = '''deepagents>=0.5.0
langchain>=1.0.0
langchain-openai>=1.0.0
fastapi>=0.100.0
uvicorn>=0.30.0
pydantic>=2.0.0
'''
    if has_mcp:
        deps += 'langchain-mcp-adapters>=0.1.0\n'
    return deps


def validate_config(config: dict) -> list[str]:
    errors = []
    required = ["name", "description", "model", "system_prompt"]
    for field in required:
        if field not in config:
            errors.append(f"Missing required field: {field}")
    if not config.get("name", "").strip():
        errors.append("name cannot be empty")
    return errors


def main():
    if len(sys.argv) < 2:
        print("Usage: python generator.py <config.json> [output_dir]")
        print("   or: echo '{{...}}' | python generator.py --stdin [output_dir]")
        sys.exit(1)

    if sys.argv[1] == "--stdin":
        config = json.load(sys.stdin)
        output_dir = sys.argv[2] if len(sys.argv) > 2 else "."
    else:
        with open(sys.argv[1]) as f:
            config = json.load(f)
        output_dir = sys.argv[2] if len(sys.argv) > 2 else Path(sys.argv[1]).parent

    errors = validate_config(config)
    if errors:
        print("Validation errors:")
        for e in errors:
            print(f"  - {e}")
        sys.exit(1)

    result = generate_agent_code(config)

    os.makedirs(output_dir, exist_ok=True)
    for filename, content in result.items():
        if filename.endswith("/"):
            skill_dir = os.path.join(output_dir, filename)
            os.makedirs(skill_dir, exist_ok=True)
        else:
            filepath = os.path.join(output_dir, filename)
            with open(filepath, "w") as f:
                f.write(content)
            print(f"Generated: {filepath}")


if __name__ == "__main__":
    main()
