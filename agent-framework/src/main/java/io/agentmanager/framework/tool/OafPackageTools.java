package io.agentmanager.framework.tool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.service.FileAssetStore;
import io.agentmanager.framework.service.storage.FileStorage;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * OAF 部署包生成链路工具（校验 + 打包登记）。
 *
 * <p>根据描述生成 OAF 部署包时：
 * <ol>
 *   <li>Agent 撰写 AGENTS.md 后先调 {@code check_oaf_package} 校验 frontmatter 必填字段
 *       （与平台 backend/internal/oaf 校验规则对齐：name / vendorKey / agentKey / version /
 *       description / author / license，kebab-case + semver）；valid=false 时按缺失清单修正重校验</li>
 *   <li>校验通过后调 {@code create_oaf_zip} 打包：JVM 侧组装 zip（AGENTS.md + 可选附加文件）、
 *       写入文件存储并登记 file_asset（origin=generated）——前端即刻出现下载卡片，
 *       返回 content_base64 供后续 upload_package 直接发布</li>
 * </ol>
 */
public class OafPackageTools {

    private static final Pattern KEBAB = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern SEMVER = Pattern.compile(
        "^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$");

    /** frontmatter 必填字段（平台 Validate 强制 7 字段非空） */
    private static final String[] REQUIRED_FIELDS =
        {"name", "vendorKey", "agentKey", "version", "description", "author", "license"};

    private final FileAssetStore fileAssetStore;
    private final FileStorage fileStorage;
    private final AgentManagerProperties props;

    public OafPackageTools() {
        this(null, null, null);
    }

    public OafPackageTools(FileAssetStore fileAssetStore,
                           FileStorage fileStorage,
                           AgentManagerProperties props) {
        this.fileAssetStore = fileAssetStore;
        this.fileStorage = fileStorage;
        this.props = props;
    }

    @Tool(
        name = "check_oaf_package",
        description = "Validate an OAF package AGENTS.md frontmatter BEFORE packaging/presenting. "
            + "Pass the full AGENTS.md text you just wrote. Returns JSON {valid, missing, invalid}: "
            + "valid=true only when all required fields are present and well-formed "
            + "(name/vendorKey/agentKey kebab-case, version semver). "
            + "MUST be called before present_file to avoid producing an unpublishable package.",
        concurrencySafe = true)
    public String checkOafPackage(
            @ToolParam(name = "agents_md", description = "Full AGENTS.md content (frontmatter + body)") String agentsMd) {
        if (agentsMd == null || agentsMd.isBlank()) {
            return json(false, Map.of("missing", java.util.List.of("AGENTS.md 内容为空")), "agents_md is empty");
        }
        // 解析 frontmatter：--- 包裹的 YAML 头部
        var fm = parseFrontmatter(agentsMd);
        if (fm == null) {
            return json(false, Map.of("invalid", java.util.List.of("frontmatter 缺失（必须以 --- 开头并以 --- 结尾声明头部字段）")),
                "frontmatter not found");
        }
        var missing = new java.util.ArrayList<String>();
        for (var field : REQUIRED_FIELDS) {
            var v = fm.get(field);
            if (v == null || v.isBlank()) {
                missing.add(field);
            }
        }
        var invalid = new java.util.ArrayList<String>();
        for (var field : new String[] {"name", "vendorKey", "agentKey"}) {
            var v = fm.get(field);
            if (v != null && !v.isBlank() && !KEBAB.matcher(v).matches()) {
                invalid.add(field + " 须为 kebab-case（小写字母/数字/连字符）");
            }
        }
        var ver = fm.get("version");
        if (ver != null && !ver.isBlank() && !SEMVER.matcher(ver).matches()) {
            invalid.add("version 须为 semver（如 1.0.0）");
        }
        boolean valid = missing.isEmpty() && invalid.isEmpty();
        return json(valid, Map.of(
            "missing", missing,
            "invalid", invalid,
            "present", new java.util.ArrayList<>(fm.keySet())),
            valid ? "OK" : "fix the issues and re-check");
    }

