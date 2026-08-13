# =========================================================================
# Dockerfile — Agent Framework (Java/Spring Boot, JDK 21)
#
# 多阶段构建:
#   Stage 1 (builder): Maven 编译 + 打包 fat jar
#   Stage 2 (runtime): eclipse-temurin JRE 21 运行 (非 root 用户)
#
# 链路追踪 (OTel Java Agent 方案, 见 docs/tracing-design.md §4.7):
#   镜像内置 opentelemetry-javaagent.jar (make otel-agent 预下载到 docker/otel/)
#   设 OTEL_TRACES_EXPORTER=otlp (或非 none) 即自动注入 -javaagent;
#   不设置则零开销 (Agent 未启用), 自研 fallback 亦不装配 (默认 none)
# =========================================================================

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# 先拷贝 pom.xml 单独预下载依赖，充分利用 Docker 层缓存
COPY pom.xml .
RUN mvn dependency:go-offline -B -q || true

# 拷贝源码并打包 (跳过测试, 测试在 CI 阶段执行)
COPY src ./src
RUN mvn package -DskipTests -B -q

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre

WORKDIR /app

# 创建非 root 运行用户 (并安装健康检查所需 curl)
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --shell /usr/sbin/nologin appuser \
    && mkdir -p /config \
    # K8s ConfigMap/Volume 挂载会覆盖目录属主为 root，故 chmod 777 保证非 root 用户可写
    && chmod 777 /config \
    && chown -R appuser:appuser /config

# 从 builder 阶段拷贝 fat jar
COPY --from=builder /app/target/agent-framework-*.jar /app/agent-framework.jar

# OTel Java Agent (链路追踪, 见 docs/tracing-design.md §4.7)
# 由 make otel-agent 预下载, 构建时直接 COPY (离线环境也可预先放置该文件)
COPY docker/otel/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

# 灵活性: 运行阶段可覆盖 JVM 参数 (如 -Xmx)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# 追踪开关: 设 OTEL_EXPORTER_OTLP_ENDPOINT 时 ENTRYPOINT 自动注入 -javaagent
# 注意: 不要设 OTEL_TRACES_EXPORTER (保持 unset), 否则 Agent 的 trace 导出也被关闭;
# 自研 fallback (OtelConfig/HttpTracingFilter) 因 Spring 默认 none 自动不装配, 无重复 span
# (Agent 默认也导出 metrics/logs, 统一关闭避免对 collector 的 404 噪音)
ENV OTEL_METRICS_EXPORTER=none \
    OTEL_LOGS_EXPORTER=none

EXPOSE 8100

VOLUME ["/config"]

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD ["bash", "-c", "curl -fsS http://localhost:${SERVER_PORT:-8100}/health || exit 1"]

USER appuser

# 设 OTEL_EXPORTER_OTLP_ENDPOINT 时自动启用 Java Agent (链路追踪, 见 docs/tracing-design.md §4.7)
# 不设则零开销: Agent 不启动, 自研 OtelConfig 亦因条件注解 (havingValue=otlp) 不装配
# 注意: OTEL_TRACES_EXPORTER 保持 unset, Agent 默认导出 traces 到上述 endpoint
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS $( [ -n \"${OTEL_EXPORTER_OTLP_ENDPOINT:-}\" ] && echo -javaagent:/app/opentelemetry-javaagent.jar ) -jar /app/agent-framework.jar"]