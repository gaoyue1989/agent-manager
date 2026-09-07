package io.agentmanager.framework.tool;

import java.util.Base64;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.sandbox.opensandbox.OpenSandboxFilesystemSpec;
import io.agentmanager.framework.service.FileAssetStore;
import io.agentmanager.framework.service.WorkspaceReader;
import io.agentmanager.framework.service.storage.FileStorage;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 文件产出登记工具（file-upload-download-plan §8.1）。
 *
 * <p>Agent 将处理后生成的报表/图片/文档经本工具登记到平台：字节落存储后端 +
 * file_asset 元数据行（origin=generated），随后 SSE 层合成 file_ready 帧供前端下载。
 *
 * <p>文件读取策略：
 * <ul>
 *   <li>非沙箱模式：file_path 为工作区相对路径，从 KV（agent_fs）读取</li>
 *   <li>沙箱模式：优先经 execd files API 从当前沙箱工作区直读（file_path 相对路径）；
 *       兼容旧方式 file_content_base64 显式传入（base64 -w0 /workspace/xxx）</li>
 * </ul>
 */
public class FileTools {

    private static final Logger log = LoggerFactory.getLogger(FileTools.class);

    private final FileAssetStore fileAssetStore;
    private final FileStorage fileStorage;
    private final AgentManagerProperties props;
    private final SandboxConfig sandboxConfig;
    private final WorkspaceReader workspaceReader;
    private final OpenSandboxFilesystemSpec sandboxFilesystemSpec;

    public FileTools(FileAssetStore fileAssetStore,
                     FileStorage fileStorage,
                     AgentManagerProperties props,
                     SandboxConfig sandboxConfig,
                     WorkspaceReader workspaceReader) {
        this(fileAssetStore, fileStorage, props, sandboxConfig, workspaceReader, null);
    }

    public FileTools(FileAssetStore fileAssetStore,
                     FileStorage fileStorage,
                     AgentManagerProperties props,
                     SandboxConfig sandboxConfig,
                     WorkspaceReader workspaceReader,
                     OpenSandboxFilesystemSpec sandboxFilesystemSpec) {
        this.fileAssetStore = fileAssetStore;
        this.fileStorage = fileStorage;
        this.props = props;
        this.sandboxConfig = sandboxConfig;
        this.workspaceReader = workspaceReader;
        this.sandboxFilesystemSpec = sandboxFilesystemSpec;
    }

