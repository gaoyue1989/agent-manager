package io.agentmanager.framework.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 文件资产元数据存储（file_asset 表，file-upload-download-plan §4.3）。
 *
 * <p>DB 只存元数据（storage_type + storage_key + 状态），字节在 FileStorage 后端。
 * status 状态机（仅沙箱模式使用）：pending（待注入沙箱）→ injected（已注入当前沙箱代）；
 * 非沙箱模式上传即置 injected。origin=upload（用户上传）/ generated（Agent 产出）。
 */
@Service
public class FileAssetStore {

    private static final Logger log = LoggerFactory.getLogger(FileAssetStore.class);

    private final DataSource dataSource;

    public FileAssetStore(DataSource dataSource) {
        this.dataSource = dataSource;
        initSchema();
    }

    /** 元数据行（字节不在 DB，见类注释） */
    public record FileAsset(
        String id,
        String userKey,
        String sessionId,
        String replyId,
        String fileName,
        String workspacePath,
        String mimeType,
        long size,
        String storageType,
        String storageKey,
        String origin,
        String status,
        LocalDateTime createdAt
    ) {}

    /** 建表（幂等），失败 fail-fast */
    private void initSchema() {
        try (var conn = dataSource.getConnection();
             var stmt0 = conn.createStatement()) {
            stmt0.execute("""
                CREATE TABLE IF NOT EXISTS kv_sync_key (
                  rel_path VARCHAR(512) NOT NULL,
                  user_key VARCHAR(255) NOT NULL,
                  updated_at DATETIME NOT NULL,
                  PRIMARY KEY (rel_path)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        } catch (Exception e) {
            log.warn("FileAssetStore: kv_sync_key init skipped: {}", e.getMessage());
        }
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS file_asset (
                  id             VARCHAR(36)  PRIMARY KEY,
                  user_key       VARCHAR(255) NOT NULL,
                  session_id     VARCHAR(255),
                  reply_id       VARCHAR(64),
                  file_name      VARCHAR(255) NOT NULL,
                  workspace_path VARCHAR(512),
                  mime_type      VARCHAR(128) NOT NULL,
                  size           BIGINT       NOT NULL,
                  storage_type   VARCHAR(16)  NOT NULL,
                  storage_key    VARCHAR(512) NOT NULL,
                  origin         VARCHAR(16)  NOT NULL,
                  status         VARCHAR(16)  NOT NULL DEFAULT 'pending',
                  created_at     DATETIME(3)  NOT NULL,
KEY idx_user_status (user_key, status),
              KEY idx_session (session_id, created_at),
              KEY idx_origin_status (origin, status, created_at),
              KEY idx_reply (reply_id),
              UNIQUE KEY uk_storage (storage_type, storage_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
            // 幂等加列：reply_id（已有表时 ALTER）
            try {
                stmt.executeUpdate("ALTER TABLE file_asset ADD COLUMN reply_id VARCHAR(64) DEFAULT NULL AFTER session_id");
                stmt.executeUpdate("ALTER TABLE file_asset ADD KEY idx_reply (reply_id)");
                log.info("FileAssetStore: added reply_id column to file_asset");
            } catch (Exception alterEx) {
                // 列已存在则忽略
            }
            log.info("FileAssetStore: file_asset table ready");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init file_asset table: " + e.getMessage(), e);
        }
    }

    /** 插入元数据（存储对象已写入后再调用；status 由调用方指定） */
    public void insert(FileAsset asset) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 INSERT INTO file_asset
                   (id, user_key, session_id, reply_id, file_name, workspace_path, mime_type, size,
                    storage_type, storage_key, origin, status, created_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(3))
                 """)) {
            stmt.setString(1, asset.id());
            stmt.setString(2, asset.userKey());
            stmt.setString(3, asset.sessionId());
            stmt.setString(4, asset.replyId());
            stmt.setString(5, asset.fileName());
            stmt.setString(6, asset.workspacePath());
            stmt.setString(7, asset.mimeType());
            stmt.setLong(8, asset.size());
            stmt.setString(9, asset.storageType());
            stmt.setString(10, asset.storageKey());
            stmt.setString(11, asset.origin());
            stmt.setString(12, asset.status());
            stmt.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to insert file_asset: " + e.getMessage(), e);
        }
    }

