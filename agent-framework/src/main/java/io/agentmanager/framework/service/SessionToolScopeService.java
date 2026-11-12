package io.agentmanager.framework.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.tool.CustomTool;

/**
 * 会话级工具三分类与名单解析（session-tool-list-design §4.1）：
 * {@link ToolFilterMiddleware}（模型侧收敛）与 {@code ToolController /tools} 查询接口
 * （{@code sessionTools} 汇总、{@code ?tools=} 过滤）共用——三处口径同源，
 * 结构上消除漂移（设计 R7）。
 *
 * <p>分类口径（作用域分层，§0：会话级接口只作用于 MCP + 自定义，内置恒可见）：
 * <ul>
 *   <li><b>MCP</b>：{@link McpToolRegistrar} 登记集（非 app_only；注册名 = mcp__{server}__{tool}
 *       模型侧可见名，两条注册路径同一登记点）；</li>
 *   <li><b>自定义</b>：{@link InternalToolRegistry} 运行时注册集（与 HarnessAgentFactory
 *       同一批 {@link CustomTool} bean，deniedTools 剔除同语义）；</li>
 *   <li><b>内置</b>：其余——SDK 升级漂移新增名默认归内置（不受会话级 tools 影响，
 *       漂移方向安全：宁可不收窄，不误伤框架能力）。</li>
 * </ul>
 *
 * <p>名单解析（§2.1 两级别名）：注册名（mcp__{server}__{tool}）精确匹配（主口径）优先；
 * 未命中再按远端裸名反查（同名工具跨 server 全部保留）；内置名 WARN 提示
 * 「不受会话级控制」；未知名静默忽略（与 skills 白名单同语义）。
 */
@Service
public class SessionToolScopeService {

    private static final Logger log = LoggerFactory.getLogger(SessionToolScopeService.class);

    private final McpToolRegistrar mcpToolRegistrar;
    private final InternalToolRegistry internalToolRegistry;

    public SessionToolScopeService(McpToolRegistrar mcpToolRegistrar,
                                   InternalToolRegistry internalToolRegistry) {
        this.mcpToolRegistrar = mcpToolRegistrar;
        this.internalToolRegistry = internalToolRegistry;
    }

    /** MCP 可控注册名集（非 app_only；reload 即时反映）。public：ToolController sessionTools 汇总同用 */
    public Set<String> mcpToolNames() {
        return mcpToolRegistrar.getSessionControllableToolNames();
    }

    /** 自定义工具运行时名集（deniedTools 剔除后；reload 即时反映）。public：同上 */
    public Set<String> customToolNames() {
        return internalToolRegistry.runtimeCustomToolNames();
    }

    /** 远端裸名反查注册名集（mcp__{server}__{tool}）；未注册返回空集 */
    protected List<String> byWireName(String wireName) {
        return mcpToolRegistrar.registeredNamesForWireName(wireName);
    }

    /**
     * 解析本轮 {@code tools} 名单 → 应保留的已注册名集合（注册名形态，两级别名已归一）。
     * 名单元素允许 null/空白（跳过）；名单本身 null 视为空名单（Resolution 空集）。
     * 解析纯内存操作，无校验报错（未知名忽略）。
     */
    public Resolution resolve(List<String> requested) {
        if (requested == null) {
            return new Resolution(Set.of());
        }
        var mcp = mcpToolNames();
        var custom = customToolNames();
        var keep = new LinkedHashSet<String>();
        for (var raw : requested) {
            var name = raw == null ? "" : raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (custom.contains(name) || mcp.contains(name)) {
                keep.add(name);   // 注册名精确匹配（主口径）
                continue;
            }
            var viaWireName = byWireName(name);
            if (!viaWireName.isEmpty()) {
                keep.addAll(viaWireName);   // 远端裸名别名（同名工具跨 server 全保留）
                continue;
            }
            if (HarnessAgentFactory.BUILT_IN_TOOL_NAMES.contains(name)) {
                // 内置工具恒可见、不受会话级 tools 控制（启停走包级 deniedTools）。
                // 显式 WARN 而非静默忽略：否则 tools:["read_file"] 会让调用方误以为
                // 收敛到了单一工具（实际效果是 MCP/自定义全剔除）
                log.warn("[tool-scope] built-in tool '{}' is not session-controllable "
                    + "(always visible; disable via package deniedTools instead)", name);
            }
            // 其余未知名静默忽略（与 skills 白名单同语义）
        }
        return new Resolution(Set.copyOf(keep));
    }

    /** 名单解析结果：应保留的已注册名集（空集 = 显式 [] → 仅内置） */
    public record Resolution(Set<String> keepNames) {
    }
}
