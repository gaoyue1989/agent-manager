package io.agentmanager.framework.sandbox.opensandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.opensandbox.sandbox.Sandbox;

import io.agentmanager.framework.service.WorkspaceReader;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;

/**
 * 软链农场子系统（skills-syncd §6.2：会话级二值判定 + 软链农场收敛），OpenSandbox 的
 * 内聚协作者：判定（store/端点探测、版本硬校验、D-5 打点、会话级记忆）+ 收敛（候选链、
 * 单条 exec 幂等建链、稳态短路记账）+ 投放过滤原语（禁用解析/清理命令/shell 安全路径——
 * PR-4b 起 tar 投放路径已退役，本类是这些原语的唯一消费方）。唯一入口 {@link #tryHydrate}：
 * 农场未启用或判定降级返回 false——PR-4b 后包技能仅 farm 一路，调用方<b>不回落 tar</b>
 * （降级会话 = 无包技能投放，回滚语义 = 回滚整个 release），命中则完成收敛并返回 true。
 */
final class SkillsFarm {

    private static final Logger log = LoggerFactory.getLogger(SkillsFarm.class);

    /** 农场判定打点（D-5）：会话级二值判定结果 Counter，farm 命中率是池漏配/灰度进度的唯一非日志信号 */
    private static final String DECISION_METRIC = "skills.farm.decision";
    /** store 版本目录命名前缀（skills-syncd §4.4 {@code v-<version>}）；版本比对仅做字符串相等（§7.2 SSOT 边界） */
    private static final String STORE_VERSION_DIR_PREFIX = "v-";

    private final OpenSandboxClientOptions options;
    private final Sandbox osbSandbox;
    /** 会话用户 L4 仓储（nullable）；判定源枚举与稳态指纹 revision 分量的来源 */
    private final WorkspaceReader workspaceReader;
    /** Pod 级投放记账表（nullable）；稳态短路跨 call 记忆 */
    private final OpenSandboxFilesystemSpec filesystemSpec;
    /** 实例绑定用户键（原始值，无兜底；指纹/禁用判定同源） */
    private final Supplier<String> userKey;
    /** 沙箱代 id（gate 键与记账代际分量） */
    private final Supplier<String> sandboxId;

    SkillsFarm(OpenSandboxClientOptions options,
               Sandbox osbSandbox,
               WorkspaceReader workspaceReader,
               OpenSandboxFilesystemSpec filesystemSpec,
               Supplier<String> userKey,
               Supplier<String> sandboxId) {
        this.options = options;
        this.osbSandbox = osbSandbox;
        this.workspaceReader = workspaceReader;
        this.filesystemSpec = filesystemSpec;
        this.userKey = userKey;
        this.sandboxId = sandboxId;
    }

    /**
     * 农场投放入口：启用且本会话判定为 farm 时完成软链收敛并返回 true；未启用/无 gate/
     * 判定降级返回 false（PR-4b 后调用方不再回落 tar——本会话无包技能投放）。收敛 exec
     * 失败原样上抛（上层 CAS 复位，下次触发重试），判定不变。
     */
    boolean tryHydrate(String scope, Path scopeDir) throws Exception {
        if (!enabled()) {
            return false;
        }
        var uid = sessionUserKey();
        var gate = filesystemSpec != null
            ? filesystemSpec.skillSyncGate(uid, sandboxId.get()) : null;
        if (gate == null || !acquireMode(gate, uid)) {
            return false;
        }
        hydrate(scope, scopeDir, gate);
        return true;
    }

    /** 农场开关（§6.4，PR-4b 语义）：SANDBOX_SKILLS_SHARED_STORE 空 = 包技能不投放（总闸，无 tar 回落） */
    private boolean enabled() {
        var store = options != null ? options.getSkillsSharedStore() : null;
        return store != null && !store.isBlank();
    }

