#!/usr/bin/env python3
"""
YG Demo Tools MCP SSE Server

符合 MCP SSE 传输协议规范：
- GET /sse → 建立 SSE 连接，发送 endpoint 事件
- POST /message → 接收 JSON-RPC 请求

工具列表:
- address_search: 地址查询 (GET)
- cost_estimate: 报价单查询 (POST)
- save_order: 保存预订单 (POST)
- order_create: 客服确认订单 (POST)
"""

import json
import queue
import threading
import uuid
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse, parse_qs

import httpx


class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


_sessions: dict[str, queue.Queue] = {}
_sessions_lock = threading.Lock()


class ToolConfig:
    def __init__(self, name: str, description: str, http_url: str, http_method: str, input_schema: dict):
        self.name = name
        self.description = description
        self.http_url = http_url
        self.http_method = http_method
        self.input_schema = input_schema


YG_DEMO_TOOLS = {
    "address_search": ToolConfig(
        name="address_search",
        description="地址查询工具 - 根据关键词搜索地址，返回匹配的地址列表",
        http_url="https://m1.apifoxmock.com/m2/8091045-7847473-default/441764982",
        http_method="GET",
        input_schema={
            "type": "object",
            "properties": {
                "keyword": {"type": "string", "description": "地址搜索关键词"}
            },
            "required": ["keyword"]
        }
    ),
    "cost_estimate": ToolConfig(
        name="cost_estimate",
        description="报价单查询工具 - 根据出发地、目的地坐标查询可用车型及报价",
        http_url="https://m1.apifoxmock.com/m2/8091045-7847473-default/441776112",
        http_method="POST",
        input_schema={
            "type": "object",
            "properties": {
                "fromLat": {"type": "number", "description": "出发地纬度"},
                "fromLng": {"type": "number", "description": "出发地经度"},
                "toLat": {"type": "number", "description": "目的地纬度"},
                "toLng": {"type": "number", "description": "目的地经度"},
                "carType": {"type": "array", "items": {"type": "string"}, "description": "车型列表"},
                "orderType": {"type": "string", "description": "订单类型"}
            },
            "required": ["fromLat", "fromLng", "toLat", "toLng"]
        }
    ),
    "save_order": ToolConfig(
        name="save_order",
        description="保存预订单工具 - 保存打车预订单信息",
        http_url="https://m1.apifoxmock.com/m2/8091045-7847473-default/441921276",
        http_method="POST",
        input_schema={
            "type": "object",
            "properties": {
                "fromAddress": {"type": "string", "description": "出发地地址"},
                "toAddress": {"type": "string", "description": "目的地地址"},
                "fromLat": {"type": "number", "description": "出发地纬度"},
                "fromLng": {"type": "number", "description": "出发地经度"},
                "toLat": {"type": "number", "description": "目的地纬度"},
                "toLng": {"type": "number", "description": "目的地经度"},
                "carType": {"type": "string", "description": "车型代码"},
                "minPrice": {"type": "number", "description": "最低价格"},
                "maxPrice": {"type": "number", "description": "最高价格"},
                "priceKey": {"type": "string", "description": "报价Key"},
                "customerName": {"type": "string", "description": "乘车人姓名"},
                "customerPhone": {"type": "string", "description": "乘车人手机号"},
                "cardNum": {"type": "string", "description": "支付卡号"},
                "orderType": {"type": "string", "description": "订单类型"}
            },
            "required": ["fromAddress", "toAddress", "customerName", "customerPhone"]
        }
    ),
    "order_create": ToolConfig(
        name="order_create",
        description="客服确认订单工具 - 将预订单发送给客户确认",
        http_url="https://m1.apifoxmock.com/m2/8091045-7847473-default/441816936",
        http_method="POST",
        input_schema={
            "type": "object",
            "properties": {
                "bzType": {"type": "string", "description": "业务类型"},
                "btrOrderid": {"type": "string", "description": "预订单号"}
            },
            "required": ["btrOrderid"]
        }
    )
}

http_client = httpx.Client(timeout=30.0)


def handle_initialize(params: dict) -> dict:
    return {
        "protocolVersion": "2024-11-05",
        "capabilities": {"tools": {}},
        "serverInfo": {"name": "yg_demo_tools", "version": "1.0.0"}
    }


