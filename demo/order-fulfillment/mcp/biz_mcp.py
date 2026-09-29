# -*- coding: utf-8 -*-
"""order-fulfillment demo Mock MCP（streamableHttp，Python 标准库，零依赖）。

订单履约业务面 mock（对齐 AgentScope 官方 order-fulfillment 案例的业务工具语义，
设计见 demo/order-fulfillment/README.md 与 docs/design/travel-fulfillment-agent-protocol-design.md §0/§3）：

只读（诊断阶段）：
- get_order(order_id)：订单状态 / version / 来源
- get_inventory(sku)：仓级可用量与调拨约束

写（执行阶段，L3 硬约束——服务端校验，不信任模型自述）：
- create_resolution(order_id, action, expected_version, plan_id, reason)
  * expected_version 不等于当前版本 → VERSION_CONFLICT 拒绝（验收断言 4）
  * plan_id 重放 → IDEMPOTENT_REJECT 拒绝（验收断言 5）
  * 通过 → 落处理单 RES-xxx，订单 version+1、状态翻转

仅实现 initialize / ping / notifications/initialized / tools/list / tools/call。
状态存内存（重启即清空）。
"""
import json
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8300
HOST = "0.0.0.0"

STATE_LOCK = threading.Lock()
ORDERS = {
    "O-1001": {
        "order_id": "O-1001", "status": "awaiting_shipment_delayed",
        "version": 3, "sku": "SKU-9H", "qty": 2,
        "promised_date": "2026-09-15", "source": "oms",
        "note": "客户反馈迟迟未发货",
    },
    "O-1002": {
        "order_id": "O-1002", "status": "awaiting_shipment_delayed",
        "version": 2, "sku": "SKU-7K", "qty": 1,
        "promised_date": "2026-09-20", "source": "oms",
        "note": "客户反馈迟迟未发货",
    },
    "O-1003": {
        "order_id": "O-1003", "status": "awaiting_shipment_delayed",
        "version": 5, "sku": "SKU-2F", "qty": 4,
        "promised_date": "2026-09-18", "source": "oms",
        "note": "客户反馈迟迟未发货",
    },
}
INVENTORY = {
    "SKU-9H": {
        "sku": "SKU-9H",
        "warehouses": {"east": 12, "south": 0},
        "transfer_note": "south 仓无货，需从 east 仓调拨；调拨须持已批准处置方案（plan_id）",
    },
    "SKU-7K": {
        "sku": "SKU-7K",
        "warehouses": {"east": 5, "south": 3},
        "transfer_note": "两仓均有货，south 仓优先发货",
    },
    "SKU-2F": {
        "sku": "SKU-2F",
        "warehouses": {"east": 0, "south": 8},
        "transfer_note": "east 仓无货，需从 south 仓调拨；调拨须持已批准处置方案（plan_id）",
    },
}
RESOLUTIONS = {}   # resolution_id -> dict
USED_PLAN_IDS = set()  # L3 幂等：plan_id 只允许消费一次
STATS = {"create_resolution_ok": 0, "create_resolution_version_conflict": 0,
         "create_resolution_idempotent_reject": 0, "get_order": 0, "get_inventory": 0}


def tool_get_order(args):
    order_id = args.get("order_id") or args.get("orderId") or ""
    with STATE_LOCK:
        STATS["get_order"] += 1
        o = ORDERS.get(order_id)
    if not o:
        return {"isError": True, "content": [{"type": "text", "text": f"order not found: {order_id}"}]}
    return {"content": [{"type": "text", "text": json.dumps(o, ensure_ascii=False)}]}


def tool_get_inventory(args):
    sku = args.get("sku") or ""
    with STATE_LOCK:
        STATS["get_inventory"] += 1
        inv = INVENTORY.get(sku)
    if not inv:
        return {"isError": True, "content": [{"type": "text", "text": f"sku not found: {sku}"}]}
    return {"content": [{"type": "text", "text": json.dumps(inv, ensure_ascii=False)}]}


