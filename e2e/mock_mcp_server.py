#!/usr/bin/env python3
"""
Mock MCP Server for E2E testing.
Supports SSE transport on port 9998.
Implements a simple 'echo' and 'add' tool.
"""
import json
import sys
import asyncio
import uvicorn
from starlette.applications import Starlette
from starlette.responses import StreamingResponse, Response
from starlette.routing import Route

MCP_TOOLS = [
    {
        "name": "echo",
        "description": "Echo back the input message",
        "inputSchema": {
            "type": "object",
            "properties": {
                "message": {"type": "string", "description": "Message to echo"}
            },
            "required": ["message"]
        }
    },
    {
        "name": "add",
        "description": "Add two numbers together",
        "inputSchema": {
            "type": "object",
            "properties": {
                "a": {"type": "number", "description": "First number"},
                "b": {"type": "number", "description": "Second number"}
            },
            "required": ["a", "b"]
        }
    }
]


async def sse_handler(request):
    """SSE endpoint for MCP communication"""
    async def event_generator():
        # Send tools/list response first on connect
        yield f"data: {json.dumps({'jsonrpc': '2.0', 'result': {'tools': MCP_TOOLS}})}\n\n"

        # Keep connection alive
        while True:
            # Wait for requests (SSE is server-push, but we poll stdin)
            line = await request.receive()
            if isinstance(line, dict) and line.get("type") == "http.disconnect":
                break

            body_b = await request.body()
            if body_b:
                try:
                    req = json.loads(body_b)
                    await process_mcp_request(req, event_generator)
                except json.JSONDecodeError:
                    pass

            await asyncio.sleep(0.1)

    return StreamingResponse(event_generator(), media_type="text/event-stream")


async def process_mcp_request(req, generator):
    method = req.get("method", "")
    params = req.get("params", {})
    req_id = req.get("id")

    if method == "tools/call":
        tool_name = params.get("name", "")
        args = params.get("arguments", {})

        if tool_name == "echo":
            msg = args.get("message", "")
            result = {"content": [{"type": "text", "text": f"Echo: {msg}"}]}
        elif tool_name == "add":
            a = args.get("a", 0)
            b = args.get("b", 0)
            result = {"content": [{"type": "text", "text": f"{a} + {b} = {a + b}"}]}
        else:
            result = {"content": [{"type": "text", "text": f"Unknown tool: {tool_name}"}]}

        resp = json.dumps({"jsonrpc": "2.0", "id": req_id, "result": result})
        yield f"data: {resp}\n\n"
    elif method == "tools/list":
        resp = json.dumps({"jsonrpc": "2.0", "id": req_id, "result": {"tools": MCP_TOOLS}})
        yield f"data: {resp}\n\n"


async def mcp_http_handler(request):
    """Handle HTTP POST requests for MCP protocol"""
    try:
        body = await request.body()
        req = json.loads(body)
        method = req.get("method", "")
        params = req.get("params", {})
        req_id = req.get("id")

        if method == "tools/list":
            resp = {"jsonrpc": "2.0", "id": req_id, "result": {"tools": MCP_TOOLS}}
        elif method == "tools/call":
            tool_name = params.get("name", "")
            args = params.get("arguments", {})
            if tool_name == "echo":
                resp = {"jsonrpc": "2.0", "id": req_id, "result": {"content": [{"type": "text", "text": f"Echo: {args.get('message', '')}"}]}}
            elif tool_name == "add":
                resp = {"jsonrpc": "2.0", "id": req_id, "result": {"content": [{"type": "text", "text": f"{args.get('a', 0)} + {args.get('b', 0)} = {args.get('a', 0) + args.get('b', 0)}"}]}}
            else:
                resp = {"jsonrpc": "2.0", "id": req_id, "result": {"content": [{"type": "text", "text": f"Unknown tool: {tool_name}"}]}}
        else:
            resp = {"jsonrpc": "2.0", "id": req_id, "error": {"code": -1, "message": f"Unknown method: {method}"}}

        return Response(json.dumps(resp), media_type="application/json")
    except Exception as e:
        return Response(json.dumps({"jsonrpc": "2.0", "error": str(e)}), status_code=400)


async def health(request):
    return Response('{"status": "healthy", "server": "mock-mcp"}', media_type="application/json")


app = Starlette(routes=[
    Route("/health", health),
    Route("/sse", sse_handler),
    Route("/api/mcp", mcp_http_handler, methods=["POST"]),
])

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9998
    print(f"Mock MCP Server starting on port {port}")
    print(f"  Health: http://localhost:{port}/health")
    print(f"  SSE:    http://localhost:{port}/sse")
    print(f"  HTTP:   http://localhost:{port}/api/mcp")
    uvicorn.run(app, host="0.0.0.0", port=port, log_level="warning")
