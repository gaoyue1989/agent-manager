"""eval-studio：离线评测服务（设计 §6）——目标档案 / 数据包 / 用例 / run 编排 / 报告。

单进程 FastAPI + SQLite(元数据) + 文件库（evalpack/run 产物）；run 由 worker 线程串行
执行（replay = 本地拉起 replay-llm + 可选内置 stub 沙盒；live = 直打档案 base_url）。

环境变量：
  STUDIO_DATA_DIR     数据目录（默认 ./data）：studio.db / packs/ / runs/
  STUDIO_BENCH_DIR    bench 目录（默认 repo 内 bench/）：定位 eval 代码与 stub-agent
  STUDIO_TOKEN        访问 token（未设=不启用鉴权；STUDIO_SKIP_AUTH=1 显式跳过；设 token 后 /api/* 全保护，/healthz 与页面豁免）
"""

import asyncio
import json
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
import zipfile
from pathlib import Path
from typing import Any

import httpx
from fastapi import Depends, FastAPI, File, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse

BENCH_DIR = Path(os.environ.get("STUDIO_BENCH_DIR", Path(__file__).resolve().parent.parent.parent))
DATA_DIR = Path(os.environ.get("STUDIO_DATA_DIR", Path(__file__).resolve().parent / "data"))
EVAL_DIR = BENCH_DIR / "eval"
sys.path.insert(0, str(EVAL_DIR))

from replay import runner as run_mod  # noqa: E402
from replay.packager import verify_checksums  # noqa: E402
from app import store  # noqa: E402

_SKIP_AUTH = os.environ.get("STUDIO_SKIP_AUTH") == "1"
_TOKEN = os.environ.get("STUDIO_TOKEN", "")
_WORKER_STARTED = False


def _auth(request: Request) -> None:
    """全局鉴权依赖（issue #97：原 _auth 定义后从未挂到任何路由，STUDIO_TOKEN 完全无效）。

    语义保持：未设 STUDIO_TOKEN 或 STUDIO_SKIP_AUTH=1 时放行（存量本地用法零破坏）；
    设 token 后受保护面为 /api/*——/healthz（探活/Dockerfile HEALTHCHECK）与 /
    （前端页面：401 后浏览器无 UI 可输 token）必须匿名可达，其余缺失/错误的 Bearer 一律 401。
    注意：全局 dependencies 只覆盖 APIRoute，/docs 等自助文档路由会被绕过——
    构造 FastAPI 时已显式关闭（docs_url/redoc_url/openapi_url=None），豁免清单仅上述两条。
    """
    if _SKIP_AUTH or not _TOKEN:
        return
    if request.url.path in ("/healthz", "/"):
        return
    if request.headers.get("authorization") != f"Bearer {_TOKEN}":
        raise HTTPException(401, "token 缺失或错误")


# 自助文档路由显式关闭：FastAPI 全局 dependencies 只作用于 APIRoute，
# setup() 注册的 /docs /redoc /openapi.json 会绕过 _auth 匿名可见完整 API 契约（issue #97 评审）
app = FastAPI(title="eval-studio", version="0.1.0", dependencies=[Depends(_auth)],
              docs_url=None, redoc_url=None, openapi_url=None)


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def resolve_secrets(spec: dict[str, Any]) -> dict[str, Any]:
    """凭据引用解析：'env:VAR' → 进程环境注入（设计 §6.2：档案不落明文）。"""
    out = json.loads(json.dumps(spec))
    for k, v in (out.get("secrets_ref") or {}).items():
        if isinstance(v, str) and v.startswith("env:"):
            out.setdefault("_resolved", {})[k] = os.environ.get(v[4:], "")
    return out


def judge_cfg_from(spec: dict[str, Any] | None, use_judge: bool) -> dict[str, str] | None:
    """judge 配置：档案 judge 段优先，缺省回落 EVAL_LLM_* 环境变量。"""
    if not use_judge:
        return None
    j = (spec or {}).get("judge") or {}
    cfg = {
        "base_url": (j.get("base_url") or os.environ.get("EVAL_LLM_BASE_URL", "")).rstrip("/"),
        "api_key": j.get("api_key") or os.environ.get("EVAL_LLM_API_KEY", ""),
        "model": j.get("model") or os.environ.get("EVAL_LLM_MODEL", ""),
    }
    if j.get("api_key", "").startswith("env:"):
        cfg["api_key"] = os.environ.get(j["api_key"][4:], "")
    return cfg if all(cfg.values()) else None


