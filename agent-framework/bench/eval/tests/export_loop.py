#!/usr/bin/env python3
"""导出器离线契约 e2e（零外联，由 flywheel selftest 子进程调用）：
mock langfuse server 验证 Basic 鉴权 / upsert 幂等 / 5xx 退避重试 / 4xx 快速失败 /
缺凭据快速终止 / pack 来源加载。断言失败非零退出。
"""

import base64
import json
import os
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(EVAL_DIR))

from exporters import langfuse_client, sources  # noqa: E402

PK, SK = "pk-local-test", "sk-local-test"
_RETRY_PROBE_ID = "probe_case_retry"


class MockLangfuse(BaseHTTPRequestHandler):
    """类属性即服务端状态：items/scores 按 id upsert，posts 记录全部请求供断言。"""
    items: dict[str, dict] = {}
    scores: dict[str, dict] = {}
    datasets: set[str] = set()
    posts: list[tuple[str, dict]] = []
    fail_once_seen: set[str] = set()
    traces_fixture: list[dict] = []  # GET /api/public/traces 返回的固定数据

    def do_GET(self):
        if self.path.startswith("/api/public/traces"):
            if self.headers.get("Authorization") != "Basic " + base64.b64encode(
                    f"{PK}:{SK}".encode()).decode():
                self._json(401, {"error": "unauthorized"})
                return
            self._json(200, {"data": type(self).traces_fixture})
            return
        self._json(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length)) if length else {}
        auth = self.headers.get("Authorization", "")
        type(self).posts.append((auth, body))
        if auth != "Basic " + base64.b64encode(f"{PK}:{SK}".encode()).decode():
            self._json(401, {"error": "unauthorized"})
            return
        if self.path == "/api/public/datasets":
            # dataset-items 不自动建集（真实实例 404 实证）：create-if-missing，重复 409
            name = body.get("name")
            if name in type(self).datasets:
                self._json(409, {"error": "already exists"})
            else:
                type(self).datasets.add(name)
                self._json(201, {"name": name})
            return
        if self.path == "/api/public/scores":
            pid = body.get("id")
            if pid == "probe_score_retry" and pid not in type(self).fail_once_seen:
                type(self).fail_once_seen.add(pid)  # 首次 500，逼客户端走退避重试
                self._json(500, {"error": "transient"})
                return
            type(self).scores[pid] = body  # upsert：同 id 覆盖
            self._json(200, {"id": pid})
            return
        assert self.path == "/api/public/dataset-items", self.path
        pid = body.get("id")
        if pid == _RETRY_PROBE_ID and pid not in type(self).fail_once_seen:
            type(self).fail_once_seen.add(pid)  # 首次 500，逼客户端走退避重试
            self._json(500, {"error": "transient"})
            return
        type(self).items[pid] = body  # upsert：同 id 覆盖，不产生重复
        self._json(200, {"id": pid})

    def _json(self, code: int, obj: dict) -> None:
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_a) -> None:  # 静默访问日志
        pass


def _synth_item(i: int) -> dict:
    return {"id": f"case_{i}", "input_message": f"问题{i}", "turns": None,
            "ground_truth": f"答案{i}", "expected_tools": None, "final_output": None,
            "actual_output": None, "metadata": {"case_id": f"case_{i}", "category": "function"}}


def _pack_fixture(root: Path) -> list[dict]:
    """最小 evalpack 夹具：1 条有效会话 + 1 条空输入会话（应被跳过）。"""
    (root / "sessions").mkdir(parents=True)
    (root / "manifest.json").write_text(json.dumps({"pack_id": "pk-exp-e2e"}), encoding="utf-8")
    ok = {"sid": "sess-1", "confidence": "high", "turns": [{"input": "你好"}],
          "final_output": "你好呀", "frames": {}, "tool_names": ["echo"],
          "recorded_final": "你好呀", "token_usage": {"input": 1, "output": 1, "total": 2}}
    empty = {"sid": "sess-empty", "confidence": "low", "turns": [{"input": ""}],
             "final_output": "", "frames": {}, "tool_names": [],
             "recorded_final": "", "token_usage": {"input": 0, "output": 0, "total": 0}}
    (root / "sessions" / "sess-1.json").write_text(json.dumps(ok), encoding="utf-8")
    (root / "sessions" / "sess-empty.json").write_text(json.dumps(empty), encoding="utf-8")
    return sources.load_from_pack(root)


