# -*- coding: utf-8 -*-
"""审批 Demo Mock MCP Server（streamableHttp，Python 标准库，零依赖）。

审批申请流程的 mock MCP 服务，验证 agent-framework 的 MCP Apps 能力：
- 用户输入：create_application（申请文本 + 文件链接落单）
- 表单确认：show_application_form（携带 ui:// 元数据，触发卡片）+
           confirm_application（app_only，仅卡片可调，确认/反悔表单）
- 提交：submit_application（agent-framework config.yaml 配 ask，
        LLM 调用时被 HITL 权限拦截，人工批准后才真正执行）

仅实现 initialize / ping / notifications/initialized / tools/list /
resources/read / tools/call 六个 MCP 方法。状态存内存（重启即清空）。
"""
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8813
HOST = "127.0.0.1"
RESOURCE_URI = "ui://approval/application-form.html"

STATE_LOCK = threading.Lock()
# application_id -> {application_id, title, description, file_links, stage, created_at}
APPLICATIONS = {}
ID_COUNTER = [0]


def next_app_id():
    with STATE_LOCK:
        ID_COUNTER[0] += 1
        return "APP-%04d" % ID_COUNTER[0]


def create_application(params):
    """创建申请单（用户输入落单）。"""
    title = str(params.get("title") or "").strip()
    description = str(params.get("description") or "").strip()
    raw_links = params.get("file_links") or []
    if isinstance(raw_links, str):
        raw_links = [l.strip() for l in raw_links.split(",") if l.strip()]
    file_links = [str(l).strip() for l in raw_links if str(l).strip()]
    if not title:
        return {"content": [{"type": "text", "text": "申请标题（title）不能为空"}], "isError": True}
    if not description:
        return {"content": [{"type": "text", "text": "申请文本（description）不能为空"}], "isError": True}
    app_id = next_app_id()
    record = {
        "application_id": app_id,
        "title": title,
        "description": description,
        "file_links": file_links,
        "stage": "created",
        "created_at": time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    with STATE_LOCK:
        APPLICATIONS[app_id] = record
    text = (
        "申请单创建成功：\n"
        "  application_id: %s\n"
        "  标题: %s\n"
        "  文件链接: %s\n"
        "下一步：调用 show_application_form(%s) 让用户在表单卡片上确认。" % (
            app_id, title, file_links, app_id)
    )
    return {"content": [{"type": "text", "text": text}], "isError": False}


def get_application(params):
    """查询申请详情（LLM 校验与卡片取数共用）。"""
    app_id = str(params.get("application_id") or "").strip()
    record = APPLICATIONS.get(app_id)
    if record is None:
        return {"content": [{"type": "text", "text": "申请单不存在: %s" % app_id}], "isError": True}
    return {"content": [{"type": "text", "text": json.dumps(record, ensure_ascii=False)}], "isError": False}


def show_application_form(params):
    """触发 MCP App 表单确认卡片（config.yaml 声明 ui:// 资源）。"""
    app_id = str(params.get("application_id") or "").strip()
    record = APPLICATIONS.get(app_id)
    if record is None:
        return {"content": [{"type": "text", "text": "申请单不存在: %s" % app_id}], "isError": True}
    text = (
        "已为申请单 %s 展示表单确认卡片，请用户核对申请文本与文件链接后点击卡片上的「确认表单」按钮。" % app_id
    )
    return {"content": [{"type": "text", "text": text}], "isError": False}


def confirm_application(params):
    """表单确认/反悔（app_only：仅卡片经代理调用，不对 LLM 暴露）。"""
    app_id = str(params.get("application_id") or "").strip()
    confirmed = bool(params.get("confirmed", True))
    record = APPLICATIONS.get(app_id)
    if record is None:
        return {"content": [{"type": "text", "text": "申请单不存在: %s" % app_id}], "isError": True}
    if record["stage"] == "submitted":
        return {"content": [{"type": "text", "text": "申请单已提交，不能再修改表单: %s" % app_id}], "isError": True}
    record["stage"] = "confirmed" if confirmed else "created"
    status = "已确认" if confirmed else "已返回修改"
    return {"content": [{"type": "text", "text": "申请单 %s 表单%s" % (app_id, status)}], "isError": False}


def submit_application(params):
    """提交审批（框架侧 permissions.tools 配 ask，人工批准后才执行到这里）。"""
    app_id = str(params.get("application_id") or "").strip()
    record = APPLICATIONS.get(app_id)
    if record is None:
        return {"content": [{"type": "text", "text": "申请单不存在: %s" % app_id}], "isError": True}
    if record["stage"] == "submitted":
        # 幂等：已提交的申请再次 submit 直接返回成功（已被人工批准过，不重复走流程）
        return {"content": [{"type": "text",
            "text": "SUCCESS: application %s was submitted. Stop now and report result to user." % app_id}],
            "isError": False}
    if record["stage"] != "confirmed":
        return {"content": [{
            "type": "text",
            "text": "申请单 %s 尚未确认表单，不能提交。请先调用 show_application_form(%s) 让用户在表单卡片上确认。" % (app_id, app_id),
        }], "isError": True}
    record["stage"] = "submitted"
    text = "SUCCESS: application %s was submitted. Stop now and report result to user." % app_id
    return {"content": [{"type": "text", "text": text}], "isError": False}


TOOLS = [
    {
        "name": "create_application",
        "description": "创建模型服务申请单，收集用户输入（申请标题 title、申请文本 description、文件链接列表 file_links）。返回 application_id。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "申请标题"},
                "description": {"type": "string", "description": "申请文本（申请理由与用途说明）"},
                "file_links": {"type": "array", "items": {"type": "string"},
                               "description": "相关文件链接列表（可空数组）"},
            },
            "required": ["title", "description"],
        },
    },
    {
        "name": "get_application",
        "description": "查询申请单详情（含 stage 状态），参数 application_id。",
        "inputSchema": {
            "type": "object",
            "properties": {"application_id": {"type": "string"}},
            "required": ["application_id"],
        },
    },
    {
        "name": "show_application_form",
        "description": "展示申请表单确认卡片（MCP App）。调用后用户会在界面上看到表单确认卡片，需在卡片上确认表单后流程才能继续。参数 application_id。",
        "inputSchema": {
            "type": "object",
            "properties": {"application_id": {"type": "string"}},
            "required": ["application_id"],
        },
        "_meta": {"ui": {"resourceUri": RESOURCE_URI}},
    },
    {
        "name": "confirm_application",
        "description": "卡片内部工具：确认或返回修改申请表单（app_only，不对 LLM 暴露）。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "application_id": {"type": "string"},
                "confirmed": {"type": "boolean", "description": "true 确认表单 / false 返回修改"},
            },
            "required": ["application_id"],
        },
    },
    {
        "name": "submit_application",
        "description": "提交申请单进入人工审批。需先确认表单。参数 application_id。",
        "inputSchema": {
            "type": "object",
            "properties": {"application_id": {"type": "string"}},
            "required": ["application_id"],
        },
    },
]

