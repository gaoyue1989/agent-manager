package io.agentmanager.framework.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.tool.Tool;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentmanager.framework.config.OafConfigHolder;

/**
 * 内置（自定义 @Tool）工具运行时注册表——/tools includeInternal 中 CustomTool 段的事实源（issue #28）。
 *
 * <p>口径（issue #39 拆字段后收窄）：本类只覆盖 {@code List<CustomTool>} 体系的自定义工具
 * （含插件 SPI 并入的实例）；SDK（Harness 框架）构建时自注册进 Toolkit 的内置工具
 * （文件/记忆/会话/计划/技能/子 Agent/异步任务/Shell）不在 toolBeans 里，经
 * {@link #listSdkInternalTools} 从运行中 agent 的实际注册集单独透出（/tools 的 sdkInternal 段），
 * 两段并列、调用方按需取用。
 *
 * <p>背景：此前 {@code ToolController} 的 includeInternal 分支返回 {@code oafConfig.tools()}
 * 纯声明视图，而运行时真实注册集由 {@code @Tool} bean 决定（{@code deniedTools} 类粒度剔除，
 * 见 AgentScopeConfig），两者互不校验导致清单与能力失真（release-agent 实测：声明空但
 * echo/present_url 实际可调）。
 *
 * <p>注册语义与 AgentScopeConfig 装配一致：遍历自定义工具 bean 的公开方法，提取
 * {@code @Tool} 注解名（注解 name 空白时用方法名）；任一方法名命中 {@code deniedTools}
 * 则整个 bean 跳过（类粒度）。配置经 {@link OafConfigHolder} 每次 {@link #listInternalTools()}
 * 时取最新值——OAF reload（deniedTools/tools 变更）即时反映，与动态加载语义对齐。
 */
public class InternalToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(InternalToolRegistry.class);

    private final OafConfigHolder oafConfigHolder;
    private final List<Object> toolBeans;

    public InternalToolRegistry(OafConfigHolder oafConfigHolder, List<?> toolBeans) {
        this.oafConfigHolder = oafConfigHolder;
        this.toolBeans = List.copyOf(toolBeans);
        log.info("InternalToolRegistry: {} builtin tool beans (names resolved lazily per request)",
                this.toolBeans.size());
    }

    /** /tools?includeInternal=true 的条目列表：以运行时注册集为准，附 declared 标注声明意图 */
    public List<Map<String, Object>> listInternalTools() {
        var oaf = oafConfigHolder.get();
        var declared = oaf != null && oaf.tools() != null ? Set.copyOf(oaf.tools()) : Set.<String>of();
        return runtimeNames(oaf).stream()
            .sorted()
            .map(name -> Map.<String, Object>of(
                "name", name,
                "category", "internal",
                "source", "builtin",
                "declared", declared.contains(name)))
            .toList();
    }

    /** 运行时真实注册的内置工具名（每次计算：deniedTools 随 OAF reload 变化） */
    private Set<String> runtimeNames(io.agentmanager.framework.model.OafConfig oaf) {
        var excluded = oaf != null && oaf.hasDeniedTools() ? oaf.deniedTools() : null;
        var names = new java.util.LinkedHashSet<String>();
        for (var tool : toolBeans) {
            var beanNames = extractToolNames(tool);
            if (excluded != null && beanNames.stream().anyMatch(excluded::contains)) {
                log.debug("InternalToolRegistry: tool bean {} excluded by deniedTools ({})",
                        tool.getClass().getSimpleName(), beanNames);
                continue;
            }
            names.addAll(beanNames);
        }
        return Set.copyOf(names);
    }

    /**
     * /tools?includeInternal=true 的 sdkInternal 段落（issue #39）：SDK（Harness 框架）在
     * HarnessAgent 构建时注册进 Toolkit 的内置工具运行时注册集——文件/记忆/会话/计划/技能/
     * 子 Agent/异步任务/Shell 等。这批工具不在 {@code List<CustomTool>} 体系内，无法从
     * toolBeans 反射得出，只能取自运行中 agent 的 {@code getToolkit().getToolNames()}
     * （与 HarnessAgentFactory#verifyToolCoverage 同源的实际注册集），不落
     * BUILT_IN_TOOL_NAMES 静态白名单——SDK feature 开关（如 memoryEnabled、plan mode）
     * 会使静态名产生幽灵条目。
     *
     * <p>excludeNames：本次响应已上报的工具名（MCP 裸名 + 自定义 @Tool 名）——它们与 SDK
     * 内置工具注册进同一 Toolkit，需先剔除，剩余的才是 SDK 内置；当前 OAF 的 deniedTools
     * 一并剔除（harness 侧 tools.json deny 隐藏 + 口径对齐「可调用」语义）。
     *
     * <p>fail-soft：agent 未就绪（null）或枚举异常时返回空列表，不阻断 /tools。
     */
    public List<Map<String, Object>> listSdkInternalTools(HarnessAgent agent, Set<String> excludeNames) {
        if (agent == null) {
            return List.of();
        }
        try {
            var excluded = new java.util.HashSet<>(excludeNames);
            var oaf = oafConfigHolder.get();
            if (oaf != null && oaf.hasDeniedTools()) {
                excluded.addAll(oaf.deniedTools());
            }
            return agent.getToolkit().getToolNames().stream()
                .filter(name -> !excluded.contains(name))
                .sorted()
                .map(name -> Map.<String, Object>of(
                    "name", name,
                    "category", "sdk",
                    "source", "sdk"))
                .toList();
        } catch (Exception e) {
            log.warn("InternalToolRegistry: sdkInternal enumeration failed, returning empty: {}", e.getMessage());
            return List.of();
        }
    }

    /** 包内测试辅助：当前运行时注册名集合 */
    Set<String> runtimeNamesForTest() {
        return runtimeNames(oafConfigHolder.get());
    }

    /** 提取 @Tool 注解名（与 AgentScopeConfig#toolToolNames 同一语义） */
    public static Set<String> extractToolNames(Object tool) {
        var names = new java.util.LinkedHashSet<String>();
        for (var method : tool.getClass().getMethods()) {
            var ann = method.getAnnotation(Tool.class);
            if (ann != null) {
                names.add(ann.name().isBlank() ? method.getName() : ann.name());
            }
        }
        return names;
    }
}
