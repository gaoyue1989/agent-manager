GO ?= /usr/local/go1.23/bin/go
export GOPROXY ?= https://goproxy.cn,direct
IMAGE ?= platform-backend:v1
REGISTRY_IMAGE ?= $(IMAGE)

.PHONY: test build image kind-load deploy-dev run-dev

test:
	$(GO) vet ./...
	$(GO) test ./...

build:
	$(GO) build -o bin/platform-backend ./cmd/server

image:
	docker build -t $(IMAGE) .

kind-load:
	kind load docker-image $(IMAGE) --name agent-manager

# 宿主机开发运行（直连 kind kubeconfig + 本地数据目录）
run-dev:
	MYSQL_DSN="oaf:OafPlatform2026@tcp(172.20.0.1:3307)/oaf_platform?charset=utf8mb4&parseTime=True" \
	AVAILABLE_IMAGES='agent-framework:latest|Agent Framework latest' \
	KUBECONFIG=$$HOME/.kube/config DATA_ROOT=/tmp/oaf-data \
	go run ./cmd/server
