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
 */
@Service
public class SkillCatalogService {
    private static final Logger log = LoggerFactory.getLogger(SkillCatalogService.class);

    /** 目录中存在但 frontmatter 未声明的技能 source 标记 */
    public static final String DYNAMIC_SOURCE = "local-dynamic";

    private final OafConfig oafConfig;
    private final FileSystemSkillRepository repository;

    public SkillCatalogService(OafConfig oafConfig, AgentManagerProperties props) {
        this.oafConfig = oafConfig;
        var skillsDir = Path.of(props.configDir()).resolve("skills");
        // 目录不存在（包未携带 skills）时不构造：官方构造器要求目录必须存在
        this.repository = Files.isDirectory(skillsDir)
            ? new FileSystemSkillRepository(skillsDir, false, "oaf-package")
            : null;
        log.info("SkillCatalogService initialized (dynamic dir: {}, repository: {})",
            skillsDir, repository != null ? "enabled" : "disabled (dir missing)");
    }

    /**
     * 合并视图（每次调用实时扫描，技能目录变化即时可见）：
     * - 同名技能：description/version 以目录 SKILL.md 为准；source/required 保留声明侧语义
     * - 目录独有：source=local-dynamic，dynamic=true
     * - 声明独有：declaredButMissing=true（required 校验交给 OafConfigLoader 启动期告警）
     */
    public List<Map<String, Object>> list() {
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
