package io.agentmanager.framework.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.model.OafConfig;

/**
 * 用户技能（L4）管理服务：agent_fs KV 中 {@code agents/{agent}/users/{uid}/skills} 的
 * 读写/删除/下发，叠加包内（L2，{@code /config/skills}）基线视图。
 *
 * <p>存储层职责划分：
 * <ul>
 *   <li>L4（个人覆盖）：{@link WorkspaceReader}（KV 命名空间与 SDK 完全一致）</li>
 *   <li>L2（包内基线）：{@link SkillManageService#getSkillsDir()}，删除个人覆盖后回落的那一层</li>
 *   <li>用户索引：agent_fs 表聚合（调试页下拉用）</li>
 * </ul>
 *
 * <p>命名空间分隔符为 0x1F（JdbcStore 编码），SQL LIKE 匹配前必须转义 {@code % / _}，
 * 否则 OAF 名中的通配符会错配（agent 名不受限，可能含下划线）。
 *
 * <p>生效范围：非沙箱档推理直接读 agent_fs 的 L4（同名 L4 覆盖 L2）；沙箱档会话读容器内
 * {@code /workspace/skills} 副本，管理面写入由「会话开始物化 L4」
 * （{@link WorkspaceReader#materializeUserSkills}，经 SandboxUserKeyMiddleware 在每次 acquire
 * 后投影进容器）在该用户下一个 turn 生效。
 * 沙箱回写侧的两道仲裁（均落在技能目录的 {@code .} 元数据键上，回写命中即跳过同名技能）：
 * 删除写 {@code /{name}/.deleted}（防删除被静默还原）、写入写 {@code /{name}/.admin-override}
 * （防管理面写入被同代容器内旧副本改回）。
 */
@Service
public class UserSkillService {
    private static final Logger log = LoggerFactory.getLogger(UserSkillService.class);

    /** 单个技能文件内容上限（与 /skills/{name}/content 一致） */
    public static final int MAX_CONTENT_BYTES = 100 * 1024;
    /** 从包内下发时的文件数上限（防一次写入过多 KV 对象） */
    public static final int MAX_SYNC_FILES = 200;
    /**
     * KV key {@code /{技能名}/{相对路径}} 的字符上限：agent_fs.item_key 为 VARCHAR(255)，
     * 减去 key 里两个 “/” 后为 253；技能名（≤128）与相对路径（≤256）各自合法但组合超限时，
     * 必须在入口 400 拦下，否则会落到 KV 写入失败（500）。
     */
    public static final int MAX_SKILL_KEY_LENGTH = 253;
    /** 用户索引的用户数上限（按 namespace_path 计，超出即截断） */
    private static final int USER_INDEX_SCAN_LIMIT = 5000;
    /** 用户索引的回读行数上限（防单个用户技能文件极多时把内存打爆；触顶同样标记截断） */
    private static final int USER_INDEX_ROW_LIMIT = 100_000;

    private final WorkspaceReader workspaceReader;
    private final SkillManageService skillManageService;
    private final DataSource dataSource;
    private final String agentName;

    public UserSkillService(WorkspaceReader workspaceReader,
                            SkillManageService skillManageService,
                            DataSource dataSource,
                            OafConfig oafConfig) {
        this.workspaceReader = workspaceReader;
        this.skillManageService = skillManageService;
        this.dataSource = dataSource;
        this.agentName = oafConfig != null ? oafConfig.name() : null;
    }

    /** 单文件超限（映射 413） */
    public static class ContentTooLargeException extends RuntimeException {
        public ContentTooLargeException(String message) {
            super(message);
        }
    }

    // ==================== 实体 ====================

    /** 用户索引项（调试页下拉/清单） */
    public record UserSkillUser(String userId, int skillCount, String updatedAt) {}

    /**
     * 用户索引结果：{@code truncated=true} 表示触顶截断（索引不全，调试页需提示），
     * 与实际返回的用户数一起下发，避免静默返回子集。
     */
    public record UserSkillIndex(List<UserSkillUser> users, boolean truncated) {}

    /** 技能摘要（L4 视角；hasPackageBaseline 表示删除后会回落到包内同名技能） */
    public record UserSkillSummary(String name, List<String> files, long bytes, long version,
                                   boolean hasPackageBaseline, boolean adminOverride) {}

