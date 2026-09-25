package io.agentmanager.framework.tool.pluginfix;

import java.util.List;

import io.agentmanager.framework.tool.ToolPlugin;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 测试夹具：最小工具插件（单类单 @Tool 方法；刻意不用 lambda/内部类，保证单 class 文件可整拷进测试 jar）。
 * 仅由 ToolPluginBootstrapperTest 从测试 jar（子类加载器）加载，不直接被测试代码引用。
 */
public class EchoPlugin implements ToolPlugin {

    @Override
    public List<io.agentmanager.framework.tool.CustomTool> tools() {
        return List.of(this);
    }

    @Tool(name = "echo_query", description = "回显输入文本（插件冒烟测试用）", readOnly = true)
    public String echo(@ToolParam(name = "text", description = "待回显文本") String text) {
        return "echo:" + text;
    }
}
