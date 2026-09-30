package io.agentmanager.framework.service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 远程子任务在途登记存储（remote_task_registry 表，travel-fulfillment 设计 §18.2 多副本）。
 *
 * <p>v1.4 的在途登记是进程内 ConcurrentHashMap（"lead 重启后丢失"）：未落卡先重启的
 * awaiting 任务无任何兜底（TTL sweep 只扫 confirm_context 已落库行），member 侧永久挂起；
 * 多副本下登记副本本地，其他副本无法接管轮询/唤醒。本表把登记持久化（跨副本可见、
 * 跨重启可重建），承载四个机制：
 * <ol>
 *   <li><b>spawn 登记</b>：{@link #register} INSERT IGNORE 幂等（uk 对撞 = 已登记）；</li>
 *   <li><b>重建</b>：{@link #findInFlight} 供 Bridge 启动/周期回填内存登记表；</li>
 *   <li><b>唤醒幂等</b>：{@link #claimWake} IN_FLIGHT→TERMINAL 的认领即收口 CAS——
 *       决策路径与后台收割路径对同一终态只有一个副本（或进程）抢到汇总 turn（G4，
 *       无中间态防卡死，CR P1-3）；</li>
 *   <li><b>endpoint 快照</b>：{@link #findEndpoint} 供跨副本确认路由在内存登记 miss
 *       （spawn 副本已亡）时退回。</li>
 * </ol>
 *
 * <p><b>定位</b>：轮询调度簿，不是授权事实源——confirm_context 多行形态
 * （设计 §5.1）仍是唯一授权事实源；本表行丢失只影响轮询/唤醒的及时性，
 * 不影响确认语义。终态行保留 7 天由 SessionCleanupService 清理。
 */
@Service
public class RemoteTaskRegistryStore {
    private static final Logger log = LoggerFactory.getLogger(RemoteTaskRegistryStore.class);

    public static final String STATUS_IN_FLIGHT = "IN_FLIGHT";
    public static final String STATUS_TERMINAL = "TERMINAL";
    public static final String STATUS_GIVEN_UP = "GIVEN_UP";

    private final DataSource dataSource;

    public RemoteTaskRegistryStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 一行登记记录（重建查询返回形态） */
    public record RegistryRow(String sessionId, String taskId, String service,
                              String endpoint, String status) {
    }

    /**
     * spawn 登记（INSERT IGNORE 幂等；uk (session_id, task_id) 对撞 = 已登记）。
     * fail-soft：登记失败不影响父流——快照轮询的及时性受损（TTL 治理仍兜底已落卡行），
     * 与 v1.4 内存登记失败语义一致。
     */
    public void register(String sessionId, String taskId, String service, String endpoint) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 INSERT IGNORE INTO remote_task_registry
                   (session_id, task_id, service, endpoint, status, created_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, NOW(3), NOW(3))
                 """)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, taskId);
            stmt.setString(3, service);
            stmt.setString(4, endpoint);
            int affected = stmt.executeUpdate();
            if (affected > 0) {
                log.debug("[RemoteTaskRegistry] registered: sid={}, taskId={}, service={}",
                    sessionId, taskId, service);
            }
        } catch (Exception e) {
            log.error("[RemoteTaskRegistry] register failed (sid={}, taskId={}): {}",
                sessionId, taskId, e.getMessage());
        }
    }

    /** 在途登记行（重建扫描源，FIFO 按登记时间；上限防大表全量回填） */
    public List<RegistryRow> findInFlight(int limit) {
        var rows = new ArrayList<RegistryRow>();
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 SELECT session_id, task_id, service, endpoint, status
                 FROM remote_task_registry
                 WHERE status = ?
                 ORDER BY created_at ASC
                 LIMIT ?
                 """)) {
            stmt.setString(1, STATUS_IN_FLIGHT);
            stmt.setInt(2, limit);
            var rs = stmt.executeQuery();
            while (rs.next()) {
                rows.add(new RegistryRow(rs.getString("session_id"), rs.getString("task_id"),
                    rs.getString("service"), rs.getString("endpoint"), rs.getString("status")));
            }
        } catch (Exception e) {
            log.warn("[RemoteTaskRegistry] findInFlight failed: {}", e.getMessage());
        }
        return rows;
    }

    /** 行是否存在（唤醒 CAS 的"被抢 vs 无行"区分：无行走进程内兜底守卫） */
    public boolean exists(String sessionId, String taskId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT 1 FROM remote_task_registry WHERE session_id = ? AND task_id = ?")) {
            stmt.setString(1, sessionId);
            stmt.setString(2, taskId);
            return stmt.executeQuery().next();
        } catch (Exception e) {
            log.warn("[RemoteTaskRegistry] exists failed (sid={}, taskId={}): {}",
                sessionId, taskId, e.getMessage());
            return false;
        }
    }

    /**
     * 唤醒认领 CAS（IN_FLIGHT → TERMINAL，**认领即收口**，CR P1-3）：affected=1 表示
     * 本副本抢到该终态的唯一唤醒权；=0 表示已被其他副本/路径认领（或已收口）。
     *
     * <p>不设中间态：认领与收口若是两条语句，中间进程死亡/DB 抖动会把行永久卡在
     * 中间态（rebuild 不拾起、claimWake 恒 0、清理不覆盖）——比 v1.4 内存守卫更差。
     * 单语句原子关闭后，唤醒失败（租约忙等）按既有语义降级为「下一用户 turn 收割」。
     */
    public boolean claimWake(String sessionId, String taskId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 UPDATE remote_task_registry SET status = ?, updated_at = NOW(3)
                 WHERE session_id = ? AND task_id = ? AND status = ?
                 """)) {
            stmt.setString(1, STATUS_TERMINAL);
            stmt.setString(2, sessionId);
            stmt.setString(3, taskId);
            stmt.setString(4, STATUS_IN_FLIGHT);
            return stmt.executeUpdate() == 1;
        } catch (Exception e) {
            log.warn("[RemoteTaskRegistry] claimWake failed (sid={}, taskId={}): {}",
                sessionId, taskId, e.getMessage());
            return false;
        }
    }

    /** 传输超限放弃收口（IN_FLIGHT → GIVEN_UP；幂等，重复收口 no-op） */
    public void markTerminal(String sessionId, String taskId, boolean givenUp) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 UPDATE remote_task_registry SET status = ?, updated_at = NOW(3)
                 WHERE session_id = ? AND task_id = ? AND status = ?
                 """)) {
            stmt.setString(1, givenUp ? STATUS_GIVEN_UP : STATUS_TERMINAL);
            stmt.setString(2, sessionId);
            stmt.setString(3, taskId);
            stmt.setString(4, STATUS_IN_FLIGHT);
            stmt.executeUpdate();
        } catch (Exception e) {
            log.warn("[RemoteTaskRegistry] markTerminal failed (sid={}, taskId={}): {}",
                sessionId, taskId, e.getMessage());
        }
    }

    /** endpoint 快照（跨副本确认路由退回链第二环：内存登记 miss → 本表 → 声明清单） */
    public Optional<String> findEndpoint(String sessionId, String taskId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT endpoint FROM remote_task_registry WHERE session_id = ? AND task_id = ?")) {
            stmt.setString(1, sessionId);
            stmt.setString(2, taskId);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                var endpoint = rs.getString("endpoint");
                if (endpoint != null && !endpoint.isBlank()) {
                    return Optional.of(endpoint);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[RemoteTaskRegistry] findEndpoint failed (sid={}, taskId={}): {}",
                sessionId, taskId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 终态行保留截止时刻（供 SessionCleanupService 清理；返回已删行数） */
    public int deleteTerminalBefore(Timestamp cutoff) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 DELETE FROM remote_task_registry
                 WHERE status IN (?, ?) AND updated_at < ?
                 """)) {
            stmt.setString(1, STATUS_TERMINAL);
            stmt.setString(2, STATUS_GIVEN_UP);
            stmt.setTimestamp(3, cutoff);
            int n = stmt.executeUpdate();
            if (n > 0) {
                log.info("[RemoteTaskRegistry] cleaned {} terminal row(s)", n);
            }
            return n;
        } catch (Exception e) {
            log.warn("[RemoteTaskRegistry] deleteTerminalBefore failed: {}", e.getMessage());
            return 0;
        }
    }
}
