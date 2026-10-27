# exporters — 评测数据导出（deepeval / langfuse 兼容层）

> 设计：`docs/design/agent-framework-eval-dataset-hub-design.md`（PR1：导出器；PR2：DatasetHub
> 成为统一来源；PR3：langfuse scores 回流）。分工原则：**数据收集归本平台，评测执行交外部生态**。

## 数据流

```
cases/*.json ──┐
evalpack sessions ──┼→ sources 标准条目 → deepeval.py → goldens.jsonl / conversational_goldens.jsonl / testcases.jsonl
run traces/ ────┘                   └→ langfuse_client.py → langfuse-items.jsonl --push→ POST /api/public/dataset-items
```

标准条目（item）：`{id, input_message, turns?, ground_truth?, expected_tools?, final_output?,
actual_output?, metadata}`——确定性 id、凭据零泄漏、可选字段缺省不造 null（`sources.py`）。

## 用法

```bash
cd agent-framework
# ① deepeval：库内用例 → 金标 JSONL（单轮/多轮分文件，混排会被 deepeval 拒绝）
python3 bench/eval/flywheel.py export --target deepeval --out reports/exports/deepeval

# ② deepeval：录制会话 → 多轮 conversational golden + 已执行轨迹 → testcases
python3 bench/eval/flywheel.py export --target deepeval --pack reports/packs/pk-xxx --out reports/exports/pk
python3 bench/eval/flywheel.py export --target deepeval --kind testcases --run reports/{task}/ --out reports/exports/tc

# ③ langfuse：先出本地 items.jsonl（可 diff 可人审），--push 才推远端（按 id upsert 幂等）
python3 bench/eval/flywheel.py export --target langfuse --dataset release-regression \
  --out reports/exports/lf
LANGFUSE_HOST=... LANGFUSE_PUBLIC_KEY=pk-... LANGFUSE_SECRET_KEY=sk-... \
  python3 bench/eval/flywheel.py export --target langfuse --dataset release-regression \
  --out reports/exports/lf --push
```

退出码沿用飞轮契约：0 成功 / 1 推送存在失败项 / 2 参数或来源问题。

## deepeval 侧加载（外部环境，工作站零依赖）

```python
from deepeval.dataset import EvaluationDataset
ds = EvaluationDataset()
ds.add_goldens_from_jsonl_file(file_path="reports/exports/deepeval/goldens.jsonl")
ds.add_goldens_from_jsonl_file(file_path="reports/exports/deepeval/conversational_goldens.jsonl")
```

手动验收（不进 CI）：任一装有 deepeval 的环境执行上面三行，加载条数 = 导出行数即通过。

## langfuse 侧

- 自建实例：`docker compose -f exporters/docker-compose.langfuse.yml up -d`（:18500，
  凭据占位符见文件头注释），pk/sk 在 Web UI 项目设置生成。
- 推送：Basic 认证，先 `POST /api/public/datasets` 自动建集（409=已存在，幂等目标态；
  dataset-items API 不自动建集，实测 404），再 `POST /api/public/dataset-items`
  **按 id upsert 幂等**（id 确定性 → 重复推送不产生重复 item）；
  429/5xx 退避重试，4xx 快速失败；单条失败不阻断批量，退出码 1。
- 实验执行（跑评测）走 langfuse SDK/UI——dataset-run-items REST 已弃用，本模块不碰。

## 契约守护

- `flywheel.py selftest` 内含导出器断言段（schema/单多轮拆分/确定性 id/凭据零泄漏）。
- `tests/export_loop.py`：mock langfuse（localhost 零外联）验证鉴权/upsert 幂等/重试矩阵，
  由 selftest 子进程调用，CI `评测自检` job 覆盖。

## 边界

- langfuse `sourceTraceId` 关联、scores 回流 → M3（设计 §7）。
- 缺省来源 = DatasetHub active items（收编统一后唯一事实源）；`--pack`/`--run` 保留为
  快捷旁路。cases/ 为兼容投影，`export --target cases-legacy` 再生。
