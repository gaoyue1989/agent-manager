# Debug 页面 API 请求 404

**日期**: 2026-05-19

## 问题

访问 `http://100.66.1.5:8911/agent/{id}/debug`，页面 HTML 能加载，但所有 API 请求返回 404，状态显示 `disconnected`，Threads 显示 `Failed to load`。

## 根因

Debug 页面的 JavaScript 使用了 `window.location.origin` 作为 API 基础路径：

```javascript
const BASE = window.location.origin;  // = "http://100.66.1.5:8911"
fetch(BASE + '/health');  // → http://100.66.1.5:8911/health  ❌ 命中 location / → Next.js 前端 → 404
```

而 agent-framework 的端点需要通过 `/agent/{id}/` 前缀访问：

```bash
# 正确路径
curl http://100.66.1.5:8911/agent/61/health  # ✅
```

## 解决

修改 `agent-framework/server/templates/debug_page.html:160`：

```javascript
const BASE = window.location.pathname.replace(/\/debug$/, '') || '';
```

这样 `/agent/{id}/debug` 页面下的 API 请求自动带上 `/agent/{id}/` 前缀。

## 影响范围

所有通过 Nginx `/agent/` 路径代理访问的 debug 页面。

## 相关文件

- `agent-framework/server/templates/debug_page.html`
