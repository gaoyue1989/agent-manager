package io.agentmanager.framework.service.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.agentprotocol.AgentRequest;

/**
 * deny_rules 定制器单测（F16 自实现第一环）：真实 AgentRequest（构造实参顺序按
 * AgentProtocolTaskStore.runAgent 字节码：taskId, agentId, input, userId,
 * parentSessionId, resume, context）+ 真实 RuntimeContext.Builder，断言挂载键与
 * 解析结果；缺失/非法 deny_rules 不挂载。
 */
class ProtocolDenyRulesContextCustomizerTest {

    private final ProtocolDenyRulesContextCustomizer customizer = new ProtocolDenyRulesContextCustomizer();

    private static AgentRequest request(Map<String, Object> context) {
        return new AgentRequest("task-1", "agent-1", "do something",
            "user-1", "parent-session-1", false, context);
    }

    @Test
    void denyRulesShouldBeMountedOnRuntimeContext() {
        var builder = RuntimeContext.builder();
        customizer.customize(request(Map.of(
            "deny_rules", List.of(Map.of("tool_name", "write_file"), "execute"),
            "user_id", "user-1")), builder);

        var ctx = builder.build();
        assertEquals(Set.of("write_file", "execute"),
            ctx.get(ProtocolDenyRules.RUNTIME_ATTRIBUTE_KEY));
    }

    @Test
    void missingDenyRulesShouldNotMountAttribute() {
        var builder = RuntimeContext.builder();
        customizer.customize(request(Map.of("user_id", "user-1")), builder);

        assertNull(builder.build().get(ProtocolDenyRules.RUNTIME_ATTRIBUTE_KEY));
    }

    @Test
    void malformedDenyRulesShouldNotMountAttribute() {
        var builder = RuntimeContext.builder();
        customizer.customize(request(Map.of("deny_rules", "write_file")), builder);

        assertNull(builder.build().get(ProtocolDenyRules.RUNTIME_ATTRIBUTE_KEY));
    }
}
