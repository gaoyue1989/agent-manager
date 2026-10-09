"""回放供给（provision pack 化，设计 §4.1 / M2）：evalpack → 独立存储 + 受测 jar + 三回放器。

复用 instance.py 的 infra（local-infra.sh MySQL:13306 / Redis:16379）与 docker jar 启动形态；
LLM/沙箱/MCP 上游指向 start_replayers() 拉起的回放器。OAF 包来自 evalpack 的 oaf/*.zip
（解包为 agent-config），无 OAF 时回落 assemble 基线模板（纯对话 Agent）。

独立存储语义（原则二）：evalpack 驱动的每轮回放复用本地 infra 容器但以独立
AGENT_REDIS_PREFIX 隔离键空间，跑完 teardown 不残留业务键。
"""

import json
import zipfile
from pathlib import Path
from typing import Any

from . import instance as inst
# 注意：eval 根目录在 sys.path（flywheel.py 注入），此处用绝对导入；eval 本身不是 python 包
from replay import runner as run_mod

REPLAY_REDIS_PREFIX = "eval-replay-"


def _recorded_model(pack_dir: str) -> str:
    """从包内主链交互提取录制模型——回放请求与录制请求的 model 必须一致（指纹含 model）。

    跳过背景调用（标题/记忆，模型可能与主链不同）；按请求体大小取最大者（主链 system
    prompt 远大于旁路调用），避免取到旁路模型。
    """
    import glob
    best, best_len = None, -1
    for f in glob.glob(str(Path(pack_dir) / "interactions" / "llm" / "*.json")):
        rec = json.loads(Path(f).read_text(encoding="utf-8"))
        req = rec.get("request") or {}
        msgs = req.get("messages") or []
        if not any(m.get("role") == "user" for m in msgs):
            continue
        size = sum(len(str(m.get("content", ""))) for m in msgs)
        if size > best_len and req.get("model"):
            best, best_len = req["model"], size
    return best or "replay-model"


def _prepare_agent_config(repo_dir: Path, runtime_dir: Path, pack_dir: str,
                          replayers: dict[str, tuple]) -> Path:
    """优先解包 evalpack 内 OAF 包；否则组装基线模板。返回 agent-config 目录。"""
    cfg_dir = runtime_dir / "agent-config"
    cfg_dir.mkdir(parents=True, exist_ok=True)
    oaf_zips = sorted((Path(pack_dir) / "oaf").glob("*.zip")) if (Path(pack_dir) / "oaf").exists() else []
    if oaf_zips:
        with zipfile.ZipFile(oaf_zips[0]) as zf:
            zf.extractall(cfg_dir)
        print(f"[provision-replay] OAF 包来自 evalpack: {oaf_zips[0].name}")
    else:
        from . import oaf as oaf_mod
        spec = {"since": "replay-minimal", "env_needs": {"plugins": [], "mock_mcp": {"enabled": False, "tools": [], "ask_tools": []},
                              "reload_probe": False, "session_model_probe": False}}
        oaf_mod.assemble(spec, cfg_dir, runtime_dir, repo_dir,
                         f"http://127.0.0.1:{replayers['mcp'][1]}/mcp" if "mcp" in replayers else "http://127.0.0.1:1/mcp")
        print("[provision-replay] evalpack 无 OAF 包，回落基线模板（纯对话 Agent）")

    # mcp-configs url 改写 → replay-mcp（设计 §4.1 步骤 1）
    if "mcp" in replayers:
        mcp_port = replayers["mcp"][1]
        for cfg in cfg_dir.glob("mcp-configs/*/config.yaml"):
            text = cfg.read_text(encoding="utf-8")
            import re
            text = re.sub(r"url:\s*https?://\S+",
                          f"url: http://127.0.0.1:{mcp_port}", text)
            cfg.write_text(text, encoding="utf-8")
            print(f"[provision-replay] mcp url 改写 → :{mcp_port} ({cfg.parent.name})")
    return cfg_dir


def provision_replay(repo_dir: Path, eval_dir: Path, pack_dir: str, out_dir: str,
                     mode: str = "strict", judge_cfg: dict[str, str] | None = None,
                     case_ids: list[str] | None = None,
                     target_llm_model: str | None = None,
                     extra_envs: dict[str, str] | None = None) -> dict[str, Any]:
    """拉起回放环境并执行 run_replay，返回 {exit_code, base_url, replayers}。

    target_llm_model 缺省自动取包内录制模型（回放请求 model 与录制一致，否则指纹漂移）。
    extra_envs 透传给受测实例——记忆/沙箱等影响请求形状的开关必须与录制侧一致
    （如 AGENT_MEMORY_ENABLED=false；录制侧开记忆而回放侧关，记忆注入差异会导致漂移）。
    调用方负责 finally 调 teardown_replay()（即使 run_replay 抛错也要清理容器与回放器）。
    """
    # 独立运行时目录（每 run 清空重建）：agent-config/workspace 必须与录制侧同源纯净，
    # 复用共享 .runtime-eval 会残留 workspace/skills 文件 → 工具集漂移（fingerprint 含 tools）
    import shutil as _sh
    runtime_dir = eval_dir / ".runtime-replay"
    _sh.rmtree(runtime_dir, ignore_errors=True)
    runtime_dir.mkdir(parents=True, exist_ok=True)
    inst.ensure_infra(repo_dir)

    replayers = run_mod.start_replayers(pack_dir)
    print(f"[provision-replay] 回放器: "
          + ", ".join(f"{k}=:{v[1]}" for k, v in replayers.items()))

    _prepare_agent_config(repo_dir, runtime_dir, pack_dir, replayers)

    extra_envs = {"AGENT_REDIS_PREFIX": REPLAY_REDIS_PREFIX, **(extra_envs or {})}
    if "sandbox" in replayers:
        extra_envs.update({"SANDBOX_ENABLED": "true",
                           "OPENSANDBOX_SERVER_URL": f"127.0.0.1:{replayers['sandbox'][1]}",
                           "OPENSANDBOX_API_KEY": "replay-dummy"})
    base_url = inst.start_instance(
        repo_dir, runtime_dir,
        target_llm={"base_url": f"http://127.0.0.1:{replayers['llm'][1]}/v1",
                    "api_key": "replay-dummy",
                    "model": target_llm_model or _recorded_model(pack_dir)},
        extra_envs=extra_envs)
    inst.wait_health(base_url)
    print(f"[provision-replay] 受测实例就绪: {base_url}")

    code = 2
    import asyncio
    try:
        code = asyncio.run(run_mod.run_replay(
            pack_dir=pack_dir, out_dir=out_dir, base_url=base_url, mode=mode,
            case_ids=case_ids, judge_cfg=judge_cfg, replayers=replayers))
    finally:
        # 回放期实际请求落盘（诊断漂移 / rebase 数据源；回放器随后回收）
        try:
            import httpx
            llm_port = replayers["llm"][1]
            captured = httpx.get(f"http://127.0.0.1:{llm_port}/captured", timeout=5).json()
            (Path(out_dir) / "replayer-captured.json").write_text(
                json.dumps(captured, ensure_ascii=False, indent=1), encoding="utf-8")
        except Exception:
            pass
        inst.teardown(runtime_dir)
        run_mod.stop_replayers(replayers)
    return {"exit_code": code, "base_url": base_url, "replayers": {k: v[1] for k, v in replayers.items()}}


def teardown_replay(replayers: dict[str, tuple] | None = None) -> None:
    if replayers:
        run_mod.stop_replayers(replayers)
