package io.agentmanager.framework.sandbox.opensandbox;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.service.WorkspaceReader;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;

/**
 * 沙箱 → KV 回写同步服务。
 * 每次用户请求完成后调用：从沙箱 /workspace 拉取运行时文件（MEMORY.md、memory/），
 * 写入 DistributedStore.baseStore()（agent_fs 表），保证 Agent 服务无状态 + 记忆持久化。
 *
 * 命名空间与注入时一致：RemoteFilesystem(baseStore, List.of(userId))。
 */
public class WorkspaceSyncService {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceSyncService.class);

    private final BaseStore baseStore;

    public WorkspaceSyncService(BaseStore baseStore) {
        this.baseStore = baseStore;
    }

    /**
     * 每次用户请求完成后调用（同步 invoke 返回后 / 流式 AGENT_END 处）。
     * 从沙箱拉取 MEMORY.md + memory/*.md，写回 KV。
     */
    public void syncBack(String userId, com.alibaba.opensandbox.sandbox.Sandbox osbSandbox) {
        if (userId == null || userId.isBlank() || osbSandbox == null) {
            return;
        }
        try {
            var root = "/workspace/";
            var ctx = RuntimeContext.builder().userId(userId).build();
            var fs = new RemoteFilesystem(baseStore, List.of(userId));

            // 1. MEMORY.md
            try {
                if (osbSandbox.files().readByteArray(root + WorkspaceReader.MEMORY_FILE) != null) {
                    var content = osbSandbox.files().readFile(root + WorkspaceReader.MEMORY_FILE);
                    syncFile(fs, ctx, WorkspaceReader.MEMORY_FILE, content);
                }
            } catch (Exception e) {
                // 文件不存在则跳过（首次使用无记忆）
                log.debug("MEMORY.md sync skipped: {}", e.getMessage());
            }

            // 2. memory/*.md（listDirectory 返回沙箱内完整路径，如 /workspace/memory/xxx.md）
            try {
                var entries = osbSandbox.files().listDirectory(root + WorkspaceReader.MEMORY_DIR);
                if (entries != null) {
                    for (var entry : entries) {
                        if (!entry.getPath().endsWith(".md")) {
                            continue;
                        }
                        var sandboxPath = entry.getPath().startsWith("/")
                            ? entry.getPath() : root + entry.getPath();
                        var relPath = sandboxPath.substring(root.length());
                        var content = osbSandbox.files().readFile(sandboxPath);
                        syncFile(fs, ctx, relPath, content);
                    }
                }
            } catch (Exception e) {
                log.debug("memory/ sync skipped: {}", e.getMessage());
            }

            log.info("Workspace sync back completed for user {}", userId);
        } catch (Exception e) {
            // 回写失败不阻塞主流程：沙箱内数据保留，下次 call 或销毁前补偿
            log.warn("Workspace sync back failed for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * 回写单个文件：先读后编辑，遵循框架文件语义（不用 BaseStore 直写覆盖）。
     * - 文件不存在 → write（创建语义）
     * - 文件已存在 → edit（基于旧内容全量替换，内容不匹配时失败 = 并发检测）
     */
    private void syncFile(RemoteFilesystem fs, RuntimeContext ctx, String path, String content) {
        var read = fs.read(ctx, path, 0, -1);
        if (read.isSuccess() && read.fileData() != null && read.fileData().content() != null) {
            // 已存在：edit 全量替换（old 取 read 返回内容，与 edit 匹配基准一致）
            var er = fs.edit(ctx, path, read.fileData().content(), content, false);
            log.info("[sync-back] edit {} ({} bytes) success={} error={}",
                path, content != null ? content.length() : 0, er.isSuccess(), er.error());
        } else {
            // 不存在：write 创建
            var wr = fs.write(ctx, path, content);
            log.info("[sync-back] write {} ({} bytes) success={} error={}",
                path, content != null ? content.length() : 0, wr.isSuccess(), wr.error());
        }
    }
}
