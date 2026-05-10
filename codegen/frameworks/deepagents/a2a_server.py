#!/usr/bin/env python3
"""
A2A Server Generator

生成 A2A v1.0.0 Server 端点代码
"""

import json
from typing import Optional
from pathlib import Path


class A2AServerGenerator:
    """A2A Server 端点代码生成器"""
    
    def __init__(
        self,
        agent_name: str,
        agent_description: str = "",
        streaming: bool = True,
        a2ui_enabled: bool = True,
    ):
        self.agent_name = agent_name
        self.agent_description = agent_description
        self.streaming = streaming
        self.a2ui_enabled = a2ui_enabled
    
    def generate_main_py(self) -> str:
        """生成 main.py"""
        return f'''#!/usr/bin/env python3
"""
Generated A2A Server: {self.agent_name}

A2A v1.0.0 + A2UI v0.8 Extension
"""

import os
import uuid
from pathlib import Path
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
import uvicorn

from agent_card import serve_agent_card
from a2a_routes import register_a2a_routes
from a2ui_handler import A2UIExtension
from a2a_clients import SubAgentRegistry
from llm_config import llm_config

AGENT_DIR = Path(__file__).parent

app = FastAPI(
    title="{self.agent_name}",
    description="{self.agent_description}",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ============================================================
# LLM 配置验证
# ============================================================
config_errors = llm_config.validate()
if config_errors:
    print("Warning: LLM configuration issues:")
    for err in config_errors:
        print(f"  - {{err}}")

# ============================================================
# Agent 初始化 (DeepAgents 加载 OAF)
# ============================================================
agent = None
try:
    from deepagents import load_oaf_agent
    agent = load_oaf_agent(AGENT_DIR)
except Exception as e:
    print(f"Warning: Could not load OAF agent: {{e}}")
    agent = None

# ============================================================
# A2UI Extension
# ============================================================
a2ui = A2UIExtension(
    catalog_id="https://a2ui.org/specification/v0_8/standard_catalog_definition.json",
    supported_catalogs=[
        "https://a2ui.org/specification/v0_8/standard_catalog_definition.json"
    ],
    accepts_inline_catalogs=True,
)

# ============================================================
# 子 Agent 注册表
# ============================================================
sub_agents = SubAgentRegistry()

# ============================================================
# Agent Card Discovery
# ============================================================
@app.get("/.well-known/agent-card.json")
async def agent_card():
    return serve_agent_card()

# ============================================================
# A2A 操作端点
# ============================================================
register_a2a_routes(app, agent, a2ui, sub_agents, llm_config)

# ============================================================
# 健康检查
# ============================================================
@app.get("/health")
async def health():
    return {{"status": "healthy", "agent": "{self.agent_name}"}}

@app.get("/")
async def root():
    return {{
        "agent": "{self.agent_name}",
        "description": "{self.agent_description}",
        "a2a": "1.0.0",
        "a2ui": "v0.8",
        "endpoints": {{
            "agent_card": "/.well-known/agent-card.json",
            "jsonrpc": "/",
            "tasks": "/tasks",
            "health": "/health",
        }},
    }}

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
'''
    
    def generate_a2a_routes_py(self) -> str:
        """生成 a2a_routes.py"""
        return '''"""A2A v1.0 操作端点 - JSON-RPC + REST 双绑定"""

import json
import uuid
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse


def register_a2a_routes(app: FastAPI, agent, a2ui, sub_agents, llm_config):
    """注册 A2A 操作端点"""
    
    tasks_store: dict = {}
    
    # ============================================================
    # JSON-RPC 2.0 Binding
    # ============================================================
    @app.post("/")
    async def jsonrpc_handler(request: Request):
        body = await request.json()
        method = body.get("method")
        params = body.get("params", {})
        msg_id = body.get("id")
        
        try:
            if method == "message/send":
                result = await _handle_send_message(params, agent, a2ui, llm_config, tasks_store)
            elif method == "message/stream":
                return StreamingResponse(
                    _handle_stream_message(params, agent, a2ui, llm_config),
                    media_type="text/event-stream",
                )
            elif method == "tasks/get":
                result = await _handle_get_task(params, tasks_store)
            elif method == "tasks/list":
                result = await _handle_list_tasks(params, tasks_store)
            elif method == "tasks/cancel":
                result = await _handle_cancel_task(params, tasks_store)
            else:
                return JSONResponse({
                    "jsonrpc": "2.0",
                    "error": {"code": -32601, "message": f"Method not found: {method}"},
                    "id": msg_id,
                })
            
            return JSONResponse({
                "jsonrpc": "2.0",
                "result": result,
                "id": msg_id,
            })
        except Exception as e:
            return JSONResponse({
                "jsonrpc": "2.0",
                "error": {"code": -1, "message": str(e)},
                "id": msg_id,
            })
    
    # ============================================================
    # HTTP+JSON/REST Binding
    # ============================================================
    @app.post("/tasks")
    async def rest_send_message(request: Request):
        body = await request.json()
        result = await _handle_send_message(body, agent, a2ui, llm_config, tasks_store)
        return JSONResponse(result)
    
    @app.get("/tasks/{task_id}")
    async def rest_get_task(task_id: str):
        result = await _handle_get_task({"id": task_id}, tasks_store)
        return JSONResponse(result)
    
    @app.get("/tasks")
    async def rest_list_tasks():
        result = await _handle_list_tasks({}, tasks_store)
        return JSONResponse(result)


async def _handle_send_message(params: dict, agent, a2ui, llm_config, tasks_store: dict):
    """处理 message/send"""
    message = params.get("message", {})
    parts = message.get("parts", [])
    
    user_text = ""
    for part in parts:
        if "text" in part:
            user_text = part["text"]
            break
    
    metadata = params.get("metadata", {})
    a2ui_caps = metadata.get("a2uiClientCapabilities", {})
    
    task_id = str(uuid.uuid4())
    task = {
        "id": task_id,
        "status": {"state": "working"},
        "artifacts": [],
    }
    tasks_store[task_id] = task
    
    try:
        if agent:
            result = await agent.invoke(user_text)
            
            if hasattr(result, "content"):
                response_text = result.content
            elif isinstance(result, dict) and "messages" in result:
                messages = result["messages"]
                response_text = ""
                for msg in reversed(messages):
                    if hasattr(msg, "content"):
                        response_text = msg.content
                        break
            else:
                response_text = str(result)
        else:
            response_text = f"Echo: {user_text}"
        
        if a2ui_caps.get("supportedCatalogIds"):
            artifact = a2ui.generate_artifact(
                surface_id=task_id,
                response_text=response_text,
                catalog_id=a2ui_caps["supportedCatalogIds"][0],
            )
        else:
            artifact = {
                "artifactId": str(uuid.uuid4()),
                "name": "response",
                "parts": [{"text": response_text}],
            }
        
        task["artifacts"].append(artifact)
        task["status"]["state"] = "completed"
        
    except Exception as e:
        task["status"]["state"] = "failed"
        task["status"]["message"] = str(e)
    
    return task


async def _handle_get_task(params: dict, tasks_store: dict):
    """处理 tasks/get"""
    task_id = params.get("id")
    if not task_id or task_id not in tasks_store:
        return {"error": "Task not found"}
    return tasks_store[task_id]


async def _handle_list_tasks(params: dict, tasks_store: dict):
    """处理 tasks/list"""
    return list(tasks_store.values())


async def _handle_cancel_task(params: dict, tasks_store: dict):
    """处理 tasks/cancel"""
    task_id = params.get("id")
    if not task_id or task_id not in tasks_store:
        return {"error": "Task not found"}
    
    task = tasks_store[task_id]
    task["status"]["state"] = "canceled"
    return task


async def _handle_stream_message(params: dict, agent, a2ui, llm_config):
    """处理 message/stream (SSE)"""
    task = await _handle_send_message(params, agent, a2ui, llm_config, {})
    
    yield f"data: {json.dumps(task)}\\n\\n"
    
    yield "data: [DONE]\\n\\n"
'''
    
    def generate_agent_card_py(self) -> str:
        """生成 agent_card.py"""
        return f'''"""Agent Card 服务"""

from agent_card_gen import AgentCardGenerator

_generator = AgentCardGenerator(
    name="{self.agent_name}",
    description="{self.agent_description}",
    host="localhost",
    port=8000,
    a2ui_enabled={str(self.a2ui_enabled).lower()},
)

def serve_agent_card() -> dict:
    """返回 Agent Card"""
    return _generator.generate()
'''
    
    def generate_a2ui_handler_py(self) -> str:
        """生成 a2ui_handler.py"""
        return '''"""A2UI Handler"""

from a2ui_extension import A2UIExtension

a2ui = A2UIExtension()
'''
    
    def generate_requirements_txt(self) -> str:
        """生成 requirements.txt"""
        return '''fastapi>=0.100.0
uvicorn>=0.30.0
pydantic>=2.0.0
httpx>=0.25.0
python-dotenv>=1.0.0
pyyaml>=6.0.0
deepagents>=0.5.0
langchain>=1.0.0
langchain-openai>=1.0.0
'''
    
    def generate_dockerfile(self) -> str:
        """生成 Dockerfile"""
        return f'''FROM python:3.12-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \\
    curl ca-certificates \\
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8000

ENV PYTHONUNBUFFERED=1

HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \\
    CMD curl -f http://localhost:8000/health || exit 1

CMD ["python", "-u", "main.py"]
'''
    
    def generate_all(self, output_dir: Path | str) -> dict[str, str]:
        """生成所有文件"""
        output_dir = Path(output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)
        
        files = {
            "main.py": self.generate_main_py(),
            "a2a_routes.py": self.generate_a2a_routes_py(),
            "agent_card.py": self.generate_agent_card_py(),
            "a2ui_handler.py": self.generate_a2ui_handler_py(),
            "requirements.txt": self.generate_requirements_txt(),
            "Dockerfile": self.generate_dockerfile(),
        }
        
        for filename, content in files.items():
            (output_dir / filename).write_text(content, encoding="utf-8")
        
        return files
