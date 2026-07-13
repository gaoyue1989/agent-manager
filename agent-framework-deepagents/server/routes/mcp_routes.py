import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse


class MCPRoutes:
    """MCP 资源代理端点 — 用于 MCP Apps Host 获取 UI 资源"""

    def __init__(self, app: FastAPI, mcp_configs: list[dict]):
        self.app = app
        self.mcp_configs = mcp_configs
        self._register_routes()

    def _register_routes(self):
        @self.app.post("/mcp/resources/read")
        async def mcp_resources_read(request: Request):
            body = await request.json()
            uri = body.get("uri", "")
            server = body.get("server", "")

            if not uri or not server:
                return JSONResponse(
                    {"error": "Missing uri or server"},
                    status_code=400,
                )

            # 优先使用活动 MCP client session（SSE 长连接）
            result = await self._read_via_active_session(request, server, uri)
            if result is not None:
                return JSONResponse(result)

            # 降级：直接 HTTP 调用
            conn = self._get_connection(server)
            if not conn:
                return JSONResponse(
                    {"error": f"Server {server} not found"},
                    status_code=404,
                )

            result = await self._call_mcp_resource(conn, uri)
            return JSONResponse(result)

        @self.app.post("/mcp/tools/list")
        async def mcp_tools_list(request: Request):
            body = await request.json()
            server = body.get("server", "")

            if not server:
                return JSONResponse(
                    {"error": "Missing server"},
                    status_code=400,
                )

            conn = self._get_connection(server)
            if not conn:
                return JSONResponse(
                    {"error": f"Server {server} not found"},
                    status_code=404,
                )

            result = await self._call_mcp_method(conn, "tools/list", {})
            return JSONResponse(result)

    async def _read_via_active_session(self, request: Request, server: str, uri: str) -> dict | None:
        mcp_client = getattr(request.app.state, "mcp_client", None)
        if not mcp_client:
            return None

        sessions = getattr(mcp_client, "sessions", None) or getattr(mcp_client, "_sessions", None)
        if not sessions:
            return None

        session = sessions.get(server)
        if not session:
            return None

        try:
            result = await session.read_resource(uri)
            contents = []
            for c in (result.contents or []):
                entry = {"uri": c.uri, "mimeType": c.mimeType}
                if hasattr(c, "text"):
                    entry["text"] = c.text
                if hasattr(c, "blob"):
                    entry["blob"] = c.blob
                contents.append(entry)
            return {"contents": contents}
        except Exception as e:
            print(f"MCP session read_resource error: {e}")
            return None

    def _get_connection(self, server: str) -> dict | None:
        for cfg in self.mcp_configs:
            if cfg.get("server") == server:
                return cfg.get("connection", {})
        return None

    async def _call_mcp_resource(self, conn: dict, uri: str) -> dict:
        url = conn.get("url", "")
        if not url:
            return {"error": "No connection URL"}

        base_url = url.rstrip("/").replace("/sse", "")
        session_id = "mcp-apps-host"

        async with httpx.AsyncClient(timeout=30.0) as client:
            try:
                resp = await client.post(
                    f"{base_url}/message?sessionId={session_id}",
                    json={
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "resources/read",
                        "params": {"uri": uri},
                    },
                    headers={"Content-Type": "application/json"},
                )
                data = resp.json()
                return data.get("result", data)
            except Exception as e:
                return {"error": str(e)}

    async def _call_mcp_method(self, conn: dict, method: str, params: dict) -> dict:
        url = conn.get("url", "")
        if not url:
            return {"error": "No connection URL"}

        base_url = url.rstrip("/").replace("/sse", "")
        session_id = "mcp-apps-host"

        async with httpx.AsyncClient(timeout=30.0) as client:
            try:
                resp = await client.post(
                    f"{base_url}/message?sessionId={session_id}",
                    json={
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": method,
                        "params": params,
                    },
                    headers={"Content-Type": "application/json"},
                )
                data = resp.json()
                return data.get("result", data)
            except Exception as e:
                return {"error": str(e)}
