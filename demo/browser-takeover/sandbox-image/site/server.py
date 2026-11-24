import hashlib
import html
import http.cookies
import secrets
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HOST = "127.0.0.1"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8088
USERNAME = "demo"
SALT = "oaf-browser-takeover"
PASSWORD_DIGEST = "eb0561a6aa174c6980bb6670e239010425bd4242f3288fb6b0fa94c24fe684c8"
COOKIE_NAME = "demo_session"

ORDERS = [
    ("SO-1001", "机械键盘", 2, 1298.00, "已发货"),
    ("SO-1002", "4K 显示器", 1, 2699.00, "已发货"),
    ("SO-1003", "降噪耳机", 1, 1599.00, "待发货"),
    ("SO-1004", "人体工学椅", 2, 3996.00, "待发货"),
    ("SO-1005", "USB-C 扩展坞", 3, 1197.00, "已完成"),
]

sessions = set()
sessions_lock = threading.Lock()

STYLE = """
body { margin: 0; font-family: "WenQuanYi Micro Hei", "Noto Sans CJK SC", sans-serif;
       background: #f4f6fa; color: #1f2937; }
header { background: #1d4ed8; color: #fff; padding: 18px 32px; font-size: 20px; }
main { max-width: 860px; margin: 48px auto; padding: 0 24px; }
.card { background: #fff; border-radius: 12px; padding: 32px; box-shadow: 0 6px 24px rgba(15, 23, 42, .08); }
label { display: block; margin: 14px 0 6px; font-size: 14px; }
input { width: 100%; box-sizing: border-box; padding: 10px 12px; font-size: 15px;
        border: 1px solid #cbd5e1; border-radius: 8px; }
button { margin-top: 22px; padding: 11px 26px; font-size: 15px; color: #fff; background: #1d4ed8;
         border: 0; border-radius: 8px; cursor: pointer; }
.error { color: #dc2626; margin-top: 14px; }
table { width: 100%; border-collapse: collapse; margin-top: 20px; }
th, td { padding: 12px; border-bottom: 1px solid #e5e7eb; text-align: left; }
th { background: #eff6ff; }
.hint { color: #64748b; font-size: 14px; margin-top: 18px; }
"""


def page(title, body):
    return f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>{html.escape(title)}</title>
<style>{STYLE}</style>
</head>
<body>
<header>星河商城 · 商家后台</header>
<main><div class="card">{body}</div></main>
</body>
</html>"""


def login_page(error=""):
    alert = f'<p class="error">{html.escape(error)}</p>' if error else ""
    return page("登录", f"""
<h1>商家登录</h1>
<form method="post" action="/login">
  <label for="username">账号</label>
  <input id="username" name="username" autocomplete="off">
  <label for="password">密码</label>
  <input id="password" name="password" type="password">
  <button type="submit">登录</button>
  {alert}
  <p class="hint">登录需要密码与短信验证，仅商家本人知晓。</p>
</form>""")


def orders_page():
    rows = "".join(
        f"<tr><td>{oid}</td><td>{html.escape(name)}</td><td>{qty}</td>"
        f"<td>{amount:,.2f}</td><td>{html.escape(status)}</td></tr>"
        for oid, name, qty, amount, status in ORDERS
    )
    return page("订单", f"""
<h1>订单列表</h1>
<table>
  <thead><tr><th>订单号</th><th>商品</th><th>数量</th><th>金额</th><th>状态</th></tr></thead>
  <tbody>{rows}</tbody>
</table>
<p class="hint">当前账号：{USERNAME} · <a href="/logout">退出登录</a></p>""")


def password_matches(candidate):
    digest = hashlib.sha256((SALT + candidate).encode()).hexdigest()
    return secrets.compare_digest(digest, PASSWORD_DIGEST)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def send_html(self, body, status=200, headers=()):
        data = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(data)

    def current_session(self):
        cookies = http.cookies.SimpleCookie(self.headers.get("Cookie"))
        if COOKIE_NAME not in cookies:
            return None
        token = cookies[COOKIE_NAME].value
        with sessions_lock:
            return token if token in sessions else None

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/login":
            if self.current_session():
                self.send_html("", 302, [("Location", "/orders")])
            else:
                self.send_html(login_page())
        elif path == "/orders":
            if self.current_session():
                self.send_html(orders_page())
            else:
                self.send_html("", 302, [("Location", "/login")])
        elif path == "/logout":
            cookies = http.cookies.SimpleCookie(self.headers.get("Cookie"))
            if COOKIE_NAME in cookies:
                with sessions_lock:
                    sessions.discard(cookies[COOKIE_NAME].value)
            self.send_html("", 302, [("Location", "/login")])
        elif path == "/":
            self.send_html("", 302, [("Location", "/orders")])
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if urlparse(self.path).path != "/login":
            self.send_response(404)
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length") or 0)
        form = parse_qs(self.rfile.read(length).decode())
        username = (form.get("username") or [""])[0].strip()
        password = (form.get("password") or [""])[0]
        if username != USERNAME or not password_matches(password):
            self.send_html(login_page("账号或密码错误"))
            return
        token = secrets.token_urlsafe(24)
        with sessions_lock:
            sessions.add(token)
        self.send_html("", 302, [("Set-Cookie", f"{COOKIE_NAME}={token}; Path=/; HttpOnly"),
                                 ("Location", "/orders")])


if __name__ == "__main__":
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
