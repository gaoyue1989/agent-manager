# eval-collector 镜像：四类交互录制代理（LLM/沙箱/MCP/业务服务 HTTP）+ 内置 Web 控制台（设计 §2.1）
# docker run -d --name eval-collector \
#   -p 18200-18203:18200-18203 -p 18300:18300 \
#   -v $PWD/data:/var/lib/eval-collector -v $PWD/conf:/etc/eval-collector \
#   -e EVAL_COLLECTOR_ADMIN_TOKEN=<token> \
#   gaoyue1989/eval-collector:latest
FROM node:22-alpine

WORKDIR /app
COPY server.mjs console.html ./

ENV EVAL_COLLECTOR_DATA=/var/lib/eval-collector \
    EVAL_COLLECTOR_CONF=/etc/eval-collector

VOLUME ["/var/lib/eval-collector", "/etc/eval-collector"]
EXPOSE 18200 18201 18202 18203 18300

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s \
  CMD wget -qO- http://127.0.0.1:18300/healthz >/dev/null 2>&1 || exit 1

CMD ["node", "server.mjs"]
