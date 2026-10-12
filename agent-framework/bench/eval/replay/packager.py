"""evalpack 打包器（设计 §3）：collector 录制数据 → 可回放数据包。

输入：
  - collector 数据目录 {collector}/{ns}/{llm|sandbox|mcp}/YYYYMMDD.jsonl（已含采集侧脱敏）
  - 可选 driver 轨迹捕获目录（sse_client 采集的 {sid}.json：turns 输入 + 事件帧）——
    提供时生成逐轮 inputs 与更准确的用例草稿；不提供时从录制 chunks 反推终答
输出 evalpack 目录契约（format_version 1，设计 §3.2）：
  manifest.json / sessions/{sid}.json / interactions/llm|sandbox|mcp/{id}.json
  /correlation.json / cases-draft/{sid}.json / CHECKSUMS（另产 zip 供 studio 上传）

会话关联（设计 §2.5 的采集侧强关联 + 指纹回退）：
  1. 交互带 X-Eval-Session（collector 已记录为 session 字段）→ 强归属，confidence=high
  2. 否则按「首条 user 消息归一化指纹 + 10 分钟时间窗」聚类 → confidence=medium
"""

import glob
import hashlib
import json
import re
import shutil
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from replay import normalize as nz
from replay.ids import validate_id

PACK_FORMAT_VERSION = 1
SESSION_GAP_MS = 10 * 60 * 1000  # 指纹聚类的会话切分时间窗


