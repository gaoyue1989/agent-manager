"""MCP SSE Server — 正确实现 MCP SSE 传输协议

响应通过 SSE stream 返回（非 HTTP response），支持多 client 并发。
"""

import json
import os
import uuid
import queue
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse


class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


# 全局: session_id → response queue
_sessions: dict[str, queue.Queue] = {}
_sessions_lock = threading.Lock()


class SSEHandler(BaseHTTPRequestHandler):
    """单个 SSE 连接的 handler"""

    def __init__(self, *args, session_id=None, **kwargs):
        self._session_id = session_id or str(uuid.uuid4())
        super().__init__(*args, **kwargs)

    def log_message(self, format, *args):
        pass

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == '/sse':
            self._handle_sse()
        else:
            self.send_error(404)

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path == '/message':
            self._handle_message()
        else:
            self.send_error(404)

    def _handle_sse(self):
        """GET /sse — 建立 SSE 连接，持续推送事件"""
        session_id = self.headers.get('X-Session-Id') or str(uuid.uuid4())
        self._session_id = session_id

        # 为该 session 创建响应队列
        q: queue.Queue = queue.Queue()
        with _sessions_lock:
            _sessions[session_id] = q

        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'keep-alive')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()

        # 发送 endpoint 事件
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
                    # SSE 注释行（以 : 开头），客户端应忽略
                    self.wfile.write(b': keepalive\n\n')
                    self.wfile.flush()
        except (BrokenPipeError, OSError):
            pass
        finally:
            with _sessions_lock:
                _sessions.pop(session_id, None)

    def _handle_message(self):
        """POST /message — 接收 JSON-RPC，处理后将响应放入 SSE 队列或直接返回"""
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length)
        message = json.loads(body)

        # 从 URL 获取 session_id
        parsed = urlparse(self.path)
        qs = {}
        if parsed.query:
            from urllib.parse import parse_qs
            qs = {k: v[0] for k, v in parse_qs(parsed.query).items()}
        session_id = qs.get('sessionId') or ''

        # 处理 JSON-RPC
        response = self._process_rpc(message)

        # 检查是否有对应的 SSE 连接
        with _sessions_lock:
            q = _sessions.get(session_id)

        if q:
            # 有 SSE 连接，响应放入队列
            q.put(response)
            self.send_response(202)
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
        else:
            # 无 SSE 连接，直接返回 HTTP 响应
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps(response).encode())

    def _process_rpc(self, message: dict) -> dict:
        """处理 JSON-RPC 请求，返回响应 dict"""
        method = message.get('method', '')
        msg_id = message.get('id', 0)

        if method == 'initialize':
            result = {
                'protocolVersion': message.get('params', {}).get('protocolVersion', '2024-11-05'),
                'capabilities': {'tools': {'listChanged': False}},
                'serverInfo': {'name': 'filesystem', 'version': '1.0.0'},
            }
        elif method == 'notifications/initialized':
            return {}  # 不需要响应
        elif method == 'tools/list':
            result = {
                'tools': [
                    {
                        'name': 'read_file',
                        'description': 'Read contents of a file',
                        'inputSchema': {
                            'type': 'object',
                            'properties': {'path': {'type': 'string'}},
                            'required': ['path'],
                        },
                    },
                    {
                        'name': 'list_directory',
                        'description': 'List files in a directory',
                        'inputSchema': {
                            'type': 'object',
                            'properties': {'path': {'type': 'string'}},
                            'required': ['path'],
                        },
                    },
                    {
                        'name': 'write_file',
                        'description': 'Write content to a file',
                        'inputSchema': {
                            'type': 'object',
                            'properties': {
                                'path': {'type': 'string'},
                                'content': {'type': 'string'},
                            },
                            'required': ['path', 'content'],
                        },
                    },
                ]
            }
        elif method == 'tools/call':
            name = message.get('params', {}).get('name', '')
            args = message.get('params', {}).get('arguments', {})
            result = self._call_tool(name, args)
            response = {
                'jsonrpc': '2.0',
                'id': msg_id,
                'result': result,
            }
            return response
        else:
            return {
                'jsonrpc': '2.0',
                'id': msg_id,
                'error': {'code': -32601, 'message': f'Method not found: {method}'},
            }

        return {
            'jsonrpc': '2.0',
            'id': msg_id,
            'result': result,
        }

    def _call_tool(self, name: str, args: dict) -> dict:
        if name == 'read_file':
            try:
                with open(args['path'], 'r') as f:
                    text = f.read()
                return {'content': [{'type': 'text', 'text': text}]}
            except Exception as e:
                return {'content': [{'type': 'text', 'text': f'Error: {e}'}], 'isError': True}
        elif name == 'list_directory':
            try:
                files = os.listdir(args.get('path', '.'))
                return {'content': [{'type': 'text', 'text': '\n'.join(files)}]}
            except Exception as e:
                return {'content': [{'type': 'text', 'text': f'Error: {e}'}], 'isError': True}
        elif name == 'write_file':
            try:
                with open(args['path'], 'w') as f:
                    f.write(args['content'])
                return {'content': [{'type': 'text', 'text': f'Successfully wrote to {args["path"]}'}]}
            except Exception as e:
                return {'content': [{'type': 'text', 'text': f'Error: {e}'}], 'isError': True}
        return {'content': [{'type': 'text', 'text': f'Unknown tool: {name}'}], 'isError': True}


