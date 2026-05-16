import json
import uuid
import asyncio
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

from server.config import AppConfig
from server.models.a2a_types import extract_user_text
from server.services.agent_runtime import AgentRuntime
from server.services.a2ui_service import A2UIService


class A2ARoutes:
    """A2A v1.0.0 端点注册

    JSON-RPC 2.0 binding + HTTP REST binding + SSE streaming
    """

    def __init__(
        self,
        app: FastAPI,
        config: AppConfig,
        agent_runtime: AgentRuntime,
        a2ui_service: A2UIService,
    ):
        self.app = app
        self.config = config
        self.agent = agent_runtime
        self.a2ui = a2ui_service
        self.tasks_store: dict[str, dict] = {}

        self._register_routes()

    def _register_routes(self):
        app = self.app

        @app.post("/")
        async def jsonrpc_handler(request: Request):
            body = await request.json()
            method = body.get("method")
            params = body.get("params", {})
            msg_id = body.get("id")

            try:
                if method == "message/send":
                    result = await self._handle_send_message(params)
                elif method == "message/stream":
                    return StreamingResponse(
                        self._handle_stream_message(params),
                        media_type="text/event-stream",
                        headers={
                            "Cache-Control": "no-cache",
                            "Connection": "keep-alive",
                            "X-Accel-Buffering": "no",
                        },
                    )
                elif method == "tasks/get":
                    result = await self._handle_get_task(params)
                elif method == "tasks/list":
                    result = await self._handle_list_tasks(params)
                elif method == "tasks/cancel":
                    result = await self._handle_cancel_task(params)
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

        @app.post("/tasks")
        async def rest_send_message(request: Request):
            body = await request.json()
            result = await self._handle_send_message(body)
            return JSONResponse(result)

        @app.get("/tasks/{task_id}")
        async def rest_get_task(task_id: str):
            result = await self._handle_get_task({"id": task_id})
            return JSONResponse(result)

        @app.get("/tasks")
        async def rest_list_tasks():
            result = await self._handle_list_tasks({})
            return JSONResponse(result)

    async def _handle_send_message(self, params: dict) -> dict:
        message = params.get("message", {})
        user_text = extract_user_text(message)
        metadata = params.get("metadata", {})
        a2ui_caps = metadata.get("a2uiClientCapabilities", {})

        task_id = str(uuid.uuid4())
        history = params.get("history", [])

        task = {
            "id": task_id,
            "status": {"state": "working"},
            "artifacts": [],
            "history": history,
        }
        self.tasks_store[task_id] = task

        try:
            response_text = await self.agent.invoke(user_text, history)

            if a2ui_caps.get("supportedCatalogIds"):
                catalog_id = a2ui_caps["supportedCatalogIds"][0]
                artifact = self.a2ui.generate_artifact(
                    surface_id=task_id,
                    response_text=response_text,
                    catalog_id=catalog_id,
                )
            else:
                artifact = {
                    "artifactId": str(uuid.uuid4()),
                    "name": "response",
                    "parts": [{"text": response_text}],
                }

            task["artifacts"].append(artifact)
            task["history"].append({"role": "user", "content": user_text})
            task["history"].append({"role": "assistant", "content": response_text})
            task["status"]["state"] = "completed"

        except Exception as e:
            task["status"]["state"] = "failed"
            task["status"]["message"] = str(e)

        return task

    async def _handle_stream_message(self, params: dict):
        message = params.get("message", {})
        user_text = extract_user_text(message)
        metadata = params.get("metadata", {})
        a2ui_caps = metadata.get("a2uiClientCapabilities", {})
        history = params.get("history", [])
        task_id = str(uuid.uuid4())

        task = {
            "id": task_id,
            "status": {"state": "working"},
            "artifacts": [],
            "history": history,
        }
        self.tasks_store[task_id] = task

        yield f"event: task_update\ndata: {json.dumps({'id': task_id, 'state': 'working'})}\n\n"

        full_text = ""

        try:
            async for token in self.agent.invoke_stream(user_text, history):
                full_text += token
                yield f"event: token\ndata: {json.dumps({'token': token, 'task_id': task_id})}\n\n"

            if full_text:
                if a2ui_caps.get("supportedCatalogIds"):
                    catalog_id = a2ui_caps["supportedCatalogIds"][0]
                    artifact = self.a2ui.generate_artifact(task_id, full_text, catalog_id)
                else:
                    artifact = {
                        "artifactId": str(uuid.uuid4()),
                        "name": "response",
                        "parts": [{"text": full_text}],
                    }

                task["artifacts"].append(artifact)
                task["history"].append({"role": "user", "content": user_text})
                task["history"].append({"role": "assistant", "content": full_text})
                task["status"]["state"] = "completed"

                yield f"event: task_update\ndata: {json.dumps({'id': task_id, 'state': 'completed', 'artifacts': task['artifacts']})}\n\n"

        except Exception as e:
            task["status"]["state"] = "failed"
            task["status"]["message"] = str(e)
            yield f"event: error\ndata: {json.dumps({'task_id': task_id, 'error': str(e)})}\n\n"

        yield f"event: done\ndata: [DONE]\n\n"

    async def _handle_get_task(self, params: dict) -> dict:
        task_id = params.get("id")
        if not task_id or task_id not in self.tasks_store:
            return {"error": "Task not found"}
        return self.tasks_store[task_id]

    async def _handle_list_tasks(self, params: dict) -> list:
        return list(self.tasks_store.values())

    async def _handle_cancel_task(self, params: dict) -> dict:
        task_id = params.get("id")
        if not task_id or task_id not in self.tasks_store:
            return {"error": "Task not found"}
        task = self.tasks_store[task_id]
        if task["status"]["state"] == "working":
            task["status"]["state"] = "canceled"
        return task
