package io.agentmanager.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AG-UI 端点配置（agui-migration-plan Phase 1：application.yml `agui:` 配置段）。
 *
 * @param agentId 单 agent 标识（固定 release-agent，路由校验用）
 * @param runTimeoutMinutes adapter run 级超时（要点 8：现链路无此限制，发布/长工具场景
 *                          可能长跑；0 或负数 = 禁用；默认 30，不低于 ingress 3600s 侧行为）
 */
@ConfigurationProperties(prefix = "agui")
public record AguiProperties(
    @DefaultValue("release-agent") String agentId,
    @DefaultValue("30") Integer runTimeoutMinutes) {

    /** run 超时：0/负数禁用返回 null，否则转 Duration */
    public java.time.Duration resolvedRunTimeout() {
        if (runTimeoutMinutes == null || runTimeoutMinutes <= 0) {
            return null;
        }
        return java.time.Duration.ofMinutes(runTimeoutMinutes);
    }
}
