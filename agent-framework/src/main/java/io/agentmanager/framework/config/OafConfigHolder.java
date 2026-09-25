package io.agentmanager.framework.config;

import org.springframework.stereotype.Component;

import io.agentmanager.framework.model.OafConfig;

/**
 * OAF 配置动态门面：volatile 持有当前 {@link OafConfig}，支持 reload 后原子替换。
 *
 * <p>背景：OAF 包（PVC /config）原位更新后经 {@code OafReloadService} 重新解析 frontmatter，
 * 产生新的不可变 {@code OafConfig} 实例。需要看到新值的读取点（A2A 卡片、/debug/config、
 * /info、McpResourceProxy 的 server 声明等）注入本 holder 而非裸 {@code OafConfig} Bean；
 * 不随包更新变化的读取点（catalogId、agentName 等）可继续注入原 Bean。
 *
 * <p>启动时由 {@link AgentScopeConfig} 以初始 {@code OafConfig} 初始化；reload 成功后
 * {@link #update(OafConfig)} 原子替换，读侧 {@link #get()} 永远拿到完整一致的实例。
 */
@Component
public class OafConfigHolder {

    private volatile OafConfig current;

    public OafConfigHolder(OafConfig initial) {
        this.current = initial;
    }

    /** 当前生效的 OAF 配置（reload 后返回新实例）。 */
    public OafConfig get() {
        return current;
    }

    /** 原子替换当前配置（仅 OafReloadService 调用）。 */
    public void update(OafConfig next) {
        this.current = next;
    }
}
