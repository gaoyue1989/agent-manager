# 自定义工具插件化加载方案（Java SPI + plugins/ 目录）

> **状态: ✅ 已实施（2026-09-25，单测 + 部署冒烟 12/12 PASS）**
> 目标：让 agent-framework **无需重编译、无需重建镜像**即可加载自定义工具 —— 工具代码以独立 jar（插件）形式放入 `plugins/` 目录，
> 服务启动时自动扫描、类隔离加载、注册进现有 Toolkit，与 `@Tool` 硬编码工具（BusinessTools/FileTools）同权运行。
> **范围：仅 agent-framework 工程（Java 服务 + 测试）。Go 后端、React 前端、镜像构建流程本次不涉及（平台集成列为后续阶段）。**

---

>
> **现状核对（2026-09-25 复核修订 → 同日实施）**：**阶段一（框架核心）+ 阶段二（示例插件与验证）已实施**；
> 阶段三（平台集成）未开始。核心机制可行性复核结论与实施前提见下方"三处前提变化"（保留作设计依据）。
>
> **实施记录（2026-09-25）**：
> - **交付物**：`tool/ToolPlugin.java`（SPI 接口，extends CustomTool）+ `tool/ToolPluginBootstrapper.java`
>   （BFPP：目录解析 → URLClassLoader(parent-first) → ServiceLoader 逐提供者 fail-soft → config.yaml `${ENV_VAR}`
>   注入 `configure()` → `registerSingleton("toolPlugin:{id}:{序号}")` → `@PreDestroy` 统一 `close()`）；
>   现有类（AgentScopeConfig/HarnessAgentFactory/InternalToolRegistry/ToolController/AgentManagerProperties）**零改动**，与 §3.4 预期一致。
> - **单测**：`ToolPluginBootstrapperTest`（10 用例：目录容错/正常注册/多提供者多工具/坏 jar/坏提供者/
>   同名插件唯一单例名/config 替换/close 回归/目录回退）+ `ToolPluginAssemblyTest`（ApplicationContextRunner
>   验证 BFPP 手工单例被 `List<CustomTool>` 注入解析）。全量 `mvn test` 1004 用例通过。
> - **部署冒烟**：`e2e/plugin-echo/EchoToolPlugin.java`（示例插件源码，兼作开发模板）+
>   `e2e/scripts/plugin-smoke.sh`（现场编译打包插件 jar → 独立进程起服务 → **12 断言全 PASS**：
>   Bootstrapper/HarnessAgentFactory/SDK Toolkit 三层注册日志、/tools 透出、OAF reload 整包重建后
>   工厂与 Toolkit 重新注册且 /tools 仍在、deniedTools 类粒度剔除（日志+接口）、撤销恢复）。
> - **与方案的差异**：①示例插件落在 `e2e/plugin-echo/`（非 §5 的 `examples/plugins/`），兼作冒烟验证件，
>   开发模板即 §4 源码；②单测中"类隔离"断言改为 parent-first 复用父类 Class——测试类路径可见 fixture 类时
>   子加载器委托父加载器（生产环境插件类不在应用类路径，由子加载器自加载，冒烟已覆盖该场景）；
>   ③Bootstrapper 汇总日志按**工具对象数**计数（一个对象多个 @Tool 方法记 1）；
>   ④ServiceLoader 迭代逐提供者 fail-soft（迭代按 services 文件行有限推进，无死循环风险）。
> - **CR 修复（2026-09-25，独立审查 1×P1 + 7×P2 全部落地）**：⑤close() 生命周期改经 **DisposableBean**
>   而非 @PreDestroy——BFPP bean 提前实例化发生在 CommonAnnotationBeanPostProcessor 登记之前，
>   注解式销毁回调不会被登记（审查代理以探针测试实证），DisposableBean 的 instanceof 检查不依赖
>   后置处理器时机；装配测试补"上下文关闭后插件 close() 已回调"回归断言。⑥重名预检基线补既有硬编码
>   自定义工具名（`FRAMEWORK_CUSTOM_TOOL_NAMES`：get_current_time/echo/present_file/present_url）。
>   ⑦插件实例先入列再 configure——中途失败的插件也能在 destroy() 收到 close()（回收半初始化资源）。
>   ⑧插件目录路径值非法（InvalidPathException）告警回退默认目录，不阻断启动。⑨冒烟脚本修复
>   jar 缺失时友好报错被 errexit 吞掉、mock LLM 启动与 trap 注册之间的泄漏窗口两处缺陷。
> - 环境变量 `AGENT_PLUGINS_DIR` 已登记 [agent-framework-deploy.md](agent-framework-deploy.md) 环境变量表。
>
> **复核前提（2026-09-25，保留作设计依据）**：核心机制可行，初稿接入点被三处后续落地变更推翻——
> 1. **自定义工具收集口径变化**：`List<Object>` → `List<CustomTool>` 标记接口（`tool/CustomTool.java`，2026-09-25
>    随 OAF 动态 reload 落地，用于消除 Spring 循环依赖）。新增自定义工具的正路是"实现 CustomTool 即自动注册"。
> 2. **agent 构建已抽出并双路复用**：`service/HarnessAgentFactory` 同时服务启动装配（AgentScopeConfig 委托）与
>    **OafReloadService 整包重建**（OAF 包原位更新后重建 HarnessAgent，见 [oaf-dynamic-reload-plan.md](oaf-dynamic-reload-plan.md) M1/M2）。
>    初稿"在启动装配点一次性注册插件"的方案，**reload 重建后插件工具会全部丢失**。
> 3. **/tools 已有运行时注册集**：`InternalToolRegistry`（issue #28）作为 `/tools?includeInternal=true` 的唯一事实源，
>    与 HarnessAgentFactory 同吃一份 `List<CustomTool>` 注入。初稿 §3.6"扩展 ToolController"不再需要。
>
> **修订要点**：插件工具实例经 `BeanDefinitionRegistryPostProcessor` 注册为 Spring 单例，**并入 `List<CustomTool>` 注入源**——
> 启动装配、OAF reload 整包重建、/tools 注册集、HITL 权限白名单四处消费方**零改动**自动生效（见 §3.4）。
> 原 `OafPackageTools`（check_oaf_package / create_oaf_zip）已于 2026-09 迁出至平台 backend MCP
> （见 [../../docs/design/oaf-tools-extraction-design.md](../../docs/design/oaf-tools-extraction-design.md)）——
> 业务领域工具走 MCP、框架通用能力走 @Tool 的分层即是本文插件机制未来服务的边界。

