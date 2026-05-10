# Research Assistant

智能研究助手，擅长信息检索、数据分析和报告生成。

## 快速开始

```bash
# 设置环境变量
export LLM_API_KEY=your_api_key
export LLM_MODEL_ID=your_model_id
export LLM_BASE_URL=https://your-api-endpoint/v1
export LLM_PROVIDER=ctyun

# 安装依赖
pip install -r requirements.txt

# 运行
python main.py
```

## A2A 端点

- `GET /.well-known/agent-card.json` - Agent Card 发现
- `POST /` - JSON-RPC 2.0 端点
- `POST /tasks` - REST 创建任务
- `GET /tasks/{id}` - REST 获取任务
- `GET /health` - 健康检查

## A2UI 支持

本 Agent 支持 A2UI v0.8 扩展，可以生成声明式 UI 组件。
