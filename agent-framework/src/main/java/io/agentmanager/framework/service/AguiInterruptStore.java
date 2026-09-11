package io.agentmanager.framework.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.AguiInterruptConstants;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiResume;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AG-UI HITL interrupt 元数据存储（agui_interrupt 表，agui-migration-plan §5.3）。
 *
 * <p>无状态闭环：RUN_FINISHED(interrupts) → persist 落库（跨副本可见）→ 前端 resume[] 新 run
 * → load 重建 AguiEvent.Interrupt 注入 RuntimeContext（agui.resume.interrupts）→ adapter 构造
 * ConfirmResult 消息恢复挂起 tool_use → CAS consumed 0→1 防重复消费（对齐 confirm_context 模式）。
 *
 * <p>持久化整个 {@link AguiEvent.Interrupt} record（含 metadata 的
 * toolName/toolInput/toolContent/replyId）：R10——adapter resumeInterrupts 对字段缺失会静默
 * 丢弃导致恢复退化为重复询问，故 load 时做完整性校验，缺失即 fail-fast
 * （对齐 confirm_context content=null 前科的回归教训）。
 *
 * <p>TTL：默认 30min（读时懒判断 + SessionCleanupService 定时清理兜底）。
 */
@Service
public class AguiInterruptStore {
    private static final Logger log = LoggerFactory.getLogger(AguiInterruptStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSource dataSource;
    private final Duration ttl;

    /** Spring 装配入口（TTL 默认 30min；多构造器场景需显式标注） */
    @org.springframework.beans.factory.annotation.Autowired
    public AguiInterruptStore(DataSource dataSource) {
        this(dataSource, Duration.ofMinutes(30));
    }

    public AguiInterruptStore(DataSource dataSource, Duration ttl) {
        this.dataSource = dataSource;
        this.ttl = ttl;
        initSchema();
    }

    /** 建表（幂等），失败 fail-fast */
    private void initSchema() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS agui_interrupt (
                  thread_id       VARCHAR(255) NOT NULL COMMENT 'AG-UI threadId',
                  interrupts_json MEDIUMTEXT   NOT NULL COMMENT '[{interruptId,toolCallId,toolName,toolInput,toolContent,replyId}]',
                  run_id          VARCHAR(128) COMMENT '产生 interrupt 的 runId',
                  consumed        TINYINT      NOT NULL DEFAULT 0 COMMENT 'CAS 消费标志 0->1',
                  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (thread_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AG-UI HITL interrupt 元数据（跨副本恢复）'
                """);
            log.info("AguiInterruptStore: agui_interrupt table ready");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init agui_interrupt table: " + e.getMessage(), e);
        }
    }

    /** 覆盖式写入 interrupt 元数据（同 thread 新 interrupt 到来覆盖旧条目；consumed 重置 0） */
    public void persist(String threadId, List<AguiEvent.Interrupt> interrupts, String runId) {
        if (threadId == null || threadId.isBlank() || interrupts == null || interrupts.isEmpty()) {
            return;
        }
        try {
            var json = MAPPER.writeValueAsString(interrupts);
            try (var conn = dataSource.getConnection();
                 var stmt = conn.prepareStatement("""
                     INSERT INTO agui_interrupt (thread_id, interrupts_json, run_id, consumed, created_at, updated_at)
                     VALUES (?, ?, ?, 0, NOW(), NOW())
                     ON DUPLICATE KEY UPDATE interrupts_json = VALUES(interrupts_json),
                                            run_id = VALUES(run_id), consumed = 0, updated_at = NOW()
                     """)) {
                stmt.setString(1, threadId);
                stmt.setString(2, json);
                stmt.setString(3, runId);
                stmt.executeUpdate();
            }
        } catch (Exception e) {
            // 持久化失败 → 跨副本/重启 resume 不可用（单副本内存内仍可用上游模式兜不了），记录告警
            log.error("agui_interrupt persist failed (threadId={}): {}", threadId, e.getMessage());
        }
    }

    /**
     * 读取未消费且未过期的 interrupt 元数据并重建 AguiEvent.Interrupt。
     * R10 完整性校验：id/reason 由 record 强制非空（Jackson 反序列化即兜底），
     * metadata 的 toolName/toolContent 缺失视为损坏 → fail-fast，由
     * controller 映射 RUN_ERROR（恢复退化为重复询问前拦截）。
     *
     * @throws AguiInterruptCorruptedException 字段缺失/类型不符
     */
    public List<AguiEvent.Interrupt> load(String threadId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT interrupts_json, consumed, created_at FROM agui_interrupt WHERE thread_id = ?")) {
            stmt.setString(1, threadId);
            var rs = stmt.executeQuery();
            if (!rs.next()) {
                return List.of();
            }
            if (rs.getInt("consumed") != 0) {
                return List.of();
            }
            var createdAt = rs.getTimestamp("created_at");
            if (createdAt != null
                && createdAt.toInstant().plus(ttl).isBefore(Instant.now())) {
                return List.of();
            }
            var interrupts = MAPPER.readValue(rs.getString("interrupts_json"), AguiEvent.Interrupt[].class);
            for (var interrupt : interrupts) {
                // R10：adapter resumeInterrupts 仅做 instanceof 校验，字段缺失会静默丢弃
                var metadata = interrupt.metadata();
                if (metadata == null
                    || metadata.get(AguiInterruptConstants.METADATA_TOOL_NAME) == null
                    || metadata.get(AguiInterruptConstants.METADATA_TOOL_CONTENT) == null) {
                    throw new AguiInterruptCorruptedException(
                        "interrupt metadata missing toolName/toolContent: " + interrupt.id());
                }
            }
            return List.of(interrupts);
        } catch (AguiInterruptCorruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new AguiInterruptCorruptedException(
                "agui_interrupt load failed for '" + threadId + "': " + e.getMessage(), e);
        }
    }

    /**
     * resume[] 覆盖率校验（§5.1 resume 约束）：绕开 AguiResumeCoordinator 后，
     * 该职责由 controller 自担——resume[] 的 interruptId 必须覆盖全部 open interrupts，
     * 部分覆盖返回缺失集合（非空 → controller 400）。
     */
    public Set<String> uncoveredInterruptIds(List<AguiEvent.Interrupt> open, List<AguiResume> resume) {
        var openIds = new HashSet<String>();
        for (var interrupt : open) {
            openIds.add(interrupt.id());
        }
        var resumed = new HashSet<String>();
        if (resume != null) {
            for (var r : resume) {
                if (r.getInterruptId() != null) {
                    resumed.add(r.getInterruptId());
                }
            }
        }
        var uncovered = new HashSet<>(openIds);
        uncovered.removeAll(resumed);
        return uncovered;
    }

    /** CAS 消费（0→1）；失败（已被消费/不存在）返回 false → controller 映射 409 */
    public boolean consume(String threadId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "UPDATE agui_interrupt SET consumed = 1, updated_at = NOW() "
                     + "WHERE thread_id = ? AND consumed = 0")) {
            stmt.setString(1, threadId);
            return stmt.executeUpdate() > 0;
        } catch (Exception e) {
            log.warn("agui_interrupt consume failed (threadId={}): {}", threadId, e.getMessage());
            return false;
        }
    }

    /** 删除同 thread 记录（DELETE thread 端点联动清理） */
    public void delete(String threadId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("DELETE FROM agui_interrupt WHERE thread_id = ?")) {
            stmt.setString(1, threadId);
            stmt.executeUpdate();
        } catch (Exception e) {
            log.warn("agui_interrupt delete failed (threadId={}): {}", threadId, e.getMessage());
        }
    }

    /** 过期清理（SessionCleanupService cron 兜底；返回删除行数，失败 0） */
    public int deleteExpired() {
        var cutoff = Timestamp.from(Instant.now().minus(ttl));
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("DELETE FROM agui_interrupt WHERE updated_at < ?")) {
            stmt.setTimestamp(1, cutoff);
            return stmt.executeUpdate();
        } catch (Exception e) {
            log.warn("agui_interrupt cleanup failed: {}", e.getMessage());
            return 0;
        }
    }

    /** interrupt 元数据损坏（R10 fail-fast）——controller 映射 RUN_ERROR/500 */
    public static class AguiInterruptCorruptedException extends RuntimeException {
        public AguiInterruptCorruptedException(String message) {
            super(message);
        }

        public AguiInterruptCorruptedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Map<interruptId, Interrupt> 组装（RuntimeContext 注入载体） */
    public static Map<String, AguiEvent.Interrupt> toInterruptMap(List<AguiEvent.Interrupt> interrupts) {
        var map = new LinkedHashMap<String, AguiEvent.Interrupt>();
        for (var interrupt : interrupts) {
            map.put(interrupt.id(), interrupt);
        }
        return map;
    }
}
