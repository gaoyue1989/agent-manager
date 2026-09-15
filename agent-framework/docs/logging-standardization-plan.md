# Agent-Framework 日志规范化改造设计文档

> 依据：中信银行《Agent-Framework 服务日志规范》（源自《容器日志收集方案-v2》，Confluence pageId=59269854）
> 目标：服务日志按规范打印到 `/applog/${HOST_NAME}/trace.log`，满足容器云日志采集与 Kibana 检索要求

---

## 1. 规范要求摘要

| 项 | 要求 |
|----|------|
| 采集路径 | 容器云日志收集器**仅采集** `/applog/${HOST_NAME}/trace.log`；其他路径/文件名/控制台日志均不采集 |
| 日志格式 | `%d{yyyy-MM-dd HH:mm:ss.SSS} [${HOST_NAME}] [${app-name}] [%-5level] [%t] [%traceId] %c{1} - %msg{nolookups}%n` |
| 归档策略 | 单文件 200MB，最多 2 个归档（trace.log1 / trace.log2），合计上限 600MB |
| 编码 | JVM 启动必须加 `-Dfile.encoding=UTF-8`；日志文件/控制台字符集 UTF-8 |
| HOST_NAME 来源 | 容器创建时注入环境变量（本项目实现：K8s downward API `fieldRef: metadata.name`，变量名按环境变量命名惯例取大写 `HOST_NAME`，规范原文的 `hostName` 语义一致），**不在 logback 中人为指定** |
| app-name | 应用标识，部署配置中手动指定（本项目实现：环境变量 `APP_NAME`） |
| 单条约束 | ≤ 4KB，禁大量特殊字符，禁敏感信息 |
| 非应用日志 | GC/JDBC 等不以 `.log` 结尾，建议 `.txt`，输出独立目录 |
| 控制台日志 | 不被采集，保留 CONSOLE appender 供本地开发调试，格式与文件一致 |

---

## 2. 现状盘点（已核实代码）

### 2.1 日志配置

`agent-framework/src/main/resources/logback-spring.xml`（当前完整内容）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <conversionRule conversionWord="traceId"
        converterClass="io.agentmanager.framework.service.TraceIdConverter"/>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%traceId] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO"><appender-ref ref="CONSOLE"/></root>
