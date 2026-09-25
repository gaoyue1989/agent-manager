package io.agentmanager.framework.tool;

import java.util.List;
import java.util.Map;

/**
 * 自定义工具插件 SPI：工具代码以独立 jar 放入插件目录（环境变量 {@code AGENT_PLUGINS_DIR}，
 * 缺省 {@code {AGENT_CONFIG_DIR}/plugins}），由 {@link ToolPluginBootstrapper} 在 Spring
 * 上下文刷新前扫描加载，工具实例注册为单例并入 {@code List<CustomTool>} 注入源——
 * HarnessAgentFactory（启动装配与 OAF reload 整包重建）、InternalToolRegistry（/tools）、
 * HITL 权限白名单三处消费方零改动共享。
 *
 * <p>插件 jar 通过 {@code META-INF/services/io.agentmanager.framework.tool.ToolPlugin}
 * 注册实现类（每行一个全限定类名）。
 *
 * <p>打包约束（硬性）：agent-framework 与 agentscope-core 依赖均为 provided，
 * 框架类必须留给应用类加载器解析——插件 jar 打包这些类会导致 {@code instanceof CustomTool}
 * 失效、注入不匹配。插件自带第三方依赖须 shade 进 jar。设计详见
 * docs/tool-plugin-extension-plan.md。
 */
public interface ToolPlugin extends CustomTool {

    /** 插件标识（默认实现类简单名；用于日志与单例命名） */
    default String id() {
        return getClass().getSimpleName();
    }

    /** 工具实例集合：实现本接口的类可直接 {@code List.of(this)}（类内多 @Tool 方法全量收集），独立工具类需 implements CustomTool */
    List<CustomTool> tools();

    /** 插件初始化（可选）：插件目录下 {jar名去后缀}/config.yaml 解析并替换 ${ENV_VAR} 后回调，文件不存在则传空 Map */
    default void configure(Map<String, String> config) {
    }

    /** 生命周期回调（可选）：服务优雅关闭时由 Bootstrapper 统一调用（手工注册的单例不受 Spring 销毁回调管理） */
    default void close() {
    }
}