class MCPFileSystemHandler(SSEHandler):
    def _process_rpc(self, message: dict) -> dict:
        method = message.get('method', '')
        msg_id = message.get('id', 0)

        if method == 'initialize':
            result = {
                'protocolVersion': message.get('params', {}).get('protocolVersion', '2024-11-05'),
                'capabilities': {'tools': {'listChanged': False}},
                'serverInfo': {'name': 'filesystem', 'version': '1.0.0'},
            }
        elif method == 'notifications/initialized':
            return {}
        elif method == 'tools/list':
            result = {
                'tools': [
                    {
                        'name': 'read_file',
                        'description': 'Read contents of a file',
                        'inputSchema': {
                            'type': 'object',
                            'properties': {'path': {'type': 'string'}},
                            'required': ['path'],
                        },
                    },
                    {
                        'name': 'list_directory',
                        'description': 'List files in a directory',
                        'inputSchema': {
                            'type': 'object',
                            'properties': {'path': {'type': 'string'}},
                            'required': ['path'],
                        },
                    },
                    {
                        'name': 'write_file',
                        'description': 'Write content to a file',
                        'inputSchema': {
                            'type': 'object',
                            'properties': {
                                'path': {'type': 'string'},
                                'content': {'type': 'string'},
                            },
                            'required': ['path', 'content'],
                        },
                    },
                ]
            }
        elif method == 'tools/call':
            name = message.get('params', {}).get('name', '')
            args = message.get('params', {}).get('arguments', {})
            result = self._call_tool(name, args)
            response = {
                'jsonrpc': '2.0',
                'id': msg_id,
                'result': result,
            }
            return response
        else:
            return {
                'jsonrpc': '2.0',
                'id': msg_id,
                'error': {'code': -32601, 'message': f'Method not found: {method}'},
            }

        return {
            'jsonrpc': '2.0',
            'id': msg_id,
            'result': result,
        }