</configuration>
```

| # | 差距 | 影响 |
|---|------|------|
| 1 | 仅 CONSOLE appender，无文件输出 | 容器云不采集控制台，Kibana 无法检索 |
| 2 | pattern 缺 `[HOST_NAME]`、`[app-name]` 字段 | 无法按主机/应用维度筛选 |
| 3 | `%logger{36}`（带包名 36 字符截断） | 规范要求 `%c{1}` 类简名 |
| 4 | `%msg` 无 `{nolookups}` | 安全防护缺失（logback 下为兼容写法） |
| 5 | encoder 未声明 `<charset>UTF-8</charset>` | 中文可能乱码 |
| 6 | 无滚动归档 | 日志不可控增长（若直写文件） |

### 2.2 已具备、无需改动的基础

| 组件 | 位置 | 说明 |
|------|------|------|
| TraceIdConverter | `service/TraceIdConverter.java` | 已实现：从 `Span.current()` 取 OTel traceId，无活跃 span 输出 `-`，与规范 `[%traceId]` 语义一致 |
| InMemoryLogAppender | `service/InMemoryLogAppender.java` | 调试页 `/debug/logs` 内存环形缓冲，独立于文件/控制台 appender，不受影响 |
| OTel 链路 | OTel Java Agent（Dockerfile 内置，`OTEL_EXPORTER_OTLP_ENDPOINT` 启用） | traceId 关联 Jaeger 闭环已有，仅日志格式变化 |

### 2.3 部署链路（本项目特有，与通用模板的差异）

agent-framework 镜像在本项目中有两类运行形态，**均由平台后端构造 Pod**，仓库内不存在独立的 `k8s/deployment.yaml`：

1. **平台业务服务**（`oaf-release-agent`、`oaf-mcdonalds-*` 等）：`backend/internal/k8s/objects.go` 的 `Deployment()` 构造 Deployment+Service+Ingress，固定注入保留键 env（`AGENT_CONFIG_DIR`/`AGENT_WORKSPACE_DIR`/`SERVER_HOST`/`SERVER_PORT`），用户 env 经 envFrom ConfigMap 注入
2. **保留键校验**：`backend/internal/service/reserved.go` 直接引用 `k8s.ReservedEnvKeys`，向该 map 加键即可自动获得"用户 env 冲突 → 400"拦截

因此 `HOST_NAME`/`APP_NAME` 注入与 `/applog` 挂载的落点是 **`backend/internal/k8s/objects.go`**，而非新建部署模板。

---

## 3. 变更范围

| 变更项 | 文件 | 变更类型 |
|--------|------|----------|
| Logback 配置 | `agent-framework/src/main/resources/logback-spring.xml` | 修改（核心变更） |
| 启动参数 | `agent-framework/Dockerfile` | 修改 |
| Pod 注入 | `backend/internal/k8s/objects.go` | 修改 |
| 单测同步 | `backend/internal/k8s/objects_test.go` | 修改 |
| 本地启动 | `agent-framework/Makefile`（`run`/`dev` 目标） | 修改 |

> 已确认决策（2026-09-15）：平台**只注入 `HOST_NAME`**；`APP_NAME` 不注入、不设保留键，纯走环境变量获取（用户经业务 env 自行指定），logback 未取到时回退默认 `agent-framework`。GC 日志（非应用日志）本期不做。

**明确不改动**（与参考文档的差异，见 §4.5）：

- `application.yml` — 无需变更
- 任何 Java 代码（TraceIdConverter / InMemoryLogAppender / LLMLogger / LlmLoggingMiddleware 等）
- Dockerfile.dev（离线开发镜像，仅跑 Maven 不跑应用）

---

## 4. 变更详细设计

### 4.1 logback-spring.xml（核心变更）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- 自定义转换器：从 OTel Context 取 traceId（无活跃 span 输出 "-"） -->
    <conversionRule conversionWord="traceId"
        converterClass="io.agentmanager.framework.service.TraceIdConverter"/>

    <!--
      属性定义（logback 查找顺序：本地属性 → system properties → OS 环境变量）
      app-name: 应用名，容器内由平台注入 APP_NAME（业务服务为各自 K8s 名），缺省 agent-framework
      log-path: 日志目录，HOST_NAME 由 K8s downward API 注入（fieldRef metadata.name），本地缺省 localhost
    -->
    <property name="app-name" value="${APP_NAME:-agent-framework}"/>
    <property name="log-path" value="/applog/${HOST_NAME:-localhost}"/>

    <!--
      规范日志格式（《容器日志收集方案-v2》）
      时间 [主机名] [应用名] [级别] [线程] [traceId] 类简名 - 内容
      %msg{nolookups} 为 Log4j2 防 lookup 攻击的兼容写法（logback 下为无副作用占位，按规范保留）
    -->
    <property name="LOG_PATTERN"
        value="%d{yyyy-MM-dd HH:mm:ss.SSS} [${HOST_NAME:-localhost}] [${app-name}] [%-5level] [%t] [%traceId] %c{1} - %msg{nolookups}%n"/>

    <!-- 控制台输出（本地开发/调试用；容器云不采集控制台日志） -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>${LOG_PATTERN}</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <!--
      文件输出（容器云日志采集唯一来源：/applog/${HOST_NAME}/trace.log，文件名固定不可改）
      滚动策略与规范 Log4j2 示例等价（SizeBasedTriggeringPolicy 200MB + DefaultRolloverStrategy max=2）：
      FixedWindowRollingPolicy(1..2) → 归档 trace.log1 / trace.log2，
      活动文件 + 2 归档共 3 个文件，上限 600MB
    -->
    <appender name="COLLECTING_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${log-path}/trace.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.FixedWindowRollingPolicy">
            <fileNamePattern>${log-path}/trace.log%i</fileNamePattern>
            <minIndex>1</minIndex>
            <maxIndex>2</maxIndex>
        </rollingPolicy>
        <triggeringPolicy class="ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy">
            <maxFileSize>200MB</maxFileSize>
        </triggeringPolicy>
        <encoder>
            <pattern>${LOG_PATTERN}</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <!-- 本地开发（dev profile）：仅控制台，不写 /applog -->
    <springProfile name="dev">
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>

    <!-- 容器及其他环境（含未指定 profile 的 default）：控制台 + 规范文件输出 -->
    <springProfile name="!dev">
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
            <appender-ref ref="COLLECTING_FILE"/>
        </root>
    </springProfile>
</configuration>
```

