"""变更→环境需求（设计 §4.1）：在 analyze_diff 模块映射之上产出 env_spec。

规则表（路径前缀 + diff 内容关键词）回答"测这些变更需要什么环境"：
  tool/** 变更            → 需要工具插件（OAF 包 plugins/ 下挂插件 jar）
  mcp/** 变更             → 需要 mock MCP（allow 工具集）
  HITL 关键词             → mock MCP 增设 ask 工具（触发 permission_ask 确认流）
  config/** / reload 关键词 → reload 回路预检
  会话模型关键词           → model 透传探针
规则集中在本文件常量区，随变更演进由 PR 补充（与 generator._MODULE_RULES 同风格）。
"""

import re
import subprocess
from pathlib import Path
from typing import Any

from casegen.generator import analyze_diff

# 插件源码目录候选（tool/ 变更时默认供给的示例插件，plugin-smoke 同源）
_DEFAULT_PLUGINS = [{"name": "echo-tool", "src": "e2e/plugin-echo",
                     "tools": ["echo_query", "smoke_config"], "reason": "tool/ 变更"}]

# mock MCP 工具目录（canned 载荷模拟 platform 域查询；ask 工具支撑 HITL 确认流评测）
_MOCK_TOOLS = [
    {"name": "list_services", "description": "列出平台服务（mock 夹具）",
     "permission": "allow", "handler": "canned",
     "payload": {"services": [
         {"serviceId": 1, "k8sName": "eval-demo-svc", "status": "running"},
         {"serviceId": 2, "k8sName": "eval-demo-svc-2", "status": "stopped"}]}},
    {"name": "get_service_status", "description": "查询服务状态（mock 夹具）",
     "permission": "allow", "handler": "canned",
     "payload": {"serviceId": 1, "k8sName": "eval-demo-svc", "status": "running",
                 "endpoint": "http://eval-demo.example.local/agent/eval-demo-svc"}},
    {"name": "mock_fail_tool", "description": "恒返回工具错误（错误路径评测用）",
     "permission": "allow", "handler": "fail"},
    {"name": "publish_service", "description": "发布服务（mock；ask 权限走人工确认流）",
     "permission": "ask", "handler": "canned",
     "payload": {"serviceId": 3, "k8sName": "eval-published-svc", "status": "deploying"}},
]

# diff 内容关键词 → 环境需求（HITL / reload / 会话模型）
_HITL_KEYWORDS = ("permission_ask", "confirm-stream", "ToolSummary", "tool_call_summary")
_RELOAD_KEYWORDS = ("AdminReloadController", "admin/reload", "reloadMcpServer", "OafConfigHolder")
_MODEL_KEYWORDS = ("ModelCatalog", "model_config", "session-model", "validateSelectable")


def _git(repo_dir: Path, *args: str) -> str:
    out = subprocess.run(["git", *args], cwd=repo_dir, capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} 失败: {out.stderr[:200]}")
    return out.stdout


def build_env_spec(repo_dir: Path, since: str) -> dict[str, Any]:
    """产出 env_spec：模块映射（复用 analyze_diff）+ 环境需求（本文件规则表）。"""
    analysis = analyze_diff(repo_dir, since)
    changed = [l.strip() for l in _git(repo_dir, "diff", "--name-only", f"{since}..HEAD").splitlines()
               if l.strip()]
    content = _git(repo_dir, "diff", f"{since}..HEAD")[:2_000_000]  # 关键词扫描截断，防超大 diff

    af_changed = [f for f in changed if f.startswith("agent-framework/src/main/java/")]
    has_tool = any("/framework/tool/" in f for f in af_changed)
    has_mcp = any("/framework/mcp/" in f for f in af_changed)
    hitl = any(k in content for k in _HITL_KEYWORDS)
    reload_probe = (any("/framework/config/" in f for f in af_changed)
                    or any(k in content for k in _RELOAD_KEYWORDS))
    model_probe = (any(re.search(r"model", f, re.I) and f.startswith("agent-framework/")
                       for f in changed if "/src/" in f or f.startswith("agent-framework/e2e/"))
                   or any(k in content for k in _MODEL_KEYWORDS))

    mock_enabled = has_mcp or hitl
    env_needs: dict[str, Any] = {
        "plugins": [dict(p) for p in _DEFAULT_PLUGINS] if has_tool else [],
        "mock_mcp": {
            "enabled": mock_enabled,
            "tools": [t["name"] for t in _MOCK_TOOLS] if mock_enabled else [],
            "ask_tools": [t["name"] for t in _MOCK_TOOLS if t["permission"] == "ask"]
                        if hitl else [],
        },
        "reload_probe": reload_probe,
        "session_model_probe": model_probe,
    }
    return {"since": since, "changed_files": changed, "modules": analysis["modules"],
            "risk": analysis["risk"], "env_needs": env_needs}


def env_tags(env_spec: dict[str, Any]) -> set[str]:
    """env_spec → 用例 requires_env 匹配标签集（runner 门禁用）。"""
    needs = env_spec.get("env_needs", {})
    tags = {f"plugin:{p['name']}" for p in needs.get("plugins", [])}
    mock = needs.get("mock_mcp", {})
    if mock.get("enabled"):
        tags.add("mock_mcp")
        if mock.get("ask_tools"):
            tags.add("mock_mcp:ask")
    if needs.get("reload_probe"):
        tags.add("reload")
    if needs.get("session_model_probe"):
        tags.add("session_model")
    return tags
