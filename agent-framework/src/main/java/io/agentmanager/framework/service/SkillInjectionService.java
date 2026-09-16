package io.agentmanager.framework.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Skill 注入服务：解析用户消息中的 @SkillName 标记，将对应 Skill 内容注入到对话上下文。
 *
 * <p>注入方式：将 @SkillName 替换为结构化的 Skill 引用块，包含 Skill 名称和完整 SKILL.md 内容，
 * 使得 Agent 在该轮推理中拥有完整的 Skill 上下文。
 *
 * <p>设计原则：
 * <ul>
 *   <li>仅注入已启用的 Skill（被禁用的 @ 引用会被保留原样，Agent 无法获取其内容）</li>
 *   <li>同一个 Skill 被 @ 多次时，只注入一次</li>
 *   <li>消息中不含 @ 时，零开销（直接返回原消息）</li>
 * </ul>
 */
@Service
public class SkillInjectionService {
    private static final Logger log = LoggerFactory.getLogger(SkillInjectionService.class);

    /**
     * 匹配 @SkillName 的正则。
     * 支持：中文字符、字母、数字、下划线、连字符、点号（与 SkillManageService.sanitizeSkillName 一致）
     * @ 前面不能是单词字符（防止匹配邮箱 user@example.com 等），
     * 但中文后面允许 @（"用@pdf处理" 是合法用法）
     */
    private static final Pattern AT_SKILL_PATTERN = Pattern.compile(
        "(?<![\\w])@([\\w\\u4e00-\\u9fff.\\-]+)"
    );

    private final SkillCatalogService catalogService;
    private final SkillManageService manageService;

    public SkillInjectionService(SkillCatalogService catalogService,
                                  SkillManageService manageService) {
        this.catalogService = catalogService;
        this.manageService = manageService;
    }

    /**
     * 注入 @Skill 引用到用户消息中。
     *
     * <p>处理流程：
     * 1. 扫描消息中所有 @SkillName 标记
     * 2. 对每个标记查找已启用的 Skill
     * 3. 将 @SkillName 替换为结构化引用块
     * 4. 在消息末尾追加所有匹配 Skill 的完整内容
     *
     * @param message 原始用户消息
     * @return 处理后的消息（含 Skill 注入），若无 @ 标记则返回原消息
     */
    public String injectSkillReferences(String message) {
        if (message == null || message.isBlank() || !message.contains("@")) {
            return message;
        }

        // 1. 获取所有已启用 Skill 的 name 集合，用于快速校验
        var enabledSkills = catalogService.list().stream()
            .filter(m -> Boolean.TRUE.equals(m.get("enabled")))
            .map(m -> (String) m.get("name"))
            .collect(Collectors.toSet());

        if (enabledSkills.isEmpty()) {
            return message;
        }

        // 2. 扫描消息中的 @SkillName
        var referencedSkills = new ArrayList<String>();
        Matcher matcher = AT_SKILL_PATTERN.matcher(message);
        var injectedSet = new java.util.LinkedHashSet<String>();

        while (matcher.find()) {
            var skillName = matcher.group(1);
            if (enabledSkills.contains(skillName) && !injectedSet.contains(skillName)) {
                injectedSet.add(skillName);
                referencedSkills.add(skillName);
            }
        }

        if (referencedSkills.isEmpty()) {
            return message;
        }

        // 3. 替换 @SkillName 为引用标记
        matcher.reset();
        var sb = new StringBuilder();
        while (matcher.find()) {
            var skillName = matcher.group(1);
            if (enabledSkills.contains(skillName)) {
                // 替换为引用标记
                matcher.appendReplacement(sb, "[@Skill:" + skillName + "]");
            } else {
                // 不在启用列表中，保留原样
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);

        // 4. 追加 Skill 内容块
        var contentBuilder = new StringBuilder(sb.toString());
        contentBuilder.append("\n\n---\n## Referenced Skills\n");

        for (var skillName : referencedSkills) {
            try {
                var content = manageService.readSkillContent(skillName);
                contentBuilder.append("\n### Skill: ").append(skillName).append("\n");
                contentBuilder.append("```\n");
                contentBuilder.append(content);
                if (!content.endsWith("\n")) {
                    contentBuilder.append('\n');
                }
                contentBuilder.append("```\n");
                log.debug("Injected skill '{}' ({} chars) into message", skillName, content.length());
            } catch (IllegalArgumentException | IOException e) {
                log.warn("Failed to read skill '{}' content for injection: {}", skillName, e.getMessage());
                // 注入失败时追加提示而非阻断
                contentBuilder.append("\n### Skill: ").append(skillName).append("\n");
                contentBuilder.append("> [Skill content unavailable: ").append(e.getMessage()).append("]\n");
            }
        }

        contentBuilder.append("\n---\n");

        log.info("Injected {} skill(s) into message: {}", referencedSkills.size(), referencedSkills);
        return contentBuilder.toString();
    }

    /**
     * 解析消息中所有 @SkillName 标记，返回匹配到的 Skill 名称列表。
     * 不做注入，仅用于前端提示/预览等场景。
     *
     * @param message 用户消息
     * @return 匹配到的 Skill 名称列表（去重，保持顺序）
     */
    public List<String> parseSkillReferences(String message) {
        if (message == null || message.isBlank() || !message.contains("@")) {
            return List.of();
        }

        var enabledSkills = catalogService.list().stream()
            .filter(m -> Boolean.TRUE.equals(m.get("enabled")))
            .map(m -> (String) m.get("name"))
            .collect(Collectors.toSet());

        var result = new ArrayList<String>();
        var seen = new java.util.LinkedHashSet<String>();
        Matcher matcher = AT_SKILL_PATTERN.matcher(message);

        while (matcher.find()) {
            var skillName = matcher.group(1);
            if (enabledSkills.contains(skillName) && !seen.contains(skillName)) {
                seen.add(skillName);
                result.add(skillName);
            }
        }

        return result;
    }
}