## 一、背景与目标

### 1.1 需求

当前自定义工具只有硬编码一种形态：`tool/` 包的 `@Tool` 注解类（`BusinessTools` / `FileTools`——含 present_file 与 present_url），
实现 `CustomTool` 标记接口后经 Spring 注入 `List<CustomTool>` → `HarnessAgentFactory.build()` 注册进 Toolkit。
**新增/修改任何自定义工具都必须重编译 agent-framework 并重建镜像**，
与"挂载模式（mount）复用预构建镜像、配置即部署"的产品形态矛盾。

需要一个**插件化加载机制**：

1. 工具代码以**独立 jar** 交付，放入约定目录，服务启动时自动加载（零代码改动）。
2. 加载后与现有 `@Tool` 工具完全同权：deniedTools 过滤、HITL 权限、`/tools` API、SSE 事件、多租户上下文全部自动生效，
   且**OAF 包动态 reload 重建 agent 后依然在位**。
3. 插件可访问 `RuntimeContext` 等运行时上下文（`@Tool` 方法参数注入，进程内深度能力，区别于 MCP 进程外方案）。
4. 插件配置通过**环境变量 / config.yaml** 注入（支持 `${ENV_VAR}` 替换，对齐 McpToolRegistrar 惯例）。
5. 单个插件 jar 支持**多个工具类 / 多个工具**（SPI 多提供者 + 类内多 `@Tool` 方法）。

### 1.2 目标

