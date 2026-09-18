GO ?= /usr/local/go1.23/bin/go
export GOPROXY ?= https://goproxy.cn,direct
IMAGE ?= platform-backend:v1
REGISTRY_IMAGE ?= $(IMAGE)

.PHONY: test cover build image kind-load deploy-dev run-dev

test:
	$(GO) vet ./...
	$(GO) test ./...

# 覆盖率分析：k8sfake 为无测试文件的测试辅助包，本地精简工具链（缺 covdata）
# 会让整跑 -coverprofile 报错退出，这里排除之（CI 为完整 go1.26 不受影响）
cover:
	$(GO) test $$($(GO) list ./... | grep -v /internal/k8s/k8sfake) -coverprofile=cover.out -covermode=atomic
	$(GO) tool cover -func=cover.out | tail -1

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
