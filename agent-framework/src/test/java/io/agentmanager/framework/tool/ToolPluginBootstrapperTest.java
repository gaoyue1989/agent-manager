package io.agentmanager.framework.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import io.agentmanager.framework.service.InternalToolRegistry;
import io.agentmanager.framework.tool.pluginfix.ConfigPlugin;

/**
 * ToolPluginBootstrapper 加载器单测：jar 现场构建（拷贝已编译的 fixture 类字节 + SPI 注册文件），
 * BFPP 语义以 DefaultListableBeanFactory 直接验证（registerSingleton 后按类型检索可见，
 * 与真实上下文中 List&lt;CustomTool&gt; 注入的解析机制一致）。
 */
class ToolPluginBootstrapperTest {

    private static final String SERVICES_PATH = "META-INF/services/io.agentmanager.framework.tool.ToolPlugin";

    @TempDir
    Path tmp;
    private Path pluginsDir;
    private ToolPluginBootstrapper bootstrapper;
    private DefaultListableBeanFactory beanFactory;

    @BeforeEach
    void setUp() throws IOException {
        pluginsDir = Files.createDirectories(tmp.resolve("plugins"));
        bootstrapper = new ToolPluginBootstrapper();
        beanFactory = new DefaultListableBeanFactory();
        System.setProperty(ToolPluginBootstrapper.PLUGINS_DIR_ENV, pluginsDir.toString());
        ConfigPlugin.lastConfig = Map.of();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ToolPluginBootstrapper.PLUGINS_DIR_ENV);
        System.clearProperty(ToolPluginBootstrapper.CONFIG_DIR_ENV);
    }

    @Test
    void missingDirShouldSkipQuietly() throws IOException {
        System.setProperty(ToolPluginBootstrapper.PLUGINS_DIR_ENV, tmp.resolve("no-such-dir").toString());
        bootstrapper.postProcessBeanDefinitionRegistry(beanFactory);
        assertEquals(0, bootstrapper.loadedPluginCount());
        assertTrue(beanFactory.getBeansOfType(CustomTool.class).isEmpty());
    }

    @Test
    void emptyDirShouldSkipQuietly() {
        bootstrapper.postProcessBeanDefinitionRegistry(beanFactory);
        assertEquals(0, bootstrapper.loadedPluginCount());
        assertTrue(beanFactory.getBeansOfType(CustomTool.class).isEmpty());
    }

    @Test
    void validJarShouldRegisterPluginTools() throws IOException {
        buildJar("echo-tool.jar", List.of("io.agentmanager.framework.tool.pluginfix.EchoPlugin"),
            "io.agentmanager.framework.tool.pluginfix.EchoPlugin");

        bootstrapper.postProcessBeanDefinitionRegistry(beanFactory);

        var beans = beanFactory.getBeansOfType(CustomTool.class);
        assertEquals(1, beans.size());
        var entry = beans.entrySet().iterator().next();
        assertTrue(entry.getKey().startsWith("toolPlugin:EchoPlugin:"), "单例名应为 toolPlugin:{id}:{序号}，实际 " + entry.getKey());
        assertTrue(InternalToolRegistry.extractToolNames(entry.getValue()).contains("echo_query"));
        // 类加载语义：parent-first 委托下，测试类路径可见的 fixture 类直接复用父加载器的 Class
        // （生产环境插件类不在应用类路径上，由子加载器自加载——该场景由部署 E2E 覆盖）
        assertSame(io.agentmanager.framework.tool.pluginfix.EchoPlugin.class, entry.getValue().getClass());
    }

    @Test
    void multiProviderAndMultiToolJarShouldRegisterAll() throws IOException {
        buildJar("multi-tool.jar",
            List.of("io.agentmanager.framework.tool.pluginfix.MultiToolPlugin",
                "io.agentmanager.framework.tool.pluginfix.EchoPlugin"),
            "io.agentmanager.framework.tool.pluginfix.MultiToolPlugin",
            "io.agentmanager.framework.tool.pluginfix.MultiToolPlugin$StandaloneTool",
            "io.agentmanager.framework.tool.pluginfix.EchoPlugin");

        bootstrapper.postProcessBeanDefinitionRegistry(beanFactory);

        var names = beanFactory.getBeansOfType(CustomTool.class).values().stream()
            .flatMap(t -> InternalToolRegistry.extractToolNames(t).stream())
            .toList();
        assertEquals(List.of("alpha_probe", "beta_probe", "echo_query").stream().sorted().toList(),
            names.stream().sorted().toList());
    }

    @Test
    void corruptJarShouldBeSkippedAndOthersLoad() throws IOException {
        Files.write(pluginsDir.resolve("corrupt.jar"), "not a jar".repeat(100).getBytes(StandardCharsets.UTF_8));
        buildJar("echo-tool.jar", List.of("io.agentmanager.framework.tool.pluginfix.EchoPlugin"),
            "io.agentmanager.framework.tool.pluginfix.EchoPlugin");

        bootstrapper.postProcessBeanDefinitionRegistry(beanFactory);

        var beans = beanFactory.getBeansOfType(CustomTool.class);
        assertEquals(1, beans.size());
    }

    @Test
    void brokenProviderShouldSkipButOthersInSameJarLoad() throws IOException {
        buildJar("mixed.jar",
            List.of("io.agentmanager.framework.tool.pluginfix.BrokenPlugin",
                "io.agentmanager.framework.tool.pluginfix.EchoPlugin"),
            "io.agentmanager.framework.tool.pluginfix.BrokenPlugin",
            "io.agentmanager.framework.tool.pluginfix.EchoPlugin");

        bootstrapper.postProcessBeanDefinitionRegistry(beanFactory);

        assertEquals(1, beanFactory.getBeansOfType(CustomTool.class).size());
    }

    @Test
    void duplicatePluginIdsShouldGetUniqueSingletonNames() throws IOException {
        buildJar("a.jar", List.of("io.agentmanager.framework.tool.pluginfix.EchoPlugin"),
            "io.agentmanager.framework.tool.pluginfix.EchoPlugin");
        buildJar("b.jar", List.of("io.agentmanager.framework.tool.pluginfix.EchoPlugin"),
            "io.agentmanager.framework.tool.pluginfix.EchoPlugin");

        bootstrapper.postProcessBeanDefinitionRegistry(beanFactory);

        var names = beanFactory.getBeansOfType(CustomTool.class).keySet();
        assertEquals(2, names.size());
    }

    @Test
    void configYamlShouldInjectWithEnvSubstitution() throws IOException {
        buildJar("config-tool.jar", List.of("io.agentmanager.framework.tool.pluginfix.ConfigPlugin"),
            "io.agentmanager.framework.tool.pluginfix.ConfigPlugin");
        // 配置目录按 {jar名去后缀} 约定；${PATH} 必然存在、${AGENT_PLUGIN_NO_SUCH} 必然缺失
        Files.createDirectories(pluginsDir.resolve("config-tool"));
        Files.writeString(pluginsDir.resolve("config-tool").resolve("config.yaml"), """
            literal: hello
            pathEnv: ${PATH}
            missingEnv: ${AGENT_PLUGIN_NO_SUCH_VAR_XYZ}
            num: 42
            """);

        bootstrapper.postProcessBeanDefinitionRegistry(beanFactory);

        assertEquals("hello", ConfigPlugin.lastConfig.get("literal"));
        assertEquals(System.getenv("PATH"), ConfigPlugin.lastConfig.get("pathEnv"));
        assertEquals("", ConfigPlugin.lastConfig.get("missingEnv"));
        assertEquals("42", ConfigPlugin.lastConfig.get("num"));
    }

    @Test
    void destroyShouldCallPluginClose() throws IOException {
        buildJar("lifecycle.jar", List.of("io.agentmanager.framework.tool.pluginfix.LifecyclePlugin"),
            "io.agentmanager.framework.tool.pluginfix.LifecyclePlugin");

        bootstrapper.postProcessBeanDefinitionRegistry(beanFactory);
        assertFalse(io.agentmanager.framework.tool.pluginfix.LifecyclePlugin.closed);
        bootstrapper.destroy();
        assertTrue(io.agentmanager.framework.tool.pluginfix.LifecyclePlugin.closed);
    }

    @Test
    void invalidPluginsDirValueShouldFallBackToDefault() {
        // 非法路径值（嵌入 NUL，trim 不会剥掉中间字符）不阻断：回退默认 {AGENT_CONFIG_DIR:-/config}/plugins
        System.setProperty(ToolPluginBootstrapper.PLUGINS_DIR_ENV, "bad\0path");
        assertEquals(Path.of("/config/plugins"), ToolPluginBootstrapper.resolvePluginsDir());
    }

    @Test
    void pluginsDirShouldFallBackToConfigDir() {
        System.clearProperty(ToolPluginBootstrapper.PLUGINS_DIR_ENV);
        System.setProperty(ToolPluginBootstrapper.CONFIG_DIR_ENV, "/cfg-root");
        assertEquals(Path.of("/cfg-root/plugins"), ToolPluginBootstrapper.resolvePluginsDir());

        System.setProperty(ToolPluginBootstrapper.PLUGINS_DIR_ENV, "/explicit");
        assertEquals(Path.of("/explicit"), ToolPluginBootstrapper.resolvePluginsDir());
    }

    /**
     * 现场构建测试插件 jar：SPI 注册文件 + 拷贝 target/test-classes 中已编译的 fixture 类字节。
     *
     * @param servicesEntries SPI 注册文件内容（每行一个全限定类名）
     * @param classes         打进 jar 的类（含嵌套类，须完整列出）
     */
    private void buildJar(String jarName, List<String> servicesEntries, String... classes) throws IOException {
        Files.createDirectories(pluginsDir);
        var jar = pluginsDir.resolve(jarName);
        try (var jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry(SERVICES_PATH));
            jos.write(String.join("\n", servicesEntries).getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            for (var className : classes) {
                var resource = className.replace('.', '/') + ".class";
                try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new IllegalStateException("测试类资源缺失: " + resource);
                    }
                    jos.putNextEntry(new JarEntry(resource));
                    in.transferTo(jos);
                    jos.closeEntry();
                }
            }
        }
    }
}
