#!/usr/bin/env python3
"""AF（Java/AgentScope Harness）vs dsh（DeepSeek Harness 平行运行时）全功能对比评测驱动。

同一评测面，仅运行时不同：
  - 同一 OAF 包（provision/templates/eval-agent + mock MCP 目录 + echo 插件，assemble 产物）
  - 同一 mock MCP（:18082，list_services/get_service_status/mock_fail_tool/publish_service[ask]）
  - 同一 LLM 上游（.env.secrets 的 EVAL_RECORD_LLM_*，OpenRouter stealth/space-bunny-alpha）
  - 同一用例库（bench/eval/cases，9 用例全矩阵）+ 同一确定性检查器 + 同一 judge（mimo）

产出：reports/compare-af-vs-dsh/{report.md, data.json}（完成度 / token 消耗 / 耗时 / RCA）。

用法：python3 bench/eval/tests/compare_af_dsh.py [--work /tmp/eval-compare] [--repeat 1]
凭据：.env.secrets（EVAL_RECORD_LLM_* 录制上游 / EVAL_JUDGE_LLM_* judge）。
"""

import argparse
import json
import os
import re
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

TESTS_DIR = Path(__file__).resolve().parent
EVAL_DIR = TESTS_DIR.parent
REPO_DIR = EVAL_DIR.parent.parent  # agent-framework/（与 flywheel.py 同口径）
sys.path.insert(0, str(EVAL_DIR))

from provision import instance as inst  # noqa: E402
from provision import oaf as oaf_mod  # noqa: E402
from provision.needs import _MOCK_TOOLS, env_tags  # noqa: E402

WORK = Path("/tmp/eval-compare")
REPORT_DIR = EVAL_DIR / "reports" / "compare-af-vs-dsh"
RUNTIMES = [
    {"key": "af", "title": "agent-framework（Java/AgentScope Harness）"},
    {"key": "dsh", "title": "agent-framework-dsh（Node/DeepSeek Harness）"},
]


def load_env_secrets() -> None:
    f = REPO_DIR.parent / ".env.secrets"
    if f.exists():
        for line in f.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip())
    # judge 别名（与 e2e 同规则）
    for dst, src in [("EVAL_LLM_BASE_URL", "EVAL_JUDGE_LLM_BASE_URL"),
                     ("EVAL_LLM_API_KEY", "EVAL_JUDGE_LLM_API_KEY"),
                     ("EVAL_LLM_MODEL", "EVAL_JUDGE_LLM_MODEL")]:
        if not os.environ.get(dst) and os.environ.get(src):
            os.environ[dst] = os.environ[src]


def full_matrix_spec() -> dict[str, Any]:
    """全能力矩阵 env_spec：插件 + mock MCP（含 ask）+ 会话模型探针。"""
    return {"since": "compare-matrix", "env_needs": {
        "plugins": [{"name": "echo-tool", "src": "e2e/plugin-echo",
                     "tools": ["echo_query", "smoke_config"], "reason": "全功能对比矩阵"}],
        "mock_mcp": {"enabled": True,
                     "tools": [t["name"] for t in _MOCK_TOOLS],
                     "ask_tools": [t["name"] for t in _MOCK_TOOLS if t["permission"] == "ask"]},
        "reload_probe": False, "session_model_probe": True,
    }}


EVAL_MOCK_PORT = int(os.environ.get("EVAL_COMPARE_MOCK_PORT", "18085"))


def start_eval_mock(runtime_dir: Path) -> subprocess.Popen:
    """拉起评测专用 mock MCP（独立端口，避免与遗留 bench mock-mcp:18082 冲突）。

    start_mock 的探活语义是「端口通即成功」，端口被占时会静默错绑——这里显式
    校验进程存活 + 目录内容来自本目录 catalog。"""
    log = open(runtime_dir / "mock-mcp.log", "w")
    proc = subprocess.Popen(
        [sys.executable, str(EVAL_DIR / "mock" / "mcp_server.py"),
         "--catalog", str(runtime_dir / "mock-catalog.json"),
         "--port", str(EVAL_MOCK_PORT), "--log", str(runtime_dir / "mock-mcp.jsonl")],
        stdout=log, stderr=subprocess.STDOUT)
    time.sleep(1.0)
    if proc.poll() is not None:
        raise RuntimeError(f"评测 mock MCP 启动失败，日志: {(runtime_dir / 'mock-mcp.log').read_text()[-400:]}")
    wait_http(f"http://127.0.0.1:{EVAL_MOCK_PORT}/health", "eval mock MCP", 20)
    return proc


