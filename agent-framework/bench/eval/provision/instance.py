"""实例生命周期与契约预检（设计 §4.4）：infra → 起 mock/实例 → 预检 → teardown。

被测实例经 docker 运行（--network host，maven 容器镜像含 JRE 21）；MySQL/Redis 复用
e2e/scripts/local-infra.sh（e2e-mysql:13306 / e2e-redis:16379）。预检把"供给是否到位"
在跑用例前暴露：插件注册、mock 工具注册、reload 回路、模型切换探针，避免产出假 FAIL。
"""

import json
import socket
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

import httpx

RUNTIME_NAME = ".runtime-eval"
CONTAINER = "af-eval-agent"
INSTANCE_PORT = 18100
MOCK_PORT = 18082

# 本地 infra 缺省（e2e/scripts/local-infra.sh 同源；可环境变量覆盖）
MYSQL_URL = "jdbc:mysql://127.0.0.1:13306/agent_framework_e2e"
MYSQL_USER = "e2e"
MYSQL_PASS = "e2e-pass"
REDIS_URL = "redis://127.0.0.1:16379"
CONTAINER_IMAGE = "maven:3.9-eclipse-temurin-21"


def _probe(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            return True
    except OSError:
        return False


def ensure_infra(repo_dir: Path) -> dict[str, str]:
    """MySQL:13306 / Redis:16379 就绪（缺失时调 local-infra.sh 拉起）。"""
    if not (_probe(13306) and _probe(16379)):
        script = repo_dir / "e2e" / "scripts" / "local-infra.sh"
        if not script.exists():
            raise RuntimeError("缺 MySQL/Redis 且找不到 e2e/scripts/local-infra.sh")
        subprocess.run(["bash", str(script)], check=True, capture_output=True, text=True, timeout=300)
        for _ in range(30):
            if _probe(13306) and _probe(16379):
                break
            time.sleep(2)
    if not (_probe(13306) and _probe(16379)):
        raise RuntimeError("基础设施未就绪（13306/16379）")
    return {"mysql_url": MYSQL_URL, "mysql_user": MYSQL_USER, "redis_url": REDIS_URL}


def start_mock(catalog_path: Path, runtime_dir: Path) -> subprocess.Popen:
    """启动 mock MCP 服务（后台常驻，pid 记入 state）。"""
    log = runtime_dir / "mock-mcp.jsonl"
    proc = subprocess.Popen(
        [sys.executable, str(Path(__file__).parents[1] / "mock" / "mcp_server.py"),
         "--catalog", str(catalog_path), "--port", str(MOCK_PORT), "--log", str(log)],
        stdout=open(runtime_dir / "mock-mcp.log", "w"), stderr=subprocess.STDOUT,
        start_new_session=True)
    for _ in range(20):
        if _probe(MOCK_PORT):
            return proc
        time.sleep(0.5)
    raise RuntimeError(f"mock MCP 启动失败，日志: {runtime_dir / 'mock-mcp.log'}")


def start_instance(repo_dir: Path, runtime_dir: Path, target_llm: dict[str, str],
                   plugin_marker: str = "eval-ok") -> str:
    """docker 起被测实例（--network host → 直连本机 MySQL/Redis/mock），返回 base_url。"""
    jars = sorted((repo_dir / "target").glob("agent-framework-*.jar"))
    if not jars:
        raise RuntimeError("缺少 agent-framework jar（先 mvn -DskipTests package）")
    subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True)
    envs = {
        "LLM_API_KEY": target_llm["api_key"],
        "LLM_MODEL_ID": target_llm["model"],
        "LLM_BASE_URL": target_llm["base_url"],
        "CHECKPOINT_JDBC_URL": MYSQL_URL,
        "CHECKPOINT_USERNAME": MYSQL_USER,
        "CHECKPOINT_PASSWORD": MYSQL_PASS,
        "AGENT_REDIS_URL": REDIS_URL,
        "AGENT_CONFIG_DIR": "/runtime/agent-config",
        "AGENT_WORKSPACE_DIR": "/runtime/workspace",
        "FILE_STORAGE_TYPE": "local",
        "FILE_STORAGE_LOCAL_DIR": "/runtime/files",
        "SANDBOX_ENABLED": "false",
        "SERVER_PORT": str(INSTANCE_PORT),
        "EVAL_PLUGIN_MARKER": plugin_marker,
        "JAVA_OPTS": "-Xmx768m",
    }
    cmd = ["docker", "run", "-d", "--name", CONTAINER, "--network", "host",
           "-v", f"{repo_dir / 'target'}:/app:ro", "-v", f"{runtime_dir}:/runtime"]
    for k, v in envs.items():
        cmd += ["-e", f"{k}={v}"]
    cmd += ["--entrypoint", "java", CONTAINER_IMAGE, "-jar",
            f"/app/{jars[-1].name}"]
    subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=60)
    return f"http://127.0.0.1:{INSTANCE_PORT}"


