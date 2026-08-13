# Agent Framework 离线开发镜像

离线开发镜像 `gaoyue1989/agent-framework:java-dev` 预装 **JDK 21 + Maven 3.9.9**，并缓存当前 `pom.xml` 的**全部依赖**（含所有 Maven 插件，已验证 `mvn -o test` 300 个用例离线通过）。适用于内网（如 192.168.31.207）等无法访问外网的环境。

## 镜像内容

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | Eclipse Temurin 21 | 运行时 + javac |
| Maven | 3.9.9 | `/opt/maven`，已链接到 `/usr/local/bin/mvn` |
| 依赖缓存 | 全量 | `/root/.m2/repository`，含编译/测试/插件全部依赖（含 OTel 1.61.0 全部 13 个组件） |
| OTel Java Agent | v2.12.0 | `/opt/otel/opentelemetry-javaagent.jar`（链路追踪用，见 `tracing-design.md`） |
| 附加工具 | curl, git, vim | 容器内开发使用 |
| 默认源 | Maven Central | 见 `/opt/maven/conf/settings.xml` |

## 交付方式（本机 → 内网）

已推送到 Docker Hub `gaoyue1989/agent-framework:java-dev`。内网若无法访问 Docker Hub，用 tar 方式：

```bash
# 方式一: 内网可直接拉取
docker pull gaoyue1989/agent-framework:java-dev

# 方式二: 导出 tar 包（在有网机器）→ 拷贝到内网 → docker load
docker save gaoyue1989/agent-framework:java-dev | gzip > agent-framework-java-dev.tar.gz
docker load < agent-framework-java-dev.tar.gz
```

## 内网使用（离线开发/构建）

```bash
# 进入离线开发容器（挂载当前目录为 /workspace）
docker run --rm -it \
  -v $(pwd):/workspace -w /workspace \
  gaoyue1989/agent-framework:java-dev bash

# 容器内：离线构建（-o = offline，仅用本地 .m2 缓存）
mvn -o clean package -DskipTests

# 离线测试（300 用例已验证通过）
mvn -o test
```

也可通过项目 Makefile：

```bash
make offline          # 进入离线容器
make offline-build    # mvn -o package -DskipTests
make docker-build-dev # 重新构建离线镜像
make docker-save      # 导出为 tar.gz
make docker-push      # 推送镜像
```

## 接入内网 Nexus（Maven 私服）

镜像内置默认 settings.xml（Maven Central）。若需接入内网 Nexus 私有源，推荐**在容器外修改并挂载覆盖**，不动镜像：

```xml
<!-- 参考模板: agent-framework/docker/offline-settings.xml -->
<settings>
  <localRepository>/root/.m2/repository</localRepository>
  <mirrors>
    <mirror>
      <id>nexus</id>
      <name>内网 Nexus 私有源</name>
      <mirrorOf>*</mirrorOf>
      <url>http://192.168.31.207:8081/repository/maven-public/</url>  <!-- 改为你的 Nexus 地址 -->
    </mirror>
  </mirrors>
</settings>
```

挂载使用：

```bash
docker run --rm -it \
  -v $(pwd):/workspace -w /workspace \
  -v /path/to/settings.xml:/root/.m2/settings.xml:ro \
  gaoyue1989/agent-framework:java-dev bash
```

> 提示：若填充了新依赖并想连 Nexus 补充下载，去掉 `-o` 即可让 Maven 通过 Nexus 拉取缺失 artifact。

## 离线镜像 Dockerfile.dev 说明

- `pom.xml` + `src` 完整预构建一次（`mvn clean install || true`），确保所有传递依赖与插件进入缓存。
- 基础镜像 `eclipse-temurin:21-jdk`（Debian）。
- 构建参数 `MAVEN_VERSION` 可指定其他 Maven 版本（默认 3.9.9）。
- 预置 OTel Java Agent jar 到 `/opt/otel/`（从 `docker/otel/` COPY，需先 `make otel-agent` 下载）。
- 构建后清理 `/root/.m2/repository/.cache` 与 wrapper 减小体积。

> 提示：2026-08-13 更新（含 OTel 依赖缓存 + javaagent jar）后镜像约 1.4GB / 导出 tar.gz 约 441MB。

## 常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| 离线构建报 `ArtifactResolutionException` | 新依赖未在镜像缓存内 | 有网机器重建镜像并 docker save 重新导入 |
| 需彻底离线但 `mvn` 仍尝试联网 | 未加 `-o` 参数 | 使用 `mvn -o` 强制离线 |
| 内网 Nexus 地址变更 | 无 | 修改 settings.xml 并重新挂载即可 |