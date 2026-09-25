#!/usr/bin/env python3
"""评测 mock MCP 服务（streamableHttp 子集，Python 标准库零依赖）。

对应设计 docs/design/agent-framework-eval-env-provisioning-design.md §4.3：
按工具目录（JSON）提供 allow/ask 权限建模的可控工具，支撑工具调用与 HITL 确认流评测，
不依赖真实平台后端。

协议子集（modelcontextprotocol java-sdk 0.17 HttpClientStreamableHttpTransport 实测口径）：
  - POST /mcp：JSON-RPC 2.0，application/json 单对象应答（initialize / tools/list / tools/call
    及批量数组）；通知（无 id）回 202
  - GET /mcp：405（无服务端主动消息面；SDK 对 405 按"无 SSE 监听"降级）
  - DELETE /mcp：204 会话终止
工具 handler 三类：echo（回显参数）/ fail（工具错误）/ canned（固定 JSON 载荷）。
权限语义由 OAF config.yaml permissions.tools 表达；本服务在 tools/list 里配
annotations.readOnlyHint 供对照（allow→true / ask→false）。

用法：
  python3 bench/eval/mock/mcp_server.py --catalog catalog.json --port 18082 --log mock-mcp.jsonl
"""

import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PROTOCOL_VERSIONS = ("2025-06-18", "2025-03-26", "2024-11-05")
SERVER_INFO = {"name": "eval-mock-mcp", "version": "1.0.0"}

# 工具目录（catalog.json）：{"tools": [{name, description, permission, handler, inputSchema?, payload?}]}
_CATALOG: dict = {"tools": []}
_LOG_LOCK = threading.Lock()
_LOG_PATH: str | None = None


def _log(entry: dict) -> None:
    """请求日志（JSONL）：RCA 对照与报告佐证用。"""
    if not _LOG_PATH:
        return
    entry["ts"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    with _LOG_LOCK:
        try:
            with open(_LOG_PATH, "a", encoding="utf-8") as f:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        except OSError:
            pass


def _tool_def(tool: dict) -> dict:
    """工具目录 → MCP tools/list 条目。"""
    return {
        "name": tool["name"],
        "description": tool.get("description", f"mock tool {tool['name']}"),
        "inputSchema": tool.get("inputSchema", {"type": "object", "properties": {}}),
        "annotations": {"readOnlyHint": tool.get("permission", "allow") != "ask"},
    }


def _call_tool(name: str, arguments: dict) -> dict:
    """执行工具 handler，返回 MCP tools/call result。未知工具抛 KeyError。"""
    tool = next((t for t in _CATALOG["tools"] if t["name"] == name), None)
    if tool is None:
        raise KeyError(name)
    handler = tool.get("handler", "canned")
    if handler == "fail":
        return {"content": [{"type": "text", "text": f"mock failure from {name}"}], "isError": True}
    if handler == "echo":
        text = "echo:" + json.dumps(arguments, ensure_ascii=False, sort_keys=True)
    else:  # canned：固定 JSON 载荷
        text = json.dumps(tool.get("payload", {"ok": True}), ensure_ascii=False)
    return {"content": [{"type": "text", "text": text}], "isError": False}


def _handle_message(msg: dict) -> dict | None:
    """单条 JSON-RPC 消息 → 应答（通知返回 None）。"""
    method = msg.get("method", "")
    msg_id = msg.get("id")
    params = msg.get("params") or {}
    is_notification = "id" not in msg

    if is_notification:
        _log({"dir": "in", "method": method, "notification": True})
        return None

    if method == "initialize":
        client_version = params.get("protocolVersion", PROTOCOL_VERSIONS[0])
        result = {
            "protocolVersion": client_version if client_version in PROTOCOL_VERSIONS
            else PROTOCOL_VERSIONS[0],
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": SERVER_INFO,
        }
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": [_tool_def(t) for t in _CATALOG["tools"]]}
    elif method == "tools/call":
        name = params.get("name", "")
        try:
            result = _call_tool(name, params.get("arguments") or {})
        except KeyError:
            return {"jsonrpc": "2.0", "id": msg_id,
                    "error": {"code": -32602, "message": f"unknown tool: {name}"}}
        _log({"dir": "call", "tool": name, "arguments": params.get("arguments"),
              "isError": result.get("isError", False)})
    else:
        return {"jsonrpc": "2.0", "id": msg_id,
                "error": {"code": -32601, "message": f"method not found: {method}"}}

    _log({"dir": "in", "method": method, "id": msg_id})
    return {"jsonrpc": "2.0", "id": msg_id, "result": result}


class McpHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args) -> None:  # 关闭默认 stderr 访问日志，走 JSONL
        pass

    def _respond(self, code: int, body: bytes = b"", ctype: str = "application/json") -> None:
        self.send_response(code)
        if body:
            self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self) -> None:
        # 无服务端主动消息面：按 streamableHttp 规范回 405，SDK 降级为无 SSE 监听
        if self.path.rstrip("/").endswith("/health"):
            self._respond(200, b'{"status":"ok"}')
            return
        self._respond(405)

    def do_DELETE(self) -> None:
        self._respond(204)

    def do_POST(self) -> None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length) if length > 0 else b"{}"
            data = json.loads(raw or b"{}")
        except (ValueError, json.JSONDecodeError):
            self._respond(400, b'{"error":"invalid json"}')
            return

        msgs = data if isinstance(data, list) else [data]
        responses = [r for r in (_handle_message(m) for m in msgs) if r is not None]
        if not responses:
            self._respond(202)  # 纯通知
            return
        body = responses if isinstance(data, list) else responses[0]
        self._respond(200, json.dumps(body, ensure_ascii=False).encode("utf-8"))


def main() -> int:
    global _CATALOG, _LOG_PATH
    p = argparse.ArgumentParser(description="评测 mock MCP 服务")
    p.add_argument("--catalog", required=True, help="工具目录 JSON 文件")
    p.add_argument("--port", type=int, default=18082)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--log", default=None, help="请求日志 JSONL 路径")
    args = p.parse_args()

    try:
        with open(args.catalog, encoding="utf-8") as f:
            _CATALOG = json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        print(f"[mock-mcp] 工具目录加载失败: {e}")
        return 1
    _LOG_PATH = args.log

    server = ThreadingHTTPServer((args.host, args.port), McpHandler)
    names = ", ".join(t["name"] for t in _CATALOG["tools"])
    print(f"[mock-mcp] listening {args.host}:{args.port} tools=[{names}]", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
