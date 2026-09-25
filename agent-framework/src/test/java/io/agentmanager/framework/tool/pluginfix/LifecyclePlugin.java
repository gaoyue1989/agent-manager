package io.agentmanager.framework.tool.pluginfix;

import java.util.List;

import io.agentmanager.framework.tool.ToolPlugin;
import io.agentscope.core.tool.Tool;

/**
 * 测试夹具：close() 生命周期回调验证（静态标志位跨类加载器实例回传）。
 */
public class LifecyclePlugin implements ToolPlugin {

    public static volatile boolean closed = false;

    @Override
    public List<io.agentmanager.framework.tool.CustomTool> tools() {
        return List.of(this);
    }

    @Override
    public void close() {
        closed = true;
    }

    @Tool(name = "lifecycle_probe", description = "生命周期探针（插件冒烟测试用）", readOnly = true)
    public String probe() {
        return "ok";
    }
}
