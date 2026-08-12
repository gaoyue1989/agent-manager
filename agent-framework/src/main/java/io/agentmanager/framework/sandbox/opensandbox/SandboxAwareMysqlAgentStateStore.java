package io.agentmanager.framework.sandbox.opensandbox;

import javax.sql.DataSource;

import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;

/**
 * 兼容沙箱状态的 MySQL AgentStateStore。
 *
 * 框架 SessionSandboxStateStore 持久化沙箱状态时使用形如
 * `sandbox/user/{agentId}/{userId}` 的 slot ID（必然含 "/"），
 * 而官方 MysqlAgentStateStore.validateSessionId 拒绝含路径分隔符的 ID，
 * 导致沙箱状态无法持久化（每次请求都降级新建沙箱）。
 *
 * 子类放宽校验：仅拒绝空 ID，放行 "/" 与 "\"。
 */
public class SandboxAwareMysqlAgentStateStore extends MysqlAgentStateStore {

    public SandboxAwareMysqlAgentStateStore(DataSource dataSource, String dbName,
                                            String tableName, boolean initializeSchema) {
        super(dataSource, dbName, tableName, initializeSchema);
    }

    @Override
    protected void validateSessionId(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("AgentStateStore ID cannot be null or empty");
        }
        // 沙箱 slot ID 形如 sandbox/user/{agentId}/{userId}，允许路径分隔符
    }
}