def wait_http(url: str, name: str, timeout_s: float = 180) -> None:
    import httpx
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            if httpx.get(url, timeout=3).status_code == 200:
                return
        except Exception:
            time.sleep(2)
    raise RuntimeError(f"{name} 健康检查超时: {url}")


def wipe_or_create_db(db_name: str) -> None:
    """重置运行时专属数据库：录制/回放/对比都要求两侧状态同源为空，且两框架的
    session_user 表结构不兼容（AF Flyway 无默认值 vs dsh 依赖列默认值），必须分库。
    e2e 用户默认只授权了 agent_framework_e2e，新库需显式 GRANT。"""
    subprocess.run(["docker", "exec", "e2e-mysql", "mysql", "-uroot", "-pe2e-root", "-e",
                    f"DROP DATABASE IF EXISTS {db_name}; CREATE DATABASE {db_name};"
                    f"GRANT ALL PRIVILEGES ON {db_name}.* TO 'e2e'@'%'; FLUSH PRIVILEGES;"],
                   check=True, capture_output=True, timeout=30)


def kill_stale_runtime(port: int) -> None:
    """清掉上轮遗留的运行时进程（占用端口会让新实例启动失败、评测面打到僵尸进程）。"""
    subprocess.run(["pkill", "-f", "scripts/boot.mjs"], capture_output=True)
    subprocess.run(["pkill", "-f", "dsh --profile oaf-web"], capture_output=True)
    for _ in range(20):
        if subprocess.run(["bash", "-c", f"! ss -tln | grep -q ':{port} '"],
                          capture_output=True).returncode == 0:
            return
        time.sleep(0.5)
    raise RuntimeError(f"端口 {port} 仍被占用（遗留运行时清理失败）")


def start_dsh(runtime_dir: Path, llm: dict[str, str], port: int, db_name: str) -> subprocess.Popen:
    """dsh 运行时（node scripts/boot.mjs）：与 AF 同一 env 契约。"""
    dsh_repo = Path("/root/agent-framework-dsh")
    if not (dsh_repo / "scripts" / "boot.mjs").exists():
        raise RuntimeError(f"dsh 仓库不存在: {dsh_repo}")
    env = dict(os.environ,
               LLM_BASE_URL=llm["base_url"], LLM_API_KEY=llm["api_key"], LLM_MODEL_ID=llm["model"],
               CHECKPOINT_JDBC_URL=f"jdbc:mysql://127.0.0.1:13306/{db_name}",
               CHECKPOINT_USERNAME=inst.MYSQL_USER, CHECKPOINT_PASSWORD=inst.MYSQL_PASS,
               AGENT_CONFIG_DIR=str(runtime_dir / "agent-config"),
               AGENT_WORKSPACE_DIR=str(runtime_dir / "workspace"),
               SERVER_HOST="127.0.0.1", SERVER_PORT=str(port),
               FILE_STORAGE_TYPE="local", FILE_STORAGE_LOCAL_DIR=str(runtime_dir / "files"),
               DSH_HOME_DIR=".dsh-home")
    log = open(runtime_dir / "dsh.log", "w")
    proc = subprocess.Popen(["node", "scripts/boot.mjs"], cwd=dsh_repo, env=env,
                            stdout=log, stderr=subprocess.STDOUT,
                            start_new_session=True)  # 独立进程组：收尾 killpg 连 dsh 子进程一起杀
    try:
        wait_http(f"http://127.0.0.1:{port}/health", "dsh runtime")
    except RuntimeError:
        os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        raise
    return proc


def latest_task_dir() -> Path:
    cands = sorted((EVAL_DIR / "reports").glob("*-trend-*"), key=lambda p: p.stat().st_mtime)
    if not cands:
        raise RuntimeError("flywheel run 未产出任务目录")
    return cands[-1]


