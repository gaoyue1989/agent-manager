package io.agentmanager.framework.tool;

import java.util.Base64;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.service.SandboxRuntime;
import io.agentmanager.framework.service.SessionKeyResolver;
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
 *   <li>非沙箱模式（{@link SandboxAwareFileTools} 之外的注册形态）：file_path 为
 *       工作区相对路径，从 KV（agent_fs）读取，兼容 file_content_base64 显式传入</li>
 *   <li>沙箱模式：{@link SandboxAwareFileTools} 覆写 presentFile，
 *       仅经 execd files API 从当前沙箱工作区直读（file_path 相对路径）。
 *       不提供 file_content_base64 回退——大文件 base64 需 LLM 在 tool call 参数里
 *       复述全文，超输出上限即截断，工具报错后 file_ready 静默丢失</li>
 * </ul>
 */
public class FileTools implements io.agentmanager.framework.tool.CustomTool {

    private static final Logger log = LoggerFactory.getLogger(FileTools.class);

    private final FileAssetStore fileAssetStore;
    private final FileStorage fileStorage;
    private final AgentManagerProperties props;
    private final SandboxRuntime sandboxRuntime;
    private final WorkspaceReader workspaceReader;
    private final OpenSandboxFilesystemSpec sandboxFilesystemSpec;
    /** 业务规范键解析（issue #52）：沙箱归属校验须与 SandboxUserKeyMiddleware 同源反查真实用户 */
    private final SessionKeyResolver sessionKeyResolver;

    public FileTools(FileAssetStore fileAssetStore,
                     FileStorage fileStorage,
                     AgentManagerProperties props,
                     SandboxRuntime sandboxRuntime,
                     WorkspaceReader workspaceReader) {
        this(fileAssetStore, fileStorage, props, sandboxRuntime, workspaceReader, null, null);
    }

    public FileTools(FileAssetStore fileAssetStore,
                     FileStorage fileStorage,
                     AgentManagerProperties props,
                     SandboxRuntime sandboxRuntime,
                     WorkspaceReader workspaceReader,
                     OpenSandboxFilesystemSpec sandboxFilesystemSpec) {
        this(fileAssetStore, fileStorage, props, sandboxRuntime, workspaceReader, sandboxFilesystemSpec, null);
    }

    public FileTools(FileAssetStore fileAssetStore,
                     FileStorage fileStorage,
                     AgentManagerProperties props,
                     SandboxRuntime sandboxRuntime,
                     WorkspaceReader workspaceReader,
                     OpenSandboxFilesystemSpec sandboxFilesystemSpec,
                     SessionKeyResolver sessionKeyResolver) {
        this.fileAssetStore = fileAssetStore;
        this.fileStorage = fileStorage;
        this.props = props;
        this.sandboxRuntime = sandboxRuntime;
        this.workspaceReader = workspaceReader;
        this.sandboxFilesystemSpec = sandboxFilesystemSpec;
        this.sessionKeyResolver = sessionKeyResolver;
    }

    /**
     * 解析沙箱归属校验键：经 SessionKeyResolver 反查真实用户（与 SandboxUserKeyMiddleware
     * 同源，issue #52）。沙箱实例绑定的是规范用户键——若此处用 RuntimeContext 原值（Channel
     * 链路下是网关 peer=前端 sessionId），归属比对恒不等 → 沙箱直读被误拒、file_ready 丢失。
     * 反查不可用/未命中时回落原值（userId 优先，空则 sessionId，兜底 debug-user）。
     */
    private String resolveUserKey(RuntimeContext ctx) {
        if (sessionKeyResolver != null && ctx != null) {
            var real = sessionKeyResolver.realUserId(ctx);
            if (real != null && !real.isBlank()) {
                return real;
            }
        }
        if (ctx != null && ctx.getUserId() != null && !ctx.getUserId().isBlank()) {
            return ctx.getUserId();
        }
        if (ctx != null && ctx.getSessionId() != null && !ctx.getSessionId().isBlank()) {
            return ctx.getSessionId();
        }
        return "debug-user";
    }

