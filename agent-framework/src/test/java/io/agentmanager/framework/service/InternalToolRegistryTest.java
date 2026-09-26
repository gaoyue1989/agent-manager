package io.agentmanager.framework.service;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.OafConfigHolder;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.tool.BusinessTools;
import io.agentmanager.framework.tool.FileTools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalToolRegistryTest {

    /** OafConfigHolder 桩：返回带指定 tools/deniedTools 的 OafConfig（registry 每请求 get()） */
    private static OafConfigHolder holder(List<String> tools, List<String> denied) {
        var oaf = mock(OafConfig.class);
        when(oaf.tools()).thenReturn(tools);
        when(oaf.deniedTools()).thenReturn(denied);
        when(oaf.hasDeniedTools()).thenReturn(denied != null && !denied.isEmpty());
        var h = mock(OafConfigHolder.class);
        when(h.get()).thenReturn(oaf);
        return h;
    }

    @Test
    void shouldExposeRuntimeRegisteredBuiltinTools() {
        // 真实工具 bean：FileTools(present_file/present_url) + BusinessTools(get_current_time/echo)
        var registry = new InternalToolRegistry(
            holder(List.of(), List.of()), List.of(new BusinessTools(), mock(FileTools.class)));
        var names = registry.runtimeNamesForTest();
        assertTrue(names.containsAll(Set.of("echo", "get_current_time", "present_file", "present_url")),
            "运行时注册集应包含全部内置 @Tool，实际: " + names);
    }

    @Test
    void shouldReturnEmptyViewWhenOafToolsDeclaredEmpty() {
        // issue #28 失真回归：OAF tools:[] 但运行时注册存在 → 清单仍应非空
        var registry = new InternalToolRegistry(
            holder(List.of(), List.of()), List.of(new BusinessTools()));
        var items = registry.listInternalTools();
        assertTrue(items.stream().anyMatch(m -> "echo".equals(m.get("name"))));
        items.forEach(m -> assertFalse((Boolean) m.get("declared"),
            "声明为空时所有内置工具 declared 应为 false"));
    }

    @Test
    void shouldMarkDeclaredWhenOafListsTool() {
        var registry = new InternalToolRegistry(
            holder(List.of("echo"), List.of()), List.of(new BusinessTools()));
        var items = registry.listInternalTools();
        var echo = items.stream().filter(m -> "echo".equals(m.get("name"))).findFirst().orElseThrow();
        assertTrue((Boolean) echo.get("declared"));
        var time = items.stream().filter(m -> "get_current_time".equals(m.get("name"))).findFirst().orElseThrow();
        assertFalse((Boolean) time.get("declared"));
    }

    @Test
    void shouldSkipWholeBeanWhenAnyNameDenied() {
        // 类粒度剔除语义（与 AgentScopeConfig 装配一致）：任一 @Tool 命中 deniedTools 整 bean 跳过
        var registry = new InternalToolRegistry(
            holder(List.of(), List.of("present_file")), List.of(new BusinessTools(), mock(FileTools.class)));
        var names = registry.runtimeNamesForTest();
        assertTrue(names.containsAll(Set.of("echo", "get_current_time")), "未命中的 bean 应保留");
        assertFalse(names.contains("present_file"));
        assertFalse(names.contains("present_url"), "同 bean 的另一工具也应随类剔除");
    }

    @Test
    void shouldReflectOafReload() {
        // 动态 reload 语义：holder.get() 返回新实例后，deniedTools 变更即时反映
        var oaf = mock(OafConfig.class);
        when(oaf.tools()).thenReturn(List.of());
        when(oaf.deniedTools()).thenReturn(List.of());
        when(oaf.hasDeniedTools()).thenReturn(false);
        var h = mock(OafConfigHolder.class);
        when(h.get()).thenReturn(oaf);
        var registry = new InternalToolRegistry(h, List.of(new BusinessTools()));
        assertTrue(registry.listInternalTools().stream().anyMatch(m -> "echo".equals(m.get("name"))));

        var reloaded = mock(OafConfig.class);
        when(reloaded.tools()).thenReturn(List.of());
        when(reloaded.deniedTools()).thenReturn(List.of("echo"));
        when(reloaded.hasDeniedTools()).thenReturn(true);
        when(h.get()).thenReturn(reloaded);
        assertFalse(registry.listInternalTools().stream().anyMatch(m -> "echo".equals(m.get("name"))),
            "reload 后 deniedTools 命中的工具应从清单消失");
    }

    @Test
    void extractToolNamesShouldPreferAnnotationName() {
        Set<String> names = InternalToolRegistry.extractToolNames(new BusinessTools());
        assertTrue(names.contains("get_current_time"));
        assertTrue(names.contains("echo"));
    }

    @Test
    void sdkInternalShouldSubtractReportedAndDeniedNames() {
        // issue #39：SDK 段 = 运行中 agent Toolkit 实际注册集 − 已上报名（MCP/自定义）− deniedTools
        var agent = mock(io.agentscope.harness.agent.HarnessAgent.class);
        var toolkit = mock(io.agentscope.core.tool.Toolkit.class);
        when(agent.getToolkit()).thenReturn(toolkit);
        when(toolkit.getToolNames()).thenReturn(new java.util.LinkedHashSet<>(List.of(
            "read_file", "write_file", "memory_save", "echo", "get_weather", "plan_enter")));

        var registry = new InternalToolRegistry(
            holder(List.of(), List.of("memory_save")), List.of(new BusinessTools()));
        // echo（自定义 @Tool）与 get_weather（MCP 裸名）已上报，memory_save 命中 deniedTools
        var items = registry.listSdkInternalTools(agent, Set.of("echo", "get_weather"));

        var names = items.stream().map(m -> (String) m.get("name")).toList();
        assertEquals(List.of("plan_enter", "read_file", "write_file"), names, "剔除后剩余 SDK 内置且有序");
        items.forEach(m -> assertEquals("sdk", m.get("category")));
    }

    @Test
    void sdkInternalShouldReturnEmptyWhenAgentNotReady() {
        // fail-soft：agent 未就绪（启动早期/测试环境）时返回空段，不抛异常
        var registry = new InternalToolRegistry(holder(List.of(), List.of()), List.of());
        assertTrue(registry.listSdkInternalTools(null, Set.of("echo")).isEmpty());
    }
}
