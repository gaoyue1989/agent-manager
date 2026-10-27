"""导出器：评测数据 → 开源评测生态兼容格式（设计 docs/design/agent-framework-eval-dataset-hub-design.md）。

分工原则：数据收集归本平台（cases / evalpack，后续 PR 收敛为 DatasetHub），
评测执行交给外部工具（deepeval 文件导入 / langfuse dataset+experiments）。

导出器统一消费「标准条目」（sources.item schema，见 sources.py），
外部 id 确定性生成保证 langfuse upsert 幂等；凭据只走环境变量，导出物零密钥。
"""

from . import deepeval, langfuse_client, sources

# 支持的导出目标（--target 取值）；新增目标在此登记，不改 flywheel 分发逻辑
TARGETS = ("deepeval", "langfuse")

__all__ = ["TARGETS", "deepeval", "langfuse_client", "sources"]