- 阶段一（框架核心，~1 天）：`ToolPlugin` SPI 接口 + `ToolPluginBootstrapper`（BFPP 装配钩子 + 目录扫描 + URLClassLoader + ServiceLoader）+ 测试。
- 阶段二（插件工程与文档）：示例插件 jar + 插件开发模板说明（pom.xml / services 文件 / 打包约定）+ AGENTS.md 更新。
- 阶段三（平台集成，后续另立方案）：agent-manager Go 后端管理插件 jar 上传/分发，挂载到 Agent Pod 卷目录。
- **范围外**：插件热加载/热卸载（JVM 类卸载限制，插件更新需重启服务，见 §3.7）；非 Java 工具（走 MCP 方案）；
  插件注册 middleware/hook（本期仅工具，扩展点见 §8）。

---

## 二、现状分析

### 2.1 相关代码链路（2026-09-25 走读）

```
BusinessTools / FileTools (implements CustomTool, @Tool)
        │  Spring 注入 List<CustomTool>（标记接口收窄候选，防循环依赖）
        ├────────────────────────────▶ HarnessAgentFactory（构造器持有 customTools）
        │                                 │ build(oafConfig, ...)：启动装配与 OAF reload 整包重建共用
        │                                 │ 循环：toolToolNames → deniedTools 类粒度过滤
        │                                 │       → toolkit.registerTool → customToolNames
        │                                 │       → buildPermissionContext(…, customToolNames)  # HITL 白名单
        │                                 ▼
        │                           HarnessAgent.toolkit（每次 build 全新 Toolkit）
        │
        └────────────────────────────▶ InternalToolRegistry（@Bean，同样注入 List<CustomTool>）
                                          │ /tools?includeInternal=true 的唯一事实源（issue #28）
                                          │ deniedTools 每请求经 OafConfigHolder 重算，reload 即时反映
                                          ▼
                                    ToolController.listTools
```

| 现有能力 | 位置 | 与本方案的关系 |
|---------|------|---------------|
| `CustomTool` 标记接口 | `tool/CustomTool.java` | **本方案的接入点**：插件工具实现它即并入注入源（见 §3.2） |
| `@Tool` 反射注册 | `Toolkit.registerTool(Object)`（agentscope-core）；注册循环在 `HarnessAgentFactory.build()` | 插件工具复用（与 BusinessTools 完全同构） |
| deniedTools 类粒度过滤 | `HarnessAgentFactory.build()`（`toolToolNames` 循环） | 插件类走同一过滤，reload 时按新配置重算 |
| HITL 权限白名单 | `buildPermissionContext(oafConfig, permCfg, customToolNames)` | 插件工具名并入 `customToolNames` 后自动生效 |
| OAF 整包重建 | `OafReloadService` → `harnessAgentFactory.build(新配置…)` | **插件存活的关键前提**：重建复用构造器持有的同一批 `customTools` |
| /tools 运行时注册集 | `InternalToolRegistry`（#28） | 并入注入源后零改动透出（见 §3.6） |
| MCP `${ENV_VAR}` 替换 | `McpToolRegistrar`（`${VAR}` 语法解析，`/config/{server}/` 目录布局） | 插件 config.yaml 复用同一语法 |
| 内置工具名白名单 | `HarnessAgentFactory.BUILT_IN_TOOL_NAMES`（公开常量） | 加载期重名预检使用（见 §3.3 规则 6） |

### 2.2 SDK 能力确认（2.0.3 javap 复核）

- `Toolkit.registerTool(Object)` / `registerAgentTool(AgentTool)` / `registerToolGroup(ToolGroup)` / `removeTool(String)` 均公开可用（初稿结论在 2.0.3 复核仍成立）。
- `ToolMethodInvoker.convertParameters(Method, Map, Agent, RuntimeContext, ToolEmitter)` + `resolveContextParameter(Parameter, RuntimeContext)`——`@Tool` 方法参数注入 `RuntimeContext` 等运行时上下文的能力仍在。
- agentscope-harness / agentscope-core **无内置 SPI/插件加载**（jar 内无相关类），需框架自研——结论不变。

