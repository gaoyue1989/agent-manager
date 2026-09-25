"""OAF 包组装器（设计 §4.2）：base 模板/真实包 + 变更覆盖层 → 确定性组装 agent-config/。

覆盖层动作（按 env_spec）：
  - mock_mcp  → 覆盖 mcp-configs/{configDir}/config.yaml（url 指向本地 mock，permissions 按工具目录）
  - plugins   → 现场编译插件源码为 jar 放入 plugins/（plugin-smoke.sh 配方：javac target/classes +
                agentscope-core → META-INF/services → jar），并写插件 config.yaml（${ENV} 替换验证）
产物留痕：oaf-manifest.json（各层来源、动机、内容 hash；文件内容本身不含时间戳，可 diff 对比）。
"""

import hashlib
import json
import re
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any

# 插件 SPI 接口（ToolPluginBootstrapper 扫描的注册契约）
_PLUGIN_SPI = "io.agentmanager.framework.tool.ToolPlugin"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:16]


def _mcp_config_dir(agents_md: Path) -> str:
    """从 AGENTS.md frontmatter 解析 mcpServers configDir（缺省 mcp-configs/platform）。"""
    try:
        text = agents_md.read_text(encoding="utf-8")
    except OSError:
        return "mcp-configs/platform"
    m = re.search(r'configDir:\s*"([^"]+)"', text)
    return m.group(1) if m else "mcp-configs/platform"


def build_plugin_jar(repo_dir: Path, plugin: dict[str, Any], out_jar: Path,
                     runtime_dir: Path) -> dict[str, Any]:
    """编译打包单个插件 jar（plugin-smoke.sh 同配方）。

    优先宿主 javac + ~/.m2；无 JDK 时经 maven 容器（af-m2 缓存卷）执行同样步骤。
    依赖产物：target/classes（mvn package 产出）+ agentscope-core jar。
    """
    src_dir = repo_dir / plugin["src"]
    sources = sorted(src_dir.glob("*.java"))
    if not sources:
        raise RuntimeError(f"插件源码不存在: {src_dir}")
    if not (repo_dir / "target" / "classes").is_dir():
        raise RuntimeError("缺少 target/classes（先执行 mvn -DskipTests package）")

    # SPI 实现类 = 源码 package + 类名（plugin-smoke.sh:76 同款拼接）
    first = sources[0].read_text(encoding="utf-8")
    pkg = re.search(r"package\s+([\w.]+)\s*;", first)
    cls = re.search(r"public\s+class\s+(\w+)", first)
    if not pkg or not cls:
        raise RuntimeError(f"无法从 {sources[0].name} 解析 package/class")
    impl = f"{pkg.group(1)}.{cls.group(1)}"

    build_dir = runtime_dir / "plugin-build" / plugin["name"]
    build_dir.mkdir(parents=True, exist_ok=True)
    if shutil.which("javac") and (Path.home() / ".m2").is_dir():
        cmd = _local_build_cmd(repo_dir, sources, impl, build_dir, out_jar)
    else:
        cmd = _docker_build_cmd(repo_dir, plugin, sources, impl, build_dir, out_jar)
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    built = build_dir / "out.jar"
    if proc.returncode == 0 and not out_jar.exists() and built.exists():
        shutil.move(str(built), str(out_jar))  # docker 构建产物在挂载卷内，取回 plugins/
    if proc.returncode != 0 or not out_jar.exists():
        raise RuntimeError(f"插件构建失败: {(proc.stderr or proc.stdout)[-500:]}")
    return {"name": plugin["name"], "jar": str(out_jar), "impl": impl,
            "tools": plugin.get("tools", []), "sha256": _sha256(out_jar)}


def _local_build_cmd(repo_dir: Path, sources: list[Path], impl: str,
                     build_dir: Path, out_jar: Path) -> list[str]:
    m2 = Path.home() / ".m2" / "repository" / "io" / "agentscope" / "agentscope-core"
    as_core = sorted(m2.glob("*/agentscope-core-*.jar"))[-1:]
    if not as_core:
        raise RuntimeError("未找到 agentscope-core jar（~/.m2）")
    srcs = " ".join(str(s) for s in sources)
    return ["bash", "-c",
            f"javac -cp '{repo_dir}/target/classes:{as_core[0]}' -d '{build_dir}/classes' {srcs} && "
            f"mkdir -p '{build_dir}/classes/META-INF/services' && "
            f"echo '{impl}' > '{build_dir}/classes/META-INF/services/{_PLUGIN_SPI}' && "
            f"jar --create --file '{out_jar}' -C '{build_dir}/classes' ."]


