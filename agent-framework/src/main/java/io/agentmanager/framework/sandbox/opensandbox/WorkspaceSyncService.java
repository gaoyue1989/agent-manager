package io.agentmanager.framework.sandbox.opensandbox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo;

import io.agentmanager.framework.service.UserSkillService;
import io.agentmanager.framework.service.WorkspaceReader;

/**
 * 沙箱 → KV 回写同步服务。
 * 每次用户请求完成后调用：从沙箱 /workspace 拉取运行时文件（MEMORY.md、memory/）
 * 与用户技能（skills/，L4），写入 agent_fs 表，保证 Agent 服务无状态 + 记忆/技能持久化。
 *
 * <p>命名空间与读取侧严格一致（禁止手拼 key）：运行时文件走
 * {@link WorkspaceReader#writeWorkspaceFile}（agents/{agent}/users/{uid}），
 * 技能走 {@link WorkspaceReader#writeUserSkillFile}（agents/{agent}/users/{uid}/skills，
 * key = /{技能名}/{相对路径}）。
 *
 * <p>为什么必须回写 skills/：沙箱模式下框架把整个工作区文件系统换成容器文件系统
 * （HarnessAgent.Builder.build 在 sandboxFilesystemSpec 非空时用 SandboxBackedFilesystem
 * 覆盖 RemoteFilesystemSpec 的 KV 路由），skill_manage 的 workspace-writable 技能仓库
 * 因此把 L4 写进容器 /workspace/skills/；不回写则技能只存活于容器 TTL 内、agent_fs 永远为空。
 *
 * <p>边界：仅做“沙箱 → KV”的写入/覆盖（增量镜像），不做删除对账——新沙箱代里可能只投影了
 * 包内 L2 基线而没有 L4 副本，此时按差集删除 KV 行会误删用户覆盖（数据丢失），
 * 故删除语义留给显式 API：管理面删除会写入 KV 删除标记（{@code /{name}/.deleted}），
 * 本服务遇标记即跳过同名技能的回写，避免“删除后又被容器内旧副本写回（复活）”。
 *
 * <p>与删除对称，管理面写入（PUT / 从包内下发）会写 KV 写入栅栏
 * （{@code /{name}/.admin-override}，见 {@link WorkspaceReader#markUserSkillAdminOverride}），
 * 本服务命中即同样跳过：沙箱档会话读的是容器内副本，管理面写入不回注容器，若照旧回写，
 * 同代容器内的旧副本会在下一次 call 结束时把管理面写入改回容器版本。
 * 代价是该技能在容器内用 skill_manage 的后续修改也不再落库，直到管理面 DELETE 该技能
 * （删除会清除栅栏，见 {@link WorkspaceReader#deleteUserSkill}）。
 */
