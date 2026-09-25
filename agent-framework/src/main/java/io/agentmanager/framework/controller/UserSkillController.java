package io.agentmanager.framework.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.service.UserSkillService;

/**
 * 用户技能（L4）管理 API：个人技能覆盖的列表/读取/写入/删除/从包内下发。
 *
 * <p>存储位置是 agent_fs KV（{@code agents/{agent}/users/{uid}/skills}），与 SkillManageController
 * 的 {@code /config/skills}（L2 包内文件，PVC）是两层不同存储，故不复用其路由：
 * 全部端点挂在 {@code /skills/users} 前缀下。
 *
 * <p>路由消歧（集群实测口径）：{@code GET /skills/users}（字面段，1 段）不与
 * {@code /skills/{name}} 冲突——SkillManageController 在该路径没有 GET 映射；
 * 但 {@code /skills/users/{userId}} 与 {@code /skills/{name}/content} 在 userId 恰为
 * {@code content} 时同时匹配，由更具体的后者命中（返回包内技能内容，而非该用户技能列表）。
 * 这是既有路由下的窄边界，索引（/skills/users 与 /debug/user-skills）与写入/删除路径不受影响。
 *
 * <p><b>生效范围分档</b>（成功提示按 {@link SandboxConfig#enabled()} 区分，避免运维误判）：
 * <ul>
 *   <li>非沙箱档（SANDBOX_ENABLED=false）：推理直接读 agent_fs 的 L4，同名技能 L4 覆盖 L2，
 *       写入/删除下一轮会话生效；</li>
 *   <li>沙箱档（SANDBOX_ENABLED=true）：会话读的是容器内 {@code /workspace/skills} 副本，
 *       管理面只写 agent_fs KV，<b>不会回注沙箱容器</b>——需容器换代或后续“会话开始物化 L4”
 *       能力（尚未实现）才对该用户会话生效。</li>
 * </ul>
 *
 * <p>沙箱档的两个 KV 仲裁标记（沙箱回写命中即跳过同名技能，避免管理面操作被容器内旧副本改回）：
 * PUT/下发写 {@code /{name}/.admin-override}，DELETE 写 {@code /{name}/.deleted}。
 * 代价是标记生效期间该技能在容器内的 {@code skill_manage} 修改不再落库，
 * 清除方式随 PUT/DELETE 响应与列表的 {@code tombstones} 字段显式下发。
 *
 * <p>端点无鉴权，与既有 {@code PUT /skills/{name}/content} 同级（可访问业务入口者即可改该 agent
 * 命名空间内的个人技能，跨 agent 不可达）。
 */
@RestController
@RequestMapping("/skills/users")
public class UserSkillController {
    private static final Logger log = LoggerFactory.getLogger(UserSkillController.class);

    /** 删除标记（tombstone）的清除方式：随删除响应下发，避免运维把“标记仍在”当成“技能已恢复” */
    private static final String TOMBSTONE_CLEAR_HINT =
        "管理面重新写入（PUT /skills/users/{userId}/{name}）或从包内下发（POST /skills/users/{userId}/{name}"
            + "/sync-from-package）会清除标记";

    private final UserSkillService userSkillService;
    private final SandboxConfig sandboxConfig;

    public UserSkillController(UserSkillService userSkillService, SandboxConfig sandboxConfig) {
        this.userSkillService = userSkillService;
        this.sandboxConfig = sandboxConfig;
    }

    /** 沙箱档提示（写在成功消息里，避免“显示已生效、实际不回注容器”的误判） */
    private boolean sandboxMode() {
        return sandboxConfig != null && sandboxConfig.enabled();
    }

