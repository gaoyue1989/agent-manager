package io.agentmanager.framework.tool.pluginfix;

import java.util.List;
import java.util.Map;

import io.agentmanager.framework.tool.ToolPlugin;
import io.agentscope.core.tool.Tool;

/**
 * 测试夹具：捕获 configure() 注入配置的插件（静态字段跨类加载器实例回传断言值）。
 */
public class ConfigPlugin implements ToolPlugin {

    public static volatile Map<String, String> lastConfig = Map.of();

    @Override
    public List<io.agentmanager.framework.tool.CustomTool> tools() {
        return List.of(this);
    }

    @Override
    public void configure(Map<String, String> config) {
        lastConfig = Map.copyOf(config);
    }

    @Tool(name = "config_probe", description = "配置探针（插件冒烟测试用）", readOnly = true)
    public String probe() {
        return "ok";
    }
}