> 与初稿的差异：初稿允许插件 `@Tool` 类与 `AgentTool` 接口实现混用（loader 分别调 registerTool/registerAgentTool）。
> 修订后注册由 `HarnessAgentFactory` 共用循环统一走 `registerTool`，**本期插件工具收敛为 `@Tool` 注解单形态**；
> `AgentTool` 异步/流式形态列为可选扩展（需在工厂循环加 `instanceof AgentTool` 分支，届时评估）。

---

## 三、方案设计

### 3.1 总体架构

```
{AGENT_CONFIG_DIR}/plugins/
├── weather-tool.jar          # 插件 jar（含 META-INF/services/io.agentmanager.framework.tool.ToolPlugin）
├── weather-tool/config.yaml  # 可选：插件配置文件（${ENV_VAR} 替换后注入 configure()）
└── db-query.jar

Spring 上下文刷新（invokeBeanFactoryPostProcessors 阶段，早于一切普通 bean 实例化）
  └── ToolPluginBootstrapper.postProcessBeanDefinitionRegistry   # 新增
        │  扫描 plugins/*.jar
        │  URLClassLoader(parent = 应用类加载器)   # 共享 agentscope-core / agent-framework
        │  ServiceLoader.load(ToolPlugin.class, loader) 实例化 SPI 提供者
        │  config.yaml → ${ENV_VAR} 替换 → configure(Map)
        │  beanFactory.registerSingleton("toolPlugin:{id}:{序号}", tool 实例)
        ▼
  List<CustomTool> 注入源（BusinessTools/FileTools bean + 插件工具单例）
        ├─▶ HarnessAgentFactory（构造器注入）──▶ build()：启动装配 + OAF reload 整包重建
        │     toolkit.registerTool / deniedTools 过滤 / customToolNames → HITL 白名单
        ├─▶ InternalToolRegistry（@Bean 注入）──▶ /tools?includeInternal=true 自动透出
        └─▶ （未来任何新的 List<CustomTool> 消费方同样自动包含）
```

### 3.2 SPI 接口（新增 `tool/ToolPlugin.java`）

```java
package io.agentmanager.framework.tool;

/**
 * 自定义工具插件 SPI。
 * 插件 jar 内通过 META-INF/services/io.agentmanager.framework.tool.ToolPlugin
 * 注册实现类（每行一个全限定类名），由 ToolPluginBootstrapper 在 Spring 上下文
 * 刷新前加载，工具实例注册为单例并入 List<CustomTool> 注入源。
 *
 * 继承 CustomTool：tools() 返回的实例由此进入 HarnessAgentFactory（启动/reload
 * 双路构建）、InternalToolRegistry（/tools）、HITL 权限白名单三处消费方，零改动共享。
 */
public interface ToolPlugin extends CustomTool {

    /** 插件标识（默认取实现类简单名；日志与单例名使用） */
    default String id() { return getClass().getSimpleName(); }

    /** 工具实例集合：类内多 @Tool 方法自动全量收集；一个 jar 可返回多个工具类实例 */
    List<CustomTool> tools();

    /** 插件初始化（可选）：config.yaml 解析并替换 ${ENV_VAR} 后回调 */
    default void configure(Map<String, String> config) {}

    /** 生命周期回调（可选）：服务优雅关闭时由 Bootstrapper 统一调用 */
    default void close() {}
}
```

**设计要点**：
- `tools()` 返回 `List<CustomTool>`：插件自身的工具类实现 `ToolPlugin`（已继承 CustomTool）即可 `List.of(this)`；
  独立工具类则单独 `implements CustomTool`。类型即契约，注入侧无需 instanceof 判断。
- `configure(Map)` 为可选钩子：bootstrapper 从 `plugins/{id}/config.yaml` 读取（不存在则传空 Map）；
  插件亦可直接 `System.getenv()` 读取环境变量（同 JVM，天然支持）。
- 接口定义在 **agent-framework 自身**（非独立 api 模块，避免新增 Maven 模块）；插件 jar 以 `provided` 作用域依赖 agent-framework。

### 3.3 加载与装配（新增 `tool/ToolPluginBootstrapper.java`）

