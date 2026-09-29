package io.agentmanager.framework.service.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * deny_rules 解析单测（F16 自实现）：规则 map 多键兼容 / 纯字符串条目 / 非法条目
 * 逐条跳过 / 整体形态非法与缺失按空集合（fail-soft，回落 ask 兜底方向安全）。
 */
class ProtocolDenyRulesTest {

    @Test
    void ruleMapsShouldParseViaCompatibleKeys() {
        assertEquals(Set.of("write_file", "execute"), ProtocolDenyRules.parseToolNames(List.of(
            Map.of("tool_name", "write_file"),
            Map.of("tool", "execute"),
            Map.of("name", "write_file"))));
    }

    @Test
    void plainStringEntriesShouldBeAccepted() {
        assertEquals(Set.of("write_file"),
            ProtocolDenyRules.parseToolNames(List.of("write_file")));
    }

    @Test
    void malformedEntriesShouldBeSkipped() {
        assertEquals(Set.of("write_file"), ProtocolDenyRules.parseToolNames(List.of(
            Map.of("tool_name", "write_file"),
            Map.of("unknown_key", "x"),
            123,
            "  ",
            Map.of())));
    }

    @Test
    void nonListOrMissingShouldBeEmpty() {
        assertTrue(ProtocolDenyRules.parseToolNames(null).isEmpty());
        assertTrue(ProtocolDenyRules.parseToolNames("write_file").isEmpty());
        assertTrue(ProtocolDenyRules.parseToolNames(Map.of("tool_name", "write_file")).isEmpty());
    }

    @Test
    void duplicatesShouldCollapse() {
        assertEquals(Set.of("write_file"), ProtocolDenyRules.parseToolNames(List.of(
            Map.of("tool_name", "write_file"), "write_file", " write_file ")));
    }

    @Test
    void runtimeAttributeKeyShouldAlignSdkNaming() {
        // 与 SDK 的 agentprotocol.context.attributes 同命名空间（AgentProtocolConstants 实证）
        assertEquals("agentprotocol.context.deny_rules", ProtocolDenyRules.RUNTIME_ATTRIBUTE_KEY);
        assertEquals("deny_rules", ProtocolDenyRules.CONTEXT_FIELD);
    }
}
