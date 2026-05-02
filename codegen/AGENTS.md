# CodeGen — AGENTS.md

## 二级模块概述

Python 代码生成模块，将 Agent 配置 JSON 转换为可直接运行的 DeepAgents (FastAPI + LangChain) 微服务代码，包含 `agent.py`、`Dockerfile`、`requirements.txt` 三个产出物。

## 基础规则

严格按用户需求执行，不擅自加功能、不脑补逻辑、不画蛇添足；只输出可直接运行的完整代码，拒绝伪代码。需求模糊主动提问，输出无多余闲聊，全程对齐项目现有代码风格、目录结构、命名规范。

## 开发流程

先看项目目录和现有关联代码，理清逻辑再编码；只修改指定文件与逻辑，不改动无关代码、不整文件重写。

## 代码规范

命名语义化，禁止硬编码密钥、魔法数字；网络、IO、数据库操作必做判空、边界校验和异常捕获；优先复用现有工具，不私自升级框架、乱加依赖；复杂逻辑加中文注释。

## 输出格式

代码块标语言、改文件标路径；保留配置原有缩进，不搞多余排版；完工自动清理调试日志、临时测试代码。

## 安全约束

不随意改 Git、Docker 及系统配置；禁用高危删除命令，敏感信息用占位符；不做删核心文件、清依赖等破坏性操作，环境报错先给排查方案。

---

## 目录结构

```
codegen/
├── generator.py                # 核心生成器 (5 个函数)
├── schema/
│   └── agent_config.json       # JSON Schema Draft-07 定义
├── templates/                  # 模板目录 (空，渲染内联于 f-string)
├── test/
│   ├── test-config.json        # 测试输入配置
│   └── output/                 # 期待输出目录
└── venv/                       # Python 虚拟环境
```

---

## generator.py 核心函数

| 函数 | 签名 | 作用 |
|------|------|------|
| `generate_agent_code` | `(config: dict) -> dict` | 主入口，返回 `{"agent.py": str, "Dockerfile": str, "requirements.txt": str}` |
| `_render_agent_py` | `(name, description, model, endpoint, api_key, system_prompt, tools, sub_agents, enable_memory, max_iterations) -> str` | 生成完整的 FastAPI + DeepAgents `agent.py` |
| `_render_dockerfile` | `(name: str, has_skills: bool = False, base_image: str = "") -> str` | 生成 Dockerfile，支持基础镜像参数 |
| `_render_requirements` | `() -> str` | 返回固定的 6 个 pip 依赖 |
| `validate_config` | `(config: dict) -> list[str]` | 校验必填字段 (`name`, `description`, `model`, `system_prompt`) |
| `main` | `() -> None` | CLI 入口，支持文件模式 / stdin 模式 |

**Dockerfile 生成逻辑：**
- 若 `base_image` 为空：生成完整 Dockerfile（含 `pip install`）
- 若 `base_image` 指定：生成精简 Dockerfile（FROM 基础镜像，仅 COPY agent.py）

---

## 调用模式

### 文件模式
```bash
python generator.py <config.json> [output_dir]
```
- 从文件读取 JSON 配置
- output_dir 默认为 config 文件所在目录

### Stdin 模式 (Go 后端调用方式)
```bash
echo '{...}' | python generator.py --stdin [output_dir]
```
- 从 stdin 读取 JSON 配置
- output_dir 默认为当前目录
- Go 后端通过 `codegen/runner.go` 调用，解析 stdout 获取生成内容

---

## 生成产物

每次生成输出 3 个文件：

### agent.py
独立 FastAPI 微服务，包含：
- **模型初始化**：通过 `langchain.chat_models.init_chat_model()` 连接 LLM
- **Agent 创建**：通过 `deepagents.create_deep_agent()` 创建 Agent 实例
- **API 端点**：
  - `GET /` — Agent 元信息 (name, description, model, endpoints)
  - `GET /health` — 健康检查 `{"status": "healthy", "agent": "<name>"}`
  - `POST /chat` — 对话接口 `{"message": str, "history": list, "stream": bool}`
- **Pydantic 模型**: `ChatRequest` / `ChatResponse`
- **运行方式**: `uvicorn` 监听 `0.0.0.0:8000`

### Dockerfile
```dockerfile
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY agent.py .
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=30s --start-period=5s --retries=3 CMD curl -f http://localhost:8000/health || exit 1
CMD ["uvicorn", "agent:app", "--host", "0.0.0.0", "--port", "8000"]
```

### requirements.txt
```
deepagents>=0.5.0
langchain>=1.0.0
langchain-openai>=1.0.0
fastapi>=0.100.0
uvicorn>=0.30.0
pydantic>=2.0.0
```

---

## JSON Schema (schema/agent_config.json)

JSON Schema Draft-07 定义：

| 属性 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `name` | string | 是 | — | Agent 名称 |
| `description` | string | 是 | — | Agent 描述 |
| `model` | string | 是 | `qwen3.6-plus` | LLM 模型 ID |
| `system_prompt` | string | 是 | — | 系统提示词 |
| `model_endpoint` | string | 否 | DashScope 地址 | LLM API 端点 |
| `api_key` | string | 否 | (DashScope key) | LLM API Key |
| `tools` | array[string] | 否 | `[]` | 工具列表 |
| `sub_agents` | array[object] | 否 | `[]` | 子 Agent 定义 |
| `memory` | boolean | 否 | `true` | 是否启用记忆 |
| `max_iterations` | integer | 否 | `50` | 最大迭代次数 |

---

## 已知问题 & 注意事项

1. **模板目录为空**：`templates/` 目录无实际文件，所有渲染通过 f-string 内联完成
2. **工具/子 Agent 未接入**：`tools` 和 `sub_agents` 在生成的 `agent.py` 中声明为变量，但未传入 `create_deep_agent()` 调用，功能待实现
3. **API Key 硬编码**：默认 API Key 出现在 schema 默认值和 test-config 中，部署时需通过环境变量覆盖
4. **渲染方式**：`agent.py` 中 FastAPI/Pydantic 的 `{}` 使用 `{{` / `}}` 双花括号转义，避免与 Python f-string 冲突