# 表单确认卡片（MCP App HTML，单文件内联，CSP 白名单允许 inline script/style）
CARD_HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>模型服务申请表</title>
<style>
  body { font-family: system-ui, -apple-system, sans-serif; margin: 0; padding: 16px;
         color: #1f2937; font-size: 14px; }
  h3 { margin: 0 0 12px; font-size: 15px; }
  .field { margin-bottom: 10px; }
  .label { font-size: 11px; color: #6b7280; margin-bottom: 3px; }
  .value { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px;
           padding: 8px 10px; white-space: pre-wrap; word-break: break-all; }
  .links { margin-top: 4px; }
  .link { background: #f0f9ff; border: 1px solid #bae6fd; border-radius: 6px;
          padding: 6px 10px; margin-bottom: 4px; word-break: break-all; color: #0369a1; }
  .status { padding: 8px 10px; border-radius: 6px; margin: 10px 0; font-weight: 600; }
  .status.ok { background: #ecfdf5; border: 1px solid #a7f3d0; color: #047857; }
  .status.wait { background: #fffbeb; border: 1px solid #fde68a; color: #b45309; }
  .status.err { background: #fef2f2; border: 1px solid #fecaca; color: #b91c1c; }
  .actions { margin-top: 12px; display: flex; gap: 8px; }
  button { border: none; border-radius: 6px; padding: 8px 16px; font-size: 13px;
           cursor: pointer; }
  button.primary { background: #2563eb; color: #fff; }
  button.secondary { background: #e5e7eb; color: #374151; }
  button:disabled { opacity: .5; cursor: not-allowed; }
  .hint { font-size: 11px; color: #9ca3af; margin-top: 10px; }
</style>
</head>
<body>
<div id="root"><div class="status wait">连接中&hellip;</div></div>
<script>
(function () {
  var root = document.getElementById('root');
  var appId = null;
  var pending = {};

  function post(msg) { window.parent.postMessage(msg, '*'); }

  function sendRequest(method, params) {
    return new Promise(function (resolve, reject) {
      var id = 'card-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8);
      var timer = setTimeout(function () {
        delete pending[id];
        reject(new Error(method + ' 响应超时'));
      }, 10000);
      pending[id] = { resolve: resolve, reject: reject, timer: timer };
      post({ jsonrpc: '2.0', id: id, method: method, params: params || {} });
    });
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function renderLoading() {
    root.innerHTML = '<div class="status wait">正在获取申请单数据&hellip;</div>';
  }

  function renderForm(rec) {
    var links = (rec.file_links || []).map(function (l) {
      return '<div class="link">' + esc(l) + '</div>';
    }).join('');
    appId = rec.application_id;
    root.innerHTML =
      '<h3>模型服务申请表</h3>' +
      '<div class="field"><div class="label">申请单号</div><div class="value">' + esc(rec.application_id) + '</div></div>' +
      '<div class="field"><div class="label">申请标题</div><div class="value">' + esc(rec.title) + '</div></div>' +
      '<div class="field"><div class="label">申请文本</div><div class="value">' + esc(rec.description) + '</div></div>' +
      '<div class="field"><div class="label">文件链接（' + (rec.file_links || []).length + '）</div>' +
      '<div class="links">' + (links || '<div class="value">（无）</div>') + '</div></div>' +
      '<div class="status wait">请核对以上信息，确认无误后点击「确认表单」</div>' +
      '<div class="actions">' +
      '<button class="primary" id="btnConfirm">确认表单</button>' +
      '<button class="secondary" id="btnModify">返回修改</button>' +
      '</div>' +
      '<div class="hint">确认后请回到对话输入「提交申请」，Agent 会发起人工审批确认。</div>';
    document.getElementById('btnConfirm').addEventListener('click', onConfirm);
    document.getElementById('btnModify').addEventListener('click', onModify);
  }

  function renderConfirmed() {
    root.innerHTML =
      '<h3>模型服务申请表</h3>' +
      '<div class="status ok">表单已确认（申请单号 ' + esc(appId) + '）</div>' +
      '<div class="hint">请回到对话告知 Agent 已确认表单，再发送「提交申请」进入人工审批。</div>' +
      '<div class="actions"><button class="secondary" id="btnBack">返回修改</button></div>';
    document.getElementById('btnBack').addEventListener('click', onModify);
  }

  function onConfirm() {
    var btn = document.getElementById('btnConfirm');
    if (!btn) return;
    btn.disabled = true; btn.textContent = '提交确认中…';
    sendRequest('tools/call', { name: 'confirm_application', arguments: { application_id: appId, confirmed: true } })
      .then(function () {
        // 静默更新模型上下文：让 agent 下一轮感知表单已确认
        return sendRequest('ui/update-model-context', {
          content: '用户已在表单卡片上确认申请单 ' + appId + ' 的表单信息无误，可以继续提交流程。'
        });
      })
      .then(function () { renderConfirmed(); })
      .catch(function (e) {
        var el = document.getElementById('btnConfirm');
        if (el) { el.disabled = false; el.textContent = '确认表单'; }
        root.insertAdjacentHTML('beforeend', '<div class="status err">确认失败：' + esc(e.message) + '</div>');
      });
  }

  function onModify() {
    var btn = event.target;
    btn.disabled = true;
    sendRequest('tools/call', { name: 'confirm_application', arguments: { application_id: appId, confirmed: false } })
      .then(function () {
        return sendRequest('ui/update-model-context', {
          content: '用户要求返回修改申请单 ' + appId + ' 的表单内容，请询问用户需要补充或修改哪些申请文本/文件链接。'
        });
      })
      .then(function () { renderForm({ application_id: appId }); })
      .catch(function (e) {
        btn.disabled = false;
        root.insertAdjacentHTML('beforeend', '<div class="status err">操作失败：' + esc(e.message) + '</div>');
      });
  }

  window.addEventListener('message', function (e) {
    var msg = e.data;
    if (!msg || msg.jsonrpc !== '2.0') return;
    if (msg.id != null && (msg.result !== undefined || msg.error)) {
      var p = pending[msg.id];
      if (p) {
        clearTimeout(p.timer);
        delete pending[msg.id];
        if (msg.error) p.reject(new Error((msg.error.message) || 'MCP App error'));
        else p.resolve(msg.result);
      }
      return;
    }
    if (msg.method === 'ui/notifications/tool-input') {
      var args = (msg.params && msg.params.arguments) || {};
      var id = args.application_id;
      if (!id) { root.innerHTML = '<div class="status err">未收到申请单号</div>'; return; }
      renderLoading();
      sendRequest('tools/call', { name: 'get_application', arguments: { application_id: id } })
        .then(function (res) {
          var texts = (res && res.content || []).filter(function (c) { return c && (c.text != null || c.type === 'text'); });
          var rec = null;
          for (var i = 0; i < texts.length; i++) {
            try { rec = JSON.parse(texts[i].text); break; } catch (e) { /* 非 JSON 继续 */ }
          }
          if (!rec) throw new Error('取数失败: ' + (texts[0] ? texts[0].text : 'empty'));
          if (rec.stage === 'confirmed') renderConfirmed();
          else renderForm(rec);
        })
        .catch(function (err) {
          root.innerHTML = '<div class="status err">' + esc(err.message) + '</div>';
        });
    } else if (msg.method === 'ui/notifications/tool-result') {
      // 结果已回流；无额外处理
    } else if (msg.method === 'ui/resource-teardown') {
      if (msg.id != null) post({ jsonrpc: '2.0', id: msg.id, result: {} });
    }
  });

  // 协议启动：ui/initialize handshake → 就绪后收 tool-input
  sendRequest('ui/initialize', { protocolVersion: '0.1.0', appContext: {} })
    .then(function () {
      post({ jsonrpc: '2.0', method: 'ui/notifications/initialized', params: {} });
    })
    .catch(function () { root.innerHTML = '<div class="status err">初始化失败</div>'; });
})();
</script>
</body>
</html>"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _send(self, data, status=200):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        """SDK streamableHttp 传输初始化时发 GET 探测，返回 SSE 空帧避免 501"""
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
                "capabilities": {"resources": {}, "tools": {}},
                "serverInfo": {"name": "approval-mcp", "version": "0.1.0"},
            }})
        elif method == "notifications/initialized":
            self._send({})
        elif method == "ping":
            self._send({"jsonrpc": "2.0", "id": mid, "result": {}})
        elif method == "tools/list":
            self._send({"jsonrpc": "2.0", "id": mid, "result": {"tools": TOOLS}})
        elif method == "resources/read":
            uri = params.get("uri", "")
            if uri == RESOURCE_URI:
                self._send({"jsonrpc": "2.0", "id": mid, "result": {
                    "contents": [{
                        "uri": RESOURCE_URI,
                        "mimeType": "text/html;profile=mcp-app",
                        "text": CARD_HTML,
                    }]
                }})
            else:
                self._send({"jsonrpc": "2.0", "id": mid, "error": {
                    "code": -32002, "message": "Resource not found: " + uri}})
        elif method == "tools/call":
            name = params.get("name", "")
            args = params.get("arguments") or {}
            import sys as _sys
            _sys.stderr.write("[trace] tools/call name=%s args=%s\n" % (name, json.dumps(args, ensure_ascii=False)))
            handlers = {
                "create_application": create_application,
                "get_application": get_application,
                "show_application_form": show_application_form,
                "confirm_application": confirm_application,
                "submit_application": submit_application,
            }
            fn = handlers.get(name)
            if fn is None:
                self._send({"jsonrpc": "2.0", "id": mid, "error": {
                    "code": -32602, "message": "Unknown tool: " + name}})
            else:
                try:
                    result = fn(args)
                    self._send({"jsonrpc": "2.0", "id": mid, "result": result})
                except Exception as e:
                    self._send({"jsonrpc": "2.0", "id": mid, "error": {
                        "code": -32000, "message": "Tool execution failed: %s" % e}})
        else:
            self._send({"jsonrpc": "2.0", "id": mid, "error": {
                "code": -32601, "message": "Method not found: " + str(method)}})


def main():
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print("approval mock-mcp listening on http://%s:%d" % (HOST, PORT), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()