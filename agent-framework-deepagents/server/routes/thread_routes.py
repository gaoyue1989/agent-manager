from fastapi import FastAPI, HTTPException

from server.services.agent_runtime import AgentRuntime


class ThreadRoutes:
    """Thread 管理 REST API

    GET  /threads          — 列出所有 thread
    GET  /threads/{id}     — 获取 thread 对话历史
    DELETE /threads/{id}   — 删除 thread
    """

    def __init__(self, app: FastAPI, agent_runtime: AgentRuntime):
        self.app = app
        self.agent = agent_runtime
        self._register()

    def _register(self):
        app = self.app

        @app.get("/threads")
        async def list_threads():
            return await self.agent.list_threads()

        @app.get("/threads/{thread_id}")
        async def get_thread(thread_id: str):
            state = await self.agent.get_thread_state(thread_id)
            if "error" in state and state.get("error"):
                raise HTTPException(status_code=404, detail=state["error"])
            return state

        @app.delete("/threads/{thread_id}")
        async def delete_thread(thread_id: str):
            deleted = await self.agent.delete_thread(thread_id)
            if not deleted:
                raise HTTPException(status_code=404, detail="Thread not found or checkpoint not available")
            return {"thread_id": thread_id, "deleted": True}
