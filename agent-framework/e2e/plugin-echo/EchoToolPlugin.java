package io.e2e.plugin;

import java.util.List;
import java.util.Map;

import io.agentmanager.framework.tool.CustomTool;
import io.agentmanager.framework.tool.ToolPlugin;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 工具插件部署冒烟示例（tool-plugin-extension-plan §4.3 验证件）：
 * 由 e2e/scripts/plugin-smoke.sh 现场编译打包为 echo-tool.jar（编译期 provided 依赖
 * target/classes + agentscope-core，与真实插件交付形态一致），经 SPI 从插件目录加载。
 *
 * <p>两个工具：echo_query 验证注册与调用链；smoke_config 验证 config.yaml 的
 * ${ENV_VAR} 替换注入（configure 回调捕获，工具返回时回吐）。
 */
public class EchoToolPlugin implements ToolPlugin {

    private volatile String marker = "(unset)";

    @Override
    public String id() {
        return "echo-tool";
    }

    @Override
    public List<CustomTool> tools() {
        return List.of(this);
    }

    @Override
    public void configure(Map<String, String> config) {
        this.marker = config.getOrDefault("marker", "(missing)");
    }

    @Tool(name = "echo_query", description = "回显输入文本（插件冒烟测试用）", readOnly = true)
    public String echo(@ToolParam(name = "text", description = "待回显文本") String text) {
        return "echo:" + text;
    }

    @Tool(name = "smoke_config", description = "回吐插件配置注入的 marker 值（插件冒烟测试用）", readOnly = true)
    public String smokeConfig() {
        return "marker=" + marker;
    }
}