def main() -> int:
    # ① pack 来源加载：空输入会话跳过、语义金标=录制终答、工具透传
    with tempfile.TemporaryDirectory() as td:
        pack_items = _pack_fixture(Path(td))
    assert len(pack_items) == 1, pack_items
    it = pack_items[0]
    assert it["id"] == "case_replay_sess-1" and it["input_message"] == "你好"
    assert it["ground_truth"] == "你好呀" and it["expected_tools"] == ["echo"]
    assert it["metadata"]["pack"] == "pk-exp-e2e"

    srv = ThreadingHTTPServer(("127.0.0.1", 0), MockLangfuse)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{srv.server_address[1]}"
    nosleep = lambda *_a: None  # 退避等待在测试中不真实 sleep

    payloads = [langfuse_client.build_item(it, "ds-e2e", id_prefix="pk-exp-e2e")]
    payloads += [langfuse_client.build_item(_synth_item(i), "ds-e2e") for i in (1, 2)]
    probe = langfuse_client.build_item(_synth_item(3), "ds-e2e")
    probe["id"] = _RETRY_PROBE_ID
    payloads.append(probe)

    # ② 正常推送 + 重试探针首次 500 后成功
    r1 = langfuse_client.push(base, PK, SK, payloads, sleep=nosleep)
    assert r1 == {"total": 4, "upserted": 4, "failed": []}, r1

    # ③ 幂等重推：同 id 再推不产生重复 item
    r2 = langfuse_client.push(base, PK, SK, payloads, sleep=nosleep)
    assert r2["upserted"] == 4, r2
    assert len(MockLangfuse.items) == 4, sorted(MockLangfuse.items)
    probe_posts = [p for p in MockLangfuse.posts if p[1].get("id") == _RETRY_PROBE_ID]
    assert len(probe_posts) == 3, f"探针应 3 次到达（500+重试成功+重推）: {len(probe_posts)}"
    assert all(a.startswith("Basic ") for a, _ in MockLangfuse.posts)

    # ④ 凭据错误 → ensure_dataset 阶段 401 快速抛错（单次请求，不重试不打批量）
    posts_before = len(MockLangfuse.posts)
    try:
        langfuse_client.push(base, PK, "wrong-sk", payloads, sleep=nosleep)
        raise AssertionError("错凭据应抛 LangfuseError")
    except langfuse_client.LangfuseError:
        pass
    assert len(MockLangfuse.posts) - posts_before == 1, "401 不应触发重试"

    # ⑤ 缺凭据 → LangfuseError，且不发起任何请求
    saved = {k: os.environ.pop(k, None) for k in ("LANGFUSE_HOST", "LANGFUSE_PUBLIC_KEY",
                                                  "LANGFUSE_SECRET_KEY")}
    try:
        try:
            langfuse_client.resolve_creds()
            raise AssertionError("缺凭据应抛 LangfuseError")
        except langfuse_client.LangfuseError:
            pass
    finally:
        os.environ.update({k: v for k, v in saved.items() if v is not None})
    assert len(MockLangfuse.posts) == posts_before + 1

    # ⑥ M3 观测回流：find_traces 双路径过滤（原生 sessionId / metadata agentscope.session.id）
    MockLangfuse.traces_fixture = [
        {"id": "tr-native", "sessionId": "sess-x"},                      # 原生映射
        {"id": "tr-meta", "sessionId": None,
         "metadata": {"agentscope.session.id": "sess-x"}},               # metadata 平铺兜底
        {"id": "tr-other", "sessionId": "sess-other"},                    # 不匹配应被滤掉
    ]
    traces = langfuse_client.find_traces(base, PK, SK, "sess-x")
    assert [t["id"] for t in traces] == ["tr-native", "tr-meta"], traces

    # ⑦ scores：确定性 id + upsert 幂等 + 重试探针（500 一次后成功）
    s1 = langfuse_client.build_score("tr-native", "eval-checks/c1", 1.0,
                                     comment="PASS", metadata={"case_id": "c1"})
    s2 = langfuse_client.build_score("tr-native", "eval-checks/c1", 0.0)
    assert s1["id"] == s2["id"] and s1["id"] != langfuse_client.build_score(
        "tr-other", "eval-checks/c1", 1.0)["id"], "score id 必须按 traceId+name 确定性"
    assert s1["dataType"] == "NUMERIC" and s1["value"] == 1.0
    probe = langfuse_client.build_score("tr-native", "probe_score_retry", 0.5)
    probe["id"] = "probe_score_retry"  # 与 mock 的 fail_once 字面量比对对齐（同 items 探针手法）
    rs = langfuse_client.push_scores(base, PK, SK, [s1, probe, s2], sleep=nosleep)
    assert rs == {"total": 3, "upserted": 3, "failed": []}, rs
    assert len(MockLangfuse.scores) == 2, sorted(MockLangfuse.scores)  # s1/s2 同 id 覆盖
    assert MockLangfuse.scores[s1["id"]]["value"] == 0.0  # 后写覆盖前写
    posts_scores = [p for p in MockLangfuse.posts if p[1].get("name") == "probe_score_retry"]
    assert len(posts_scores) == 2, f"scores 重试探针应 2 次到达: {len(posts_scores)}"
    posts_before_scores = len(MockLangfuse.posts)
    rs_bad = langfuse_client.push_scores(base, PK, "wrong-sk", [s1], sleep=nosleep)
    assert rs_bad["upserted"] == 0 and len(rs_bad["failed"]) == 1, rs_bad  # 401 快速失败
    assert len(MockLangfuse.posts) - posts_before_scores == 1, "401 不应触发重试"

    srv.shutdown()
    print("export_loop PASS（pack 加载/Basic 鉴权/建集幂等/upsert 幂等/5xx 退避重试/401 快速失败/"
          "缺凭据终止/traces 双路径过滤/scores 确定性幂等回流）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