```java
@Component
public class ToolPluginBootstrapper implements BeanDefinitionRegistryPostProcessor, DisposableBean {

    private final List<ToolPlugin> plugins = new ArrayList<>();

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        // ① 解析插件目录（见下方"目录解析"，直读环境变量）
        // ② 扫描 *.jar → ServiceLoader 实例化 → config.yaml 注入 configure()
        // ③ 重名预检（BUILT_IN_TOOL_NAMES + 已收集插件工具名，命中仅告警）
        // ④ 逐工具实例 registerSingleton("toolPlugin:{id}:{序号}", tool)
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) { /* 无处理 */ }

    @Override  // DisposableBean：BFPP 提前实例化下 @PreDestroy 不生效（见加载规则 7）
    public void destroy() { plugins.forEach(p -> p.close()); }
}
```

**加载规则**：

1. **目录解析**：`AGENT_PLUGINS_DIR` 环境变量优先；未配置时回退 `{AGENT_CONFIG_DIR:-/config}/plugins`
   （与 `AgentManagerProperties.resolvedWorkspaceBaseDir` 的回退语义同构）。目录不存在或为空 → 直接返回（不报错，兼容无插件部署）。
   **不经 `AgentManagerProperties`**：BFPP bean 会被提前实例化，构造器/字段注入其他 bean 会强制过早初始化并触发告警，
   因此目录与配置一律直读环境变量和文件系统，本类不持有任何 Spring 依赖。
2. 每个 `*.jar` 用独立 `URLClassLoader`（parent = Bootstrapper 的类加载器，**parent-first 委托**）：
   - 共享 `agentscope-core` / `agentscope-harness` / `agent-framework`（含 `CustomTool`/`ToolPlugin`/`@Tool` 注解）等框架类；
   - 插件自己的第三方依赖（若有）需 **shade 进 jar**；与框架重名类以父加载器为准。
   - **类身份约束**：`CustomTool` 接口必须由父加载器解析（插件 provided 依赖、不打包框架类），
     否则 `instanceof CustomTool` 失效、`List<CustomTool>` 注入不匹配——这是硬性打包纪律（§3.5）。
3. `ServiceLoader.load(ToolPlugin.class, loader)` 实例化该 jar 内全部 SPI 提供者。
4. 每个提供者按 `{jar名去后缀}/config.yaml` 读取配置（不存在跳过），`${ENV_VAR}` 替换（复用 McpToolRegistrar 同款实现）后调 `configure(Map)`。
5. 单 jar / 单类加载异常 → `log.warn` 记录并跳过，**不影响其他插件与服务启动**（fail-soft，与 MCP 注册语义一致）。
6. **重名预检**：注册前将插件工具名与 `HarnessAgentFactory.BUILT_IN_TOOL_NAMES` 及已收集的其他插件工具名做差集，
   命中打 WARN（仍注册，由 SDK 覆盖语义决定最终行为）；插件工具名建议带插件语义前缀（如 `weather_query`）避开内置/MCP 名。
7. **close() 回调**：`registerSingleton` 注册的手工单例**不受 Spring 销毁回调管理**，
   由 Bootstrapper 自身实现 `DisposableBean.destroy()` 统一触发——**不能用 @PreDestroy**：
   BFPP bean 的提前实例化发生在 CommonAnnotationBeanPostProcessor 登记之前，注解式销毁回调
   不会被登记（CR 探针实证）；DisposableBean 的 instanceof 检查在 bean 创建时即命中，不依赖时机。

**装配时序依据**：BFPP 在 `invokeBeanFactoryPostProcessors` 阶段执行，早于普通 bean 实例化；
`DefaultListableBeanFactory.getBeanNamesForType` 的检索范围含手工注册单例（manualSingletonNames）。
因此 `HarnessAgentFactory` 构造器与 `internalToolRegistry` @Bean 解析 `List<CustomTool>` 时，
插件工具单例已就位，类型匹配经父加载器解析的同一 `CustomTool` Class 成立。

### 3.4 零侵入接入（不改任何现有类）

初稿在 `AgentScopeConfig.harnessAgent` 装配点循环注册插件工具——该点已随重构失效（构建逻辑移入 `HarnessAgentFactory`，
且 reload 重建不再经过 AgentScopeConfig）。修订后的接入**只有 BFPP 注册单例这一个动作**：

