package io.agentmanager.framework.service.protocol;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 远程子 agent 提交上下文 {@code context.deny_rules} 的解析与 RuntimeContext 载体
 * （travel-fulfillment 设计 F16/§6.1 兜底修订：agent-protocol 扩展 2.0.3 不消费该字段，
 * member 侧自实现动态 DENY）。
 *
 * <p>链路：lead 侧 {@code RemoteSubmitContext.denyRules}（List&lt;Map&lt;String,String&gt;&gt;，
 * SDK toMap 序列化为 context 键 "deny_rules"）→ member 侧扩展收到提交后经
 * {@code RuntimeContextCustomizer} 解析本集合挂 RuntimeContext → 中间件在 acting 阶段
 * 按工具名拦截。规则匹配为精确工具名（与 {@code buildPermissionContext} 同口径，
 * ruleContent/pattern 维度 SDK 权限引擎不支持，忽略）。
 */
public final class ProtocolDenyRules {

    private static final Logger log = LoggerFactory.getLogger(ProtocolDenyRules.class);

    /** 提交上下文里 deny_rules 的键（RemoteSubmitContext.toMap 字节码实证） */
    public static final String CONTEXT_FIELD = "deny_rules";

    /** deny_rules 规范化后在 RuntimeContext 上的挂载键（命名对齐 SDK 的 agentprotocol.context.attributes） */
    public static final String RUNTIME_ATTRIBUTE_KEY = "agentprotocol.context.deny_rules";

    /** 规则 map 里工具名的兼容键（官方未定 schema，按常见命名逐个识别） */
    private static final List<String> TOOL_NAME_KEYS = List.of("tool_name", "tool", "name");

    private ProtocolDenyRules() {}

    /**
     * 解析 deny_rules 原始值为去重工具名集合；非法条目逐条跳过（fail-soft），
     * 整体形态非法按空集合处理。安全方向无损：解析不出 = 不拦截 = 回落既有
     * fail-closed（未覆盖工具走 ask）路径。入参兼容 List/Collection（挂载侧
     * LinkedHashSet 与原始 List 双形态）。
     */
    public static Set<String> parseToolNames(Object denyRules) {
        var names = new LinkedHashSet<String>();
        if (denyRules == null) {
            return names;
        }
        if (!(denyRules instanceof java.util.Collection<?> rules)) {
            log.warn("[protocol-deny-rules] ignore malformed deny_rules (not a collection): {}",
                denyRules.getClass().getName());
            return names;
        }
        for (var rule : rules) {
            var name = extractToolName(rule);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    /** 单条规则 → 工具名；不支持形态返回 null 并记调试日志 */
    private static String extractToolName(Object rule) {
        if (rule instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        if (rule instanceof Map<?, ?> map) {
            for (var key : TOOL_NAME_KEYS) {
                var v = map.get(key);
                if (v instanceof String s && !s.isBlank()) {
                    return s.trim();
                }
            }
        }
        log.debug("[protocol-deny-rules] skip unrecognizable deny rule entry: {}", rule);
        return null;
    }
}
