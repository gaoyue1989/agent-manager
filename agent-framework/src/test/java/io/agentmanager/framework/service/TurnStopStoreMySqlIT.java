package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.mysql.cj.jdbc.MysqlDataSource;

/**
 * {@link TurnStopStore} 集成测试（真实 MySQL）。
 *
 * <p>运行方式（与其它 MySQL IT 同款开关）：
 * <pre>
 * HITL_MYSQL_IT=1 CHECKPOINT_JDBC_URL=jdbc:mysql://127.0.0.1:3307/agent_manager_test \
 *   CHECKPOINT_USERNAME=agent_manager CHECKPOINT_PASSWORD=... mvn test -Dtest=TurnStopStoreMySqlIT
 * </pre>
 *
 * <p>覆盖：写入/覆盖、replyId 栅栏消费（陈旧清理不得删除新请求）、全量读取、过期清理。
 */
@EnabledIfEnvironmentVariable(named = "HITL_MYSQL_IT", matches = "1")
class TurnStopStoreMySqlIT {

    @Test
    void requestFindConsumeShouldRespectReplyIdFencing() throws Exception {
        var dataSource = new MysqlDataSource();
        dataSource.setURL(System.getenv("CHECKPOINT_JDBC_URL"));
        dataSource.setUser(System.getenv("CHECKPOINT_USERNAME"));
        dataSource.setPassword(System.getenv("CHECKPOINT_PASSWORD"));
        io.agentmanager.framework.support.TestSchemaMigrator.migrate(dataSource);
        var store = new TurnStopStore(dataSource);
        var sid = "it-stop_" + UUID.randomUUID();

        try {
            assertEquals(TurnStopStore.RequestOutcome.REQUESTED,
                store.request(sid, "rid-1", "u1"));
            var found = store.find(sid);
            assertNotNull(found);
            assertEquals("rid-1", found.replyId());
            assertTrue(store.findAll().stream()
                .anyMatch(r -> sid.equals(r.sessionId()) && "rid-1".equals(r.replyId())));

            // 覆盖到新一轮（last-writer-wins）
            assertEquals(TurnStopStore.RequestOutcome.REQUESTED,
                store.request(sid, "rid-2", "u2"));
            assertEquals("rid-2", store.find(sid).replyId());

            // 陈旧 replyId 的清理不得删除新请求（栅栏）
            assertTrue(!store.consume(sid, "rid-1"));
            assertEquals("rid-2", store.find(sid).replyId());

            // 匹配删除成功
            assertTrue(store.consume(sid, "rid-2"));
            assertNull(store.find(sid));

            // 过期清理
            assertEquals(TurnStopStore.RequestOutcome.REQUESTED,
                store.request(sid, "rid-old", "u1"));
            store.deleteBefore(Instant.now().plusSeconds(60));
            assertNull(store.find(sid));
        } finally {
            try (var conn = dataSource.getConnection();
                 var ps = conn.prepareStatement("DELETE FROM turn_stop WHERE session_id = ?")) {
                ps.setString(1, sid);
                ps.executeUpdate();
            } catch (Exception e) {
                fail("清理 IT 数据失败: " + e.getMessage());
            }
        }
    }
}
