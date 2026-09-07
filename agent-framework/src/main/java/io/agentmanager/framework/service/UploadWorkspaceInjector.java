package io.agentmanager.framework.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.service.storage.FileStorage;

/**
 * 上传文件 → 工作区注入（file-upload-download-plan §6）。
 *
 * <p>非沙箱模式：文件系统根为 <code>{workspace}/.agentscope/workspace/{sessionId}/</code>
 * （SDK 按会话隔离的本地目录 + SQLite 索引，实测 write_file/read_file 落于此），
 * chat 请求处理时（sendStream 前）把上传字节写入该目录
 * <code>uploads/{唯一化文件名}</code>，并回写 file_asset.workspace_path。
 *
 * <p>沙箱模式：不在此注入（无沙箱句柄），由 OpenSandbox.doExec 首执行前
 * 经 execd files API 注入（见 §6.2 与 OpenSandbox.injectPendingUploadsIfNeeded）。
 */
@Service
public class UploadWorkspaceInjector {

    private static final Logger log = LoggerFactory.getLogger(UploadWorkspaceInjector.class);

    private final FileStorage fileStorage;
    private final FileAssetStore fileAssetStore;
    private final AgentManagerProperties props;

    public UploadWorkspaceInjector(FileStorage fileStorage,
                                   FileAssetStore fileAssetStore,
                                   AgentManagerProperties props) {
        this.fileStorage = fileStorage;
        this.fileAssetStore = fileAssetStore;
        this.props = props;
    }

    /**
     * 非沙箱模式下将文件注入会话工作区 uploads/ 目录（本地磁盘）。
     *
     * @param workspaceKey SDK 隔离 key（Channel 链路 = sessionId，实测文件根 {root}/{sessionId}/）
     * @return 实际工作区路径（含唯一化后缀）；文件不存在/读取失败返回 null（调用方降级）
     */
    public String injectToWorkspace(String fileId, String workspaceKey) {
        var meta = fileAssetStore.get(fileId);
        if (meta.isEmpty()) {
            log.warn("inject: file_asset {} not found", fileId);
            return null;
        }
        var asset = meta.get();
        byte[] bytes;
        try (var in = fileStorage.read(asset.storageKey())) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            log.warn("inject: storage read failed for {}: {}", fileId, e.getMessage());
            return null;
        }
        var root = workspaceRoot(workspaceKey);
        var wsPath = uniqueWorkspacePath(root, asset.fileName());
        try {
            var target = root.resolve(wsPath);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            // 注入成功后才回写 workspace_path（保证路径与工作区内实际一致）
            fileAssetStore.updateWorkspacePath(fileId, wsPath);
            log.info("inject: uploaded {} → {}", asset.fileName(), wsPath);
            return wsPath;
        } catch (Exception e) {
            log.warn("inject: workspace write failed for {}: {}", fileId, e.getMessage());
            return null;
        }
    }

    /** SDK 会话文件系统根：{workspace}/.agentscope/workspace/{sessionId} */
    java.nio.file.Path workspaceRoot(String workspaceKey) {
        var base = Path.of(props.resolvedWorkspaceBaseDir(), ".agentscope", "workspace");
        return base.resolve(workspaceKey).toAbsolutePath().normalize();
    }

    /**
     * 唯一化工作区文件名：同名文件追加 _1/_2（DeerFlow claim_unique_filename 同款）。
     * 基于目录 ls 判断冲突（不覆盖，防静默覆盖历史上传）。
     */
    private String uniqueWorkspacePath(java.nio.file.Path root, String fileName) {
        var base = "uploads/" + fileName;
        if (!Files.isDirectory(root.resolve("uploads"))) {
            return base;
        }
        try (var stream = Files.list(root.resolve("uploads"))) {
            var existing = stream.map(p -> "uploads/" + p.getFileName().toString())
                .collect(java.util.stream.Collectors.toSet());
            if (!existing.contains(base)) {
                return base;
            }
            int dot = fileName.lastIndexOf('.');
            var stem = dot > 0 ? fileName.substring(0, dot) : fileName;
            var ext = dot > 0 ? fileName.substring(dot) : "";
            for (int i = 1; ; i++) {
                var candidate = "uploads/" + stem + "_" + i + ext;
                if (!existing.contains(candidate)) {
                    return candidate;
                }
            }
        } catch (IOException e) {
            log.warn("inject: unique name probe failed, fallback {}", base);
            return base;
        }
    }

    /**
     * 构造用户消息 ContentBlock 列表（file-upload-download-plan §7.2）：
     * 图片（≤FILE_IMAGE_MAX_MB 且总预算内）→ ImageBlock(Base64Source) 内联；
     * 其余 → 路径提示文本。
     *
     * @return 追加块 + 路径提示块（供 SessionStreamController 拼装 Msg）
     */
    public List<io.agentscope.core.message.ContentBlock> buildContentBlocks(
            List<String> fileIds, String message, String userKey) {
        var cfg = props.file();
        var blocks = new ArrayList<io.agentscope.core.message.ContentBlock>();
        if (message != null && !message.isBlank()) {
            blocks.add(io.agentscope.core.message.TextBlock.builder().text(message).build());
        }
        long imageInlineBytes = 0;
        long maxSingle = cfg.imageMaxMb() * 1024L * 1024L;
        long totalBudget = cfg.imageInlineTotalMb() * 1024L * 1024L;
        var pathHints = new ArrayList<String>();
        if (fileIds != null) {
            for (var fileId : fileIds) {
                var meta = fileAssetStore.get(fileId);
                if (meta.isEmpty()) {
                    log.warn("buildContent: file_asset {} not found, skip", fileId);
                    continue;
                }
                var asset = meta.get();
                String mime = asset.mimeType();
                if (mime != null && mime.startsWith("image/")
                        && asset.size() <= maxSingle
                        && imageInlineBytes + asset.size() <= totalBudget) {
                    byte[] bytes = readAll(asset);
                    if (bytes != null) {
                        blocks.add(io.agentscope.core.message.ImageBlock.builder()
                            .source(io.agentscope.core.message.Base64Source.builder()
                                .mediaType(mime)
                                .data(Base64.getEncoder().encodeToString(bytes))
                                .build())
                            .build());
                        imageInlineBytes += asset.size();
                        continue;
                    }
                    // 读取失败 → 降级路径提示（不阻断消息构造）
                }
                var wsPath = asset.workspacePath() != null ? asset.workspacePath()
                    : "uploads/" + asset.fileName();
                pathHints.add(wsPath);
            }
        }
        if (!pathHints.isEmpty()) {
            blocks.add(io.agentscope.core.message.TextBlock.builder().text(
                "用户上传了文件（工作区相对路径）：\n- " + String.join("\n- ", pathHints)
                    + "\n请用 read_file / list_files 工具或沙箱脚本处理这些文件。")
                .build());
        }
        return blocks;
    }

    /** 从存储后端读全部字节（失败返回 null 由调用方降级） */
    private byte[] readAll(FileAssetStore.FileAsset asset) {
        try (var in = fileStorage.read(asset.storageKey())) {
            return in.readAllBytes();
        } catch (IOException e) {
            log.warn("buildContent: storage read failed for {}: {}", asset.id(), e.getMessage());
            return null;
        }
    }
}