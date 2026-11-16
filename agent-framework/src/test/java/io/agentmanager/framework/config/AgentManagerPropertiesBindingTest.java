package io.agentmanager.framework.config;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import io.agentmanager.framework.config.AgentManagerProperties.CleanupConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * FileConfig 三键（issue #345 P3 新增的 spool-dir + 交付护栏 upload/present 上限）绑定回归：
     * 键改名/字段误删时静默回落默认值（20/50/空），此处直接驱动 Binder 锁住绑定链路。
     */
    @Test
    void fileBindsUploadPresentSpoolKeys() {
        var source = new MapConfigurationPropertySource(Map.of(
            "agent.file.upload-max-mb", "33",
            "agent.file.present-max-mb", "64",
            "agent.file.spool-dir", "/var/spool/file-transfer"));
        AgentManagerProperties.FileConfig bound = new Binder(source)
            .bind("agent.file", Bindable.of(AgentManagerProperties.FileConfig.class))
            .get();

        assertEquals(33, bound.uploadMaxMb());
        assertEquals(64, bound.presentMaxMb());
        assertEquals("/var/spool/file-transfer", bound.spoolDir());
        // 交付通道守护不变量（issue #345）：upload 上限必须 < present 上限（否则 present
        // 大上传件复现先读后拒的 OOM 路径）
        assertTrue(bound.uploadMaxMb() < bound.presentMaxMb());
    }

    /**
     * resolvedSpoolDir 回落链路（issue #345 P3）：显式配置优先；未配置回落
     * {workspaceBase}/tmp/file-transfer——业务 Pod /tmp 不可写实测（Permission denied），
     * s3 后端该回落错了只有生产暴露（spool 全部落盘失败）。
     */
    @Test
    void resolvedSpoolDirShouldFallBackToWorkspaceTmp() {
        // 显式配置优先
        assertEquals("/var/spool/custom", props("", "/var/spool/custom").resolvedSpoolDir());
        // spoolDir 未配置：回落 {workspaceDir}/tmp/file-transfer
        assertEquals("/data/ws/tmp/file-transfer", props("/data/ws", "").resolvedSpoolDir());
        // 空白串视同未配置（isBlank 判据）
        assertEquals("/data/ws/tmp/file-transfer", props("/data/ws", " ").resolvedSpoolDir());
        // workspaceDir 也未配置：workspaceBase 回落 configDir
        assertEquals("/config/tmp/file-transfer", props("", "").resolvedSpoolDir());
    }

    private static AgentManagerProperties props(String workspaceDir, String spoolDir) {
        return new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1",
                "openai", 0.7, 4096, 120, true, 0, "", null),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass", "test"),
            "/config",
            workspaceDir,
            new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20, "image/*,text/plain", 5, 15, 50, true, 7,
                "s3", "/data/files", "", "", "", "agent-files", "", spoolDir),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            AgentManagerProperties.HarnessConfig.defaults());
    }
}
