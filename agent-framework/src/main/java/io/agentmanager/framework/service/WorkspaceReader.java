package io.agentmanager.framework.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;

/**
 * 运行时工作区文件读写：MEMORY.md / memory/ 的 KV 读取与沙箱注入；
 * 以及用户技能（L4，{@code agents/{agent}/users/{uid}/skills}）的 KV 读写。
 *
 * KV 命名空间与 RemoteFilesystemSpec(IsolationScope.USER) 一致：
 * 直接复用框架 RemoteFilesystem(baseStore, List.of(userId))，避免手工拼接 key。
 */
public class WorkspaceReader {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceReader.class);

    /** 运行时文件路径（与回写范围对称） */
    public static final String MEMORY_FILE = "MEMORY.md";
    public static final String MEMORY_DIR = "memory";

    /** 用户技能（L4）KV 布局：技能名目录 + 技能主文件（与 SDK workspace-writable 仓库一致） */
    public static final String SKILLS_DIR = "skills";
    public static final String SKILL_FILE = "SKILL.md";

    /**
     * 元数据目录前缀：SDK 软删归档 {@code skills/.archive}、草稿 {@code skills/_drafts}
     * 均以 {@code .}/{@code _} 开头，SDK 侧（hasMetadataAncestor）同样跳过，枚举时必须一并过滤，
     * 否则会把归档/草稿当成用户技能。
     */
    private static boolean isMetadataSegment(String segment) {
        return segment != null && !segment.isEmpty()
            && (segment.charAt(0) == '.' || segment.charAt(0) == '_');
    }

    private final BaseStore baseStore;

    /**
     * agent 名称：框架 {@code RemoteFilesystemSpec(IsolationScope.USER)} 的 KV 命名空间首段
     * （实测结构 {@code agents/{agentName}/users/{userId}/...}）。Agent 侧读写必须与其一致，
     * 否则写进裸 userId 命名空间、读时却查不到（e2e-ci-plan §11.3 D3 的第二处断裂）。
     * 未配置（单测/无 OAF 场景）时回落裸 userId 命名空间，保持旧行为可用。
     */
    private final String agentName;

    public WorkspaceReader(DistributedStore distributedStore) {
        this(distributedStore, null);
    }

    public WorkspaceReader(DistributedStore distributedStore, String agentName) {
        this.baseStore = distributedStore.baseStore();
        this.agentName = agentName;
    }

    /** KV 命名空间段：有 agentName 时与框架一致（agents/{agentName}/users/{userId}），否则裸 userId */
    private List<String> namespaceFor(String safeUserKey) {
        if (agentName == null || agentName.isBlank()) {
            return List.of(safeUserKey);
        }
        return List.of("agents", agentName, "users", safeUserKey);
    }

    /**
     * 用户技能（L4）KV 命名空间：{@code agents/{agent}/users/{uid}/skills}。
     *
     * <p>与 SDK workspace-writable 技能仓库一致（DB 实测 namespace_path 以 skills 段结尾）；
     * item_key 带前导斜杠（{@code /{name}/SKILL.md}），与 {@link RemoteFilesystem#write} 原样
     * 落 key 的行为一致——本类所有技能路径都带前导斜杠，不带则静默读不到。
     */
    private List<String> skillNamespaceFor(String safeUserKey) {
        var ns = new ArrayList<>(namespaceFor(safeUserKey));
        ns.add(SKILLS_DIR);
        return ns;
    }

    /** 技能文件在 KV 中的 key：{@code /{name}/{relPath}}（前导斜杠，去除 relPath 上多余的斜杠） */
    private static String skillFileKey(String name, String relPath) {
        var rel = relPath == null ? "" : relPath;
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        return "/" + name + "/" + rel;
    }

    /**
     * 归一化 ls/glob 返回的路径：去掉前导 “/”（read/delete 需要的相对形态）
     * 与尾部 “/”（ls 的目录项形如 {@code /demo-a/}）。
     */
    private static String stripSlash(String path) {
        if (path == null) {
            return null;
        }
        var normalized = path;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 从 KV 读取某用户的运行时文件（MEMORY.md + memory/*.md）。
     * 返回相对路径 → 内容字节。
     */
    public Map<String, byte[]> readRuntimeFiles(String userId) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        var safeUserId = io.agentmanager.framework.util.PathSafe.sanitize(userId);
        var ctx = RuntimeContext.builder().userId(safeUserId).build();
        try {
            var fs = new RemoteFilesystem(baseStore, namespaceFor(safeUserId));

            // 直接 read 判断存在（exists() 对相对路径返回 false，不可靠）
            var memoryRead = fs.read(ctx, MEMORY_FILE, 0, -1);
            if (memoryRead.isSuccess() && memoryRead.fileData() != null && memoryRead.fileData().content() != null) {
                files.put(MEMORY_FILE, memoryRead.fileData().content().getBytes(StandardCharsets.UTF_8));
            }

            var ls = fs.ls(ctx, "/" + MEMORY_DIR);
            if (ls.isSuccess()) {
                for (var info : ls.entries()) {
                    // RemoteFilesystem.ls 返回前导 "/" 的完整相对路径，read 需去掉
                    var rel = info.path().startsWith("/") ? info.path().substring(1) : info.path();
                    if (rel.endsWith(".md")) {
                        var read = fs.read(ctx, rel, 0, -1);
                        if (read.isSuccess() && read.fileData() != null && read.fileData().content() != null) {
                            files.put(rel, read.fileData().content().getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to read runtime files for user {}: {}", userId, e.getMessage());
        }
        return files;
    }

    /**
     * 读取某用户 KV 工作区的任意文件（present_file 等工具用）。
     * 返回完整字节（≤ 单文件上限，调用方自行限制）；不存在/失败返回 null。
     */
    public byte[] readWorkspaceFile(String userKey, String relPath) {
        if (userKey == null || userKey.isBlank() || relPath == null || relPath.isBlank()) {
            return null;
        }
        try {
            var safeUserKey = io.agentmanager.framework.util.PathSafe.sanitize(userKey);
            var ctx = RuntimeContext.builder().userId(safeUserKey).build();
            var fs = new RemoteFilesystem(baseStore, namespaceFor(safeUserKey));
            var res = fs.read(ctx, relPath, 0, -1);
            if (res.isSuccess() && res.fileData() != null && res.fileData().content() != null) {
                return res.fileData().content().getBytes(StandardCharsets.UTF_8);
            }
            // 读失败时打出实际命名空间，便于定位写读 key 不一致（D3 排查沉淀）
            log.info("read_workspace_file: miss {} in namespace {} (success={}, err={})",
                relPath, namespaceFor(safeUserKey), res.isSuccess(),
                res.isSuccess() ? "-" : String.valueOf(res.error()));
            return null;
        } catch (Exception e) {
            log.warn("Failed to read workspace file {} for user {}: {}", relPath, userKey, e.getMessage());
            return null;
        }
    }

    /**
     * 将文件内容写入某用户 KV 工作区（供 write_file 同步回 KV 等场景使用）。
     *
     * <p>写入语义与 {@link RemoteFilesystem#write} 一致：文件不存在则创建，已存在则全量覆盖。
     *
     * @return true 写入成功；false 参数非法或写入失败
     */
    public boolean writeWorkspaceFile(String userKey, String relPath, String content) {
        if (userKey == null || userKey.isBlank() || relPath == null || relPath.isBlank() || content == null) {
            return false;
        }
        try {
            var safeUserKey = io.agentmanager.framework.util.PathSafe.sanitize(userKey);
            var ctx = RuntimeContext.builder().userId(safeUserKey).build();
            var fs = new RemoteFilesystem(baseStore, namespaceFor(safeUserKey));
            return writeKvFile(fs, ctx, relPath, content, "write_workspace_file", safeUserKey);
        } catch (Exception e) {
            log.warn("write_workspace_file: KV write failed for {} user {}: {}", relPath, userKey, e.getMessage());
            return false;
        }
    }

    /**
     * KV 文件写入公共实现（工作区文件与 L4 技能文件共用，避免危险逻辑二次拷贝）：
     * 文件不存在 → write 创建；已存在 → edit 全量替换。
     *
     * <p><b>空内容 / 内容相同必须短路</b>：SDK 的 edit 会对 old 做 countOccurrences 扫描，
     * 而 countOccurrences 在 needle 为空串时死循环（indexOf("", i) 恒返回 i，游标不前进
     * → CPU 100% 挂死，2026-09-09 e2e 实测 F5 用例即此路径）。所有 KV 写入路径都必须走这里。
     *
     * <p><b>但「KV 已有空内容 → 写入非空内容」不得短路</b>：write 对已存在 key 返回失败、
     * edit 又会踩上面的空 needle 死循环，故该分支必须 delete 后 write（版本号会重置）。
     * 曾把这一半并进短路：调用方拿到 true、KV 里却仍是空内容（静默丢写，容器换代后数据永久丢失）。
     *
     * @return true 写入成功（含“内容完全相同”的短路跳过）；false 写入失败
     */
    private boolean writeKvFile(RemoteFilesystem fs, RuntimeContext ctx, String relPath, String content,
                                String opTag, String safeUserKey) {
        var read = fs.read(ctx, relPath, 0, -1);
        boolean success;
        if (read.isSuccess() && read.fileData() != null && read.fileData().content() != null) {
            var current = read.fileData().content();
            if (current.equals(content)) {
                log.info("{}: skip edit (content unchanged, {} chars) for user {}", opTag,
                    content.length(), safeUserKey);
                return true;
            }
            if (current.isEmpty()) {
                // KV 已有空内容且新内容非空 → delete + write（不能走 edit，空 old 会死循环）
                var delRes = fs.delete(ctx, relPath);
                if (!delRes.isSuccess()) {
                    log.warn("{}: KV delete-before-write failed for {} user {}: {}", opTag, relPath,
                        safeUserKey, delRes.error());
                    return false;
                }
                var writeRes = fs.write(ctx, relPath, content);
                success = writeRes.isSuccess();
                if (!success) {
                    log.warn("{}: KV write failed for {} user {}: {}", opTag, relPath, safeUserKey,
                        writeRes.error());
                }
            } else {
                // 文件已存在且内容不同 → edit 全量替换
                var editRes = fs.edit(ctx, relPath, current, content, false);
                success = editRes.isSuccess();
                if (!success) {
                    log.warn("{}: KV edit failed for {} user {}: {}", opTag, relPath, safeUserKey, editRes.error());
                }
            }
        } else {
            // 文件不存在 → write 创建
            var writeRes = fs.write(ctx, relPath, content);
            success = writeRes.isSuccess();
            if (!success) {
                log.warn("{}: KV write failed for {} user {}: {}", opTag, relPath, safeUserKey, writeRes.error());
            }
        }
        if (success) {
            log.info("{}: synced {} ({} chars) to KV for user {}", opTag, relPath, content.length(), safeUserKey);
        }
        return success;
    }

    // ==================== 用户技能（L4）KV 读写 ====================
    // 布局（与 SDK workspace-writable 技能仓库一致，DB 实测）：
    //   namespace = agents/{agent}/users/{uid}/skills
    //   key       = /{技能名}/SKILL.md（前导斜杠；资源文件同规则，如 /{技能名}/scripts/a.sh）
    // 非沙箱档推理时框架合并 L2（包内 /config/skills）与 L4，同名 L4 覆盖 L2；沙箱档
    // （SANDBOX_ENABLED=true）会话读的是容器内 /workspace/skills 副本，管理面写进这里的 L4
    // 需“会话开始物化 L4”能力（尚未实现）才对会话生效——本层只保证 KV 读写本身正确。
    // 两个元数据键（均以 . 开头，不算技能文件）承载回写仲裁：.deleted（管理面删除）与
    // .admin-override（管理面写入），sandbox 回写侧命中任一即跳过同名技能。

    /** 显式删除标记（tombstone）：技能目录下的元数据键，回写侧据此跳过同名技能 */
    private static final String DELETION_MARKER = ".deleted";

    /**
     * 管理面写入栅栏（admin override fence）：技能目录下的元数据键，管理面 PUT/下发时写入，
     * 回写侧据此跳过同名技能——否则同代容器内旧副本会在下一次 call 结束时把管理面写入改回容器版本
     * （DELETE 有 {@link #DELETION_MARKER} 防护，写入侧此前无对称防护）。
     */
    private static final String ADMIN_OVERRIDE_MARKER = ".admin-override";

    /**
     * 列出某用户 L4 技能：技能名 → 该技能下文件相对路径（含 SKILL.md，字典序）。
     *
     * <p>命名空间为空时 {@code ls} 返回失败而非空集（框架语义），此处按“无技能”处理，
     * 否则新用户会 500。仅保留含 SKILL.md 的目录（与 SDK 技能判定一致），
     * 并过滤 {@code .archive}/{@code _drafts} 等元数据目录。
     *
     * <p>单个技能的文件枚举失败（glob 不可信）时抛 {@link IllegalStateException}：
     * 不得把“枚举失败”当成“该技能只有 SKILL.md”返回，否则列表/字节数会偏小且删除会漏行。
     */
    public Map<String, List<String>> listUserSkills(String userId) {
        Map<String, List<String>> skills = new LinkedHashMap<>();
        if (userId == null || userId.isBlank()) {
            return skills;
        }
        var safeUserId = io.agentmanager.framework.util.PathSafe.sanitize(userId);
        var ctx = RuntimeContext.builder().userId(safeUserId).build();
        var fs = new RemoteFilesystem(baseStore, skillNamespaceFor(safeUserId));
        List<io.agentscope.harness.agent.filesystem.model.FileInfo> entries;
        try {
            var ls = fs.ls(ctx, "/");
            if (!ls.isSuccess()) {
                return skills;
            }
            entries = ls.entries();
        } catch (Exception e) {
            // 空命名空间/列举失败按“无技能”处理（框架语义），否则新用户会 500
            log.warn("list_user_skills: failed for user {}: {}", safeUserId, e.getMessage());
            return skills;
        }
        for (var info : entries) {
            var name = stripSlash(info.path());
            if (name == null || name.isBlank() || name.contains("/") || isMetadataSegment(name)) {
                continue;
            }
            var files = listSkillFiles(fs, ctx, name);
            if (files == null) {
                // 枚举失败不得降级：抛出去由调用方显式报错（列表漏文件 / 删除漏行都不可接受）
                throw new IllegalStateException("枚举技能文件失败（KV glob 不可信）: " + name);
            }
            if (!files.contains(SKILL_FILE)) {
                continue; // 无 SKILL.md 不算技能
            }
            skills.put(name, files);
        }
        return skills;
    }

    /**
     * 列出单个 L4 技能下的文件相对路径（glob 递归）。
     *
     * <p><b>“枚举失败”与“无文件”必须区分</b>：glob 抛异常/返回失败时不得降级成“该技能只有
     * SKILL.md”——那会让列表漏文件、删除漏删 KV 行却按成功上报。此时若技能主文件确实存在，
     * 返回 <b>null</b>（调用方必须显式报错）；技能确实不存在（新建用户/空命名空间）返回空列表。
     *
     * @return 文件相对路径（字典序）；枚举不可信且技能存在时返回 null
     */
    private List<String> listSkillFiles(RemoteFilesystem fs, RuntimeContext ctx, String name) {
        var files = new ArrayList<String>();
        boolean enumerated = false;
        try {
            // glob 返回的是**完整 key**（带前导 “/” 与技能名前缀），需按前缀裁掉技能名
            var glob = fs.glob(ctx, "**/*", "/" + name);
            if (glob.isSuccess()) {
                enumerated = true;
                var prefix = name + "/";
                for (var info : glob.matches()) {
                    var rel = stripSlash(info.path());
                    if (rel == null || rel.isEmpty()) {
                        continue;
                    }
                    if (rel.startsWith(prefix)) {
                        rel = rel.substring(prefix.length());
                    }
                    if (rel.isEmpty() || isMetadataSegment(rel)) {
                        continue;
                    }
                    files.add(rel);
                }
            } else {
                log.warn("list_user_skills: glob unsuccessful for skill {}: {}", name, glob.error());
            }
        } catch (Exception e) {
            log.warn("list_user_skills: glob failed for skill {}: {}", name, e.getMessage());
        }
        if (enumerated) {
            if (!files.contains(SKILL_FILE)) {
                // glob 成功但漏了主文件（适配器口径差异）→ 直接探测补齐，不影响“枚举可信”的结论
                var read = fs.read(ctx, skillFileKey(name, SKILL_FILE), 0, -1);
                if (read.isSuccess() && read.fileData() != null && read.fileData().content() != null) {
                    files.add(SKILL_FILE);
                }
            }
            Collections.sort(files);
            return files;
        }
        // 枚举不可信 → 只探测技能主文件（read 为精确 key 查询，无回退）：
        // 存在则返回 null（枚举结果不可用于列表/删除），不存在按“无此技能”处理
        try {
            var read = fs.read(ctx, skillFileKey(name, SKILL_FILE), 0, -1);
            if (read.isSuccess() && read.fileData() != null && read.fileData().content() != null) {
                return null;
            }
            return files;
        } catch (Exception e) {
            log.warn("list_user_skills: probe {} failed for skill {}: {}", SKILL_FILE, name, e.getMessage());
            return null;
        }
    }

    /**
     * 读取 L4 技能文件内容（{@code relPath} 相对技能目录，如 SKILL.md / scripts/a.sh）。
     *
     * <p><b>“不存在”与“读失败”必须区分</b>：读失败（KV 不可用）抛 {@link IllegalStateException}，
     * 不得返回 null——否则管理面会把 DB 宕机静默报成“无此技能”（404），与写路径要求显式报错的设计不一致。
     *
     * @return 内容；文件不存在返回 null
     * @throws IllegalStateException KV 读取失败
     */
    public String readUserSkillFile(String userId, String name, String relPath) {
        if (userId == null || userId.isBlank() || name == null || name.isBlank()
                || relPath == null || relPath.isBlank()) {
            return null;
        }
        var safeUserId = io.agentmanager.framework.util.PathSafe.sanitize(userId);
        var ctx = RuntimeContext.builder().userId(safeUserId).build();
        try {
            var fs = new RemoteFilesystem(baseStore, skillNamespaceFor(safeUserId));
            var res = fs.read(ctx, skillFileKey(name, relPath), 0, -1);
            if (res.isSuccess() && res.fileData() != null && res.fileData().content() != null) {
                return res.fileData().content();
            }
            return null;
        } catch (Exception e) {
            log.warn("read_user_skill_file: failed for user {} skill {}: {}", safeUserId, name, e.getMessage());
            throw new IllegalStateException("L4 技能读取失败（KV 不可用）: " + name + "/" + relPath, e);
        }
    }

    /**
     * 写入 L4 技能文件（不存在创建 / 已存在全量覆盖），走 {@link #writeKvFile} 公共实现。
     *
     * @return true 写入成功；false 参数非法或写入失败（调用方必须向用户报错，不能静默）
     */
    public boolean writeUserSkillFile(String userId, String name, String relPath, String content) {
        if (userId == null || userId.isBlank() || name == null || name.isBlank()
                || relPath == null || relPath.isBlank() || content == null) {
            return false;
        }
        var safeUserId = io.agentmanager.framework.util.PathSafe.sanitize(userId);
        var ctx = RuntimeContext.builder().userId(safeUserId).build();
        try {
            var fs = new RemoteFilesystem(baseStore, skillNamespaceFor(safeUserId));
            return writeKvFile(fs, ctx, skillFileKey(name, relPath), content,
                "write_user_skill_file", safeUserId);
        } catch (Exception e) {
            log.warn("write_user_skill_file: failed for user {} skill {}: {}", safeUserId, name, e.getMessage());
            return false;
        }
    }

    /**
     * 读取 L4 技能文件版本号（BaseStore 每次写入自增；{@link RemoteFilesystem} 不返回 version）。
     *
     * @return 版本号；不存在返回 0
     */
    public long userSkillFileVersion(String userId, String name, String relPath) {
        if (userId == null || userId.isBlank() || name == null || name.isBlank()
                || relPath == null || relPath.isBlank()) {
            return 0L;
        }
        var safeUserId = io.agentmanager.framework.util.PathSafe.sanitize(userId);
        try {
            var item = baseStore.get(skillNamespaceFor(safeUserId), skillFileKey(name, relPath));
            return item != null ? item.version() : 0L;
        } catch (Exception e) {
            log.warn("user_skill_file_version: failed for user {} skill {}: {}", safeUserId, name, e.getMessage());
            return 0L;
        }
    }

    /** 判断某用户是否存在 L4 版本的技能主文件 */
    public boolean hasUserSkill(String userId, String name) {
        return readUserSkillFile(userId, name, SKILL_FILE) != null;
    }

    /**
     * 删除某用户 L4 技能的全部文件（框架 delete 语义：命中 key 本身或其子路径，
     * 因此逐个文件 key 删除即可，避免依赖目录语义），成功后写入显式删除标记
     * （{@code /{name}/.deleted}），供沙箱回写侧识别“用户已删除该技能”；
     * 同时清除管理面写入栅栏（{@code /{name}/.admin-override}）——删除后由删除标记接管防护，
     * 残留栅栏会让人误以为管理面内容仍在保护期内。
     *
     * @return 删除的文件数（不含删除标记）；失败返回 -1（-1 与 0 必须区分：0 = 无覆盖，-1 = 删除失败）
     */
    public int deleteUserSkill(String userId, String name) {
        if (userId == null || userId.isBlank() || name == null || name.isBlank()) {
            return -1;
        }
        var safeUserId = io.agentmanager.framework.util.PathSafe.sanitize(userId);
        var ctx = RuntimeContext.builder().userId(safeUserId).build();
        try {
            var fs = new RemoteFilesystem(baseStore, skillNamespaceFor(safeUserId));
            var files = listSkillFiles(fs, ctx, name);
            if (files == null) {
                log.warn("delete_user_skill: 文件枚举失败，拒绝按“已全部删除”上报（user {} skill {}）",
                    safeUserId, name);
                return -1;
            }
            // 先写删除标记：回写侧据此刻跳过同名技能，避免“同代容器内副本把刚删除的覆盖写回（复活）”。
            // 中途失败时 KV 里仍是旧内容（重试删除即可），但回写不会把它覆盖成容器内的版本。
            if (!markUserSkillDeleted(userId, name)) {
                log.warn("delete_user_skill: 写删除标记失败（user {} skill {}）", safeUserId, name);
                return -1;
            }
            // 栅栏随删除一并清除（删除标记已接管防护）：清除失败只告警——回写仍被删除标记拦住，
            // 不会复活；残留栅栏只影响可见性提示，不值得把已完成的删除报成失败
            if (!clearUserSkillAdminOverride(userId, name)) {
                log.warn("delete_user_skill: 清除管理面写入栅栏失败（user {} skill {}），删除标记仍生效",
                    safeUserId, name);
            }
            int deleted = 0;
            for (var rel : files) {
                if (!deleteUserSkillFile(userId, name, rel)) {
                    log.warn("delete_user_skill: delete {} failed for user {} skill {}", rel, safeUserId, name);
                    return -1;
                }
                deleted++;
            }
            log.info("delete_user_skill: removed {} file(s) of skill {} for user {} (tombstone written)",
                deleted, name, safeUserId);
            return deleted;
        } catch (Exception e) {
            log.warn("delete_user_skill: failed for user {} skill {}: {}", safeUserId, name, e.getMessage());
            return -1;
        }
    }

    /**
     * 删除 L4 技能下的单个文件（下发差集清理 / 回滚用）。
     *
     * @return true 删除成功；false 参数非法或删除失败（调用方必须显式报错）
     */
    public boolean deleteUserSkillFile(String userId, String name, String relPath) {
        if (userId == null || userId.isBlank() || name == null || name.isBlank()
                || relPath == null || relPath.isBlank()) {
            return false;
        }
        var safeUserId = io.agentmanager.framework.util.PathSafe.sanitize(userId);
        var ctx = RuntimeContext.builder().userId(safeUserId).build();
        try {
            var fs = new RemoteFilesystem(baseStore, skillNamespaceFor(safeUserId));
            var res = fs.delete(ctx, skillFileKey(name, relPath));
            if (!res.isSuccess()) {
                log.warn("delete_user_skill_file: {} failed for user {} skill {}: {}",
                    relPath, safeUserId, name, res.error());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("delete_user_skill_file: failed for user {} skill {} {}: {}",
                safeUserId, name, relPath, e.getMessage());
            return false;
        }
    }

    /**
     * 写显式删除标记（tombstone，键 {@code /{name}/.deleted}，值为写入时间毫秒）：管理面删除个人覆盖后，
     * 沙箱回写（syncBack）据此刻跳过同名技能，否则同代容器内副本会在下次 call 结束时把覆盖写回 KV
     * （表现：删除接口成功、紧接着 GET 也为包内基线，用户再发一条消息后覆盖“复活”）。
     *
     * <p>键以 {@code .} 开头属元数据段，不会被 {@link #listUserSkills} 当成技能文件。
     *
     * @return true 写入成功；false 参数非法或写入失败（调用方必须显式报错）
     */
    public boolean markUserSkillDeleted(String userId, String name) {
        return writeUserSkillFile(userId, name, DELETION_MARKER, String.valueOf(System.currentTimeMillis()));
    }

    /** 该用户该技能是否带显式删除标记（沙箱回写侧据此跳过写回） */
    public boolean isUserSkillDeleted(String userId, String name) {
        return readUserSkillFile(userId, name, DELETION_MARKER) != null;
    }

    /**
     * 写管理面写入栅栏（键 {@code /{name}/.admin-override}，值为写入时间毫秒）：管理面 PUT/下发
     * 覆盖该技能后，沙箱回写（syncBack）据此跳过同名技能——同代容器内 {@code /workspace/skills}
     * 的副本比管理面写入旧，照旧回写会把管理面内容改回容器版本（DELETE 侧有删除标记防护，
     * 这是与其对称的写侧仲裁）。
     *
     * <p>键以 {@code .} 开头属元数据段，不会被 {@link #listUserSkills} 当成技能文件，
     * 也不会被 {@link #deleteUserSkill} 的逐文件删除命中（清理由删除流程显式做）。
     *
     * @return true 写入成功；false 参数非法或写入失败（调用方必须显式报错）
     */
    public boolean markUserSkillAdminOverride(String userId, String name) {
        return writeUserSkillFile(userId, name, ADMIN_OVERRIDE_MARKER,
            String.valueOf(System.currentTimeMillis()));
    }

    /** 该用户该技能是否带管理面写入栅栏（沙箱回写侧据此跳过写回，保留管理面内容） */
    public boolean isUserSkillAdminOverride(String userId, String name) {
        return readUserSkillFile(userId, name, ADMIN_OVERRIDE_MARKER) != null;
    }

    /**
     * 清除管理面写入栅栏（管理面删除该技能时调用：删除后由删除标记接管防护，栅栏不再有意义）。
     *
     * @return true 已清除或本无栅栏；false 清除失败（调用方必须显式报错）
     */
    public boolean clearUserSkillAdminOverride(String userId, String name) {
        if (userId == null || userId.isBlank() || name == null || name.isBlank()) {
            return false;
        }
        if (!isUserSkillAdminOverride(userId, name)) {
            return true; // 无栅栏，无需清除
        }
        return deleteUserSkillFile(userId, name, ADMIN_OVERRIDE_MARKER);
    }

    /** 管理面删除标记视图项：技能名 + 标记写入时间（毫秒；值不可解析为 0） */
    public record Tombstone(String name, long deletedAt) {}

    /**
     * 枚举某用户带删除标记（tombstone）的技能：删除后该技能不再出现在 {@link #listUserSkills}
     * （目录里只剩元数据键），若不单独暴露，运维看不到「已删除、但标记仍在」的状态——该用户
     * 在同代容器内用 skill_manage 重建同名技能会被回写侧一直跳过（不落库）。
     *
     * <p>枚举方式与 {@link #listUserSkills} 一致（ls 顶层目录 + 精确 key 读标记）；
     * 标记读失败（KV 不可用）抛 {@link IllegalStateException}，不降级成「无标记」——
     * 否则调用方会把「有标记」显示成「无标记」，与读路径的显式报错口径一致。
     */
    public List<Tombstone> listUserSkillTombstones(String userId) {
        var result = new ArrayList<Tombstone>();
        if (userId == null || userId.isBlank()) {
            return result;
        }
        var safeUserId = io.agentmanager.framework.util.PathSafe.sanitize(userId);
        var ctx = RuntimeContext.builder().userId(safeUserId).build();
        var fs = new RemoteFilesystem(baseStore, skillNamespaceFor(safeUserId));
        List<io.agentscope.harness.agent.filesystem.model.FileInfo> entries;
        try {
            var ls = fs.ls(ctx, "/");
            if (!ls.isSuccess()) {
                return result; // 空命名空间（该用户从无 L4）按「无标记」处理，与 listUserSkills 同口径
            }
            entries = ls.entries();
        } catch (Exception e) {
            log.warn("list_user_skill_tombstones: failed for user {}: {}", safeUserId, e.getMessage());
            return result;
        }
        for (var info : entries) {
            var name = stripSlash(info.path());
            if (name == null || name.isBlank() || name.contains("/") || isMetadataSegment(name)) {
                continue;
            }
            var marker = readUserSkillFile(userId, name, DELETION_MARKER);
            if (marker != null) {
                result.add(new Tombstone(name, parseMarkerTime(marker)));
            }
        }
        return result;
    }

    /** 标记值解析为毫秒时间戳；不可解析（旧格式/异常写入）返回 0，不抛异常（时间只服务可见性） */
    private static long parseMarkerTime(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 清除显式删除标记（用户在管理面重新创建该技能时调用）。
     *
     * @return true 已清除或本无标记；false 清除失败（调用方必须显式报错）
     */
    public boolean clearUserSkillDeletion(String userId, String name) {
        if (userId == null || userId.isBlank() || name == null || name.isBlank()) {
            return false;
        }
        if (!isUserSkillDeleted(userId, name)) {
            return true; // 无标记，无需清除
        }
        return deleteUserSkillFile(userId, name, DELETION_MARKER);
    }

    /**
     * 将运行时文件注入沙箱 /workspace（SDK 文件 API）。
     * 静态模板（AGENTS.md/skills/ 等）由框架投影注入，不在此处理。
     */
    public void injectToSandbox(com.alibaba.opensandbox.sandbox.Sandbox osbSandbox,
                                Map<String, byte[]> files) {
        if (files.isEmpty()) {
            return;
        }
        var entries = files.entrySet().stream()
            .map(e -> com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry.builder()
                .path("/workspace/" + e.getKey())
                .data(new String(e.getValue(), StandardCharsets.UTF_8))
                .mode(644)
                .build())
            .toList();
        osbSandbox.files().write(entries);
        log.info("Injected {} runtime files into sandbox", entries.size());
    }
}
