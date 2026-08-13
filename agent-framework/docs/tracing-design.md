# agent-framework 链路追踪设计方案

> **状态: 🚧 已实施 + 单元测试全绿 + Jaeger E2E 验证通过（2026-08-13）**
>
> - 步骤 1–13 完成：300 用例 0 失败；第十节 10 项验证全部通过
> - **最终选型：OTel Java Agent v2.12.0**（4.7 方案 A）——自研 HTTP Filter 方案实测 MVC 异步断链（验证 #5 失败），Agent 方案单一 trace_id 达成。生产部署走 Agent；`OtelConfig`/`HttpTracingFilter` 保留为无 Agent 环境的 fallback（第七节验证结果、第八节风险状态）
> - 步骤 14 完成：Dockerfile 内置 agent jar + 自动注入 + Makefile `otel-agent` 目标（2026-08-13 验证 ✅，见 4.7 与第十节）
> - 步骤 15 完成：Dockerfile.dev 预置 javaagent jar + OTel 1.61.0 依赖缓存，已导出离线镜像（2026-08-13）
> - 全部 15 个实施步骤完成 ✅

## 一、背景与目标

agent-framework 当前仅有 `LlmLoggingMiddleware`（内存记录，debug 页面消费），无分布式 trace 能力。目标是接入 **OpenTelemetry**，实现：

- 每次 Agent 调用产生完整 trace（agent → model → tool 三层 span）
- 可对接 Jaeger / Langfuse / 任意 OTLP 后端
- 保留现有 `LlmLoggingMiddleware`（debug 页面不受影响）
- 通过环境变量开关，零代码禁用

---

## 二、关键发现：SDK 已内置 OtelTracingMiddleware

```
agentscope-core-2.0.0.jar
├── io/agentscope/core/tracing/OtelTracingMiddleware.class   ← 已存在
├── io/agentscope/core/middleware/MiddlewareBase.class         ← 5 个拦截点
└── 依赖: opentelemetry-api 1.37.0 + opentelemetry-reactor-3.1 2.27.0-alpha
```

> **已修正（实施阶段核实）**：`agentscope-core` 传递引入的 `opentelemetry-api` **实际为 1.37.0**（`mvn dependency:tree` 核实），而非 1.61.0。若引入 1.61.0 的 `opentelemetry-sdk`/`exporter`，必须用 `dependencyManagement` 将 api/context/sdk-trace/sdk-common/exporter 组件**统一提升到 1.61.0**，否则 sdk(1.61) 与 sdk-trace/common(1.37) 混用，启动时抛 `NoClassDefFoundError: StandardComponentId$ExporterType`（4.1 已含完整 dependencyManagement 清单）。

`OtelTracingMiddleware` 实现了 `MiddlewareBase`，覆盖三个切点：

| 切点 | Span 名称 | 采集属性 |
|------|-----------|---------|
| `onAgent` | `invoke_agent <name>` | gen_ai.operation.name, gen_ai.agent.name/id, messages.count |
| `onModelCall` | `chat <model>` | gen_ai.request.model, messages.count, tools.count, usage.input/output_tokens |
| `onActing` | `execute_tool <name>` | gen_ai.tool.name, tool.call.count, tool.call.id |

> **注意**：`OtelTracingMiddleware` 未覆写 `order()`，默认值为 `1`。

> **已修正（实施阶段核实）**：`MiddlewareBase` 实际**无 `order()` 方法**（详见 3.2），执行顺序完全由 `.middleware()` 注册顺序决定，先注册者更外层。

**结论：无需重写核心 tracing 逻辑，直接复用 SDK 内置中间件。** 需要补充的是：

1. OTel SDK + OTLP Exporter 依赖（SDK 只含 API，不含实现）
2. agent-framework 特有属性（userId, sessionId, tenantPrefix）
3. Spring Boot 自动装配 + 环境变量配置

---

## 三、架构设计

### 3.1 组件关系

```
                      ┌─────────────────────────────────┐
                      │         Jaeger / Langfuse        │
                      │       (OTLP HTTP :4318)          │
                      └────────────▲────────────────────┘
                                   │ OTLP HTTP
                      ┌────────────┴────────────────────┐
                      │   OTel SDK (SdkTracerProvider)   │
                      │   + BatchSpanProcessor           │
                      │   + OtlpHttpSpanExporter         │
                      └────────────▲────────────────────┘
                                   │ GlobalOpenTelemetry
  ┌────────────┬────────────┬────────────┬────────────┬──────────────┐
  │ HTTP Span  │ OtelTracing│ Framework  │ Reasoning  │ Tracing      │
  │ Filter     │ Middleware │ Tracing    │ Tracing    │ Sandbox      │
  │ (必需)     │ (SDK 内置) │ Middleware │ Middleware │ Client       │
  │            │            │ (自定义)   │ (自定义)   │ (自定义)     │
  │ POST /     │ onAgent    │ onAgent    │ onReasoning│ create()     │
  │ /chat/     │ onModelCall│ onModelCall│            │ resume()     │
  │ stream     │ onActing   │ onActing   │            │ delete()     │
  └────────────┴────────────┴────────────┴────────────┴──────────────┘
        │             │            │            │
        └─────────────┴────────────┴────────────┘
              TraceIdLogEnricher (logback，日志 trace_id 关联)

另：TracingModelWrapper（自定义 Model 装饰器）包装 memory/compaction 使用的
    model，为其内部直接调用的 LLM 请求创建 span（不经过 middleware 链）。
```

### 3.2 中间件顺序与执行流程

> **已修正（实施阶段核实 SDK 2.0.0 字节码）**：`MiddlewareBase` **无 `order()` 方法**（仅有 5 个钩子默认方法），此前"按 order() 降序排序"的假设错误。实际机制：
> - `ReActAgent` 构造器将中间件存为 `List.copyOf([GracefulShutdownMiddleware, ...builder.middlewares])`，**无排序**
> - `MiddlewareChain.build()` 从列表末尾向前遍历构建洋葱链 → **注册顺序 = 执行顺序，先注册者 = 更外层 = 更先执行**
> - 结论：执行位置由 `.middleware()` **调用顺序**决定。当前注册顺序（AgentScopeConfig）：`Otel → Framework → Reasoning → LlmLogging → SandboxUserKey`，恰好满足"Otel 最外层、Framework/Reasoning 内层"的设计意图。

```
执行顺序（外 → 内）＝注册顺序（.middleware() 调用顺序）：

┌─────────────────────────────────────────────────────────────┐
│  MiddlewareChain 执行顺序（外 → 内）                          │
│                                                             │
│  ┌─ OtelTracingMiddleware（最先注册，最外层）───────────────┐ │
│  │  创建 span，通过 ContextPropagationOperator.runWithContext │ │
│  │  将 span context 注入 Reactor Context                    │ │
│  │                                                         │ │
│  │  ┌─ FrameworkTracingMiddleware（第 2 注册）────────────┐  │ │
│  │  │  next.apply(input) 内部执行时，Span.current()      │  │ │
│  │  │  已指向 Otel 创建的 span → 写入 userId/sessionId   │  │ │
│  │  │                                                    │  │ │
│  │  │  ┌─ ReasoningTracingMiddleware（第 3 注册）───────┐  │  │ │
│  │  │  │  onReasoning: 创建每轮推理 span                │  │  │ │
│  │  │  │                                                │  │  │ │
│  │  │  │  ┌─ LlmLoggingMiddleware（第 4 注册）───────┐  │  │  │ │
│  │  │  │  │  onModelCall: 记录到 LLMLogger 内存      │  │  │  │ │
│  │  │  │  │                                          │  │  │  │ │
│  │  │  │  │  ┌─ SandboxUserKeyMiddleware（最内层）─┐  │  │  │  │ │
│  │  │  │  │  │  onAgent: 注入 userId 到 ThreadLocal│  │  │  │  │ │
│  │  │  │  │  │  (实际核心 agent 逻辑)              │  │  │  │  │ │
│  │  │  │  │  └────────────────────────────────────┘  │  │  │  │ │
│  │  │  │  └──────────────────────────────────────────┘  │  │  │ │
│  │  │  └────────────────────────────────────────────────┘  │  │ │
│  │  └──────────────────────────────────────────────────────┘  │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘

注：SandboxUserKeyMiddleware 仅在沙箱模式（SANDBOX_ENABLED=true）下注册。
```

**Reasoning 嵌套关系**（ReasoningTracingMiddleware 末实现 onAgent/onModelCall 为直通；OtelTracingMiddleware 未实现 onReasoning 为直通）：

```
invoke_agent <name>                     ← Otel.onAgent（外层）
  ├─ reasoning                          ← ReasoningTracingMiddleware.onReasoning（第 1 轮）
  │    └─ chat <model>                  ← Otel.onModelCall（嵌套在 reasoning 内）
  ├─ execute_tool <name>                ← Otel.onActing（与 reasoning 平级，工具执行阶段）
  ├─ reasoning                          ← 第 2 轮
  │    └─ chat <model>
  └─ ...
```

> reasoning span 与 chat span 的父子关系成立的原因：`ReasoningTracingMiddleware.onReasoning` 用 `ContextPropagationOperator.runWithContext()` 包裹 `next.apply(input)`，模型调用（onModelCall 链）在 reasoning 核心逻辑内执行，Otel 的 `onModelCall` 从 Reactor Context 解析父级时读到 reasoning span。

### 3.3 Span 上下文传播时序

```
OtelTracingMiddleware.onAgent():
  1. Flux.deferContextual(ctxView → {
  2.   parentCtx = resolveOtelContext(ctxView)     // 从 Reactor Context 读取父 span
  3.   span = tracer.spanBuilder("invoke_agent X").setParent(parentCtx).startSpan()
  4.   otelCtx = span.storeInContext(parentCtx)     // span 写入 OTel Context
  5.   ContextPropagationOperator.runWithContext(    // 将 otelCtx 注入 Reactor Context
         next.apply(input),                          //   ↓
         otelCtx)                                    // FrameworkTracingMiddleware.onAgent()
       })                                              Span.current() → 上面创建的 span ✅
```

关键：`ContextPropagationOperator.runWithContext()` 将 OTel Context 注入 Reactor 的 `Context`，下游（包括 FrameworkTracingMiddleware）在同一订阅链中通过 `Span.current()` 即可读取。

### 3.4 沙箱生命周期 Trace 缺口

沙箱操作在 middleware 链**外部**执行，`OtelTracingMiddleware` 无法覆盖：

