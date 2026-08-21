# 自定义工具插件化加载方案（Java SPI + plugins/ 目录）

> **状态: 📝 设计稿（2026-08-20，未实施）**
> 目标：让 agent-framework **无需重编译、无需重建镜像**即可加载自定义工具 —— 工具代码以独立 jar（插件）形式放入 `plugins/` 目录，
> 服务启动时自动扫描、类隔离加载、注册进现有 Toolkit，与 `@Tool` 硬编码工具（BusinessTools）同权运行。
> **范围：仅 agent-framework 工程（Java 服务 + 测试）。Go 后端、React 前端、镜像构建流程本次不涉及（平台集成列为后续阶段）。**

---

## 一、背景与目标

### 1.1 需求

当前自定义工具只有硬编码一种形态：`tool/BusinessTools.java` 的 `@Tool` 注解类，经 Spring Bean
`customTools` → `AgentScopeConfig.harnessAgent` 注册进 Toolkit
（`AgentScopeConfig.java:192-197, 238-252`）。**新增/修改任何自定义工具都必须重编译 agent-framework 并重建镜像**，
与"挂载模式（mount）复用预构建镜像、配置即部署"的产品形态矛盾。

需要一个**插件化加载机制**：

1. 工具代码以**独立 jar** 交付，放入约定目录，服务启动时自动加载（零代码改动）。
2. 加载后与现有 `@Tool` 工具完全同权：deniedTools 过滤、HITL 权限、`/tools` API、SSE 事件、多租户上下文全部自动生效。
3. 插件可访问 `RuntimeContext` / `AgentState` / `ToolExecutionContext`（进程内深度能力，区别于 MCP 进程外方案）。
4. 插件配置通过**环境变量 / config.yaml** 注入（支持 `${ENV_VAR}` 替换，对齐 McpToolRegistrar 惯例）。
5. 单个插件 jar 支持**多个工具类 / 多个工具**（SPI 多提供者 + 类内多 `@Tool` 方法）。

### 1.2 目标

- 阶段一（框架核心，~半天）：`ToolPlugin` SPI 接口 + `ToolPluginLoader`（目录扫描 + URLClassLoader + ServiceLoader）+ 装配接入 + 测试。
- 阶段二（插件工程与文档）：示例插件 jar + 插件开发模板说明（pom.xml / services 文件 / 打包约定）+ AGENTS.md 更新。
- 阶段三（平台集成，后续另立方案）：agent-manager Go 后端管理插件 jar 上传/分发，挂载到 Agent Pod 卷目录。
- **范围外**：热加载/热卸载（JVM 类卸载限制，插件更新需重启服务）；非 Java 工具（走 MCP 方案）；插件注册 middleware/hook（本期仅工具，扩展点见 §5.5）。

---

## 二、现状分析

### 2.1 相关代码链路

```
BusinessTools.java (@Tool)  ──Spring Bean──▶ AgentScopeConfig.customTools
                                                │ toolkit.registerTool(obj)（反射收集 @Tool 方法）
                                                │ deniedTools 类粒度过滤（toolToolNames）
                                                ▼
                                          HarnessAgent.toolkit
                                                │
                                                ▼
                              /tools API（目前仅透出 MCP 工具） / SSE 事件 / HITL / 多租户
```

| 现有能力 | 位置 | 与本方案的关系 |
|---------|------|---------------|
| `@Tool` 反射注册 | `Toolkit.registerTool(Object)`（agentscope-core，方法参数自动注入 `RuntimeContext`/`AgentState`/`ToolExecutionContext`） | 插件工具直接复用 |
| `AgentTool` 接口注册 | `Toolkit.registerAgentTool(AgentTool)`（`callAsync` 返回 `Mono<ToolResultBlock>`，异步/流式） | 插件可选实现 |
| deniedTools 类粒度过滤 | `AgentScopeConfig.java:240-252` `toolToolNames()` | 插件类走同一过滤 |
| MCP `${ENV_VAR}` 替换 | `McpToolRegistrar` / `ToolsConfigLoader.substituteEnv` | 插件 config.yaml 复用同一语法 |
| /tools API | `ToolController.java:59-81`（仅 MCP 工具 + includeInternal） | **需扩展**：透出插件自定义工具 |

### 2.2 SDK 能力确认（反编译验证）

