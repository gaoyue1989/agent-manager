package io.agentmanager.framework.tool;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.stereotype.Component;

import io.agentmanager.framework.service.HarnessAgentFactory;
import io.agentmanager.framework.service.InternalToolRegistry;
import org.yaml.snakeyaml.Yaml;

/**
 * 工具插件装配钩子（BFPP）：扫描插件目录 → 独立 URLClassLoader（parent-first）经
 * ServiceLoader 实例化 {@link ToolPlugin} → config.yaml（${ENV_VAR} 替换）注入
 * {@code configure()} → 工具实例 {@code registerSingleton} 并入 {@code List<CustomTool>}
 * 注入源。此后启动装配与 OAF reload 整包重建（HarnessAgentFactory）、/tools 注册集
 * （InternalToolRegistry）、HITL 权限白名单均按既有 {@code List<CustomTool>} 语义
 * 自动包含插件工具，无需任何消费方改动。
 *
 * <p>装配时序：BFPP 在 invokeBeanFactoryPostProcessors 阶段执行，早于普通 bean 实例化；
 * 手工注册单例（manualSingletonNames）参与 getBeanNamesForType 按类型检索，故后续
 * {@code List<CustomTool>} 构造器注入可解析到插件工具。
 *
 * <p>实现约束：本类会被提前实例化，构造器不得注入任何 bean；插件目录从环境变量直读
 * （环境变量优先，同名系统属性兜底以便测试），不经 AgentManagerProperties。
 *
 * <p>close() 生命周期走 {@link DisposableBean} 而非 @PreDestroy：BFPP bean 的提前实例化
 * 发生在 CommonAnnotationBeanPostProcessor 登记之前，注解式销毁回调不会被登记（CR 实证）；
 * DisposableBean 的 instanceof 检查不依赖后置处理器注册时机，销毁回调必然生效。
 *
 * <p>fail-soft 语义：单 jar / 单 SPI 提供者加载失败仅告警跳过，不影响其他插件与服务启动。
 * 类加载器启动期一次性创建、不回收（插件工具的方法签名在每次 agent 重建时仍会被反射解析，
 * 加载器须存活于整个生命周期）。
 */
