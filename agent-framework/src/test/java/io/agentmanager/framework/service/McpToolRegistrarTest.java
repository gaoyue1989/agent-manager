package io.agentmanager.framework.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import reactor.core.publisher.Mono;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolRegistrarTest {

    @TempDir
    Path tempDir;

    private McpToolRegistrar registrar;

    @BeforeEach
    void setUp() {
        var props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass", "test"),
            tempDir.toString(),
            new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7)
        );
        registrar = new McpToolRegistrar(props);
    }

    private OafConfig.McpServerConfig mcp(String server, String configDir) {
        return new OafConfig.McpServerConfig("vendor", server, "1.0.0", configDir, true);
    }

    private void writeConfigYaml(String server, String content) throws Exception {
        var dir = tempDir.resolve(server);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yaml"), content);
    }

    @Test
    void shouldBuildSseClient() throws Exception {
        writeConfigYaml("weather", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        var wrapper = registrar.buildClient(mcp("weather", "weather"));
        assertNotNull(wrapper);
        assertEquals("weather", wrapper.getName());
        assertFalse(wrapper.isInitialized()); // 构建不连接
    }

    @Test
    void shouldBuildStdioClient() throws Exception {
        writeConfigYaml("local-py", """
            connection:
              type: stdio
              command: python
              args: ["mcp_server.py"]
            """);

        var wrapper = registrar.buildClient(mcp("local-py", "local-py"));
        assertNotNull(wrapper);
        assertEquals("local-py", wrapper.getName());
    }

    @Test
    void shouldBuildStreamableHttpClient() throws Exception {
        writeConfigYaml("http-api", """
            connection:
              type: streamableHttp
              url: https://api.example.com/mcp
            """);

        var wrapper = registrar.buildClient(mcp("http-api", "http-api"));
        assertNotNull(wrapper);
        assertEquals("http-api", wrapper.getName());
    }

    @Test
    void shouldBuildClientWithAuthEnvToken() throws Exception {
        writeConfigYaml("auth-mcp", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            auth:
              type: bearer
              token: ${TEST_MCP_TOKEN}
            """);

        // 环境变量未设置时替换为空字符串，构建不抛异常
        var wrapper = registrar.buildClient(mcp("auth-mcp", "auth-mcp"));
        assertNotNull(wrapper);
    }

    @Test
    void shouldBuildClientWithPlainToken() throws Exception {
        writeConfigYaml("auth-plain", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            auth:
              type: bearer
              token: static-token-123
            """);

        var wrapper = registrar.buildClient(mcp("auth-plain", "auth-plain"));
        assertNotNull(wrapper);
    }

    @Test
    void shouldReturnNullWhenConfigYamlMissing() {
        var wrapper = registrar.buildClient(mcp("missing", "missing"));
        assertNull(wrapper);
    }

    // ===== MCP Apps: McpResourceProxy 独立同步 client 构建 =====

    @Test
    void shouldBuildSyncClientForStreamableHttp() throws Exception {
        writeConfigYaml("sync-http", """
            connection:
              type: streamableHttp
              url: http://localhost:8811/mcp
            """);

        var client = registrar.buildSyncClient(mcp("sync-http", "sync-http"));
        assertNotNull(client);
        assertFalse(client.isInitialized()); // 构建不连接（懒连接由代理控制）
    }

    @Test
    void shouldBuildSyncClientForSse() throws Exception {
        writeConfigYaml("sync-sse", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        var client = registrar.buildSyncClient(mcp("sync-sse", "sync-sse"));
        assertNotNull(client);
        assertFalse(client.isInitialized());
    }

    @Test
    void shouldBuildSyncClientForStdio() throws Exception {
        writeConfigYaml("sync-stdio", """
            connection:
              type: stdio
              command: python
              args: ["mcp_server.py"]
            """);

        var client = registrar.buildSyncClient(mcp("sync-stdio", "sync-stdio"));
        assertNotNull(client);
    }

    @Test
    void shouldBuildSyncClientWithAuthToken() throws Exception {
        writeConfigYaml("sync-auth", """
            connection:
              type: streamableHttp
              url: http://localhost:8811/mcp
            auth:
              type: bearer
              token: ${TEST_MCP_TOKEN}
            """);

        var client = registrar.buildSyncClient(mcp("sync-auth", "sync-auth"));
        assertNotNull(client);
    }

    @Test
    void shouldReturnNullSyncClientWhenConfigMissing() {
        assertNull(registrar.buildSyncClient(mcp("sync-missing", "sync-missing")));
    }

    @Test
    void shouldReturnNullWhenConnectionSectionMissing() throws Exception {
        writeConfigYaml("no-conn", """
            vendor: block
            server: no-conn
            """);

        assertNull(registrar.buildClient(mcp("no-conn", "no-conn")));
    }

    @Test
    void shouldFallbackToServerDirWhenConfigDirNotExists() throws Exception {
        writeConfigYaml("fallback-server", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        // configDir 指向不存在的目录，回退到 server 名目录
        var wrapper = registrar.buildClient(mcp("fallback-server", "nonexistent-dir"));
        assertNotNull(wrapper);
        assertEquals("fallback-server", wrapper.getName());
    }

    @Test
    void shouldDetectReadOnlyPermission() throws Exception {
        writeConfigYaml("ro-server", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            permissions:
              read_only: true
            """);

        assertTrue(registrar.isReadOnlyConfigured(mcp("ro-server", "ro-server")));
    }

    @Test
    void shouldNotReadOnlyWhenPermissionAbsent() throws Exception {
        writeConfigYaml("rw-server", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        assertFalse(registrar.isReadOnlyConfigured(mcp("rw-server", "rw-server")));
    }

    @Test
    void shouldNotReadOnlyWhenPermissionFalse() throws Exception {
        writeConfigYaml("explicit-rw", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            permissions:
              read_only: false
            """);

        assertFalse(registrar.isReadOnlyConfigured(mcp("explicit-rw", "explicit-rw")));
    }

    private void writeActiveMcp(String server, String content) throws Exception {
        var dir = tempDir.resolve(server);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("ActiveMCP.json"), content);
    }

    @Test
    void shouldLoadActiveMcpConfigWithEnabledFlags() throws Exception {
        writeActiveMcp("finance", """
            {
              "selectedTools": [
                {"name": "get_user_info", "enabled": true},
                {"name": "query_db", "enabled": true},
                {"name": "transfer_money", "enabled": false}
              ]
            }
            """);

        var config = registrar.loadActiveMcpConfig(mcp("finance", "finance"));

        assertNotNull(config);
        assertEquals(3, config.size());
        assertTrue(config.get("get_user_info"));
        assertTrue(config.get("query_db"));
        assertFalse(config.get("transfer_money"));
    }

    @Test
    void shouldReturnNullWhenActiveMcpMissing() throws Exception {
        writeConfigYaml("plain", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        var config = registrar.loadActiveMcpConfig(mcp("plain", "plain"));
        assertNull(config);
    }

    @Test
    void shouldReturnNullWhenActiveMcpCorrupt() throws Exception {
        writeActiveMcp("corrupt", "{invalid json!!");

        var config = registrar.loadActiveMcpConfig(mcp("corrupt", "corrupt"));
        assertNull(config);
    }

    @Test
    void shouldDefaultEnabledTrueWhenFieldAbsent() throws Exception {
        writeActiveMcp("implicit-enabled", """
            {
              "selectedTools": [
                {"name": "get_weather"},
                {"name": "query_db", "enabled": false}
              ]
            }
            """);

        var config = registrar.loadActiveMcpConfig(mcp("implicit-enabled", "implicit-enabled"));

        assertNotNull(config);
        assertTrue(config.get("get_weather"));   // 无 enabled 字段默认 true
        assertFalse(config.get("query_db"));
    }

    @Test
    void shouldLoadToolPermissions() throws Exception {
        writeConfigYaml("perm-server", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            permissions:
              read_only: false
              tools:
                list_directory: allow
                write_file: ask
                delete_file: deny
            """);

        var perms = registrar.loadToolPermissions(mcp("perm-server", "perm-server"));

        assertEquals(3, perms.size());
        assertEquals("allow", perms.get("list_directory"));
        assertEquals("ask", perms.get("write_file"));
        assertEquals("deny", perms.get("delete_file"));
    }

    @Test
    void shouldReturnEmptyWhenPermissionsAbsent() throws Exception {
        writeConfigYaml("no-perm", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        assertTrue(registrar.loadToolPermissions(mcp("no-perm", "no-perm")).isEmpty());
    }

    @Test
    void shouldIgnoreUnknownBehavior() throws Exception {
        writeConfigYaml("bad-perm", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            permissions:
              tools:
                read_file: allow
                write_file: ban
            """);

        var perms = registrar.loadToolPermissions(mcp("bad-perm", "bad-perm"));

        assertEquals(1, perms.size());
        assertEquals("allow", perms.get("read_file"));
        assertFalse(perms.containsKey("write_file"));
    }

    @Test
    void shouldCollectPermissionRulesFromRegisteredTools() throws Exception {
        // weather: get_weather=ask, update_weather=allow, delete_weather=deny（delete 未注册 → 忽略）
        writeConfigYaml("weather", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            permissions:
              tools:
                get_weather: ask
                update_weather: allow
                delete_weather: deny
            """);
        writeConfigYaml("finance", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        seedRegisteredTools("weather", "get_weather", "update_weather");
        seedRegisteredTools("finance", "query_db");

        var oaf = mock(OafConfig.class);
        when(oaf.runtimeConfig())
            .thenReturn(new OafConfig.RuntimeConfig(0.7, 4096, false, "dont_ask"));
        when(oaf.mcpServers())
            .thenReturn(List.of(mcp("weather", "weather"), mcp("finance", "finance")));

        var result = registrar.collectPermissionRules(oaf);

        assertEquals(PermissionMode.DONT_ASK, result.mode());
        // delete_weather 未注册被忽略；finance 无声明不产生规则
        assertEquals(Map.of("get_weather", "ask", "update_weather", "allow"), result.tools());
        assertEquals(Set.of("get_weather", "update_weather", "query_db"), result.mcpNames());
    }

    @Test
    void shouldFallbackToDefaultModeWhenInvalid() throws Exception {
        var oaf = mock(OafConfig.class);
        when(oaf.runtimeConfig())
            .thenReturn(new OafConfig.RuntimeConfig(0.7, 4096, false, "banana"));
        when(oaf.mcpServers()).thenReturn(List.of());

        assertEquals(PermissionMode.DEFAULT, registrar.collectPermissionRules(oaf).mode());
    }

    // ===== MCP Apps: ui 静态声明解析 =====

    @Test
    void shouldLoadUiMappingWithToolsAppOnlyAndCsp() throws Exception {
        writeConfigYaml("weather", """
            connection:
              type: streamableHttp
              url: http://localhost:8811/mcp
            ui:
              tools:
                get_weather: "ui://weather/mcp-app.html"
                get_forecast: "ui://weather/forecast.html"
              app_only:
                refresh_dashboard: "ui://weather/mcp-app.html"
              csp:
                connect_domains: ["https://api.weather.com"]
                resource_domains: ["https://cdn.jsdelivr.net"]
            """);

        var mapping = registrar.loadUiMapping(mcp("weather", "weather"));

        assertEquals(2, mapping.tools().size());
        assertEquals("ui://weather/mcp-app.html", mapping.tools().get("get_weather"));
        assertEquals("ui://weather/forecast.html", mapping.tools().get("get_forecast"));
        assertEquals(1, mapping.appOnly().size());
        assertEquals("ui://weather/mcp-app.html", mapping.appOnly().get("refresh_dashboard"));
        assertEquals(List.of("https://api.weather.com"), mapping.csp().connectDomains());
        assertEquals(List.of("https://cdn.jsdelivr.net"), mapping.csp().resourceDomains());
    }

    @Test
    void shouldReturnEmptyUiMappingWhenUiSectionAbsent() throws Exception {
        writeConfigYaml("plain", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        var mapping = registrar.loadUiMapping(mcp("plain", "plain"));

        assertTrue(mapping.tools().isEmpty());
        assertTrue(mapping.appOnly().isEmpty());
        assertEquals(List.of(), mapping.csp().connectDomains());
    }

    @Test
    void shouldIgnoreNonUiSchemeEntries() throws Exception {
        writeConfigYaml("bad-uri", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            ui:
              tools:
                get_weather: "ui://weather/mcp-app.html"
                bad_tool: "http://evil.example.com/x.html"
            """);

        var mapping = registrar.loadUiMapping(mcp("bad-uri", "bad-uri"));

        assertEquals(1, mapping.tools().size());
        assertEquals("ui://weather/mcp-app.html", mapping.tools().get("get_weather"));
        assertFalse(mapping.tools().containsKey("bad_tool"));
    }

    // ===== MCP Apps: 自动发现兜底 =====

    @Test
    void shouldDiscoverUiFromToolMetaWhenNotDeclared() throws Exception {
        writeConfigYaml("auto-srv", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        var wrapper = mock(McpClientWrapper.class);
        var metaTool = new McpSchema.Tool("get_time", "", "desc", null, null, null,
            Map.of("ui", Map.of("resourceUri", "ui://get-time/mcp-app.html")));
        when(wrapper.listTools()).thenReturn(Mono.just(List.of(metaTool)));

        registrar.recordRegisteredTools(wrapper, "auto-srv");

        var info = registrar.getToolsByServer("auto-srv").get(0);
        assertEquals("ui://get-time/mcp-app.html", info.uiResourceUri());
        assertEquals("auto", info.uiSource());
    }

    @Test
    void shouldPreferConfigDeclarationOverAutoDiscovery() throws Exception {
        writeConfigYaml("prefer-config", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            ui:
              tools:
                get_time: "ui://custom/time.html"
            """);

        var wrapper = mock(McpClientWrapper.class);
        var metaTool = new McpSchema.Tool("get_time", "", "desc", null, null, null,
            Map.of("ui", Map.of("resourceUri", "ui://get-time/mcp-app.html")));
        when(wrapper.listTools()).thenReturn(Mono.just(List.of(metaTool)));

        var mapping = registrar.loadUiMapping(mcp("prefer-config", "prefer-config"));
        registrar.recordRegisteredTools(wrapper, "prefer-config", mapping);

        var info = registrar.getToolsByServer("prefer-config").get(0);
        assertEquals("ui://custom/time.html", info.uiResourceUri());
        assertEquals("config", info.uiSource());
    }

    @Test
    void shouldNotDiscoverWhenMetaHasNoUi() throws Exception {
        writeConfigYaml("no-ui", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            """);

        var wrapper = mock(McpClientWrapper.class);
        var plainTool = new McpSchema.Tool("echo", "", "desc", null, null, null, null);
        when(wrapper.listTools()).thenReturn(Mono.just(List.of(plainTool)));

        registrar.recordRegisteredTools(wrapper, "no-ui");

        var info = registrar.getToolsByServer("no-ui").get(0);
        assertNull(info.uiResourceUri());
    }

    // ===== MCP Apps: app_only 工具 =====

    @Test
    void shouldHandleAppOnlyToolsWithUiMapping() throws Exception {
        writeConfigYaml("app-only-srv", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            permissions:
              read_only: true
            ui:
              tools:
                get_weather: "ui://weather/mcp-app.html"
              app_only:
                refresh_dashboard: "ui://weather/mcp-app.html"
            """);

        var wrapper = mock(McpClientWrapper.class);
        var tools = List.of(
            new McpSchema.Tool("get_weather", "", "desc", null, null, null, null),
            new McpSchema.Tool("refresh_dashboard", "", "desc", null, null, null, null),
            new McpSchema.Tool("delete_weather", "", "desc", null, null,
                new McpSchema.ToolAnnotations(null, null, true, null, null, null), null)
        );
        when(wrapper.initialize()).thenReturn(Mono.empty());
        when(wrapper.listTools()).thenReturn(Mono.just(tools));

        var toolkit = mock(Toolkit.class);
        var mapping = registrar.loadUiMapping(mcp("app-only-srv", "app-only-srv"));
        registrar.registerReadOnlyForTest(toolkit, wrapper, "app-only-srv", null, mapping);

        // app_only 工具不注册 Toolkit，但记录 ToolInfo（含 uiResourceUri + appOnly 标记）
        var registered = registrar.getToolsByServer("app-only-srv");
        assertEquals(3, registered.size());
        assertTrue(registrar.isAppOnly("app-only-srv", "refresh_dashboard"));
        assertEquals("ui://weather/mcp-app.html",
            registered.stream().filter(t -> t.name().equals("refresh_dashboard")).findFirst().get().uiResourceUri());
        // read_only server + destructive 工具 → proxy 应拒绝
        assertTrue(registrar.isServerReadOnly("app-only-srv"));
        assertTrue(registrar.isDestructiveHint("app-only-srv", "delete_weather"));
        // 仅 get_weather 与 delete_weather 注册进 Toolkit，app_only 的 refresh_dashboard 不注册
        var captor = org.mockito.ArgumentCaptor.forClass(io.agentscope.core.tool.AgentTool.class);
        org.mockito.Mockito.verify(toolkit, org.mockito.Mockito.times(2))
            .registerTool(captor.capture());
        var registeredNames = captor.getAllValues().stream()
            .map(io.agentscope.core.tool.AgentTool::getName).toList();
        assertTrue(registeredNames.contains("get_weather"));
        assertTrue(registeredNames.contains("delete_weather"));
        assertFalse(registeredNames.contains("refresh_dashboard"));
    }

    // ===== MCP Apps: resolveUiRef 裸名歧义 =====

    @Test
    void shouldResolveUiRefWhenUniqueUriAcrossServers() throws Exception {
        writeConfigYaml("srv-a", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            ui:
              tools:
                get_weather: "ui://weather/mcp-app.html"
            """);
        writeConfigYaml("srv-b", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            ui:
              tools:
                get_weather: "ui://weather/mcp-app.html"
            """);

        seedRegisteredToolsWithUi("srv-a", "get_weather", "ui://weather/mcp-app.html");
        seedRegisteredToolsWithUi("srv-b", "get_weather", "ui://weather/mcp-app.html");

        var ref = registrar.resolveUiRef("get_weather");

        assertNotNull(ref);
        assertEquals("ui://weather/mcp-app.html", ref.resourceUri());
    }

    @Test
    void shouldReturnNullOnConflictingUiUris() throws Exception {
        writeConfigYaml("srv-c", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            ui:
              tools:
                get_weather: "ui://weather/mcp-app.html"
            """);
        writeConfigYaml("srv-d", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            ui:
              tools:
                get_weather: "ui://stocks/mcp-app.html"
            """);

        seedRegisteredToolsWithUi("srv-c", "get_weather", "ui://weather/mcp-app.html");
        seedRegisteredToolsWithUi("srv-d", "get_weather", "ui://stocks/mcp-app.html");

        assertNull(registrar.resolveUiRef("get_weather"));
    }

    // ===== MCP Apps: /tools 与 proxy 用到的查询 =====

    @Test
    void shouldReturnDeclaredUiResourceUris() throws Exception {
        writeConfigYaml("weather", """
            connection:
              type: sse
              url: http://localhost:8811/sse
            ui:
              tools:
                get_weather: "ui://weather/mcp-app.html"
            """);

        seedRegisteredToolsWithUi("weather", "get_weather", "ui://weather/mcp-app.html");

        var uris = registrar.getUiResourceUris("weather");

        assertEquals(Set.of("ui://weather/mcp-app.html"), uris);
    }

    /** 预置带 ui 元数据的工具缓存 */
    private void seedRegisteredToolsWithUi(String server, String name, String uri) {
        var wrapper = mock(McpClientWrapper.class);
        when(wrapper.listTools()).thenReturn(Mono.just(List.of(
            new McpSchema.Tool(name, "", "desc", null, null, null, null))));
        var mapping = new McpToolRegistrar.UiMapping(Map.of(name, uri), Map.of(), McpToolRegistrar.UiCsp.empty());
        registrar.recordRegisteredTools(wrapper, server, mapping);
    }

    /** 预置已注册工具缓存（模拟 registerAll 后的注册结果） */
    private void seedRegisteredTools(String server, String... names) {
        var wrapper = mock(McpClientWrapper.class);
        var tools = java.util.Arrays.stream(names)
            .map(n -> new McpSchema.Tool(n, "", "desc", null, null, null, null))
            .toList();
        when(wrapper.listTools()).thenReturn(Mono.just(tools));
        registrar.recordRegisteredTools(wrapper, server);
    }
}