设计要点：

| 点 | 决策 | 理由 |
|----|------|------|
| 滚动策略选型 | `FixedWindowRollingPolicy(1..2)` + `SizeBasedTriggeringPolicy(200MB)`，**不用** `SizeAndTimeBasedRollingPolicy` | 规范要求"归档恰好 2 个、命名 trace.log1/trace.log2"。logback 的 `SizeAndTimeBasedRollingPolicy` 中 `maxHistory` 语义是"保留的时间周期数（天）"而非归档个数，且 `%i` 从 0 起（会生成 trace.log0），均不满足规范；FixedWindow 是规范 Log4j2 示例（SizeBasedTriggeringPolicy + DefaultRolloverStrategy max=2）的忠实等价 |
| profile 划分 | `dev` 仅控制台；`!dev`（含未设 profile 的 default）控制台+文件 | 容器内不设 `SPRING_PROFILES_ACTIVE`，自然落入 `!dev` 启用文件输出；本地 `make run`/`make dev` 显式设 `dev`，避免向开发机 `/applog`（无权限）写文件 |
| `${HOST_NAME}` 兜底 | pattern 与路径均写 `${HOST_NAME:-localhost}` | logback 属性替换发生在配置解析期；环境变量缺失时回退 `localhost` 而非输出 `HOST_NAME_IS_UNDEFINED` |
| `%msg{nolookups}` | 保留 | logback 对未知转换选项忽略，无副作用；按规范保留以对齐银行 Log4j2 模板 |
| `%t` 线程名 | 直接使用 | 规范原文即 `%t`，Tomcat 下形如 `[http-nio-8100-exec-1]` |

### 4.2 Dockerfile

两处修改（`agent-framework/Dockerfile`）：

1. **`-Dfile.encoding=UTF-8` 硬编码进 ENTRYPOINT**，置于 `$JAVA_OPTS` 之前：

```dockerfile
# 修改前
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS $( [ -n \"${OTEL_EXPORTER_OTLP_ENDPOINT:-}\" ] && echo -javaagent:/app/opentelemetry-javaagent.jar ) -jar /app/agent-framework.jar"]

# 修改后
ENTRYPOINT ["sh", "-c", "exec java -Dfile.encoding=UTF-8 $JAVA_OPTS $( [ -n \"${OTEL_EXPORTER_OTLP_ENDPOINT:-}\" ] && echo -javaagent:/app/opentelemetry-javaagent.jar ) -jar /app/agent-framework.jar"]
```

> 不放入 `ENV JAVA_OPTS`：`JAVA_OPTS` 设计为部署方可覆盖（注释明示），覆盖后编码参数会丢失。

2. **RUN 链追加 `/applog` 目录**（镜像内无挂载时的兜底，未挂卷时文件 appender 不至于因目录不可建而报错刷屏；挂卷后该目录被覆盖，不影响）：

```dockerfile
    && mkdir -p /config /applog \
    # K8s ConfigMap/Volume 挂载会覆盖目录属主为 root，故 chmod 777 保证非 root 用户可写
    && chmod 777 /config /applog \
    && chown -R appuser:appuser /config /applog
```

> 注：JDK 21（JEP 400）默认编码已是 UTF-8，显式参数按规范要求保留，无副作用。

### 4.3 Pod 注入 — backend/internal/k8s/objects.go

#### 4.3.1 保留键扩展

```go
var ReservedEnvKeys = map[string]bool{
	"AGENT_CONFIG_DIR": true, "AGENT_WORKSPACE_DIR": true, "SERVER_HOST": true, "SERVER_PORT": true,
	// 日志规范注入键（logging-standardization-plan §4.3）：HOST_NAME=Pod Name
	"HOST_NAME": true,
}
```

`backend/internal/service/reserved.go` 引用该 map，用户 env 冲突即自动 400，无需另改。