def run_flywheel(base_url: str, repeat: int) -> tuple[Path, int]:
    """跑一圈飞轮（用例库全量 + judge），返回 (任务目录, exit code)。"""
    env = dict(os.environ)
    cmd = [sys.executable, "bench/eval/flywheel.py", "run",
           "--base-url", base_url, "--repeat", str(repeat), "--workers", "1", "--judge"]
    before = {p.name for p in (EVAL_DIR / "reports").glob("*-trend-*")}
    proc = subprocess.run(cmd, cwd=REPO_DIR, env=env, capture_output=True, text=True, timeout=3600)
    (WORK / "flywheel-last.log").write_text(
        f"exit={proc.returncode}\n{proc.stdout}\n---stderr---\n{proc.stderr}", encoding="utf-8")
    print(proc.stdout[-1500:])
    after = {p.name for p in (EVAL_DIR / "reports").glob("*-trend-*")}
    new = after - before
    task_dir = EVAL_DIR / "reports" / sorted(new)[-1] if new else latest_task_dir()
    return task_dir, proc.returncode


def collect(task_dir: Path) -> dict[str, Any]:
    """汇总任务目录：逐用例状态/token/耗时 + 汇总 + judge + RCA。"""
    rows = [json.loads(l) for l in (task_dir / "summary.jsonl").read_text(encoding="utf-8").splitlines()
            if l.strip()]
    task = json.loads((task_dir / "task.json").read_text(encoding="utf-8"))
    # 跳过归因：run_suite 的 skipped 不落 summary.jsonl/task.json，从报告跳过节解析
    skipped: list[dict[str, str]] = []
    report_md = (task_dir / "report.md").read_text(encoding="utf-8")
    for m in re.finditer(r"- (case_[\w-]+): (.+)", report_md.split("## 跳过用例")[-1].split("\n## ")[0]):
        skipped.append({"case_id": m.group(1), "reason": m.group(2)[:160]})
    by_case: dict[str, dict[str, Any]] = {}
    for r in rows:
        c = by_case.setdefault(r["case_id"], {"repeats": 0, "input_tokens": 0, "output_tokens": 0,
                                              "total_tokens": 0, "duration_ms": 0})
        c["repeats"] += 1
        for k in ("input_tokens", "output_tokens", "duration_ms"):
            c[k] += r.get(k) or 0
        c["total_tokens"] = c["input_tokens"] + c["output_tokens"]
        c.setdefault("status", r["status"])
        c["status"] = "pass" if (r["status"] == "success" and r["checks_passed"]) else \
                      ("skip" if r["status"] == "skipped" else "fail")
        c.setdefault("skip_kind", r.get("kind") or "")
    return {
        "task_id": task_dir.name, "commit": task.get("commit"),
        "skipped": skipped,
        "contract": task.get("contract"),
        "pass_rate": task.get("pass_rate"), "total_score": task.get("total_score"),
        "judge_model": task.get("judge_model"),
        "analyses": task.get("analyses", []),
        "cases": by_case,
        "case_count": len(by_case),
    }


def verdict_matrix(af: dict[str, Any], dsh: dict[str, Any]) -> list[dict[str, Any]]:
    """逐用例对比行：状态/判定/token 差异。"""
    out = []
    for cid in sorted(set(af["cases"]) | set(dsh["cases"])):
        a = af["cases"].get(cid, {"status": "missing", "total_tokens": 0, "duration_ms": 0})
        b = dsh["cases"].get(cid, {"status": "missing", "total_tokens": 0, "duration_ms": 0})
        if a["status"] == b["status"]:
            v = {"pass": "双方通过", "fail": "双方失败", "skip": "双方跳过", "missing": "缺失"}[a["status"]]
        elif a["status"] == "pass":
            v = "AF 独有通过"
        elif b["status"] == "pass":
            v = "dsh 独有通过"
        else:
            v = "结果分歧"
        out.append({"case_id": cid, "verdict": v,
                    "af": a, "dsh": b,
                    "token_delta": (b.get("total_tokens") or 0) - (a.get("total_tokens") or 0)})
    return out


