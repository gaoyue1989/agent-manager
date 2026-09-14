package io.agentmanager.framework.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

import io.agentmanager.framework.service.SkillCatalogService;
import io.agentmanager.framework.service.SkillInjectionService;
import io.agentmanager.framework.service.SkillManageService;

/**
 * Skill 管理 API：提供 zip 上传、删除、启停切换、内容读取/修改等操作。
 *
 * <p>所有文件操作直接作用于 /config/skills 目录（L2 仓库事实来源），
 * HarnessSkillMiddleware 下轮推理自动重扫目录，无需重启 Agent。
 */
@RestController
@RequestMapping("/skills")
public class SkillManageController {
    private static final Logger log = LoggerFactory.getLogger(SkillManageController.class);

    private final SkillManageService manageService;
    private final SkillCatalogService catalogService;
    private final SkillInjectionService skillInjectionService;

    public SkillManageController(SkillManageService manageService,
                                 SkillCatalogService catalogService,
                                 SkillInjectionService skillInjectionService) {
        this.manageService = manageService;
        this.catalogService = catalogService;
        this.skillInjectionService = skillInjectionService;
    }

    /**
     * 列出所有 Skill（含启停状态）。
     * 区别于 GET /skills（只返回启用的），此端点返回全部，供管理页面使用。
     */
    @GetMapping("/manage")
    public List<Map<String, Object>> listAll() {
        return catalogService.listAll();
    }

    /**
     * 列出可用的 Skill 摘要（供前端 @Skill 提示使用）。
     * 仅返回已启用 Skill 的 name + description，精简字段降低传输开销。
     * 前端可在输入框中检测 @ 符号后调用此接口展示候选列表。
     */
    @GetMapping("/available")
    public List<Map<String, String>> listAvailable() {
        return catalogService.availableSkills();
    }

    /**
     * 解析消息中的 @Skill 引用（预览用）。
     * 前端在输入过程中可调用此接口实时展示已匹配的 Skill 列表。
     *
     * @param message 用户输入的消息片段
     */
    @GetMapping("/parse-refs")
    public ResponseEntity<Map<String, Object>> parseReferences(
            @RequestParam("message") String message) {
        var refs = skillInjectionService.parseSkillReferences(message);
        return ResponseEntity.ok(Map.of("skills", refs, "count", refs.size()));
    }

    /**
     * 上传 zip 格式的 Skill 包。
     *
     * <p>zip 结构要求：
     * <ul>
     *   <li>根目录直接包含 SKILL.md（扁平结构），或</li>
     *   <li>一级子目录包含 SKILL.md（包裹结构）</li>
     * </ul>
     *
     * <p>skill 名称优先取 SKILL.md frontmatter 的 name 字段。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return err(HttpStatus.BAD_REQUEST, "no_file", "请选择要上传的 zip 文件");
        }
        var fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".zip")) {
            return err(HttpStatus.BAD_REQUEST, "invalid_format", "仅支持 .zip 格式的 Skill 包");
        }
        // 大小上限 20MB
        if (file.getSize() > 20 * 1024 * 1024) {
            return err(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "文件大小超过 20MB 限制");
        }
        try {
            var name = manageService.uploadSkill(file.getInputStream(), fileName);
            var skill = catalogService.listAll().stream()
                .filter(s -> name.equals(s.get("name")))
                .findFirst().orElse(null);
            var resp = new LinkedHashMap<String, Object>();
            resp.put("name", name);
            resp.put("message", "Skill '" + name + "' 上传成功");
            if (skill != null) {
                resp.put("skill", skill);
            }
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.BAD_REQUEST, "upload_failed", e.getMessage());
        } catch (IOException e) {
            log.warn("Skill upload failed: {}", e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "upload_failed", "上传失败: " + e.getMessage());
        }
    }

    /** 删除指定 Skill */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String name) {
        // 安全校验：防止路径注入
        if (name.contains("..") || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            return err(HttpStatus.BAD_REQUEST, "invalid_name", "无效的 Skill 名称");
        }
        try {
            var deleted = manageService.deleteSkill(name);
            if (!deleted) {
                return err(HttpStatus.NOT_FOUND, "not_found", "Skill '" + name + "' 不存在");
            }
            return ResponseEntity.ok(Map.of("name", name, "message", "Skill '" + name + "' 已删除"));
        } catch (IOException e) {
            log.warn("Skill delete failed: {}", e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "delete_failed", "删除失败: " + e.getMessage());
        }
    }

    /** 切换 Skill 启停状态 */
    @PutMapping("/{name}/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@PathVariable String name) {
        if (name.contains("..") || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            return err(HttpStatus.BAD_REQUEST, "invalid_name", "无效的 Skill 名称");
        }
        try {
            var nowEnabled = manageService.toggleSkill(name);
            var status = nowEnabled ? "已启用" : "已禁用";
            return ResponseEntity.ok(Map.of(
                "name", name,
                "enabled", nowEnabled,
                "message", "Skill '" + name + "' " + status
            ));
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
        } catch (IOException e) {
            log.warn("Skill toggle failed: {}", e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "toggle_failed", "操作失败: " + e.getMessage());
        }
    }

    /** 读取 Skill 的 SKILL.md 内容 */
    @GetMapping("/{name}/content")
    public ResponseEntity<Map<String, Object>> getContent(@PathVariable String name) {
        if (name.contains("..") || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            return err(HttpStatus.BAD_REQUEST, "invalid_name", "无效的 Skill 名称");
        }
        try {
            var content = manageService.readSkillContent(name);
            return ResponseEntity.ok(Map.of("name", name, "content", content));
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
        } catch (IOException e) {
            log.warn("Read skill content failed: {}", e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "read_failed", "读取失败: " + e.getMessage());
        }
    }

    /** 修改 Skill 的 SKILL.md 内容 */
    @PutMapping("/{name}/content")
    public ResponseEntity<Map<String, Object>> updateContent(
            @PathVariable String name,
            @RequestBody Map<String, String> body) {
        if (name.contains("..") || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            return err(HttpStatus.BAD_REQUEST, "invalid_name", "无效的 Skill 名称");
        }
        var content = body.get("content");
        if (content == null || content.isBlank()) {
            return err(HttpStatus.BAD_REQUEST, "empty_content", "内容不能为空");
        }
        // 大小限制 100KB
        if (content.getBytes(StandardCharsets.UTF_8).length > 100 * 1024) {
            return err(HttpStatus.PAYLOAD_TOO_LARGE, "content_too_large", "SKILL.md 内容超过 100KB 限制");
        }
        try {
            manageService.writeSkillContent(name, content);
            return ResponseEntity.ok(Map.of("name", name, "message", "Skill '" + name + "' 内容已更新"));
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
        } catch (IOException e) {
            log.warn("Write skill content failed: {}", e.getMessage());
            return err(HttpStatus.INTERNAL_SERVER_ERROR, "write_failed", "修改失败: " + e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> err(HttpStatus status, String code, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