```
HarnessAgent.wrappedCall()
  │
  ├─ Mono.using.resourceSupplier:
  │    SandboxLifecycleMiddleware.acquireForCall(ctx)     ← 沙箱创建/恢复（middleware 链外）
  │      ├─ SandboxManager.acquire()
  │      │    ├─ OpenSandboxClient.create()               ← HTTP 调用，创建远程沙箱
  │      │    └─ 或 OpenSandboxClient.resume()            ← HTTP 调用，恢复已有沙箱
  │      └─ sandbox.start()                               ← 工作区投影
  │
  ├─ Mono.using.resource:
  │    middleware chain 执行                                ← OtelTracingMiddleware 在这里
  │      ├─ onAgent → invoke_agent span
  │      │    ├─ onModelCall → chat span
  │      │    └─ onActing → execute_tool span
  │      └─ ...
  │
  └─ Mono.using.resourceClosure:
       SandboxLifecycleMiddleware.releaseForCall(ctx)     ← 沙箱停止（middleware 链外）
         ├─ sandboxManager.persistState()                  ← 状态保存
         └─ sandboxManager.release() → sandbox.stop()     ← 停止 + KV 回写
```

**缺口清单：**

| 操作 | 耗时 | 被 OtelTracing 捕获？ |
|------|------|----------------------|
| `OpenSandboxClient.create()` — 创建远程沙箱 | **秒级** | ❌ 缺失 |
| `OpenSandboxClient.resume()` — 恢复沙箱 | **秒级** | ❌ 缺失 |
| `sandbox.start()` — 工作区投影 | 毫秒级 | ❌ 缺失 |
| `sandboxManager.persistState()` — 状态保存 | 毫秒级 | ❌ 缺失 |
| `sandbox.stop()` + `syncBack()` — 停止回写 | 毫秒级 | ❌ 缺失 |
| `OpenSandboxClient.delete()` — 销毁沙箱 | 毫秒级 | ❌ 缺失 |

**根因**：`SandboxLifecycleMiddleware` 实现了 `HarnessRuntimeMiddleware` 但**不覆写任何 middleware 钩子**。它的 `acquireForCall()`/`releaseForCall()` 由 `HarnessAgent` 通过 `Mono.using` 在 middleware 链外调用。

**解决方案**：新增 `TracingSandboxClient`，包装 `OpenSandboxClient`，在 create/resume/stop/delete 操作前后创建 OTel span。沙箱操作是同步阻塞调用（非 Reactor 响应式），直接使用 `try-finally` 管理 span 生命周期即可。

### 3.5 沙箱 Trace 修正后的完整 Span 结构

```
HTTP POST / 或 GET /chat/stream             ← HTTP Span Filter（共同父 span，必需）
  ├─ sandbox.create                        ← TracingSandboxClient.create()（秒级）
  ├─ invoke_agent <name>                   ← OtelTracingMiddleware.onAgent()
  │    ├─ reasoning                        ← ReasoningTracingMiddleware（第 1 轮）
  │    │    └─ chat <model>                ← OtelTracingMiddleware.onModelCall()
  │    ├─ execute_tool <name>              ← OtelTracingMiddleware.onActing()
  │    ├─ memory                           ← TracingModelWrapper（flush/consolidation 触发时）
  │    ├─ compaction                       ← TracingModelWrapper（压缩触发时）
  │    └─ ...
  └─ sandbox.delete                        ← TracingSandboxClient.delete()（显式销毁时）

非沙箱模式：仅 HTTP → invoke_agent 子树。
```

> **注意 1**：`sandbox.create`/`sandbox.delete` 与 `invoke_agent` 是**平级**关系（非父子），因为沙箱操作在 middleware 链外执行，无法嵌入 `invoke_agent` span 内部。这是 SDK 架构限制，不影响耗时分析。
>
> **注意 2**：HTTP Span Filter **不可省略**。沙箱操作在 `Mono.using` resourceSupplier 中执行、`invoke_agent` 在 middleware 链内执行，两者都依赖 HTTP span 作为共同父级。**没有 HTTP span 时，一次 Agent 调用会产出多个互不关联的独立 trace**（沙箱 span、agent span 各自成为根 span），链路彻底断裂。
>
> **注意 3**：`sandbox.stop` 未覆盖（`SandboxManager.release()` 绕过 `SandboxClient` 直接调用 `sandbox.stop()`），仅显式销毁场景有 `sandbox.delete` span。详见风险 #9。

---

## 四、需要新增/修改的文件

### 4.1 pom.xml — 新增依赖

```xml
<properties>
    <opentelemetry.version>1.61.0</opentelemetry.version>
</properties>

<dependencyManagement>
    <dependencies>
        <!-- 统一 OTel 组件版本：agentscope-core 传递 1.37.0，必须全部提升到 1.61.0，
             否则 sdk(1.61) 与 sdk-trace/common(1.37) 混用启动即崩 -->
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-api</artifactId>
            <version>${opentelemetry.version}</version>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-context</artifactId>
            <version>${opentelemetry.version}</version>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-sdk</artifactId>
            <version>${opentelemetry.version}</version>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-sdk-common</artifactId>
            <version>${opentelemetry.version}</version>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-sdk-trace</artifactId>
            <version>${opentelemetry.version}</version>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-sdk-metrics</artifactId>
            <version>${opentelemetry.version}</version>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-sdk-logs</artifactId>
            <version>${opentelemetry.version}</version>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-sdk-testing</artifactId>
            <version>${opentelemetry.version}</version>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-exporter-common</artifactId>
            <version>${opentelemetry.version}</version>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-exporter-otlp</artifactId>
            <version>${opentelemetry.version}</version>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-exporter-otlp-common</artifactId>
            <version>${opentelemetry.version}</version>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-exporter-sender-okhttp</artifactId>
            <version>${opentelemetry.version}</version>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-sdk-extension-autoconfigure-spi</artifactId>
            <version>${opentelemetry.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

```xml
<!-- OTel SDK + OTLP Exporter (OTLP HTTP 协议) -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
    <version>${opentelemetry.version}</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
    <version>${opentelemetry.version}</version>
</dependency>
<!-- 测试：InMemorySpanExporter 捕获 span 断言，无需真实 Jaeger -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk-testing</artifactId>
    <version>${opentelemetry.version}</version>
    <scope>test</scope>
</dependency>
```

> `opentelemetry-api` 和 `opentelemetry-reactor-3.1` 已由 `agentscope-core` 传递引入，无需重复声明；api 版本经 dependencyManagement 统一为 1.61.0。
>
> **离线环境注意**：`opentelemetry-sdk-testing` 为新增依赖，内网离线开发镜像（Dockerfile.dev）需同步更新依赖缓存后导出，否则离线 `mvn -o test` 失败。

### 4.2 新增 `FrameworkTracingMiddleware.java`

路径：`src/main/java/io/agentmanager/framework/service/FrameworkTracingMiddleware.java`

**职责**：在 SDK 的 `OtelTracingMiddleware` 基础上补充 agent-framework 特有的业务属性。不重复创建 span，而是通过 `Span.current()` 向 OtelTracingMiddleware 已创建的 span 写入额外属性。**覆盖 onAgent/onModelCall/onActing 三个钩子**，保证 userId/sessionId/tenantPrefix 出现在所有层级的 span 上（而非仅根 span）。

**顺序保证**：执行顺序由注册顺序决定（`MiddlewareChain` 按注册序构建，先注册者更外层，已核实 SDK 2.0.0 字节码，无 order() 机制）。AgentScopeConfig 中本中间件在 `OtelTracingMiddleware` **之后**注册，故在其内层执行。`Span.current()` 在 `next.apply(input)` 内部调用时，OTel Context 已通过 `ContextPropagationOperator.runWithContext()` 注入 Reactor Context。

```java
package io.agentmanager.framework.service;

import java.util.function.Function;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.opentelemetry.api.trace.Span;
import reactor.core.publisher.Flux;

/**
 * 框架级链路追踪中间件：补充 agent-framework 特有业务属性到 OTel span。
 *
 * <p>依赖 OtelTracingMiddleware 已创建的 span。
 * OtelTracingMiddleware 通过 ContextPropagationOperator.runWithContext() 将
 * OTel Context 注入 Reactor Context，本中间件在 next.apply() 内部执行时
 * Span.current() 即可读取到 Otel 创建的 span。
 *
 * <p>执行顺序由注册顺序决定（MiddlewareChain 按注册序构建，先注册者更外层），
 * AgentScopeConfig 中本中间件在 OtelTracingMiddleware 之后注册。
 *
 * <p>覆盖 onAgent/onModelCall/onActing 三个钩子，保证 userId/sessionId/tenantPrefix
 * 出现在所有 span（invoke_agent/chat/execute_tool）上，而非仅根 span，
 * 使 Jaeger 按会话/用户过滤时子 span 可命中。
 */
public class FrameworkTracingMiddleware implements MiddlewareBase {

    private final String tenantPrefix;

    public FrameworkTracingMiddleware(String tenantPrefix) {
        this.tenantPrefix = tenantPrefix;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnNext(event -> enrichCurrentSpan(ctx));
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnNext(event -> enrichCurrentSpan(ctx));
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input).doOnNext(event -> enrichCurrentSpan(ctx));
    }

    /**
     * 向当前活跃 span 写入业务属性。
     * 在 doOnNext 中执行时，Otel 的 ContextPropagationOperator.runWithContext()
     * 已生效，Span.current() 指向 Otel 创建的 span（invoke_agent / chat / execute_tool）。
     */
    private void enrichCurrentSpan(RuntimeContext ctx) {
        Span span = Span.current();
        if (ctx != null) {
            if (ctx.getUserId() != null && !ctx.getUserId().isBlank()) {
                span.setAttribute("agentscope.user.id", ctx.getUserId());
            }
            if (ctx.getSessionId() != null && !ctx.getSessionId().isBlank()) {
                span.setAttribute("agentscope.session.id", ctx.getSessionId());
            }
        }
        if (tenantPrefix != null && !tenantPrefix.isBlank()) {
            span.setAttribute("agentscope.tenant.prefix", tenantPrefix);
        }
    }
}
```

> **关键设计**：属性写入放在 `doOnNext` 而非 `next.apply()` 之前。`doOnNext` 在事件流中执行时，Otel 的 `runWithContext` 已生效，`Span.current()` 返回正确的 span。若在 `next.apply()` 之前调用 `Span.current()`，可能读到的是父 span 或空 span。

### 4.3 新增 `OtelConfig.java`

路径：`src/main/java/io/agentmanager/framework/config/OtelConfig.java`

**职责**：OTel SDK 初始化 + Bean 注册。仅在 `OTEL_TRACES_EXPORTER=otlp` 时激活。

```java
package io.agentmanager.framework.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