- `Toolkit.registerTool(Object)` / `registerAgentTool(AgentTool)` / `ToolGroup`（分组激活）均公开可用。
- `ToolMethodInvoker` 支持注入 `RuntimeContext`、`AgentState`、`ToolExecutionContext`。
- agentscope-harness / agentscope-core **无内置 SPI/插件加载**，需框架自研。

---

## 三、方案设计

### 3.1 总体架构

```
{AGENT_CONFIG_DIR}/plugins/
├── weather-tool.jar          # 插件 jar（含 META-INF/services/io.agentmanager.framework.tool.ToolPlugin）
├── weather-tool/config.yaml  # 可选：插件配置文件（${ENV_VAR} 替换后注入 configure()）
└── db-query.jar

AgentFrameworkApplication 启动
  └── AgentScopeConfig.harnessAgent Bean 装配
        └── ToolPluginLoader.load(pluginsDir)          # 新增
              │  扫描 plugins/*.jar
              │  URLClassLoader(parent = 应用类加载器)  # 共享 agentscope-core/harness
              │  ServiceLoader.load(ToolPlugin.class, loader)  实例化 SPI 提供者
              │  config.yaml → configure(Map) 注入
              ▼
        toolkit.registerTool(instance.tools()...)      # 与 BusinessTools 同一路径
              │  deniedTools 类粒度过滤（复用 toolToolNames）
              ▼
        HarnessAgent（注册完毕，/tools、SSE、HITL 自动生效）
```

### 3.2 SPI 接口（新增 `tool/ToolPlugin.java`）

```java
package io.agentmanager.framework.tool;

/**
 * 自定义工具插件 SPI。
 * 插件 jar 内通过 META-INF/services/io.agentmanager.framework.tool.ToolPlugin
 * 注册实现类（每行一个全限定类名），由 ToolPluginLoader 在启动时加载。
 */
public interface ToolPlugin {

    /** 插件标识（默认取 jar 文件名，如 weather-tool） */
    default String id() { return getClass().getSimpleName(); }

    /** 工具实例集合：@Tool 注解类 / AgentTool 实现均可，loader 逐个 registerTool/registerAgentTool */
    List<Object> tools();

    /** 插件初始化（可选）：config.yaml 解析并替换 ${ENV_VAR} 后回调 */
    default void configure(Map<String, String> config) {}

    /** 生命周期回调（可选）：服务优雅关闭时调用 */
    default void close() {}
}
```

**设计要点**：
- `tools()` 返回对象列表而非单实例 —— 一个 jar 可含多个工具类（每类可含多个 `@Tool` 方法），且允许 `@Tool` 注解类与 `AgentTool` 接口实现混用。
- `configure(Map)` 为可选钩子：loader 从 `plugins/{id}/config.yaml` 读取（不存在则传空 Map）；插件亦可直接 `System.getenv()` 读取环境变量（同 JVM，天然支持）。
- 接口定义在 **agent-framework 自身**（非独立 api 模块，避免新增 Maven 模块）；插件 jar 以 `provided` 作用域依赖 agent-framework。

### 3.3 加载器（新增 `tool/ToolPluginLoader.java`）

```java
@Component
public class ToolPluginLoader {

    /** 扫描 {pluginsDir}/*.jar，返回加载成功的插件集合；单个 jar 失败仅告警不阻断启动 */
    public List<ToolPlugin> load(Path pluginsDir);

    /** 单个 jar 加载：URLClassLoader(parent=应用类加载器) + ServiceLoader */
    Optional<ToolPlugin> loadJar(Path jar);
}
```

**加载规则**：
1. `pluginsDir` 不存在或为空 → 直接返回空列表（不报错，兼容无插件部署）。
2. 每个 `*.jar` 用独立 `URLClassLoader`（parent = 当前线程上下文类加载器，**parent-first 委托**）：
   - 共享 `agentscope-core` / `agentscope-harness` / `agent-framework` 等框架类（插件不得打包这些依赖，见 §3.5）。
   - 插件自己的第三方依赖（若有）需 **shade 进 jar**；与框架重名类以父加载器为准。
3. `ServiceLoader.load(ToolPlugin.class, loader)` 实例化该 jar 内全部 SPI 提供者。
4. 每个提供者按 `{jar名去后缀}/config.yaml` 读取配置（不存在跳过），`${ENV_VAR}` 替换（复用 `substituteEnv` 同款实现）后调 `configure(Map)`。
5. 单 jar / 单类加载异常 → `log.warn` 记录并跳过，**不影响其他插件与服务启动**。
6. `close()` 注册到 Spring `DisposableBean` / `@PreDestroy`，服务关闭时逐个调用。