    /** 管理面删除标记（tombstone）视图项：该用户该技能已被管理面删除，但标记仍在 KV 中 */
    public record UserSkillTombstone(String name, String deletedAt) {}

    /**
     * 技能文件内容视图。
     *
     * <p>{@code source} / {@code hasUserOverride} / {@code version} / {@code files} 四者同源，
     * 都描述“本次返回内容所在的那一层”：
     * <ul>
     *   <li>{@code source=user}：内容来自 L4 KV，version 为该文件 KV 版本，files 为 L4 文件清单</li>
     *   <li>{@code source=package}：内容来自包内基线，version=0，files 为包内文件清单
     *       （不再出现“L4 文件表 + package/false/0”的自相矛盾响应）</li>
     * </ul>
     *
     * <p>{@code userOverrideExists} 单独说明该用户在本技能上是否已有个人覆盖（KV 里有 SKILL.md）：
     * {@code source=package} 且该值为 true 时表示“用户确实有个人覆盖，但请求的这个文件只在包内”。
     */
    public record UserSkillContent(String userId, String name, String content, String source,
                                   boolean hasUserOverride, long version, List<String> files,
                                   boolean userOverrideExists) {}

    /** 写入结果：action = created | updated */
    public record WriteOutcome(String action, long version) {}

    /**
     * 从包内下发的执行结果。
     *
     * <p>{@code files}= 已下发（个人版本）的文件；{@code skipped}= 包内存在但无法下发
     * （非 UTF-8/二进制，KV 只存字符串，静默替换会损坏数据）而被显式跳过的文件。
     */
    public record SyncOutcome(List<String> files, List<String> skipped) {}

    // ==================== 名称校验（集中一处维护，禁止路径穿越） ====================
    // 两条硬约束，违反其一就会“写成功但 SDK 读不到”或直接 500：
    //   1) 控制字符（含 0x1F 命名空间分隔符）不得进入命名空间/拼段——JdbcStore.namespacePath
    //      对含 0x1F 的段抛 IllegalArgumentException，必须拦在入口成 400；
    //   2) 用户标识必须与 PathSafe.sanitize 等价（sanitize 是幂等键）：WorkspaceReader 按
    //      清洗后的 userId 拼 KV 命名空间，框架 RemoteFilesystemSpec 用的却是原始 userId，
    //      两者不一致时个人覆盖会落在 SDK 永远读不到的命名空间。

