# -*- coding: utf-8 -*-
"""审批 Demo 开发服务器：静态服务 + API 反向代理（Python 标准库，零依赖）。

- 页面资源：agent-framework/example/approval-forms/ui/ 目录静态文件
- API 请求：/debug/*、/threads/*、/mcp/*、/.well-known/*、/ 等反向代理到 agent-framework :8100
- SSE 长连接（/debug/threads/{sid}/events 与 /threads/{sid}/confirm-stream）逐块即时转发，不缓冲

同源部署 ⇒ 页面与 iframe 卡片均无需 CORS；agent-framework 无需任何改动。
用法：python3 proxy.py [--port 8913] [--backend http://127.0.0.1:8100]
"""
import argparse
import http.client
import mimetypes
import os
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
UI_DIR = os.path.join(HERE, "ui")
DEFAULT_PORT = 8913
DEFAULT_BACKEND = "127.0.0.1:8100"

# 透传响应头白名单（排除 hop-by-hop 头与后端内部信息）
PASSTHROUGH_HEADERS = (
    "content-type", "content-length", "location", "date", "cache-control",
    "etag", "last-modified", "expires", "pragma", "content-language",
    "content-encoding", "www-authenticate", "retry-after",
)


def serve_static(handler, rel_path):
    """尝试从 ui/ 目录返回静态文件；找不到返回 None（进行代理）。"""
    if rel_path in ("", "/"):
        rel_path = "index.html"
    if rel_path.startswith("/"):
        rel_path = rel_path[1:]
    # 路径规范化并防目录穿越
    normalized = os.path.normpath(rel_path)
    if normalized.startswith("..") or os.path.isabs(normalized):
        return False
    full = os.path.join(UI_DIR, normalized)
    if not os.path.isfile(full):
        return False
    ctype, _ = mimetypes.guess_type(full)
    if ctype is None:
        ctype = "application/octet-stream"
    if ctype.startswith("text/") or ctype in ("application/javascript", "application/json"):
        ctype += "; charset=utf-8"
    with open(full, "rb") as f:
        body = f.read()
    handler.send_response(200)
    handler.send_header("Content-Type", ctype)
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)
    return True


def proxy_request(handler, backend_host, backend_port):
    """把请求转发到 agent-framework，流式返回（SSE 关键）。"""
    length = int(handler.headers.get("Content-Length", 0) or 0)
    body = handler.rfile.read(length) if length > 0 else None
    conn = http.client.HTTPConnection(backend_host, backend_port, timeout=300)
    headers = {}
    for key, value in handler.headers.items():
        k = key.lower()
        if k in ("host", "connection", "accept-encoding", "transfer-encoding",
                 "content-length", "upgrade"):
            continue
        headers[key] = value
    try:
        conn.request(handler.command, handler.path, body=body, headers=headers)
        resp = conn.getresponse()
    except Exception as e:
        try:
            handler.send_response(502)
            handler.send_header("Content-Type", "application/json; charset=utf-8")
            body_resp = ("{\"error\": \"backend unreachable: %s\"}" % e).encode("utf-8")
            handler.send_header("Content-Length", str(len(body_resp)))
            handler.end_headers()
            handler.wfile.write(body_resp)
        except Exception:
            pass
        return

    try:
        handler.send_response(resp.status)
        for key, value in resp.getheaders():
            if key.lower() in PASSTHROUGH_HEADERS:
                handler.send_header(key, value)
        # SSE / 流式响应禁止缓冲（http.server 的 wfile 默认无缓冲，直接写即可）
        handler.end_headers()
        while True:
            # read1 返回当前可用字节（read(65536) 在无 Content-Length 的流式/SSE 响应上会阻塞到 EOF）
            chunk = resp.read1(65536) if hasattr(resp, "read1") else resp.read(65536)
            if not chunk:
                break
            handler.wfile.write(chunk)
            handler.wfile.flush()
    except (BrokenPipeError, ConnectionResetError):
        pass
    finally:
        conn.close()


class Handler(BaseHTTPRequestHandler):
    server_version = "ApprovalDemoProxy/0.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[proxy] %s %s\n" % (self.command, self.path))

    def _handle(self):
        parsed = urllib.parse.urlparse(self.path)
        # 静态资源优先；其余全部反向代理（页面 API、SSE、卡片代理等）
        if self.command in ("GET", "HEAD") and serve_static(self, parsed.path):
            return
        proxy_request(self, self.server.backend_host, self.server.backend_port)

    do_GET = _handle
    do_POST = _handle
    do_PUT = _handle
    do_DELETE = _handle


def main():
    parser = argparse.ArgumentParser(description="Approval Demo proxy server")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--backend", default=DEFAULT_BACKEND,
                        help="agent-framework 地址，如 127.0.0.1:8100")
    args = parser.parse_args()
    host, _, port = args.backend.partition(":")
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    server.backend_host = host
    server.backend_port = int(port or 8100)
    print("approval demo ui      : http://localhost:%d/" % args.port, flush=True)
    print("backend proxied       : http://%s:%d" % (server.backend_host, server.backend_port), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()