| 消费方 | 接入方式 | 改动 |
|--------|---------|------|
| `HarnessAgentFactory`（启动 + OAF reload 整包重建） | 构造器注入的 `List<CustomTool>` 自动含插件工具；每次 `build()` 循环重新注册进全新 Toolkit，deniedTools 按当次配置重算 | **零** |
| `InternalToolRegistry`（/tools，#28） | @Bean 注入的 `List<CustomTool>` 同源；`source=builtin`、`declared` 标注语义照常 | **零** |
| HITL 权限白名单 | `customToolNames` 在注册循环中收集，插件工具名自动进入 `buildPermissionContext` | **零** |
| `AgentScopeConfig` / `ToolController` / `AgentManagerProperties` / application.yml | 不改（插件目录直读环境变量，见 §3.3 规则 1） | **零** |

> 备选方案（不采用）：`HarnessAgentFactory` / `InternalToolRegistry` 构造器增加 `ToolPluginLoader` 参数。
> 显式直白，但两处注入点都要改，且未来新的 `List<CustomTool>` 消费方需记得手动并入；
> BFPP 方案把"插件工具就是又一批 CustomTool bean"这一语义贯彻到底，接入面收敛为一处。

### 3.5 插件 jar 打包约定

| 项 | 约定 |
|----|------|
| 编译依赖 | `agent-framework`（scope=provided，取 `ToolPlugin`/`CustomTool`）+ `agentscope-core`（provided，2.0.3，取 `@Tool`/`@ToolParam`） |
| 打包 | 普通 jar（**勿打 fat jar / 勿 shade agentscope-core / agent-framework**——框架类必须留给父加载器解析，见 §3.3 规则 2）；自带第三方依赖须 shade 进 jar 内 |
| SPI 注册 | `META-INF/services/io.agentmanager.framework.tool.ToolPlugin`（每行一个全限定类名） |
| 放置位置 | `{AGENT_PLUGINS_DIR}`（默认 `/config/plugins`，挂载模式由部署侧挂卷） |
| 插件配置 | `{AGENT_PLUGINS_DIR}/{jar名去后缀}/config.yaml`（可选，`${ENV_VAR}` 可替换） |
| 环境变量 | 插件运行在框架 JVM 内，可直接 `System.getenv()` 读取全部注入环境变量 |

### 3.6 /tools API：零改动自动透出

issue #28 后 `/tools?includeInternal=true` 以 `InternalToolRegistry` 运行时注册集为准，与注册链路同源；
插件工具并入 `List<CustomTool>` 后自动出现在列表中（`category=internal`、`source=builtin`、`declared` 按声明），
**无需任何代码改动**。

可选增强（本期不做）：`source` 细化为 `plugin` 并附 `pluginId`——需 Bootstrapper 把插件工具名集合写入共享 holder、
registry 输出时改写来源字段，收益仅是展示区分，实施时视需要补。

### 3.7 与 OAF 动态 reload 的关系（边界声明）

| 场景 | 行为 |
|------|------|
| OAF 包原位更新 → 整包重建（M1/M2 已实施） | `OafReloadService` → `HarnessAgentFactory.build()` → 新 Toolkit 重新注册 `List<CustomTool>`（**含插件工具，实例复用**）；deniedTools 按新配置重算，可能即时剔除插件工具类 |
| MCP server 声明增删改（单 server 重注册） | 与插件无关（McpToolRegistrar 独立路径） |
| 插件 jar 文件更新/删除 | **不触发任何重载**：JVM 类卸载限制，类实例无法热替换 → 需重启 Pod（与初稿"范围外"声明一致） |
| M4（SIGHUP / 定时扫描，未实施） | 即使实施，触发的也是 **OAF reload**（重建 agent、复用同一批插件实例），不是插件重载。两条边界：包配置热更新（做）vs 插件代码热更新（永不做） |

### 3.8 安全边界

