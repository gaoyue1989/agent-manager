"""环境供给编排（设计 §4.5）：env_spec → OAF 组装 → mock/实例 → 契约预检。

产物（reports/{ts}-provision/ 与 .runtime-eval/）：
  env_spec.json / oaf-manifest.json / preflight.json（人读留痕）
  .runtime-eval/state.json（运行态：base_url / pid / env_tags，供 run 复用与 teardown）
"""

import json
import time
from pathlib import Path
from typing import Any

from . import instance as inst
from . import needs as needs_mod
from . import oaf as oaf_mod

MOCK_URL = f"http://127.0.0.1:{inst.MOCK_PORT}/mcp"


def provision_env(eval_dir: Path, repo_dir: Path, since: str, base_dir: Path | None = None,
                  plugin_src: str | None = None, reports_dir: Path | None = None) -> dict[str, Any]:
    """执行 ①+①.5：分析 → 组装 → 起 mock/实例 → 预检。返回运行态摘要。

    预检硬失败抛 RuntimeError（调用方按退出码 2 处理——环境问题不伪装成用例失败）。
    """
    runtime_dir = eval_dir / inst.RUNTIME_NAME
    runtime_dir.mkdir(parents=True, exist_ok=True)

    env_spec = needs_mod.build_env_spec(repo_dir, since)
    tags = needs_mod.env_tags(env_spec)
    print(f"[provision] env_needs: plugins={[p['name'] for p in env_spec['env_needs']['plugins']]} "
          f"mock={env_spec['env_needs']['mock_mcp']['enabled']} "
          f"ask={env_spec['env_needs']['mock_mcp']['ask_tools']} "
          f"reload={env_spec['env_needs']['reload_probe']} "
          f"model={env_spec['env_needs']['session_model_probe']}")

    inst.ensure_infra(repo_dir)
    manifest = oaf_mod.assemble(env_spec, runtime_dir / "agent-config", runtime_dir,
                                repo_dir, MOCK_URL, base_dir=base_dir, plugin_src=plugin_src)
    print(f"[provision] OAF 组装完成: {runtime_dir / 'agent-config'} "
          f"(插件 {len(manifest['plugins'])} 个, mock 工具 {len(manifest['mock_tools'])} 个)")

    mock_proc = inst.start_mock(runtime_dir / "mock-catalog.json", runtime_dir)
    target_llm = _target_llm_from_env()
    base_url = inst.start_instance(repo_dir, runtime_dir, target_llm)
    print(f"[provision] 实例就绪: base_url={base_url} mock_pid={mock_proc.pid}")

    pre = inst.preflight(env_spec, base_url)
    if not pre["pass"]:
        failed = [c["name"] for c in pre["checks"] if not c["pass"]]
        inst.teardown(runtime_dir)
        raise RuntimeError(f"契约预检失败: {failed}，明细: {pre['checks']}")

    state = {"base_url": base_url, "mock_pid": mock_proc.pid, "env_tags": sorted(tags),
             "since": since, "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
    (runtime_dir / "state.json").write_text(json.dumps(state, ensure_ascii=False, indent=1),
                                            encoding="utf-8")

    out = (reports_dir or eval_dir / "reports") / f"{time.strftime('%Y%m%d-%H%M%S')}-provision"
    out.mkdir(parents=True, exist_ok=True)
    (out / "env_spec.json").write_text(json.dumps(env_spec, ensure_ascii=False, indent=1),
                                      encoding="utf-8")
    (out / "oaf-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1),
                                          encoding="utf-8")
    (out / "preflight.json").write_text(json.dumps(pre, ensure_ascii=False, indent=1),
                                        encoding="utf-8")
    print(f"[provision] 预检全绿，产物: {out}")
    return {**state, "env_spec": env_spec, "manifest": manifest, "preflight": pre,
            "runtime_dir": str(runtime_dir), "provision_dir": str(out)}


def load_runtime_state(eval_dir: Path) -> dict[str, Any] | None:
    """读运行态（run --base-url 复用供给环境时取 env_tags）。"""
    state_file = eval_dir / inst.RUNTIME_NAME / "state.json"
    if not state_file.exists():
        return None
    try:
        return json.loads(state_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def _target_llm_from_env() -> dict[str, str]:
    """被测实例 LLM：EVAL_TARGET_LLM_* 优先，回落 EVAL_LLM_*（同一模型端点亦可）。"""
    import os
    get = lambda k, d="": os.environ.get(k, d)  # noqa: E731
    cfg = {
        "base_url": get("EVAL_TARGET_LLM_BASE_URL") or get("EVAL_LLM_BASE_URL"),
        "api_key": get("EVAL_TARGET_LLM_API_KEY") or get("EVAL_LLM_API_KEY"),
        "model": get("EVAL_TARGET_LLM_MODEL") or get("EVAL_LLM_MODEL"),
    }
    missing = [k for k, v in cfg.items() if not v]
    if missing:
        raise RuntimeError(f"被测 LLM 配置缺失（EVAL_TARGET_LLM_* 或 EVAL_LLM_*）: {missing}")
    return cfg