def _docker_build_cmd(repo_dir: Path, plugin: dict[str, Any], sources: list[Path],
                      impl: str, build_dir: Path, out_jar: Path) -> list[str]:
    """无宿主 JDK 时经 maven 容器构建（挂载仓库与 af-m2 依赖缓存卷）。"""
    srcs = " ".join(str(s).replace(str(repo_dir), "/workspace") for s in sources)
    script = (
        "AS_CORE=$(ls /root/.m2/repository/io/agentscope/agentscope-core/*/"
        "agentscope-core-*.jar | sort -V | tail -1) && "
        f"javac -cp /workspace/target/classes:$AS_CORE -d /rt/{plugin['name']}/classes {srcs} && "
        f"mkdir -p /rt/{plugin['name']}/classes/META-INF/services && "
        f"echo '{impl}' > /rt/{plugin['name']}/classes/META-INF/services/{_PLUGIN_SPI} && "
        f"jar --create --file /rt/{plugin['name']}/out.jar -C /rt/{plugin['name']}/classes .")
    return ["docker", "run", "--rm",
            "-v", f"{repo_dir}:/workspace", "-v", f"{build_dir}:/rt/{plugin['name']}",
            "-v", "af-m2:/root/.m2", "-w", "/workspace",
            "maven:3.9-eclipse-temurin-21", "bash", "-c", script]


def assemble(env_spec: dict[str, Any], out_dir: Path, runtime_dir: Path,
             repo_dir: Path, mock_url: str, base_dir: Path | None = None,
             plugin_src: str | None = None) -> dict[str, Any]:
    """组装 agent-config/ 与 mock 工具目录，返回 manifest。"""
    needs = env_spec["env_needs"]
    out_dir.mkdir(parents=True, exist_ok=True)
    layers: list[dict[str, Any]] = []

    # ---- 层 1：base（真实 OAF 包或最小模板） ----
    if base_dir is not None:
        for name in ("AGENTS.md", "skills", "mcp-configs"):
            src = base_dir / name
            if src.is_dir():
                shutil.copytree(src, out_dir / name, dirs_exist_ok=True)
                layers.append({"layer": "base", "src": str(src)})
            elif src.is_file():
                shutil.copy2(src, out_dir / name)
                layers.append({"layer": "base", "src": str(src)})
    else:
        template = Path(__file__).parent / "templates" / "eval-agent"
        shutil.copytree(template, out_dir, dirs_exist_ok=True)
        layers.append({"layer": "base", "src": str(template)})

    # ---- 层 2：mock MCP 配置覆盖（url 指向本地 mock，permissions 按工具目录） ----
    if needs["mock_mcp"]["enabled"]:
        cfg_dir = _mcp_config_dir(out_dir / "AGENTS.md")
        cfg_path = out_dir / cfg_dir / "config.yaml"
        cfg_path.parent.mkdir(parents=True, exist_ok=True)
        perms = "\n".join(f"    {t['name']}: {t['permission']}"
                          for t in _MOCK_CATALOG_FOR(needs)["tools"])
        cfg_path.write_text(
            "server: platform-publisher\n"
            "vendor: agentmanager\n"
            'version: "1.0.0"\n'
            "connection:\n"
            "  type: streamableHttp\n"
            f"  url: {mock_url}\n"
            "  timeout: 60\n"
            "permissions:\n"
            "  tools:\n" + perms + "\n",
            encoding="utf-8")
        layers.append({"layer": "mock_mcp_config", "src": str(cfg_path),
                       "reason": "mcp/ 或 HITL 变更"})

    # ---- 层 3：工具插件（现场编译 jar + ${ENV} 配置） ----
    plugin_manifest = []
    for plugin in needs["plugins"]:
        if plugin_src:
            plugin = dict(plugin, src=plugin_src)
        out_jar = out_dir / "plugins" / f"{plugin['name']}.jar"
        out_jar.parent.mkdir(parents=True, exist_ok=True)
        plugin_manifest.append(build_plugin_jar(repo_dir, plugin, out_jar, runtime_dir))
        (out_dir / "plugins" / plugin["name"]).mkdir(parents=True, exist_ok=True)
        (out_dir / "plugins" / plugin["name"] / "config.yaml").write_text(
            "marker: ${EVAL_PLUGIN_MARKER}\n", encoding="utf-8")
        layers.append({"layer": "plugin", "src": plugin["src"],
                       "reason": plugin.get("reason", "")})

    catalog = _MOCK_CATALOG_FOR(needs)
    catalog_path = runtime_dir / "mock-catalog.json"
    with open(catalog_path, "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, indent=1)

    manifest = {
        "since": env_spec["since"],
        "layers": layers,
        "plugins": plugin_manifest,
        "mock_tools": catalog["tools"],
        "files": {str(p.relative_to(out_dir)): _sha256(p)
                  for p in sorted(out_dir.rglob("*")) if p.is_file()},
        "assembled_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
    }
    with open(out_dir / "oaf-manifest.json", "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    return manifest


def _MOCK_CATALOG_FOR(needs: dict[str, Any]) -> dict[str, Any]:
    """env_spec → mock 工具目录（ask 工具仅在 HITL 需求时提供）。"""
    from . import needs as needs_mod  # 延迟导入避免环
    tools = [t for t in needs_mod._MOCK_TOOLS
             if t["permission"] != "ask" or t["name"] in needs["mock_mcp"]["ask_tools"]]
    return {"tools": tools}