# 逐项归因注解（轨迹证据；随用例库/运行时演进由 PR 维护）
RCA_ANNOTATIONS = {
    "case_hitl_publish_confirm_001": (
        "dsh：publish_service 被直接执行并返回结果（终答含 serviceId=3），全程无 permission_ask 帧、"
        "无 confirm 续段（AGENT_END 1 vs 期望 2）——ask 权限未拦截，HITL 确认流未触发。"
        "dsh 仓库自测的 ask-deny 场景可过，但对本 eval 的 mock MCP ask 目录（permissions.tools 声明）"
        "未生效，属权限桥接缺口。"),
    "case_model_switch_unknown_001": (
        "dsh：未知模型以 HTTP 400 {error:unknown_model} 拒绝，而非协议内的 SSE error 帧"
        "（期望 error=1 且文本含模型名）。行为语义等价（都拒绝）但协议形态不同——dsh 设计边界："
        "模型托管面在 M3（现仅接受系统模型）。"),
    "case_present_url_whitelist_001": (
        "dsh：present_url 对非白名单 URL 连续报错（模型自述调用失败），但 file_ready 帧仍发出 3 次"
        "（期望 0）——交付登记未按工具结果失败而抑制，白名单拒绝语义与 AF 不一致。"),
    "case_tool_plugin_echo_001": (
        "dsh：缺失（能力门禁跳过）——Java 工具插件（echo_query/smoke_config jar）无对应加载机制，"
        "属 dsh 能力边界（M1+ 路线），非协议缺陷。"),
}

CAVEATS = [
    "judge 均分口径：error 断言型用例（model_switch_unknown）终答为空 → judge 打 0 拉低均值，"
    "两侧同口径仍可比，但均分不宜直接解读为「回答质量差 5 个点」。",
    "token 结构性差异：AF 每轮注入完整 system prompt（工具目录+技能段，~7.5K tokens/轮），"
    "dsh 极简注入（几百 tokens）——这是提示词组装策略差异，同时解释 input 量级差与"
    "「AF 首轮更贵、dsh 更省」的形态；输出 token dsh 略高（dsh 计入 reasoning tokens，AF 不计）。",
    "dsh 失败三例中两例（HITL 拦截、白名单抑制）是行为语义缺口，一例（HTTP 400 vs error 帧）"
    "是协议形态差异——修复难度与性质不同，建议 dsh 侧按此排序。",
    "运行时版本：AF=主仓 558adec4 构建；dsh=agent-framework-dsh 85cc392（M0+oaf-loader/tools/HITL 桥）。",
]