### 3.4 装配接入（修改 `config/AgentScopeConfig.java` + `config/AgentManagerProperties.java`）

```java
// AgentManagerProperties 新增
public record AgentManagerProperties(
    ...,
    @DefaultValue("") String pluginsDir        // 环境变量 AGENT_PLUGINS_DIR，空则回退 {configDir}/plugins
) {}

// AgentScopeConfig.harnessAgent 装配处（customTools 循环之后、mcpToolRegistrar 之前）
var pluginTools = toolPluginLoader.load(Path.of(props.pluginsDir(), "plugins"));
for (var plugin : pluginTools) {
    for (var tool : plugin.tools()) {
        var names = toolToolNames(tool);                       // 复用现有反射提取
        if (oafConfig.hasDeniedTools()
                && names.stream().anyMatch(oafConfig.deniedTools()::contains)) {
            log.info("Plugin tool(s) {} excluded by deniedTools", names);
            continue;
        }
        toolkit.registerTool(tool);
        customToolNames.addAll(names);
        log.info("Plugin [{}] registered tools: {}", plugin.id(), names);
    }
}
```

- 装配顺序：BusinessTools → 插件工具 → MCP 工具（现有循环不变，插件并入同一 `toolkit`）。
- `customToolNames`（HITL 权限白名单）自动覆盖插件工具。
- `AGENT_PLUGINS_DIR` 为空时回退 `{configDir}/plugins`（`/config/plugins`，与挂载模式目录约定一致）。

### 3.5 插件 jar 打包约定

| 项 | 约定 |
|----|------|
| 编译依赖 | `agent-framework`（scope=provided，取 SPI 接口）+ `agentscope-core`（provided，取 @Tool/AgentTool） |
| 打包 | 普通 jar（勿打 fat jar / 勿 shade agentscope-core）；自带第三方依赖须 shade 进 jar 内 |
| SPI 注册 | `META-INF/services/io.agentmanager.framework.tool.ToolPlugin`（每行一个全限定类名） |
| 放置位置 | `{AGENT_CONFIG_DIR}/plugins/xxx-tool.jar`（挂载模式由部署侧挂卷） |
| 插件配置 | `{AGENT_CONFIG_DIR}/plugins/{jar名}/config.yaml`（可选，`${ENV_VAR}` 可替换） |
| 环境变量 | 插件运行在框架 JVM 内，可直接 `System.getenv()` 读取全部注入环境变量 |

### 3.6 /tools API 扩展（修改 `controller/ToolController.java`）

当前 `/tools` 仅返回 MCP 工具（`ToolController.java:83-105`），BusinessTools 也不在列表。本次同步扩展：
- `McpToolRegistrar` / 新增 `PluginToolRegistry` 记录已注册插件工具信息（name、description、plugin id、category=`plugin`）。
- `ToolController.listTools` 合并输出，`category` 区分 `mcp` / `plugin` / `internal`。

> 说明：`/tools` 扩展是**展示层配套**；即使不做，插件工具在 LLM 调用链上已完整可用（注册进 Toolkit 即生效）。

### 3.7 安全边界

- **插件即代码**：插件在框架 JVM 进程内以全权限运行（可读环境变量/密钥、访问数据库连接、执行任意代码）。
  **plugins/ 目录必须视为可信输入**，仅允许受信任方投放 jar；不做沙箱隔离（如需隔离请用 MCP 进程外方案）。
- 加载阶段异常已隔离（单 jar 失败不阻断启动），但**不做类加载器回收**（启动期一次性加载，无卸载需求）。

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
        <version>2.0.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

```java
// WeatherTools.java —— 开发者只写这一个类
public class WeatherTools implements ToolPlugin {

    @Override
    public List<Object> tools() {
        return List.of(this);          // 同类内多 @Tool 方法自动全量收集
    }

    @Tool(name = "get_weather", description = "查询指定城市天气", readOnly = true)
    public String getWeather(@ToolParam(name = "city", description = "城市名") String city) {
        return weatherApi.query(city);
    }

    @Tool(name = "get_forecast", description = "查询未来几天预报", readOnly = true)
    public String getForecast(@ToolParam(name = "city", description = "城市名") String city) {
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
# {pluginsDir}/weather-tool/config.yaml —— loader 解析后注入 configure(Map)
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
# 重启 agent-framework → 启动日志出现 "Plugin [weather-tool] registered tools: [get_weather, get_forecast]"
curl localhost:8100/tools?includeInternal=false   # 可见 category=plugin 工具
```

