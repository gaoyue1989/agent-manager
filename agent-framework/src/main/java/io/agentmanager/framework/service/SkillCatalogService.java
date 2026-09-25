package io.agentmanager.framework.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.skill.util.MarkdownSkillParser;

/**
 * 动态技能目录：为对外 API（GET /skills、A2A agent-card、/debug/config/oaf）提供
 * 「frontmatter skills 声明 ∪ /config/skills 目录实际内容」的合并视图。
 *
 * <p>skills 目录动态加载后，目录是事实来源（PVC 上内容可运行中变化），冲突时以目录
 * SKILL.md 为准；frontmatter 声明降级为元数据（required 校验、来源标记、卡片展示意图）。
 * 未声明但目录中存在的技能同样可见（source=local-dynamic）——这正是动态加载的意义。
 *
 * <p>扫描复用官方 {@link FileSystemSkillRepository}（每次调用重扫目录，SKILL.md
 * mtime+size 快照缓存，内容不变零开销），不自研缓存与 watcher。
 *
 * <p>启停过滤：被 SkillManageService 禁用的 skill 不会出现在 list() 结果中，
 * 也不会注入到 HarnessSkillMiddleware 的 prompt（因为 list() 是唯一数据源）。
 */
@Service
public class SkillCatalogService {
    private static final Logger log = LoggerFactory.getLogger(SkillCatalogService.class);

    /** 目录中存在但 frontmatter 未声明的技能 source 标记 */
    public static final String DYNAMIC_SOURCE = "local-dynamic";

    private final OafConfig oafConfig;
    private final FileSystemSkillRepository repository;
    private final SkillManageService manageService;
    private final UserSkillService userSkillService;

    public SkillCatalogService(OafConfig oafConfig, AgentManagerProperties props,
                               SkillManageService manageService,
                               UserSkillService userSkillService) {
        this.oafConfig = oafConfig;
        this.manageService = manageService;
        this.userSkillService = userSkillService;
        var skillsDir = Path.of(props.resolvedConfigDir()).resolve("skills");
        // 目录不存在（包未携带 skills）时不构造：官方构造器要求目录必须存在
        this.repository = Files.isDirectory(skillsDir)
            ? new FileSystemSkillRepository(skillsDir, false, "oaf-package")
            : null;
        log.info("SkillCatalogService initialized (dynamic dir: {}, repository: {})",
            skillsDir, repository != null ? "enabled" : "disabled (dir missing)");
    }

    /**
     * 管理视图：返回所有 skill（含被禁用的），每个条目带 enabled 字段。
     * 用于管理页面展示启停状态。
     */
    public List<Map<String, Object>> listAll() {
        var disabled = manageService.getDisabledSet();
        var byName = new LinkedHashMap<String, Map<String, Object>>();

        // ① 目录事实
        if (repository != null) {
            for (var skill : repository.getAllSkills()) {
                var name = skill.getName();
                byName.put(name, entry(
                    name,
                    orEmpty(skill.getDescription()),
                    versionOf(skill),
                    DYNAMIC_SOURCE,
                    false, true, false));
            }
        }

        // ② frontmatter 声明
        for (var declared : oafConfig.skills()) {
            var existing = byName.get(declared.name());
            if (existing == null) {
                byName.put(declared.name(), entry(
                    declared.name(),
                    orEmpty(declared.description()),
                    declared.version() != null ? declared.version() : "",
                    declared.source() != null ? declared.source() : "local",
                    declared.required(), false, true));
            } else {
                existing.put("source", declared.source() != null ? declared.source() : "local");
                existing.put("required", declared.required());
            }
        }

        // ③ 标记启停状态
        for (var entry : byName.values()) {
            var name = (String) entry.get("name");
            entry.put("enabled", !disabled.contains(name));
        }

        return new ArrayList<>(byName.values());
    }