    /**
     * OAF zip 打包登记工具。
     *
     * <p>在 JVM 侧完成 zip 组装（AGENTS.md + 可选附加文件）→ 写入文件存储 →
     * 登记 file_asset（origin=generated），前端立即出现下载卡片（file_ready）。
     * 返回 JSON 含 file_id / file_name / size / content_base64（供 upload_package
     * 上传发布）。打包前自动做 frontmatter 校验，不通过则拒绝。
     */
    @Tool(
        name = "create_oaf_zip",
        description = "Create an OAF deployment package zip from an AGENTS.md (and optional extra files) "
            + "and register it for user download. Validates frontmatter first (same rules as check_oaf_package); "
            + "rejects if invalid. Returns JSON {file_id, file_name, mime_type, size, content_base64}: "
            + "file_id is shown as a download card in the UI; content_base64 can be passed to upload_package "
            + "to publish. extra_files is an optional JSON array [{\"path\":\"skills/x.md\",\"content\":\"...\"}].",
        concurrencySafe = true)
    public String createOafZip(
            @ToolParam(name = "package_name", description = "Zip file name, e.g. weather-agent.zip") String packageName,
            @ToolParam(name = "agents_md", description = "Full AGENTS.md content (frontmatter + body)") String agentsMd,
            @ToolParam(name = "extra_files", description = "Optional JSON array of extra files to include",
                    required = false) String extraFiles) {
        if (fileAssetStore == null || fileStorage == null || props == null) {
            return err("create_oaf_zip unavailable (not wired in this runtime)");
        }
        if (agentsMd == null || agentsMd.isBlank()) {
            return err("agents_md is empty");
        }
        var name = packageName == null || packageName.isBlank() ? "oaf-package.zip" : packageName;
        if (!name.toLowerCase().endsWith(".zip")) {
            name = name + ".zip";
        }
        // 1. 前置校验（与 check_oaf_package 同规则）
        var check = checkOafPackage(agentsMd);
        if (!check.contains("\"valid\":true")) {
            return err("AGENTS.md 校验未通过：" + check);
        }
        // 2. 组装 zip：AGENTS.md + 附加文件
        byte[] zipBytes;
        try {
            zipBytes = buildZip(agentsMd, extraFiles);
        } catch (Exception e) {
            return err("zip build failed: " + e.getMessage());
        }
        if (zipBytes.length == 0) {
            return err("zip build produced empty archive");
        }
        long maxBytes = props.file().presentMaxMb() * 1024L * 1024L;
        if (zipBytes.length > maxBytes) {
            return err("package exceeds " + props.file().presentMaxMb() + "MB limit (" + zipBytes.length + " bytes)");
        }
        // 3. 写存储 + 登记 file_asset（origin=generated，复用下载链路）
        var id = UUID.randomUUID().toString();
        var storageKey = "generated/" + java.time.LocalDate.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyyMM")) + "/" + id + "-" + name;
        try {
            fileStorage.write(storageKey, new ByteArrayInputStream(zipBytes), zipBytes.length, "application/zip");
        } catch (Exception e) {
            return err("storage write failed: " + e.getMessage());
        }
        try {
            fileAssetStore.insert(new FileAssetStore.FileAsset(
                id, "oaf-gen", null, name, name, "application/zip", zipBytes.length,
                props.file().storageType(), storageKey, "generated", "injected", LocalDateTime.now()));
        } catch (Exception e) {
            try {
                fileStorage.delete(storageKey);
            } catch (Exception ex) {
                // 回滚失败仅告警
            }
            return err("metadata write failed: " + e.getMessage());
        }
        var b64 = java.util.Base64.getEncoder().encodeToString(zipBytes);
        return "{\"file_id\":\"" + id + "\",\"file_name\":\"" + name
            + "\",\"mime_type\":\"application/zip\",\"size\":" + zipBytes.length
            + ",\"content_base64\":\"" + b64 + "\"}";
    }

    /** 组装 zip：AGENTS.md 固定第一项，附加文件按 extra_files JSON 数组写入 */
    private static byte[] buildZip(String agentsMd, String extraFiles) throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(bos)) {
            putEntry(zos, "AGENTS.md", agentsMd);
            if (extraFiles != null && !extraFiles.isBlank()) {
                var arr = parseExtraFiles(extraFiles);
                for (var e : arr) {
                    var path = e.get("path");
                    var content = e.get("content");
                    if (path == null || path.isBlank() || content == null) {
                        continue;
                    }
                    putEntry(zos, path, content);
                }
            }
        }
        return bos.toByteArray();
    }

    private static void putEntry(ZipOutputStream zos, String path, String content) throws Exception {
        var bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        zos.putNextEntry(new ZipEntry(path));
        zos.write(bytes);
        zos.closeEntry();
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Map<String, String>> parseExtraFiles(String json) throws Exception {
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        if (!node.isArray()) {
            throw new IllegalArgumentException("extra_files must be a JSON array");
        }
        var out = new java.util.ArrayList<Map<String, String>>();
        for (var item : node) {
            var m = new LinkedHashMap<String, String>();
            m.put("path", item.path("path").asText(null));
            m.put("content", item.path("content").asText(null));
            out.add(m);
        }
        return out;
    }

    private static String err(String msg) {
        return "{\"error\":\"" + escape(msg) + "\"}";
    }

    /** 解析 frontmatter（--- 首行开始，第二个 --- 结束），字段简单按 `key: value` 提取 */
    private static Map<String, String> parseFrontmatter(String agentsMd) {
        if (!agentsMd.trim().startsWith("---")) {
            return null;
        }
        var lines = agentsMd.split("\n", -1);
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            return null;
        }
        var fm = new LinkedHashMap<String, String>();
        for (int i = 1; i < end; i++) {
            var line = lines[i].trim();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            var key = line.substring(0, colon).trim();
            var val = line.substring(colon + 1).trim();
            // 去掉引号
            if (val.length() >= 2 && (val.startsWith("\"") && val.endsWith("\"")
                || val.startsWith("'") && val.endsWith("'"))) {
                val = val.substring(1, val.length() - 1);
            }
            fm.put(key, val);
        }
        return fm;
    }

    private static String json(boolean valid, Map<String, ?> extra, String note) {
        var sb = new StringBuilder("{\"valid\":").append(valid).append(",\"note\":\"")
            .append(escape(note)).append("\"");
        for (var e : extra.entrySet()) {
            sb.append(",\"").append(e.getKey()).append("\":").append(escapeJson(e.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String escapeJson(Object o) {
        if (o instanceof java.util.List<?> list) {
            var items = new java.util.ArrayList<String>();
            for (var it : list) {
                items.add("\"" + escape(String.valueOf(it)) + "\"");
            }
            return "[" + String.join(",", items) + "]";
        }
        return "\"" + escape(String.valueOf(o)) + "\"";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}