    /** 农场会话键（与 session_user 规范键同源）：middleware/ctx 已解析的 userKey，缺失时 spec ThreadLocal 兜底 */
    private String sessionUserKey() {
        var bound = userKey.get();
        if (bound != null && !bound.isBlank()) {
            return bound;
        }
        return filesystemSpec != null ? filesystemSpec.peekPendingUserKey() : null;
    }

    /**
     * 会话级二值判定（B-1，§6.2/§7.2）：CAS 首触点执行一次——readlink store/CURRENT 可解析
     * 且与端点指纹（D-4 短 TTL）目录名字符串相等 → 本会话软链模式；任一失败（store 缺失 /
     * 端点不可达 / 版本不一致）→ 本会话整体无包技能投放。判定按会话键记忆于 gate：
     * 降级会话不中途翻转，新会话（新 sandboxId 换新 gate）重新判定。
     */
    private boolean acquireMode(SkillSyncGate gate, String uid) {
        var remembered = gate.skillFarmState();
        if (remembered != null) {
            return remembered.farm();
        }
        var decision = decideMode(uid);
        gate.setSkillFarmState(new SkillSyncGate.SkillFarmState(decision.farm(), decision.l4Names()));
        recordDecision(decision.result());
        return decision.farm();
    }

    private record FarmDecision(boolean farm, String result, Set<String> l4Names) {}

    private FarmDecision decideMode(String uid) {
        String endpointVersion = null;
        try {
            var svc = options.getSkillBundleService();
            endpointVersion = svc == null ? null : svc.currentVersion().version();
            if (endpointVersion == null
                    || endpointVersion.startsWith(SkillBundleService.ERROR_VERSION_PREFIX)) {
                throw new IllegalStateException("skill bundle endpoint unavailable (version="
                    + endpointVersion + ")");
            }
        } catch (Exception e) {
            log.warn("[sandbox-open] skills farm endpoint version unavailable: {}", e.getMessage());
            return new FarmDecision(false, "degraded_endpoint_down", Set.of());
        }
        var store = probeStore(options.getSkillsSharedStore().trim());
        if (store.version() == null) {
            // store 无 CURRENT 二分：store 目录由守护启动时创建——目录存在 = 守护活着但同步
            // 失败，第一根因即守护→端点链路不可达（token 漂移 401 / 端点关闭 404 / egress
            // 断网），归 degraded_endpoint_down（runbook 信号直指端点链路）；目录缺失 = 守护
            // 未装/未跑（或 store 卷未挂），归 degraded_store_missing。exec 探测异常按目录
            // 缺失保守处理（fail-open 同桶）。
            if (store.dirPresent()) {
                return new FarmDecision(false, "degraded_endpoint_down", Set.of());
            }
            return new FarmDecision(false, "degraded_store_missing", Set.of());
        }
        if (!(STORE_VERSION_DIR_PREFIX + endpointVersion).equals(store.version())) {
            log.error("[sandbox-open] skills farm version hard-check failed (store={}, endpoint={}): "
                + "store treated as unusable, session has no package skills this session",
                store.version(), endpointVersion);
            return new FarmDecision(false, "degraded_version_mismatch", Set.of());
        }
        return new FarmDecision(true, "farm", captureL4SkillNames(uid));
    }

    /** store 探测结果：CURRENT 版本目录名（null = 无 CURRENT/readlink 未命中）+ 目录存在性 */
    private record StoreProbe(String version, boolean dirPresent) {}

