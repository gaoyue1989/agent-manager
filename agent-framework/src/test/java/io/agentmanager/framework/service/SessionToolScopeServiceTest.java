package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.service.McpToolRegistrar.ToolInfo;

/**
 * {@link SessionToolScopeService} 单测（session-tool-list-design §2.1/§4.1）：
 * 三分类名单解析——裸名精确（MCP/自定义）、{@code mcp__{server}__{tool}} 全名别名、
 * 内置名 WARN 不生效、未知名忽略；app_only 全名不可解析；空白/重复归一。
 * 三个数据源方法以匿名子类打桩（构造器依赖置 null，覆写后不触碰）。
 */
class SessionToolScopeServiceTest {

    /** 打桩：mcp/custom 集与全名映射由用例直接给定 */
    private SessionToolScopeService scope(Set<String> mcp, Set<String> custom,
                                          Set<String> mcpFullNames) {
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
            protected ToolInfo byFullName(String fullName) {
                return mcpFullNames.contains(fullName)
                    ? new ToolInfo(fullName.replaceFirst("^mcp__[a-z]+__", ""),
                        fullName, "d", fullName.split("__")[1])
                    : null;
            }
        };
    }

    @Test
    void bareNamesShouldResolveDirectly() {
        var s = scope(Set.of("query", "search"), Set.of("get_order"), Set.of());
        var r = s.resolve(List.of("query", "get_order"));
        assertEquals(Set.of("query", "get_order"), r.keepNames());
    }

    @Test
    void fullNameAliasShouldResolveToBareName() {
        var s = scope(Set.of("query"), Set.of(), Set.of("mcp__db__query"));
        var r = s.resolve(List.of("mcp__db__query"));
        assertEquals(Set.of("query"), r.keepNames(), "全名别名必须归一为裸名");
    }

    @Test
    void bareNameShouldWinOverFullNameLookup() {
        // 裸名精确匹配优先：即使全名也能解析，裸名命中先行
        var s = scope(Set.of("query"), Set.of(), Set.of("mcp__db__query"));
        assertEquals(Set.of("query"), s.resolve(List.of("query")).keepNames());
    }

    @Test
    void unknownAndBuiltinNamesShouldBeIgnored() {
        var s = scope(Set.of("query"), Set.of("get_order"), Set.of());
        var r = s.resolve(List.of("read_file", "no_such_tool", "query"));
        assertEquals(Set.of("query"), r.keepNames(),
            "内置名（read_file）与未知名都不进 keepNames（内置恒可见、未知名忽略）");
    }

    @Test
    void nullBlankAndDuplicatesShouldNormalize() {
        var s = scope(Set.of("query", "search"), Set.of(), Set.of());
        var r = s.resolve(java.util.Arrays.asList(" query ", null, "", "query", "search"));
        assertEquals(Set.of("query", "search"), r.keepNames());
    }

    @Test
    void fullNamePointingToUnknownRegistrationShouldBeInvalid() {
        var s = scope(Set.of("query"), Set.of(), Set.of());
        var r = s.resolve(List.of("mcp__other__tool", "query"));
        assertEquals(Set.of("query"), r.keepNames(), "未注册全名不解析（自然无效）");
    }

    @Test
    void emptyRequestedShouldYieldEmptyKeepSet() {
        var s = scope(Set.of("query"), Set.of("get_order"), Set.of());
        var r = s.resolve(List.of());
        assertTrue(r.keepNames().isEmpty(), "显式 [] → 空 keepNames（= 仅内置）");
    }

    @Test
    void byFullNameBridgeShouldReturnNullForNullOrBlank() {
        var registrar = new McpToolRegistrarTestBridge();
        assertNull(registrar.findByDisplayName(null));
        assertNull(registrar.findByDisplayName("  "));
        assertNull(registrar.findByDisplayName("mcp__none__tool"));
    }

    /** 真实 McpToolRegistrar 桥接：findByDisplayName 空值/未注册防御（不触碰任何注册状态） */
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
    void appOnlyFullNameShouldNotLeakIntoKeepSet_viaRealRegistrar() {
        // 真实 registrar 链路：app_only 工具登记了 ToolInfo，但 resolve 全名解析必须排除
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
        assertEquals(Set.of("query"), registrar.getSessionControllableToolNames());
        // 全名解析 app_only 被排除（byFullName 返回 info 但 appOnly → 不进 keepNames）
        var r = s.resolve(List.of("mcp__ui__card_ui", "query"));
        assertEquals(Set.of("query"), r.keepNames());
        assertTrue(registrar.getSessionControllableToolNames().contains("query"));
        assertFalse(registrar.getSessionControllableToolNames().contains("card_ui"));
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