def handle_tools_list(params: dict) -> dict:
    tools = []
    for name, config in YG_DEMO_TOOLS.items():
        tools.append({
            "name": config.name,
            "description": config.description,
            "inputSchema": config.input_schema
        })
    return {"tools": tools}


def handle_tools_call(params: dict) -> dict:
    tool_name = params.get("name")
    arguments = params.get("arguments", {})
    
    if tool_name not in YG_DEMO_TOOLS:
        raise ValueError(f"Unknown tool: {tool_name}")
    
    config = YG_DEMO_TOOLS[tool_name]
    
    try:
        if config.http_method == "GET":
            response = http_client.get(config.http_url, params=arguments)
        else:
            response = http_client.post(config.http_url, json=arguments)
        
        response.raise_for_status()
        data = response.json()
        
        return {
            "content": [{"type": "text", "text": json.dumps(data, ensure_ascii=False)}]
        }
    except httpx.HTTPError as e:
        return {
            "content": [{"type": "text", "text": f"Error: {e}"}],
            "isError": True
        }


def handle_ping(params: dict) -> dict:
    return {}


def handle_jsonrpc(request: dict) -> dict | None:
    method = request.get("method")
    params = request.get("params", {})
    req_id = request.get("id")
    
    if method == "notifications/initialized":
        return None
    
    handlers = {
        "initialize": handle_initialize,
        "tools/list": handle_tools_list,
        "tools/call": handle_tools_call,
        "ping": handle_ping,
    }
    
    handler = handlers.get(method)
    if not handler:
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "error": {"code": -32601, "message": f"Method not found: {method}"}
        }
    
    try:
        result = handler(params)
        return {"jsonrpc": "2.0", "id": req_id, "result": result}
    except Exception as e:
        return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32603, "message": str(e)}}


class SSEHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass
    
    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == '/sse':
            self._handle_sse()
        elif parsed.path == '/health':
            self._handle_health()
        else:
            self.send_error(404)
    
    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path == '/message':
            self._handle_message()
        else:
            self.send_error(404)
    
    def _handle_health(self):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps({"status": "ok", "tools": list(YG_DEMO_TOOLS.keys())}).encode())
    
    def _handle_sse(self):
        session_id = self.headers.get('X-Session-Id') or str(uuid.uuid4())
        
        q: queue.Queue = queue.Queue()
        with _sessions_lock:
            _sessions[session_id] = q
        
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'keep-alive')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        
        endpoint_url = f"/message?sessionId={session_id}"
        self.wfile.write(f'event: endpoint\ndata: {endpoint_url}\n\n'.encode())
        self.wfile.flush()
        
        try:
            while True:
                try:
                    data = q.get(timeout=30)
                    self.wfile.write(f'event: message\ndata: {json.dumps(data)}\n\n'.encode())
                    self.wfile.flush()
                except queue.Empty:
                    self.wfile.write(b': keepalive\n\n')
                    self.wfile.flush()
        except (BrokenPipeError, OSError):
            pass
        finally:
            with _sessions_lock:
                _sessions.pop(session_id, None)
    
    def _handle_message(self):
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length)
        message = json.loads(body)
        
        parsed = urlparse(self.path)
        qs = {}
        if parsed.query:
            qs = {k: v[0] for k, v in parse_qs(parsed.query).items()}
        session_id = qs.get('sessionId', '')
        
        response = handle_jsonrpc(message)
        
        if response is None:
            self.send_response(204)
            self.end_headers()
            return
        
        with _sessions_lock:
            q = _sessions.get(session_id)
        
        if q:
            q.put(response)
            self.send_response(202)
            self.end_headers()
        else:
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(response).encode())


def main():
    import sys
    port = 8820
    if len(sys.argv) > 1:
        for i, arg in enumerate(sys.argv):
            if arg == '--port' and i + 1 < len(sys.argv):
                port = int(sys.argv[i + 1])
    
    print(f"Starting YG Demo Tools MCP SSE Server on 0.0.0.0:{port}")
    print(f"Tools: {list(YG_DEMO_TOOLS.keys())}")
    
    server = ThreadedHTTPServer(('0.0.0.0', port), SSEHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        http_client.close()
        server.shutdown()


if __name__ == "__main__":
    main()