    /**
     * store 探测（§7.2 SSOT 边界）：{@code readlink <store>/CURRENT} 的目录名，仅做字符串
     * 相等比较、禁止在 Java 侧重算 store 内容指纹。readlink 未命中（store 无 CURRENT）时以
     * {@code test -d} 补一次目录存在性探测，区分「守护活着但同步失败」与「守护未装/未跑」——
     * farm 正常路径（readlink 命中）不跑 test -d，无额外 exec。
     */
    private StoreProbe probeStore(String store) {
        try {
            var execution = osbSandbox.commands().run("readlink '" + store + "/CURRENT'");
            if (execution.getExitCode() != null && execution.getExitCode() == 0) {
                var stdout = execution.getLogs() != null && execution.getLogs().getStdout() != null
                    ? execution.getLogs().getStdout().stream()
                        .map(msg -> msg.getText())
                        .collect(Collectors.joining("\n"))
                    : "";
                var token = stdout.trim();
                if (!token.isEmpty()) {
                    int slash = token.lastIndexOf('/');
                    return new StoreProbe(slash >= 0 ? token.substring(slash + 1) : token, true);
                }
            }
        } catch (Exception e) {
            log.debug("[sandbox-open] skills farm store probe failed: {}", e.getMessage());
            return new StoreProbe(null, false);
        }
        boolean dirPresent = false;
        try {
            var probe = osbSandbox.commands().run("test -d '" + store + "'");
            dirPresent = probe.getExitCode() != null && probe.getExitCode() == 0;
        } catch (Exception e) {
            log.debug("[sandbox-open] skills farm store dir probe failed: {}", e.getMessage());
        }
        return new StoreProbe(null, dirPresent);
    }

    /**
     * 同名 L4 优先的判定源（规则 1）：判定时刻的用户 L4 技能名集，会话级快照（农场会话全程
     * 复用）。枚举失败按空集 fail-soft——同名裁决最终仍由 SDK 消费层兜底（workspace 优先），
     * 农场建链只是收敛面收窄。
     */
    private Set<String> captureL4SkillNames(String uid) {
        if (workspaceReader == null || uid == null || uid.isBlank()) {
            return Set.of();
        }
        try {
            return Set.copyOf(workspaceReader.listUserSkills(uid).keySet());
        } catch (Exception e) {
            log.warn("[sandbox-open] skills farm L4 name capture failed (user={}): {}", uid, e.getMessage());
            return Set.of();
        }
    }

    /** 判定打点（D-5）：Counter skills.farm.decision{result}；每次降级判定同步落一行结构化 WARN */
    private void recordDecision(String result) {
        var registry = options != null ? options.getFarmMeterRegistry() : null;
        if (registry != null) {
            registry.counter(DECISION_METRIC, "result", result).increment();
        }
        if (!"farm".equals(result)) {
            log.warn("[sandbox-open] skills farm decision degraded (result={})", result);
        }
    }

