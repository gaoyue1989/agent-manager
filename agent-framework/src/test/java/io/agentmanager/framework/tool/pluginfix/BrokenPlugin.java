package io.agentmanager.framework.tool.pluginfix;

import java.util.List;

import io.agentmanager.framework.tool.CustomTool;
import io.agentmanager.framework.tool.ToolPlugin;

/**
 * 测试夹具：构造即抛异常，验证单提供者 fail-soft（跳过它，同 jar 其余提供者正常加载）。
 */
public class BrokenPlugin implements ToolPlugin {

    public BrokenPlugin() {
        throw new IllegalStateException("broken by design");
    }

    @Override
    public List<CustomTool> tools() {
        return List.of();
    }
}
