package io.agentmanager.framework.tool;

/**
 * 自定义工具标记接口：{@code List<CustomTool>} 注入收集的收窄依据。
 *
 * <p>背景：AgentScopeConfig.customTools 曾以 {@code List<Object>} 聚合 BusinessTools/FileTools，
 * 但 List 泛型注入会把上下文中所有 Object 型 Bean 扫为装配候选（含 OafReloadService 等新服务），
 * 依赖链回环时形成 Spring 循环依赖。引入本标记接口后候选仅限实现类（BusinessTools/FileTools），
 * 语义不变、装配确定。新增自定义工具类时实现本接口即可被自动注册。
 */
public interface CustomTool {
}