def _read_jsonl(paths: list[str]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for p in sorted(paths):
        with open(p, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        out.append(json.loads(line))
                    except json.JSONDecodeError:
                        continue  # 坏行跳过（写入侧原子追加，不应出现）
    return out


def _parse_ts(ts: str | None) -> float:
    if not ts:
        return 0.0
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp() * 1000
    except ValueError:
        return 0.0


def final_text_from_chunks(chunks: list[str]) -> str:
    """从 SSE data 载荷原文数组反推拼接终答（OpenAI delta 协议）。"""
    parts: list[str] = []
    for c in chunks or []:
        if c == "[DONE]":
            continue
        try:
            j = json.loads(c)
        except json.JSONDecodeError:
            continue
        for ch in j.get("choices") or []:
            d = (ch.get("delta") or {}).get("content")
            if isinstance(d, str):
                parts.append(d)
            # 非流式 body 兜底
            msg = (ch.get("message") or {}).get("content")
            if isinstance(msg, str) and j.get("object") == "chat.completion":
                parts.append(msg)
    return "".join(parts)


def usage_from_chunks(chunks: list[str]) -> dict[str, int]:
    usage = {"input": 0, "output": 0, "total": 0}
    for c in chunks or []:
        try:
            j = json.loads(c)
        except json.JSONDecodeError:
            continue
        u = j.get("usage") or {}
        usage["input"] += int(u.get("prompt_tokens") or 0)
        usage["output"] += int(u.get("completion_tokens") or 0)
        usage["total"] += int(u.get("total_tokens") or 0)
    return usage


def _is_background(req: dict[str, Any] | None) -> bool:
    """背景调用判定（与 replay-llm 同规则，llm-server.mjs 已验证的识别口径）：
    ① 请求无 user 消息（title/compaction 旁路）；
    ② system 含 memory extraction 特征（记忆抽取调用，内容含会话上下文、逐次易变）。
    背景调用仍写入 interactions（replay-llm 播放期按背景合成应答），但不进会话主链、
    不生成用例草稿——否则真实 Agent 的标题/记忆调用会污染会话骨架与 A2 期望步数。
    """
    msgs = (req or {}).get("messages") or []
    if not any(m.get("role") == "user" for m in msgs):
        return True
    for m in msgs:
        c = str(m.get("content", "")).lower()
        if m.get("role") == "system" and ("memory extraction" in c or "会话标题生成助手" in c
                                          or "generate a concise title" in c):
            return True
    return False


def group_llm_sessions(interactions: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    """把 LLM 交互按会话分组：X-Eval-Session 强关联优先，指纹+时间窗回退。

    背景调用（无 user 消息）不参与分组。返回 {sid: {confidence, calls: [按时间升序]}}。
    """
    sessions: dict[str, dict[str, Any]] = {}
    leftovers = [i for i in interactions if not i.get("session") and not _is_background(i.get("request"))]
    for i in interactions:
        if i.get("session") and not _is_background(i.get("request")):
            sid = i["session"]
            g = sessions.setdefault(sid, {"confidence": "high", "calls": []})
            g["calls"].append(i)

    # 指纹回退：无 session 头的交互按「首条 user 归一化文本 + 时间窗」聚类
    leftovers.sort(key=lambda x: _parse_ts(x.get("ts_start")))
    clusters: list[dict[str, Any]] = []
    for i in leftovers:
        anchor = nz.first_user_text(i.get("request") or {})
        ts = _parse_ts(i.get("ts_start"))
        placed = False
        for c in clusters:
            if c["anchor"] == anchor and abs(ts - c["last_ts"]) <= SESSION_GAP_MS:
                c["calls"].append(i)
                c["last_ts"] = max(c["last_ts"], ts)
                placed = True
                break
        if not placed:
            clusters.append({"anchor": anchor, "last_ts": ts, "calls": [i]})
    for n, c in enumerate(clusters, 1):
        sid = f"auto-{n:04d}-{c['calls'][0].get('id', 'x')}"
        sessions[sid] = {"confidence": "medium", "calls": sorted(c["calls"], key=lambda x: _parse_ts(x.get("ts_start")))}
    return sessions


def derive_expected(session: dict[str, Any], case_id: str) -> dict[str, Any]:
    """从录制骨架**保守**派生用例期望（设计 §3.3：自动断言过严会误报良性漂移）。"""
    frames = session.get("frames") or {}
    exp: dict[str, Any] = {"no_error": True}
    agent_end = frames.get("AGENT_END", 0)
    if agent_end:
        exp["frames"] = {"AGENT_END": f">={min(agent_end, 1)}"}
    if session.get("tool_names"):
        exp["tool_calls"] = {"required": sorted(set(session["tool_names"]))}
    final = session.get("final_output") or ""
    if final:
        exp["final_text_min_len"] = max(1, len(final) // 2)
        exp["final_text_contains"] = [final[:12]] if len(final) >= 12 else [final]
    return exp


def pack(
    collector_dir: str,
    out_dir: str,
    ns: str | None = None,
    traces_dir: str | None = None,
    oaf_zip: str | None = None,
    since: str | None = None,
    until: str | None = None,
    framework_version: str = "unknown",
) -> Path:
    """执行打包，返回 evalpack 目录。zip 同名生成（供 studio 上传/分发）。"""
    collector = Path(collector_dir)
    if ns:
        collector = collector / ns
    out = Path(out_dir)
    if out.exists():
        shutil.rmtree(out)
    (out / "sessions").mkdir(parents=True)
    (out / "interactions" / "llm").mkdir(parents=True)
    (out / "interactions" / "sandbox").mkdir(parents=True)
    (out / "interactions" / "mcp").mkdir(parents=True)
    (out / "cases-draft").mkdir(parents=True)

    since_ms = _parse_ts(since) if since else 0
    until_ms = _parse_ts(until) if until else float("inf")

    def in_range(rec: dict[str, Any]) -> bool:
        ts = _parse_ts(rec.get("ts_start"))
        return since_ms <= ts <= until_ms

    llm = [r for r in _read_jsonl([str(p) for p in sorted((collector / "llm").glob("*.jsonl"))]) if in_range(r)]
    sandbox = [r for r in _read_jsonl([str(p) for p in sorted((collector / "sandbox").glob("*.jsonl"))]) if in_range(r)]
    mcp = [r for r in _read_jsonl([str(p) for p in sorted((collector / "mcp").glob("*.jsonl"))]) if in_range(r)]
    http_recs = [r for r in _read_jsonl([str(p) for p in sorted((collector / "http").glob("*.jsonl"))])] if (collector / "http").exists() else []

    # 单交互落盘（回放器直接消费）
    # 文件名用交互 id（collector 生成的 llm-<8hex> 等，非客户端可控 session id），不构成穿越面
    for rec in llm:
        with open(out / "interactions" / "llm" / f"{rec.get('id', 'x')}.json", "w", encoding="utf-8") as f:
            json.dump(rec, f, ensure_ascii=False, indent=1)
    # 业务服务 HTTP 交互（附录 C）：作为会话归属证据与前端视角对照进包
    http_sessions: dict[str, list[str]] = {}
    for rec in http_recs:
        (out / "interactions" / "http").mkdir(exist_ok=True)
        with open(out / "interactions" / "http" / f"{rec.get('id', 'x')}.json", "w", encoding="utf-8") as f:
            json.dump(rec, f, ensure_ascii=False, indent=1)
        if rec.get("session"):
            http_sessions.setdefault(rec["session"], []).append(rec.get("id"))

    sessions = group_llm_sessions(llm)

    # driver 轨迹捕获（可选）：{sid}.json = {"turns":[{"input":...}], "events":[...]}
    traces: dict[str, dict[str, Any]] = {}
    if traces_dir:
        for p in glob.glob(str(Path(traces_dir) / "*.json")):
            try:
                data = json.loads(Path(p).read_text(encoding="utf-8"))
                if data.get("sid"):
                    traces[data["sid"]] = data
            except (json.JSONDecodeError, OSError):
                continue

    correlation: dict[str, Any] = {}
    session_summaries: list[dict[str, Any]] = []
    # 轨迹锚点索引：fingerprint 聚类的 sid 是合成的（auto-XXXX），按「首轮输入归一化
    # 指纹」把驱动器轨迹接到会话上（真实 agent 无 X-Eval-Session 头时的连接路径）
    traces_by_anchor: dict[str, dict[str, Any]] = {}
    for tr in traces.values():
        turns = tr.get("turns") or []
        if turns:
            traces_by_anchor[nz.normalize_text(turns[0].get("input", ""))] = tr

    for sid, g in sorted(sessions.items()):
        # sid 可能来自 collector 转发的客户端 x-eval-session 头（server.mjs 仅 trim），
        # 未校验即拼 sessions/{sid}.json / cases-draft/{sid}.json 可路径穿越落盘包外
        # （issue #97 问题 9）——非法即终止整包，注明来源便于定位录制源头
        validate_id("session id", sid)
        calls = g["calls"]
        last = calls[-1]
        # 全会话各轮终答拼接（对齐驱动器合并轨迹的 final_output 口径）
        final = "".join(final_text_from_chunks(c.get("chunks") or []) for c in calls)
        usage = usage_from_chunks(last.get("chunks") or [])
        # 工具名 = 轨迹里实际调用的工具（请求里的 tools 目录只是「可用」，不构成断言）
        tr = traces.get(sid) or traces_by_anchor.get(
            nz.first_user_text((calls[0].get("request") or {}))) or {}
        tool_names = list(tr.get("tool_names") or [])
        inputs = tr.get("turns") or [{"input": nz.first_user_text((calls[0].get("request") or {}))}]
        session_obj = {
            "sid": sid,
            "confidence": "high" if sid in http_sessions else g["confidence"],
            "turns": inputs,
            "final_output": tr.get("final_output", final),
            "frames": tr.get("frame_counts") or {},
            "tool_names": tool_names,
            "hitl": tr.get("hitl") or {},
            "token_usage": usage,
            "llm_calls": [c.get("id") for c in calls],
            "recorded_final": final,  # 回放等价比对基准（chunks 反推）
            "ts_start": calls[0].get("ts_start"),
            "ts_end": last.get("ts_end"),
        }
        with open(out / "sessions" / f"{sid}.json", "w", encoding="utf-8") as f:
            json.dump(session_obj, f, ensure_ascii=False, indent=1)
        case = {
            "case_id": f"case_replay_{sid[:24]}",
            "title": tr.get("title") or f"录制会话回放 {sid}",
            "category": "replay",
            "input": {"message": inputs[0].get("input", ""), "userId": "eval-replay",
                      "sessionId": sid},
            "expected": derive_expected(session_obj, sid),
            "ground_truth": final or None,
            "requires_tools": tool_names or None,
            "status": "draft",
            "source": {"pack": True, "sid": sid},
        }
        case = {k: v for k, v in case.items() if v is not None}
        with open(out / "cases-draft" / f"{sid}.json", "w", encoding="utf-8") as f:
            json.dump(case, f, ensure_ascii=False, indent=1)
        confidence = g["confidence"]
        if sid in http_sessions:
            confidence = "high"  # HTTP 反代口有该 session 的强记录（附录 C 根治路径）
        correlation[sid] = {
            "confidence": confidence,
            "llm": [c.get("id") for c in calls],
            "sandbox": [s.get("id") for s in sandbox if s.get("session") == sid],
            "mcp": [m.get("id") for m in mcp if m.get("session") == sid],
            "http": http_sessions.get(sid, []),
        }
        session_summaries.append({"sid": sid, "confidence": confidence,
                                  "turns": len(inputs), "llm_calls": len(calls)})
    # 有 HTTP 反代交互但无 LLM 会话的 session（如纯查询轮）：单列归属条目，
    # 保证 http 交互在包内可寻址（前端视角回放演进的数据基础）
    for sid, ids in http_sessions.items():
        if sid not in correlation:
            correlation[sid] = {"confidence": "high", "llm": [], "sandbox": [],
                                "mcp": [], "http": ids}

    # 沙箱/MCP 交互落盘（回放器消费；无会话归属的也保留）
    for rec in sandbox:
        with open(out / "interactions" / "sandbox" / f"{rec.get('id', 'x')}.json", "w", encoding="utf-8") as f:
            json.dump(rec, f, ensure_ascii=False, indent=1)
    for rec in mcp:
        with open(out / "interactions" / "mcp" / f"{rec.get('id', 'x')}.json", "w", encoding="utf-8") as f:
            json.dump(rec, f, ensure_ascii=False, indent=1)

    # OAF 包副本
    oaf_info = None
    if oaf_zip and Path(oaf_zip).exists():
        (out / "oaf").mkdir(exist_ok=True)
        dst = out / "oaf" / Path(oaf_zip).name
        shutil.copy2(oaf_zip, dst)
        sha = hashlib.sha256(dst.read_bytes()).hexdigest()
        oaf_info = {"file": dst.name, "sha256": sha}

    manifest = {
        "pack_id": out.name,
        "format_version": PACK_FORMAT_VERSION,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "source": {"collector_dir": str(collector), "ns": ns or "(auto)",
                   "time_range": [since, until]},
        "runtime": {"framework_version": framework_version, "oaf": oaf_info},
        "redaction": {"applied": True, "note": "采集侧默认规则已应用；打包侧二次脱敏见 pack 调用参数"},
        "sessions": session_summaries,
        "stats": {"llm": len(llm),
                  "llm_main": len(llm) - sum(1 for r in llm if _is_background(r.get("request"))),
                  "llm_background": sum(1 for r in llm if _is_background(r.get("request"))),
                  "sandbox": len(sandbox), "mcp": len(mcp), "http": len(http_recs)},
    }
    with open(out / "manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    with open(out / "correlation.json", "w", encoding="utf-8") as f:
        json.dump(correlation, f, ensure_ascii=False, indent=1)

    # CHECKSUMS（全包 sha256 清单）
    # 豁免口径与 verify_checksums 反向扫描对齐：仅按根路径豁免根清单自身——
    # 若按文件名豁免，oaf_zip 恰名为 CHECKSUMS 时（copy 到 out/oaf/CHECKSUMS）
    # 该文件既不登记又过不了反向扫描，自产包将报废于自家校验（评审发现）
    checksums_resolved = (out / "CHECKSUMS").resolve()
    lines = []
    for p in sorted(out.rglob("*")):
        if p.is_file() and p.resolve() != checksums_resolved:
            lines.append(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(out)}")
    (out / "CHECKSUMS").write_text("\n".join(lines) + "\n", encoding="utf-8")

    # zip（studio 上传/离线分发）
    zip_path = out.with_suffix(".zip")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for p in sorted(out.rglob("*")):
            if p.is_file():
                zf.write(p, p.relative_to(out))
    return out


def verify_checksums(pack_dir: str | Path) -> bool:
    """校验 evalpack CHECKSUMS（studio 导入时强校验，runner load_pack 复用）。

    三重校验（issue #97 问题 21：原实现只正向核对清单内文件，空清单+多余
    evil.json 会漏过，缺 CHECKSUMS 抛 FileNotFoundError）：
      ① 正向：清单每条 rel 的 sha256 核对；rel 自身防穿越（拒绝绝对路径、
         resolve 后必须仍在包目录内——清单随包自带，需防其指向包外文件）；
      ② 反向：rglob 扫描包内全部文件，凡未登记于清单的（CHECKSUMS 自身除外）
         判篡改失败；
      ③ 兜底：CHECKSUMS 缺失/不可读/坏行一律返回 False 不抛穿——与 studio
         上传入口「缺 CHECKSUMS 即 400」的前置检查语义一致。
    """
    root = Path(pack_dir)
    try:
        sums = (root / "CHECKSUMS").read_text(encoding="utf-8").splitlines()
    except OSError:
        return False
    registered: set[Path] = set()
    root_resolved = root.resolve()
    try:
        for line in sums:
            if not line.strip():
                continue
            try:
                sha, rel = line.split("  ", 1)
            except ValueError:
                return False  # 坏行（非「sha256␣␣rel」格式）判校验失败
            p = root / rel
            resolved = p.resolve()
            if rel.startswith("/") or not resolved.is_relative_to(root_resolved):
                return False  # 清单 rel 为绝对路径或穿越出包目录
            if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest() != sha:
                return False
            registered.add(resolved)
        # 反向扫描：多余未登记文件 = 包被塞入未申报内容，拒收。
        # 仅按根路径排除清单自身（root/CHECKSUMS）——按文件名排除会让子目录下
        # 未登记的同名 CHECKSUMS 绕过检测（评审发现的走私通道）
        checksums_resolved = (root / "CHECKSUMS").resolve()
        for p in root.rglob("*"):
            resolved = p.resolve()
            if p.is_file() and resolved != checksums_resolved and resolved not in registered:
                return False
    except OSError:
        return False  # 包内文件不可读等 IO 异常一律判失败
    return True