def render_report(spec: dict[str, Any], af: dict[str, Any], dsh: dict[str, Any],
                  rows: list[dict[str, Any]], llm_desc: str) -> str:
    def tot(d: dict[str, Any], key: str) -> float:
        return sum(c.get(key) or 0 for c in d["cases"].values())

    def failed_cases(d: dict[str, Any]) -> list[str]:
        return sorted(cid for cid, c in d["cases"].items() if c["status"] == "fail")

    def skipped_cases(d: dict[str, Any]) -> list[str]:
        return sorted(cid for cid, c in d["cases"].items() if c["status"] == "skip")

    lines = [
        "# AF vs dsh 全功能对比评测报告",
        "",
        f"- 生成时间：{time.strftime('%Y-%m-%d %H:%M:%S %z')}",
        f"- 评测面：同一 OAF 包（eval-agent 基座 + mock MCP{'+ echo 插件' if spec['env_needs']['plugins'] else ''}）、"
        f"同一 LLM 上游（{llm_desc}）、同一用例库（{af['case_count']} 用例）、同一确定性检查器、同一 judge（{af.get('judge_model') or '-'}）",
        f"- 契约版本：AF={af.get('contract')} dsh={dsh.get('contract')}（frame-mapping.json，两侧一致才可比）",
        f"- AF 任务：`{af['task_id']}`（commit {af.get('commit')}）",
        f"- dsh 任务：`{dsh['task_id']}`（commit {dsh.get('commit')}）",
        "",
        "## 1. 结论速览",
        "",
        "| 维度 | agent-framework (Java) | agent-framework-dsh (Node) | 差值（dsh−AF） |",
        "|---|---|---|---|",
        f"| 任务完成度（确定性检查通过率） | {af['pass_rate']:.0%} | {dsh['pass_rate']:.0%} | {dsh['pass_rate']-af['pass_rate']:+.0%} |",
        f"| judge 语义分（advisory） | {af['total_score'] if af['total_score'] is not None else '-'} | {dsh['total_score'] if dsh['total_score'] is not None else '-'} | - |",
        f"| input tokens | {tot(af, 'input_tokens'):,} | {tot(dsh, 'input_tokens'):,} | {tot(dsh, 'input_tokens')-tot(af, 'input_tokens'):+,} |",
        f"| output tokens | {tot(af, 'output_tokens'):,} | {tot(dsh, 'output_tokens'):,} | {tot(dsh, 'output_tokens')-tot(af, 'output_tokens'):+,} |",
        f"| total tokens | {tot(af, 'total_tokens'):,} | {tot(dsh, 'total_tokens'):,} | {tot(dsh, 'total_tokens')-tot(af, 'total_tokens'):+,} |",
        f"| 端到端耗时（用例内） | {tot(af, 'duration_ms'):,} ms | {tot(dsh, 'duration_ms'):,} ms | {tot(dsh, 'duration_ms')-tot(af, 'duration_ms'):+,} ms |",
        "",
        "## 2. 逐用例对比",
        "",
        "| 用例 | 判定 | AF 状态 | dsh 状态 | AF tokens | dsh tokens | Δtokens | AF 耗时 | dsh 耗时 |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        lines.append(
            f"| {r['case_id']} | {r['verdict']} | {r['af']['status']} | {r['dsh']['status']} "
            f"| {r['af'].get('total_tokens', 0):,} | {r['dsh'].get('total_tokens', 0):,} "
            f"| {r['token_delta']:+,} | {r['af'].get('duration_ms', 0)}ms | {r['dsh'].get('duration_ms', 0)}ms |")
    lines += [
        "",
        "## 3. 失败与跳过归因",
        "",
        f"- AF 失败：{failed_cases(af) or '无'}",
        f"- dsh 失败：{failed_cases(dsh) or '无'}",
        f"- AF 跳过：{skipped_cases(af) or '无'}（kind 见 data.json）",
        f"- dsh 跳过：{skipped_cases(dsh) or '无'}",
        "",
        "### 3.1 逐项归因（轨迹证据）",
        "",
    ]
    for cid in sorted(set(failed_cases(af) + failed_cases(dsh)
                          + skipped_cases(af) + skipped_cases(dsh))):
        note = RCA_ANNOTATIONS.get(cid)
        lines.append(f"- **{cid}**：{note or '（无注解，见 RCA 初筛与 trace）'}")
    lines += [
        "",
        "### 3.2 RCA（规则初筛）",
        "",
    ]
    for name, d in (("AF", af), ("dsh", dsh)):
        for a in d.get("analyses") or []:
            lines.append(f"- [{name}] {a.get('case_id')}: {a.get('category')} — {a.get('detail', '')[:120]}")
    if not (af.get("analyses") or dsh.get("analyses")):
        lines.append("- （无失败项，无 RCA 条目）")
    lines += [
        "",
        "## 4. 方法与口径",
        "",
        "- 完成度 = 确定性检查通过率（帧词表/工具调用/错误断言，零 LLM）——可复现的门禁口径；",
        "  judge 语义分只作参考（advisory，永不阻断）。",
        "- token 消耗 = 用例轨迹中 MODEL_CALL_END 的 usage 累计（input+output，全部由真实 LLM 上游产生）。",
        "- 两侧运行时使用同一 OAF 包组装、同一 mock MCP 实例、同一 LLM 上游与超参（模型自带推理行为一致），",
        "  差异仅来自运行时本身（提示词组装、工具面、协议实现）。",
        "- repeat=1 单样本：通过率不含统计波动控制，token 对比为点估计；需方差数据可加 repeat 重跑。",
        "",
        "### 4.1 口径补充与注意事项",
        "",
    ]
    lines += [f"- {c}" for c in CAVEATS]
    lines += [
        "",
        "## 5. 数据文件",
        "",
        "- `data.json`：本报告全部结构化数据（逐用例 token/耗时/状态、RCA、任务元信息）。",
    ]
    return "\n".join(lines) + "\n"