    /** 控制字符（0x00-0x1F 与 0x7F）检测 */
    private static boolean hasControlChar(String value) {
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    /** 技能名合法性：禁止 .. / \ 与 . / _ 开头（元数据目录约定）、控制字符，长度受限 */
    public static boolean isValidSkillName(String name) {
        if (name == null || name.isBlank() || name.length() > 128 || hasControlChar(name)) {
            return false;
        }
        return !name.contains("..") && !name.contains("/") && !name.contains("\\")
            && !name.startsWith(".") && !name.startsWith("_");
    }

    /** 用户标识合法性：sanitize 幂等（命名空间与 SDK 一致）、禁路径穿越、长度受限 */
    public static boolean isValidUserId(String userId) {
        if (userId == null || userId.isBlank() || userId.length() > 64 || hasControlChar(userId)) {
            return false;
        }
        // 前导 "_" 不拦：框架匿名用户标识即 "_default"（RemoteFilesystemSpec.anonymousUserId）
        if (userId.contains("..") || userId.contains("/") || userId.contains("\\")
                || userId.startsWith(".")) {
            return false;
        }
        // 清洗后不等价 → KV 命名空间与框架实际使用的 userId 不同，写进去也读不到
        return userId.equals(io.agentmanager.framework.util.PathSafe.sanitize(userId));
    }

    /** 技能内相对文件路径合法性：允许子目录，逐段禁止 ..、. / _ 开头（元数据）与控制字符 */
    public static boolean isValidSkillFilePath(String relPath) {
        if (relPath == null || relPath.isBlank() || relPath.length() > 256
                || hasControlChar(relPath) || relPath.startsWith("/")) {
            return false;
        }
        for (var segment : relPath.replace('\\', '/').split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")
                    || segment.startsWith(".") || segment.startsWith("_")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 技能名 + 技能内相对路径的<b>组合</b>合法性：两者各自合法不代表拼出的 KV key 合法——
     * key 为 {@code /{name}/{rel}}（多两个 “/”），受 {@code agent_fs.item_key VARCHAR(255)} 限制，
     * 故要求 {@code len(name) + len(rel) <= 253}，否则必须在入口 400 而不是落到 KV 写入 500。
     */
    public static boolean isValidSkillIdentity(String name, String relPath) {
        if (!isValidSkillName(name) || !isValidSkillFilePath(relPath)) {
            return false;
        }
        return name.length() + relPath.length() <= MAX_SKILL_KEY_LENGTH;
    }

    // ==================== 用户索引（agent_fs 聚合） ====================

    /**
     * 列出存在 L4 技能覆盖的 userId（按本 agent 命名空间过滤）。
     *
     * <p>命名空间形态：{@code agents<US>{agent}<US>users<US>{uid}<US>skills<US>}（US = 0x1F）。
     *
     * <p>聚合下推到 SQL：外层按（namespace_path, item_key）稳定排序，内层先按 namespace_path
     * 分组取前 {@link #USER_INDEX_SCAN_LIMIT} 个用户（“上限按用户计”而不是按文件行计，
     * 否则文件多的用户会把后面的用户挤出索引）。触顶时 {@code truncated=true}（不静默截断），
     * 并丢弃可能只取到一半的最后一个用户。
     *
     * <p><b>查询失败（SQL/连接不可用）抛 {@link IllegalStateException}</b>，与
     * {@link #readSkill}/{@link #deleteSkill} 同口径——不把「索引存储不可用」降级成
     * 「200 + 空列表」，否则 DB 抖动时调用方（调试页/运维脚本）会把它当成「没有任何用户有个人技能」。
     *
     * @throws IllegalStateException 索引查询失败
     */
    public UserSkillIndex listUsers() {
        var result = new ArrayList<UserSkillUser>();
        if (agentName == null || agentName.isBlank()) {
            return new UserSkillIndex(result, false);
        }
        // namespace_path 前缀：agents<US>{agent}<US>users<US>（LIKE 通配符已转义）
        var prefix = "agents\u001F" + escapeLike(agentName) + "\u001Fusers\u001F";
        var sql = "SELECT f.namespace_path, f.item_key, f.updated_at FROM agent_fs f "
            + "JOIN (SELECT namespace_path FROM agent_fs WHERE namespace_path LIKE ? ESCAPE '\\\\' "
            + "GROUP BY namespace_path ORDER BY namespace_path LIMIT ?) u "
            + "ON u.namespace_path = f.namespace_path "
            + "ORDER BY f.namespace_path, f.item_key LIMIT ?";
        // userId → 技能名集合 / 最近更新时间（保持 SQL 的稳定顺序，便于确定性截断）
        var skillNames = new LinkedHashMap<String, Set<String>>();
        var updatedAt = new LinkedHashMap<String, Long>();
        boolean truncated = false;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, prefix + "%");
            stmt.setInt(2, USER_INDEX_SCAN_LIMIT + 1);
            stmt.setInt(3, USER_INDEX_ROW_LIMIT + 1);
            int rows = 0;
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows++;
                    if (rows > USER_INDEX_ROW_LIMIT) {
                        // 行数触顶：最后一个用户可能只回读了一半，丢弃它（宁可少一个也不给错数）
                        truncated = true;
                        break;
                    }
                    var segments = rs.getString("namespace_path").split("\u001F");
                    // 严格校验段位：agents / {agent} / users / {uid} / skills
                    if (segments.length < 5 || !"agents".equals(segments[0])
                            || !agentName.equals(segments[1]) || !"users".equals(segments[2])
                            || !WorkspaceReader.SKILLS_DIR.equals(segments[4])) {
                        continue;
                    }
                    var userId = segments[3];
                    var skillName = skillNameOf(rs.getString("item_key"));
                    if (userId.isBlank() || skillName == null) {
                        continue;
                    }
                    skillNames.computeIfAbsent(userId, k -> new LinkedHashSet<>()).add(skillName);
                    updatedAt.merge(userId, rs.getLong("updated_at"), Math::max);
                }
            }
        } catch (Exception e) {
            log.warn("list_user_skills users failed: {}", e.getMessage());
            // 必须显式报错：降级成空列表会让「DB 不可用」看起来像「没有任何用户有个人技能」
            throw new IllegalStateException("用户技能索引查询失败: " + e.getMessage(), e);
        }
        if (skillNames.size() > USER_INDEX_SCAN_LIMIT) {
            // 用户数触顶：SQL 多取了 1 个用于判定，这里连同其数据一起丢弃
            truncated = true;
            var extra = new ArrayList<>(skillNames.keySet()).get(skillNames.size() - 1);
            skillNames.remove(extra);
            updatedAt.remove(extra);
        }
        if (truncated) {
            log.warn("list_user_skills users: 索引触顶截断（users>{} 或 rows>{}），返回 {} 个用户",
                USER_INDEX_SCAN_LIMIT, USER_INDEX_ROW_LIMIT, skillNames.size());
        }
        for (var entry : skillNames.entrySet()) {
            var updated = updatedAt.getOrDefault(entry.getKey(), 0L);
            result.add(new UserSkillUser(entry.getKey(), entry.getValue().size(),
                updated > 0 ? new java.sql.Timestamp(updated).toString() : ""));
        }
        return new UserSkillIndex(result, truncated);
    }

    /**
     * 从 item_key 提取技能名（仅认技能主文件 SKILL.md；兼容带/不带前导斜杠两种 key 形态），
     * 元数据目录（.archive/_drafts 等 . 或 _ 开头）不算技能。
     */
    private static String skillNameOf(String itemKey) {
        if (itemKey == null || itemKey.isBlank()) {
            return null;
        }
        var key = itemKey.startsWith("/") ? itemKey.substring(1) : itemKey;
        if (!key.endsWith("/" + WorkspaceReader.SKILL_FILE)) {
            return null;
        }
        var name = key.substring(0, key.length() - WorkspaceReader.SKILL_FILE.length() - 1);
        if (name.isBlank() || name.contains("/") || name.charAt(0) == '.' || name.charAt(0) == '_') {
            return null;
        }
        return name;
    }

    /** MySQL LIKE 通配符转义（配合 ESCAPE '\\'） */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ==================== 技能列表 / 读取 ====================

    /** 列出某用户的 L4 技能（含包内基线标记与管理面写入栅栏标记） */
    public List<UserSkillSummary> listSkills(String userId) {
        var skills = workspaceReader.listUserSkills(userId);
        var result = new ArrayList<UserSkillSummary>(skills.size());
        for (var entry : skills.entrySet()) {
            var name = entry.getKey();
            var files = entry.getValue();
            result.add(new UserSkillSummary(name, files, totalBytes(userId, name, files),
                workspaceReader.userSkillFileVersion(userId, name, WorkspaceReader.SKILL_FILE),
                hasPackageBaseline(name),
                workspaceReader.isUserSkillAdminOverride(userId, name)));
        }
        return result;
    }

    /**
     * 列出该用户带管理面删除标记（tombstone）的技能。
     *
     * <p>删除后技能已不在 L4（{@link #listSkills} 看不到它），但标记仍在 KV 中，且会让沙箱回写
     * 跳过同名技能——不单独暴露，运维无法解释「用户在同代容器内重建同名技能却不落库」，
     * 也不知道该走「管理面重新写入 / 从包内下发」来清除标记。
     */
    public List<UserSkillTombstone> listTombstones(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        var result = new ArrayList<UserSkillTombstone>();
        for (var tombstone : workspaceReader.listUserSkillTombstones(userId)) {
            var at = tombstone.deletedAt();
            result.add(new UserSkillTombstone(tombstone.name(),
                at > 0 ? new java.sql.Timestamp(at).toString() : ""));
        }
        return result;
    }

    /** 技能目录下文件总字节数（UTF-8；读不到的忽略） */
    private long totalBytes(String userId, String name, List<String> files) {
        long total = 0;
        for (var rel : files) {
            var content = workspaceReader.readUserSkillFile(userId, name, rel);
            if (content != null) {
                total += content.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return total;
    }

    /**
     * 读取技能文件内容（L4 优先，无 L4 时回落包内基线）。
     *
     * <p>{@code files}/{@code version}/{@code hasUserOverride} 与 {@code source} 同源：
     * 回落包内基线的分支给的是<b>包内清单</b>（不是 L4 清单），避免同一响应里出现
     * “source=package / hasUserOverride=false / version=0 却带 L4 文件表”的自相矛盾
     * ——调试页据此回填内容，混用会把包内内容写成个人覆盖。
     * 该用户是否另有个人覆盖由 {@code userOverrideExists} 单独表达。
     *
     * @param relPath 技能内相对路径（空则 SKILL.md）
     * @return 内容视图；两侧都不存在返回 empty（调用方映射 404）
     */
    public Optional<UserSkillContent> readSkill(String userId, String name, String relPath) {
        if (userId == null || userId.isBlank() || name == null || name.isBlank()) {
            return Optional.empty();
        }
        var rel = (relPath == null || relPath.isBlank()) ? WorkspaceReader.SKILL_FILE : relPath;
        var userContent = workspaceReader.readUserSkillFile(userId, name, rel);
        var hasOverride = workspaceReader.hasUserSkill(userId, name);
        if (userContent != null) {
            var files = hasOverride
                ? new ArrayList<>(workspaceReader.listUserSkills(userId).getOrDefault(name, List.of()))
                : new ArrayList<>(packageSkillFiles(name));
            return Optional.of(new UserSkillContent(userId, name, userContent, "user", true,
                workspaceReader.userSkillFileVersion(userId, name, WorkspaceReader.SKILL_FILE), files, true));
        }
        var baseline = readPackageSkillFile(name, rel);
        if (baseline == null) {
            return Optional.empty();
        }
        return Optional.of(new UserSkillContent(userId, name, baseline, "package", false, 0L,
            packageSkillFiles(name), hasOverride));
    }

    // ==================== 写入 / 删除 / 下发 ====================

    /**
     * 新建或覆盖某用户的技能主文件（SKILL.md）。
     *
     * <p>写完即置管理面写入栅栏（{@code /{name}/.admin-override}）：沙箱档同代容器内
     * {@code /workspace/skills} 的副本比本次写入旧，回写侧命中栅栏即跳过，避免下一次 call 结束时
     * 把管理面写入改回容器版本（与删除侧的 tombstone 对称）。
     *
     * @return created/updated + 版本号
     * @throws ContentTooLargeException 内容超 100KB
     * @throws IllegalStateException    KV 写入失败（必须显式报错，不能静默）
     */
    public WriteOutcome writeSkill(String userId, String name, String content) {
        if (!isValidUserId(userId) || !isValidSkillName(name)) {
            throw new IllegalArgumentException("无效的用户标识或 Skill 名称");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("内容不能为空");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new ContentTooLargeException("SKILL.md 内容超过 100KB 限制");
        }
        var existed = workspaceReader.hasUserSkill(userId, name);
        if (!workspaceReader.writeUserSkillFile(userId, name, WorkspaceReader.SKILL_FILE, content)) {
            throw new IllegalStateException("L4 技能写入失败（KV " + namespaceHint() + "）");
        }
        // 用户重新创建：清除删除标记，否则沙箱回写侧会一直跳过该技能
        if (!workspaceReader.clearUserSkillDeletion(userId, name)) {
            throw new IllegalStateException("L4 技能已写入但删除标记清除失败（KV " + namespaceHint() + "）");
        }
        // 写侧栅栏：本次写入优先于同代容器内旧副本（清除失败必须显式上报，否则写入会被静默改回）
        if (!workspaceReader.markUserSkillAdminOverride(userId, name)) {
            throw new IllegalStateException("L4 技能已写入但管理面写入栅栏写入失败（KV "
                + namespaceHint() + "）");
        }
        var version = workspaceReader.userSkillFileVersion(userId, name, WorkspaceReader.SKILL_FILE);
        return new WriteOutcome(existed ? "updated" : "created", version);
    }

    /**
     * 删除某用户的 L4 覆盖（全部文件）→ 下一轮同用户回落包内基线。
     *
     * <p>删除同时写入 KV 删除标记（{@code /{name}/.deleted}）：沙箱回写（syncBack）据此跳过同名技能，
     * 否则同代容器内 /workspace/skills 的副本会在该用户下次 call 结束时被原样写回，删除被静默还原。
     * 用户在管理面重新写入（{@link #writeSkill}）或从包内下发（{@link #syncFromPackage}）会清除该标记。
     *
     * <p><b>标记无 TTL、也没有用户侧清除路径</b>：该用户之后在容器内用 skill_manage 重建同名技能
     * 会被回写侧一直跳过（KV 不落库、容器换代即丢）。这是有意的仲裁结果（否则删除会被容器内副本
     * 静默还原），故必须让运维看得见：删除响应带 {@code tombstone} 字段，{@link #listTombstones}
     * 在列表里暴露仍带标记的技能，清除方式只有「管理面重新写入」或「从包内下发」。
     *
     * <p>边界：只删个人覆盖，不动包内（L2）技能；沙箱档下容器内副本仍存活到容器换代
     * （管理面没有“KV → 容器”物化路径，本服务只保证 KV 侧删除生效）。
     *
     * @return 删除文件数；该用户无此 L4 技能返回 empty（调用方映射 404）
     * @throws IllegalStateException 删除失败
     */
    public Optional<Integer> deleteSkill(String userId, String name) {
        if (!isValidUserId(userId) || !isValidSkillName(name)) {
            throw new IllegalArgumentException("无效的用户标识或 Skill 名称");
        }
        if (!workspaceReader.hasUserSkill(userId, name)) {
            return Optional.empty();
        }
        var deleted = workspaceReader.deleteUserSkill(userId, name);
        if (deleted < 0) {
            throw new IllegalStateException("L4 技能删除失败（KV " + namespaceHint() + "）");
        }
        return Optional.of(deleted);
    }

    /**
     * 把包内（L2）同名技能整目录下发为该用户个人版本（含 scripts 等资源文件）。
     *
     * <p>语义是<b>以包内清单为准的全量替换</b>：包内已不存在的旧文件（如换版后删掉的 scripts）
     * 会被差集清理，保证个人目录与包内目录一致；任一环节失败都做 best-effort 回滚
     * （恢复旧内容、删除本次新建的键），不会留下“有 SKILL.md 缺资源”的半份覆盖。
     *
     * <p>非 UTF-8/二进制文件显式跳过（KV 只存字符串，替换成 U+FFFD 等于下发即损坏数据），
     * 跳过清单随 {@link SyncOutcome#skipped()} 返回并打告警日志。
     *
     * @return 下发结果（files / skipped）；包内无此技能返回 empty（调用方映射 404）
     * @throws ContentTooLargeException 单文件超 100KB
     * @throws IllegalArgumentException 包内技能缺 SKILL.md / 文件数超限 / key 组合超长
     * @throws IllegalStateException    源目录不可读或 KV 写入失败
     */
    public Optional<SyncOutcome> syncFromPackage(String userId, String name) {
        if (!isValidUserId(userId) || !isValidSkillName(name)) {
            throw new IllegalArgumentException("无效的用户标识或 Skill 名称");
        }
        var sourceDir = skillManageService.getSkillsDir().resolve(name);
        if (!Files.isDirectory(sourceDir)) {
            return Optional.empty();
        }
        Map<String, String> sourceFiles = new LinkedHashMap<>();
        var packagePaths = new LinkedHashSet<String>();  // 包内文件全集（含跳过项，用于差集清理）
        var skipped = new ArrayList<String>();           // 非 UTF-8/二进制被显式跳过的文件
        try (Stream<Path> stream = Files.walk(sourceDir)) {
            for (var path : stream.filter(Files::isRegularFile).toList()) {
                var rel = sourceDir.relativize(path).toString().replace('\\', '/');
                if (!isValidSkillFilePath(rel) || isMetadataFile(rel)) {
                    continue; // 隐藏/元数据文件（含 .skill-states.json）不参与下发
                }
                if (!isValidSkillIdentity(name, rel)) {
                    // KV key 上限（item_key VARCHAR(255)）：入口即拦，否则会落到 KV 写入 500
                    throw new IllegalArgumentException(
                        "技能名与文件路径合计超过 " + MAX_SKILL_KEY_LENGTH + " 字符: " + rel);
                }
                packagePaths.add(rel);
                var bytes = Files.readAllBytes(path);
                if (bytes.length > MAX_CONTENT_BYTES) {
                    throw new ContentTooLargeException("技能文件 " + rel + " 超过 100KB 限制");
                }
                var content = decodeUtf8Strict(bytes);
                if (content == null) {
                    skipped.add(rel);
                    log.warn("sync_from_package: 跳过非 UTF-8/二进制文件 {}/{}（{} 字节）",
                        name, rel, bytes.length);
                    continue;
                }
                sourceFiles.put(rel, content);
            }
        } catch (ContentTooLargeException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("读取包内技能失败: " + e.getMessage());
        }
        if (sourceFiles.isEmpty() || !sourceFiles.containsKey(WorkspaceReader.SKILL_FILE)) {
            throw new IllegalArgumentException("包内技能 '" + name + "' 缺少 " + WorkspaceReader.SKILL_FILE);
        }
        if (sourceFiles.size() > MAX_SYNC_FILES) {
            throw new IllegalArgumentException("技能文件数超过 " + MAX_SYNC_FILES + " 限制");
        }

        // 下发前取 L4 现有文件的“相对路径 → 内容”快照：既用于差集清理，也用于失败回滚
        Map<String, String> previous = new LinkedHashMap<>();
        for (var rel : workspaceReader.listUserSkills(userId).getOrDefault(name, List.of())) {
            var old = workspaceReader.readUserSkillFile(userId, name, rel);
            if (old != null) {
                previous.put(rel, old);
            }
        }

        // SKILL.md 先写：主文件失败即整体失败（回滚后不留半份覆盖，无 SKILL.md 的目录不算技能）
        var orderedFiles = new LinkedHashMap<String, String>();
        var skillMd = sourceFiles.remove(WorkspaceReader.SKILL_FILE);
        orderedFiles.put(WorkspaceReader.SKILL_FILE, skillMd);
        orderedFiles.putAll(sourceFiles);
        var written = new ArrayList<String>();
        var removed = new ArrayList<String>();
        try {
            for (var entry : orderedFiles.entrySet()) {
                if (!workspaceReader.writeUserSkillFile(userId, name, entry.getKey(), entry.getValue())) {
                    throw new IllegalStateException("下发失败: " + entry.getKey() + "（KV " + namespaceHint() + "）");
                }
                written.add(entry.getKey());
            }
            // 差集清理：包内已不存在的旧文件必须删掉，否则个人版本 ≠ 包内目录（旧 scripts 仍被 L4 覆盖可见）
            for (var rel : previous.keySet()) {
                if (packagePaths.contains(rel)) {
                    continue;
                }
                if (!workspaceReader.deleteUserSkillFile(userId, name, rel)) {
                    throw new IllegalStateException(
                        "清理包内已不存在的旧文件失败: " + rel + "（KV " + namespaceHint() + "）");
                }
                removed.add(rel);
            }
        } catch (RuntimeException e) {
            rollbackSync(userId, name, previous, written, removed);
            throw e;
        }
        // 个人版本已重建：清除删除标记，否则沙箱回写侧会一直跳过该技能；并置管理面写入栅栏，
        // 避免同代容器内旧副本在下次 call 结束时把刚下发的版本改回容器版本
        if (!workspaceReader.clearUserSkillDeletion(userId, name)) {
            throw new IllegalStateException("下发成功但删除标记清除失败（KV " + namespaceHint() + "）");
        }
        if (!workspaceReader.markUserSkillAdminOverride(userId, name)) {
            throw new IllegalStateException("下发成功但管理面写入栅栏写入失败（KV " + namespaceHint() + "）");
        }
        log.info("sync_from_package: skill {} ({} files, {} skipped) → user {}",
            name, orderedFiles.size(), skipped.size(), userId);
        // 与 files() 同样按字典序返回：跳过清单源自 Files.walk 的枚举顺序，
        // 不同文件系统不一致（CI runner 上即出现顺序差异），会给出不确定的对外结果
        java.util.Collections.sort(skipped);
        return Optional.of(new SyncOutcome(new ArrayList<>(orderedFiles.keySet()), skipped));
    }

    /**
     * 下发中途失败时的 best-effort 回滚：恢复旧内容、删除本次新建的键、还原被差集删掉的旧文件。
     * 回滚自身的失败只告警（原始异常才是要上报给调用方的原因），但逐键记录便于人工核对。
     */
    private void rollbackSync(String userId, String name, Map<String, String> previous,
                              List<String> written, List<String> removed) {
        for (var rel : written) {
            var old = previous.get(rel);
            try {
                if (old != null) {
                    if (!workspaceReader.writeUserSkillFile(userId, name, rel, old)) {
                        log.warn("sync_from_package rollback: 恢复 {} 失败（user {} skill {}）", rel, userId, name);
                    }
                } else if (!workspaceReader.deleteUserSkillFile(userId, name, rel)) {
                    log.warn("sync_from_package rollback: 删除新建键 {} 失败（user {} skill {}）", rel, userId, name);
                }
            } catch (Exception e) {
                log.warn("sync_from_package rollback: {} 回滚异常: {}", rel, e.getMessage());
            }
        }
        for (var rel : removed) {
            var old = previous.get(rel);
            if (old == null) {
                continue;
            }
            try {
                if (!workspaceReader.writeUserSkillFile(userId, name, rel, old)) {
                    log.warn("sync_from_package rollback: 还原被清理的 {} 失败（user {} skill {}）", rel, userId, name);
                }
            } catch (Exception e) {
                log.warn("sync_from_package rollback: {} 还原异常: {}", rel, e.getMessage());
            }
        }
        log.warn("sync_from_package rollback: user {} skill {} 已回滚 {} 个写入 / {} 个删除",
            userId, name, written.size(), removed.size());
    }

    /**
     * 严格 UTF-8 解码：非法字节序列（二进制资源 / GBK 等非 UTF-8 文本）返回 null，
     * 绝不静默替换成 U+FFFD——那样下发即损坏数据，且调用方看不到任何异常。
     */
    private static String decodeUtf8Strict(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }

    // ==================== 包内（L2）基线 ====================

    /**
     * 包内是否存在同名技能（删除覆盖后会回落到的基线）。
     *
     * <p>判定口径与 {@link #syncFromPackage} / 框架技能判定一致：目录内必须有 SKILL.md。
     * 只判断目录存在会把“无 SKILL.md 的目录”标成有基线，删除后实际回落不到任何技能，
     * 调试页与删除提示都会与事实不符。
     */
    public boolean hasPackageBaseline(String name) {
        if (!isValidSkillName(name)) {
            return false;
        }
        return Files.isRegularFile(
            skillManageService.getSkillsDir().resolve(name).resolve(WorkspaceReader.SKILL_FILE));
    }

    /** 包内技能的文件相对路径列表（无此技能返回空列表） */
    public List<String> packageSkillFiles(String name) {
        var files = new ArrayList<String>();
        if (!isValidSkillName(name)) {
            return files;
        }
        var dir = skillManageService.getSkillsDir().resolve(name);
        if (!Files.isDirectory(dir)) {
            return files;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            for (var path : stream.filter(Files::isRegularFile).toList()) {
                var rel = dir.relativize(path).toString().replace('\\', '/');
                if (isMetadataFile(rel)) {
                    continue;
                }
                files.add(rel);
            }
        } catch (IOException e) {
            log.warn("package_skill_files failed for {}: {}", name, e.getMessage());
        }
        java.util.Collections.sort(files);
        return files;
    }

    /** 读取包内技能文件内容；不存在返回 null（路径越界返回 null） */
    public String readPackageSkillFile(String name, String relPath) {
        if (!isValidSkillName(name) || !isValidSkillFilePath(relPath)) {
            return null;
        }
        var root = skillManageService.getSkillsDir().normalize();
        var target = root.resolve(name).resolve(relPath).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            return null;
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("read_package_skill_file failed for {}/{}: {}", name, relPath, e.getMessage());
            return null;
        }
    }

    // ==================== 内部方法 ====================

    /** 元数据/隐藏文件：任一路径段以 . 或 _ 开头（.skill-states.json / .archive / _drafts） */
    private static boolean isMetadataFile(String relPath) {
        for (var segment : relPath.replace('\\', '/').split("/")) {
            if (segment.startsWith(".") || segment.startsWith("_")) {
                return true;
            }
        }
        return false;
    }

    /** 日志用命名空间提示（agent 名缺失时说明回落形态） */
    private String namespaceHint() {
        return (agentName == null || agentName.isBlank())
            ? "agents/{agent}/users/{uid}/skills，agent 名未配置→裸 userId"
            : "agents/" + agentName + "/users/{uid}/skills";
    }
}
