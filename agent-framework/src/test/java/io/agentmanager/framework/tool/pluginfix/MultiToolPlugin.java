package io.agentmanager.framework.tool.pluginfix;

import java.util.List;

import io.agentmanager.framework.tool.CustomTool;
import io.agentmanager.framework.tool.ToolPlugin;
import io.agentscope.core.tool.Tool;

/**
 * 测试夹具：单插件多工具形态——自身一个 @Tool 方法 + 独立工具类 StandaloneTool 一个，
 * 验证 tools() 多实例注册路径。
 */
public class MultiToolPlugin implements ToolPlugin {

    @Override
    public List<CustomTool> tools() {
        return List.of(this, new StandaloneTool());
    }

    @Tool(name = "alpha_probe", description = "插件本体工具（冒烟测试用）", readOnly = true)
    public String alpha() {
        return "alpha";
    }

    /** 独立工具类：不实现 ToolPlugin，仅实现 CustomTool 标记接口 */
    public static class StandaloneTool implements CustomTool {

        @Tool(name = "beta_probe", description = "插件附属工具（冒烟测试用）", readOnly = true)
        public String beta() {
            return "beta";
        }
    }
}
