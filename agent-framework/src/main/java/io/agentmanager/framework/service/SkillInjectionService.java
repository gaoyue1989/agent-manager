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
 * Skill 注入服务：解析用户消息中的 /SkillName 标记，将对应 Skill 内容注入到对话上下文。
 *
 * <p>注入方式：将 /SkillName 替换为结构化的 Skill 引用块，包含 Skill 名称和完整 SKILL.md 内容，
 * 使得 Agent 在该轮推理中拥有完整的 Skill 上下文。
 *
 * <p>设计原则：
 * <ul>
 *   <li>仅注入已启用的 Skill（被禁用的 / 引用会被保留原样，Agent 无法获取其内容）</li>
 *   <li>同一个 Skill 被 / 多次引用时，只注入一次</li>
 *   <li>消息中不含 / 引用时，零开销（直接返回原消息）</li>
 * </ul>
 */
@Service
public class SkillInjectionService {
    private static final Logger log = LoggerFactory.getLogger(SkillInjectionService.class);

    /**
     * 匹配 /SkillName 的正则。
     * 支持：中文字符、字母、数字、下划线、连字符、点号（与 SkillManageService.sanitizeSkillName 一致）。
     * "/" 前面不能是单词字符、":" 或 "/"（防止匹配 URL https://host/path、Unix 路径等）；
     * 但中文后面允许 "/"（"用/pdf处理" 是合法用法，Java 的 \w 默认为 ASCII）。
     */
    private static final Pattern SLASH_SKILL_PATTERN = Pattern.compile(
        "(?<![\\w:/.])/([\\w\\u4e00-\\u9fff.\\-]+)"
    );

    /**
     * 注入区块起始标记。
     *
     * <p>append（{@link #injectSkillReferences}）与 strip（{@link #stripInjectedSkills}）
     * 必须用同一常量——分叉会让剥离漂移（漏剥或误剥用户正文）。
     */
    public static final String SKILL_SECTION_MARKER = "\n\n---\n## Referenced Skills\n";

    /**
     * 剥离注入的 Skill 引用区块，还原为「用户自己敲的内容」（history 展示用，不改动模型上下文）。
     *
     * <p><b>为什么要剥离</b>：注入把整篇 SKILL.md 追加进用户消息，那是给模型看的上下文
     * （模型必须有完整 Skill 内容）。但用户可见历史原样渲染这条消息时，提问气泡里会拖着
     * 整篇技能文档——既冗长，也不是用户输入的任何一部分。剥离后只剩用户原文
     * （{@code /Name} 已替换为 {@code [/Skill:Name]}，仍反映用户所敲）。
     *
     * @return 剥离后的文本；无注入区块时原样返回（null/空安全）
     */
    public static String stripInjectedSkills(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        var idx = text.indexOf(SKILL_SECTION_MARKER);
        return idx >= 0 ? text.substring(0, idx) : text;
    }

    private final SkillCatalogService catalogService;
    private final SkillManageService manageService;
    private final UserSkillService userSkillService;

    public SkillInjectionService(SkillCatalogService catalogService,
                                  SkillManageService manageService,
                                  UserSkillService userSkillService) {
        this.catalogService = catalogService;
        this.manageService = manageService;
        this.userSkillService = userSkillService;
    }

    /**
     * 注入 /Skill 引用到用户消息中（不合并用户 L4 个人技能）。
     *
     * @param message 原始用户消息
     * @return 处理后的消息（含 Skill 注入），若无 / 标记则返回原消息
     */
    public String injectSkillReferences(String message) {
        return injectSkillReferences(message, null);
    }