    /** 主键查询（下载/消息构造入口） */
    public Optional<FileAsset> get(String id) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("SELECT * FROM file_asset WHERE id = ?")) {
            stmt.setString(1, id);
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            log.warn("FileAssetStore: get {} failed: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 按文件名 + 会话候选反查资产所属 userKey（present_file 定位写入侧命名空间用）。
     *
     * <p>KV 工作区命名空间由写入时的 userKey 决定；工具侧 RuntimeContext 在 Channel 链路下
     * 被网关改写为 peer，拿不到请求体 userId。此查询从落库记录取回原始 userKey（D3 修复）。
     */
    public java.util.Optional<String> findUserKeyByFileName(String fileName, String... sessionCandidates) {
        if (fileName == null || fileName.isBlank()) {
            return java.util.Optional.empty();
        }
        var placeholders = sessionCandidates.length == 0
            ? "NULL"
            : String.join(",", java.util.Collections.nCopies(sessionCandidates.length, "?"));
        var sql = "SELECT user_key FROM file_asset WHERE file_name = ?"
            + " AND (session_id IS NULL OR session_id IN (" + placeholders + "))"
            + " ORDER BY created_at DESC LIMIT 1";
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            int i = 1;
            stmt.setString(i++, fileName);
            for (var s : sessionCandidates) {
                stmt.setString(i++, s);
            }
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? java.util.Optional.ofNullable(rs.getString("user_key"))
                    : java.util.Optional.empty();
            }
        } catch (Exception e) {
            log.warn("FileAssetStore: findUserKeyByFileName {} failed: {}", fileName, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * 登记 write_file→KV 同步的 (相对路径 → userKey) 映射（跨副本可查）。
     *
     * <p>present_file 读 KV 时需要写入侧命名空间 key，而工具侧 RuntimeContext 在 Channel
     * 链路下 userId 被网关改写为 peer——此表把写入键持久化，读取端按路径反查（D3 修复）。
     * 幂等：同一路径覆盖更新。
     */
    public void upsertKvSyncKey(String relPath, String userKey) {
        if (relPath == null || relPath.isBlank() || userKey == null || userKey.isBlank()) {
            return;
        }
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 INSERT INTO kv_sync_key (rel_path, user_key, updated_at) VALUES (?, ?, NOW())
                 ON DUPLICATE KEY UPDATE user_key = VALUES(user_key), updated_at = NOW()
                 """)) {
            stmt.setString(1, relPath);
            stmt.setString(2, userKey);
            stmt.executeUpdate();
        } catch (Exception e) {
            log.warn("FileAssetStore: upsertKvSyncKey {} failed: {}", relPath, e.getMessage());
        }
    }

    /** 按相对路径反查 KV 同步 userKey（无记录返回 empty） */
    public java.util.Optional<String> findKvSyncKey(String relPath) {
        if (relPath == null || relPath.isBlank()) {
            return java.util.Optional.empty();
        }
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT user_key FROM kv_sync_key WHERE rel_path = ?")) {
            stmt.setString(1, relPath);
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? java.util.Optional.ofNullable(rs.getString("user_key"))
                    : java.util.Optional.empty();
            }
        } catch (Exception e) {
            log.warn("FileAssetStore: findKvSyncKey {} failed: {}", relPath, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /** 该 user_key 或 session 下 status=pending 的文件（沙箱注入批次；兼容上传时未绑定会话） */
    public List<FileAsset> listPending(String userKey, String sessionId) {
        var result = new ArrayList<FileAsset>();
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 SELECT * FROM file_asset
                 WHERE status = 'pending' AND (user_key = ? OR (session_id IS NOT NULL AND session_id = ?))
                 ORDER BY created_at
                 """)) {
            stmt.setString(1, userKey);
            stmt.setString(2, sessionId != null ? sessionId : "");
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
        } catch (Exception e) {
            log.warn("FileAssetStore: listPending {} failed: {}", userKey, e.getMessage());
        }
        return result;
    }

    /** 该 user_key 下 pending 文件数（上传限流软校验） */
    public int countPending(String userKey) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM file_asset WHERE user_key = ? AND status = 'pending'")) {
            stmt.setString(1, userKey);
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            log.warn("FileAssetStore: countPending {} failed: {}", userKey, e.getMessage());
            return 0;
        }
    }

    /** 注入成功后批量置 injected（仅影响 pending → injected 转移，幂等） */
    public void markInjected(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "UPDATE file_asset SET status = 'injected' WHERE id = ? AND status = 'pending'")) {
            for (var id : ids) {
                stmt.setString(1, id);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (Exception e) {
            log.warn("FileAssetStore: markInjected failed: {}", e.getMessage());
        }
    }

    /** 注入成功后回写实际工作区路径（含唯一化后缀） */
    public void updateWorkspacePath(String id, String workspacePath) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "UPDATE file_asset SET workspace_path = ? WHERE id = ?")) {
            stmt.setString(1, workspacePath);
            stmt.setString(2, id);
            stmt.executeUpdate();
        } catch (Exception e) {
            log.warn("FileAssetStore: updateWorkspacePath {} failed: {}", id, e.getMessage());
        }
    }

    /**
     * 沙箱容器销毁/新建时回滚注入状态（§6.2.4 双保险）：
     * 该 user_key 或 session 下 origin=upload 且 status=injected 的行 → pending，新容器重新注入。
     */
    public void resetInjectedToPending(String userKey) {
        resetInjectedToPending(userKey, userKey);
    }

    /** 双维度回滚（user_key 与 session_id 都覆盖——上传记录 user_key 为请求 userId，沙箱隔离键为 sessionId） */
    public void resetInjectedToPending(String userKey, String sessionId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("""
                 UPDATE file_asset SET status = 'pending'
                 WHERE origin = 'upload' AND status = 'injected'
                   AND (user_key = ? OR (session_id IS NOT NULL AND session_id = ?))
                 """)) {
            stmt.setString(1, userKey);
            stmt.setString(2, sessionId != null ? sessionId : "");
            var n = stmt.executeUpdate();
            if (n > 0) {
                log.info("FileAssetStore: reset {} file(s) injected→pending for user {}", n, userKey);
            }
        } catch (Exception e) {
            log.warn("FileAssetStore: resetInjectedToPending {} failed: {}", userKey, e.getMessage());
        }
    }

    /** 控制器层合成 file_ready 后回写 reply_id（按主键更新，幂等） */
    public void updateReplyId(String id, String replyId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "UPDATE file_asset SET reply_id = ? WHERE id = ?")) {
            stmt.setString(1, replyId);
            stmt.setString(2, id);
            stmt.executeUpdate();
        } catch (Exception e) {
            log.warn("FileAssetStore: updateReplyId {} failed: {}", id, e.getMessage());
        }
    }

    /** 控制器层合成 file_ready 后回写业务 sessionId（peer），与 replyId 回写时机一致 */
    public void updateSessionId(String id, String sessionId) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "UPDATE file_asset SET session_id = ? WHERE id = ?")) {
            stmt.setString(1, sessionId);
            stmt.setString(2, id);
            stmt.executeUpdate();
        } catch (Exception e) {
            log.warn("FileAssetStore: updateSessionId {} failed: {}", id, e.getMessage());
        }
    }

    private static FileAsset map(ResultSet rs) throws SQLException {
        return new FileAsset(
            rs.getString("id"),
            rs.getString("user_key"),
            rs.getString("session_id"),
            rs.getString("reply_id"),
            rs.getString("file_name"),
            rs.getString("workspace_path"),
            rs.getString("mime_type"),
            rs.getLong("size"),
            rs.getString("storage_type"),
            rs.getString("storage_key"),
            rs.getString("origin"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toLocalDateTime());
    }
}