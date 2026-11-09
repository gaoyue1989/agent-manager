# -*- coding: utf-8 -*-
"""会话级工具列表 E2E Mock MCP Server（streamableHttp，Python 标准库，零依赖）。

提供两个 server 实例（tools-a / tools-b，端口区分），各注册带标志名的小工具：
  tools-a: query(params 可回显)        —— e2e 白名单主工具
  tools-b: search(keyword 可回显)      —— 名单外对照工具
额外端点：
  GET /health — 存活探测
  GET /stats  — 各工具真实执行次数（acting 兜底 DENY 的强证据：白名单外调用
                即使被模型幻觉发起，mock 侧执行计数也必须为 0）

仅实现 initialize / notifications/initialized / ping / tools/list / tools/call
（与 example/approval-forms/mock-mcp 同款骨架）。状态存内存（重启即清空）。

用法：python3 mock-mcp-server.py <port> <serverName> <toolName>
"""
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8814
SERVER_NAME = sys.argv[2] if len(sys.argv) > 2 else "tools-a"
TOOL_NAME = sys.argv[3] if len(sys.argv) > 3 else "query"

LOCK = threading.Lock()
CALL_COUNTS = {}


def tool_schema():
    param = "keyword" if TOOL_NAME == "search" else "params"
    return {
        "name": TOOL_NAME,
        "description": "E2E mock tool %s/%s (session-tool-list-design)" % (SERVER_NAME, TOOL_NAME),
        "inputSchema": {
            "type": "object",
            "properties": {param: {"type": "string", "description": "echo back marker"}},
        },
    }


def call_tool(params):
    with LOCK:
        CALL_COUNTS[TOOL_NAME] = CALL_COUNTS.get(TOOL_NAME, 0) + 1
    text = "%s@%s executed with %s" % (TOOL_NAME, SERVER_NAME, json.dumps(params, ensure_ascii=False))
    return {"content": [{"type": "text", "text": text}], "isError": False}


class Handler(BaseHTTPRequestHandler):

    def _send(self, obj, status=200, headers=None):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send({"ok": True, "server": SERVER_NAME})
            return
        if self.path == "/stats":
            with LOCK:
                self._send({"server": SERVER_NAME, "calls": dict(CALL_COUNTS)})
            return
        # SDK streamableHttp 传输初始化时发 GET 探测，返回 SSE 空帧避免 501
        body = b'data: {"jsonrpc":"2.0","result":{},"id":null}\n\n'
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        try:
            msg = json.loads(raw)
        except (ValueError, TypeError):
            self._send({"jsonrpc": "2.0", "error": {"code": -32700, "message": "Parse error"}})
            return
        method = msg.get("method")
        mid = msg.get("id")
        params = msg.get("params") or {}

        if method == "initialize":
            self._send({"jsonrpc": "2.0", "id": mid, "result": {
                "protocolVersion": "2025-03-26",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": SERVER_NAME, "version": "0.1.0"},
            }})
        elif method == "notifications/initialized":
            self._send({})
        elif method == "ping":
            self._send({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "tools/list":
            self._send({"jsonrpc": "2.0", "id": mid, "result": {"tools": [tool_schema()]}})
        elif method == "tools/call":
            name = str((params or {}).get("name") or "")
            args = (params or {}).get("arguments") or {}
            if name != TOOL_NAME:
                self._send({"jsonrpc": "2.0", "id": mid,
                            "error": {"code": -32602, "message": "Unknown tool: %s" % name}})
                return
            self._send({"jsonrpc": "2.0", "id": mid, "result": call_tool(args)})
        else:
            self._send({"jsonrpc": "2.0", "error": {"code": -32601, "message": "Method not found"}})

    def log_message(self, fmt, *args):  # 静默默认访问日志
        pass


if __name__ == "__main__":
    print("mock MCP %s listening on 0.0.0.0:%d (tool=%s)" % (SERVER_NAME, PORT, TOOL_NAME), flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
