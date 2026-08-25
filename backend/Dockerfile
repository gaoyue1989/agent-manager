# 多阶段构建：golang 编译 → 运行镜像
FROM golang:1.26 AS builder
ENV GOPROXY=https://goproxy.cn,direct
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /out/platform-backend ./cmd/server

FROM alpine:3.20
RUN apk add --no-cache tzdata ca-certificates curl && adduser -D -u 10001 app
COPY --from=builder /out/platform-backend /usr/local/bin/platform-backend
USER app
EXPOSE 8080
ENTRYPOINT ["platform-backend"]