    /**
     * 列出存在个人技能覆盖的用户（调试页下拉/清单）。
     *
     * <p>该路径为单段字面量：SkillManageController 没有 {@code GET /skills/{name}} 映射
     * （只有 {@code DELETE /skills/{name}} 与 {@code GET /skills/{name}/content}），
     * 因此不与任何既有路由冲突（集群实测 200）。调试页默认读 {@code /debug/user-skills}
     * （同一份数据，与 /debug/memory 的“按用户聚合”风格一致），此处保留 REST 侧入口。
     *
     * <p>索引查询失败（DB/SQL 不可用）→ 500：不得降级成 200 + 空列表，
     * 否则调用方会把「索引查不到」当成「没有任何用户有个人技能」。
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listUsers() {
        UserSkillService.UserSkillIndex index;
        try {
            index = userSkillService.listUsers();
        } catch (Exception e) {
            log.warn("User skill index failed: {}", e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "index_failed",
                "用户技能索引读取失败: " + e.getMessage());
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("count", index.users().size());
        body.put("users", index.users());
        if (index.truncated()) {
            // 触顶截断必须显式暴露，否则调用方会把子集当成全集
            body.put("truncated", true);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * 列出某用户的个人技能（L4），含“删除后回落的包内基线”标记；
     * {@code tombstones} 为该用户仍带管理面删除标记的技能（已不在 L4，但回写侧仍会跳过同名技能）。
     */
    @GetMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> listSkills(@PathVariable String userId) {
        if (!UserSkillService.isValidUserId(userId)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_user_id", "无效的用户标识");
        }
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("userId", userId);
            body.put("skills", userSkillService.listSkills(userId));
            body.put("tombstones", userSkillService.listTombstones(userId));
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            // KV 枚举失败必须显式报错（不能返回“该用户没有个人技能”这种降级结果）
            log.warn("User skill list failed for {}: {}", userId, e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "list_failed", "读取失败: " + e.getMessage());
        }
    }

    /**
     * 读取某用户某技能的文件内容：L4 优先，无 L4 时回落包内基线。
     *
     * <p>KV 读取失败 → 500（不能把“存储不可用”静默报成 not_found 404）；
     * 两侧都不存在才是 404。
     *
     * @param file 技能内相对路径（默认 SKILL.md，只读；写入固定为 SKILL.md）
     */
    @GetMapping("/{userId}/{name}")
    public ResponseEntity<Map<String, Object>> getSkill(
            @PathVariable String userId,
            @PathVariable String name,
            @RequestParam(name = "file", required = false) String file) {
        if (!UserSkillService.isValidUserId(userId)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_user_id", "无效的用户标识");
        }
        if (!UserSkillService.isValidSkillName(name)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_name", "无效的 Skill 名称");
        }
        if (file != null && !file.isBlank() && !UserSkillService.isValidSkillFilePath(file)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_file_path", "无效的技能文件路径");
        }
        Optional<UserSkillService.UserSkillContent> content;
        try {
            content = userSkillService.readSkill(userId, name, file);
        } catch (Exception e) {
            log.warn("User skill read failed for {}/{}: {}", userId, name, e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "read_failed", "读取失败: " + e.getMessage());
        }
        if (content.isEmpty()) {
            return err(HttpStatus.NOT_FOUND, "not_found",
                "技能 '" + name + "' 在用户 " + userId + " 与包内均不存在");
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
     * 新建/覆盖某用户的技能主文件（SKILL.md）。
     *
     * <p>提示文案分档：非沙箱档“下轮会话生效”；沙箱档只写 agent_fs KV，不会回注容器。
     */
    @PutMapping("/{userId}/{name}")
    public ResponseEntity<Map<String, Object>> putSkill(
            @PathVariable String userId,
            @PathVariable String name,
            @RequestBody(required = false) Map<String, String> body) {
        if (!UserSkillService.isValidUserId(userId)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_user_id", "无效的用户标识");
        }
        if (!UserSkillService.isValidSkillName(name)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_name", "无效的 Skill 名称");
        }
        var content = body == null ? null : body.get("content");
        if (content == null || content.isBlank()) {
            return err(HttpStatus.BAD_REQUEST, "empty_content", "内容不能为空");
        }
        try {
            var outcome = userSkillService.writeSkill(userId, name, content);
            var resp = new LinkedHashMap<String, Object>();
            resp.put("userId", userId);
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
                    : "，用户 " + userId + " 下轮会话生效"));
            return ResponseEntity.ok(resp);
        } catch (UserSkillService.ContentTooLargeException e) {
            return err(HttpStatus.PAYLOAD_TOO_LARGE, "content_too_large", e.getMessage());
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.BAD_REQUEST, "invalid_content", e.getMessage());
        } catch (Exception e) {
            log.warn("User skill write failed for {}/{}: {}", userId, name, e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "write_failed", "写入失败: " + e.getMessage());
        }
    }

    /**
     * 删除某用户的个人技能覆盖（全部文件）。
     *
     * <p>提示按“删除后是否有包内基线可回落”区分（{@link UserSkillService#hasPackageBaseline}），
     * 沙箱档另行提示容器内副本的存活边界。
     */
    @DeleteMapping("/{userId}/{name}")
    public ResponseEntity<Map<String, Object>> deleteSkill(
            @PathVariable String userId,
            @PathVariable String name) {
        if (!UserSkillService.isValidUserId(userId)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_user_id", "无效的用户标识");
        }
        if (!UserSkillService.isValidSkillName(name)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_name", "无效的 Skill 名称");
        }
        try {
            var deleted = userSkillService.deleteSkill(userId, name);
            if (deleted.isEmpty()) {
                return err(HttpStatus.NOT_FOUND, "not_found",
                    "用户 " + userId + " 无个人技能 '" + name + "'");
            }
            var hasBaseline = userSkillService.hasPackageBaseline(name);
            var resp = new LinkedHashMap<String, Object>();
            resp.put("userId", userId);
            resp.put("name", name);
            resp.put("deletedFiles", deleted.get());
            resp.put("hasPackageBaseline", hasBaseline);
            // 删除标记可见性：标记无 TTL，用户在同代容器内重建同名技能会被回写侧一直跳过，
            // 该后果与清除方式必须随响应显式下发（否则表现为“技能明明存了却不落库”）
            resp.put("tombstone", Map.of("name", name, "clearHint", TOMBSTONE_CLEAR_HINT));
            resp.put("message", "用户技能 '" + name + "' 已删除（已写入删除标记防止回写复活；"
                + TOMBSTONE_CLEAR_HINT + "），用户 "
                + userId + (hasBaseline ? " 回落包内基线" : " 无包内同名技能，该技能已消失")
                + "；标记生效期间该用户在同代（及后续）容器内用 skill_manage 重建同名技能不会被回写落库"
                + (sandboxMode()
                    ? "；当前 SANDBOX_ENABLED=true，tombstone 生效：下一次会话物化时不再写回容器"
                      + "（容器内旧副本在容器换代前仍可能对该用户会话可见）"
                    : ""));
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
        } catch (Exception e) {
            log.warn("User skill delete failed for {}/{}: {}", userId, name, e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "delete_failed", "删除失败: " + e.getMessage());
        }
    }

    /**
     * 把包内（L2）同名技能整目录下发为该用户的个人版本（含 scripts 等资源文件）。
     *
     * <p>以包内清单为准全量替换（差集清理多余旧文件）+ 失败回滚；非 UTF-8/二进制文件显式跳过，
     * 跳过清单在 {@code skipped} 字段返回。
     */
    @PostMapping("/{userId}/{name}/sync-from-package")
    public ResponseEntity<Map<String, Object>> syncFromPackage(
            @PathVariable String userId,
            @PathVariable String name) {
        if (!UserSkillService.isValidUserId(userId)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_user_id", "无效的用户标识");
        }
        if (!UserSkillService.isValidSkillName(name)) {
            return err(HttpStatus.BAD_REQUEST, "invalid_name", "无效的 Skill 名称");
        }
        try {
            var outcome = userSkillService.syncFromPackage(userId, name);
            if (outcome.isEmpty()) {
                return err(HttpStatus.NOT_FOUND, "not_found", "包内不存在技能 '" + name + "'");
            }
            var sync = outcome.get();
            var resp = new LinkedHashMap<String, Object>();
            resp.put("userId", userId);
            resp.put("name", name);
            resp.put("files", sync.files());
            resp.put("skipped", sync.skipped());
            resp.put("message", "包内技能 '" + name + "' 已下发为用户 " + userId + " 的个人版本"
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
            log.warn("User skill sync failed for {}/{}: {}", userId, name, e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "sync_failed", "下发失败: " + e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> err(HttpStatus status, String code, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
