# eval-studio 镜像：开箱即用评测服务（设计 §2.1 / §6）
# 单镜像 = studio(web/API/run 编排) + packager(evalpack 打包) + replay 引擎(replay-llm/stub 沙盒) + judge 客户端
# 构建上下文 = bench/（打包 eval 引擎代码）：
#   docker build -f eval-studio/Dockerfile -t gaoyue1989/eval-studio:latest .
# 运行：
#   docker run -d --name eval-studio -p 18400:18400 \
#     -v $PWD/studio-data:/data -v $PWD/collector-data:/var/lib/eval-collector:ro \
#     -e EVAL_LLM_BASE_URL=... -e EVAL_LLM_API_KEY=... -e EVAL_LLM_MODEL=... \
#     gaoyue1989/eval-studio:latest
FROM node:22-alpine

# python3 = FastAPI 应用 + 打包器；node = replay-llm / 内置回放沙盒
RUN apk add --no-cache python3 py3-pip

WORKDIR /srv
COPY eval-studio/requirements.txt ./
COPY eval-studio/app/ ./app/
COPY eval-studio/static/ ./static/
COPY eval/ ./eval/

# 依赖装独立 venv（alpine 3.20+ PEP 668 外部管理环境）
RUN python3 -m venv /opt/venv \
 && /opt/venv/bin/pip install --no-cache-dir -r requirements.txt

ENV PATH="/opt/venv/bin:$PATH" \
    STUDIO_DATA_DIR=/data \
    STUDIO_BENCH_DIR=/srv \
    PYTHONUNBUFFERED=1

VOLUME ["/data"]
EXPOSE 18400

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s \
  CMD wget -qO- http://127.0.0.1:18400/healthz >/dev/null 2>&1 || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "18400", "--app-dir", "/srv"]
