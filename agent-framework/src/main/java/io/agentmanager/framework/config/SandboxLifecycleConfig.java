package io.agentmanager.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 沙箱生命周期配置（sandbox-pool-opensandbox-design §4.7，PR-D）。
 * application.yml {@code agent.sandbox.lifecycle.*} 占位符段直传 env
 * （SystemEnvironmentPropertyMapper 只映射顶层键，占位符段是 env 直传唯一通路）。
 *
 * <p><b>默认开启</b>（新语义即平台默认行为）：存量已发布服务 env 固化不含新键 → 取框架
 * 默认值自动获得配额与 TTL 行为，显式 env 覆盖优先（§4.7）。
 *
 * <p>env 对照：SANDBOX_LIFECYCLE_ENABLED / SANDBOX_POOL_REF / SANDBOX_USER_QUOTA /
 * SANDBOX_RENEW_INTERVAL_SECONDS / SANDBOX_CONFIRM_HOLD_MINUTES；TTL 复用既有键
 * {@code SANDBOX_TIMEOUT_MINUTES}（语义升级为生命周期 TTL，默认 30，绑定于
 * {@link SandboxConfig#timeoutMinutes}，不在此重复）。
 */
@ConfigurationProperties(prefix = "agent.sandbox.lifecycle")
public record SandboxLifecycleConfig(
    /** 总开关；false = 配额/绑定/续期全部旁路，完全回到回滚后基线行为（一键回退，§4.10） */
    @DefaultValue("true") boolean enabled,
    /** OpenSandbox Pool 名；空 = 不用服务端池（create 不带 extensions.poolRef，§4.5） */
    @DefaultValue("") String poolRef,
    /** 单用户沙箱配额（per-service）；0 = 不限（仅关配额判满，TTL/记账行为保留，终审 R2） */
    @DefaultValue("5") int userQuota,
    /** T3 在途续期间隔（秒）；启动校验 {@code < TTL×60/2}（§4.7 fail-fast） */
    @DefaultValue("60") int renewIntervalSeconds,
    /** T4 HITL 挂起保留窗（分钟）：窗口内挂起沙箱持续续期、不可被 LRU 调度（§4.4） */
    @DefaultValue("20") int confirmHoldMinutes
) {}
