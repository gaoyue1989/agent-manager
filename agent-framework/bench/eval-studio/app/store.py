"""eval-studio 持久化：SQLite 元数据 + 文件库实体（evalpack/run 产物，设计 §6.1）。

敏感凭据只存引用（env:VAR）或档案内显式配置（本服务为内部评测工具，不落文件系统的
明文仅限 judge key 一项，且导出接口一律剥离）。
"""

import json
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any

_CONN: sqlite3.Connection | None = None
_LOCK = threading.Lock()

_SCHEMA = """
CREATE TABLE IF NOT EXISTS profiles (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, spec TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS packages (
  pack_id TEXT PRIMARY KEY, manifest TEXT NOT NULL, dir TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS cases (
  case_id TEXT PRIMARY KEY, pack_id TEXT NOT NULL, spec TEXT NOT NULL, status TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS runs (
  run_id TEXT PRIMARY KEY, type TEXT NOT NULL, profile_id TEXT, pack_id TEXT,
  status TEXT NOT NULL, params TEXT NOT NULL, report TEXT, out_dir TEXT,
  error TEXT, created_at TEXT NOT NULL, finished_at TEXT
);
"""


def init(db_path: str) -> None:
    global _CONN
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    _CONN = sqlite3.connect(db_path, check_same_thread=False)
    _CONN.row_factory = sqlite3.Row
    _CONN.executescript(_SCHEMA)
    _CONN.commit()


def _now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S")


def _exec(sql: str, args: tuple = ()) -> None:
    with _LOCK:
        _CONN.execute(sql, args)
        _CONN.commit()


def _all(sql: str, args: tuple = ()) -> list[dict[str, Any]]:
    with _LOCK:
        rows = _CONN.execute(sql, args).fetchall()
    return [dict(r) for r in rows]


def _one(sql: str, args: tuple = ()) -> dict[str, Any] | None:
    rows = _all(sql, args)
    return rows[0] if rows else None


# ---- profiles ----

def list_profiles() -> list[dict[str, Any]]:
    return [{**{k: r[k] for k in ("id", "name", "created_at")},
             "spec": json.loads(r["spec"])} for r in _all("SELECT * FROM profiles ORDER BY created_at")]


def get_profile(pid: str) -> dict[str, Any] | None:
    r = _one("SELECT * FROM profiles WHERE id=?", (pid,))
    return {**{k: r[k] for k in ("id", "name", "created_at")}, "spec": json.loads(r["spec"])} if r else None


def put_profile(pid: str, name: str, spec: dict[str, Any]) -> None:
    exists = _one("SELECT id FROM profiles WHERE id=?", (pid,))
    if exists:
        _exec("UPDATE profiles SET name=?, spec=? WHERE id=?", (name, json.dumps(spec, ensure_ascii=False), pid))
    else:
        _exec("INSERT INTO profiles(id,name,spec,created_at) VALUES(?,?,?,?)",
              (pid, name, json.dumps(spec, ensure_ascii=False), _now()))


def delete_profile(pid: str) -> None:
    _exec("DELETE FROM profiles WHERE id=?", (pid,))
    if get_setting("active_profile") == pid:
        set_setting("active_profile", "")


def get_setting(key: str) -> str | None:
    r = _one("SELECT value FROM settings WHERE key=?", (key,))
    return r["value"] if r else None


def set_setting(key: str, value: str) -> None:
    _exec("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
          (key, value))


# ---- packages ----

def put_package(pack_id: str, manifest: dict[str, Any], dir_path: str) -> None:
    _exec("INSERT INTO packages(pack_id,manifest,dir,created_at) VALUES(?,?,?,?) "
          "ON CONFLICT(pack_id) DO UPDATE SET manifest=excluded.manifest, dir=excluded.dir",
          (pack_id, json.dumps(manifest, ensure_ascii=False), dir_path, _now()))


