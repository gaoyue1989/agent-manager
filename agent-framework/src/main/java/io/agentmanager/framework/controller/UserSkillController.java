package io.agentmanager.framework.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.agentmanager.framework.service.SandboxRuntime;
import io.agentmanager.framework.service.UserSkillService;

/**
 * 用户技能（L4）管理 API：当前用户个人技能覆盖的列表/读取/写入/上传/下载/删除/从包内下发。
 *
 * <p><b>用户身份来自 {@code X-User-Id} 请求头</b>（网关注入的登录态用户；缺失/非法 → 400），
 * 不再从 URL 路径取 userId——前端无需自报 userId，也不存在跨用户越权面（网关层负责鉴权）。
 * 「所有用户」索引是运维/调试用途，仅保留 {@code GET /debug/user-skills}（DebugApiController）。
 *
 * <p>存储位置是 agent_fs KV（{@code agents/{agent}/users/{uid}/skills}），与 SkillManageController
 * 的 {@code /config/skills}（L2 包内文件，PVC）是两层不同存储，故不复用其路由。
 *
 * <p>路由歧义（既有窄边界，集群实测口径）：{@code /skills/users/{name}} 与
 * {@code /skills/{name}/content}、{@code /skills/{name}/toggle} 在 {@code name} 恰为
 * {@code content} / {@code toggle} 时同时匹配，由更具体的后者命中——个人技能名若恰为
 * {@code content}/{@code toggle} 会被包内技能端点吞掉。属既有路由下的窄边界，正常技能名不受影响。
 *
 * <p><b>生效范围分档</b>（成功提示按 {@link SandboxRuntime#enabled()} 区分，避免运维误判）：
 * <ul>
 *   <li>非沙箱档（SANDBOX_ENABLED=false）：推理直接读 agent_fs 的 L4，同名技能 L4 覆盖 L2，
 *       写入/删除下一轮会话生效；</li>
 *   <li>沙箱档（SANDBOX_ENABLED=true）：会话读的是容器内 {@code /workspace/skills} 副本，
 *       管理面只写 agent_fs KV，由「会话开始物化 L4」在该用户下一个 turn 投影进容器生效。</li>
 * </ul>
 *
 * <p>沙箱档的两个 KV 仲裁标记（沙箱回写命中即跳过同名技能，避免管理面操作被容器内旧副本改回）：
 * PUT/上传/下发写 {@code /{name}/.admin-override}，DELETE 写 {@code /{name}/.deleted}。
 * 代价是标记生效期间该技能在容器内的 {@code skill_manage} 修改不再落库，
 * 清除方式随 PUT/DELETE 响应与列表的 {@code tombstones} 字段显式下发。
 */
@RestController
@RequestMapping("/skills/users")
public class UserSkillController {
    private static final Logger log = LoggerFactory.getLogger(UserSkillController.class);

    /** 网关注入的登录态用户头 */
    private static final String USER_ID_HEADER = "X-User-Id";

    /** 删除标记（tombstone）的清除方式：随删除响应下发，避免运维把“标记仍在”当成“技能已恢复” */
    private static final String TOMBSTONE_CLEAR_HINT =
        "管理面重新写入（PUT /skills/users/{name}）或从包内下发（POST /skills/users/{name}"
            + "/sync-from-package）会清除标记";

    private final UserSkillService userSkillService;
    private final SandboxRuntime sandboxRuntime;

    public UserSkillController(UserSkillService userSkillService, SandboxRuntime sandboxRuntime) {
        this.userSkillService = userSkillService;
        this.sandboxRuntime = sandboxRuntime;
    }

    /** 沙箱档提示（写在成功消息里，避免“显示已生效、实际不回注容器”的误判） */
    private boolean sandboxMode() {
        return sandboxRuntime.enabled();
    }

    /**
     * 用户身份校验：{@code X-User-Id} 缺失 → 400 {@code missing_user_id}；非法 → 400 {@code invalid_user_id}。
     *
     * @return null 表示通过；否则返回应直接下发的 400 响应
     */
    private static ResponseEntity<Map<String, Object>> userIdError(String userId) {
        if (userId == null || userId.isBlank()) {
            return err(HttpStatus.BAD_REQUEST, "missing_user_id",
                "缺少 " + USER_ID_HEADER + " 请求头");
        }
        if (!UserSkillService.isValidUserId(userId)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_user_id", "无效的用户标识");
        }
        return null;
    }