> `APP_NAME` **不加入保留键**（已确认决策）：平台不强制注入，用户可经业务 env 自行指定应用名；未设置时 logback 回退默认 `agent-framework`。

#### 4.3.2 固定 env 注入（Deployment() 内 fixedEnv 追加）

```go
fixedEnv := []corev1.EnvVar{
	{Name: "AGENT_CONFIG_DIR", Value: "/config"},
	{Name: "AGENT_WORKSPACE_DIR", Value: "/workspace"},
	{Name: "SERVER_HOST", Value: "0.0.0.0"},
	{Name: "SERVER_PORT", Value: fmt.Sprintf("%d", AgentPort)},
	// 日志规范：HOST_NAME=Pod Name（downward API fieldRef metadata.name），
	// logback 据此拼日志路径 /applog/${HOST_NAME} 与日志内容 [HOST_NAME] 字段；
	// APP_NAME 不注入，由业务 env 可选指定，缺省 agent-framework
	{Name: "HOST_NAME", ValueFrom: &corev1.EnvVarSource{FieldRef: &corev1.ObjectFieldSelector{FieldPath: "metadata.name"}}},
}
```

#### 4.3.3 卷挂载（/applog，emptyDir）

```go
// VolumeMounts 追加
{Name: "applog", MountPath: "/applog"},

// Volumes 追加（与 /workspace emptyDir 同模式，集群已验证非 root appuser 可写）
{Name: "applog", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}},
```

要点：

| 点 | 决策 | 理由 |
|----|------|------|
| `HOST_NAME` 取值 | `fieldRef: metadata.name`（downward API，用户已确认） | Pod Name 全局唯一，对应规范"容器平台自动传入"；无 RBAC 要求 |
| `APP_NAME` | 平台不注入、不设保留键，环境变量可选 | 已确认：按规范"部署配置中手动指定"，用户 env 指定即用，缺省回退 `agent-framework`（logback `${APP_NAME:-agent-framework}`） |
| 卷类型 | emptyDir | 采集器在本项目（kind）仅按容器内路径采集即可验证；生产灵雀云环境由运维按规范改挂 hostPath `/var/log/mounts/${namespace}/${app-name}`（平台不动此语义）。`/workspace` 已是 emptyDir 且非 root 可写，有集群实证 |
| 生效范围 | 全部业务 Pod（release-agent、mcdonalds-* 等） | 同一框架镜像，统一注入即统一合规 |

> `SPRING_PROFILES_ACTIVE` 平台不注入：容器缺省 profile 落 `!dev` 分支，文件输出天然开启。

### 4.4 Makefile 本地启动

`agent-framework/Makefile` 的 `run` / `dev` 目标统一前置 `SPRING_PROFILES_ACTIVE=dev`，本地仅控制台输出、不写 `/applog`（本地无该目录写权限，且规范路径本就面向容器）。

### 4.5 与参考设计文档的差异说明

参考文档（对话附带稿）已按本仓库实际核对修正：

| 参考文档假设 | 本仓库实际 | 修正 |
|--------------|-----------|------|
| 存在 `k8s/deployment.yaml` 可改 | 业务 Pod 由 backend `objects.go` 构造，仓库无该文件 | 改落 `backend/internal/k8s/objects.go`（§4.3） |
| `application.yml` 增加 `logging.charset.*` | Spring `logging.charset.*` 仅作用于 Spring 自动装配 appender，本项目 logback-spring.xml 完全接管后无效 | 不改 application.yml，charset 在 encoder 显式声明 |
| `SizeAndTimeBasedRollingPolicy` + `maxHistory=2` | `maxHistory` 是时间周期数而非归档个数，`%i` 从 0 起 | `FixedWindowRollingPolicy(1..2)` + `SizeBasedTriggeringPolicy`（§4.1） |
| APP_NAME 统一 `agent-framework` | 一镜像多业务服务 | 平台不注入、不设保留键，环境变量可选指定，缺省 `agent-framework`（§4.3.2，已确认） |

---

## 5. 影响分析

### 5.1 功能与兼容性

