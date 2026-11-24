---
name: chrome-devtools-cli
description: Use this skill to write shell scripts or run shell commands to automate tasks in the browser or otherwise use Chrome DevTools via CLI.
---

`chrome-devtools-mcp` 提供的 `chrome-devtools` CLI 让你在终端里直接操作浏览器。本沙箱已预装该 CLI（版本 1.13.0），后台 daemon 已通过 `~/.config/cd4a/config.json` 连接到虚拟屏幕上可见的 Chromium（`http://127.0.0.1:9222`）——不需要再执行 `start`/`status`/`stop`。

## 工作流

1. **定位页面**：`chrome-devtools list_pages`（页面以数字 id 标识；后续命令的第一个位置参数就是页面 id）
2. **采样元素**：`chrome-devtools take_snapshot <pageId>` 返回可交互元素与 uid
3. **执行动作**：`chrome-devtools click <pageId> "<uid>"`、`chrome-devtools fill <pageId> "<uid>" "<文本>"` 等；动作后重新 `take_snapshot` 拿最新 uid

## 命令速查

```bash
chrome-devtools list_pages
chrome-devtools take_snapshot 1
chrome-devtools navigate_page 1 --url "http://127.0.0.1:8088/login"
chrome-devtools click 1 "2_5"
chrome-devtools fill 1 "2_7" "demo"
chrome-devtools press_key 1 "Enter"
chrome-devtools type_text 1 "hello" --submitKey "Enter"
chrome-devtools take_screenshot 1 --format jpeg --quality 70 --filePath /tmp/shot.jpg
chrome-devtools evaluate_script "() => document.title" --pageId 1
chrome-devtools list_console_messages 1
```

说明：

- 必填参数用位置参数；可选参数用 `--flag`（如 `--dblClick true`、`--includeSnapshot true`）
- `click`/`fill`/`hover`/`press_key` 支持 `--includeSnapshot true`：动作后直接返回新快照，省一次调用
- 输出默认是人类可读文本，加 `--output-format=json` 拿 JSON
- 每个命令都是一次 CLI 调用，状态（标签页、Cookie、表单内容）在后台 daemon 里跨命令保留
- 命令出错时先 `chrome-devtools list_pages` / `take_snapshot <pageId>` 确认现场，再决定下一步
- 需要保存截图/文件时用 `--filePath`，路径落在沙箱内

## 参考

- 上游工具参考：https://github.com/ChromeDevTools/chrome-devtools-mcp/blob/main/docs/tool-reference.md
- 上游 CLI 文档：https://github.com/ChromeDevTools/chrome-devtools-mcp/blob/main/docs/cli.md
