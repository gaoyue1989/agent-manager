FROM golang:1.23-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o server ./cmd/server

FROM alpine:3.19
RUN apk add --no-cache ca-certificates curl kubectl python3 py3-pip && \
    python3 -m venv /opt/codegen-venv && \
    /opt/codegen-venv/bin/pip install --no-cache-dir \
      deepagents>=0.5.0 langchain langchain-openai fastapi uvicorn pydantic pyyaml

COPY --from=builder /app/server /usr/local/bin/server

COPY ../codegen/generator.py /opt/codegen/generator.py

ENV CODEGEN_PYTHON=/opt/codegen-venv/bin/python3
ENV CODEGEN_SCRIPT=/opt/codegen/generator.py

EXPOSE 8080
CMD ["server"]
