"""T2 会话骨架只读导出器：checkpoint MySQL → 骨架 JSON（设计 dataset-hub §数据获取通道）。

只读约束：本模块只发 SELECT（SQL 由本模块模板拼装，--since 经格式校验后内插），
不写不迁；凭据经环境变量 MYSQL_PWD 传给 mysql CLI（不出现在 argv/文件/日志）。
依赖 mysql 客户端二进制（评测工作站预装；集群库不可达时先 kubectl port-forward）。

输出 {out}/{safe_sid}.json（sid 含 ':' 会清洗为 '_'，原始 sid 保留在 JSON 内）：
  {sid, confidence, turns: [{input, ts}], tool_names, final_output,
   message_count, ts_start, ts_end}
骨架是 G2' 式保守素材：语义金标未定，供 dataset --import-skeleton 采样转
draft item，人审/G3 synth 补齐后再转正。
"""

import json
import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

from replay.ids import validate_id

_SINCE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}([ T]\d{2}:\d{2}(:\d{2})?)?$")


class SkeletonError(Exception):
    """骨架导出失败（客户端缺失/连接失败/凭据缺失）。"""


def build_sql(since: str | None = None) -> str:
    """会话消息只读查询。--since 经格式校验后内插（模板内无其他外部输入，无注入面）。"""
    where = ""
    if since:
        if not _SINCE_RE.match(since):
            raise SkeletonError(f"--since 格式非法: {since!r}（需 YYYY-MM-DD[ hh:mm[:ss]]）")
        where = f" WHERE created_at >= '{since}'"
    return ("SELECT session_id, role, msg_data, created_at FROM session_message"
            f"{where} ORDER BY session_id, id")


def _run_query(db_host: str, db_port: int, db_user: str, db_name: str,
               sql: str, mysql_bin: str | None,
               kubectl_resource: str | None, kubectl_ns: str,
               kubectl_user: str, kubectl_password_env: str) -> str:
    """执行只读查询返回 TSV 输出。两种通路：
    - direct：mysql CLI，密码走宿主机 MYSQL_PWD 环境变量（适合库可达且账号已授权）
    - kubectl：kubectl exec 进 DB 容器执行，凭据全程留在 pod 环境里（集群库推荐，
      宿主机无需任何数据库账号；库容器内 env 即凭据）
    """
    if kubectl_resource:
        if not shutil.which("kubectl"):
            raise SkeletonError("kubectl 不可用")
        # SQL 不含单引号（since 正则约束），可安全嵌入单引号 sh -c 脚本
        script = (f'mysql --default-character-set=utf8mb4 -h {db_host} -u {kubectl_user} '
                  f'-p"${kubectl_password_env}" {db_name} --batch --raw -e \'{sql}\'')
        argv = ["kubectl", "-n", kubectl_ns, "exec", kubectl_resource,
                "--", "sh", "-c", script]
    else:
        if not shutil.which(mysql_bin or "mysql"):
            raise SkeletonError("mysql 客户端不可用（集群库建议 --via kubectl 或先 port-forward）")
        if not os.environ.get("MYSQL_PWD"):
            raise SkeletonError("缺 MYSQL_PWD 环境变量（密码不落 argv/文件）")
        argv = [mysql_bin or "mysql", "--default-character-set=utf8mb4",
                "-h", db_host, "-P", str(db_port), "-u", db_user,
                "--batch", "--raw", db_name, "-e", sql]
    proc = subprocess.run(argv, capture_output=True, text=True, timeout=120)
    if proc.returncode != 0:
        raise SkeletonError(f"查询失败（检查 host/port/user/库与网络）: {proc.stderr.strip()[-300:]}")
    return proc.stdout


def _content_summary(msg_data: str) -> dict[str, Any]:
    """AgentScope Msg JSON → {user_text, assistant_text, tool_names}（thinking 不进任何字段）。"""
    out: dict[str, Any] = {"user_text": "", "assistant_text": "", "tools": []}
    try:
        msg = json.loads(msg_data)
    except json.JSONDecodeError:
        return out
    for block in msg.get("content") or []:
        btype = block.get("type")
        if btype == "text" and isinstance(block.get("text"), str):
            if (msg.get("role") or "").upper() == "USER":
                out["user_text"] += block["text"]
            elif (msg.get("role") or "").upper() == "ASSISTANT":
                out["assistant_text"] += block["text"]
        elif btype == "tool_use" and block.get("name"):
            out["tools"].append(block["name"])
        elif btype == "tool_result" and block.get("name"):
            out["tools"].append(block["name"])
    return out


def sessions_from_rows(rows: list[tuple[str, str, str, str]]) -> dict[str, dict[str, Any]]:
    """(session_id, role, msg_data, created_at) 有序行 → 按 sid 聚合的骨架映射。"""
    sessions: dict[str, dict[str, Any]] = {}
    for sid, role, msg_data, created_at in rows:
        s = sessions.setdefault(sid, {
            "sid": sid, "confidence": "medium", "turns": [], "tool_names": [],
            "final_output": "", "message_count": 0, "ts_start": created_at,
            "ts_end": created_at,
        })
        s["message_count"] += 1
        s["ts_end"] = created_at
        c = _content_summary(msg_data)
        s["tool_names"].extend(c["tools"])
        if c["user_text"]:
            s["turns"].append({"input": c["user_text"], "ts": created_at})
        if c["assistant_text"]:
            s["final_output"] = c["assistant_text"]  # 取最后一段助手文本（近似终答）
    for s in sessions.values():
        s["tool_names"] = sorted(set(s["tool_names"]))
    return sessions


def export_skeleton(db_host: str, db_port: int, db_user: str, db_name: str,
                    out_dir: str | Path, since: str | None = None,
                    mysql_bin: str | None = None,
                    kubectl_resource: str | None = None, kubectl_ns: str = "agent-platform",
                    kubectl_user: str = "root", kubectl_password_env: str = "MYSQL_ROOT_PASSWORD",
                    kubectl_db_host: str = "127.0.0.1") -> list[Path]:
    """执行只读导出，返回骨架文件清单。kubectl 模式凭据不出 pod；direct 模式密码走 MYSQL_PWD。"""
    sql = build_sql(since)
    if kubectl_resource:
        db_host = kubectl_db_host  # 容器内回环
    stdout = _run_query(db_host, db_port, db_user, db_name, sql, mysql_bin,
                        kubectl_resource, kubectl_ns, kubectl_user, kubectl_password_env)

    rows: list[tuple[str, str, str, str]] = []
    for line in stdout.splitlines()[1:]:  # 首行为表头
        parts = line.split("\t", 3)
        if len(parts) == 4:
            rows.append((parts[0], parts[1], parts[2], parts[3]))
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    produced: list[Path] = []
    for sid, session in sorted(sessions_from_rows(rows).items()):
        safe = sid.replace(":", "_").replace("/", "_")
        validate_id("skeleton file id", safe)
        path = out / f"{safe}.json"
        with open(path, "w", encoding="utf-8") as f:
            json.dump(session, f, ensure_ascii=False, indent=1)
        produced.append(path)
    return produced