def main() -> int:
    global WORK
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default="/tmp/eval-compare")
    ap.add_argument("--repeat", type=int, default=1)
    args = ap.parse_args()
    WORK = Path(args.work)
    WORK.mkdir(parents=True, exist_ok=True)
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    load_env_secrets()

    spec = full_matrix_spec()
    tags = env_tags(spec)
    print(f"[compare] env_tags={sorted(tags)}")

    # 共享上游：优先 judge 端点（mimo，用户指定评测模型、工具调用已验证）——
    # OpenRouter stealth/space-bunny-alpha 已下线（404 No endpoints），不可再用
    base = os.environ.get("EVAL_LLM_BASE_URL") or os.environ["EVAL_RECORD_LLM_BASE_URL"]
    key = os.environ.get("EVAL_LLM_API_KEY") or os.environ["EVAL_RECORD_LLM_API_KEY"]
    model = os.environ.get("EVAL_LLM_MODEL") or os.environ["EVAL_RECORD_LLM_MODEL"]
    llm = {"base_url": base, "api_key": key, "model": model}
    llm_desc = f"{llm['base_url']} / {llm['model']}"

    inst.ensure_infra(REPO_DIR)
    # mock MCP 共享实例（catalog 由 AF 侧组装产出，两侧 assemble 的目录内容同源一致）
    af_dir = WORK / "af"
    oaf_mod.assemble(spec, af_dir / "agent-config", af_dir, REPO_DIR, f"http://127.0.0.1:{EVAL_MOCK_PORT}/mcp")
    mock_proc = start_eval_mock(af_dir)
    print(f"[compare] mock MCP pid={mock_proc.pid} port={EVAL_MOCK_PORT}")

    results: dict[str, dict[str, Any]] = {}
    try:
        for rt in RUNTIMES:
            key, title = rt["key"], rt["title"]
            rt_dir = WORK / key
            print(f"\n[compare] ===== {title} =====")
            # 每个运行时独立 assemble（同源 spec；workspace/files 目录隔离）
            (rt_dir / "agent-config").mkdir(parents=True, exist_ok=True)
            oaf_mod.assemble(spec, rt_dir / "agent-config", rt_dir, REPO_DIR,
                             f"http://127.0.0.1:{EVAL_MOCK_PORT}/mcp")
            # 飞轮门禁：写 state.json（base_url + env_tags）
            runtime_eval = EVAL_DIR / ".runtime-eval"
            runtime_eval.mkdir(exist_ok=True)
            base_url = f"http://127.0.0.1:{18100 if key == 'af' else 18110}"
            (runtime_eval / "state.json").write_text(json.dumps(
                {"base_url": base_url, "env_tags": sorted(tags)}, ensure_ascii=False), encoding="utf-8")

            proc = None
            try:
                if key == "af":
                    kill_stale_runtime(18100)
                    wipe_or_create_db("agent_framework_e2e")   # AF Flyway 建表权
                    base_url = inst.start_instance(REPO_DIR, rt_dir, llm)
                    inst.wait_health(base_url)
                else:
                    kill_stale_runtime(18110)
                    wipe_or_create_db("eval_compare_dsh")      # dsh 迁移器建表权
                    proc = start_dsh(rt_dir, llm, 18110, "eval_compare_dsh")
                    base_url = "http://127.0.0.1:18110"
                print(f"[compare] {key} 就绪: {base_url}")
                task_dir, code = run_flywheel(base_url, args.repeat)
                results[key] = collect(task_dir)
                results[key]["exit_code"] = code
                print(f"[compare] {key} 完成: pass_rate={results[key]['pass_rate']:.0%} "
                      f"task={task_dir.name}")
            finally:
                if key == "af":
                    inst.teardown(rt_dir)
                elif proc is not None:
                    try:
                        os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
                    except (ProcessLookupError, PermissionError):
                        proc.terminate()
    finally:
        mock_proc.terminate()

    if len(results) != 2:
        print(f"[compare][FAIL] 运行时未全部完成: {list(results)}", file=sys.stderr)
        return 2

    rows = verdict_matrix(results["af"], results["dsh"])
    data = {"llm": llm_desc, "env_tags": sorted(tags), "rows": rows,
            "af": results["af"], "dsh": results["dsh"]}
    (REPORT_DIR / "data.json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    report = render_report(spec, results["af"], results["dsh"], rows, llm_desc)
    (REPORT_DIR / "report.md").write_text(report, encoding="utf-8")
    print(f"\n[compare] 报告: {REPORT_DIR / 'report.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
