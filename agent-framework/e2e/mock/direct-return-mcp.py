# -*- coding: utf-8 -*-
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("DIRECT_MCP_PORT", "8814"))
HOST = "127.0.0.1"
SENTINEL = "DR-SENTINEL-7f3a9c"

ORDERS = {
    "o-1": {"items": [("机械键盘", 1, 299.0), ("USB-C 数据线", 2, 39.0), ("显示器支架", 1, 71.0)]},
    "o-2": {"items": [("降噪耳机", 1, 899.0)]},
}

CALL_LOG = []
CALL_LOG_LOCK = threading.Lock()


def order_id_of(params):
    order_id = str(params.get("order_id") or "").strip()
    return order_id or "o-1"


def order_total(order_id):
    return round(sum(price * qty for _, qty, price in ORDERS.get(order_id, ORDERS["o-1"])["items"]), 2)


def card_payload(order_id):
    return {
        "type": "confirm_card",
        "title": "确认订单",
        "order_id": order_id,
        "items": [
            {"name": name, "qty": qty, "price": price}
            for name, qty, price in ORDERS.get(order_id, ORDERS["o-1"])["items"]
        ],
        "total": order_total(order_id),
        "note": "第一行\n第二行  含双空格\t结尾是制表符",
        "sentinel": SENTINEL,
    }


def text_result(text, is_error=False):
    return {"content": [{"type": "text", "text": text}], "isError": is_error}


# 1x1 PNG（纯图片直返 DR9：结果不含任何 text 块，SDK 不发直返文本信号）
PNG_1X1_BASE64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
)


def render_chart_png(params):
    return {"content": [{"type": "image", "data": PNG_1X1_BASE64, "mimeType": "image/png"}], "isError": False}


def render_confirm_card(params):
    return text_result(json.dumps(card_payload(order_id_of(params)), ensure_ascii=False, indent=2))


def submit_order(params):
    order_id = order_id_of(params)
    receipt = {"type": "order_receipt", "order_id": order_id, "status": "submitted", "total": order_total(order_id)}
    return text_result(json.dumps(receipt, ensure_ascii=False, indent=2))


def broken_render(params):
    return text_result("渲染服务暂不可用，请稍后重试", True)


def empty_render(params):
    return text_result("")


def lookup_order(params):
    return text_result("订单 %s：已发货，预计明天送达。" % order_id_of(params))


def notify_warehouse(params):
    return text_result("已通知仓库核对订单 %s 的库存。" % order_id_of(params))


TOOLS = [
    {
        "name": "render_confirm_card",
        "description": "把订单确认卡片展示给用户。调用后卡片内容会原样显示在用户界面上，本轮对话到此结束。参数 order_id。",
        "inputSchema": {"type": "object", "properties": {"order_id": {"type": "string"}}, "required": ["order_id"]},
    },
    {
        "name": "submit_order",
        "description": "提交订单并把订单回执展示给用户。执行前需要用户授权。参数 order_id。",
        "inputSchema": {"type": "object", "properties": {"order_id": {"type": "string"}}, "required": ["order_id"]},
    },
    {
        "name": "broken_render",
        "description": "展示一张确认卡片（演示渲染服务故障）。参数 order_id。",
        "inputSchema": {"type": "object", "properties": {"order_id": {"type": "string"}}, "required": ["order_id"]},
    },
    {
        "name": "empty_render",
        "description": "展示一张内容为空的确认卡片。参数 order_id。",
        "inputSchema": {"type": "object", "properties": {"order_id": {"type": "string"}}, "required": ["order_id"]},
    },
    {
        "name": "lookup_order",
        "description": "查询订单物流状态。参数 order_id。",
        "inputSchema": {"type": "object", "properties": {"order_id": {"type": "string"}}, "required": ["order_id"]},
    },
    {
        "name": "notify_warehouse",
        "description": "通知仓库核对订单库存。参数 order_id。",
        "inputSchema": {"type": "object", "properties": {"order_id": {"type": "string"}}, "required": ["order_id"]},
    },
    {
        "name": "render_chart_png",
        "description": "渲染订单金额占比图并直接展示给用户。调用后图片会原样显示在用户界面上，本轮对话到此结束。参数 order_id。",
        "inputSchema": {"type": "object", "properties": {"order_id": {"type": "string"}}, "required": ["order_id"]},
    },
]

HANDLERS = {
    "render_confirm_card": render_confirm_card,
    "submit_order": submit_order,
    "broken_render": broken_render,
    "empty_render": empty_render,
    "lookup_order": lookup_order,
    "notify_warehouse": notify_warehouse,
    "render_chart_png": render_chart_png,
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _send(self, data, status=200):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path, _, query = self.path.partition("?")
        if path == "/expected":
            params = dict(part.split("=", 1) for part in query.split("&") if "=" in part)
            tool = params.get("tool", "render_confirm_card")
            self._send({"text": HANDLERS[tool](params)["content"][0]["text"]})
            return
        if path == "/calls":
            with CALL_LOG_LOCK:
                self._send({"calls": list(CALL_LOG)})
            return
        if path == "/reset":
            with CALL_LOG_LOCK:
                CALL_LOG.clear()
            self._send({"status": "reset"})
            return
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
                "serverInfo": {"name": "direct-return-mcp", "version": "0.1.0"},
            }})
        elif method == "notifications/initialized":
            self._send({})
        elif method == "ping":
            self._send({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "tools/list":
            self._send({"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS}})
        elif method == "tools/call":
            name = params.get("name", "")
            args = params.get("arguments") or {}
            with CALL_LOG_LOCK:
                CALL_LOG.append({"name": name, "arguments": args})
            sys.stderr.write("[trace] tools/call name=%s args=%s\n" % (name, json.dumps(args, ensure_ascii=False)))
            sys.stderr.flush()
            fn = HANDLERS.get(name)
            if fn is None:
                self._send({"jsonrpc": "2.0", "id": mid, "error": {
                    "code": -32602, "message": "Unknown tool: " + name}})
            else:
                try:
                    self._send({"jsonrpc": "2.0", "id": mid, "result": fn(args)})
                except Exception as e:
                    self._send({"jsonrpc": "2.0", "id": mid, "error": {
                        "code": -32000, "message": "Tool execution failed: %s" % e}})
        else:
            self._send({"jsonrpc": "2.0", "id": mid, "error": {
                "code": -32601, "message": "Method not found: " + str(method)}})


def main():
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print("direct-return mock-mcp listening on http://%s:%d" % (HOST, PORT), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