    /**
     * 注入 /Skill 引用到用户消息中，并按 session userId 合并该用户的 L4 个人技能。
     *
     * <p>处理流程：
     * 1. 扫描消息中所有 /SkillName 标记
     * 2. 对每个标记查找已启用的 Skill（全局目录 ∪ 该用户 L4 个人技能）
     * 3. 将 /SkillName 替换为结构化引用块
     * 4. 在消息末尾追加所有匹配 Skill 的完整内容（L4 优先，无覆盖回落包内基线）
     *
     * @param message 原始用户消息
     * @param userId  会话生效的 userId（网关注入；可为 null，此时仅全局目录）
     * @return 处理后的消息（含 Skill 注入），若无 / 标记则返回原消息
     */
    public String injectSkillReferences(String message, String userId) {
        return injectSkillReferences(message, userId, (List<String>) null);
    }

    /**
     * 注入 /Skill 引用（本轮可用技能列表，session-skill-list-design §5）：
     * 仅注入「白名单内且已启用」的技能；白名单外（含未知名）保留原文，不报错、不提示。
     *
     * @param message    原始用户消息
     * @param userId     会话生效的 userId（可为 null，此时仅全局目录）
     * @param skillNames 本轮可用技能列表；null = 不限制（全部启用技能）
     * @return 处理后的消息（含 Skill 注入），若无 / 标记则返回原消息
     */
    public String injectSkillReferences(String message, String userId, List<String> skillNames) {
        if (message == null || message.isBlank() || !message.contains("/")) {
            return message;
        }

        // 1. 先做零成本候选扫描：没有 /SkillName 形态直接返回，避免 URL/路径消息触发 L4 查询
        Matcher matcher = SLASH_SKILL_PATTERN.matcher(message);
        if (!matcher.find()) {
            return message;
        }

        // 2. 获取本轮可用的已启用 Skill name 集合（全局目录 ∪ 该用户 L4，再按白名单裁剪）
        var enabledSkills = enabledSkillNames(userId, skillNames);

        if (enabledSkills.isEmpty()) {
            return message;
        }

        // 3. 扫描消息中的 /SkillName
        var referencedSkills = new ArrayList<String>();
        var injectedSet = new java.util.LinkedHashSet<String>();

        matcher.reset();
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

        // 4. 替换 /SkillName 为引用标记
        matcher.reset();
        var sb = new StringBuilder();
        while (matcher.find()) {
            var skillName = matcher.group(1);
            if (enabledSkills.contains(skillName)) {
                // 替换为引用标记
                matcher.appendReplacement(sb, "[/Skill:" + skillName + "]");
            } else {
                // 不在启用列表中，保留原样
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);

        // 5. 追加 Skill 内容块（标记与 stripInjectedSkills 共用常量，剥离不漂移）
        var contentBuilder = new StringBuilder(sb.toString());
        contentBuilder.append(SKILL_SECTION_MARKER);

        for (var skillName : referencedSkills) {
            try {
                var content = readSkillContent(userId, skillName);
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
     * 解析消息中所有 /SkillName 标记，返回匹配到的 Skill 名称列表。
     * 不做注入，仅用于前端提示/预览等场景。
     *
     * @param message 用户消息
     * @return 匹配到的 Skill 名称列表（去重，保持顺序）
     */
    public List<String> parseSkillReferences(String message) {
        return parseSkillReferences(message, null);
    }

    /**
     * 解析消息中的 /SkillName 标记（按 session userId 合并 L4 个人技能后校验）。
     *
     * @param message 用户消息
     * @param userId  会话生效的 userId（网关注入；可为 null，此时仅全局目录）
     * @return 匹配到的 Skill 名称列表（去重，保持顺序）
     */
    public List<String> parseSkillReferences(String message, String userId) {
        return parseSkillReferences(message, userId, (List<String>) null);
    }

    /**
     * 解析消息中的 /SkillName 标记（本轮可用技能列表，§5）：仅返回白名单内技能，
     * 与 {@code /Skill} 注入同口径。
     *
     * @param message    用户消息
     * @param userId     会话生效的 userId（可为 null，此时仅全局目录）
     * @param skillNames 本轮可用技能列表；null = 不限制
     * @return 匹配到的 Skill 名称列表（去重，保持顺序）
     */
    public List<String> parseSkillReferences(String message, String userId, List<String> skillNames) {
        if (message == null || message.isBlank() || !message.contains("/")) {
            return List.of();
        }

        // 零成本候选扫描：没有 /SkillName 形态直接返回，避免 URL/路径消息触发 L4 查询
        Matcher matcher = SLASH_SKILL_PATTERN.matcher(message);
        if (!matcher.find()) {
            return List.of();
        }

        var enabledSkills = enabledSkillNames(userId, skillNames);

        var result = new ArrayList<String>();
        var seen = new java.util.LinkedHashSet<String>();

        matcher.reset();
        while (matcher.find()) {
            var skillName = matcher.group(1);
            if (enabledSkills.contains(skillName) && !seen.contains(skillName)) {
                seen.add(skillName);
                result.add(skillName);
            }
        }

        return result;
    }

    /**
     * 已启用技能名集合：全局目录（已启用）∪ 该用户 L4 个人技能，再按本轮白名单裁剪
     * （§5——白名单为 null 不裁剪；空列表 = 空集）。
     * L4 合并失败降级为全局目录，不阻断 / 注入。
     */
    private java.util.Set<String> enabledSkillNames(String userId, List<String> skillNames) {
        var names = new java.util.LinkedHashSet<String>();
        catalogService.list().stream()
            .filter(m -> Boolean.TRUE.equals(m.get("enabled")))
            .map(m -> (String) m.get("name"))
            .filter(n -> n != null && !n.isBlank())
            .forEach(names::add);
        if (userId != null && !userId.isBlank()) {
            try {
                for (var skill : userSkillService.listSkills(userId)) {
                    if (skill.name() == null || skill.name().isBlank()) {
                        continue;
                    }
                    if (!skill.enabled()) {
                        // 被管理面禁用的个人技能：连同包内同名基线一并排除
                        names.remove(skill.name());
                        continue;
                    }
                    names.add(skill.name());
                }
            } catch (Exception e) {
                log.warn("enabledSkillNames: merge L4 failed for user {}: {}", userId, e.getMessage());
            }
        }
        // L2 禁用优先（oa-1110-repro-fixes-design §2.1b）：同名 L4 enabled 覆盖不得把包级
        // 禁用技能重新放回（与 SkillDisabledFilterMiddleware 的名字并集禁用口径对齐）；
        // 禁用集读取失败按空集 fail-soft
        try {
            var l2Disabled = manageService.getDisabledSet();
            if (l2Disabled != null && !l2Disabled.isEmpty()) {
                names.removeAll(l2Disabled);
            }
        } catch (Exception e) {
            log.warn("enabledSkillNames: read L2 disabled set failed: {}", e.getMessage());
        }
        if (skillNames == null) {
            return names;
        }
        var allow = new java.util.LinkedHashSet<String>();
        for (var name : skillNames) {
            var trimmed = name == null ? "" : name.trim();
            if (!trimmed.isEmpty()) {
                allow.add(trimmed);
            }
        }
        var out = new java.util.LinkedHashSet<String>();
        for (var n : names) {
            if (allow.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * 读取技能内容：L4 个人技能优先，无覆盖回落包内基线。
     *
     * @throws IOException 两侧都读不到（与 manageService.readSkillContent 一致，交由调用方降级处理）
     */
    private String readSkillContent(String userId, String skillName) throws IOException {
        if (userId != null && !userId.isBlank()) {
            try {
                var opt = userSkillService.readSkill(userId, skillName, null);
                if (opt.isPresent()) {
                    var content = opt.get().content();
                    if (content != null && !content.isBlank()) {
                        return content;
                    }
                }
            } catch (Exception e) {
                // L4 读取异常不阻断：回落包内基线
                log.debug("read L4 skill {} for user {} failed: {}", skillName, userId, e.getMessage());
            }
        }
        return manageService.readSkillContent(skillName);
    }
}