    /**
     * 农场链候选（相对 scopeDir 的 ns/name，排序稳定）：宿主 scope 目录即 SDK stage manifest，
     * 其内全部为包技能（workspace-native L4 不进 .skills-cache）。禁用名（{@link
     * #disabledSkillDirs} 同源）与 L4 同名（规则 1 真实文件保护）不建链；含单引号/空白/{@code ..}
     * 的相对路径跳过并 WARN 留痕——tar 物化已退役，farm 侧静默缺失即会话内真实缺失，
     * 必须留痕可观测。
     */
    private List<String> linkCandidates(Path scopeDir, Set<Path> disabledDirs,
                                        Set<String> l4Names) throws Exception {
        if (!Files.isDirectory(scopeDir)) {
            return List.of();
        }
        var candidates = new java.util.TreeSet<String>();
        try (var nsStream = Files.list(scopeDir)) {
            for (var ns : nsStream.filter(Files::isDirectory).sorted().toList()) {
                try (var skillStream = Files.list(ns)) {
                    for (var skillDir : skillStream.filter(Files::isDirectory).sorted().toList()) {
                        var rel = scopeDir.relativize(skillDir).toString().replace('\\', '/');
                        if (!isShellSafeRelPath(rel)) {
                            log.warn("[sandbox-open] skills farm link candidate skipped, rel not shell-safe (rel={})",
                                rel);
                            continue;
                        }
                        if (disabledDirs.contains(scopeDir.relativize(skillDir))
                                || l4Names.contains(skillDir.getFileName().toString())) {
                            continue;
                        }
                        candidates.add(rel);
                    }
                }
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * 软链农场投放（规则 1-5，单条 exec 幂等收敛）：对 manifest 内的包技能路径有全权——
     * 目标态为软链 {@code <store>/CURRENT/<skill>}（B-4 间接寻址不钉版本目录），路径现存任何物
     * （缺失 / 悬空 / 降级会话残留真实文件）统一 rm 后重建（D-2）。禁用判定复用
     * {@link #disabledSkillDirs}：禁用名不建链且随 exec 清理既有软链（B-2，会话中途禁用生效——
     * 用户级禁用经 L4 revision bump 进指纹；全局禁用进端点版本，版本变化触发重收敛主动清理）；
     * prune 与禁用清理复用容器内清理语义。exec 失败上抛（CAS 复位，下次触发重试），判定不变。
     *
     * <p><b>稳态短路（D-10，设计修订否决原 H2「每次触发收敛」口径）</b>：稳态指纹 =
     * {@link #farmFingerprint}（端点版本 + L4 revision，PR-4b 拆分后包内容身份唯一 SSOT 在
     * 端点侧）+ sandboxId 代际记账（复用 scoped 指纹表，Pod 级 miss 经跨副本共享层兜底），记账带
     * farm 模式分量（D-11：本代降级会话的记账不满足 farm 稳态——降级↔farm 翻转后代际必须重收敛，
     * 把降级残留真实文件副本替换为软链，D-2/§6.2-4）。指纹未变且本沙箱代已收敛过当前 scope →
     * 容器链接即最新（B-4 间接寻址，版本切换经 CURRENT 即见）→ 跳过收敛 exec。收敛触发面 = 新
     * 沙箱代（记账随新 sandboxId 清零）/ 模式翻转 / 指纹变化 / exec 失败不记账。会话中途容器重启
     * （同 sandboxId resume）软链不重建为已接受盲点——与投影指纹缓存 §3.3 系统性盲点同姿态；
     * per-call 容器侧标记兜底被否决（观测容器态本身即 1 次 exec RTT）。
     */
    private void hydrate(String scope, Path scopeDir, SkillSyncGate gate) throws Exception {
        String fingerprint;
        try {
            fingerprint = farmFingerprint(userKey.get());
        } catch (Exception e) {
            // 端点瞬时不可达：无法判定稳态 → 仍收敛（幂等安全：链目标经 CURRENT 间接寻址，
            // store 内容由守护维护）；不记账（record 对 null 指纹为 no-op），下次触发重新判定
            log.warn("[sandbox-open] skills farm fingerprint unavailable ({}), converging anyway",
                e.getMessage());
            fingerprint = null;
        }
        String id = sandboxId.get();
        // 稳态判定（D-10 + 模式分量）：指纹未变 且 本沙箱代以 farm 模式收敛过当前 scope → 容器链接即最新
        String podFp = filesystemSpec.scopedSkillCacheFingerprint(id, scope,
            OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM);
        boolean steady = fingerprint != null && podFp != null && podFp.equals(fingerprint)
            && scope.equals(filesystemSpec.hydratedScopedScope(id));
        if (steady) {
            log.debug("[sandbox-open] skills farm fingerprint unchanged ({}), skipping converge", scope);
            return;
        }
        var disabledDirs = disabledSkillDirs(
            options.getSkillDisabledChecker(), userKey.get(), scopeDir);
        Set<String> l4Names = gate != null && gate.skillFarmState() != null
            ? gate.skillFarmState().l4Names()
            : Set.of();
        var linkCmd = new StringBuilder();
        var candidates = linkCandidates(scopeDir, disabledDirs, l4Names);
        for (var rel : candidates) {
            if (linkCmd.length() > 0) {
                linkCmd.append(" && ");
            }
            String containerPath = options.getWorkspaceRoot() + "/" + SkillCacheScope.CACHE_DIR + "/" + scope + "/" + rel;
            String parentDir = containerPath.substring(0, containerPath.lastIndexOf('/'));
            String target = options.getSkillsSharedStore().trim() + "/CURRENT/" + OpenSandbox.leaf(rel);
            linkCmd.append("mkdir -p '").append(parentDir).append("'")
                .append(" && rm -rf '").append(containerPath).append("'")
                .append(" && ln -s '").append(target).append("' '").append(containerPath).append("'");
        }
        String pruneCmd = "find " + options.getWorkspaceRoot() + "/" + SkillCacheScope.CACHE_DIR
            + " -mindepth 1 -maxdepth 1 -type d ! -name '" + scope + "' -exec rm -rf {} + ; true";
        String rmCmd = disabledRmCmd(scope, disabledDirs, options.getWorkspaceRoot());
        String orphanCmd = orphanDiffCmd(scope, candidates, options.getWorkspaceRoot());
        String cleanup = pruneCmd;
        if (!rmCmd.isEmpty()) {
            cleanup += "; " + rmCmd;
        }
        if (!orphanCmd.isEmpty()) {
            cleanup += "; " + orphanCmd;
        }
        String command = linkCmd.length() == 0 ? "{ " + cleanup + "; }" : linkCmd + " && { " + cleanup + "; }";
        var execution = osbSandbox.commands().run(command);
        if (execution.getExitCode() != null && execution.getExitCode() != 0) {
            throw new SandboxException(SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                "hydrateSkillsFarm", "skills farm converge failed with exit " + execution.getExitCode(), null);
        }
        // 收敛成功才记账（失败不记账：下次触发重收敛），带 farm 模式分量；指纹未知（端点瞬时
        // 不可达）时 record 对 null 指纹 no-op——下次触发重新判定稳态
        filesystemSpec.recordScopedSkillCacheFingerprint(id, scope,
            OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM, fingerprint);
        filesystemSpec.markHydratedScopedScope(id, scope);
    }

    /**
     * 孤儿软链差集清理（§6.2-3：manifest 已禁用/移除的既有软链删除）：容器内当前 scope 的
     * 软链不在本次候选集内即为孤儿（包技能移除/改名后的残留），悬空滞留会让 load 撞已删内容
     * ——随收敛 exec 在 shell 内枚举+差集删除（不增加 exec 数，D-10 稳态 0 exec 不受影响）。
     * 禁用目录由 {@link #disabledRmCmd} 负责（B-2 同语义，候选集外差集与其自然重合、双删无害）。
     * 真实场景实测（kind 三路径验收）揪出 PR-4a 起的缺口：包技能移除后守护 GC 正确、容器孤儿链
     * 永久滞留。候选名已过 {@link #isShellSafeRelPath}（无引号/空白/glob 元字符风险由 quoted
     * case 模式兜底），`${__rel}` 展开在 case 模式的带引号段内按字面匹配。
     */
    private String orphanDiffCmd(String scope, List<String> candidates, String workspaceRoot) {
        String scopeAll = workspaceRoot + "/" + SkillCacheScope.CACHE_DIR + "/" + scope;
        var pipeList = new StringBuilder("|");
        for (var c : candidates) {
            pipeList.append(c).append('|');
        }
        return "find '" + scopeAll + "' -type l 2>/dev/null | while IFS= read -r __f; do"
            + " __rel=${__f#'" + scopeAll + "/'};"
            + " case '" + pipeList + "' in *\"|$__rel|\"*) ;; *) rm -rf \"$__f\" ;; esac; done; true";
    }

    /**
     * 农场稳态指纹（D-10，PR-4b 拆分后口径）：{@code 端点版本 + "|" + L4 revision}。包内容
     * 身份（含全局禁用 overlay）唯一 SSOT 在端点侧（{@link SkillBundleService} computeVersion
     * = {@code PackageSourceFingerprint.digest(skillsDir, disabled overlay)}），本侧不再直接
     * stat 包目录——「包指纹逻辑整体移交端点侧」；L4 revision 覆盖用户维度（用户级禁用/
     * 同名变化经 revision bump 进指纹）。端点版本经 D-4 singleflight+TTL 缓存且 decideMode
     * 已在取，稳态短路零额外开销。异常上抛由 {@link #hydrate} 归为「指纹未知」→ 收敛不记账。
     * 升级窗口：存量记账值（旧「包 stat|revision」口径）与新口径天然不等 → 首轮触发一次
     * 重收敛（与 D-11 无模式存量同姿态，无需迁移）。
     */
    private String farmFingerprint(String uid) throws Exception {
        var svc = options != null ? options.getSkillBundleService() : null;
        if (svc == null) {
            throw new IllegalStateException("skill bundle service unwired");
        }
        var version = svc.currentVersion().version();
        if (version == null || version.startsWith(SkillBundleService.ERROR_VERSION_PREFIX)) {
            throw new IllegalStateException("skill bundle endpoint unavailable (version=" + version + ")");
        }
        String revision = "";
        if (workspaceReader != null && uid != null && !uid.isBlank()) {
            var rev = workspaceReader.readUserSkillRevision(uid);
            revision = rev == null ? "" : rev;
        }
        return version + "|" + revision;
    }

    /**
     * 禁用技能目录解析（oa-1110-repro-fixes-design §2.1a）：包级禁用名 + 用户级禁用，
     * 返回相对 scopeDir 的路径集。checker 缺失/目录缺失返回空集；用户级判定异常按
     * 未禁用处理（fail-soft，不中断投放）。
     */
    private static Set<Path> disabledSkillDirs(SkillDisabledChecker checker, String uid, Path scopeDir) {
        if (checker == null || !Files.isDirectory(scopeDir)) {
            return Set.of();
        }
        var disabled = new LinkedHashSet<Path>();
        Set<String> pkgDisabled;
        try {
            pkgDisabled = checker.packageDisabled();
        } catch (Exception e) {
            pkgDisabled = Set.of();
        }
        try (var nsStream = Files.list(scopeDir)) {
            for (var ns : nsStream.toList()) {
                if (!Files.isDirectory(ns)) {
                    continue;
                }
                try (var skillStream = Files.list(ns)) {
                    for (var skillDir : skillStream.toList()) {
                        if (!Files.isDirectory(skillDir)) {
                            continue;
                        }
                        String name = skillDir.getFileName().toString();
                        if (pkgDisabled.contains(name)) {
                            disabled.add(scopeDir.relativize(skillDir));
                            continue;
                        }
                        try {
                            if (checker.isUserDisabled(uid, name)) {
                                disabled.add(scopeDir.relativize(skillDir));
                            }
                        } catch (Exception e) {
                            log.warn("[sandbox-open] disabled-check failed for {}: {}", name, e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[sandbox-open] resolve disabled skill dirs failed: {}", e.getMessage());
            return Set.of();
        }
        return disabled;
    }

    /**
     * 容器内禁用技能目录的清理命令段（当前 scope 内；空集返回空串）。路径单引号包裹，
     * 含单引号/控制字符/{@code ..} 的相对路径直接跳过（防御性：技能名来自 SDK stage 落盘）。
     */
    private static String disabledRmCmd(String scope, Set<Path> disabledDirs, String workspaceRoot) {
        if (disabledDirs.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        String scopeRoot = workspaceRoot + "/" + SkillCacheScope.CACHE_DIR + "/" + scope;
        for (var rel : disabledDirs) {
            String joined = rel.toString().replace('\\', '/');
            if (!isShellSafeRelPath(joined)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" && ");
            }
            sb.append("rm -rf '").append(scopeRoot).append("/").append(joined).append("'");
        }
        return sb.toString();
    }

    /** 相对路径是否可安全内插 shell 单引号段：拒绝空白/控制字符/单引号/路径穿越 */
    private static boolean isShellSafeRelPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/")) {
            return false;
        }
        if (path.contains("'") || path.contains("..")) {
            return false;
        }
        return path.chars().allMatch(c -> c > 32);
    }
}