def wait_health(base_url: str, timeout_s: int = 180) -> None:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            if httpx.get(f"{base_url}/health", timeout=5).status_code == 200:
                return
        except httpx.HTTPError:
            pass
        time.sleep(2)
    raise RuntimeError(f"实例健康检查超时（{timeout_s}s）：{base_url}/health")


def preflight(env_spec: dict[str, Any], base_url: str) -> dict[str, Any]:
    """契约预检：按 env_spec 逐项验证供给到位，结果写 caller 落盘。"""
    needs = env_spec["env_needs"]
    checks: list[dict[str, Any]] = []

    def record(name: str, ok: bool, detail: str = "") -> None:
        checks.append({"name": name, "pass": ok, "detail": detail})

    wait_health(base_url)
    record("health", True)

    def tool_names() -> set[str]:
        resp = httpx.get(f"{base_url}/tools", params={"includeInternal": "true"}, timeout=10)
        resp.raise_for_status()
        return {t.get("name") for t in resp.json().get("tools", []) if t.get("name")}

    for plugin in needs["plugins"]:
        want = set(plugin.get("tools", []))
        got = tool_names()
        record(f"plugin:{plugin['name']}", want <= got,
               f"期望 {sorted(want)}，/tools 实有 {sorted(got & want) if want & got else '（无）'}")

    if needs["mock_mcp"]["enabled"]:
        want = set(needs["mock_mcp"]["tools"])
        got = tool_names()
        record("mock_mcp_tools", want <= got, f"缺 {sorted(want - got) or '无'}")

    if needs["reload_probe"]:
        try:
            r = httpx.post(f"{base_url}/admin/reload?scope=agent", timeout=60)
            ok = r.status_code == 200
            detail = f"HTTP {r.status_code}"
            if ok:
                time.sleep(3)
                got = tool_names()
                after = all(set(p.get("tools", [])) <= got for p in needs["plugins"]) \
                    or all(t in got for t in needs["mock_mcp"]["tools"])
                record("reload_probe", after, "reload 后工具集保持")
            else:
                record("reload_probe", False, detail)
        except httpx.HTTPError as e:
            record("reload_probe", False, str(e))

    if needs["session_model_probe"]:
        try:
            r = httpx.get(f"{base_url}/models", timeout=10)
            record("session_model_probe", r.status_code == 200,
                   f"GET /models HTTP {r.status_code}")
        except httpx.HTTPError as e:
            record("session_model_probe", False, str(e))

    return {"checks": checks, "pass": all(c["pass"] for c in checks),
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z")}


def teardown(runtime_dir: Path | None = None) -> None:
    """停被测实例与 mock（infra 容器保留复用）。"""
    subprocess.run(["docker", "rm", "-f", CONTAINER], capture_output=True)
    state_file = (runtime_dir or Path(__file__).parents[1] / RUNTIME_NAME) / "state.json"
    if state_file.exists():
        try:
            state = json.loads(state_file.read_text(encoding="utf-8"))
            pid = state.get("mock_pid")
            if pid:
                subprocess.run(["kill", str(pid)], capture_output=True)
        except (OSError, json.JSONDecodeError):
            pass
