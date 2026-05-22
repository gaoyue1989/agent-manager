#!/usr/bin/env python3
"""
xty (小天元空间) MCP SSE Server — 智能差旅全套工具服务

从 ai-mgr.cenxt.cn xty 命名空间获取工具和技能配置。
符合 MCP SSE 传输协议规范 (JSON-RPC 2.0)。

工具总数: 32, 覆盖: 出差申请、机票、酒店、报销、用户、支付
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


TOOLS = {}

def register(name, desc, url, method, input_schema=None):
    TOOLS[name] = {"name": name, "description": desc, "http_url": url, "http_method": method, "input_schema": input_schema or {}}

# ============================================================
# 出差申请
# ============================================================
register("biz-travel-create-application", "创建申请单",
         "http://47.107.143.202/biz-travel-supplier/chat/trip/apply", "POST",
         {"type":"object","properties":{"budget":{"type":"number"},"reason":{"type":"string"},"applyer":{"type":"string"},"endDate":{"type":"string"},"remarks":{"type":"string"},"departure":{"type":"string"},"startDate":{"type":"string"},"destination":{"type":"string"}},"required":["departure","destination","startDate","endDate","reason"]})

register("create_business_trip_application", "创建申请单(v2)",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/v2/business-trip/application/create", "POST")

register("biz-travel-query-application-detail", "查询出差申请单列表",
         "http://47.107.143.202/biz-travel-supplier/chat/apply/detail", "POST")

register("query_business_trip_application_progress", "查询申请单进度",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/business-trip/application/progress", "GET")

register("update_business_trip_application", "更新申请单状态",
         "https://tool.cenxt.cn/business-trip/application/info", "POST")

register("preview_business_trip_application", "预校验出差申请单",
         "https://cs.creditcard.ecitic.com/citiccard/cshop-api-gateway/crypto/token", "GET")

# ============================================================
# 员工/用户信息
# ============================================================
register("biz-travel-query-employee-info", "查询当前员工信息",
         "http://47.107.143.202/biz-travel-supplier/chat/employee/info", "POST")

register("query_user_info", "查询用户信息",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/xty/user/info", "GET")

register("biz-travel-query-account-info", "查询报销卡号信息",
         "http://47.107.143.202/biz-travel-supplier/chat/employee/bank", "POST")

register("biz-travel-select-passenger", "选择乘机人",
         "http://47.107.143.202/biz-travel-supplier/chat/employee/frequent-traveler", "POST")

register("biz-travel-select-room-guest", "选择入住人",
         "http://47.107.143.202/biz-travel-supplier/chat/employee/frequent-traveler", "POST")

# ============================================================
# 差旅标准
# ============================================================
register("biz-travel-query-travel-standard", "差旅标准查询",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/chat/travel/standard", "POST")

# ============================================================
# 机票
# ============================================================
register("biz-travel-query-flight-list", "机票信息查询。根据出发城市、到达城市和出发日期查询航班列表。",
         "http://47.107.143.202/biz-travel-supplier/api/v1/flight/search", "POST",
         {"type":"object","properties":{"departureCityName":{"type":"string","description":"出发城市名称，如：北京、深圳"},"arrivalCityName":{"type":"string","description":"到达城市名称，如：深圳、北京"},"departureDate":{"type":"string","description":"出发日期，格式：YYYY-MM-DD"}},"required":["departureCityName","arrivalCityName","departureDate"]})

register("query_business_trip_flight_list", "航班信息查询",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/business-trip/flight/list", "GET")

register("biz-travel-query-carbin-list", "航班舱位查询。根据航班号和出发日期查询可用舱位。",
         "http://47.107.143.202/biz-travel-supplier/api/v1/flight/carbin", "POST",
         {"type":"object","properties":{"flightNo":{"type":"string","description":"航班号，如：9C8792"},"departureDate":{"type":"string","description":"出发日期，格式：YYYY-MM-DD"}},"required":["flightNo","departureDate"]})

register("query_business_trip_cabin_list", "舱位信息查询",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/business-trip/cabin/list", "GET")

register("biz-travel-create-flight-order", "机票预定。创建机票订单，需要提供航班信息、乘机人信息等。",
         "http://47.107.143.202/biz-travel-supplier/api/v1/flight/create-order", "POST",
         {"type":"object","properties":{"departureCityName":{"type":"string","description":"出发城市名称"},"arrivalCityName":{"type":"string","description":"到达城市名称"},"departureDate":{"type":"string","description":"出发日期，格式：YYYY-MM-DD"},"flightNo":{"type":"string","description":"航班号"},"cabinPriceId":{"type":"string","description":"舱位价格ID"},"companyId":{"type":"string","description":"公司ID"},"employeeId":{"type":"string","description":"员工ID"},"tourists":{"type":"array","description":"乘机人列表"},"contactTourist":{"type":"string","description":"联系人"},"applyNo":{"type":"string","description":"出差申请单号"}},"required":["departureCityName","arrivalCityName","departureDate","flightNo"]})

register("create_business_trip_flight_order", "机票预定(v2)",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/v2/business-trip/flight/order", "POST")

register("query_business_trip_flight_order", "机票订单查询",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/business-trip/flight/order", "GET")

register("biz-travel-flight-order-detail", "查询机票预订单详情",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/biz-travel/flight-order/detail", "GET")

register("biz-travel-pay-flight-order", "支付机票订单",
         "http://47.107.143.202/biz-travel-supplier/api/v1/flight/pay", "POST",
         {"type":"object","properties":{"orderId":{"type":"string","description":"订单ID"},"payMethod":{"type":"string","description":"支付方式"}},"required":["orderId"]})

# ============================================================
# 酒店
# ============================================================
register("biz-travel-query-hotel-list", "酒店查询。根据城市、入住日期和退房日期查询酒店列表。",
         "http://47.107.143.202/biz-travel-supplier/booking/hotel/search", "POST",
         {"type":"object","properties":{"cityName":{"type":"string","description":"城市名称，如：深圳、北京"},"checkInDate":{"type":"string","description":"入住日期，格式：YYYY-MM-DD"},"checkOutDate":{"type":"string","description":"退房日期，格式：YYYY-MM-DD"}},"required":["cityName","checkInDate","checkOutDate"]})

register("query_business_trip_hotel_list", "酒店信息查询",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/business-trip/hotel/list", "GET")

register("biz-travel-query-hotel-detail", "酒店详情。根据酒店ID查询详细信息。",
         "http://47.107.143.202/biz-travel-supplier/booking/hotel/detail", "POST",
         {"type":"object","properties":{"hotelId":{"type":"string","description":"酒店ID"}},"required":["hotelId"]})

register("biz-travel-create-hotel-order", "酒店预订。创建酒店订单。",
         "http://47.107.143.202/biz-travel-supplier/booking/hotel/order", "POST",
         {"type":"object","properties":{"hotelId":{"type":"string","description":"酒店ID"},"checkInDate":{"type":"string","description":"入住日期，格式：YYYY-MM-DD"},"checkOutDate":{"type":"string","description":"退房日期，格式：YYYY-MM-DD"},"guestName":{"type":"string","description":"入住人姓名"},"roomId":{"type":"string","description":"房间ID"}},"required":["hotelId","checkInDate","checkOutDate"]})

register("create_business_trip_hotel_order", "酒店预定(v2)",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/v2/business-trip/hotel/order", "POST")

register("query_business_trip_hotel_order", "酒店订单查询",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/business-trip/hotel/order", "GET")

register("biz-travel-hotel-order-detail", "查询酒店预订单详情",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/biz-travel/hotel-order/detail", "GET")

register("biz-travel-pay-hotel-order", "支付酒店订单",
         "http://47.107.143.202/biz-travel-supplier/booking/hotel/pay", "POST")

# ============================================================
# 报销
# ============================================================
register("biz-travel-submit-expense", "提交报销单",
         "http://47.107.143.202/biz-travel-supplier/chat/expense/submit", "POST")

register("create_business_trip_reimbursement", "报销单提交",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/v2/business-trip/reimbursement", "POST")

register("biz-travel-query-reimbursement-list", "查询报销申请单列表",
         "http://47.107.143.202/biz-travel-supplier/chat/expense/query", "POST")

register("query_business_trip_reimbursement_process", "报销单进度查询",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/business-trip/reimbursement/process", "GET")

register("biz-travel-query-expense-list", "查询出差费用列表",
         "http://47.107.143.202/biz-travel-supplier/chat/fee/summary", "POST")

register("biz-travel-preview-reimbursement", "预校验出差费用报销",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/biz-travel-preview-reimbursement", "POST")

# ============================================================
# 发票/流水/支付
# ============================================================
register("biz-travel-query-invoice-list", "查询发票列表",
         "http://47.107.143.202/biz-travel-supplier/api/invoice/list", "POST")

register("biz-travel-query-bill-list", "查询账单流水",
         "http://47.107.143.202/biz-travel-supplier/api/transaction/list", "POST")

register("biz-travel-invoice-transaction", "关联发票和交易凭证",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/biz-travel/invoice-transaction", "GET")

register("biz-travel-order-pay", "订单支付",
         "https://m1.apifoxmock.com/m1/8065247-7820759-default/biz-travel/order/pay", "POST")


http_client = httpx.Client(timeout=30.0)


def handle_initialize(params: dict) -> dict:
    return {
        "protocolVersion": "2024-11-05",
        "capabilities": {"tools": {}},
        "serverInfo": {"name": "xty_demo_tools", "version": "1.0.0"}
    }


def handle_tools_list(params: dict) -> dict:
    tools = []
    for name, cfg in TOOLS.items():
        tools.append({
            "name": cfg["name"],
            "description": cfg["description"],
            "inputSchema": cfg.get("input_schema") or {"type": "object", "properties": {}}
        })
    return {"tools": tools}


def handle_tools_call(params: dict) -> dict:
    tool_name = params.get("name")
    arguments = params.get("arguments", {})

    if tool_name not in TOOLS:
        return {
            "content": [{"type": "text", "text": json.dumps({"error": f"Unknown tool: {tool_name}"})}],
            "isError": True
        }

    cfg = TOOLS[tool_name]

    try:
        if cfg["http_method"] == "GET":
            response = http_client.get(cfg["http_url"], params=arguments)
        else:
            response = http_client.post(cfg["http_url"], json=arguments)

        response.raise_for_status()
        try:
            data = response.json()
        except Exception:
            data = {"raw_response": response.text[:2000]}
        return {
            "content": [{"type": "text", "text": json.dumps(data, ensure_ascii=False)}]
        }
    except httpx.HTTPError as e:
        return {
            "content": [{"type": "text", "text": json.dumps({"error": str(e), "tool": tool_name, "url": cfg["http_url"]}, ensure_ascii=False)}],
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
            "jsonrpc": "2.0", "id": req_id,
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
        self.wfile.write(json.dumps({"status": "ok", "tools": len(TOOLS), "server": "xty_demo_tools"}).encode())

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
    port = 8821
    if len(sys.argv) > 1:
        for i, arg in enumerate(sys.argv):
            if arg == '--port' and i + 1 < len(sys.argv):
                port = int(sys.argv[i + 1])

    print(f"Starting XTY (小天元空间) MCP SSE Server on 0.0.0.0:{port}")
    print(f"Tools: {list(TOOLS.keys())}")

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