    @Tool(
        name = "present_file",
        description = "Register a file produced in the workspace (report/image/document/zip package) to the platform "
            + "so the user can download it. MUST be called for any generated output file. "
            + "Provide file_path (workspace-relative, e.g. outputs/report.txt or /workspace/outputs/x.png). "
            + "file_content_base64 is OPTIONAL: in sandbox mode the file is read directly from the sandbox "
            + "workspace via the sandbox API (preferred); if direct read fails you may fall back to passing "
            + "file_content_base64 (get it via shell: base64 -w0 <path>). "
            + "Returns JSON with file_id/file_name/mime_type/size.",
        concurrencySafe = true)
    public String presentFile(
            RuntimeContext ctx,
            @ToolParam(name = "file_path", description = "Workspace path of the file to present")
                    String filePath,
            @ToolParam(name = "file_content_base64", description = "Base64-encoded file content (optional in sandbox mode)",
                    required = false) String fileContentBase64) {
        // 1. 路径校验：必须位于工作区内（沙箱 /workspace/ 前缀；非沙箱相对路径防 ../）
        var norm = normalizeWorkspacePath(filePath);
        if (norm == null) {
            return err("invalid path: " + filePath + " (must be inside workspace)");
        }
        var userKey = ctx != null && ctx.getUserId() != null && !ctx.getUserId().isBlank()
            ? ctx.getUserId() : (ctx != null && ctx.getSessionId() != null ? ctx.getSessionId() : "debug-user");
        // 2. 获取字节：显式 base64 优先；否则沙箱模式经 execd 直读，非沙箱从 KV 读
        byte[] bytes = null;
        if (fileContentBase64 != null && !fileContentBase64.isBlank()) {
            try {
                bytes = Base64.getDecoder().decode(fileContentBase64);
            } catch (IllegalArgumentException e) {
                return err("file_content_base64 is not valid base64");
            }
        } else if (sandboxConfig.enabled()) {
            // 沙箱直读：取当前会话沙箱（acquire 时注册到 spec），校验归属 userKey 防并发串沙箱
            var sandbox = sandboxFilesystemSpec != null ? sandboxFilesystemSpec.getLatestSandbox() : null;
            var boundKey = sandbox != null ? sandbox.getUserKey() : null;
            if (sandbox != null && (boundKey == null || boundKey.isBlank() || boundKey.equals(userKey))) {
                bytes = sandbox.readWorkspaceFile(norm);
            }
            if (bytes == null) {
                return err("sandbox mode: file not readable in sandbox workspace: " + norm
                    + " (make sure the file exists under /workspace; or pass file_content_base64 explicitly)");
            }
        } else {
            bytes = readFromWorkspace(ctx, norm);
            if (bytes == null) {
                return err("file not readable in workspace: " + norm);
            }
        }
        // 3. 大小限制（FILE_PRESENT_MAX_MB）
        long maxBytes = props.file().presentMaxMb() * 1024L * 1024L;
        if (bytes.length > maxBytes) {
            return err("file exceeds " + props.file().presentMaxMb() + "MB limit ("
                + bytes.length + " bytes)");
        }
        // 4. MIME 推断（扩展名兜底）
        var mime = guessMime(norm);
        // 5. 存储后端写入（key 按 §4.2，origin=generated）→ 落元数据 → 失败回滚
        var id = UUID.randomUUID().toString();
        var storageKey = "generated/" + java.time.LocalDate.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyyMM")) + "/" + id + "-" + basename(norm);
        try {
            fileStorage.write(storageKey, new java.io.ByteArrayInputStream(bytes), bytes.length, mime);
        } catch (Exception e) {
            log.warn("present_file: storage write failed: {}", e.getMessage());
            return err("storage write failed: " + e.getMessage());
        }
        try {
            fileAssetStore.insert(new FileAssetStore.FileAsset(
                id, userKey,
                ctx != null ? ctx.getSessionId() : null,
                basename(norm), norm, mime, bytes.length,
                props.file().storageType(), storageKey, "generated", "injected",
                java.time.LocalDateTime.now()));
        } catch (Exception e) {
            try {
                fileStorage.delete(storageKey);
            } catch (Exception ex) {
                log.warn("present_file: rollback storage failed: {}", ex.getMessage());
            }
            return err("metadata write failed: " + e.getMessage());
        }
        log.info("present_file: registered {} ({} bytes, mime={})", norm, bytes.length, mime);
        // 6. 返回 JSON（供 LLM 确认 + 供控制器合成 file_ready 帧）
        return "{\"file_id\":\"" + id + "\",\"file_name\":\"" + basename(norm)
            + "\",\"mime_type\":\"" + mime + "\",\"size\":" + bytes.length + "}";
    }

    /** 路径规范化：拒绝 ../ 逃逸；统一返回工作区相对路径（无前导 /workspace） */
    private String normalizeWorkspacePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        var p = filePath.replace('\\', '/');
        if (p.startsWith("/workspace/")) {
            p = p.substring("/workspace/".length());
        } else if (p.startsWith("/")) {
            // 非 /workspace 绝对路径 → 拒绝
            return null;
        }
        if (p.contains("..") || p.startsWith("../") || p.contains("/../")) {
            return null;
        }
        return p;
    }

    /** 非沙箱模式：从本地工作区读文件（实测文件在 {workspace}/.agentscope/workspace/{sessionId}/，非 KV） */
    private byte[] readFromWorkspace(RuntimeContext ctx, String relPath) {
        var userKey = ctx != null && ctx.getUserId() != null && !ctx.getUserId().isBlank()
            ? ctx.getUserId() : (ctx != null && ctx.getSessionId() != null ? ctx.getSessionId() : null);
        if (userKey == null) {
            return null;
        }
        return workspaceReader.readWorkspaceFile(userKey, relPath);
    }

    /** 扩展名 → MIME 推断（兜底 application/octet-stream） */
    private String guessMime(String path) {
        var lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    private static String basename(String path) {
        var p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private static String err(String msg) {
        return "{\"error\":\"" + msg.replace("\"", "'") + "\"}";
    }
}