class MCPWeatherHandler(SSEHandler):
    MOCK_WEATHER = {
        "北京": {"temp": 28, "desc": "晴天", "humidity": "45%"},
        "上海": {"temp": 32, "desc": "多云转晴", "humidity": "60%"},
        "深圳": {"temp": 33, "desc": "雷阵雨", "humidity": "80%"},
        "广州": {"temp": 30, "desc": "阴天", "humidity": "75%"},
        "杭州": {"temp": 29, "desc": "晴天", "humidity": "50%"},
    }

    # 天气图标映射
    WEATHER_ICONS = {
        "晴天": "☀️", "晴": "☀️", "多云转晴": "⛅", "多云": "☁️",
        "阴天": "☁️", "雷阵雨": "⛈️", "雨": "🌧️", "小雨": "🌦️",
        "中雨": "🌧️", "大雨": "🌧️", "雪": "❄️", "小雪": "🌨️",
        "雾": "🌫️", "未知": "🌡️",
    }

    def _get_weather_icon(self, desc: str) -> str:
        """根据天气描述返回对应图标"""
        for key, icon in self.WEATHER_ICONS.items():
            if key in desc:
                return icon
        return "🌡️"

    def _get_weather_card_html(self) -> str:
        """返回天气卡片 HTML 模板（MCP Apps ui:// 资源）"""
        return '''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>Weather Card</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:transparent}
.weather-card{border-radius:16px;overflow:hidden;max-width:360px;margin:0 auto;background:#1a1a2e;box-shadow:0 8px 32px rgba(0,0,0,0.3)}
.weather-header{padding:20px 24px 12px;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);display:flex;justify-content:space-between;align-items:center}
.weather-city{color:#fff;font-size:18px;font-weight:600}
.weather-icon{font-size:48px;line-height:1}
.weather-body{padding:16px 24px 20px;text-align:center}
.weather-temp{font-size:56px;font-weight:700;color:#fff;line-height:1.1}
.weather-temp sup{font-size:24px;font-weight:400;vertical-align:super}
.weather-desc{font-size:16px;color:#a0aec0;margin-top:8px}
.weather-details{display:flex;justify-content:center;gap:24px;margin-top:16px;padding-top:16px;border-top:1px solid #2d3748}
.weather-detail{text-align:center}
.weather-detail-label{font-size:11px;color:#718096;text-transform:uppercase;letter-spacing:0.5px}
.weather-detail-value{font-size:16px;color:#e2e8f0;font-weight:600;margin-top:2px}
</style>
</head>
<body>
<div class="weather-card">
  <div class="weather-header">
    <span class="weather-city" id="city">--</span>
    <span class="weather-icon" id="icon">🌡️</span>
  </div>
  <div class="weather-body">
    <div class="weather-temp" id="temp">--<sup>°C</sup></div>
    <div class="weather-desc" id="desc">--</div>
    <div class="weather-details">
      <div class="weather-detail">
        <div class="weather-detail-label">湿度</div>
        <div class="weather-detail-value" id="humidity">--</div>
      </div>
    </div>
  </div>
</div>
<script>
(function(){
  var d=document;
  function render(data){
    if(!data)return;
    var c=d.getElementById('city'),t=d.getElementById('temp'),
        ds=d.getElementById('desc'),h=d.getElementById('humidity'),
        i=d.getElementById('icon');
    if(c)c.textContent=data.city||'--';
    if(t){var temp=(data.temperature||'--').replace('°C','');t.innerHTML=temp+'<sup>°C</sup>';}
    if(ds)ds.textContent=data.description||'--';
    if(h)h.textContent=data.humidity||'--';
    var icons={"晴天":"☀️","晴":"☀️","多云转晴":"⛅","多云":"☁️","阴天":"☁️","雷阵雨":"⛈️","雨":"🌧️","小雨":"🌦️","中雨":"🌧️","大雨":"🌧️","雪":"❄️","小雪":"🌨️","雾":"🌫️"};
    var desc=data.description||'';var icon='🌡️';
    for(var k in icons){if(desc.indexOf(k)!==-1){icon=icons[k];break;}}
    if(i)i.textContent=icon;
  }
  // 监听 MCP Apps tool-input 通知
  window.addEventListener('message',function(e){
    if(e.data&&e.data.method==='ui/notifications/tool-input'){
      render(e.data.params.arguments);
    }
  });
  // 暴露全局 render 函数供外部调用
  window.renderWeather=render;
})();
</script>
</body>
</html>'''

    def _process_rpc(self, message: dict) -> dict:
        method = message.get('method', '')
        msg_id = message.get('id', 0)

        if method == 'initialize':
            result = {
                'protocolVersion': message.get('params', {}).get('protocolVersion', '2024-11-05'),
                'capabilities': {'tools': {'listChanged': False}},
                'serverInfo': {'name': 'weather', 'version': '1.0.0'},
            }
        elif method == 'notifications/initialized':
            return {}
        elif method == 'tools/list':
            result = {
                'tools': [
                    {
                        'name': 'get_weather',
                        'description': '查询指定城市的当前天气（温度、天气状况、湿度）',
                        'inputSchema': {
                            'type': 'object',
                            'properties': {'city': {'type': 'string'}},
                            'required': ['city'],
                        },
                        '_meta': {
                            'ui': {
                                'resourceUri': 'ui://weather/weather-card',
                            },
                        },
                    },
                    {
                        'name': 'get_forecast',
                        'description': '查询指定城市未来3天的天气预报',
                        'inputSchema': {
                            'type': 'object',
                            'properties': {'city': {'type': 'string'}},
                            'required': ['city'],
                        },
                    },
                ]
            }
        elif method == 'resources/list':
            result = {
                'resources': [
                    {
                        'uri': 'ui://weather/weather-card',
                        'name': 'Weather Card',
                        'description': '天气卡片展示组件',
                        'mimeType': 'text/html;profile=mcp-app',
                        '_meta': {
                            'ui': {
                                'prefersBorder': True,
                            },
                        },
                    },
                ],
            }
        elif method == 'resources/read':
            uri = message.get('params', {}).get('uri', '')
            if uri == 'ui://weather/weather-card':
                result = {
                    'contents': [{
                        'uri': 'ui://weather-card',
                        'mimeType': 'text/html;profile=mcp-app',
                        'text': self._get_weather_card_html(),
                    }],
                }
            else:
                return {
                    'jsonrpc': '2.0',
                    'id': msg_id,
                    'error': {'code': -32001, 'message': f'Resource not found: {uri}'},
                }
        elif method == 'tools/call':
            name = message.get('params', {}).get('name', '')
            args = message.get('params', {}).get('arguments', {})
            city = args.get('city', 'default')
            w = self.MOCK_WEATHER.get(city, {"temp": 22, "desc": "未知", "humidity": "N/A"})

            if name == 'get_weather':
                text = json.dumps({
                    'city': city,
                    'temperature': f"{w['temp']}°C",
                    'description': w['desc'],
                    'humidity': w['humidity'],
                }, ensure_ascii=False)
            elif name == 'get_forecast':
                text = json.dumps({
                    'city': city,
                    'forecast': [
                        {'day': '今天', 'high': w['temp'] + 2, 'low': w['temp'] - 5, 'desc': w['desc']},
                        {'day': '明天', 'high': w['temp'] + 1, 'low': w['temp'] - 3, 'desc': '多云'},
                        {'day': '后天', 'high': w['temp'] + 3, 'low': w['temp'] - 2, 'desc': '晴天'},
                    ],
                }, ensure_ascii=False)
            else:
                text = f'未知工具: {name}'

            response = {
                'jsonrpc': '2.0',
                'id': msg_id,
                'result': {'content': [{'type': 'text', 'text': text}]},
            }
            return response
        else:
            return {
                'jsonrpc': '2.0',
                'id': msg_id,
                'error': {'code': -32601, 'message': f'Method not found: {method}'},
            }

        return {
            'jsonrpc': '2.0',
            'id': msg_id,
            'result': result,
        }


def run_server(port: int, handler_class, name: str):
    server = ThreadedHTTPServer(('0.0.0.0', port), handler_class)
    print(f'{name} SSE server running on http://0.0.0.0:{port}')
    server.serve_forever()


if __name__ == '__main__':
    import sys
    server_type = sys.argv[1] if len(sys.argv) > 1 else 'both'

    if server_type == 'filesystem':
        run_server(8811, MCPFileSystemHandler, 'Filesystem')
    elif server_type == 'weather':
        run_server(8812, MCPWeatherHandler, 'Weather')
    else:
        t1 = threading.Thread(target=run_server, args=(8811, MCPFileSystemHandler, 'Filesystem'), daemon=True)
        t2 = threading.Thread(target=run_server, args=(8812, MCPWeatherHandler, 'Weather'), daemon=True)
        t1.start()
        t2.start()
        print('Both MCP servers started on 8811 and 8812')
        t1.join()
        t2.join()