| 影响项 | 说明 | 风险 |
|--------|------|------|
| 日志格式 | 所有日志行新增 `[HOST_NAME]` `[app-name]` 字段、logger 变类简名 | 🟢 低，仅结构变化 |
| 业务逻辑 | 不涉及任何 Java 代码改动，A2A/SSE/MCP/HITL 均不受影响 | 🟢 低 |
| TraceIdConverter / InMemoryLogAppender | 完全不变；调试页 `/debug/logs` 使用 InMemoryLogAppender 自有格式，与文件格式解耦 | 🟢 无 |
| 保留键扩展 | `HOST_NAME` 成为平台保留键，用户 env 同名即 400 | 🟢 低，全大写命名与用户业务 env 撞名概率极低；`APP_NAME` 不设保留键，用户可自由指定 |
| 磁盘占用 | 每 Pod 日志上限 600MB（emptyDir 计入临时存储，极端情况触发 kubelet 临时存储驱逐线） | 🟢 低，200MB×3 有硬顶 |
| backend 单测 | `objects_test.go` 需同步新增 env/volume 断言 | 🟢 低 |
| Kibana 索引 | 新增 HOST_NAME/app-name 字段需索引模板映射（运维侧） | ⚠️ 平台外依赖 |

### 5.2 已知风险：单条日志 4KB 约束

规范要求单条 ≤ 4KB。框架当前日志以简要行为主，但 LLM 相关日志（LlmLoggingMiddleware/LLMLogger）若未来整包输出请求/响应体可能超限（Kibana 截断）。本期不改内容逻辑，**列为后续可选优化**：对超长 msg 截断至 4KB。另规范"禁止敏感信息"项建议后续对 LLM API Key 等做一次日志审计（本期不动）。

---

## 6. 测试验证方案

### 6.1 本地

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | `cd agent-framework && mvn test` | 456 用例全绿（配置改动不影响单测） |
| 2 | `make dev`（dev profile） | 控制台输出 `[localhost] [agent-framework] [INFO ] [main] [-] ...` 格式，无文件生成 |
| 3 | `SPRING_PROFILES_ACTIVE=sit java -jar target/*.jar` | 控制台 + `/applog/localhost/trace.log` 同时输出，`file -i` 验证 UTF-8 |
| 4 | 触发一次对话/HTTP 请求（OTel 启用时） | `[%traceId]` 输出 32 位 traceId；无请求时输出 `[-]` |

### 6.2 容器/集群

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | `make docker-build`，导入 kind | 构建成功 |
| 2 | `cd backend && make test` | 保留键扩展后单测全绿 |
| 3 | 平台重新发布任一业务服务 | Pod 正常 Running，无 CrashLoopBackOff |
| 4 | `kubectl exec ... env \| grep HOST_NAME` | `HOST_NAME=<pod名>`；未设 `APP_NAME` 时日志 `[agent-framework]` 缺省应用名 |
| 5 | `kubectl exec ... ls /applog/$HOSTNAME`（或 exec 查看） | `trace.log` 存在且持续写入，格式符合规范 Pattern |
| 6 | 用户 env 携带 `HOST_NAME=x` 调 PATCH env | 返回 400（保留键拦截）；携带 `APP_NAME=xxx` 正常保存并生效于日志 |
| 7 | 回归：平台对话/A2A/SSE/MCP 调用 | 功能不受影响 |

> 银行容器云生产环境的 hostPath 挂载（`/var/log/mounts/${namespace}/${app-name}`）与 Kibana 索引映射属运维侧动作，按规范 §9 由部署方执行。

---

## 7. 回滚方案

| 文件 | 回滚动作 |
|------|----------|
| `logback-spring.xml` | git revert（恢复 CONSOLE 单 appender 旧 pattern） |
| `Dockerfile` | 移除 `-Dfile.encoding=UTF-8` 与 `/applog` 目录创建 |
| `objects.go` + `objects_test.go` | git revert（保留键、env、卷恢复原状） |
| `Makefile` | 移除 `SPRING_PROFILES_ACTIVE=dev` |

回滚后仅日志退回"仅控制台输出"，已落盘/已采集历史日志不受影响，业务零影响。

---

## 8. 已确认决策（2026-09-15）

1. 整体方案（§4.1–§4.4）确认，按此实施。
2. **APP_NAME**：平台不注入、不设保留键，纯环境变量获取，未设置时 logback 回退默认 `agent-framework`。
3. **GC 日志（非应用日志）**：本期不做。
