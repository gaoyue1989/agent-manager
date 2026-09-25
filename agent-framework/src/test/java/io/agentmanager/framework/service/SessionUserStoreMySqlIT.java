package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.mysql.cj.jdbc.MysqlDataSource;

/**
 * {@link SessionUserStore} 集成测试（真实 MySQL）。
 *
 * <p>运行方式（与其它 MySQL IT 同款开关）：
 * <pre>
 * HITL_MYSQL_IT=1 CHECKPOINT_JDBC_URL=jdbc:mysql://127.0.0.1:3307/agent_manager_test \
 *   CHECKPOINT_USERNAME=agent_manager CHECKPOINT_PASSWORD=... mvn test -Dtest=SessionUserStoreMySqlIT
 * </pre>
 *
 * <p><b>回归守卫（2026-09-24 冒烟暴露）</b>：MySQL 8 拒绝对目标表做
 * {@code INSERT ... VALUES (子查询引用同表)}（ERROR 1093），早期实现（模型绑定 / 标题写入）
 * 用该模式取 user_id 列，写入被 catch 成 warn 后**静默失败**——单测用 mock DataSource 覆盖不到，
 * 必须走真实 MySQL 才能暴露。本用例覆盖：不存在行补建、存在行 UPDATE、覆盖与清除、remark 同通道。
 */
@EnabledIfEnvironmentVariable(named = "HITL_MYSQL_IT", matches = "1")
class SessionUserStoreMySqlIT {

    @Test
    void modelAndRemarkUpsertShouldRoundTripOnRealMysql() throws Exception {
        var dataSource = new MysqlDataSource();
        dataSource.setURL(System.getenv("CHECKPOINT_JDBC_URL"));
        dataSource.setUser(System.getenv("CHECKPOINT_USERNAME"));
        dataSource.setPassword(System.getenv("CHECKPOINT_PASSWORD"));
        var store = new SessionUserStore(dataSource);
        var sid = "it-model_" + UUID.randomUUID();

        try {
            // 行不存在：补建 + 绑定（PATCH 打向未知会话的路径）
            store.upsertModel(sid, "m-1");
            assertEquals("m-1", store.findModelBySession(sid));

            // 覆盖与清除（""=回默认模型）
            store.upsertModel(sid, "m-2");
            assertEquals("m-2", store.findModelBySession(sid));
            store.upsertModel(sid, "");
            assertEquals("", store.findModelBySession(sid));

            // remark（标题）同通道：自动生成与手动重命名都写这里
            store.upsertRemark(sid, "标题A");
            assertEquals("标题A", store.findRemarkBySession(sid));
            store.upsertRemark(sid, "标题B");
            assertEquals("标题B", store.findRemarkBySession(sid));

            // 会话行已存在（chat 先 upsert 用户映射）→ 走 UPDATE 分支，且不改动 user_id
            store.upsert(sid, "it-user");
            assertEquals("it-user", store.findUserIdBySession(sid));
            store.upsertModel(sid, "m-3");
            assertEquals("m-3", store.findModelBySession(sid));
            assertEquals("it-user", store.findUserIdBySession(sid), "upsertModel 不得覆盖 user_id");
        } finally {
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement("DELETE FROM session_user WHERE session_id = ?")) {
                ps.setString(1, sid);
                ps.executeUpdate();
            } catch (Exception e) {
                fail("清理 IT 数据失败: " + e.getMessage());
            }
        }
    }
}