- **插件即代码**：插件在框架 JVM 进程内以全权限运行（可读环境变量/密钥、访问数据库连接、执行任意代码）。
  **plugins/ 目录必须视为可信输入**，仅允许受信任方投放 jar；不做沙箱隔离（如需隔离请用 MCP 进程外方案）。
- 加载阶段异常已隔离（单 jar 失败不阻断启动），但**不做类加载器回收**（启动期一次性加载，无卸载需求）。
- 与平台部署形态的对照：PVC subPath 只读挂载下插件目录同样只读——插件更新走"改卷内容 + 重启"，不存在运行中写入。

---

## 四、插件开发指南（阶段二交付物）

### 4.1 工程骨架

```
weather-tool/
├── pom.xml
└── src/main/
    ├── java/com/acme/tools/WeatherTools.java
    └── resources/META-INF/services/io.agentmanager.framework.tool.ToolPlugin
```

```xml
<!-- pom.xml（核心部分） -->
<dependencies>
    <!-- 框架 SPI 接口 + agentscope-core（均 provided，不打进 jar） -->
    <dependency>
        <groupId>io.agentmanager</groupId>
        <artifactId>agent-framework</artifactId>
        <version>${project.version}</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-core</artifactId>
        <version>2.0.3</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

```java
// WeatherTools.java —— 开发者只写这一个类（ToolPlugin 已继承 CustomTool）
public class WeatherTools implements ToolPlugin {

    @Override
    public List<CustomTool> tools() {
        return List.of(this);          // 同类内多 @Tool 方法自动全量收集
    }

    @Tool(name = "weather_query", description = "查询指定城市天气", readOnly = true)
    public String query(@ToolParam(name = "city", description = "城市名") String city) {
        return weatherApi.query(city);
    }

    @Tool(name = "weather_forecast", description = "查询未来几天预报", readOnly = true)
    public String forecast(@ToolParam(name = "city", description = "城市名") String city) {
        return weatherApi.forecast(city);
    }
}
```

```properties
# META-INF/services/io.agentmanager.framework.tool.ToolPlugin
com.acme.tools.WeatherTools
```

### 4.2 配置读取（两种方式并存）

```yaml
# {AGENT_PLUGINS_DIR}/weather-tool/config.yaml —— bootstrapper 解析后注入 configure(Map)
apiBase: https://api.weather.example.com
apiKey: ${WEATHER_API_KEY}        # ${ENV_VAR} 自动替换为环境变量
timeout: 10
```

```java
@Override
public void configure(Map<String, String> config) {
    this.apiKey = config.getOrDefault("apiKey", System.getenv("WEATHER_API_KEY"));
}
```

### 4.3 开发/验证流程

```bash
mvn package                          # 产出 weather-tool.jar
cp target/weather-tool.jar /config/plugins/
# 重启 agent-framework → 启动日志出现 "Tool plugin [weather-tool] registered tools: [...]"
curl localhost:8100/tools?includeInternal=true   # 列表含插件工具
# OAF reload（整包重建）后再次调用 —— 插件工具仍在（§3.7 回归点）
```

> 上述流程已脚本化为 `e2e/scripts/plugin-smoke.sh`（示例插件 `e2e/plugin-echo/`，12 断言，
> 覆盖三层注册日志 / /tools / reload 存活 / deniedTools 剔除与恢复），2026-09-25 部署冒烟 12/12 PASS。

---

## 五、文件改动清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/main/java/io/agentmanager/framework/tool/ToolPlugin.java` | 新增 | SPI 接口，extends CustomTool（§3.2） |
| `src/main/java/io/agentmanager/framework/tool/ToolPluginBootstrapper.java` | 新增 | BFPP：目录解析 + 扫描 + URLClassLoader + ServiceLoader + config 注入 + registerSingleton + @PreDestroy close（§3.3） |
| `src/test/java/io/agentmanager/framework/tool/ToolPluginBootstrapperTest.java` | 新增 | 加载器与 BFPP 注册单测（§6） |
| `src/test/java/io/agentmanager/framework/tool/ToolPluginAssemblyTest.java` | 新增 | List<CustomTool> 注入语义集成测试（§6） |
| `agent-framework/AGENTS.md` | 修改 | 工具体系小节补充插件机制 |
| `docs/agent-framework-deploy.md` | 修改 | 环境变量表补 `AGENT_PLUGINS_DIR`（默认空，回退 `/config/plugins`） |
| `docs/tool-plugin-extension-plan.md` | 本文件 | 设计文档（含实施记录） |
| `e2e/plugin-echo/EchoToolPlugin.java` | 新增 | 示例插件源码（兼作开发模板，2026-09-25 已交付） |
| `e2e/scripts/plugin-smoke.sh` | 新增 | 部署冒烟：现场编译打包插件 → 独立进程验证 12 断言（2026-09-25 已交付，12/12 PASS） |

