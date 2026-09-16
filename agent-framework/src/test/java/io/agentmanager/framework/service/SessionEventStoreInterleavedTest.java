package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 不变量 I1 在**多 session 交错**下的回归（补充 SessionEventStoreBatchTest 的单 session 覆盖）。
 *
 * <p>缓冲按 session 分区（而不是一条全局 FIFO），钉住三条性质：
 * <ol>
 *   <li>{@code finishTurn(A)} 只刷 A 的行，B 的缓冲原封不动；
 *   <li>按 session 看，落库的 seq 顺序与完整性不受其他 session 干扰；
 *   <li><b>一次 INSERT 只装一个 session 的行</b>——所以 A 的批整批失败时 B 的行不受牵连。
 * </ol>
 *
 * <p>第三条是分区的主要收益，也是唯一键（uk_session_seq）兜底能成立的前提：InnoDB 按
 * <b>语句</b>回滚，全局缓冲时代一批里混着多个 session，一次 seq 冲突会把邻居的行一并丢掉
 * ——那是把「静默重复」换成「静默丢失 + 殃及邻居」，比原问题更糟。
 *
 * <p>批大小设为 10、时间窗 60s，确保测试期间不会因"满批/超时"而刷出——唯一的刷出点是
 * 里程碑事件与 {@code finishTurn}。
 */
@ExtendWith(MockitoExtension.class)
class SessionEventStoreInterleavedTest {

    @Mock private DataSource dataSource;

    private SessionEventStore store;

    /** 成功落库的 (sessionId, seq)，模拟 DB 中实际存在的行 */
    private final List<String> written = new ArrayList<>();

    /** 让某个 session 的 INSERT 抛唯一键冲突（模拟 uk_session_seq 兜底生效）；null = 不失败 */
    private volatile String failInsertsFor;

    @BeforeEach
    void setUp() throws Exception {
        var conn = mock(java.sql.Connection.class);
        var stmt = mock(java.sql.Statement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeUpdate(anyString())).thenReturn(0);

        var selectPs = mock(java.sql.PreparedStatement.class);
        var rs = mock(java.sql.ResultSet.class);
        when(conn.prepareStatement(contains("SELECT COALESCE(MAX"))).thenReturn(selectPs);
        when(selectPs.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);

        // insertBatch 每次都是新的 PreparedStatement，绑定状态天然按语句隔离。
        //
        // 绑定顺序为 sid(setString,1), seq(setInt,2), type, payload, replyId，每行推进 5 个下标，
        // 故第 n 行的 seq 下标 = 5n-3，其前一个下标 5n-4 正是该行的 sid。
        //
        // 行先攒在 bound 里、executeUpdate 成功才计入 written：失败时整条语句回滚，
        // 那些行根本没有落库——若在绑定时就记进 written，containment 用例会看到幻影行。
        when(conn.prepareStatement(startsWith("INSERT INTO session_event"))).thenAnswer(inv -> {
            var ps = mock(java.sql.PreparedStatement.class);
            List<String> bound = new ArrayList<>();
            Map<Integer, String> sidByIndex = new HashMap<>();

            doAnswer(a -> {
                sidByIndex.put(a.getArgument(0), a.getArgument(1));
                return null;
            }).when(ps).setString(anyInt(), any());
            doAnswer(a -> {
                int seqIdx = a.getArgument(0);
                bound.add(sidByIndex.get(seqIdx - 1) + ":" + a.getArgument(1));
                return null;
            }).when(ps).setInt(anyInt(), anyInt());
            when(ps.executeUpdate()).thenAnswer(a -> {
                if (!bound.isEmpty() && bound.get(0).split(":")[0].equals(failInsertsFor)) {
                    bound.clear();   // 语句整体回滚
                    throw new java.sql.SQLIntegrityConstraintViolationException(
                        "Duplicate entry for key 'uk_session_seq'");
                }
                written.addAll(bound);
                return bound.size();
            });
            return ps;
        });

        store = new SessionEventStore(dataSource, 7, 10, 60_000);
    }

    @Test
    void finishTurnFlushesOnlyThatSessionsRows() {
        store.seedSeq("A");
        store.seedSeq("B");

        // 交错写入：A、B 各 2 条，均留在缓冲中（批大小 10、时间窗 60s）
        store.append("A", "ra", "TEXT_BLOCK_DELTA", "{\"d\":\"a1\"}");
        store.append("B", "rb", "TEXT_BLOCK_DELTA", "{\"d\":\"b1\"}");
        store.append("A", "ra", "TEXT_BLOCK_DELTA", "{\"d\":\"a2\"}");
        store.append("B", "rb", "TEXT_BLOCK_DELTA", "{\"d\":\"b2\"}");
        assertEquals(List.of(), written, "未满批不应落库");

        store.finishTurn("A");

        assertEquals(List.of("A:1", "A:2"), written,
            "finishTurn(A) 只应刷出 A 的行，不得捎带 B 的");
        assertEquals(List.of("1", "2"), seqsOf("A"), "A 的 seq 必须是连续前缀");

        // B 的行原封不动地留在缓冲里：既没被 A 的收尾刷出，也没被丢掉
        store.append("B", "rb", "TEXT_BLOCK_DELTA", "{\"d\":\"b3\"}");
        assertEquals(List.of("A:1", "A:2"), written, "B 的缓冲不该被 A 的收尾影响");

        store.finishTurn("B");

        assertEquals(List.of("A:1", "A:2", "B:1", "B:2", "B:3"), written,
            "B 随后按序整体刷出，不漏不重");
        assertEquals(List.of("1", "2", "3"), seqsOf("B"), "B 的 seq 必须是连续前缀");

        // A 的计数器已释放 → 新 turn 重新从 DB 最大值播种（此处 DB 桩恒为 0）。
        // 里程碑事件会刷出 A 的缓冲，但同样只刷 A 的
        store.seedSeq("A");
        store.append("A", "ra2", "AGENT_END", "{}");
        assertEquals(List.of("1", "2", "1"), seqsOf("A"),
            "计数器已释放，A 的新 turn 从 DB 最大值重新播种");
        assertEquals(List.of("A:1", "A:2", "B:1", "B:2", "B:3", "A:1"), written,
            "A 的里程碑只刷 A 自己的行");
    }

    @Test
    void collidingSessionDoesNotDragDownOtherSessionsRows() {
        // 分区之前这里是一条全局缓冲：A 撞唯一键会让整批回滚，B 的行跟着一起没了。
        // 分区之后一条语句只装一个 session 的行，冲突的影响面止于肇事 session。
        store.seedSeq("A");
        store.seedSeq("B");
        store.append("A", "ra", "TEXT_BLOCK_DELTA", "{\"d\":\"a1\"}");
        store.append("B", "rb", "TEXT_BLOCK_DELTA", "{\"d\":\"b1\"}");

        failInsertsFor = "A";

        store.finishTurn("A");   // A 的批整批失败 → 该批的行被丢弃（已知偏差）
        store.finishTurn("B");   // B 的批在自己的语句里，必须照常落库

        assertEquals(List.of("B:1"), written,
            "A 的唯一键冲突不得带走 B 的行");

        // 失败批的行已随缓冲一起被移除：不会在后续某次收尾时"复活"并写到新 owner
        // 已占用的 seq 区间上。丢弃是确定的，不取决于后续还有没有事件进来。
        store.finishTurn("A");
        assertEquals(List.of("B:1"), written, "失败批不得在后续收尾时被重试写出");
    }

    private List<String> seqsOf(String sid) {
        return written.stream().filter(s -> s.startsWith(sid + ":"))
            .map(s -> s.split(":")[1]).toList();
    }
}