@Configuration
@ConditionalOnProperty(name = "otel.traces.exporter", havingValue = "otlp")
public class OtelConfig {

    @Bean(destroyMethod = "close")
    public SdkTracerProvider sdkTracerProvider(
            @Value("${otel.exporter.otlp.endpoint:http://localhost:4318}") String endpoint,
            @Value("${otel.exporter.otlp.headers:}") String headers,
            @Value("${otel.traces.sampler:always_on}") String sampler,
            @Value("${otel.service.name:agent-framework}") String serviceName
    ) {
        var exporterBuilder = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint + "/v1/traces");

        // 支持自定义 HTTP 头（Langfuse 等需要 Authorization）
        if (headers != null && !headers.isBlank()) {
            for (String header : headers.split(",")) {
                String[] kv = header.split("=", 2);
                if (kv.length == 2) {
                    exporterBuilder.addHeader(kv[0].trim(), kv[1].trim());
                }
            }
        }

        // 采样策略：always_on / always_off / ratio:0.5（比例采样，生产高流量场景）
        // 提取为静态方法便于单元测试（OtelConfigTest）
        var samplerObj = parseSampler(sampler);

        var provider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporterBuilder.build()).build())
                .setSampler(samplerObj)
                .setResource(Resource.getDefault().toBuilder()
                        .put("service.name", serviceName)
                        .build())
                .build();

        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build());

        return provider;
    }

    /** 采样策略解析（static，可单测）：always_on / always_off / ratio:0.5，非法输入回退 always_on */
    static Sampler parseSampler(String sampler) {
        if ("always_off".equals(sampler)) {
            return Sampler.alwaysOff();
        }
        if (sampler != null && sampler.startsWith("ratio:")) {
            try {
                double ratio = Double.parseDouble(sampler.substring("ratio:".length()));
                return Sampler.traceIdRatioBased(Math.max(0.0, Math.min(1.0, ratio)));
            } catch (NumberFormatException e) {
                return Sampler.alwaysOn();
            }
        }
        return Sampler.alwaysOn();
    }
}
```

### 4.4 修改 `AgentScopeConfig.java`

在 `harnessAgent()` 方法中注册 middleware，在 `sandboxFilesystemSpec()` 方法中注入沙箱 tracing：

**middleware 注册（harnessAgent 方法）：**

```java
var builder = HarnessAgent.builder()
    // ...existing config...
    // OTel 链路追踪（SDK 内置，创建 span，最先注册=最外层）
    .middleware(new OtelTracingMiddleware())
    // 框架级属性补充（userId/sessionId/tenant，第 2 注册，覆盖 onAgent/onModelCall/onActing）
    .middleware(new FrameworkTracingMiddleware(oafConfig.slug()))
    // ReAct 推理轮次 span（第 3 注册，覆盖 onReasoning）
    .middleware(new ReasoningTracingMiddleware())
    // LLM 调用记录（debug 页面，第 4 注册，保留）
    .middleware(new LlmLoggingMiddleware(llmLogger))
    // ...rest...
```

**沙箱 tracing 注入（已修正：SDK 的 `SandboxFilesystemSpec` 无 `setSandboxClient()` 方法，改为在 `OpenSandboxFilesystemSpec.createClient()` 内包装）：**

```java
// OpenSandboxFilesystemSpec.createClient() 方法中（已实施）
@Override
protected SandboxClient<?> createClient() {
    // 包装 TracingSandboxClient：沙箱 create/resume/delete 操作创建 OTel span。
    // OTEL_TRACES_EXPORTER=none 时 GlobalOpenTelemetry 返回 no-op tracer，零开销。
    return new TracingSandboxClient(
        new OpenSandboxClient(clientOptions(), workspaceReader, workspaceSyncService, this));
}
```

> **注册顺序说明**：执行顺序 = 注册顺序（无 order() 机制，见 3.2）。实际注册：`Otel → Framework → Reasoning → LlmLogging → SandboxUserKey(沙箱模式)`，Otel 最外层。
>
> **沙箱注入点说明**：`SandboxFilesystemSpec.createClient()`（protected）是 SDK 提供的唯一 `SandboxClient` 工厂入口，`SandboxManager` 在 `acquire()` 时调用 `client.create()` 或 `client.resume()`，经过 `TracingSandboxClient` 包装后自动产生 span。
>
> **memory/compaction model 包装**：见 4.10 `TracingModelWrapper` 装配方式（`MemoryConfig.model()` / `CompactionConfig.model()` 设置包装后的 model）。

### 4.5 修改 `application.yml`

新增 OTel 配置段：

```yaml
# OTel 链路追踪（默认关闭，设置 OTEL_TRACES_EXPORTER=otlp 启用）
otel:
  traces:
    exporter: ${OTEL_TRACES_EXPORTER:none}
    sampler: ${OTEL_TRACES_SAMPLER:always_on}
  exporter:
    otlp:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}
      headers: ${OTEL_EXPORTER_OTLP_HEADERS:}
  service:
    name: ${OTEL_SERVICE_NAME:agent-framework}
```

### 4.6 新增 `TracingSandboxClient.java`

路径：`src/main/java/io/agentmanager/framework/sandbox/opensandbox/TracingSandboxClient.java`

**职责**：包装 `OpenSandboxClient`，在 create/resume/stop/delete 操作前后创建 OTel span。沙箱操作是同步阻塞调用，使用 `try-finally` 管理 span 生命周期。

```java
package io.agentmanager.framework.sandbox.opensandbox;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

/**
 * 沙箱链路追踪包装器：在 OpenSandboxClient 的 create/resume/delete 操作前后创建 OTel span。
 *
 * <p>沙箱操作在 HarnessAgent 的 Mono.using 中执行（middleware 链外），
 * OtelTracingMiddleware 无法覆盖。本类通过装饰器模式补充这部分 trace。
 *
 * <p>注意：仅覆盖 SandboxClient 接口暴露的 create/resume/delete 三个操作；
 * 沙箱 release 路径的 stop 由 SandboxManager 直接调用 sandbox.stop()（绕过本类），
 * 暂无 span（见风险 #9，后续在 WorkspaceSyncService.syncBack() 处埋点补充）。
 *
 * <p>OTEL_TRACES_EXPORTER=none 时 GlobalOpenTelemetry 返回 no-op tracer，零开销。
 */
public class TracingSandboxClient implements SandboxClient<OpenSandboxClientOptions> {

    private static final String INSTRUMENTATION_NAME = "io.agentscope.sandbox";
    private final OpenSandboxClient delegate;

    public TracingSandboxClient(OpenSandboxClient delegate) {
        this.delegate = delegate;
    }