    @Tool(
        name = "present_file",
        description = "Register a file produced in the workspace (report/image/document/zip package) to the platform "
            + "so the user can download it. MUST be called for any generated output file. "
            + "Provide file_path (workspace-relative, e.g. outputs/report.txt or /workspace/outputs/x.png); "
            + "the file content is read directly from the workspace by the platform. "
            + "Returns JSON with file_id/file_name/mime_type/size.",
        concurrencySafe = true)
    public String presentFile(
            RuntimeContext ctx,
            @ToolParam(name = "file_path", description = "Workspace path of the file to present")
                    String filePath,
            @ToolParam(name = "file_content_base64", description = "Base64-encoded file content (optional; non-sandbox only)",
                    required = false) String fileContentBase64) {
        // 1. 路径校验：必须位于工作区内（沙箱 /workspace/ 前缀；非沙箱相对路径防 ../）
        var norm = normalizeWorkspacePath(filePath);
        if (norm == null) {
            return err("invalid path: " + filePath + " (must be inside workspace)");
        }
        var userKey = resolveUserKey(ctx);
        // 2. 获取字节：显式 base64 仅非沙箱合法（KV 工作区可能没有 LLM 未落盘的内容）；
        //    沙箱模式一律拒绝——LLM 复述大文件 base64 必超输出上限截断，file_ready 静默丢失
        byte[] bytes = null;
        if (fileContentBase64 != null && !fileContentBase64.isBlank()) {
            if (sandboxRuntime.enabled()) {
                return err("file_content_base64 is not supported in sandbox mode; "
                    + "the platform reads the file directly from the sandbox workspace via file_path");
            }
            try {
                bytes = Base64.getDecoder().decode(fileContentBase64);
            } catch (IllegalArgumentException e) {
                return err("file_content_base64 is not valid base64");
            }
        } else if (sandboxRuntime.enabled()) {
            // 沙箱直读：取当前会话沙箱（acquire 时注册到 spec），校验归属 userKey 防并发串沙箱
            // （沙箱部署的注册形态是 SandboxAwareFileTools：schema 无 base64 参数，
            //  此分支仅为防御直读兜底——例如手工反射调用）
            var sandbox = sandboxFilesystemSpec != null ? sandboxFilesystemSpec.getLatestSandbox() : null;
            var boundKey = sandbox != null ? sandbox.getUserKey() : null;
            if (sandbox != null && (boundKey == null || boundKey.isBlank() || boundKey.equals(userKey))) {
                bytes = sandbox.readWorkspaceFile(norm);
            }
            if (bytes == null) {
                return err("sandbox mode: file not readable in sandbox workspace: " + norm
                    + " (make sure the file exists under /workspace; check the path with list_files)");
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
                null,  // sessionId: 工具层不写，由控制器层 emitFileReadyViaEventBus 回写业务 peer
                null,  // replyId: 工具层无此信息，由控制器层 file_ready 合成时回写
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

    /**
     * 外部交付物登记工具（与 present_file 同构的 file_ready 卡片链路，见
     * docs/design/oaf-tools-extraction-design.md）。
     *
     * <p>产物本体在外部系统（如平台 create_oaf_zip 返回的包 download_url），本工具只登记
     * file_asset（storage_type=external，storage_key=URL），不写文件存储后端；卡片实时
     * file_ready 与历史回放统一走 GET /files/{id} 的服务端代理下载（前缀白名单防 SSRF）。
     */
    @Tool(
        name = "present_url",
        description = "Register an EXTERNAL resource (an http(s) URL produced by another system, "
            + "e.g. the download_url returned by the create_oaf_zip platform tool) as a "
            + "user-downloadable deliverable card. MUST be called to deliver any externally-hosted "
            + "artifact; the card appears immediately and in session history replay. "
            + "The url must match the configured external prefix allowlist. "
            + "Returns JSON with file_id/file_name/mime_type/size.",
        concurrencySafe = true)
    public String presentUrl(
        RuntimeContext ctx,
        @ToolParam(name = "file_name", description = "Display/download file name, e.g. weather-agent.zip")
                String fileName,
        @ToolParam(name = "url", description = "External http(s) URL of the artifact (e.g. download_url from create_oaf_zip)")
                String url,
        @ToolParam(name = "mime_type", description = "MIME type (optional, default application/octet-stream)",
                required = false) String mimeType,
        @ToolParam(name = "size", description = "Artifact size in bytes if known (optional)",
                required = false) Long size) {
        var prefixes = props.file().externalUrlPrefixList();
        if (prefixes.isEmpty()) {
            return err("present_url unavailable: no external URL prefixes configured (FILE_EXTERNAL_URL_PREFIXES)");
        }
        if (url == null || url.isBlank()) {
            return err("url is required");
        }
        var u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            return err("url must be http(s): " + u);
        }
        // 前缀白名单（SSRF 收敛）：仅允许已配置前缀下的 URL
        var allowed = prefixes.stream().anyMatch(p -> u.equals(p) || u.startsWith(p + "/"));
        if (!allowed) {
            return err("url not in configured external prefixes: " + u);
        }
        var name = sanitizeDisplayName(fileName);
        if (name == null) {
            return err("invalid file_name: " + fileName);
        }
        var userKey = resolveUserKey(ctx);
        var mime = mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType.trim();
        long sz = size == null ? 0L : size;
        // 幂等复用：uk_storage(storage_type, storage_key) 全局唯一，同 URL 重复交付复用既有 file_id
        //（file_ready 合成时会回写最新 reply_id/session_id，卡片关联跟随最近一次交付）
        var id = UUID.randomUUID().toString();
        try {
            fileAssetStore.insert(new FileAssetStore.FileAsset(
                id, userKey, null, null, name, name, mime, sz,
                "external", u, "generated", "injected", java.time.LocalDateTime.now()));
        } catch (Exception e) {
            var existing = fileAssetStore.findByStorage("external", u);
            if (existing.isPresent()) {
                id = existing.get().id();
            } else {
                return err("metadata write failed: " + e.getMessage());
            }
        }
        log.info("present_url: registered {} -> {}", name, u);
        return "{\"file_id\":\"" + id + "\",\"file_name\":\"" + escape(name)
            + "\",\"mime_type\":\"" + escape(mime) + "\",\"size\":" + sz + "}";
    }

    /** 展示文件名 sanitize：basename、去控制字符、非空 */
    private static String sanitizeDisplayName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        var base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[\\p{Cntrl}]", "_").trim();
        return base.isBlank() || ".".equals(base) || "..".equals(base) ? null : base;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
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

    /**
     * 非沙箱模式：从 KV 工作区读文件。
     *
     * <p><b>候选键顺序</b>（e2e-ciplan §11.3 D3 的第三处断裂）：控制器的 write_file→KV 同步
     * 用的是**请求体 userId**，而工具侧 RuntimeContext 在 Channel 链路下 userId 被网关改写为
     * peer（=sessionId）。两者不一致时读不到刚写入的文件。这里按 (userId, sessionId) 依次尝试，
     * 命中任一即返回——两条链路（直接 userId / 网关 peer）都能读到。
     */
    private byte[] readFromWorkspace(RuntimeContext ctx, String relPath) {
        if (ctx == null) {
            return null;
        }
        var candidates = new java.util.LinkedHashSet<String>();
        // ① RuntimeContext.userId（直接调用/普通链路即请求体 userId）
        if (ctx.getUserId() != null && !ctx.getUserId().isBlank()) {
            candidates.add(ctx.getUserId());
        }
        // ② sessionId（网关 peer 即 rawSessionId）
        if (ctx.getSessionId() != null && !ctx.getSessionId().isBlank()) {
            candidates.add(ctx.getSessionId());
        }
        // ③ kv_sync_key 反查：write_file→KV 同步登记的写入侧 userKey
        //    （Channel 链路下 ctx.userId 是网关 peer，拿不到请求体 userId——D3 修复）
        try {
            fileAssetStore.findKvSyncKey(relPath).ifPresent(candidates::add);
            // ④ 上传文件：按文件名 + 会话候选反查 file_asset
            var name = relPath.contains("/") ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
            fileAssetStore.findUserKeyByFileName(name, candidates.toArray(String[]::new))
                .ifPresent(candidates::add);
        } catch (Exception e) {
            log.debug("present_file: userKey lookup failed for {}: {}", relPath, e.getMessage());
        }
        for (var key : candidates) {
            var bytes = workspaceReader.readWorkspaceFile(key, relPath);
            if (bytes != null) {
                return bytes;
            }
        }
        return null;
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
        var safe = msg.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
        return "{\"error\":\"" + safe + "\"}";
    }

    /**
     * 沙箱部署专用注册形态：{@code present_file} 无 {@code file_content_base64} 参数，
     * 仅经 execd files API 从沙箱工作区直读。
     *
     * <p>为什么不复用基类方法签名：工具 JSON schema 由 {@code @ToolParam} 注解在注册期
     * 静态生成（Toolkit 反射 getDeclaredMethods），而沙箱开关注定于部署级
     * （{@code SandboxRuntime} 启动时三层裁决、进程内不变）——由
     * {@code AgentScopeConfig#customTools} 按模式二选一注册。
     *
     * <p>移除该参数的原因：base64 回退要求 LLM 在 tool call 参数里复述文件全文
     * （1MB 文件 ≈ 1.37MB base64 文本），必然超过输出 token 上限被截断，解码失败后
     * 工具返回 error，file_ready 合成静默跳过——用户视角即「大文件生成完没有下载卡片」。
     * 沙箱直读不经 LLM，无此上限。
     */
    public static class SandboxAwareFileTools extends FileTools {

        public SandboxAwareFileTools(FileAssetStore fileAssetStore,
                                            FileStorage fileStorage,
                                            AgentManagerProperties props,
                                            SandboxRuntime sandboxRuntime,
                                            WorkspaceReader workspaceReader,
                                            OpenSandboxFilesystemSpec sandboxFilesystemSpec,
                                            SessionKeyResolver sessionKeyResolver) {
            super(fileAssetStore, fileStorage, props, sandboxRuntime, workspaceReader,
                sandboxFilesystemSpec, sessionKeyResolver);
        }

        @Tool(
            name = "present_file",
            description = "Register a file produced in the sandbox workspace (report/image/document/zip package) "
                + "to the platform so the user can download it. MUST be called for any generated output file. "
                + "Provide file_path (workspace-relative, e.g. outputs/report.pptx or /workspace/outputs/x.png); "
                + "the file content is read directly from the sandbox workspace by the platform "
                + "(no content parameter needed, works for large binaries). "
                + "Returns JSON with file_id/file_name/mime_type/size.",
            concurrencySafe = true)
        public String presentFile(
                RuntimeContext ctx,
                @ToolParam(name = "file_path", description = "Workspace path of the file to present")
                        String filePath) {
            // 与父类 3 参形态构成重载（少一个参数）：getDeclaredMethods 只取子类声明，
            // Toolkit 恰好只注册这个单参数 schema
            return super.presentFile(ctx, filePath, null);
        }

        /**
         * Toolkit.registerTool 反射只扫 getDeclaredMethods（不含继承），present_url 必须
         * 在子类重新声明，否则沙箱部署丢失该工具（与 present_file 同构的纯透传）。
         */
        @Tool(
            name = "present_url",
            description = "Register an EXTERNAL resource (an http(s) URL produced by another system, "
                + "e.g. the download_url returned by the create_oaf_zip platform tool) as a "
                + "user-downloadable deliverable card. MUST be called to deliver any externally-hosted "
                + "artifact; the card appears immediately and in session history replay. "
                + "The url must match the configured external prefix allowlist. "
                + "Returns JSON with file_id/file_name/mime_type/size.",
            concurrencySafe = true)
        public String presentUrl(
                RuntimeContext ctx,
                @ToolParam(name = "file_name", description = "Display/download file name, e.g. weather-agent.zip")
                        String fileName,
                @ToolParam(name = "url", description = "External http(s) URL of the artifact (e.g. download_url from create_oaf_zip)")
                        String url,
                @ToolParam(name = "mime_type", description = "MIME type (optional, default application/octet-stream)",
                        required = false) String mimeType,
                @ToolParam(name = "size", description = "Artifact size in bytes if known (optional)",
                        required = false) Long size) {
            // 同签名覆写：仅为让 getDeclaredMethods 能看到（注册透传，语义全在父类）
            return super.presentUrl(ctx, fileName, url, mimeType, size);
        }
    }
}