def list_packages() -> list[dict[str, Any]]:
    return [{**{k: r[k] for k in ("pack_id", "dir", "created_at")},
             "manifest": json.loads(r["manifest"])} for r in _all("SELECT * FROM packages ORDER BY created_at DESC")]


def get_package(pack_id: str) -> dict[str, Any] | None:
    r = _one("SELECT * FROM packages WHERE pack_id=?", (pack_id,))
    return {**{k: r[k] for k in ("pack_id", "dir", "created_at")},
            "manifest": json.loads(r["manifest"])} if r else None


def delete_package(pack_id: str) -> None:
    _exec("DELETE FROM packages WHERE pack_id=?", (pack_id,))


# ---- cases ----

def upsert_case(case: dict[str, Any], pack_id: str, status: str) -> None:
    _exec("INSERT INTO cases(case_id,pack_id,spec,status,created_at) VALUES(?,?,?,?,?) "
          "ON CONFLICT(case_id) DO UPDATE SET spec=excluded.spec, status=excluded.status",
          (case["case_id"], pack_id, json.dumps(case, ensure_ascii=False), status, _now()))


def list_cases(status: str | None = None) -> list[dict[str, Any]]:
    if status:
        rows = _all("SELECT * FROM cases WHERE status=? ORDER BY created_at DESC", (status,))
    else:
        rows = _all("SELECT * FROM cases ORDER BY created_at DESC")
    return [{**{k: r[k] for k in ("case_id", "pack_id", "status", "created_at")},
             "spec": json.loads(r["spec"])} for r in rows]


def get_case(case_id: str) -> dict[str, Any] | None:
    r = _one("SELECT * FROM cases WHERE case_id=?", (case_id,))
    return {**{k: r[k] for k in ("case_id", "pack_id", "status")},
            "spec": json.loads(r["spec"])} if r else None


# ---- runs ----

def create_run(run_id: str, run_type: str, profile_id: str | None, pack_id: str | None,
               params: dict[str, Any], out_dir: str) -> None:
    _exec("INSERT INTO runs(run_id,type,profile_id,pack_id,status,params,out_dir,created_at) "
          "VALUES(?,?,?,?, 'queued', ?, ?, ?)",
          (run_id, run_type, profile_id, pack_id, json.dumps(params, ensure_ascii=False), out_dir, _now()))


def update_run(run_id: str, **fields: Any) -> None:
    sets, args = [], []
    for k, v in fields.items():
        sets.append(f"{k}=?")
        args.append(json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list)) else v)
    args.append(run_id)
    _exec(f"UPDATE runs SET {', '.join(sets)} WHERE run_id=?", tuple(args))


def list_runs(limit: int = 50) -> list[dict[str, Any]]:
    rows = _all("SELECT * FROM runs ORDER BY created_at DESC LIMIT ?", (limit,))
    return [_run_public(r) for r in rows]


def get_run(run_id: str) -> dict[str, Any] | None:
    r = _one("SELECT * FROM runs WHERE run_id=?", (run_id,))
    return _run_public(r) if r else None


def _run_public(r: dict[str, Any]) -> dict[str, Any]:
    return {
        "run_id": r["run_id"], "type": r["type"], "profile_id": r["profile_id"],
        "pack_id": r["pack_id"], "status": r["status"], "error": r["error"],
        "params": json.loads(r["params"]),
        "report": json.loads(r["report"]) if r["report"] else None,
        "out_dir": r["out_dir"], "created_at": r["created_at"], "finished_at": r["finished_at"],
    }


def next_queued_run() -> dict[str, Any] | None:
    r = _one("SELECT * FROM runs WHERE status='queued' ORDER BY created_at LIMIT 1")
    if not r:
        return None
    # 原子占用：queued → running（单 worker，直接更新即可）
    _exec("UPDATE runs SET status='running' WHERE run_id=? AND status='queued'", (r["run_id"],))
    return get_run(r["run_id"])
