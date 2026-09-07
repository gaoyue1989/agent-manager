package io.agentmanager.framework.controller;

import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.service.FileAssetStore;
import io.agentmanager.framework.service.storage.FileStorage;

/**
 * 文件上传/下载端点（file-upload-download-plan §5/§9）。
 *
 * <p>POST /files/upload —— multipart 上传：校验 → 先写存储后端（原子）→ 落元数据
 * （先存储后落库，落库失败回滚存储对象，防孤儿，§5.3）。
 *
 * <p>GET /files/{fileId} —— 下载/预览：file_asset 元数据查行 + FileStorage 流式转发；
 * 无鉴权（平台无认证，UUID 不可枚举即授权，§9 已确认）。
 */
@RestController
@RequestMapping("/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    /** 存储 key 前缀时间格式：upload/202509/{uuid}-{sanitizedFileName} */
    private static final DateTimeFormatter KEY_MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final FileStorage fileStorage;
    private final FileAssetStore fileAssetStore;
    private final AgentManagerProperties props;
    private final SandboxConfig sandboxConfig;

    public FileController(FileStorage fileStorage, FileAssetStore fileAssetStore,
                          AgentManagerProperties props, SandboxConfig sandboxConfig) {
        this.fileStorage = fileStorage;
        this.fileAssetStore = fileAssetStore;
        this.props = props;
        this.sandboxConfig = sandboxConfig;
    }

    /**
     * multipart 文件上传。
     *
     * @param file      单文件（v1）
     * @param userId    可选，默认 "debug-user"（与 chat 一致；沙箱注入命名空间）
     * @param sessionId 可选，上传时绑定会话
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        var cfg = props.file();
        if (!cfg.uploadEnabled()) {
            return err(HttpStatus.FORBIDDEN, "upload_disabled", "file upload is disabled");
        }
        if (file == null || file.isEmpty()) {
            return err(HttpStatus.BAD_REQUEST, "no_file_uploaded", "file is required");
        }
        // 文件名 sanitize（basename 截断、去反斜杠、UTF-8 ≤255 字节）
        var rawName = file.getOriginalFilename();
        var fileName = sanitizeFileName(rawName);
        if (fileName == null || fileName.isBlank()) {
            return err(HttpStatus.BAD_REQUEST, "invalid_file_name", "invalid file name: " + rawName);
        }
        // MIME 白名单
        var mime = file.getContentType();
        if (mime == null || mime.isBlank() || !mimeAllowed(mime, cfg.uploadAllowedMime())) {
            return err(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_file_type", "unsupported mime: " + mime);
        }
        // 大小上限
        long maxBytes = cfg.uploadMaxMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            return err(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
                "file exceeds " + cfg.uploadMaxMb() + "MB limit");
        }
        var key = resolveUserKey(userId);

        // 数量上限（软限制：并发上传可能少量超发）
        int pending = fileAssetStore.countPending(key);
        if (pending >= cfg.uploadMaxPending()) {
            return err(HttpStatus.TOO_MANY_REQUESTS, "too_many_pending_files",
                "pending files exceed " + cfg.uploadMaxPending());
        }

        // 先写存储后端（原子）→ 落元数据 → 失败回滚（§5.3）
        var id = UUID.randomUUID().toString();
        var storageKey = buildStorageKey("upload", fileName);
        try {
            fileStorage.write(storageKey, file.getInputStream(), file.getSize(), mime);
        } catch (IOException e) {
            log.warn("upload storage write failed ({}): {}", fileName, e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "storage_write_failed", e.getMessage());
        }
        // 非沙箱模式无注入对象 → 直接 injected；沙箱模式 pending 挂账待首 exec 注入（§6.2）
        var status = isSandboxMode() ? "pending" : "injected";
        try {
            fileAssetStore.insert(new FileAssetStore.FileAsset(
                id, key, sessionId, fileName, null, mime, file.getSize(),
                props.file().storageType(), storageKey, "upload", status,
                java.time.LocalDateTime.now()));
        } catch (Exception e) {
            // 落库失败 → best-effort 回滚存储对象（孤儿清扫兜底，P2）
            try {
                fileStorage.delete(storageKey);
            } catch (IOException ex) {
                log.warn("rollback storage object failed ({}): {}", storageKey, ex.getMessage());
            }
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "metadata_write_failed", e.getMessage());
        }

        var resp = new LinkedHashMap<String, Object>();
        resp.put("file_id", id);
        resp.put("file_name", fileName);
        resp.put("mime_type", mime);
        resp.put("size", file.getSize());
        return ResponseEntity.ok(resp);
    }

    /** 下载/预览：file_asset 元数据 + FileStorage 流式转发 */
    @GetMapping("/{fileId}")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String fileId,
            @RequestParam(value = "inline", required = false, defaultValue = "0") int inline) {
        var cfg = props.file();
        if (!cfg.downloadEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        var meta = fileAssetStore.get(fileId);
        if (meta.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var asset = meta.get();
        try {
            if (!fileStorage.exists(asset.storageKey())) {
                log.warn("download: storage object missing for file {}", fileId);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            }
        } catch (IOException e) {
            log.warn("download: storage probe failed for {}: {}", fileId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }

        // 响应头：Content-Type/Content-Length/Content-Disposition（inline 仅 image/text）
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(asset.mimeType()));
        headers.setContentLength(asset.size());
        boolean forceInline = inline == 1
            && (asset.mimeType().startsWith("image/") || asset.mimeType().startsWith("text/"));
        String disposition = forceInline ? "inline" : "attachment";
        headers.set(HttpHeaders.CONTENT_DISPOSITION, disposition
            + "; filename*=UTF-8''" + urlEncode(asset.fileName()));
        headers.set("X-Content-Type-Options", "nosniff");

        var key = asset.storageKey();
        StreamingResponseBody body = out -> {
            try (InputStream in = fileStorage.read(key)) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /** 沙箱模式判断（agent.sandbox.enabled）：上传文件 pending 挂账待沙箱注入 */
    private boolean isSandboxMode() {
        return sandboxConfig != null && sandboxConfig.enabled();
    }

    /** userId 规范化：空值降级 "debug-user"（与 chat 一致） */
    private static String resolveUserKey(String userId) {
        return (userId == null || userId.isBlank()) ? "debug-user" : userId;
    }

    /**
     * 文件名 sanitize（对齐 DeerFlow normalize_filename）：
     * 取 basename、去反斜杠、UTF-8 ≤255 字节。
     */
    static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        var base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        // 去除控制字符与路径分隔残留
        base = base.replaceAll("[\\p{Cntrl}]", "_");
        if (base.isBlank() || ".".equals(base) || "..".equals(base)) {
            return null;
        }
        var bytes = base.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 255) {
            base = new String(bytes, 0, 255, java.nio.charset.StandardCharsets.UTF_8);
        }
        return base;
    }

    /** MIME 白名单匹配：逗号分隔，支持 * 通配（如 image/*、application/vnd.openxmlformats-officedocument.*） */
    static boolean mimeAllowed(String mime, String allowedList) {
        if (allowedList == null || allowedList.isBlank()) {
            return false;
        }
        var lower = mime.toLowerCase(Locale.ROOT);
        for (var item : allowedList.split(",")) {
            var pat = item.trim().toLowerCase(Locale.ROOT);
            if (pat.isEmpty()) {
                continue;
            }
            if (pat.endsWith("*")) {
                // 通配形态 xxx/*：前缀保留结尾斜杠（image/、...officedocument.）
                var prefix = pat.substring(0, pat.length() - 1);
                if (lower.startsWith(prefix)) {
                    return true;
                }
            } else if (pat.equals(lower)) {
                return true;
            }
        }
        return false;
    }

    /** 存储 key：{origin}/{yyyyMM}/{uuid}-{sanitizedFileName}（§4.2） */
    static String buildStorageKey(String origin, String fileName) {
        var month = java.time.LocalDate.now().format(KEY_MONTH);
        return origin + "/" + month + "/" + UUID.randomUUID() + "-" + fileName;
    }

    /** 文件名 URL 编码（RFC 5987 filename*） */
    private static String urlEncode(String name) {
        try {
            return java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        } catch (Exception e) {
            return "file";
        }
    }

    private static ResponseEntity<Map<String, Object>> err(HttpStatus status, String code, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}