def tool_create_resolution(args):
    order_id = args.get("order_id") or args.get("orderId") or ""
    action = args.get("action") or ""
    expected_version = args.get("expected_version")
    plan_id = args.get("plan_id") or ""
    reason = args.get("reason") or ""
    with STATE_LOCK:
        o = ORDERS.get(order_id)
        if not o:
            return {"isError": True, "content": [{"type": "text", "text": f"order not found: {order_id}"}]}
        # L3 硬约束 1：乐观并发——expected_version 必须等于服务端当前版本
        try:
            ev = int(expected_version)
        except (TypeError, ValueError):
            ev = -1
        if ev != o["version"]:
            STATS["create_resolution_version_conflict"] += 1
            return {"isError": True, "content": [{"type": "text", "text": (
                f"VERSION_CONFLICT: expected_version={expected_version} but server version="
                f"{o['version']}（须以 get_order 查询到的最新 version 重试）")}]}
        # L3 硬约束 2：幂等——plan_id 只允许消费一次（重放拒绝）
        if plan_id in USED_PLAN_IDS:
            STATS["create_resolution_idempotent_reject"] += 1
            return {"isError": True, "content": [{"type": "text", "text": (
                f"IDEMPOTENT_REJECT: plan_id={plan_id} 已被消费（重放拒绝）")}]}
        if not plan_id:
            return {"isError": True, "content": [{"type": "text", "text": (
                "PLAN_ID_REQUIRED: plan_id 只能来自用户已批准的处置方案")}]}
        USED_PLAN_IDS.add(plan_id)
        rid = "RES-" + uuid.uuid4().hex[:8].upper()
        o["version"] += 1
        o["status"] = "reissue_in_progress" if action == "reissue" else f"{action}_in_progress"
        RESOLUTIONS[rid] = {"resolution_id": rid, "order_id": order_id, "action": action,
                            "plan_id": plan_id, "reason": reason,
                            "new_version": o["version"], "status": "accepted"}
        STATS["create_resolution_ok"] += 1
    return {"content": [{"type": "text", "text": json.dumps(
        {"resolution_id": rid, "order_id": order_id, "action": action,
         "new_version": o["version"], "status": "accepted"}, ensure_ascii=False)}]}


TOOLS = [
    {"name": "get_order", "description": "查询订单状态、version、来源（只读，诊断用）",
     "inputSchema": {"type": "object", "properties": {
         "order_id": {"type": "string", "description": "订单号，如 O-1001"}}, "required": ["order_id"]}},
    {"name": "get_inventory", "description": "查询 SKU 仓级可用量与调拨约束（只读，诊断用）",
     "inputSchema": {"type": "object", "properties": {
         "sku": {"type": "string"}}, "required": ["sku"]}},
    {"name": "create_resolution", "description": (
        "创建处理单（写操作）。服务端强制校验 expected_version（乐观并发）与 plan_id"
        "（幂等键，只能来自用户已批准的处置方案，重放拒绝）"),
     "inputSchema": {"type": "object", "properties": {
         "order_id": {"type": "string"}, "action": {"type": "string", "description": "如 reissue"},
         "expected_version": {"type": "integer"}, "plan_id": {"type": "string"},
         "reason": {"type": "string"}},
         "required": ["order_id", "action", "expected_version", "plan_id", "reason"]}},
]

TOOL_HANDLERS = {"get_order": tool_get_order, "get_inventory": tool_get_inventory,
                 "create_resolution": tool_create_resolution}


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"status": "ok"})
        elif self.path == "/stats":
            with STATE_LOCK:
                self._send(200, {"stats": STATS, "resolutions": list(RESOLUTIONS.values())})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/mcp":
            self._send(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            req = json.loads(self.rfile.read(length) or b"{}")
        except Exception as e:
            self._send(400, {"error": f"bad request: {e}"})
            return
        method = req.get("method", "")
        rid = req.get("id")
        if method == "initialize":
            self._send(200, {"jsonrpc": "2.0", "id": rid, "result": {
                "protocolVersion": "2025-03-26",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "biz-mcp", "version": "0.1.0"}}})
        elif method == "ping":
            self._send(200, {"jsonrpc": "2.0", "id": rid, "result": {}})
        elif method == "notifications/initialized":
            self._send(202, {})
        elif method == "tools/list":
            self._send(200, {"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}})
        elif method == "tools/call":
            params = req.get("params") or {}
            name = params.get("name", "")
            handler = TOOL_HANDLERS.get(name)
            if not handler:
                self._send(200, {"jsonrpc": "2.0", "id": rid, "result": {
                    "isError": True, "content": [{"type": "text", "text": f"unknown tool: {name}"}]}})
                return
            try:
                result = handler(params.get("arguments") or {})
            except Exception as e:  # 工具异常不炸服务
                result = {"isError": True, "content": [{"type": "text", "text": f"internal error: {e}"}]}
            self._send(200, {"jsonrpc": "2.0", "id": rid, "result": result})
        else:
            self._send(200, {"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": f"method not found: {method}"}})

    def log_message(self, fmt, *args):  # 静默访问日志
        pass


if __name__ == "__main__":
    print(f"biz-mcp listening on {HOST}:{PORT}", flush=True)
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
