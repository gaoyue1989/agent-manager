# =========================================================================
# Makefile — Agent Framework (Java/Spring Boot, JDK 21, Maven)
# =========================================================================

# 变量
MVN        ?= mvn
JAR        ?= target/agent-framework-2.1.0.jar
IMAGE      ?= agent-framework:latest
DEV_IMAGE  ?= gaoyue1989/agent-framework:java-dev

.PHONY: help build package run dev test clean docker-build docker-build-dev \
        docker-save docker-load docker-push offline-build offline-cache clean-image \
        otel-agent

help:
	@echo "Agent Framework (Java) 可用目标:"
	@echo "  build          编译打包 (mvn package, 含测试)"
	@echo "  package        编译打包 (跳过测试)"
	@echo "  run            运行 fat jar (需先 package)"
	@echo "  dev            开发热启动 (mvn spring-boot:run)"
	@echo "  test           运行单元测试"
	@echo "  clean          清理 target"
	@echo "  docker-build   构建 Docker 运行镜像 (Dockerfile, 自动下载 OTel agent)"
	@echo "  otel-agent     下载 OTel Java Agent jar 到 docker/otel/"
	@echo "  docker-build-dev 构建离线开发镜像 (Dockerfile.dev)"
	@echo "  docker-save    导出离线开发镜像为 tar.gz"
	@echo "  docker-push    推送离线开发镜像到 Harbor/Docker Hub"
	@echo "  dev-build      离线镜像内构建 (离线模式)"
	@echo "  offline        进入离线开发容器"

# ---------- Maven 生命周期 ----------
build:
	$(MVN) clean package

package:
	$(MVN) package -DskipTests

test:
	$(MVN) test

compile:
	$(MVN) compile

dev:
	$(MVN) spring-boot:run

clean:
	$(MVN) clean

# ---------- Docker ----------
# OTel Java Agent jar (链路追踪, 见 docs/tracing-design.md §4.7)
# 预下载到 docker/otel/, Dockerfile 构建时直接 COPY (离线环境可预先放置)
OTEL_JAVAAGENT_VERSION ?= 2.12.0
OTEL_JAVAAGENT_JAR ?= docker/otel/opentelemetry-javaagent.jar

otel-agent:
	@if [ -f "$(OTEL_JAVAAGENT_JAR)" ]; then echo "已存在: $(OTEL_JAVAAGENT_JAR)"; \
	else \
		echo "下载 OTel Java Agent v$(OTEL_JAVAAGENT_VERSION) ..."; \
		mkdir -p docker/otel; \
		curl -fSLo $(OTEL_JAVAAGENT_JAR) \
			"https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v$(OTEL_JAVAAGENT_VERSION)/opentelemetry-javaagent.jar"; \
	fi

docker-build: otel-agent
	docker build -t $(IMAGE) -f Dockerfile .

# 构建离线开发镜像 (有网络或已有 .m2 缓存的机器)
docker-build-dev:
	docker build -t $(DEV_IMAGE) -f Dockerfile.dev .

# 导出离线镜像 tar.gz (便于传输到内网机器 docker load)
docker-save: docker-build-dev
	@docker save $(DEV_IMAGE) | gzip > agent-framework-java-dev.tar.gz
	@echo "已导出: agent-framework-java-dev.tar.gz"

# 推送镜像到远端仓库
docker-push:
	docker push $(DEV_IMAGE)

# ---------- 离线开发支持 ----------
# 在离线容器内强制离线构建 (仅用 /root/.m2 缓存, 不访问网络)
offline-build:
	$(MVN) -o package -DskipTests

# 进入离线开发容器 (挂载当前目录为工作区)
offline:
	docker run --rm -it \
		-v $$(pwd):/workspace \
		-w /workspace \
		-e _JAVA_OPTIONS="-Duser.home=/root" \
		$(DEV_IMAGE) bash

clean-image:
	docker rmi $(IMAGE) $(DEV_IMAGE) 2>/dev/null || true