    /**
     * 合并视图（每次调用实时扫描，技能目录变化即时可见）：
     * - 同名技能：description/version 以目录 SKILL.md 为准；source/required 保留声明侧语义
     * - 目录独有：source=local-dynamic，dynamic=true
     * - 声明独有：declaredButMissing=true（required 校验交给 OafConfigLoader 启动期告警）
     * - 被禁用的 skill 不出现在结果中
     */
    public List<Map<String, Object>> list() {
        var disabled = manageService.getDisabledSet();
        var byName = new LinkedHashMap<String, Map<String, Object>>();

        // ① 目录事实（动态）：优先放入，同名声明不覆盖其 description/version
        if (repository != null) {
            for (var skill : repository.getAllSkills()) {
                byName.put(skill.getName(), entry(
                    skill.getName(),
                    orEmpty(skill.getDescription()),
                    versionOf(skill),
                    DYNAMIC_SOURCE,
                    false, true, false));
            }
        }

        // ② frontmatter 声明：目录未覆盖的补入；同名时仅回填 source/required 元数据
        for (var declared : oafConfig.skills()) {
            var existing = byName.get(declared.name());
            if (existing == null) {
                byName.put(declared.name(), entry(
                    declared.name(),
                    orEmpty(declared.description()),
                    declared.version() != null ? declared.version() : "",
                    declared.source() != null ? declared.source() : "local",
                    declared.required(), false, true));
            } else {
                existing.put("source", declared.source() != null ? declared.source() : "local");
                existing.put("required", declared.required());
            }
        }

        // ③ 过滤掉被禁用的 skill
        if (!disabled.isEmpty()) {
            byName.keySet().removeAll(disabled);
        }

        return new ArrayList<>(byName.values());
    }

    /**
     * 目录中实际可见的技能名集合（只读快速视图，供健康检查/诊断用）。
     */
    public List<String> dynamicSkillNames() {
        if (repository == null) {
            return List.of();
        }
        return repository.getAllSkills().stream().map(AgentSkill::getName).toList();
    }

    /**
     * 可用 Skill 摘要列表（供前端 @Skill 提示使用）。
     * 仅返回已启用的 skill 的 name + description。
     * 等价于 list() 的精简视图，去掉 version/source/required 等管理字段。
     */
    public List<Map<String, String>> availableSkills() {
        return toNameDesc(collectAvailable(null));
    }

    /**
     * 按用户合并的可用 Skill 摘要（全局目录 ∪ 该用户 L4 个人技能，同名 L4 覆盖描述）。
     *
     * <p>{@code userId} 为空时等价于 {@link #availableSkills()}。L4 合并失败降级为全局目录
     * （不阻断 @ 补全）；被 SkillManageService 禁用的包内技能仍不出现。
     */
    public List<Map<String, String>> availableSkills(String userId) {
        if (userId == null || userId.isBlank()) {
            return availableSkills();
        }
        return toNameDesc(collectAvailable(userId));
    }

    /** name → description 合并视图：全局目录为底，L4 个人技能覆盖同名描述（并补入独有技能） */
    private Map<String, String> collectAvailable(String userId) {
        var merged = new LinkedHashMap<String, String>();
        for (var m : list()) {
            var name = (String) m.get("name");
            if (name == null || name.isBlank()) {
                continue;
            }
            merged.put(name, (String) m.getOrDefault("description", ""));
        }
        if (userId != null && !userId.isBlank()) {
            try {
                for (var skill : userSkillService.listSkills(userId)) {
                    var name = skill.name();
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    merged.put(name, l4Description(userId, name));
                }
            } catch (Exception e) {
                // L4 合并失败降级为全局目录，避免 @ 补全整体不可用
                log.warn("availableSkills: merge L4 failed for user {}: {}", userId, e.getMessage());
            }
        }
        return merged;
    }

    /** 读取 L4 SKILL.md frontmatter 的 description（解析失败返回空串） */
    private String l4Description(String userId, String name) {
        try {
            var opt = userSkillService.readSkill(userId, name, null);
            if (opt.isEmpty() || opt.get().content() == null) {
                return "";
            }
            var parsed = MarkdownSkillParser.parse(opt.get().content());
            var meta = parsed != null ? parsed.getMetadata() : null;
            var desc = meta != null ? meta.get("description") : null;
            return desc != null ? String.valueOf(desc) : "";
        } catch (Exception e) {
            log.debug("availableSkills: read L4 skill {} for user {} failed: {}", name, userId, e.getMessage());
            return "";
        }
    }

    private static List<Map<String, String>> toNameDesc(Map<String, String> merged) {
        var out = new ArrayList<Map<String, String>>();
        for (var e : merged.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                continue;
            }
            out.add(Map.of(
                "name", e.getKey(),
                "description", e.getValue() != null ? e.getValue() : ""));
        }
        return out;
    }

    private static Map<String, Object> entry(
        String name, String description, String version, String source,
        boolean required, boolean dynamic, boolean declaredButMissing) {
        var m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        m.put("description", description);
        m.put("version", version);
        m.put("source", source);
        m.put("required", required);
        m.put("dynamic", dynamic);
        m.put("declaredButMissing", declaredButMissing);
        m.put("enabled", true);
        return m;
    }

    /** SKILL.md frontmatter 顶层 version（MarkdownSkillParser 将其归入 metadata） */
    private static String versionOf(AgentSkill skill) {
        var v = skill.getMetadataValue("version");
        return v != null ? v.toString() : "";
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }
}