    /** 列出当前用户（X-User-Id）的个人技能（L4），含“删除后回落的包内基线”与 tombstone 标记 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listSkills(
            @RequestHeader(value = USER_ID_HEADER, required = false) String headerUserId) {
        var invalid = userIdError(headerUserId);
        if (invalid != null) {
            return invalid;
        }
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("userId", headerUserId);
            body.put("skills", userSkillService.listSkills(headerUserId));
            body.put("tombstones", userSkillService.listTombstones(headerUserId));
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            // KV 枚举失败必须显式报错（不能返回“该用户没有个人技能”这种降级结果）
            log.warn("User skill list failed for {}: {}", headerUserId, e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "list_failed", "读取失败: " + e.getMessage());
        }
    }

    /**
     * 读取当前用户某技能的文件内容：L4 优先，无 L4 时回落包内基线。
     *
     * <p>KV 读取失败 → 500（不能把“存储不可用”静默报成 not_found 404）；
     * 两侧都不存在才是 404。
     *
     * @param file 技能内相对路径（默认 SKILL.md，只读；写入固定为 SKILL.md）
     */
    @GetMapping("/{name}")
    public ResponseEntity<Map<String, Object>> getSkill(
            @RequestHeader(value = USER_ID_HEADER, required = false) String headerUserId,
            @PathVariable String name,
            @RequestParam(name = "file", required = false) String file) {
        var invalid = userIdError(headerUserId);
        if (invalid != null) {
            return invalid;
        }
        if (!UserSkillService.isValidSkillName(name)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_name", "无效的 Skill 名称");
        }
        if (file != null && !file.isBlank() && !UserSkillService.isValidSkillFilePath(file)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_file_path", "无效的技能文件路径");
        }
        Optional<UserSkillService.UserSkillContent> content;
        try {
            content = userSkillService.readSkill(headerUserId, name, file);
        } catch (Exception e) {
            log.warn("User skill read failed for {}/{}: {}", headerUserId, name, e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "read_failed", "读取失败: " + e.getMessage());
        }
        if (content.isEmpty()) {
            return err(HttpStatus.NOT_FOUND, "not_found",
                "技能 '" + name + "' 在用户 " + headerUserId + " 与包内均不存在");
        }
        var view = content.get();
        var body = new LinkedHashMap<String, Object>();
        body.put("userId", view.userId());
        body.put("name", view.name());
        body.put("file", (file == null || file.isBlank()) ? "SKILL.md" : file);
        body.put("content", view.content());
        body.put("source", view.source());
        body.put("hasUserOverride", view.hasUserOverride());
        body.put("version", view.version());
        body.put("files", view.files());
        // 该用户是否另有个人覆盖（source=package 时也可能为 true：请求的文件只在包内）
        body.put("userOverrideExists", view.userOverrideExists());
        return ResponseEntity.ok(body);
    }