@Component
public class ToolPluginBootstrapper implements BeanDefinitionRegistryPostProcessor, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ToolPluginBootstrapper.class);

    /** 插件目录环境变量；未设置时回退 {AGENT_CONFIG_DIR:-/config}/plugins */
    public static final String PLUGINS_DIR_ENV = "AGENT_PLUGINS_DIR";
    /** 配置目录环境变量（与 AgentManagerProperties.configDir 同源，BFPP 阶段直读避免提前绑定） */
    public static final String CONFIG_DIR_ENV = "AGENT_CONFIG_DIR";
    private static final String DEFAULT_CONFIG_DIR = "/config";

    /**
     * 既有硬编码自定义工具（BusinessTools/FileTools）注册名：重名预检基线补充。
     * 与 {@link HarnessAgentFactory#BUILT_IN_TOOL_NAMES} 同为手工维护——漂移时仅少一条
     * WARN（不影响注册行为），随工具增删同步。
     */
    private static final Set<String> FRAMEWORK_CUSTOM_TOOL_NAMES = Set.of(
        "get_current_time", "echo", "present_file", "present_url");

    /** 已实例化的插件（destroy() 生命周期回调使用；含 configure/注册中途失败者，尽量回收资源） */
    private final List<ToolPlugin> plugins = new ArrayList<>();

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        var pluginsDir = resolvePluginsDir();
        if (!Files.isDirectory(pluginsDir)) {
            log.info("Tool plugins dir {} not present, skip loading", pluginsDir);
            return;
        }
        List<Path> jars;
        try (Stream<Path> stream = Files.list(pluginsDir)) {
            jars = stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                .sorted().toList();
        } catch (IOException e) {
            log.warn("Tool plugins dir {} list failed, skip loading: {}", pluginsDir, e.getMessage());
            return;
        }
        if (jars.isEmpty()) {
            log.info("Tool plugins dir {} has no jar, skip loading", pluginsDir);
            return;
        }

        // 重名预检基线：Harness 内置 + 既有硬编码自定义 + 先前已注册的插件工具名（命中仅告警，注册行为交给 Toolkit 覆盖语义）
        var knownNames = new HashSet<String>(HarnessAgentFactory.BUILT_IN_TOOL_NAMES);
        knownNames.addAll(FRAMEWORK_CUSTOM_TOOL_NAMES);
        int toolSeq = 0;
        for (var jar : jars) {
            try {
                // 注意：不关闭 classloader——实例方法签名在每次 agent 重建时仍会被反射解析
                var loader = new URLClassLoader(
                    new URL[] { jar.toUri().toURL() }, ToolPluginBootstrapper.class.getClassLoader());
                toolSeq = loadProvidersFromJar(jar, loader, registry, knownNames, toolSeq);
            } catch (Throwable t) {
                // 坏 jar（不可读/损坏）仅告警跳过，不影响其余插件
                log.warn("Tool plugin jar {} load failed (skipped): {}", jar.getFileName(), t.toString());
            }
        }
        log.info("Tool plugins loaded: {} plugin(s), {} tool(s) registered from {}",
            plugins.size(), countRegisteredTools(registry), pluginsDir);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // 注册动作全部在 postProcessBeanDefinitionRegistry 完成，此处无需处理
    }

    /** 单 jar 加载全部 SPI 提供者（单个提供者失败仅告警；ServiceLoader 迭代按 services 文件行有限推进，无死循环风险） */
    private int loadProvidersFromJar(Path jar, URLClassLoader loader, BeanDefinitionRegistry registry,
                                     Set<String> knownNames, int toolSeq) {
        var config = loadPluginConfig(jar);
        // 手工迭代而非 for-each：ServiceLoader 的懒加载迭代器在单个提供者实例化失败时抛
        // ServiceConfigurationError，需逐个捕获并继续，让同 jar 其余提供者仍被加载
        var iterator = ServiceLoader.load(ToolPlugin.class, loader).iterator();
        while (true) {
            ToolPlugin plugin;
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                plugin = iterator.next();
            } catch (Throwable t) {
                log.warn("Tool plugin provider in {} failed to load (skipped): {}", jar.getFileName(), t.toString());
                continue;
            }
            // 先入列再初始化：configure/注册中途抛异常的插件也能在 destroy() 收到 close()（尽量回收半初始化资源）
            plugins.add(plugin);
            try {
                plugin.configure(config);
                toolSeq = registerPluginTools(plugin, registry, knownNames, toolSeq);
            } catch (Throwable t) {
                log.warn("Tool plugin {} init/register failed (skipped): {}", plugin.id(), t.toString());
            }
        }
        return toolSeq;
    }

    /** 注册单个插件的全部工具实例为单例（"toolPlugin:{id}:{序号}"，序号保证跨插件唯一） */
    private int registerPluginTools(ToolPlugin plugin, BeanDefinitionRegistry registry,
                                    Set<String> knownNames, int toolSeq) {
        var names = new ArrayList<String>();
        for (var tool : plugin.tools()) {
            var toolNames = InternalToolRegistry.extractToolNames(tool);
            if (toolNames.isEmpty()) {
                log.warn("Tool plugin {} provides a tool object without @Tool methods (skipped): {}",
                    plugin.id(), tool.getClass().getName());
                continue;
            }
            for (var name : toolNames) {
                if (knownNames.contains(name)) {
                    log.warn("Tool plugin {} tool name '{}' conflicts with built-in/registered tool "
                        + "(registered anyway, Toolkit overwrite semantics apply)", plugin.id(), name);
                }
            }
            if (registry instanceof SingletonBeanRegistry singletonRegistry) {
                singletonRegistry.registerSingleton("toolPlugin:" + plugin.id() + ":" + toolSeq++, tool);
            } else {
                log.warn("Tool plugin {} tool {} skipped: registry {} does not support manual singleton",
                    plugin.id(), tool.getClass().getName(), registry.getClass().getName());
                continue;
            }
            knownNames.addAll(toolNames);
            names.addAll(toolNames);
        }
        if (!names.isEmpty()) {
            log.info("Tool plugin [{}] registered tools: {}", plugin.id(), names);
        }
        return toolSeq;
    }

    /** 读取 {pluginsDir}/{jar名去后缀}/config.yaml 为扁平 k-v（${ENV_VAR} 替换，McpToolRegistrar.resolveEnv 同款语义）；文件不存在返回空 Map */
    private Map<String, String> loadPluginConfig(Path jar) {
        var stem = jar.getFileName().toString().replaceFirst("\\.jar$", "");
        var configPath = jar.getParent().resolve(stem).resolve("config.yaml");
        if (!Files.isRegularFile(configPath)) {
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(configPath)) {
            var raw = new Yaml().<Map<String, Object>>load(in);
            var config = new LinkedHashMap<String, String>();
            if (raw != null) {
                raw.forEach((key, value) -> {
                    if (value instanceof String s) {
                        config.put(String.valueOf(key), resolveEnv(s));
                    } else if (value instanceof Number || value instanceof Boolean) {
                        config.put(String.valueOf(key), String.valueOf(value));
                    } else {
                        log.warn("Tool plugin config {} ignores non-scalar entry '{}' (value: {})",
                            configPath, key, value == null ? "null" : value.getClass().getSimpleName());
                    }
                });
            }
            return config;
        } catch (Exception e) {
            log.warn("Tool plugin config {} parse failed (empty config supplied): {}", configPath, e.getMessage());
            return Map.of();
        }
    }

    /** ${ENV_VAR} 语法替换：环境变量缺失时替换为空串并告警（与 McpToolRegistrar.resolveEnv 一致） */
    private String resolveEnv(String value) {
        if (value.startsWith("${") && value.endsWith("}")) {
            var envName = value.substring(2, value.length() - 1);
            var envVal = System.getenv(envName);
            if (envVal != null) {
                return envVal;
            }
            log.warn("Env var {} not set, using empty value", envName);
            return "";
        }
        return value;
    }

    /**
     * 插件目录解析：AGENT_PLUGINS_DIR 环境变量优先，同名系统属性兜底（测试用），
     * 缺省 {AGENT_CONFIG_DIR:-/config}/plugins；路径值非法时告警回退默认（fail-soft，不阻断启动）。
     */
    static Path resolvePluginsDir() {
        var dir = firstNonBlank(System.getenv(PLUGINS_DIR_ENV), System.getProperty(PLUGINS_DIR_ENV));
        if (dir != null) {
            try {
                return Path.of(dir);
            } catch (InvalidPathException e) {
                log.warn("Plugin dir {} invalid ({}), fallback to default", PLUGINS_DIR_ENV, e.getMessage());
            }
        }
        var configDir = firstNonBlank(System.getenv(CONFIG_DIR_ENV), System.getProperty(CONFIG_DIR_ENV));
        try {
            return Path.of(configDir != null ? configDir : DEFAULT_CONFIG_DIR).resolve("plugins");
        } catch (InvalidPathException e) {
            log.warn("Config dir {} invalid ({}), fallback to default", CONFIG_DIR_ENV, e.getMessage());
            return Path.of(DEFAULT_CONFIG_DIR).resolve("plugins");
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    /** 已加载插件数（供测试与日志断言） */
    int loadedPluginCount() {
        return plugins.size();
    }

    /** 统计本 Bootstrapper 注册进 registry 的工具单例数量（按单例名前缀识别） */
    private int countRegisteredTools(BeanDefinitionRegistry registry) {
        if (registry instanceof ConfigurableListableBeanFactory factory) {
            var count = 0;
            for (var name : factory.getSingletonNames()) {
                if (name.startsWith("toolPlugin:")) {
                    count++;
                }
            }
            return count;
        }
        return -1;
    }

    /** 服务优雅关闭时逐个回调 close()（DisposableBean 实现，见类注释；异常隔离，互不影响） */
    @Override
    public void destroy() {
        for (var plugin : plugins) {
            try {
                plugin.close();
            } catch (Throwable t) {
                log.warn("Tool plugin {} close failed: {}", plugin.id(), t.toString());
            }
        }
    }
}
