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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.service.SandboxRuntime;
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
    /** fileId 格式：UUID（用于 download 端点入参校验） */
    private static final java.util.regex.Pattern FILE_ID_PATTERN =
        java.util.regex.Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private final FileStorage fileStorage;
    private final FileAssetStore fileAssetStore;
    private final AgentManagerProperties props;
    private final SandboxRuntime sandboxRuntime;

    public FileController(FileStorage fileStorage, FileAssetStore fileAssetStore,
                          AgentManagerProperties props, SandboxRuntime sandboxRuntime) {
        this.fileStorage = fileStorage;
        this.fileAssetStore = fileAssetStore;
        this.props = props;
        this.sandboxRuntime = sandboxRuntime;
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
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {
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
        // 扩展名与 MIME 交叉校验：防止攻击者伪造 Content-Type 绕过白名单
        if (!extensionConsistentWithMime(fileName, mime)) {
            return err(HttpStatus.BAD_REQUEST, "extension_mime_mismatch",
                "file extension does not match declared mime type: " + fileName + " vs " + mime);
        }
        // 大小上限
        long maxBytes = cfg.uploadMaxMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            return err(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
                "file exceeds " + cfg.uploadMaxMb() + "MB limit");
        }
        var key = resolveUserKey(userId, headerUserId);

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
                id, key, sessionId, null, fileName, null, mime, file.getSize(),
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
        // fileId 格式校验：应为 UUID（防路径注入/遍历）
        if (!FILE_ID_PATTERN.matcher(fileId).matches()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        var meta = fileAssetStore.get(fileId);
        if (meta.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var asset = meta.get();
        // 外部交付物（storage_type=external）：服务端代理拉取——实时 file_ready 与历史回放
        // 统一走 /files/{id} 单一 URL，前端无需感知外部地址；白名单收敛防 SSRF
        if ("external".equals(asset.storageType())) {
            return proxyExternal(asset, inline);
        }
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

    /** 外部交付物代理下载客户端（连接池共享；超时收敛防慢端点拖死容器线程） */
    private static final java.net.http.HttpClient EXTERNAL_CLIENT = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(5))
        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
        .build();

    /**
     * 外部交付物代理：GET storage_key 指向的 http(s) URL 并流式转发。
     * URL 来源为 present_url 工具登记（LLM 可控），必须经 FILE_EXTERNAL_URL_PREFIXES
     * 前缀白名单校验后才发起请求（SSRF 收敛）；上游非 2xx 返回 502。
     */
    private ResponseEntity<StreamingResponseBody> proxyExternal(FileAssetStore.FileAsset asset, int inline) {
        var url = asset.storageKey();
        var prefixes = props.file().externalUrlPrefixList();
        boolean allowed = prefixes.stream().anyMatch(p -> url.equals(p) || url.startsWith(p + "/"));
        if (!allowed) {
            log.warn("download: external url not allowlisted for file {}: {}", asset.id(), url);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        java.net.http.HttpResponse<InputStream> upstream;
        try {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(60))
                .GET()
                .build();
            upstream = EXTERNAL_CLIENT.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            log.warn("download: external fetch failed for file {}: {}", asset.id(), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        if (upstream.statusCode() / 100 != 2) {
            try {
                upstream.body().close();
            } catch (IOException ignored) {
                // 关闭失败无需处理
            }
            log.warn("download: external upstream {} for file {}", upstream.statusCode(), asset.id());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
        var headers = new HttpHeaders();
        var contentType = upstream.headers().firstValue("Content-Type").orElse(asset.mimeType());
        try {
            headers.setContentType(MediaType.parseMediaType(contentType));
        } catch (Exception e) {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }
        upstream.headers().firstValue("Content-Length").map(Long::parseLong)
            .ifPresent(headers::setContentLength);
        boolean forceInline = inline == 1
            && (contentType.startsWith("image/") || contentType.startsWith("text/"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, (forceInline ? "inline" : "attachment")
            + "; filename*=UTF-8''" + urlEncode(asset.fileName()));
        headers.set("X-Content-Type-Options", "nosniff");
        var body = upstream.body();
        StreamingResponseBody stream = out -> {
            try (InputStream in = body) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok().headers(headers).body(stream);
    }

    /** 沙箱模式判断（agent.sandbox.enabled）：上传文件 pending 挂账待沙箱注入 */
    private boolean isSandboxMode() {
        return sandboxRuntime.enabled();
    }

    /** userId 规范化：网关 Header 优先，空值降级 "debug-user"（与 chat 一致） */
    private static String resolveUserKey(String userId, String headerUserId) {
        if (headerUserId != null && !headerUserId.isBlank()) {
            return headerUserId;
        }
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

    /**
     * 扩展名与 MIME 一致性校验（防伪造 Content-Type 绕过白名单）。
     * 原则：扩展名必须在 MIME 类型对应的扩展名集合内，否则拒绝。
     * 对于无法映射的扩展名，仅允许 application/octet-stream（通用二进制）。
     */
    static boolean extensionConsistentWithMime(String fileName, String mime) {
        var dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            // 无扩展名：仅允许 application/octet-stream 等通用类型
            return "application/octet-stream".equals(mime);
        }
        var ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        var expected = MIME_TO_EXTENSIONS.get(mime.toLowerCase(Locale.ROOT));
        if (expected != null) {
            return expected.contains(ext);
        }
        // 未知 MIME 类型但扩展名可识别 → 拒绝（白名单已筛选，此处兜底）
        // 已知扩展名但 MIME 不在映射中 → 也拒绝（如 .exe 声明为 image/png）
        return KNOWN_DANGEROUS_EXTENSIONS.contains(ext) ? false : true;
    }

    /** MIME → 允许的扩展名集合（与白名单对齐） */
    private static final java.util.Map<String, java.util.Set<String>> MIME_TO_EXTENSIONS = java.util.Map.ofEntries(
        // image/*
        java.util.Map.entry("image/png", java.util.Set.of("png", "apng")),
        java.util.Map.entry("image/jpeg", java.util.Set.of("jpg", "jpeg", "jfif")),
        java.util.Map.entry("image/gif", java.util.Set.of("gif")),
        java.util.Map.entry("image/webp", java.util.Set.of("webp")),
        java.util.Map.entry("image/svg+xml", java.util.Set.of("svg")),
        java.util.Map.entry("image/bmp", java.util.Set.of("bmp")),
        java.util.Map.entry("image/x-icon", java.util.Set.of("ico")),
        java.util.Map.entry("image/tiff", java.util.Set.of("tif", "tiff")),
        // text/*
        java.util.Map.entry("text/plain", java.util.Set.of("txt", "md", "log", "csv", "tsv")),
        java.util.Map.entry("text/markdown", java.util.Set.of("md", "markdown")),
        java.util.Map.entry("text/csv", java.util.Set.of("csv", "tsv")),
        java.util.Map.entry("text/html", java.util.Set.of("html", "htm")),
        java.util.Map.entry("text/css", java.util.Set.of("css")),
        // application/*
        java.util.Map.entry("application/pdf", java.util.Set.of("pdf")),
        java.util.Map.entry("application/json", java.util.Set.of("json")),
        java.util.Map.entry("application/zip", java.util.Set.of("zip")),
        java.util.Map.entry("application/gzip", java.util.Set.of("gz", "gzip", "tgz")),
        java.util.Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            java.util.Set.of("docx")),
        java.util.Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            java.util.Set.of("xlsx")),
        java.util.Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation",
            java.util.Set.of("pptx")),
        java.util.Map.entry("application/vnd.ms-excel", java.util.Set.of("xls")),
        java.util.Map.entry("application/vnd.ms-powerpoint", java.util.Set.of("ppt")),
        java.util.Map.entry("application/vnd.ms-word", java.util.Set.of("doc")),
        java.util.Map.entry("application/xml", java.util.Set.of("xml")),
        java.util.Map.entry("application/javascript", java.util.Set.of("js", "mjs")),
        java.util.Map.entry("application/octet-stream", java.util.Set.of("bin", "dat", "pkg", "dmg"))
    );

    /** 已知危险扩展名：即使 MIME 声明为安全类型也不允许 */
    private static final java.util.Set<String> KNOWN_DANGEROUS_EXTENSIONS = java.util.Set.of(
        "exe", "bat", "cmd", "ps1", "vbs", "js", "wsf", "msi", "scr", "com",
        "dll", "sys", "reg", "inf", "hta", "cpl", "msp", "mst"
    );

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