    /**
     * 下载当前用户某技能的整目录为 zip。
     *
     * <p>源与列表/读取同口径：有 L4 个人覆盖则打包 L4，否则回落包内（L2）基线；
     * 两个源都不存在 → 404。文件名 {@code {name}.zip}（RFC 5987 filename* 编码，支持中文）。
     */
    @GetMapping("/{name}/download")
    public ResponseEntity<?> downloadSkill(
            @RequestHeader(value = USER_ID_HEADER, required = false) String headerUserId,
            @PathVariable String name) {
        var invalid = userIdError(headerUserId);
        if (invalid != null) {
            return invalid;
        }
        if (!UserSkillService.isValidSkillName(name)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_name", "无效的 Skill 名称");
        }
        Optional<byte[]> zip;
        try {
            zip = userSkillService.exportSkillZip(headerUserId, name);
        } catch (Exception e) {
            log.warn("User skill export failed for {}/{}: {}", headerUserId, name, e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "download_failed", "下载失败: " + e.getMessage());
        }
        if (zip.isEmpty()) {
            return err(HttpStatus.NOT_FOUND, "not_found",
                "技能 '" + name + "' 在用户 " + headerUserId + " 与包内均不存在");
        }
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentLength(zip.get().length);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename*=UTF-8''" + urlEncode(name + ".zip"));
        headers.set("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(headers).body(zip.get());
    }

    /**
     * 上传 zip 包作为当前用户的个人技能（L4）：解压 → 定位 SKILL.md → 以包内清单全量替换该技能。
     *
     * <p>技能名取自 SKILL.md frontmatter {@code name}，缺省回落包裹目录名 / zip 文件名；
     * 同名技能<b>覆盖</b>。zip 支持扁平（根目录含 SKILL.md）与包裹（一层子目录含 SKILL.md）两种结构。
     *
     * <p>体积上限 {@link UserSkillService#MAX_ZIP_BYTES}（10MB），解压后条目数/总大小由
     * {@link io.agentmanager.framework.service.SkillZipSupport} 兜底。<b>含二进制/非 UTF-8 文件
     * 拒绝整个包</b>（400）——KV 只存字符串，静默替换会损坏数据。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadSkillZip(
            @RequestHeader(value = USER_ID_HEADER, required = false) String headerUserId,
            @RequestParam("file") MultipartFile file) {
        var invalid = userIdError(headerUserId);
        if (invalid != null) {
            return invalid;
        }
        if (file == null || file.isEmpty()) {
            return err(HttpStatus.BAD_REQUEST, "empty_file", "请提供 zip 文件");
        }
        if (file.getSize() > UserSkillService.MAX_ZIP_BYTES) {
            return err(HttpStatus.PAYLOAD_TOO_LARGE, "zip_too_large",
                "zip 包超过 " + (UserSkillService.MAX_ZIP_BYTES / 1024 / 1024) + "MB 限制");
        }
        try (var in = file.getInputStream()) {
            var outcome = userSkillService.uploadSkillZip(headerUserId, in, file.getOriginalFilename());
            var resp = new LinkedHashMap<String, Object>();
            resp.put("userId", headerUserId);
            resp.put("name", outcome.name());
            resp.put("action", outcome.action());
            resp.put("files", outcome.files());
            resp.put("version", outcome.version());
            resp.put("message", "用户技能 '" + outcome.name() + "' 已"
                + ("created".equals(outcome.action()) ? "创建" : "覆盖更新")
                + "（" + outcome.files().size() + " 个文件，已写入 agent_fs）"
                + (sandboxMode()
                    ? "；当前 SANDBOX_ENABLED=true，该用户下一个 turn 开始时物化进容器 /workspace/skills 生效"
                    : "，用户 " + headerUserId + " 下轮会话生效"));
            return ResponseEntity.ok(resp);
        } catch (UserSkillService.ContentTooLargeException e) {
            return err(HttpStatus.PAYLOAD_TOO_LARGE, "content_too_large", e.getMessage());
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.BAD_REQUEST, "invalid_zip", e.getMessage());
        } catch (Exception e) {
            log.warn("User skill zip upload failed for {}: {}", headerUserId, e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "upload_failed", "上传失败: " + e.getMessage());
        }
    }

    /**
     * 新建/覆盖当前用户的技能主文件（SKILL.md）。
     *
     * <p>提示文案分档：非沙箱档“下轮会话生效”；沙箱档只写 agent_fs KV，由会话开始物化 L4 投影进容器。
     */
    @PutMapping("/{name}")
    public ResponseEntity<Map<String, Object>> putSkill(
            @RequestHeader(value = USER_ID_HEADER, required = false) String headerUserId,
            @PathVariable String name,
            @RequestBody(required = false) Map<String, String> body) {
        var invalid = userIdError(headerUserId);
        if (invalid != null) {
            return invalid;
        }
        if (!UserSkillService.isValidSkillName(name)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_name", "无效的 Skill 名称");
        }
        var content = body == null ? null : body.get("content");
        if (content == null || content.isBlank()) {
            return err(HttpStatus.BAD_REQUEST, "empty_content", "内容不能为空");
        }
        try {
            var outcome = userSkillService.writeSkill(headerUserId, name, content);
            var resp = new LinkedHashMap<String, Object>();
            resp.put("userId", headerUserId);
            resp.put("name", name);
            resp.put("action", outcome.action());
            resp.put("version", outcome.version());
            resp.put("message", "用户技能 '" + name + "' 已"
                + ("created".equals(outcome.action()) ? "创建" : "更新")
                + "（已写入 agent_fs）"
                + (sandboxMode()
                    ? "；当前 SANDBOX_ENABLED=true，该写入已落 agent_fs 并置管理面写入栅栏——"
                      + "同代容器内旧副本在下次 call 结束时不会把它改回容器版本（代价：该技能在容器内用"
                      + "skill_manage 的后续修改同样不再回写落库，删除该技能可清除栅栏）；"
                      + "会话开始物化 L4 已启用：该用户下一个 turn 开始时会把本写入投影进容器 /workspace/skills 生效"
                    : "，用户 " + headerUserId + " 下轮会话生效"));
            return ResponseEntity.ok(resp);
        } catch (UserSkillService.ContentTooLargeException e) {
            return err(HttpStatus.PAYLOAD_TOO_LARGE, "content_too_large", e.getMessage());
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.BAD_REQUEST, "invalid_content", e.getMessage());
        } catch (Exception e) {
            log.warn("User skill write failed for {}/{}: {}", headerUserId, name, e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "write_failed", "写入失败: " + e.getMessage());
        }
    }

