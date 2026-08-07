# =========================================================================
# Dockerfile — Agent Framework (Java/Spring Boot, JDK 21)
#
# 多阶段构建:
#   Stage 1 (builder): Maven 编译 + 打包 fat jar
#   Stage 2 (runtime): eclipse-temurin JRE 21 运行 (非 root 用户)
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
    && chown -R appuser:appuser /config

# 从 builder 阶段拷贝 fat jar
COPY --from=builder /app/target/agent-framework-*.jar /app/agent-framework.jar

# 灵活性: 运行阶段可覆盖 JVM 参数 (如 -Xmx)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8100

VOLUME ["/config"]

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD ["bash", "-c", "curl -fsS http://localhost:${SERVER_PORT:-8100}/health || exit 1"]

USER appuser

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/agent-framework.jar"]