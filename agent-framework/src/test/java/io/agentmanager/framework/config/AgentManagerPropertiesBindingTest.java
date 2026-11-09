package io.agentmanager.framework.config;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import io.agentmanager.framework.config.AgentManagerProperties.CleanupConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * CleanupConfig 绑定入口回归（g1：删除 D16 字段时 {@code @ConstructorBinding} 一并丢失）：
 * 嵌套 record 存在 5 参兼容构造（双构造）时，Spring Binder 必须由 {@code @ConstructorBinding}
 * 指定绑定入口——缺注解时整组静默绑定失败（2026-11-06 实测 cleanup=null 回退全默认值，
 * {@code AGENT_CLEANUP_*} env 全部永不生效）。本测试直接驱动 Binder（不起 Spring 上下文），
 * 注解丢失即全部断言回落默认值而失败。
 */
class AgentManagerPropertiesBindingTest {

    @Test
    void cleanupBindsFromAgentCleanupEnvKeys() {
        var source = new MapConfigurationPropertySource(Map.of(
            "agent.cleanup.confirm-ttl-minutes", "11",
            "agent.cleanup.turn-lease-ttl-seconds", "61",
            "agent.cleanup.turn-lease-renew-seconds", "21",
            "agent.cleanup.audit-retention-days", "31",
            "agent.cleanup.session-retention-days", "8",
            "agent.cleanup.skill-cache-gc-enabled", "false",
            "agent.cleanup.skill-cache-retention-days", "5",
            "agent.cleanup.sandbox-binding-retention-days", "3"));
        // 注解缺失（双构造无绑定入口）时这里 bind 返回全默认值/失败，断言即红
        CleanupConfig bound = new Binder(source)
            .bind("agent.cleanup", Bindable.of(CleanupConfig.class))
            .get();

        assertEquals(11, bound.confirmTtlMinutes());
        assertEquals(61, bound.turnLeaseTtlSeconds());
        assertEquals(21, bound.turnLeaseRenewSeconds());
        assertEquals(31, bound.auditRetentionDays());
        assertEquals(8, bound.sessionRetentionDays());
        assertFalse(bound.skillCacheGcEnabled());
        assertEquals(5, bound.skillCacheRetentionDays());
        assertEquals(3, bound.sandboxBindingRetentionDays());
    }
}