    /**
     * 删除当前用户的个人技能覆盖（全部文件）。
     *
     * <p>提示按“删除后是否有包内基线可回落”区分（{@link UserSkillService#hasPackageBaseline}），
     * 沙箱档另行提示容器内副本的存活边界。
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> deleteSkill(
            @RequestHeader(value = USER_ID_HEADER, required = false) String headerUserId,
            @PathVariable String name) {
        var invalid = userIdError(headerUserId);
        if (invalid != null) {
            return invalid;
        }
        if (!UserSkillService.isValidSkillName(name)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_name", "无效的 Skill 名称");
        }
        try {
            var deleted = userSkillService.deleteSkill(headerUserId, name);
            if (deleted.isEmpty()) {
                return err(HttpStatus.NOT_FOUND, "not_found",
                    "用户 " + headerUserId + " 无个人技能 '" + name + "'");
            }
            var hasBaseline = userSkillService.hasPackageBaseline(name);
            var resp = new LinkedHashMap<String, Object>();
            resp.put("userId", headerUserId);
            resp.put("name", name);
            resp.put("deletedFiles", deleted.get());
            resp.put("hasPackageBaseline", hasBaseline);
            // 删除标记可见性：标记无 TTL，用户在同代容器内重建同名技能会被回写侧一直跳过，
            // 该后果与清除方式必须随响应显式下发（否则表现为“技能明明存了却不落库”）
            resp.put("tombstone", Map.of("name", name, "clearHint", TOMBSTONE_CLEAR_HINT));
            resp.put("message", "用户技能 '" + name + "' 已删除（已写入删除标记防止回写复活；"
                + TOMBSTONE_CLEAR_HINT + "），用户 "
                + headerUserId + (hasBaseline ? " 回落包内基线" : " 无包内同名技能，该技能已消失")
                + "；标记生效期间该用户在同代（及后续）容器内用 skill_manage 重建同名技能不会被回写落库"
                + (sandboxMode()
                    ? "；当前 SANDBOX_ENABLED=true，tombstone 生效：下一次会话物化时不再写回容器"
                      + "（容器内旧副本在容器换代前仍可能对该用户会话可见）"
                    : ""));
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
        } catch (Exception e) {
            log.warn("User skill delete failed for {}/{}: {}", headerUserId, name, e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "delete_failed", "删除失败: " + e.getMessage());
        }
    }

    /**
     * 把包内（L2）同名技能整目录下发为当前用户的个人版本（含 scripts 等资源文件）。
     *
     * <p>以包内清单为准全量替换（差集清理多余旧文件）+ 失败回滚；非 UTF-8/二进制文件显式跳过，
     * 跳过清单在 {@code skipped} 字段返回。
     */
    @PostMapping("/{name}/sync-from-package")
    public ResponseEntity<Map<String, Object>> syncFromPackage(
            @RequestHeader(value = USER_ID_HEADER, required = false) String headerUserId,
            @PathVariable String name) {
        var invalid = userIdError(headerUserId);
        if (invalid != null) {
            return invalid;
        }
        if (!UserSkillService.isValidSkillName(name)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_name", "无效的 Skill 名称");
        }
        try {
            var outcome = userSkillService.syncFromPackage(headerUserId, name);
            if (outcome.isEmpty()) {
                return err(HttpStatus.NOT_FOUND, "not_found", "包内不存在技能 '" + name + "'");
            }
            var sync = outcome.get();
            var resp = new LinkedHashMap<String, Object>();
            resp.put("userId", headerUserId);
            resp.put("name", name);
            resp.put("files", sync.files());
            resp.put("skipped", sync.skipped());
            resp.put("message", "包内技能 '" + name + "' 已下发为用户 " + headerUserId + " 的个人版本"
                + (sync.skipped().isEmpty() ? "" : "（跳过 " + sync.skipped().size() + " 个非 UTF-8 文件: "
                    + String.join(", ", sync.skipped()) + "）")
                + (sandboxMode()
                    ? "；当前 SANDBOX_ENABLED=true，该用户下一个 turn 开始时物化进容器 /workspace/skills 生效"
                    : ""));
            return ResponseEntity.ok(resp);
        } catch (UserSkillService.ContentTooLargeException e) {
            return err(HttpStatus.PAYLOAD_TOO_LARGE, "content_too_large", e.getMessage());
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.BAD_REQUEST, "sync_failed", e.getMessage());
        } catch (Exception e) {
            log.warn("User skill sync failed for {}/{}: {}", headerUserId, name, e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "sync_failed", "下发失败: " + e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> err(HttpStatus status, String code, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    /** 文件名 URL 编码（RFC 5987 filename*，支持中文技能名） */
    private static String urlEncode(String name) {
        try {
            return java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        } catch (Exception e) {
            return "skill.zip";
        }
    }
}