public class WorkspaceSyncService {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceSyncService.class);

    /** 技能目录递归深度上限（异常目录结构防御） */
    private static final int MAX_SKILL_DEPTH = 5;
    /** 单个技能回写文件数上限（与 UserSkillService.syncFromPackage 对齐） */
    private static final int MAX_SKILL_FILES = UserSkillService.MAX_SYNC_FILES;
    /** 单文件回写上限（与技能 API 同口径，超限跳过不阻塞） */
    private static final int MAX_SKILL_FILE_BYTES = UserSkillService.MAX_CONTENT_BYTES;
    /** 单技能回写总字节上限（与技能 API 同口径，超限跳过整个技能） */
    private static final long MAX_SKILL_TOTAL_BYTES = UserSkillService.MAX_SKILL_TOTAL_BYTES;

    private final WorkspaceReader workspaceReader;
    /** 快照差分开关（SANDBOX_SYNC_SNAPSHOT_DIFF，默认 true；false=每次全量读容器 skills/） */
    private final boolean snapshotDiffEnabled;

    public WorkspaceSyncService(WorkspaceReader workspaceReader) {
        this(workspaceReader, true);
    }

    public WorkspaceSyncService(WorkspaceReader workspaceReader, boolean snapshotDiffEnabled) {
        this.workspaceReader = workspaceReader;
        this.snapshotDiffEnabled = snapshotDiffEnabled;
    }

    /**
     * 每次用户请求完成后调用（同步 invoke 返回后 / 流式 AGENT_END 处）。
     * 从沙箱拉取 MEMORY.md + memory/*.md + skills/**，写回 KV。
     *
     * @param gate 会话水合门控（②′ 快照差分：非空且含 manifest 时只读与快照不一致的文件；
     *             null = 全量读=现行为）
     */
    public void syncBack(String userId, com.alibaba.opensandbox.sandbox.Sandbox osbSandbox,
                         SkillSyncGate gate) {
        if (userId == null || userId.isBlank() || osbSandbox == null || workspaceReader == null) {
            return;
        }
        var root = "/workspace/";
        try {
            // 记忆总开关关闭（AGENT_MEMORY_ENABLED=false）：跳过 MEMORY.md + memory/ 回写，
            // 技能回写（syncUserSkills）不受影响、保持原样
            if (workspaceReader.isMemoryEnabled()) {
                syncRuntimeFiles(userId, osbSandbox, root);
            }
            syncUserSkills(userId, osbSandbox, root, gate);
            log.info("Workspace sync back completed for user {}", userId);
        } catch (Exception e) {
            // 回写失败不阻塞主流程：沙箱内数据保留，下次 call 或销毁前补偿
            log.warn("Workspace sync back failed for user {}: {}", userId, e.getMessage());
        }
    }

    /** 兼容入口（无快照差分，全量读=现行为） */
    public void syncBack(String userId, com.alibaba.opensandbox.sandbox.Sandbox osbSandbox) {
        syncBack(userId, osbSandbox, null);
    }

    // ==================== 运行时文件（MEMORY.md / memory/） ====================

    private void syncRuntimeFiles(String userId, com.alibaba.opensandbox.sandbox.Sandbox osbSandbox, String root) {
        // 1. MEMORY.md
        try {
            if (osbSandbox.files().readByteArray(root + WorkspaceReader.MEMORY_FILE) != null) {
                var content = osbSandbox.files().readFile(root + WorkspaceReader.MEMORY_FILE);
                syncRuntimeFile(userId, WorkspaceReader.MEMORY_FILE, content);
            }
        } catch (Exception e) {
            // 文件不存在则跳过（首次使用无记忆）
            log.debug("MEMORY.md sync skipped: {}", e.getMessage());
        }

        // 2. memory/*.md（listDirectory 返回沙箱内完整路径；个别适配器返回相对 /workspace 的路径，
        //    见 toSandboxPath —— 相对条目必须带 memory/ 前缀，否则会读成 /workspace/<name>.md）
        try {
            var entries = osbSandbox.files().listDirectory(root + WorkspaceReader.MEMORY_DIR);
            if (entries != null) {
                for (var entry : entries) {
                    // 单条目异常隔离：一条读失败不能中断整轮，否则同目录其余记忆被静默丢弃
                    try {
                        if (entry.getPath() == null || !entry.getPath().endsWith(".md")) {
                            continue;
                        }
                        var sandboxPath = toSandboxPath(entry.getPath(), root);
                        var relPath = sandboxPath.substring(root.length());
                        var content = osbSandbox.files().readFile(sandboxPath);
                        syncRuntimeFile(userId, relPath, content);
                    } catch (Exception e) {
                        log.warn("memory/ sync: 条目 {} 跳过: {}", entry.getPath(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("memory/ sync skipped: {}", e.getMessage());
        }
    }

    /** 回写单个运行时文件（命名空间/key 由 WorkspaceReader 决定，不在此处拼接） */
    private void syncRuntimeFile(String userId, String relPath, String content) {
        if (content == null) {
            return;
        }
        if (workspaceReader.writeWorkspaceFile(userId, relPath, content)) {
            // 成功路径已由 WorkspaceReader 打 INFO，这里只留调试轨迹，避免双份日志
            log.debug("[sync-back] runtime file {} ({} chars) synced to KV", relPath, content.length());
        }
    }

    // ==================== 用户技能（L4，skills/） ====================

    /**
     * 回写沙箱内 skills/ 下的用户技能到 L4 KV。
     *
     * <p>只认含 SKILL.md 的技能目录（与 SDK 技能判定一致），逐段过滤以 . / _ 开头的
     * 元数据目录与文件（.archive / _drafts / .audit / .usage.json / .skills-cache）。
     * 目录不存在或列举失败仅跳过（不阻塞、也不做任何删除）。
     *
     * <p>快照差分（SANDBOX_SYNC_SNAPSHOT_DIFF，默认 true）：gate 非空时先遍历容器 skills/
     * 的 manifest（仅 listDirectory 元数据，实测 0.75ms/op），只对「与快照不一致」的文件
     * 读内容回写——稳态零内容读（实测 449ms → ~64ms）。manifest 缺失/为空 = 未知 → 全读
     * （现行为）；同字节数的原位内容漂移不在差分感知范围（设计 §7 已评估接受）。
     */
    private void syncUserSkills(String userId, com.alibaba.opensandbox.sandbox.Sandbox osbSandbox, String root,
                                SkillSyncGate gate) {
        var skillsRoot = root + WorkspaceReader.SKILLS_DIR;
        List<EntryInfo> entries;
        try {
            entries = osbSandbox.files().listDirectory(skillsRoot);
        } catch (Exception e) {
            // 沙箱内无 skills 目录（未用过技能）时 execd 返回 404
            log.debug("skills/ sync skipped: {}", e.getMessage());
            return;
        }
        if (entries == null || entries.isEmpty()) {
            return;
        }
        var snapshotDiff = gate != null && snapshotDiffEnabled && gate.manifest() != null;
        int syncedSkills = 0;
        for (var entry : entries) {
            var name = leafName(entry.getPath());
            if (name == null || isMetadataSegment(name)) {
                continue;
            }
            if (!UserSkillService.isValidSkillName(name)) {
                log.warn("skills/ sync: skip illegal skill dir name {}", name);
                continue;
            }
            // 不按条目类型预筛：目录判定交给 listDirectory 是否成功（execd 的 type 口径不一）
            if (syncOneSkill(userId, osbSandbox, skillsRoot + "/" + name, name, snapshotDiff ? gate : null)) {
                syncedSkills++;
            }
        }
        if (syncedSkills > 0) {
            log.info("[sync-back] {} skill(s) synced to L4 KV for user {}", syncedSkills, userId);
        }
    }

    /** 回写单个技能目录；返回是否写出至少一个文件（SKILL.md 写失败即整体判失败并中止）。
     *  gate 非空走快照差分（只读与快照不一致的文件），null 走全量读（现行为） */
    private boolean syncOneSkill(String userId, com.alibaba.opensandbox.sandbox.Sandbox osbSandbox,
                                 String skillDir, String name, SkillSyncGate gate) {
        // 管理面已显式删除该技能（KV 删除标记）：跳过回写，避免同代容器内副本把覆盖“复活”
        if (workspaceReader.isUserSkillDeleted(userId, name)) {
            log.info("skills/ sync: {} 已被管理面显式删除（tombstone），跳过回写", name);
            return false;
        }
        // 管理面已禁用该技能（启停标记）：跳过回写，避免把容器内旧副本重新落库
        if (workspaceReader.isUserSkillDisabled(userId, name)) {
            log.info("skills/ sync: {} 已被管理面禁用（disabled），跳过回写", name);
            return false;
        }
        // 管理面已写入该技能（KV 写入栅栏）：同代容器内副本比管理面写入旧，回写即把管理面内容
        // 改回容器版本（本机无集群时的代码推断，E2E 9.6~9.8 为现场验证入口）
        if (workspaceReader.isUserSkillAdminOverride(userId, name)) {
            log.info("skills/ sync: {} 带管理面写入栅栏（admin-override），跳过回写（保留管理面内容）", name);
            return false;
        }
        // 相对路径 → 文件内容（差分模式下只读与快照不一致的文件；读取失败/超限的不进入结果集）
        Map<String, String> files = new LinkedHashMap<>();
        collectSkillFiles(osbSandbox, skillDir, "", files, 0, gate);
        if (files.isEmpty() || !files.containsKey(WorkspaceReader.SKILL_FILE)) {
            // 无 SKILL.md 不算技能（同 SDK / WorkspaceReader.listUserSkills 判定）。
            // 差分模式下 SKILL.md 未变时不进 files：此时快照必然已含它（快照只能来自
            // 一次含 SKILL.md 的全读/物化记录），因此这里只在「快照为空」（= 该技能从未
            // 全读过的异常态，如 KV 行被外部清空）时跳过；快照含 SKILL.md 则按增量继续
            // （只回写真正变化的资源文件，主文件留在 KV 不动）。
            var snapshotHasSkillFile = gate != null && gate.manifest().containsKey(WorkspaceReader.SKILL_FILE);
            if (!snapshotHasSkillFile) {
                log.debug("skills/ sync: {} 缺少 {}，跳过", name, WorkspaceReader.SKILL_FILE);
                return false;
            }
        }
        if (files.size() > MAX_SKILL_FILES) {
            log.warn("skills/ sync: {} 文件数 {} 超过上限 {}，跳过", name, files.size(), MAX_SKILL_FILES);
            return false;
        }
        long totalBytes = 0;
        for (var value : files.values()) {
            totalBytes += value.getBytes(StandardCharsets.UTF_8).length;
        }
        if (totalBytes > MAX_SKILL_TOTAL_BYTES) {
            log.warn("skills/ sync: {} 总大小 {} 字节超过上限 {}，跳过", name, totalBytes, MAX_SKILL_TOTAL_BYTES);
            return false;
        }
        // SKILL.md 先写且失败即中止：否则 KV 会留下“有 scripts 资源、无 SKILL.md”的半份覆盖
        // （这类孤儿行 listUserSkills/deleteSkill 都以 SKILL.md 为前提，管理面既看不到也删不掉）
        var ordered = new ArrayList<>(files.entrySet());
        ordered.sort(Comparator.comparing(e -> !WorkspaceReader.SKILL_FILE.equals(e.getKey())));
        int written = 0;
        for (var entry : ordered) {
            boolean ok;
            try {
                ok = workspaceReader.writeUserSkillFile(userId, name, entry.getKey(), entry.getValue());
                if (ok) {
                    log.info("[sync-back] skill file {}/{} ({} chars) synced to L4 KV", name, entry.getKey(),
                        entry.getValue().length());
                }
            } catch (Exception e) {
                log.warn("skills/ sync: {}/{} 回写失败: {}", name, entry.getKey(), e.getMessage());
                ok = false;
            }
            if (ok) {
                written++;
                continue;
            }
            if (WorkspaceReader.SKILL_FILE.equals(entry.getKey())) {
                // 主文件失败：中止该技能本次回写，不写其余资源文件（与 syncFromPackage 的 fail-fast 同序）
                log.warn("skills/ sync: {}/{} 回写失败，中止该技能本次回写（已写 {} 个文件）",
                    name, WorkspaceReader.SKILL_FILE, written);
                // 中止前清掉该技能本轮回写涉及的 manifest 记录：KV 未落库的文件若留在快照里，
                // 下轮差分会按"未变"跳过内容读，容器内数据永远回不去（快照必须只记已落库状态）
                removeSkillEntriesFromGate(gate, files.keySet());
                return false;
            }
            log.warn("skills/ sync: {}/{} 回写失败，继续其余文件（下次 call 重试）", name, entry.getKey());
        }
        return written > 0;
    }

    /** 从快照移除本轮回写失败的条目（relPath 即 manifest key，不含技能名前缀——collectSkillFiles 以技能目录为根），防止"未落库被当已同步" */
    private static void removeSkillEntriesFromGate(SkillSyncGate gate, java.util.Set<String> relPaths) {
        if (gate == null) {
            return;
        }
        for (var rel : relPaths) {
            gate.manifest().remove(rel);
        }
    }

    /**
     * 递归收集技能目录下的文件（相对路径 → 内容）。
     * 任一路径段以 . / _ 开头即整体跳过（元数据/草稿，与 UserSkillService.isMetadataFile 同规则）。
     *
     * <p>目录判定双保险：类型字段优先，读取失败时回落按目录递归——execd 的 type 口径若不匹配
     * （例如把目录标成非 directory），技能目录不会被静默漏掉。
     *
     * <p>快照差分（gate 非空）：先 listDirectory 拿条目元数据（含 size），与 gate manifest
     * 一致的文件跳过内容读（同时把本次看到的 size 记回快照，含未读文件）；不一致/未知才读。
     * gate 为 null 时行为与旧版完全一致（全部读内容）。
     */
    private void collectSkillFiles(com.alibaba.opensandbox.sandbox.Sandbox osbSandbox, String dirPath,
                                   String relPrefix, Map<String, String> out, int depth, SkillSyncGate gate) {
        if (depth > MAX_SKILL_DEPTH) {
            log.warn("skills/ sync: 目录层级超过 {} 层，跳过 {}", MAX_SKILL_DEPTH, dirPath);
            return;
        }
        List<EntryInfo> entries;
        try {
            entries = osbSandbox.files().listDirectory(dirPath);
        } catch (Exception e) {
            log.debug("skills/ sync: 列举 {} 失败: {}", dirPath, e.getMessage());
            return;
        }
        if (entries == null) {
            return;
        }
        for (var entry : entries) {
            var leaf = leafName(entry.getPath());
            if (leaf == null || isMetadataSegment(leaf)) {
                continue;
            }
            var absPath = dirPath + "/" + leaf;
            var relPath = relPrefix + leaf;
            if (isDirectoryEntry(entry)) {
                collectSkillFiles(osbSandbox, absPath, relPath + "/", out, depth + 1, gate);
                continue;
            }
            long entrySize = entry.getSize();
            if (gate != null) {
                var known = gate.manifest().get(relPath);
                if (known != null && known.longValue() == entrySize) {
                    // 与快照一致：不读内容（同时刷新快照口径——size 字段以本次列举为准）
                    gate.manifest().put(relPath, entrySize);
                    continue;
                }
            }
            var content = readFileOrNull(osbSandbox, absPath, entry.getSize());
            if (content != null && !content.isEmpty()) {
                out.put(relPath, content);
                if (gate != null) {
                    // 读到内容才记 manifest：读失败回落目录递归的条目不能记——
                    // 若记了 size，下轮差分会把它当"未变文件"跳过内容读，目录内文件漏镜像
                    gate.manifest().put(relPath, entrySize);
                }
            } else {
                // 读不到或空内容：可能是目录被判成文件（type 口径差异 / 目录读成空串），
                // 回落按目录递归；真空文件会被跳过（技能资源里空文件无意义）。
                // 不记 manifest：下轮仍按未知条目重试读取/递归
                collectSkillFiles(osbSandbox, absPath, relPath + "/", out, depth + 1, gate);
            }
        }
    }

    /** 读取技能文件内容；不存在/目录/超限/异常统一返回 null（调用方据此回落目录递归） */
    private static String readFileOrNull(com.alibaba.opensandbox.sandbox.Sandbox osbSandbox,
                                         String absPath, long size) {
        if (size > MAX_SKILL_FILE_BYTES) {
            log.warn("skills/ sync: {} 超过 {} 字节上限，跳过", absPath, MAX_SKILL_FILE_BYTES);
            return null;
        }
        try {
            var content = osbSandbox.files().readFile(absPath);
            if (content == null) {
                return null;
            }
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_SKILL_FILE_BYTES) {
                log.warn("skills/ sync: {} 超过 {} 字节上限，跳过", absPath, MAX_SKILL_FILE_BYTES);
                return null;
            }
            return content;
        } catch (Exception e) {
            log.debug("skills/ sync: 读取 {} 失败: {}", absPath, e.getMessage());
            return null;
        }
    }

    // ==================== 路径工具 ====================

    /** 统一为沙箱内绝对路径（实测 listDirectory 有的适配器返回相对 /workspace 的路径） */
    private static String toSandboxPath(String path, String root) {
        return path.startsWith("/") ? path : root + path;
    }

    /** 取路径末段（去尾斜杠；非目录条目也兼容）；非法输入返回 null */
    private static String leafName(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        var trimmed = path;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        var idx = trimmed.lastIndexOf('/');
        var leaf = idx >= 0 ? trimmed.substring(idx + 1) : trimmed;
        return leaf.isBlank() ? null : leaf;
    }

    /** 元数据/隐藏段判定：以 . 或 _ 开头（.archive / _drafts / .audit / .usage.json） */
    private static boolean isMetadataSegment(String segment) {
        return segment != null && !segment.isEmpty()
            && (segment.charAt(0) == '.' || segment.charAt(0) == '_');
    }

    /** 目录条目判定：优先 type 字段（execd 返回 directory/file），缺失时回落尾斜杠 */
    private static boolean isDirectoryEntry(EntryInfo entry) {
        var type = entry.getType();
        if (type != null && !type.isBlank()) {
            return "directory".equalsIgnoreCase(type) || "dir".equalsIgnoreCase(type);
        }
        return entry.getPath() != null && entry.getPath().endsWith("/");
    }
}

