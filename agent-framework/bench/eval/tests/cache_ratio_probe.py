#!/usr/bin/env python3
"""缓存命中率对比探针（AF vs dsh）：两框架经 eval-collector 跑同款多轮对话，
解析 provider 返回的 prompt_tokens / cached_tokens，输出逐调用与汇总的缓存命中统计。

背景：dsh MODEL_CALL_END.inputTokens 只含缓存未命中增量（issue #7），AF 帧面为全量——
两侧自报口径不可比；缓存命中统计必须取 provider 侧原始 usage（本脚本经 collector 录制获得）。

用法：python3 bench/eval/tests/cache_ratio_probe.py [--turns N] [--out 报告.json]
前提：.env.secrets 凭据、docker（e2e-mysql/redis + AF jar 已 mvn package）、本地 dsh 仓库、node。
产出：逐调用 {prompt, cached, ratio} + 汇总（首轮/重复轮命中率、未命中均值），markdown 表。
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

import httpx

TESTS_DIR = Path(__file__).resolve().parent
EVAL_DIR = TESTS_DIR.parent
REPO = EVAL_DIR.parent.parent  # agent-framework/（与 flywheel.py 同口径）
DATA = Path("/tmp/cache-probe/data")
sys.path.insert(0, str(EVAL_DIR))
TURNS = [
    "请用一句话介绍你自己（缓存探针轮）",
    "调用 echo 工具原样回显文本 cache-probe-1",
    "再调用一次 echo 工具，回显文本 cache-probe-2",
]


def load_env_secrets() -> None:
    f = REPO.parent / ".env.secrets"
    if f.exists():
        for line in f.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip())
    # judge 别名（与 e2e/compare 驱动同规则）：优先用评测模型，录制上游已下线时兜底
    for dst, src_env in [("EVAL_LLM_BASE_URL", "EVAL_JUDGE_LLM_BASE_URL"),
                         ("EVAL_LLM_API_KEY", "EVAL_JUDGE_LLM_API_KEY"),
                         ("EVAL_LLM_MODEL", "EVAL_JUDGE_LLM_MODEL")]:
        if not os.environ.get(dst) and os.environ.get(src_env):
            os.environ[dst] = os.environ[src_env]


def free_port_aside() -> tuple[subprocess.Popen, int, int, int, int, int]:
    """拉起取证用 collector（独立端口，不与工作站冲突）。返回 (proc, llm, sbx, mcp, agent, admin)。"""
    ports = (18220, 18221, 18222, 18223, 18320)
    for p in ports:
        kill_port(p)  # 上轮崩溃可能遗留 collector 占端口
    data, conf = DATA / ".." / "collector-data", DATA / ".." / "collector-conf"
    for p in (data, conf):
        p.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, EVAL_COLLECTOR_DATA=str(DATA), EVAL_COLLECTOR_CONF=str(conf),
               EVAL_COLLECTOR_PORT_LLM=str(ports[0]), EVAL_COLLECTOR_PORT_SANDBOX=str(ports[1]),
               EVAL_COLLECTOR_PORT_MCP=str(ports[2]), EVAL_COLLECTOR_PORT_AGENT=str(ports[3]),
               EVAL_COLLECTOR_PORT_ADMIN=str(ports[4]))
    proc = subprocess.Popen(["node", str(REPO / "bench/eval-collector/server.mjs")], env=env,
                            stdout=open(DATA / "collector.log", "w"), stderr=subprocess.STDOUT)
    for _ in range(30):
        try:
            if httpx.get(f"http://127.0.0.1:{ports[4]}/healthz", timeout=1).status_code == 200:
                return proc, *ports
        except httpx.HTTPError:
            time.sleep(0.3)
    raise RuntimeError("collector 启动失败")


def ensure_mock_mcp() -> None:
    out = subprocess.run(["ss", "-tln"], capture_output=True, text=True).stdout
    if ":18085 " in out:
        return
    subprocess.Popen([sys.executable, str(EVAL_DIR / "mock/mcp_server.py"),
                      "--catalog", "/tmp/eval-compare/af/mock-catalog.json", "--port", "18085"],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1)


def kill_port(port: int) -> None:
    """按端口精确清遗留进程（不按 cmdline 模式杀——集群容器进程同名会误伤）。"""
    for _ in range(20):
        out = subprocess.run(["ss", "-tlnp"], capture_output=True, text=True).stdout
        pids = {m for l in out.splitlines() if f":{port} " in l for m in re.findall(r"pid=(\d+)", l)}
        if not pids:
            return
        for p in pids:
            subprocess.run(["kill", "-9", p], capture_output=True)
        time.sleep(0.5)


def wipe_db(db: str) -> None:
    subprocess.run(["docker", "exec", "e2e-mysql", "mysql", "-uroot", "-pe2e-root", "-e",
                    f"DROP DATABASE IF EXISTS {db}; CREATE DATABASE {db};"
                    f"GRANT ALL PRIVILEGES ON {db}.* TO 'e2e'@'%'; FLUSH PRIVILEGES;"],
                   check=True, capture_output=True, timeout=30)


def drive(base_url: str, turns: list[str]) -> None:
    for t in turns:
        with httpx.stream("POST", f"{base_url}/threads/chat",
                          json={"message": t, "userId": "cache-probe"}, timeout=180) as resp:
            if resp.status_code != 200:
                raise RuntimeError(f"chat {resp.status_code}")
            for _ in resp.iter_lines():
                pass
        time.sleep(0.5)


def parse_ns(ns: str) -> list[dict]:
    """从 collector 录制解析每次调用的 usage；后台调用判定 = 请求无 tools 字段
    （标题/记忆旁路不带工具目录——比"无 user 消息"更准，标题 prompt 含 user 消息）。"""
    calls = []
    for f in sorted((DATA / ns / "llm").glob("*.jsonl")):
        for line in f.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            r = json.loads(line)
            usage = None
            for c in r.get("chunks") or []:
                if c == "[DONE]":
                    continue
                try:
                    j = json.loads(c)
                    if j.get("usage"):
                        usage = j["usage"]
                except json.JSONDecodeError:
                    pass
            if not usage and isinstance(r.get("body"), dict):
                usage = r["body"].get("usage")  # 非流式响应：usage 在 body
            if not usage:
                continue
            p = usage.get("prompt_tokens", 0)
            cached = (usage.get("prompt_tokens_details") or {}).get("cached_tokens", 0)
            calls.append({"prompt": p, "cached": cached,
                          "ratio": round(cached / p, 3) if p else 0,
                          "background": not (r.get("request") or {}).get("tools")})
    return calls


def summarize(calls: list[dict]) -> dict:
    main = [c for c in calls if not c["background"]]
    rep = main[1:]
    return {"main_calls": len(main),
            "first_call_ratio": main[0]["ratio"] if main else None,
            "repeat_hit_ratio": round(sum(c["cached"] for c in rep) / max(sum(c["prompt"] for c in rep), 1), 3),
            "repeat_uncached_avg": round(sum(c["prompt"] - c["cached"] for c in rep) / max(len(rep), 1)) if rep else None,
            "calls": calls}


def render(results: dict, turns: list[str]) -> str:
    lines = ["# 缓存命中率对比（provider 侧 usage 实测）", "",
             f"- 探针对话：{len(turns)} 轮（第 2/3 轮带工具调用）",
             "- 口径：provider 返回的 `prompt_tokens` / `prompt_tokens_details.cached_tokens`；",
             "  后台调用（无工具目录的标题/记忆旁路）单列不计入主链统计。", ""]
    for key in ("af", "dsh"):
        s = results[key]
        lines.append(f"## {key}（主链 {s['main_calls']} 次）")
        lines.append("")
        lines.append("| # | prompt | cached | 命中率 |")
        lines.append("|---|---|---|---|")
        n = 0
        for c in s["calls"]:
            kind = "后台" if c["background"] else f"主链{n+1}"
            if not c["background"]:
                n += 1
            lines.append(f"| {kind} | {c['prompt']} | {c['cached']} | {c['ratio']:.1%} |")
        lines.append("")
        lines.append(f"首轮命中 {s['first_call_ratio']:.1%}；"
                     f"重复轮命中 {s['repeat_hit_ratio']:.1%}；"
                     f"重复轮未命中均值 {s['repeat_uncached_avg']} tok/次。")
        lines.append("")
    return "\n".join(lines) + "\n"


def main() -> int:
    global DATA
    ap = argparse.ArgumentParser()
    ap.add_argument("--turns", type=int, default=3)
    ap.add_argument("--work", default="/tmp/cache-probe")
    ap.add_argument("--out", default=None, help="统计 json 输出路径")
    args = ap.parse_args()
    DATA = Path(args.work)
    DATA.mkdir(parents=True, exist_ok=True)
    load_env_secrets()
    turns = TURNS[:max(args.turns, 2)]

    inst_mod = None
    from provision import instance as inst_mod  # noqa: E402
    inst_mod.ensure_infra(REPO)
    ensure_mock_mcp()
    collector, llm_p, _sbx, _mcp, _agent, admin_p = free_port_aside()

    base_upstream = (os.environ.get("EVAL_LLM_BASE_URL") or os.environ.get("EVAL_RECORD_LLM_BASE_URL", "")).rstrip("/")
    api_key = os.environ.get("EVAL_LLM_API_KEY") or os.environ.get("EVAL_RECORD_LLM_API_KEY", "")
    model = os.environ.get("EVAL_LLM_MODEL") or os.environ.get("EVAL_RECORD_LLM_MODEL", "")
    httpx.post(f"http://127.0.0.1:{admin_p}/api/profiles", timeout=10,
               json={"ns": "cache", "upstream": {"llm": base_upstream, "llm_api_key": api_key,
                                                 "llm_default_model": model}})
    results: dict[str, dict] = {}
    try:
        for key, port, db in (("af", 18100, "agent_framework_e2e"), ("dsh", 18110, "eval_compare_dsh")):
            # 每框架独立 ns 录制（互不覆盖）；重置实例与库（provider 缓存为账号级前缀缓存，
            # 首轮命中受历史流量影响，解读时注意）
            subprocess.run(["rm", "-rf", str(DATA / "cache")], capture_output=True)
            kill_port(port)
            wipe_db(db)
            if key == "af":
                rt = DATA / "af"
                (rt / "agent-config").mkdir(parents=True, exist_ok=True)
                if not (rt / "agent-config/AGENTS.md").exists():
                    from provision import oaf as oaf_mod
                    from provision.needs import _MOCK_TOOLS
                    spec = {"since": "cache-probe", "env_needs": {
                        "plugins": [], "reload_probe": False, "session_model_probe": False,
                        "mock_mcp": {"enabled": True, "tools": [t["name"] for t in _MOCK_TOOLS],
                                     "ask_tools": [t["name"] for t in _MOCK_TOOLS if t["permission"] == "ask"]}}}
                    oaf_mod.assemble(spec, rt / "agent-config", rt, REPO, "http://127.0.0.1:18085/mcp")
                base = inst_mod.start_instance(REPO, rt, {"base_url": f"http://127.0.0.1:{llm_p}/cache/v1",
                                                          "api_key": "probe", "model": model})
                inst_mod.wait_health(base)
                try:
                    drive(base, turns)
                finally:
                    inst_mod.teardown(rt)
            else:
                dsh_repo = Path("/root/agent-framework-dsh")
                if not (dsh_repo / "scripts/boot.mjs").exists():
                    raise RuntimeError(f"dsh 仓库不存在: {dsh_repo}")
                env = dict(os.environ, LLM_BASE_URL=f"http://127.0.0.1:{llm_p}/cache/v1",
                           LLM_API_KEY="probe", LLM_MODEL_ID=model,
                           CHECKPOINT_JDBC_URL=f"jdbc:mysql://127.0.0.1:13306/{db}",
                           CHECKPOINT_USERNAME="e2e", CHECKPOINT_PASSWORD="e2e-pass",
                           AGENT_CONFIG_DIR="/tmp/eval-compare/dsh/agent-config",
                           AGENT_WORKSPACE_DIR="/tmp/eval-compare/dsh/workspace",
                           SERVER_PORT=str(port))
                log = open(DATA / f"{key}.log", "w")
                proc = subprocess.Popen(["node", "scripts/boot.mjs"], cwd=dsh_repo, env=env,
                                        stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                time.sleep(6)
                try:
                    drive(f"http://127.0.0.1:{port}", turns)
                finally:
                    os.killpg(os.getpgid(proc.pid), 9)
            results[key] = summarize(parse_ns("cache"))
            print(f"[cache-probe] {key}: 主链 {results[key]['main_calls']} 次")
    finally:
        collector.terminate()

    out = Path(args.out) if args.out else EVAL_DIR / "reports" / "cache-ratio" / "data.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"turns": turns, "results": results}, ensure_ascii=False, indent=1),
                   encoding="utf-8")
    (out.parent / "report.md").write_text(render(results, turns), encoding="utf-8")
    print(f"[cache-probe] 产物: {out.parent}/report.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
