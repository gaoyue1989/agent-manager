import json
import uuid
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

from server.config import AppConfig
from server.models.a2a_types import extract_user_text
from server.services.agent_runtime import AgentRuntime
from server.services.a2ui_service import A2UIService


class A2ARoutes:
    """A2A v1.0.0 端点 — 全 Checkpoint 驱动

    JSON-RPC 2.0 + HTTP REST + SSE streaming
    会话由 MySQL checkpoint 持久化，thread_id 贯穿所有请求
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
                elif method == "threads/list":
                    result = await self._handle_list_threads(params)
                elif method == "threads/get":
                    result = await self._handle_get_thread(params)
                elif method == "threads/delete":
                    result = await self._handle_delete_thread(params)
                elif method == "threads/create":
                    result = await self._handle_create_thread(params)
                elif method in ("tasks/get", "threads/get"):
                    result = await self._handle_get_thread(params)
                elif method in ("tasks/list", "threads/list"):
                    result = await self._handle_list_threads(params)
                elif method == "tasks/cancel":
                    result = await self._handle_delete_thread(params)
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
            result = await self._handle_get_thread({"thread_id": task_id})
            return JSONResponse(result)

        @app.get("/tasks")
        async def rest_list_tasks():
            result = await self._handle_list_threads({})
            return JSONResponse(result)

    async def _handle_send_message(self, params: dict) -> dict:
        message = params.get("message", {})
        user_text = extract_user_text(message)
        metadata = params.get("metadata", {})
        thread_id = metadata.get("thread_id") or str(uuid.uuid4())
        a2ui_caps = metadata.get("a2uiClientCapabilities", {})

        response_text, thread_id = await self.agent.invoke(user_text, thread_id)

        if a2ui_caps.get("supportedCatalogIds"):
            catalog_id = a2ui_caps["supportedCatalogIds"][0]
            artifact = self.a2ui.generate_artifact(
                surface_id=thread_id,
                response_text=response_text,
                catalog_id=catalog_id,
            )
        else:
            artifact = {
                "artifactId": str(uuid.uuid4()),
                "name": "response",
                "parts": [{"text": response_text}],
            }

        return {
            "id": thread_id,
            "status": {"state": "completed"},
            "artifacts": [artifact],
            "metadata": {"thread_id": thread_id},
        }

    async def _handle_stream_message(self, params: dict):
        message = params.get("message", {})
        user_text = extract_user_text(message)
        metadata = params.get("metadata", {})
        thread_id = metadata.get("thread_id") or str(uuid.uuid4())
        a2ui_caps = metadata.get("a2uiClientCapabilities", {})

        yield f"event: task_update\ndata: {json.dumps({'id': thread_id, 'state': 'working', 'metadata': {'thread_id': thread_id}})}\n\n"

        full_text = ""
        tool_calls = []

        try:
            async for event in self.agent.invoke_stream(user_text, thread_id):
                evt_type = event.get("type", "")

                if evt_type == "token":
                    token = event.get("token", "")
                    full_text += token
                    yield f"event: token\ndata: {json.dumps({'token': token, 'task_id': thread_id})}\n\n"

                elif evt_type == "tool_call":
                    tc = {
                        "type": "tool_call",
                        "name": event.get("name", ""),
                        "args": event.get("args", {}),
                        "tool_call_id": event.get("tool_call_id", ""),
                    }
                    tool_calls.append(tc)
                    yield f"event: tool_call\ndata: {json.dumps({'task_id': thread_id, **tc})}\n\n"

                elif evt_type == "tool_result":
                    tr = {
                        "type": "tool_result",
                        "name": event.get("name", ""),
                        "result": event.get("result", ""),
                        "tool_call_id": event.get("tool_call_id", ""),
                    }
                    yield f"event: tool_result\ndata: {json.dumps({'task_id': thread_id, **tr})}\n\n"

                elif evt_type == "done":
                    # 从 checkpoint 获取完整的 tool_call args（流式传输中 args 可能为空）
                    for tc in tool_calls:
                        tc_id = tc.get("tool_call_id", "")
                        tc_name = tc.get("name", "")
                        tc_args = tc.get("args", {})
                        if not tc_args and tc_id:
                            real_args = await self._get_tool_args_from_state(thread_id, tc_id, tc_name)
                            if real_args:
                                yield f"event: tool_call\ndata: {json.dumps({'task_id': thread_id, 'type': 'tool_call', 'name': tc_name, 'args': real_args, 'tool_call_id': tc_id})}\n\n"
                    if full_text:
                        if a2ui_caps.get("supportedCatalogIds"):
                            catalog_id = a2ui_caps["supportedCatalogIds"][0]
                            artifact = self.a2ui.generate_artifact(thread_id, full_text, catalog_id)
                        else:
                            artifact = {
                                "artifactId": str(uuid.uuid4()),
                                "name": "response",
                                "parts": [{"text": full_text}],
                            }

                        yield f"event: task_update\ndata: {json.dumps({'id': thread_id, 'state': 'completed', 'artifacts': [artifact], 'metadata': {'thread_id': thread_id}})}\n\n"
                    else:
                        yield f"event: task_update\ndata: {json.dumps({'id': thread_id, 'state': 'completed', 'metadata': {'thread_id': thread_id}})}\n\n"
                    return

        except Exception as e:
            yield f"event: error\ndata: {json.dumps({'task_id': thread_id, 'error': str(e)})}\n\n"

        yield f"event: done\ndata: [DONE]\n\n"

    async def _get_tool_args_from_state(self, thread_id: str, tool_call_id: str, tool_name: str) -> dict:
        """从 checkpoint state 中获取完整的 tool_call args"""
        try:
            state = await self.agent.get_thread_state(thread_id)
            for msg in state.get("messages", []):
                if msg.get("type") == "tool_call" and msg.get("tool_name") == tool_name and msg.get("tool_call_id") == tool_call_id:
                    return msg.get("tool_args", {})
        except Exception:
            pass
        return {}

    async def _handle_list_threads(self, params: dict) -> dict:
        threads = await self.agent.list_threads()
        return {"threads": threads}

    async def _handle_get_thread(self, params: dict) -> dict:
        thread_id = params.get("thread_id") or params.get("id") or ""
        if not thread_id:
            return {"error": "thread_id is required"}
        state = await self.agent.get_thread_state(thread_id)
        if state.get("error"):
            return {"error": state["error"]}
        return state

    async def _handle_delete_thread(self, params: dict) -> dict:
        thread_id = params.get("thread_id") or params.get("id") or ""
        if not thread_id:
            return {"error": "thread_id is required"}
        deleted = await self.agent.delete_thread(thread_id)
        return {"thread_id": thread_id, "deleted": deleted}

    async def _handle_create_thread(self, params: dict) -> dict:
        thread_id = str(uuid.uuid4())
        metadata = params.get("metadata", {})
        return {"thread_id": thread_id, "metadata": metadata}