# ---------------------------------------------------------------------------
# run worker（单线程串行消费 queued run）
# ---------------------------------------------------------------------------

def _spawn_stub_agent(port: int, llm_base: str, extra_env: dict[str, str] | None = None) -> subprocess.Popen:
    stub = EVAL_DIR / "tests" / "stub-agent.mjs"
    node = shutil.which("node") or "node"
    env = dict(os.environ, STUB_PORT=str(port), STUB_LLM_BASE_URL=llm_base,
               STUB_LLM_API_KEY="studio-stub", **(extra_env or {}))
    return subprocess.Popen([node, str(stub), str(port)], env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def _wait_health(url: str, timeout_s: float = 15.0) -> None:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            if httpx.get(url, timeout=1.0).status_code == 200:
                return
        except httpx.HTTPError:
            time.sleep(0.2)
    raise RuntimeError(f"健康检查超时: {url}")


def _exec_run(run: dict[str, Any]) -> None:
    """执行单个 run（worker 线程内；replay = replay-llm + 可选内置 stub 沙盒）。"""
    spec = resolve_secrets(store.get_profile(run["profile_id"])["spec"]) if run["profile_id"] else {}
    params = run["params"]
    pack_dir = str(DATA_DIR / "packs" / params["pack_id"]) if params.get("pack_id") else None
    out_dir = run["out_dir"]
    use_judge = bool(params.get("judge", True))
    judge_cfg = judge_cfg_from(spec, use_judge)

    if run["type"] == "replay":
        if not pack_dir:
            raise RuntimeError("replay run 需要 pack_id")
        proc = None
        stub = None
        try:
            proc, llm_port = run_mod.start_replay_llm(pack_dir)
            harness = (spec.get("replay") or {}).get("harness", "builtin-stub")
            if harness == "builtin-stub":
                # 开箱即用回放沙盒：内置 stub agent 以 replay-llm 为 LLM 上游；
                # replay.extra_env 可注入沙盒环境（如 STUB_MUTATE=1 模拟行为变更的被测版本）
                stub_port = free_port()
                stub = _spawn_stub_agent(stub_port, f"http://127.0.0.1:{llm_port}/v1",
                                         extra_env=(spec.get("replay") or {}).get("extra_env"))
                _wait_health(f"http://127.0.0.1:{stub_port}/healthz")
                base_url = f"http://127.0.0.1:{stub_port}"
            else:
                base_url = (spec.get("replay") or {}).get("base_url") or params.get("base_url")
                if not base_url:
                    raise RuntimeError("harness=external 需要 base_url（档案或 run 参数）")
            code = asyncio.run(run_mod.run_replay(
                pack_dir=pack_dir, out_dir=out_dir, base_url=base_url,
                mode=(spec.get("replay") or {}).get("strictness", "strict"),
                case_ids=params.get("case_ids"), judge_cfg=judge_cfg,
                replayer=(proc, llm_port)))
            store.update_run(run["run_id"], status="done" if code == 0 else "done_with_failures",
                             finished_at=time.strftime("%Y-%m-%dT%H:%M:%S"))
        finally:
            if stub:
                stub.terminate()
            if proc:
                proc.terminate()
    elif run["type"] == "live":
        base_url = (spec.get("live") or {}).get("base_url") or params.get("base_url")
        if not base_url:
            raise RuntimeError("live 档案缺少 live.base_url")
        code = asyncio.run(run_mod.run_live(
            base_url=base_url, out_dir=out_dir, pack_dir=pack_dir,
            case_ids=params.get("case_ids"), judge_cfg=judge_cfg,
            cases_dir=str(EVAL_DIR / "cases") if params.get("use_library_cases") else None,
            timeout_s=float((spec.get("live") or {}).get("timeout_s", 120))))
        store.update_run(run["run_id"], status="done" if code == 0 else "done_with_failures",
                         finished_at=time.strftime("%Y-%m-%dT%H:%M:%S"))
    else:
        raise RuntimeError(f"未知 run 类型: {run['type']}")

    report_path = Path(out_dir) / "report.json"
    if report_path.exists():
        store.update_run(run["run_id"],
                         report=json.loads(report_path.read_text(encoding="utf-8")))


def _worker_loop() -> None:
    while True:
        try:
            run = store.next_queued_run()
            if run is None:
                time.sleep(1.0)
                continue
            store.update_run(run["run_id"], status="running")
            try:
                _exec_run(run)
            except Exception as e:  # run 失败不杀 worker
                store.update_run(run["run_id"], status="error", error=str(e)[:800],
                                 finished_at=time.strftime("%Y-%m-%dT%H:%M:%S"))
        except Exception as e:  # worker 兜底：任何异常都继续轮询
            print(f"[worker] 轮询异常: {e}", file=sys.stderr)
            time.sleep(2.0)


@app.on_event("startup")
def _startup() -> None:
    global _WORKER_STARTED
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    (DATA_DIR / "packs").mkdir(exist_ok=True)
    (DATA_DIR / "runs").mkdir(exist_ok=True)
    store.init(str(DATA_DIR / "studio.db"))
    if not _WORKER_STARTED:
        threading.Thread(target=_worker_loop, daemon=True).start()
        _WORKER_STARTED = True


# ---------------------------------------------------------------------------
# API
# ---------------------------------------------------------------------------

@app.get("/healthz")
def healthz():
    return {"ok": True, "data_dir": str(DATA_DIR)}


@app.get("/", response_class=HTMLResponse)
def index():
    static = Path(__file__).resolve().parent.parent / "static" / "index.html"
    return HTMLResponse(static.read_text(encoding="utf-8"))


# ---- 目标档案 ----

@app.get("/api/profiles")
def api_profiles():
    return {"profiles": store.list_profiles(), "active": store.get_setting("active_profile") or None}


@app.post("/api/profiles")
def api_profile_create(spec: dict[str, Any]):
    pid = spec.get("id")
    if not pid or not pid.startswith("prof-"):
        raise HTTPException(400, "id 必填且以 prof- 开头")
    if store.get_profile(pid):
        raise HTTPException(409, f"档案已存在: {pid}")
    store.put_profile(pid, spec.get("name", pid), spec)
    return store.get_profile(pid)


@app.put("/api/profiles/{pid}")
def api_profile_update(pid: str, spec: dict[str, Any]):
    if not store.get_profile(pid):
        raise HTTPException(404, "档案不存在")
    store.put_profile(pid, spec.get("name", pid), spec)
    return store.get_profile(pid)


@app.delete("/api/profiles/{pid}")
def api_profile_delete(pid: str):
    store.delete_profile(pid)
    return {"deleted": True}


@app.post("/api/profiles/{pid}/activate")
def api_profile_activate(pid: str):
    if not store.get_profile(pid):
        raise HTTPException(404, "档案不存在")
    store.set_setting("active_profile", pid)
    return {"active": pid}


@app.get("/api/profiles/{pid}/export")
def api_profile_export(pid: str):
    prof = store.get_profile(pid)
    if not prof:
        raise HTTPException(404, "档案不存在")
    # 导出剥离敏感值（api_key 等），团队共享安全
    spec = json.loads(json.dumps(prof["spec"]))
    spec.pop("secrets_ref", None)
    if "api_key" in (spec.get("judge") or {}):
        spec["judge"]["api_key"] = ""
    return {"id": prof["id"], "name": prof["name"], "spec": spec}


@app.post("/api/profiles/{pid}/precheck")
async def api_profile_precheck(pid: str):
    prof = store.get_profile(pid)
    if not prof:
        raise HTTPException(404, "档案不存在")
    spec = resolve_secrets(prof["spec"])
    result: dict[str, Any] = {}
    if spec.get("target_mode") == "replay":
        # replay：pack 与框架引用存在性校验
        packs = {p["pack_id"] for p in store.list_packages()}
        result["pack_policy"] = {"ok": bool(packs), "detail": f"已有包 {len(packs)} 个"}
    else:
        url = (spec.get("live") or {}).get("base_url")
        if url:
            try:
                r = await httpx.AsyncClient(timeout=8).get(
                    f"{url.rstrip('/')}/.well-known/agent-card.json")
                result["live"] = {"ok": r.status_code < 500, "detail": f"agent-card HTTP {r.status_code}"}
            except httpx.HTTPError as e:
                result["live"] = {"ok": False, "detail": str(e)[:200]}
        j = judge_cfg_from(spec, True)
        result["judge"] = {"ok": bool(j), "detail": "已配置" if j else "未配置（EVAL_LLM_*）"}
    return result


# ---- 数据包 ----

@app.post("/api/packages")
async def api_package_upload(file: UploadFile = File(...)):
    tmp = DATA_DIR / "packs" / f".upload-{int(time.time()*1000)}.zip"
    tmp.write_bytes(await file.read())
    try:
        with zipfile.ZipFile(tmp) as zf:
            names = zf.namelist()
            if "manifest.json" not in names or "CHECKSUMS" not in names:
                raise HTTPException(400, "不是合法 evalpack（缺 manifest.json/CHECKSUMS）")
            manifest = json.loads(zf.read("manifest.json"))
            pack_id = manifest["pack_id"]
            target = DATA_DIR / "packs" / pack_id
            if target.exists():
                shutil.rmtree(target)
            zf.extractall(target)
    except zipfile.BadZipFile:
        raise HTTPException(400, "zip 损坏")
    finally:
        tmp.unlink(missing_ok=True)
    if not verify_checksums(str(target)):
        shutil.rmtree(target)
        raise HTTPException(400, "CHECKSUMS 校验失败（包被篡改或不完整）")
    store.put_package(pack_id, manifest, str(target))
    # 用例草稿登记（status=draft，待人审转正）
    for p in sorted((target / "cases-draft").glob("*.json")):
        case = json.loads(p.read_text(encoding="utf-8"))
        store.upsert_case(case, pack_id, "draft")
    return {"pack_id": pack_id, "sessions": len(manifest.get("sessions", [])),
            "stats": manifest.get("stats", {})}


@app.get("/api/packages")
def api_packages():
    return {"packages": store.list_packages()}


@app.get("/api/packages/{pack_id}")
def api_package_detail(pack_id: str):
    pkg = store.get_package(pack_id)
    if not pkg:
        raise HTTPException(404, "包不存在")
    sessions = []
    for f in sorted((Path(pkg["dir"]) / "sessions").glob("*.json")):
        s = json.loads(f.read_text(encoding="utf-8"))
        sessions.append({"sid": s["sid"], "confidence": s.get("confidence"), "turns": s.get("turns"),
                         "final_output": s.get("final_output"), "token_usage": s.get("token_usage")})
    return {"pack_id": pack_id, "manifest": pkg["manifest"], "sessions": sessions}


@app.get("/api/packages/{pack_id}/cases")
def api_package_cases(pack_id: str):
    pkg = store.get_package(pack_id)
    if not pkg:
        raise HTTPException(404, "包不存在")
    cases = [json.loads(p.read_text(encoding="utf-8"))
             for p in sorted((Path(pkg["dir"]) / "cases-draft").glob("*.json"))]
    return {"cases": cases}


@app.delete("/api/packages/{pack_id}")
def api_package_delete(pack_id: str):
    pkg = store.get_package(pack_id)
    if pkg:
        shutil.rmtree(pkg["dir"], ignore_errors=True)
        store.delete_package(pack_id)
    return {"deleted": True}


@app.post("/api/packs/from-collector")
def api_pack_from_collector(body: dict[str, Any]):
    """compose 工作站模式：collector 控制台「数据打包」转调本端点，直接读共享录制卷出包。"""
    src = os.environ.get("STUDIO_COLLECTOR_DATA", "")
    if not src or not Path(src).exists():
        raise HTTPException(400, "未配置 STUDIO_COLLECTOR_DATA（需以 compose 工作站模式共享 collector 录制卷）")
    from replay import packager
    ns = body.get("ns")
    pack_id = body.get("pack_id") or f"pk-{ns or 'all'}-{time.strftime('%Y%m%d-%H%M%S')}"
    out = packager.pack(collector_dir=src, out_dir=str(DATA_DIR / "packs" / pack_id), ns=ns)
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    store.put_package(pack_id, manifest, str(out))
    for p in sorted((out / "cases-draft").glob("*.json")):
        store.upsert_case(json.loads(p.read_text(encoding="utf-8")), pack_id, "draft")
    return {"pack_id": pack_id, "sessions": len(manifest.get("sessions", [])),
            "stats": manifest.get("stats", {})}


# ---- 用例 ----

@app.get("/api/cases")
def api_cases(status: str | None = None):
    return {"cases": store.list_cases(status)}


@app.post("/api/cases/promote")
def api_case_promote(body: dict[str, Any]):
    pack_id = body.get("pack_id")
    case_id = body.get("case_id")
    pkg = store.get_package(pack_id)
    if not pkg:
        raise HTTPException(404, "包不存在")
    src = Path(pkg["dir"]) / "cases-draft" / f"{body.get('sid') or ''}.json"
    case = None
    if src.exists():
        case = json.loads(src.read_text(encoding="utf-8"))
    else:
        for p in (Path(pkg["dir"]) / "cases-draft").glob("*.json"):
            c = json.loads(p.read_text(encoding="utf-8"))
            if c.get("case_id") == case_id:
                case = c
                break
    if not case:
        raise HTTPException(404, "用例草稿不存在")
    case.update(body.get("edits") or {})
    case["status"] = "active"
    store.upsert_case(case, pack_id, "active")
    return {"case": case}


# ---- runs ----

@app.post("/api/runs")
def api_run_create(body: dict[str, Any]):
    run_type = body.get("type")
    if run_type not in ("replay", "live"):
        raise HTTPException(400, "type 须为 replay|live")
    profile = store.get_profile(body.get("profile_id") or store.get_setting("active_profile") or "")
    if not profile:
        raise HTTPException(400, "profile_id 无效且无活跃档案")
    pack_id = body.get("pack_id")
    if pack_id and not store.get_package(pack_id):
        raise HTTPException(404, "包不存在")
    run_id = f"{run_type}-{time.strftime('%Y%m%d-%H%M%S')}-{int(time.time()*1000)%10000}"
    out_dir = str(DATA_DIR / "runs" / run_id)
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    store.create_run(run_id, run_type, profile["id"], pack_id, body, out_dir)
    return {"run_id": run_id, "status": "queued"}


@app.get("/api/runs")
def api_runs():
    return {"runs": store.list_runs()}


@app.get("/api/runs/{run_id}")
def api_run_detail(run_id: str):
    run = store.get_run(run_id)
    if not run:
        raise HTTPException(404, "run 不存在")
    return run


@app.get("/api/runs/{run_id}/report.json")
def api_run_report_json(run_id: str):
    run = store.get_run(run_id)
    if not run:
        raise HTTPException(404, "run 不存在")
    return JSONResponse(run["report"] or {})


@app.get("/api/runs/{run_id}/report.html")
def api_run_report_html(run_id: str):
    run = store.get_run(run_id)
    if not run:
        raise HTTPException(404, "run 不存在")
    path = Path(run["out_dir"]) / "report.html"
    if not path.exists():
        raise HTTPException(404, "报告未生成")
    return HTMLResponse(path.read_text(encoding="utf-8"))


@app.get("/api/runs/{run_id}/cases/{name}")
def api_run_case_file(run_id: str, name: str):
    run = store.get_run(run_id)
    if not run:
        raise HTTPException(404, "run 不存在")
    # 白名单防目录穿越
    if not (name.endswith(".trace.json") or name.endswith(".trajectory.json")):
        raise HTTPException(400, "仅允许 trace/trajectory 文件")
    path = (Path(run["out_dir"]) / "cases" / name).resolve()
    if not str(path).startswith(str((Path(run["out_dir"]) / "cases").resolve())) or not path.exists():
        raise HTTPException(404, "文件不存在")
    return FileResponse(path, media_type="application/json")


@app.get("/api/trends")
def api_trends(profile_id: str | None = None):
    runs = [r for r in store.list_runs(100) if r["report"]
            and (not profile_id or r["profile_id"] == profile_id)]
    return {"runs": [{
        "run_id": r["run_id"], "type": r["type"], "profile_id": r["profile_id"],
        "pack_id": r["pack_id"], "created_at": r["created_at"],
        "pass_rate": r["report"]["summary"]["pass_rate"],
        "score_avg": r["report"]["summary"].get("score_avg"),
        "drift_rate": r["report"]["summary"].get("drift_rate"),
    } for r in runs]}


# ---- 对比视图（M4 验收：同 pack 多版本对比产出回归结论） ----

def _verdict(sa: str, sb: str) -> str:
    if sa == "missing":
        return "new_in_b"
    if sb == "missing":
        return "removed_in_b"
    if sa == "passed" and sb != "passed":
        return "regression"
    if sa != "passed" and sb == "passed":
        return "improved"
    if sa == "passed" and sb == "passed":
        return "stable_pass"
    return "both_failed"


@app.get("/api/compare")
def api_compare(run_a: str, run_b: str):
    """同 pack 双 run 并排对比：逐用例 verdict（regression/improved/…）+ 检查项差异 + 汇总结论。"""
    ra, rb = store.get_run(run_a), store.get_run(run_b)
    if not ra or not rb:
        raise HTTPException(404, "run 不存在")
    if not ra["report"] or not rb["report"]:
        raise HTTPException(400, "所选 run 尚无报告")
    if ra["pack_id"] != rb["pack_id"]:
        raise HTTPException(400, f"仅支持同 pack 对比（{ra['pack_id']} vs {rb['pack_id']}）")

    rep_a, rep_b = ra["report"], rb["report"]
    cases_a = {c["case_id"]: c for c in rep_a["cases"]}
    cases_b = {c["case_id"]: c for c in rep_b["cases"]}
    rows: list[dict[str, Any]] = []
    counts: dict[str, int] = {}
    for cid in list(dict.fromkeys(list(cases_a) + list(cases_b))):
        ca, cb = cases_a.get(cid), cases_b.get(cid)
        sa = ca["status"] if ca else "missing"
        sb = cb["status"] if cb else "missing"
        verdict = _verdict(sa, sb)
        counts[verdict] = counts.get(verdict, 0) + 1

        def _score(c: dict[str, Any] | None) -> float | None:
            return ((c.get("scores") or {}).get("correctness") or {}).get("score") if c else None

        def _drift(c: dict[str, Any] | None) -> float | None:
            return (c.get("trajectory") or {}).get("drift_rate") if c else None

        checks_by_name_a = {ch["name"]: ch for ch in (ca.get("checks") or [])} if ca else {}
        checks_by_name_b = {ch["name"]: ch for ch in (cb.get("checks") or [])} if cb else {}
        checks_diff = []
        for name in sorted(set(checks_by_name_a) | set(checks_by_name_b)):
            pa = checks_by_name_a.get(name, {}).get("passed")
            pb = checks_by_name_b.get(name, {}).get("passed")
            if pa != pb:
                checks_diff.append({"name": name, "passed_a": pa, "passed_b": pb})

        rows.append({
            "case_id": cid, "verdict": verdict,
            "status_a": sa, "status_b": sb,
            "duration_a": ca.get("duration_ms") if ca else None,
            "duration_b": cb.get("duration_ms") if cb else None,
            "drift_a": _drift(ca), "drift_b": _drift(cb),
            "score_a": _score(ca), "score_b": _score(cb),
            "final_a": (ca.get("final_output") or "")[:200] if ca else None,
            "final_b": (cb.get("final_output") or "")[:200] if cb else None,
            "checks_diff": checks_diff,
        })

    s_a, s_b = rep_a["summary"], rep_b["summary"]
    regressions = counts.get("regression", 0)
    conclusion = {
        "regressions": regressions,
        "improvements": counts.get("improved", 0),
        "stable_pass": counts.get("stable_pass", 0),
        "both_failed": counts.get("both_failed", 0),
        "pass_rate_a": s_a["pass_rate"], "pass_rate_b": s_b["pass_rate"],
        "drift_a": s_a.get("drift_rate"), "drift_b": s_b.get("drift_rate"),
        "verdict": ("检出回归" if regressions else
                    "存在改进" if counts.get("improved") else
                    "行为一致（全部稳定通过）" if counts.get("stable_pass") and not counts.get("both_failed")
                    else "两侧均存在失败"),
    }
    return {
        "run_a": {"run_id": run_a, "created_at": ra["created_at"], "profile_id": ra["profile_id"],
                  "summary": s_a},
        "run_b": {"run_id": run_b, "created_at": rb["created_at"], "profile_id": rb["profile_id"],
                  "summary": s_b},
        "pack_id": ra["pack_id"], "cases": rows, "counts": counts, "conclusion": conclusion,
    }
