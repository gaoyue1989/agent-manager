FROM python:3.12-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY server/ ./server/

EXPOSE 8100

ENV PYTHONUNBUFFERED=1

VOLUME ["/config"]

HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8100/health || exit 1

CMD exec gunicorn -w ${GUNICORN_WORKERS:-4} -k uvicorn.workers.UvicornWorker \
    server.wsgi:app \
    --bind ${SERVER_HOST:-0.0.0.0}:${SERVER_PORT:-8100} \
    --max-requests ${GUNICORN_MAX_REQUESTS:-1000} \
    --max-requests-jitter ${GUNICORN_MAX_REQUESTS_JITTER:-50}
