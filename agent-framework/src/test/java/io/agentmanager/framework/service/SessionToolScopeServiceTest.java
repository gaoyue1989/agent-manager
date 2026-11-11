package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.service.McpToolRegistrar.ToolInfo;

/**
 * {@link SessionToolScopeService} 单测（session-tool-list-design §2.1/§4.1）：
 * 三分类名单解析——注册名精确（MCP=mcp__{server}__{tool}/自定义）、远端裸名别名反查
 * （同名工具跨 server 全保留）、内置名 WARN 不生效、未知名忽略；app_only 不可解析；
 * 空白/重复归一。三个数据源方法以匿名子类打桩（构造器依赖置 null，覆写后不触碰）。
 */
class SessionToolScopeServiceTest {

    /** 打桩：mcp/custom 集与裸名→注册名映射由用例直接给定 */
    private SessionToolScopeService scope(Set<String> mcp, Set<String> custom,
                                          Map<String, List<String>> wireAliases) {
        return new SessionToolScopeService(null, null) {
            @Override
            public Set<String> mcpToolNames() {
                return mcp;
            }

            @Override
            public Set<String> customToolNames() {
                return custom;
            }

            @Override
            protected List<String> byWireName(String wireName) {
                return wireAliases.getOrDefault(wireName, List.of());
            }
        };
    }

    @Test
    void registeredNamesShouldResolveDirectly() {
        var s = scope(Set.of("mcp__db__query", "mcp__db__search"), Set.of("get_order"), Map.of());
        var r = s.resolve(List.of("mcp__db__query", "get_order"));
        assertEquals(Set.of("mcp__db__query", "get_order"), r.keepNames());
    }

    @Test
    void wireNameAliasShouldResolveToRegisteredNames() {
        var s = scope(Set.of("mcp__db__query"), Set.of(),
            Map.of("query", List.of("mcp__db__query")));
        var r = s.resolve(List.of("query"));
        assertEquals(Set.of("mcp__db__query"), r.keepNames(), "裸名别名必须归一为注册名");
    }

    @Test
    void wireNameAliasKeepsAllServersWithSameToolName() {
        var s = scope(Set.of("mcp__db__query", "mcp__dw__query"), Set.of(),
            Map.of("query", List.of("mcp__db__query", "mcp__dw__query")));
        var r = s.resolve(List.of("query"));
        assertEquals(Set.of("mcp__db__query", "mcp__dw__query"), r.keepNames(),
            "同名工具跨 server：调用方无法区分，别名取并集");
    }

    @Test
    void registeredNameShouldWinOverWireAliasLookup() {
        // 注册名精确匹配优先：即使裸名别名也能解析，注册名命中先行
        var s = scope(Set.of("mcp__db__query"), Set.of(),
            Map.of("query", List.of("mcp__db__query")));
        assertEquals(Set.of("mcp__db__query"), s.resolve(List.of("mcp__db__query")).keepNames());
    }

    @Test
    void unknownAndBuiltinNamesShouldBeIgnored() {
        var s = scope(Set.of("mcp__db__query"), Set.of("get_order"), Map.of());
        var r = s.resolve(List.of("read_file", "no_such_tool", "mcp__db__query"));
        assertEquals(Set.of("mcp__db__query"), r.keepNames(),
            "内置名（read_file）与未知名都不进 keepNames（内置恒可见、未知名忽略）");
    }

    @Test
    void nullBlankAndDuplicatesShouldNormalize() {
        var s = scope(Set.of("mcp__db__query", "mcp__db__search"), Set.of(), Map.of());
        var r = s.resolve(java.util.Arrays.asList(" mcp__db__query ", null, "", "mcp__db__query", "mcp__db__search"));
        assertEquals(Set.of("mcp__db__query", "mcp__db__search"), r.keepNames());
    }

    @Test
    void unknownWireNameShouldBeInvalid() {
        var s = scope(Set.of("mcp__db__query"), Set.of(), Map.of());
        var r = s.resolve(List.of("no_such_wire_name", "mcp__db__query"));
        assertEquals(Set.of("mcp__db__query"), r.keepNames(), "未注册裸名不解析（自然无效）");
    }

    @Test
    void emptyRequestedShouldYieldEmptyKeepSet() {
        var s = scope(Set.of("mcp__db__query"), Set.of("get_order"), Map.of());
        var r = s.resolve(List.of());
        assertTrue(r.keepNames().isEmpty(), "显式 [] → 空 keepNames（= 仅内置）");
    }

    @Test
    void wireAliasBridgeShouldReturnEmptyForNullOrBlank() {
        var registrar = new McpToolRegistrarTestBridge();
        assertTrue(registrar.registeredNamesForWireName(null).isEmpty());
        assertTrue(registrar.registeredNamesForWireName("  ").isEmpty());
        assertTrue(registrar.registeredNamesForWireName("mcp__none__tool").isEmpty());
    }

    /** 真实 McpToolRegistrar 桥接：registeredNamesForWireName 空值/未注册防御（不触碰任何注册状态） */
    private static final class McpToolRegistrarTestBridge extends McpToolRegistrar {
        McpToolRegistrarTestBridge() {
            super(new AgentManagerProperties(
                new AgentManagerProperties.LLMConfig("k", "m", "http://localhost", "openai", 0.7, 4096, 120, true, 0, "", null),
                new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
                new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "u", "p", "db"),
                "/test", "",
                new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
                new AgentManagerProperties.FileConfig(true, 20, 20, "image/*", 5, 15, 50, true, 7, "local", "/data/files", "", "", "", "agent-files", ""),
                new AgentManagerProperties.SseConfig(20, 5, 256, 300),
                AgentManagerProperties.HarnessConfig.defaults()));
        }
    }

    @Test
    void appOnlyToolShouldNotLeakIntoKeepSet_viaRealRegistrar() {
        // 真实 registrar 链路：app_only 工具登记了 ToolInfo，但注册名集与裸名别名都必须排除
        var registrar = new McpToolRegistrar(props());
        registrar.putToolInfoForTest(new ToolInfo("card_ui", "mcp__ui__card_ui", "d", "ui",
            "ui://x", "config", true));
        registrar.putToolInfoForTest(new ToolInfo("query", "mcp__db__query", "d", "db"));

        var s = new SessionToolScopeService(registrar, null) {
            @Override
            public Set<String> customToolNames() {
                return Set.of();
            }
        };

        // getSessionControllableToolNames 不含 app_only
        assertEquals(Set.of("mcp__db__query"), registrar.getSessionControllableToolNames());
        // 注册名精确解析 app_only 被排除（不在 mcp 集）；裸名别名同样排除（appOnly 过滤）
        var r = s.resolve(List.of("mcp__ui__card_ui", "card_ui", "mcp__db__query"));
        assertEquals(Set.of("mcp__db__query"), r.keepNames());
        assertTrue(registrar.getSessionControllableToolNames().contains("mcp__db__query"));
        assertFalse(registrar.getSessionControllableToolNames().contains("mcp__ui__card_ui"));
    }

    private AgentManagerProperties props() {
        return new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("k", "m", "http://localhost", "openai", 0.7, 4096, 120, true, 0, "", null),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "u", "p", "db"),
            "/test", "",
            new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20, "image/*", 5, 15, 50, true, 7, "local", "/data/files", "", "", "", "agent-files", ""),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            AgentManagerProperties.HarnessConfig.defaults());
    }
}