---

## 五、文件改动清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/main/java/io/agentmanager/framework/tool/ToolPlugin.java` | 新增 | SPI 接口（§3.2） |
| `src/main/java/io/agentmanager/framework/tool/ToolPluginLoader.java` | 新增 | 目录扫描 + URLClassLoader + ServiceLoader + config 注入（§3.3） |
| `src/main/java/io/agentmanager/framework/config/AgentManagerProperties.java` | 修改 | 新增 `pluginsDir`（`AGENT_PLUGINS_DIR`） |
| `src/main/resources/application.yml` | 修改 | `agent.plugins-dir: ${AGENT_PLUGINS_DIR:}` |
| `src/main/java/io/agentmanager/framework/config/AgentScopeConfig.java` | 修改 | harnessAgent 装配接入插件加载（§3.4） |
| `src/main/java/io/agentmanager/framework/controller/ToolController.java` | 修改 | /tools 透出 plugin 工具（§3.6） |
| `src/test/java/io/agentmanager/framework/tool/ToolPluginLoaderTest.java` | 新增 | 加载器单测 |
| `src/test/java/io/agentmanager/framework/config/AgentScopeConfigPluginTest.java` | 新增 | 装配集成测试 |
| `AGENTS.md` | 修改 | 工具体系小节补充插件机制 |
| `docs/tool-plugin-extension-plan.md` | 本文件 | 设计文档 |
| `examples/plugins/weather-tool/`（或 docs 内嵌模板） | 新增 | 示例插件工程（阶段二） |

## 六、测试方案

| 用例 | 覆盖点 |
|------|--------|
| 目录不存在/为空 → 返回空列表，不报错 | 容错 |
| 正常 jar 加载 → 工具注册成功、日志输出工具名 | 主流程 |
| 多提供者（SPI 多行）→ 全部加载 | 多类一 jar |
| 单类多 `@Tool` 方法 → 全部注册 | 多工具一类 |
| `@Tool` 与 `AgentTool` 混用 | 双注册路径 |
| deniedTools 命中 → 类粒度跳过 | 过滤联动 |
| config.yaml `${ENV_VAR}` 替换 → configure 收到解析值 | 配置注入 |
| 坏 jar（损坏/缺 SPI 文件/抛异常）→ 跳过该 jar，其余正常 | 错误隔离 |
| 重名工具（同名第二次注册）→ 告警跳过 | 冲突处理 |
| 装配集成：pluginsDir 配置 → harnessAgent 工具集含插件工具 | Spring 装配 |

> 测试策略：测试插件类放 `src/test/java`（实现 `ToolPlugin` + `@Tool`），测试资源目录放
> `META-INF/services` 注册文件；`ToolPluginLoader` 以 URLClassLoader(parent=测试类加载器) 直接加载
> `target/test-classes` 路径，避免测试期现场构建 jar。

## 七、实施步骤

| 步骤 | 内容 | 预估 |
|------|------|------|
| 1 | SPI 接口 + ToolPluginLoader + 单测 | 2~3h |
| 2 | AgentManagerProperties / application.yml / AgentScopeConfig 装配接入 + 集成测试 | 1~2h |
| 3 | ToolController /tools 扩展 + 测试 | 0.5~1h |
| 4 | 示例插件工程 + AGENTS.md / 文档更新 | 1h |
| 5 | 手动验证：真实 jar 放入 /config/plugins 重启注册 | 0.5h |

**合计：约半天~1 天（不含阶段三平台集成）。**

## 八、后续规划（阶段三，另行方案）

- agent-manager Go 后端：插件 jar 上传/存储（MinIO）/版本管理，Agent 配置关联插件列表。
- 挂载模式部署：插件 jar 分发到 Pod 卷（ConfigMap 仅限文本，jar 需独立 PVC 或镜像内置 `COPY`）。
- 可选：`plugin-api` 独立 Maven 模块（剥离 Spring Boot 传递依赖，纯 core + SPI 接口，降低插件编译依赖面）。
- 可选：插件注册 middleware/hook 的 SPI 扩展点（事件埋点、请求拦截），需扩展 `ToolPlugin` 接口与装配逻辑。
