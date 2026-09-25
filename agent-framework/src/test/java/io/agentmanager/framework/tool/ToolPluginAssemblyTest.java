package io.agentmanager.framework.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import io.agentmanager.framework.service.InternalToolRegistry;

/**
 * 插件装配语义集成测试：BFPP 注册的手工单例必须被 {@code List<CustomTool>} 注入解析到——
 * 这是 HarnessAgentFactory（启动 + OAF reload 整包重建）与 InternalToolRegistry（/tools）
 * 两处消费方零改动共享插件工具的机制保证（tool-plugin-extension-plan §3.3/§3.4）。
 */
class ToolPluginAssemblyTest {

    private static final String SERVICES_PATH = "META-INF/services/io.agentmanager.framework.tool.ToolPlugin";
    private static final String ECHO_PLUGIN = "io.agentmanager.framework.tool.pluginfix.EchoPlugin";
    private static final String LIFECYCLE_PLUGIN = "io.agentmanager.framework.tool.pluginfix.LifecyclePlugin";

    @TempDir
    Path tmp;

    @BeforeEach
    void setUp() throws IOException {
        var pluginsDir = Files.createDirectories(tmp.resolve("plugins"));
        buildPluginJar(pluginsDir, "echo-tool.jar", ECHO_PLUGIN);
        System.setProperty(ToolPluginBootstrapper.PLUGINS_DIR_ENV, pluginsDir.toString());
        io.agentmanager.framework.tool.pluginfix.LifecyclePlugin.closed = false;
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ToolPluginBootstrapper.PLUGINS_DIR_ENV);
    }

    @Test
    void pluginToolsShouldFlowIntoCustomToolListInjection() {
        new ApplicationContextRunner()
            .withUserConfiguration(BootstrapperImport.class, ConsumerConfig.class)
            .run(ctx -> {
                if (ctx.getStartupFailure() != null) {
                    throw new AssertionError("上下文启动失败", ctx.getStartupFailure());
                }
                var consumer = ctx.getBean(CustomToolConsumer.class);
                assertNotNull(consumer);
                assertEquals(1, consumer.tools.size(), "List<CustomTool> 注入应含且仅含插件工具");
                assertTrue(consumer.toolNames.contains("echo_query"));
            });
    }

    /**
     * close() 生命周期回归（CR P1）：BFPP bean 提前实例化早于 CommonAnnotationBeanPostProcessor
     * 登记，@PreDestroy 不会被调；Bootstrapper 由此实现 DisposableBean——上下文关闭
     * （ApplicationContextRunner run() 结束即 close）后插件 close() 必须已被回调。
     */
    @Test
    void pluginCloseShouldRunOnContextClose() throws IOException {
        var pluginsDir = Path.of(System.getProperty(ToolPluginBootstrapper.PLUGINS_DIR_ENV));
        buildPluginJar(pluginsDir, "lifecycle.jar", LIFECYCLE_PLUGIN);
        Files.delete(pluginsDir.resolve("echo-tool.jar"));

        new ApplicationContextRunner()
            .withUserConfiguration(BootstrapperImport.class)
            .run(ctx -> {
                if (ctx.getStartupFailure() != null) {
                    throw new AssertionError("上下文启动失败", ctx.getStartupFailure());
                }
            });
        assertTrue(io.agentmanager.framework.tool.pluginfix.LifecyclePlugin.closed,
            "上下文关闭后插件 close() 应回调（DisposableBean 路径）");
    }

    /** 模拟 HarnessAgentFactory / InternalToolRegistry 的消费形态：构造器注入 List<CustomTool> */
    @Configuration
    static class ConsumerConfig {

        @Bean
        CustomToolConsumer customToolConsumer(List<CustomTool> customTools) {
            return new CustomToolConsumer(customTools);
        }
    }

    /** 以 @Import 注册 BFPP bean（等价于组件扫描发现） */
    @Configuration
    @Import(ToolPluginBootstrapper.class)
    static class BootstrapperImport {
    }

    static class CustomToolConsumer {

        final List<CustomTool> tools;
        final Set<String> toolNames;

        CustomToolConsumer(List<CustomTool> tools) {
            this.tools = List.copyOf(tools);
            this.toolNames = tools.stream()
                .flatMap(t -> InternalToolRegistry.extractToolNames(t).stream())
                .collect(Collectors.toUnmodifiableSet());
        }
    }

    private void buildPluginJar(Path pluginsDir, String jarName, String providerClass) throws IOException {
        var jar = pluginsDir.resolve(jarName);
        try (var jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry(SERVICES_PATH));
            jos.write(providerClass.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            var resource = providerClass.replace('.', '/') + ".class";
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