> 与初稿清单的差异：`AgentManagerProperties` / `application.yml` / `AgentScopeConfig` / `ToolController` 四处改动**全部取消**
> （BFPP 直读环境变量 + 注入源自动收集，见 §3.4）。

## 六、测试方案

| 用例 | 覆盖点 |
|------|--------|
| 目录不存在/为空 → 不报错、无单例注册 | 容错 |
| 正常 jar 加载 → 单例注册成功、日志输出工具名 | 主流程 |
| 多提供者（SPI 多行）→ 全部加载 | 多类一 jar |
| 单类多 `@Tool` 方法 → 全部收集 | 多工具一类 |
| deniedTools 命中 → 类粒度跳过（经 HarnessAgentFactory 既有循环，单测直接断言 List<CustomTool> 组成即可） | 过滤联动 |
| config.yaml `${ENV_VAR}` 替换 → configure 收到解析值 | 配置注入 |
| 坏 jar（损坏/缺 SPI 文件/构造抛异常）→ 跳过该 jar，其余正常 | 错误隔离 |
| 重名预检：插件工具名命中 BUILT_IN_TOOL_NAMES → WARN（仍注册） | 冲突处理 |
| **BFPP 装配语义**：手工 `DefaultListableBeanFactory` + postProcess → `getBeanNamesForType(CustomTool)` 含插件工具、`List<CustomTool>` 解析可见 | 注入时序（§3.3） |
| **reload 回归**：多次调用 `HarnessAgentFactory.build()`（模拟 OAF 重建）→ Toolkit 注册集含插件工具且实例复用 | §3.7 存活性 |

> 测试策略：测试插件类放 `src/test/java`（实现 `ToolPlugin` + `@Tool`），测试资源目录放
> `META-INF/services` 注册文件；`ToolPluginBootstrapper` 以 URLClassLoader(parent=测试类加载器) 直接加载
> `target/test-classes` 路径，避免测试期现场构建 jar。

## 七、实施步骤

| 步骤 | 内容 | 预估 |
|------|------|------|
| 1 | `ToolPlugin` 接口 + `ToolPluginBootstrapper`（BFPP + 加载 + 预检）+ 单测 | 3~4h |
| 2 | 装配集成测试（注入时序 + reload 回归） | 1~2h |
| 3 | 示例插件工程 + AGENTS.md / deploy 文档更新 | 1h |
| 4 | 手动验证：真实 jar 放入 /config/plugins 重启注册 → OAF reload 后工具仍在 → /tools 可见 | 1h |

**合计：约 1 天（不含阶段三平台集成）。**

## 八、后续规划（阶段三，另行方案）

- agent-manager Go 后端：插件 jar 上传/存储（MinIO）/版本管理，Agent 配置关联插件列表。
- 挂载模式部署：插件 jar 分发到 Pod 卷（ConfigMap 仅限文本，jar 需独立 PVC 或镜像内置 `COPY`）。
- 可选：`plugin-api` 独立 Maven 模块（剥离 Spring Boot 传递依赖，纯 core + SPI 接口，降低插件编译依赖面）。
- 可选：插件注册 middleware/hook 的 SPI 扩展点（事件埋点、请求拦截），需扩展 `ToolPlugin` 接口与装配逻辑。
- 可选：`AgentTool` 异步/流式工具形态支持（需 `HarnessAgentFactory` 注册循环加 `instanceof` 分支）。