    private Tracer getTracer() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    @Override
    public Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec,
                          OpenSandboxClientOptions options) {
        Span span = getTracer().spanBuilder("sandbox.create")
                .setAttribute("sandbox.image", options.getImage() != null ? options.getImage() : "")
                .startSpan();
        try {
            Sandbox result = delegate.create(workspaceSpec, snapshotSpec, options);
            span.setAttribute("sandbox.id", sandboxIdOf(result.getState()));
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    public Sandbox resume(SandboxState state) {
        Span span = getTracer().spanBuilder("sandbox.resume")
                .setAttribute("sandbox.id", sandboxIdOf(state))
                .startSpan();
        try {
            Sandbox result = delegate.resume(state);
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    public void delete(Sandbox sandbox) {
        Span span = getTracer().spanBuilder("sandbox.delete")
                .setAttribute("sandbox.id", sandboxIdOf(sandbox.getState()))
                .startSpan();
        try {
            delegate.delete(sandbox);
            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** sandboxId 仅存在于 OpenSandboxState（基类 SandboxState 无此字段） */
    private String sandboxIdOf(SandboxState state) {
        if (state instanceof OpenSandboxState osbState && osbState.getSandboxId() != null) {
            return osbState.getSandboxId();
        }
        return "";
    }

    @Override
    public String serializeState(SandboxState state) {
        return delegate.serializeState(state);
    }

    @Override
    public SandboxState deserializeState(String serialized) {
        return delegate.deserializeState(serialized);
    }
}
```

> **为什么用装饰器而非修改 OpenSandboxClient**：保持 OpenSandboxClient 职责单一，tracing 逻辑可独立开关。`SandboxManager` 通过 `SandboxClient` 接口调用，装饰器对上游透明。

### 4.7 新增 `HttpTracingFilter.java`（必需，非可选）

路径：`src/main/java/io/agentmanager/framework/service/HttpTracingFilter.java`

**为什么必需**：`sandbox.create`（`Mono.using` resourceSupplier）、`invoke_agent`（middleware 链）、`sandbox.delete`（resourceClosure）三处 span 创建时都没有活跃父 span。没有 HTTP span 作为共同父级，一次 Agent 调用会产出**多个互不关联的独立 trace**，链路彻底断裂。本 Filter 是唯一能提供共同父 span 的组件。

**覆盖范围**：
- `POST /`（A2A JSON-RPC 入口）
- `GET /chat/stream`（Channel SSE 入口）
- 其余端点（/health、/debug 等）按需覆盖

**实现要点**：

```java
@Component
@ConditionalOnProperty(name = "otel.traces.exporter", havingValue = "otlp")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpTracingFilter extends OncePerRequestFilter {

    private static final String INSTRUMENTATION_NAME = "io.agentmanager.http";

    /** 惰性获取：Filter 构造可能早于 OtelConfig 注册 SDK，字段初始化会拿到永久 no-op tracer */
    private Tracer tracer() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, ServletException {
        // W3C traceparent 提取：上游服务传入的 traceparent 可延续上游 trace
        // （本期不考虑跨服务链路，仅提取不做严格校验也可，为后续预留扩展点）
        Span span = tracer().spanBuilder(request.getMethod() + " " + request.getRequestURI())
                .startSpan();
        span.setAttribute("http.request.method", request.getMethod());
        span.setAttribute("url.path", request.getRequestURI());
        try (var scope = span.makeCurrent()) {
            chain.doFilter(request, response);
            span.setAttribute("http.response.status_code", response.getStatus());
            span.setStatus(response.getStatus() >= 400 ? StatusCode.ERROR : StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
```

**MVC 异步传播注意事项**：

`spring-boot-starter-web`（Tomcat MVC）下 `Flux` 返回值的实际订阅发生在 **async dispatch 线程**，Filter 的 `makeCurrent()` 作用域不会自动跟随。Span 需通过以下方式之一传播到订阅线程：

- **方案 A（最终选型，已验证 ✅）**：**OTel Java Agent**（`JAVA_OPTS=-javaagent:opentelemetry-javaagent.jar`）。Agent 自动完成 HTTP span、MVC 异步传播、跨线程上下文传递，并自动注册 `GlobalOpenTelemetry`。**关键联动**：使用 Agent 时**不设置** `OTEL_TRACES_EXPORTER`（Spring 侧 `application.yml` 默认 `none`，自研 `OtelConfig` 与 `HttpTracingFilter` 因 `@ConditionalOnProperty` 自动不装配；Agent 侧默认 `otlp` 正常导出），SDK 内置的 `OtelTracingMiddleware` 与自研中间件/包装器通过 `GlobalOpenTelemetry.getTracer()` 自动复用 Agent 注册的 SDK，零代码切换。实施验证（2026-08-13）：非沙箱 53 spans / 沙箱 67 spans / 子 Agent 97 spans 全部单一 trace_id，`POST /` → `invoke_agent` → `reasoning` → `chat`/`execute_tool`/`sandbox.create` 层级正确，子 Agent span 正确挂接（风险 #10 预期断链，实测未断）。附带收益：JDBC 查询自动插桩（`SELECT agent_fs` 等 span）。Agent 包下载：`curl -sL https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.12.0/opentelemetry-javaagent.jar`
- **方案 B（自研，保留作备选）**：Filter 创建 span 后，将 OTel Context 写入 Reactor 订阅链——在 `A2AController`/`StreamController` 返回的 `Flux` 上 `.contextWrite(...)` 注入 context，配合已注册的 `ContextPropagationOperator` 传播到异步线程。实现复杂，容易遗漏分支，不推荐。**实测失败**：仅 Filter makeCurrent + runWithContext 时，HTTP span（52ms）与 agent 链（68s）成为两个独立 trace，验证项 #5（单一 trace_id）不通过。

> **结论（2026-08-13 验证后定案）**：采用**方案 A（Java Agent）**。自研 `HttpTracingFilter`/`OtelConfig` 代码保留（非 Agent 环境 fallback + 单元测试覆盖），但生产部署一律走 Agent（`Dockerfile` 已内置 agent jar + 自动注入，见第十节步骤 14）。
>
> **Dockerfile 集成（步骤 14 完成，2026-08-13 验证 ✅）**：镜像内置 `docker/otel/opentelemetry-javaagent.jar`（`make otel-agent` 预下载），ENTRYPOINT 在设 `OTEL_EXPORTER_OTLP_ENDPOINT` 时自动注入 `-javaagent`。验证（agent-framework:otel-test 镜像 + Jaeger）：Docker 容器内 41 spans 单一 trace，`POST /`(×1，无重复) → `invoke_agent Minimal Agent` → `execute_tool echo`，层级正确。
>
> **⚠️ 环境变量坑（实施时实测）**：Dockerfile 曾设 `ENV OTEL_TRACES_EXPORTER=none`，实测 Agent 模式下 **trace 完全不导出**（该变量同时被 Spring 条件注解与 Java Agent exporter 配置读取，设 none 会同时禁掉 Agent 的 otlp exporter）。**正确姿势：`OTEL_TRACES_EXPORTER` 保持 unset**（Spring 侧 `application.yml` 默认 `none` → 自研组件不装配；Agent 侧默认 `otlp` → trace 正常导出）。反之若显式设 `OTEL_TRACES_EXPORTER=otlp`（想走自研 fallback）且同时有 Agent，则 **HTTP span 双份**（Agent servlet 插桩 + 自研 HttpTracingFilter 各一个 `POST /`），实测 52 spans 含 2 个 `POST /`。另：Agent 默认导出 metrics/logs（collector 无对应接收端时 404 噪音），Dockerfile 已设 `OTEL_METRICS_EXPORTER=none` + `OTEL_LOGS_EXPORTER=none`（只保留 traces）。次要发现：`GlobalOpenTelemetry.set()` 在 Agent 存在时被显式忽略并告警 "all GlobalOpenTelemetry.set calls are ignored"——无需代码防治冲突。

### 4.8 新增 `ReasoningTracingMiddleware.java`

路径：`src/main/java/io/agentmanager/framework/service/ReasoningTracingMiddleware.java`

**职责**：为每轮 ReAct 推理（reasoning 阶段）创建独立 span，解决"循环轮次不可见"缺口。SDK 的 `OtelTracingMiddleware` 未实现 `onReasoning` 钩子，无法看出：执行了几轮、每轮耗时、哪轮最慢。

```java
package io.agentmanager.framework.service;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import reactor.core.publisher.Flux;

/**
 * ReAct 推理轮次追踪中间件：每轮 reasoning 创建独立 span。
 *
 * <p>OtelTracingMiddleware 未实现 onReasoning（默认直通），本中间件在其内层执行
 * （AgentScopeConfig 中注册于 Otel 之后，注册顺序=执行顺序，先注册者更外层），
 *
 * <p>模型调用（onModelCall 链）在 reasoning 核心逻辑内执行，本中间件用
 * runWithContext 包裹 next.apply(input)，chat span 自动成为 reasoning span 的子级。
 */
public class ReasoningTracingMiddleware implements MiddlewareBase {

    private static final String INSTRUMENTATION_NAME = "io.agentmanager.framework";
    private final Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(ctxView -> {
            Context parent = ContextPropagationOperator
                    .getOpenTelemetryContextFromContextView(ctxView, Context.current());
            Span span = tracer.spanBuilder("reasoning " + agent.getName())
                    .setParent(parent)
                    .setAttribute("gen_ai.operation.name", "reasoning")
                    .setAttribute("gen_ai.request.messages.count",
                            input.messages() != null ? (long) input.messages().size() : 0L)
                    .startSpan();
            Context otelCtx = span.storeInContext(parent);
            AtomicReference<Boolean> ended = new AtomicReference<>(false);

            return ContextPropagationOperator.runWithContext(
                    next.apply(input)
                            .doOnComplete(() -> {
                                if (ended.compareAndSet(false, true)) {
                                    span.setStatus(StatusCode.OK);
                                    span.end();
                                }
                            })
                            .doOnError(e -> {
                                if (ended.compareAndSet(false, true)) {
                                    span.setStatus(StatusCode.ERROR, e.getMessage());
                                    span.recordException(e);
                                    span.end();
                                }
                            })
                            .doOnCancel(() -> {
                                if (ended.compareAndSet(false, true)) {
                                    span.setStatus(StatusCode.ERROR, "cancelled");
                                    span.end();
                                }
                            }),
                    otelCtx);
        });
    }
}
```

> **轮次编号简化**：本轮设计不携带轮次序号（Reactor 上下文传递每轮计数器的复杂度高、收益低），轮次信息可从 Jaeger 中 span 的时序先后自然推断。后续如需精确轮次，可在 `onAgent` 入口向 Reactor Context 写入计数器，`onReasoning` 中读取递增。

### 4.9 新增 `TraceIdLogEnricher`（日志 trace_id 关联）

**职责**：将 `trace_id`/`span_id` 注入日志，打通"Jaeger trace ↔ 应用日志（含 debug 页面 InMemoryLogAppender）"排障闭环。

**实现分两部分：**

1. **InMemoryLogAppender 增强**：`append()` 时读取 `Span.current().getSpanContext()`，将 `trace_id`/`span_id` 写入存储条目，debug 页面日志展示直接带 trace_id 列。改动最小、收益最大（debug 页面是当前主要排障入口）。
2. **logback 全局 pattern 注入**：新增 `logback-spring.xml`，注册自定义 converter：

```xml
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

```java
// TraceIdConverter.java — 从 OTel Context 读取 trace_id
public class TraceIdConverter extends ch.qos.logback.classic.pattern.ClassicConverter {
    @Override
    public String convert(ILoggingEvent event) {
        Span span = Span.current();
        if (span != null && span.getSpanContext().isValid()) {
            return span.getSpanContext().getTraceId();
        }
        return "-";
    }
}
```

> **可行性说明**：`ContextPropagationOperator.runWithContext` 在信号回调执行期间会 `makeCurrent()`，因此 reactive 链内打日志时 `Span.current()` 有效；同步阻塞代码（沙箱操作、DB 访问）在 span scope 内执行同样有效。不依赖线程局部 MDC，规避了 reactive 跨线程 MDC 丢失的经典问题。
>
> **替代方案（已定案 2026-08-13）**：采用 OTel Java Agent（4.7 方案 A）后，日志关联由 Agent 的 logback 自动注入完成（实测日志出现 `[trace_id]`）；本组件保留，无 Agent 时提供同样的 trace_id 注入（Agent 模式下两者不冲突——本组件的 converter 读取 `Span.current()`，Agent 注册的 SDK 即全局 tracer）。

### 4.10 新增 `TracingModelWrapper.java`（P1：memory/compaction LLM 调用追踪）

路径：`src/main/java/io/agentmanager/framework/service/TracingModelWrapper.java`

**背景**：`MemoryFlushMiddleware`/`MemoryConsolidator`/`CompactionMiddleware` 直接持有 model 实例调用（绕过 middleware 链的 `onModelCall` 钩子，源码确认），其 LLM 调用与 token 用量无 span。记忆 flush/consolidation 是 token 消耗大头，必须可见。

**方案**：实现 `io.agentscope.core.model.Model` 接口的装饰器，包装传入的 model，`stream()` 内部创建 span 后委托。已用 javap 验证 API：`Model.stream(List<Msg>, List<ToolSchema>, GenerateOptions) → Flux<ChatResponse>`，`ChatResponse.getUsage()` 可取用量。

```java
package io.agentmanager.framework.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import reactor.core.publisher.Flux;

/**
 * Model 装饰器：为绕过 middleware 链的 LLM 调用创建 OTel span。
 *
 * <p>用于 memory flush/consolidation（MemoryFlushManager/MemoryConsolidator）
 * 与 compaction（CompactionMiddleware）持有的 model——这三处直接调用
 * model.stream()，不经过 onModelCall 链，OtelTracingMiddleware 无法覆盖。
 *
 * <p>父级：调用发生在 middleware 链的 runWithContext 作用域内（memory flush
 * 在 onAgent 链、compaction 在 onReasoning 链），Context.current() 为
 * invoke_agent 或 reasoning span，子 span 归属同一 trace。已实测
 * （2026-08-13）：memory span 为 invoke_agent 子级（第七节 #9），
 * compaction 同理（onReasoning 链内）。
 */
public class TracingModelWrapper implements Model {

    private static final String INSTRUMENTATION_NAME = "io.agentmanager.framework";

    private static final List<String> TENANT_ATTRIBUTE_KEYS = List.of(
            "agentscope.user.id", "agentscope.session.id", "agentscope.tenant.prefix");

    private final Model delegate;
    private final String spanName;   // "memory" / "compaction"

    public TracingModelWrapper(Model delegate, String spanName) {
        this.delegate = delegate;
        this.spanName = spanName;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                     GenerateOptions options) {
        return Flux.defer(() -> {
            Context parent = Context.current();
            Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME)
                    .spanBuilder(spanName)
                    .setParent(parent)
                    .setAttribute("gen_ai.operation.name", spanName)
                    .setAttribute("gen_ai.request.model", delegate.getModelName())
                    .setAttribute("gen_ai.request.messages.count",
                            messages != null ? (long) messages.size() : 0L)
                    .startSpan();

            // 从父 span 复制租户属性，保证按会话/用户过滤时命中（中间件链的
            // FrameworkTracingMiddleware 不会覆盖本 span）
            copyTenantAttributes(Span.fromContext(parent), span);

            AtomicReference<Boolean> ended = new AtomicReference<>(false);
            return delegate.stream(messages, tools, options)
                    .doOnNext(resp -> {
                        if (resp.getUsage() != null) {
                            span.setAttribute("gen_ai.usage.input_tokens",
                                    (long) resp.getUsage().getInputTokens());
                            span.setAttribute("gen_ai.usage.output_tokens",
                                    (long) resp.getUsage().getOutputTokens());
                        }
                    })
                    .doOnComplete(() -> {
                        if (ended.compareAndSet(false, true)) {
                            span.setStatus(StatusCode.OK);
                            span.end();
                        }
                    })
                    .doOnError(e -> {
                        if (ended.compareAndSet(false, true)) {
                            span.setStatus(StatusCode.ERROR, e.getMessage());
                            span.recordException(e);
                            span.end();
                        }
                    })
                    .doOnCancel(() -> {
                        if (ended.compareAndSet(false, true)) {
                            span.end();
                        }
                    });
        });
    }

    private void copyTenantAttributes(Span parentSpan, Span span) {
        for (String key : TENANT_ATTRIBUTE_KEYS) {
            String value = parentSpan.getAttribute(AttributeKey.stringKey(key));
            if (value != null && !value.isBlank()) {
                span.setAttribute(key, value);
            }
        }
    }

    // ---- 委托方法 ----

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
```

**装配方式**（`AgentScopeConfig.harnessAgent()`）：

```java
var model = OpenAIChatModel.builder()...build();

// P1: 包装 memory/compaction 内部 LLM 调用追踪
// 不设置 .model() 时 harness 回退使用主 model（无 trace），设置包装后行为不变且带 span
var memoryModel = new TracingModelWrapper(model, "memory");           // flush + consolidation 共用
var compactionModel = new TracingModelWrapper(model, "compaction");   // compaction 专用

var builder = HarnessAgent.builder()
    // ...
    .memory(MemoryConfig.builder()
        .flushTrigger(MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(10)))
        .consolidationMaxTokens(8_000)
        .consolidationMinGap(Duration.ofHours(1))
        .model(memoryModel)            // ← 新增：包装后的 model
        .build())
    .compaction(CompactionConfig.builder()
        .triggerMessages(30)
        .keepMessages(10)
        .flushBeforeCompact(true)
        .offloadBeforeCompact(true)
        .model(compactionModel)        // ← 新增：包装后的 model
        .build())
    // ...
```

> **span 命名说明**：flush 与 consolidation 共用 `memoryConfig.model()`，两者无法在装饰器层面区分（同一实例），span 名称统一为 `memory`。区分方式：compaction 仅在压缩触发时出现；flush/consolidation 从 span 时序与消息内容（consolidation 消息量远大于 flush）判断。后续如需精确区分，需向 SDK 提交 MemoryConfig 拆分 model 的 issue。
>
> **行为等价性**：`MemoryConfig.model()`/`CompactionConfig.model()` 未设置时 harness 回退主 model（`memoryConfig.model() != null ? ... : model`，源码确认），因此显式设置包装器不改变任何生成行为，仅增加 span。

### 4.11 可选：Metrics（后续阶段，P2）

当前设计仅覆盖 Traces。指标层规划（不在本期实施）：

| 指标 | 类型 | 来源 |
|------|------|------|
| agent.call.duration | Histogram | AgentRuntimeService.invoke/invokeStream |
| llm.tokens.total | Counter | LlmLoggingMiddleware（已有数据） |
| llm.call.duration | Histogram | LlmLoggingMiddleware（已有数据） |
| sandbox.create.duration | Histogram | TracingSandboxClient |
| agent.error.rate | Counter | AgentRuntimeService 异常路径 |

实现路径：`micrometer-registry-prometheus` + 已有 `spring-boot-starter-actuator`，暴露 `/actuator/prometheus` 供外部抓取，零侵入。或者 `micrometer-registry-otlp` 推送至 OTel Collector（需 Collector 具备 Prometheus receiver）。

---

## 五、环境变量配置

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `OTEL_TRACES_EXPORTER` | 不设置 | **Agent 方案下保持不设置（unset）**：Spring 侧 `application.yml` 默认 `none` → 自研 `OtelConfig`/`HttpTracingFilter` 不装配；Agent 侧默认 `otlp` → trace 正常导出。**切勿显式设 `none`**（会连 Agent 的 otlp exporter 一起禁掉，trace 完全不导出，步骤 14 实测）。仅在无 Agent 的自研 fallback 路径设为 `otlp` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | OTLP **HTTP** 端点（非 gRPC） |
| `OTEL_EXPORTER_OTLP_HEADERS` | 空 | 自定义 HTTP 头，格式 `key1=value1,key2=value2`（Langfuse 认证用） |
| `OTEL_TRACES_SAMPLER` | `always_on` | 仅自研路径生效；Agent 方案用标准 `OTEL_TRACES_SAMPLER`/`OTEL_TRACES_SAMPLER_ARG` 环境变量（如 `OTEL_TRACES_SAMPLER=traceidratio OTEL_TRACES_SAMPLER_ARG=0.1`） |
| `OTEL_SERVICE_NAME` | `agent-framework` | Jaeger UI 中显示的服务名 |
| `OTEL_METRICS_EXPORTER` | 见 Agent | Agent 方案建议设 `none`（实测 Jaeger 不收 metrics 会持续 404 刷日志；保留 `prometheus` 则另需 exporter） |

**使用示例（Agent 方案，生产/本地推荐）：**

```bash
# 启用追踪，发送到本地 Jaeger（需先下载 javaagent 到 /opt/otel/）
java -javaagent:/opt/otel/opentelemetry-javaagent.jar \
  -Dotel.exporter.otlp.endpoint=http://localhost:4318 \
  -Dotel.service.name=agent-framework \
  -Dotel.metrics.exporter=none \
  -jar target/agent-framework-*.jar

# 启用追踪，发送到 Langfuse
java -javaagent:/opt/otel/opentelemetry-javaagent.jar \
  -Dotel.exporter.otlp.endpoint=https://cloud.langfuse.com/api/public/otel \
  -Dotel.exporter.otlp.headers="Authorization=Basic xxx,x-langfuse-ingestion-version=4" \
  -Dotel.metrics.exporter=none \
  -jar target/agent-framework-*.jar

# 禁用追踪（默认，Agent 不启动即零开销；加 -javaagent 但 OTEL_TRACES_EXPORTER=none 则仅采样器开销）
java -jar target/agent-framework-*.jar
```

---

## 六、OTEL_TRACES_EXPORTER=none 时的行为

> **2026-08-13 定案后更新**：该状态即 **Agent 方案的正常运行形态**（Agent 独立注册 SDK，与 `OTEL_TRACES_EXPORTER` 无关）。下表为"无 Agent + exporter=none"的自研 fallback 行为：

| 组件 | 行为 |
|------|------|
| `OtelConfig` | `@ConditionalOnProperty` 不满足，**不激活**，不创建 SdkTracerProvider |
| `OtelTracingMiddleware` | **仍注册**到 middleware 链，但 `GlobalOpenTelemetry.getTracer()` 返回 no-op tracer，`spanBuilder()` 返回 no-op span，所有操作近零开销。**Agent 方案下**：返回 Agent 注册的 SDK tracer，正常产 span |
| `FrameworkTracingMiddleware` | **仍注册**，`Span.current()` 返回 invalid span，`setAttribute()` 为空操作 |
| `ReasoningTracingMiddleware` | **仍注册**，no-op span，零开销 |
| `HttpTracingFilter` | **不注册**（`@ConditionalOnProperty`），HTTP 层零开销；**Agent 方案下** HTTP span 由 Agent 的 servlet instrumentation 生成（`POST /` 命名，含 http.* 属性） |
| `TracingSandboxClient` | **仍包装**，`GlobalOpenTelemetry.getTracer()` 返回 no-op tracer，`spanBuilder()` 返回 no-op span，零开销 |
| `TraceIdLogEnricher` | 正常工作，但日志 trace_id 恒为 `-`（无有效 span），属预期；**Agent 方案下**读取 Agent 注册 SDK 的 `Span.current()`，实测日志出现 trace_id |
| `LlmLoggingMiddleware` | 正常工作，不受影响 |

**结论**：无需在 `AgentScopeConfig` 中做条件判断，中间件始终注册即可；HttpTracingFilter 由条件注解自动控制。**Agent 方案（生产推荐）**：**不设置** `OTEL_TRACES_EXPORTER` 环境变量（Spring 侧 `application.yml` 默认 `none` 自动生效），通过 `-javaagent` + OTel property 启用，所有组件自动复用 Agent SDK（4.7 已验证）。

---

## 七、验证环境

### Jaeger（已部署）

| 服务 | 地址 | 用途 |
|------|------|------|
| Jaeger UI | `http://localhost:16686` | 查看 trace 瀑布图 |
| OTLP HTTP | `http://localhost:4318` | agent-framework 发送目标 |

启动 Jaeger 命令：

```bash
docker run -d --name jaeger \
  -e COLLECTOR_OTLP_ENABLED=true \
  -p 4318:4318 \
  -p 16686:16686 \
  jaegertracing/all-in-one:latest
```

### 验证步骤

1. 启动 agent-framework 并发送一条消息：
   ```bash
   OTEL_TRACES_EXPORTER=otlp \
   OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 \
   java -jar target/agent-framework-*.jar
   ```
2. 打开 `http://localhost:16686` → Service 选 `agent-framework` → Find Traces
3. 验证 trace 结构（非沙箱模式，期望 **单一 trace** 包含以下层级）：
   - 根 span: `POST /` 或 `GET /chat/stream`（HTTP Filter）
   - 子 span: `invoke_agent <name>`，含 `agentscope.user.id`、`agentscope.session.id`、`agentscope.tenant.prefix`
   - 孙 span: `reasoning <name>`，含 `gen_ai.operation.name=reasoning`
   - 曾孙 span: `chat <model>`，含 `gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens`，且**同样含** userId/sessionId 属性
   - 孙 span: `execute_tool <name>`，含 `gen_ai.tool.name`、`gen_ai.tool.call.id`
4. 验证 trace 结构（沙箱模式，需 SANDBOX_ENABLED=true）：
   - 同一 trace 下：`sandbox.create`（含 `sandbox.image`、`sandbox.id`）→ `invoke_agent` 子树 → `sandbox.delete`（显式销毁时）
   - 三者平级，均为 HTTP span 的子级
5. 验证 trace 唯一性：**一次请求必须只有一个 trace_id**。若出现多个不关联 trace，说明 HTTP span 未生效（MVC async 传播问题，见 4.7）
6. 验证多轮推理：一次需要多轮工具调用的请求，trace 中应出现多个 `reasoning` span，且 `chat` span 嵌套在各自 reasoning 下
7. 验证子 Agent：发送触发 `agent_spawn` 的请求，检查子 Agent 的 `invoke_agent` span 是否与父 trace 关联（**需实测**，异步任务可能断链，见风险 #10）
8. 验证日志关联：debug 页面日志（/debug/logs）与 LLM Calls 记录应显示 trace_id，且与 Jaeger 中 trace_id 一致
9. 验证 memory/compaction span：等待记忆 flush 触发（默认节流 10 分钟）或构造超过 30 条消息触发 compaction，trace 中应出现 `memory` / `compaction` span，含 `gen_ai.usage.*` 与 userId/sessionId（从父 span 复制），且是 invoke_agent/reasoning 的子 span（若为根 span 说明父级丢失，见风险 #11）
10. 验证属性完整性：每个 span 应有 `service.name=agent-framework`

### 验证结果（2026-08-13，方案 A：OTel Java Agent v2.12.0）

> 采用 Agent 时**不设置** `OTEL_TRACES_EXPORTER`（Spring 默认 `none` 自动生效），自研 `OtelConfig`/`HttpTracingFilter` 不装配，HTTP span 与 MVC 异步传播由 Agent 完成。启动：`java -javaagent:/opt/otel/opentelemetry-javaagent.jar -Dotel.exporter.otlp.endpoint=http://localhost:4318 -Dotel.service.name=agent-framework -Dotel.metrics.exporter=none -jar ...`。

| # | 验证项 | 结果 | 实测数据 |
|---|--------|------|---------|
| 1 | 服务启动 | ✅ | 8110 端口，Startup 4.8s（agent 附加开销可忽略） |
| 2 | Jaeger 可见 | ✅ | service=`agent-framework` |
| 3 | 非沙箱 trace 层级 | ✅ | `POST /` → `invoke_agent Minimal Agent` → `reasoning Minimal Agent`(×2) → `chat sensenova-6.7-flash-lite` / `execute_tool echo` / `memory` |
| 4 | 沙箱模式 trace | ✅ | `POST /` 下 `sandbox.create`（`sandbox.id`=50601220-859d-4be9-8e87-8ef9315b0a81，`sandbox.image`=opensandbox/code-interpreter:v1.1.0）与 `invoke_agent` 平级子 span；`sandbox.delete` 未见（SSE 会话期沙箱存活，预期内，见风险 #9） |
| 5 | 单一 trace_id | ✅ | 非沙箱 53 spans、沙箱 67 spans、子 Agent 97 spans 均单一 trace_id（**自研 Filter 方案实测失败**：HTTP span 52ms 与 agent 链 68s 拆成两个独立 trace） |
| 6 | 多轮推理 | ✅ | 同一 trace 内 2 个 `reasoning` span，各自嵌套 `chat` |
| 7 | 子 Agent | ✅ | `invoke_agent reporter` 正确挂接在 `execute_tool agent_spawn` 下，**同一 trace_id**（风险 #10 预期断链，实测未断——Agent 方案异步上下文传播正常） |
| 8 | 日志 trace_id | ✅ | 日志 `[31e342044cf2500a9465a7a5c2cbdba0]` 与 Jaeger trace `31e342044cf2500a…` 前 16 位一致 |
| 9 | memory span | ✅ | `memory` 为 `invoke_agent` 子级，含 `gen_ai.usage.input_tokens=555`、`gen_ai.operation.name=memory` |
| 10 | service.name | ✅ | 位于 process resource `{"p1": {"serviceName": "agent-framework", ...}}`（Jaeger 按 resource 归类，span tags 层无） |

**实测补充发现**（Agent 方案附带收益）：JDBC 查询自动插桩（`SELECT/INSERT agent_fs`、`SELECT agent_state` 等 DB span 挂在调用方 span 下）；LLM 调用产生 `POST`（HTTP client）子 span。属性完整性：`chat` span 含 `agentscope.user.id/session.id/tenant.prefix` + `gen_ai.request.messages.count` + `gen_ai.usage.input_tokens`；`invoke_agent reporter` 含 `agentscope.agent.reply_id` + `gen_ai.*` 全套。

---

## 八、Span 属性清单

| 属性 | 来源 | 说明 |
|------|------|------|
| `service.name` | OtelConfig (Resource) | 服务名，Jaeger UI 中标识 |
| `gen_ai.operation.name` | OtelTracingMiddleware | invoke_agent / chat / execute_tool |
| `gen_ai.agent.name` | OtelTracingMiddleware | Agent 名称 |
| `gen_ai.agent.id` | OtelTracingMiddleware | Agent ID |
| `gen_ai.request.model` | OtelTracingMiddleware | 模型名 |
| `gen_ai.request.messages.count` | OtelTracingMiddleware | 消息数 |
| `gen_ai.request.tools.count` | OtelTracingMiddleware | 工具数 |
| `gen_ai.usage.input_tokens` | OtelTracingMiddleware | 输入 token |
| `gen_ai.usage.output_tokens` | OtelTracingMiddleware | 输出 token |
| `gen_ai.tool.name` | OtelTracingMiddleware | 工具名 |
| `gen_ai.tool.call.count` | OtelTracingMiddleware | 工具调用数 |
| `gen_ai.tool.call.id` | OtelTracingMiddleware | 工具调用 ID |
| `agentscope.user.id` | FrameworkTracingMiddleware | 用户 ID |
| `agentscope.session.id` | FrameworkTracingMiddleware | 会话 ID |
| `agentscope.tenant.prefix` | FrameworkTracingMiddleware | 租户前缀 |
| `sandbox.id` | TracingSandboxClient | 沙箱实例 ID |
| `sandbox.image` | TracingSandboxClient | 沙箱镜像名 |
| `http.request.method` | HttpTracingFilter | HTTP 方法 |
| `url.path` | HttpTracingFilter | 请求路径 |
| `http.response.status_code` | HttpTracingFilter | 响应状态码 |
| `gen_ai.operation.name=reasoning` | ReasoningTracingMiddleware | 推理轮次 span 标识 |
| `gen_ai.operation.name=memory` | TracingModelWrapper | 记忆 flush/consolidation 的 LLM 调用 |
| `gen_ai.operation.name=compaction` | TracingModelWrapper | 上下文压缩的 LLM 调用 |

> 注：`FrameworkTracingMiddleware` 覆盖 onAgent/onModelCall/onActing 三个钩子，`agentscope.user.id`/`session.id`/`tenant.prefix` 出现在**所有层级 span**（invoke_agent/chat/execute_tool/reasoning），而非仅根 span。
>
> 注：`trace_id`/`span_id` 由 `TraceIdLogEnricher` 注入日志（debug 页面 + 控制台），不属 span 属性。

---

## 九、与现有 LlmLoggingMiddleware 的关系

| 维度 | LlmLoggingMiddleware | OtelTracingMiddleware | FrameworkTracingMiddleware |
|------|---------------------|----------------------|---------------------------|
| 拦截点 | `onModelCall` | onAgent / onModelCall / onActing | onAgent |
| order | 1 (默认) | 1 (默认) | 0 |
| 数据去向 | 内存 (LLMLogger) | OTel Collector | OTel Collector |
| 消费者 | Debug 页面 | Jaeger/Langfuse | Jaeger/Langfuse |
| 消息内容 | 完整 messages | 仅 count | 仅 count |
| Token 用量 | 有 | 有 | — |
| 用户/会话 | 无 | 无 | 有 |

**三者互不冲突**，洋葱模型中各自独立执行。

---

## 十、实施步骤

| 步骤 | 内容 | 改动文件 | 状态 |
|------|------|---------|------|
| 1 | pom.xml 添加 OTel SDK + Exporter + sdk-testing(test) 依赖 | `pom.xml` | ✅ |
| 2 | 新建 `OtelConfig.java`（SDK 初始化，含比例采样，parseSampler 静态化） | `config/OtelConfig.java` | ✅ |
| 3 | 新建 `FrameworkTracingMiddleware.java`（onAgent/onModelCall/onActing 属性补充） | `service/FrameworkTracingMiddleware.java` | ✅ |
| 4 | 新建 `ReasoningTracingMiddleware.java`（ReAct 轮次 span） | `service/ReasoningTracingMiddleware.java` | ✅ |
| 5 | 新建 `HttpTracingFilter.java`（必需，共同父 span） | `service/HttpTracingFilter.java` | ✅ |
| 6 | 新建 `TracingSandboxClient.java` | `sandbox/opensandbox/TracingSandboxClient.java` | ✅ |
| 7 | 新建 `TracingModelWrapper.java`（memory/compaction LLM 调用追踪） | `service/TracingModelWrapper.java` | ✅ |
| 8 | `AgentScopeConfig` 注册 3 个 middleware + 沙箱 tracing 注入 + memory/compaction model 包装 | `config/AgentScopeConfig.java` | ✅ |
| 9 | `application.yml` 添加 OTel 配置段 | `resources/application.yml` | ✅ |
| 10 | 新建 `TraceIdLogEnricher`（InMemoryLogAppender 增强 + logback converter） | `service/InMemoryLogAppender.java`、`service/TraceIdConverter.java`、`resources/logback-spring.xml` | ✅ |
| 11 | 编写单元测试：`TracingTestBase` + 7 个测试类（第十三节清单） | `src/test/java/io/agentmanager/framework/` | ✅ |
| 12 | `mvn test` 全量回归（现有基线用例 + 新增 tracing 用例） | — | ✅ 300 tests, 0 failures |
| 13 | 本地启动 + Jaeger 验证 trace 产出（含沙箱模式、多轮推理、子 Agent、memory/compaction，第七节） | — | ✅ 全部 10 项验证通过（2026-08-13，结果见 4.7 结论与第七节） |
| 14 | 评估 OTel Java Agent 方案（若 HTTP span 异步传播自研失败，切换 Agent） | `Dockerfile`、`Makefile` | ✅ 已定案采用 Agent（v2.12.0）；`Dockerfile` 内置 agent jar（`make otel-agent` 预下载到 `docker/otel/`）+ ENTRYPOINT 自动注入 + `Makefile` 加 `otel-agent` 目标（2026-08-13 完成，验证见 4.7） |
| 15 | 离线镜像依赖缓存更新（opentelemetry-sdk / sdk-testing） | `Dockerfile.dev` | ✅ 2026-08-13：Dockerfile.dev 预置 javaagent jar 到 `/opt/otel/`（COPY docker/otel/）+ OTel 13 组件 1.61.0 缓存（sdk/exporter-otlp/sdk-testing 等全部就位），已重建并导出 `agent-framework-java-dev.tar.gz`（441MB） |

---

## 十一、风险与注意事项

1. **零开销保证**：`OTEL_TRACES_EXPORTER=none`（默认）时，`OtelConfig` 不激活，`GlobalOpenTelemetry` 返回 no-op tracer，span 创建近零开销。所有 tracing 组件（中间件/Filter/包装器）始终注册但无实际开销。
2. **Reactor 上下文传播**：`OtelTracingMiddleware` 构造时注册 `ContextPropagationOperator`，确保 `publishOn`/`subscribeOn` 跨线程时 span 上下文不丢失。
3. **版本对齐**：OTel SDK 版本必须与 `agentscope-core` 传递的 `opentelemetry-api` 版本保持一致。**已修正（2026-08-13 实施时发现）**：`agentscope-core` 传递的是 **1.37.0** 而非 1.61.0，已通过 `dependencyManagement` 统一提升到 1.61.0（4.1），否则启动抛 `NoClassDefFoundError: StandardComponentId$ExporterType`。
4. **Docker 构建**：新增依赖会增加约 15MB 镜像体积，对当前 ~200MB 镜像影响可控。
5. **中间件顺序**：执行顺序 = 注册顺序（已核实 SDK 2.0.0 无 order() 机制，`MiddlewareChain` 按注册序构建洋葱链，先注册者更外层）。`OtelTracingMiddleware` 必须在 `FrameworkTracingMiddleware`/`ReasoningTracingMiddleware` 之前注册（AgentScopeConfig 已保证），后续改动须保持此注册顺序。
6. **Span.current() 时序**：`FrameworkTracingMiddleware` 的属性写入在 `doOnNext` 中执行（而非 `next.apply()` 之前），确保此时 Otel 的 `ContextPropagationOperator.runWithContext()` 已生效。
7. **OTLP 协议**：使用 `OtlpHttpSpanExporter`（HTTP 协议），Jaeger 4318 端口支持 HTTP 和 gRPC，兼容无问题。Langfuse 仅支持 HTTP。
8. **trace 断链风险（核心）**：HTTP span 是沙箱 span 与 agent span 的共同父级，**不可省略**。没有它，`sandbox.create`/`invoke_agent`/`sandbox.delete` 各自成为独立根 span（3 个互不关联的 trace_id）。且 MVC 异步线程下 Filter 的 `makeCurrent()` 不自动传播，必须用 Java Agent 或 `contextWrite` 方案解决（见 4.7）。验证步骤 #5 是硬性验收条件。**已解决（2026-08-13）**：自研 Filter 方案实测断链（HTTP span 52ms 与 agent 链 68s 分属两个 trace），切换 **OTel Java Agent 方案后单一 trace_id 达成**（第 5/7 节验证结果）。
9. **沙箱 stop span 缺失**：`SandboxManager.release()` 直接调用 `sandbox.stop()`（绕过 `SandboxClient`），`TracingSandboxClient` 仅覆盖 create/resume/delete。`stop` 的 trace 后续在 `WorkspaceSyncService.syncBack()` 处埋点补充（P3）。
10. **子 Agent span 归属**：子 Agent 继承父 Agent 的 middleware 列表（`HarnessAgentBuilderSupport` 源码确认），会产生自己的 `invoke_agent` span。**已实测验证（2026-08-13，Agent 方案）**：`invoke_agent reporter` 正确挂接在 `execute_tool agent_spawn` 下且同一 trace_id — **未断链**（Agent 自动完成异步上下文传播）。仅自研方案（方案 B）存在断链风险；生产走 Agent 时此风险关闭。
11. **memory/compaction LLM 调用的 span 父级归属**：已通过 `TracingModelWrapper` 解决（4.10）。其父级依赖调用点处于 middleware 链的 `runWithContext` 作用域内（flush 在 onAgent 链、compaction 在 onReasoning 链），正常情况下为 invoke_agent/reasoning 的子 span。**已实测验证**：`memory` span 为 `invoke_agent` 子级，含 usage 属性（第七节 #9）。
12. **日志关联不依赖 MDC**：`TraceIdLogEnricher` 直接读取 `Span.current()`（converter 每次日志事件实时读取），规避 reactive 跨线程 MDC 丢失问题。副作用：非请求线程（如 SessionCleanupService 定时任务）日志 trace_id 显示 `-`，属预期行为。
13. **采样策略**：默认 `always_on`；生产高流量建议 `ratio:0.1` 起步，避免 Jaeger 存储膨胀。`ratio:` 语法由本设计自定义（自研路径用）；Agent 方案用标准环境变量：`OTEL_TRACES_SAMPLER=traceidratio` + `OTEL_TRACES_SAMPLER_ARG=0.1`。
14. **Agent 方案附加开销/噪音**（2026-08-13 实测）：① JDBC 自动插桩产生大量 `SELECT agent_fs` 等 DB span（每次工作区读写都计），大请求下 span 数可观（一次对话 53–97 spans）；如需精简可 `-Dotel.instrumentation.jdbc.enabled=false` 或 `jdbc-datasource` 只开关键库。② `OTEL_METRICS_EXPORTER` 默认 exporter 会向 Jaeger 4318 投送 metrics 导致持续 404 刷日志，必须设 `none`（或按需配置 Prometheus）。③ Agent 附带 `otel.javaagent` 前缀日志，检索时注意过滤。

---

## 十二、已知缺口与后续规划

本期设计已解决的缺口：trace 断链（HTTP span 必需化）、child span 租户属性、推理轮次可见、日志 trace_id 关联、比例采样、memory flush/consolidation 与 compaction 的 LLM 调用追踪（`TracingModelWrapper`）。

以下缺口**不在本期范围内**，按优先级列入后续规划：

| 优先级 | 缺口 | 现状 | 后续方向 |
|--------|------|------|---------|
| P2 | Metrics 指标 | 无 Prometheus 指标 | 见 4.11，`micrometer-registry-prometheus` + actuator |
| P2 | 子 Agent span 归属 | 已实测（Agent 方案未断链，风险 #10） | 风险已关闭；如后续切换自研方案需复测 |
| P2 | 跨服务 trace 断链（Go 后端 → agent-framework） | 上游 Go 后端无 traceparent 注入 | **本期明确不做**；Agent 方案下 HTTP 层已按 W3C traceparent 自动提取（Agent 内置），Go 后端接入 OTel 后即可跨服务关联，无需代码改动 |
| P3 | MCP 外部调用延迟细分 | MCP 工具网络往返在 `execute_tool` span 内无子 span | 包装 `McpClientBuilder` 创建连接时注入 tracing 装饰器 |
| P3 | A2A 层操作 span | `tasks/get`、`tasks/cancel` 无独立 span | HTTP span 已覆盖请求入口；任务级生命周期 span 按需补充 |
| P3 | 错误分类属性 | span 仅 OK/ERROR 状态 | 在 `doOnError` 中按异常类型写入 `error.type` 属性（LLM_TIMEOUT / TOOL_ERROR / DB_ERROR / SANDBOX_ERROR） |
| P3 | 沙箱 stop span | 风险 #9 | 在 `WorkspaceSyncService.syncBack()` 埋点 |
| P3 | 沙箱 exec 内部延迟细分 | 沙箱内代码执行在 `execute_tool` 内无细分 | 在 OpenSandbox exec 调用处埋点（与 SDK 行为对齐后再评估） |
| P3 | 推理轮次编号 | reasoning span 无轮次序号 | Reactor Context 传计数器（见 4.8 注释） |
| P3 | flush 与 consolidation 的 span 区分 | 共用同一 model 实例，span 名称统一为 `memory` | 向 SDK 提交 MemoryConfig 拆分 model 的 issue（见 4.10 注释） |

---

## 十三、测试验证方案

### 13.1 测试分层

| 层级 | 方式 | 依赖 | 覆盖范围 |
|------|------|------|---------|
| 单元测试 | JUnit 5 + Mockito + InMemorySpanExporter | 无外部服务 | 各 tracing 组件的 span 创建/属性/状态/委托 |
| 集成验证 | 手动 E2E（第七节）+ Jaeger | 本地 Jaeger + MySQL + LLM key | 全链路 span 层级、trace 唯一性、沙箱模式 |
| 回归保障 | 全量 `mvn test`（300 用例） | 无 | 确保 tracing 接入不破坏既有功能 |

### 13.2 测试基础设施 `TracingTestBase`

核心思路：用 `InMemorySpanExporter`（`opentelemetry-sdk-testing`，已加 test 依赖）+ `SimpleSpanProcessor` 捕获 span，断言零依赖真实 Jaeger。已用 javap 验证 `GlobalOpenTelemetry.resetForTest()` 在 1.61.0 存在。

```java
package io.agentmanager.framework;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;

/**
 * tracing 组件测试基类：注册带 InMemorySpanExporter 的 OTel SDK，
 * 供 GlobalOpenTelemetry 全局单例读取，捕获并断言 span。
 */
public abstract class TracingTestBase {

    protected static InMemorySpanExporter spanExporter;

    @BeforeAll
    static void registerTestOpenTelemetry() {
        spanExporter = InMemorySpanExporter.create();
        var provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        // GlobalOpenTelemetry 单 JVM 仅可注册一次；resetForTest 保证测试类间隔离
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build());
        // 必须注册：ContextPropagationOperator 存在全局 enabled 开关（默认 false），
        // 未 registerOnEachOperator() 时 runWithContext() 直接原样返回（上下文不传播）。
        // 等价 OtelTracingMiddleware 构造时的行为；hook 幂等，测试类间无冲突。
        ContextPropagationOperator.builder().build().registerOnEachOperator();
    }

    @BeforeEach
    void clearExporter() {
        spanExporter.reset();
    }

    /** 按名称过滤已完成 span */
    protected List<SpanData> findSpans(String name) {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(name))
                .toList();
    }
}
```

> **注意**：`GlobalOpenTelemetry.set()` 单 JVM 仅能成功一次；多个测试类共享同一 SDK 实例，`@BeforeEach` 清空 exporter 保证用例隔离。`OtelTracingMiddleware` 构造时注册的 `ContextPropagationOperator` 全局钩子为静态单次，测试类间无冲突。**已修正**（2026-08-13 实施时发现）：`runWithContext()` 受全局 `enabled` 开关控制（javap 核实 `ContextPropagationOperator.runWithContext` 起始即 `ifeq` 检查 `enabled`），必须调用 `registerOnEachOperator()` 才生效——测试基类已补注册；真实运行时由 `OtelTracingMiddleware` 构造时注册。

### 13.3 单元测试清单

新增/扩展 7 个测试类（沿用现有纯 JUnit 5 + Mockito 风格，参考 `LlmLoggingMiddlewareTest`）：

| 测试类 | 覆盖组件 | 关键用例 | 验证点 |
|--------|---------|---------|--------|
| `FrameworkTracingMiddlewareTest` | 属性补充 | ①活跃 span 下 onAgent/onModelCall/onActing 写入属性；②userId/sessionId 为空跳过；③事件透传不改变流 | span attributes（user.id/session.id/tenant.prefix）、事件顺序 |
| `ReasoningTracingMiddlewareTest` | 轮次 span | ①正常完成 → OK 状态；②error 流 → ERROR + recordException；③cancel → span 结束；④next 链内部 `Span.current()` 为 reasoning span（父子前提） | span 名称 `reasoning <name>`、状态、异常事件、active span 一致性 |
| `TracingSandboxClientTest` | 沙箱 span | ①create 成功 → OK + sandbox.id/image；②create 抛异常 → ERROR + 异常事件；③resume/delete 成功；④serialize/deserialize 委托 | span 状态/属性、异常传播 |
| `TracingModelWrapperTest` | model 装饰 | ①stream 创建 `memory`/`compaction` span + usage 属性（构造含 `ChatUsage` 的 `ChatResponse`）；②父 span 活跃时租户属性复制；③委托方法（getModelName 等）；④error 路径 | span 名称/usage/属性复制、委托等价性 |
| `HttpTracingFilterTest` | HTTP span | ①MockHttpServletRequest + MockFilterChain 成功 → OK + http.* 属性；②下游抛异常 → ERROR；③`otel.traces.exporter != otlp` 时 Bean 不装配 | span 名称 method+path、http 属性、条件装配 |
| `OtelConfigTest` | SDK 初始化 | `parseSampler`：always_on/always_off/ratio:0.5/ratio:2.0 截断/非法输入回退 always_on | Sampler 类型（RatioBasedSampler 比例值） |
| `TraceIdConverterTest` | 日志关联 | ①活跃 span 下返回 32 位 trace_id；②无活跃 span 返回 `-` | 字符串格式 |
| `InMemoryLogAppenderTest`（扩展，继承 `TracingTestBase`） | 日志关联 | append 时活跃 span 下存储条目含 trace_id；无活跃 span 显示 `-` | 存储条目结构 |

### 13.4 关键测试示例

**示例 1：ReasoningTracingMiddleware span 创建 + 父子前提**

```java
class ReasoningTracingMiddlewareTest extends TracingTestBase {

    @Test
    void shouldCreateReasoningSpanAndActivateItDownstream() {
        var agent = mock(Agent.class);
        when(agent.getName()).thenReturn("assistant");
        var ctx = mock(RuntimeContext.class);
        var input = new ReasoningInput(List.of(), List.of(), null);

        var capturedSpanId = new AtomicReference<String>();
        // next 链内部捕获活跃 span：若推理 span 未激活，此处为 invalid span。
        // 注意：必须在信号回调（doOnNext 等）中捕获——runWithContext 的 makeCurrent
        // 在信号投递时生效，Flux.defer 的 supplier 在订阅期执行（早于上下文激活）。
        Function<ReasoningInput, Flux<AgentEvent>> next = i -> Flux.just(someEvent())
                .doOnNext(e -> capturedSpanId.set(Span.current().getSpanContext().getSpanId()));

        new ReasoningTracingMiddleware().onReasoning(agent, ctx, input, next).blockLast();

        var spans = findSpans("reasoning assistant");
        assertEquals(1, spans.size());
        assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());
        // 下游捕获的 spanId 与完成的 reasoning span 一致 → 父子链成立的前提
        assertEquals(spans.get(0).getSpanId(), capturedSpanId.get());
    }
}
```

**示例 2：TracingSandboxClient 异常路径**

```java
class TracingSandboxClientTest extends TracingTestBase {

    @Test
    void shouldRecordErrorWhenCreateFails() {
        var delegate = mock(OpenSandboxClient.class);
        when(delegate.create(any(), any(), any()))
                .thenThrow(new RuntimeException("remote sandbox unavailable"));
        var options = mock(OpenSandboxClientOptions.class);
        var client = new TracingSandboxClient(delegate);

        assertThrows(RuntimeException.class, () -> client.create(null, null, options));

        var spans = findSpans("sandbox.create");
        assertEquals(1, spans.size());
        assertEquals(StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
        assertEquals(1, spans.get(0).getEvents().size());  // recordException
    }
}
```

**示例 3：no-op 降级**（`OTEL_TRACES_EXPORTER=none` 场景等价性）

```java
@Test
void shouldPassThroughWhenNoSdkRegistered() {
    // 不注册 SDK（GlobalOpenTelemetry 为 no-op）：中间件必须透传且不抛异常
    GlobalOpenTelemetry.resetForTest();
    var middleware = new FrameworkTracingMiddleware("tenant-x");
    var flux = middleware.onAgent(mock(Agent.class), null,
            new AgentInput(List.of()), i -> Flux.just(/*event*/));
    // 断言流正常完成，事件原样透传
}
```

### 13.5 集成验证（手动 E2E）

单元测试无法覆盖的跨组件行为，依赖第七节手动验证清单，重点项：

| 验证项 | 方法 |
|--------|------|
| 单一 trace_id（HTTP span 共同父级生效） | 第七节 #5（**已完成 2026-08-13**：Agent 方案通过；自研 Filter 方案曾失败） |
| 沙箱模式全链路 | 第七节 #4（**已完成**：需 `SANDBOX_ENABLED=true` + OpenSandbox server） |
| memory/compaction span 与父级归属 | 第七节 #9（**已完成**：`memory` 为 `invoke_agent` 子级；flush 节流 10 分钟，验证时临时调小 `flushTrigger` 可加速） |
| 子 Agent span 归属 | 第七节 #7（**已完成**：Agent 方案下 `invoke_agent reporter` 挂接 `execute_tool agent_spawn`，同 trace） |
| MVC 异步线程传播 | **已定案**：Agent 方案（4.7 方案 A）自动处理，无需自研方案 |

**可选自动化集成测试**（不推荐本期做）：真实 `HarnessAgent` + 本地 GreatSQL + 真实 LLM key 的全链路 span 层级断言。成本：需要真实 LLM 调用（key 消耗、网络依赖、慢），与现有基线用例"纯 mock、秒级"风格不符。建议以手动 E2E 覆盖。

### 13.6 测试注意事项

1. **GlobalOpenTelemetry 单例约束**：`set()` 单 JVM 仅一次；`resetForTest()` 用于测试类间隔离；断言前先 `spanExporter.reset()`。
2. **no-op 场景必须测**：`OTEL_TRACES_EXPORTER=none`（默认）时所有组件应透传且零开销——示例 3 覆盖。
3. **Reactor 异步性**：span 结束发生在 `doOnComplete/doOnError`，断言前必须 `blockLast()` 或 `StepVerifier` 同步化；`Flux.defer` 保证惰性创建 span（订阅时才创建）。
4. **不 mock OTel 全局对象**：用 `InMemorySpanExporter` 走真实链路，避免 `mock(Span.class)` 掩盖上下文传播问题。
5. **全量回归**：`mvn test` 300 用例全量通过为验收底线；`LlmLoggingMiddlewareTest`、`InMemoryLogAppenderTest` 等既有测试不得因中间件链改动而失败。
6. **离线环境**：`opentelemetry-sdk-testing` 为新增 test 依赖，离线开发镜像（Dockerfile.dev）需同步缓存（见 4.1 注意）。
7. **随机 traceId 生成**（2026-08-13 实施时发现）：比例采样测试**不能用 UUID**——UUID v4 低位含变体位（`10`），导致 `longFromBase16String(traceId, 16)` 解析出的 64 位恒 ≥ 2^62，ratio:0.5 永远不可能采样（实测 1000/1000 不采样）。用 `String.format("%016x%016x", random.nextLong(), random.nextLong())` 生成均匀随机 traceId。
8. **`Span.current()` 捕获时机**（2026-08-13）：`runWithContext` 的 makeCurrent 在信号投递时生效（`ContextPropagationOperator` 的 hook 包装 subscriber），`Flux.defer` 的 supplier 在订阅期执行，此时上下文尚未激活。断言"下游 Span.current() == reasoning span"必须在 `doOnNext`/`doOnComplete` 类信号回调中捕获。
