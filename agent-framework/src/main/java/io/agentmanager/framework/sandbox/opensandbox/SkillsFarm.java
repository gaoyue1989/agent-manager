package io.agentmanager.framework.sandbox.opensandbox;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.opensandbox.sandbox.Sandbox;

import io.agentmanager.framework.service.SkillZipSupport;
import io.agentmanager.framework.service.WorkspaceReader;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;

/**
 * 软链农场子系统（skills-syncd §6.2：会话级二值判定 + 软链农场收敛），OpenSandbox 的
 * 内聚协作者：判定（store/端点探测、版本硬校验、D-5 打点、会话级记忆）+ 收敛（候选链、
 * 单条 exec 幂等建链、稳态短路记账、孤儿差集清理）+ 投放过滤原语（禁用解析/清理命令/
 * shell 安全路径——PR-4b 起 tar 投放路径已退役，本类是这些原语的唯一消费方）。
 * 候选 manifest = 端点 version 响应 skills 名单（SDK stager 已由 classpath 遮蔽类停写宿主，
 * 见 docs/design/sdk-stager-shadow-design-261114.md）。唯一入口 {@link #tryHydrate}：
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
    private final SkillBundlePusher pusher;

    SkillsFarm(OpenSandboxClientOptions options,
               Sandbox osbSandbox,
               WorkspaceReader workspaceReader,
               OpenSandboxFilesystemSpec filesystemSpec,
               Supplier<String> userKey,
               Supplier<String> sandboxId) {
        this(options, osbSandbox, workspaceReader, filesystemSpec, userKey, sandboxId, new SkillBundlePusher());
    }

    SkillsFarm(OpenSandboxClientOptions options,
               Sandbox osbSandbox,
               WorkspaceReader workspaceReader,
               OpenSandboxFilesystemSpec filesystemSpec,
               Supplier<String> userKey,
               Supplier<String> sandboxId,
               SkillBundlePusher pusher) {
        this.options = options;
        this.osbSandbox = osbSandbox;
        this.workspaceReader = workspaceReader;
        this.filesystemSpec = filesystemSpec;
        this.userKey = userKey;
        this.sandboxId = sandboxId;
        this.pusher = pusher;
    }

    boolean tryHydrate(String scope) throws Exception {
        if (!enabled()) {
            return false;
        }
        var uid = sessionUserKey();
        var gate = filesystemSpec != null
            ? filesystemSpec.skillSyncGate(uid, sandboxId.get()) : null;
        if (gate == null) {
            return false;
        }
        var acquired = acquireState(gate, uid);
        var state = acquired == null ? null : acquired.state();
        if (state == null || !state.active()) {
            return false;
        }
        String activeStore = state.storeDir() != null && !state.storeDir().isBlank()
            ? state.storeDir()
            : options.getSkillsSharedStore().trim();
        String hydrateMode = state.mode() == SkillSyncGate.FarmMode.PUSH
            ? OpenSandboxFilesystemSpec.HYDRATE_MODE_PUSH
            : OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM;
        hydrate(scope, gate, uid, state.mode(), activeStore, hydrateMode, acquired.justSyncedVersion());
        return true;
    }

    /** 农场开关（§6.4，PR-4b 语义；issue #392 起空或 off = 关闸，off 为平台可送达形态）：包技能不投放（总闸，无 tar 回落） */
    private boolean enabled() {
        return !OpenSandboxClientOptions.skillsSharedStoreOff(
            options != null ? options.getSkillsSharedStore() : null);
    }

    /** 农场会话键（与 session_user 规范键同源）：middleware/ctx 已解析的 userKey，缺失时 spec ThreadLocal 兜底 */
    private String sessionUserKey() {
        var bound = userKey.get();
        if (bound != null && !bound.isBlank()) {
            return bound;
        }
        return filesystemSpec != null ? filesystemSpec.peekPendingUserKey() : null;
    }

    private record AcquireResult(SkillSyncGate.SkillFarmState state, String justSyncedVersion) {}

    private AcquireResult acquireState(SkillSyncGate gate, String uid) {
        var remembered = gate.skillFarmState();
        if (remembered != null) {
            return new AcquireResult(remembered, null);
        }
        var decision = decideMode(uid);
        var state = new SkillSyncGate.SkillFarmState(decision.mode(), decision.storeDir(), decision.l4Names());
        gate.setSkillFarmState(state);
        recordDecision(decision.result());
        return new AcquireResult(state, decision.syncedVersion());
    }

    private record FarmDecision(SkillSyncGate.FarmMode mode, String storeDir, String result,
                                Set<String> l4Names, String syncedVersion) {
        FarmDecision(SkillSyncGate.FarmMode mode, String storeDir, String result, Set<String> l4Names) {
            this(mode, storeDir, result, l4Names, null);
        }
    }

    private FarmDecision decideMode(String uid) {
        SkillBundleService svc = options.getSkillBundleService();
        SkillBundleService.SkillVersion snapshot;
        try {
            snapshot = svc == null ? null : svc.currentVersion();
            if (snapshot == null
                    || snapshot.version() == null
                    || snapshot.version().startsWith(SkillBundleService.ERROR_VERSION_PREFIX)) {
                throw new IllegalStateException("skill bundle endpoint unavailable (version="
                    + (snapshot == null ? null : snapshot.version()) + ")");
            }
        } catch (Exception e) {
            log.warn("[sandbox-open] skills farm endpoint version unavailable: {}", e.getMessage());
            return new FarmDecision(SkillSyncGate.FarmMode.NONE, null, "degraded_endpoint_down", Set.of());
        }
        String endpointVersion = snapshot.version();
        String primaryStore = options.getSkillsSharedStore().trim();
        var store = probeStore(primaryStore);
        if ((STORE_VERSION_DIR_PREFIX + endpointVersion).equals(store.version())) {
            return new FarmDecision(SkillSyncGate.FarmMode.FARM, primaryStore, "farm", captureL4SkillNames(uid));
        }
        String originReason;
        if (store.version() != null) {
            log.error("[sandbox-open] skills farm version hard-check failed (store={}, endpoint={}): "
                + "store treated as unusable",
                store.version(), endpointVersion);
            originReason = "degraded_version_mismatch";
        } else if (store.dirPresent()) {
            originReason = "degraded_endpoint_down";
        } else {
            originReason = "degraded_store_missing";
        }
        if (!canFallbackPush(originReason)) {
            return new FarmDecision(SkillSyncGate.FarmMode.NONE, null, originReason, Set.of());
        }
        String fallbackStore = options.getSkillsFallbackStoreDir() == null
            ? "" : options.getSkillsFallbackStoreDir().trim();
        if (snapshot.skills() != null && snapshot.skills().isEmpty()) {
            if (!OpenSandboxClientOptions.isValidFallbackStoreDir(fallbackStore)) {
                return new FarmDecision(SkillSyncGate.FarmMode.NONE, null, "degraded_fallback_failed", Set.of());
            }
            return new FarmDecision(SkillSyncGate.FarmMode.PUSH, fallbackStore, "fallback_push_hit",
                captureL4SkillNames(uid));
        }
        var outcome = pusher.ensureSynced(
            osbSandbox,
            svc,
            endpointVersion,
            fallbackStore,
            options.getSkillsFallbackMaxBytes(),
            options.getSkillsFallbackSemaphore(),
            options.getSkillsFallbackAcquireTimeoutMs()
        );
        return switch (outcome.status()) {
            case HIT -> {
                log.info("[sandbox-open] skills fallback push store hit (origin={}, version={}, store={}, sandbox={})",
                    originReason, endpointVersion, fallbackStore, sandboxId.get());
                yield new FarmDecision(SkillSyncGate.FarmMode.PUSH, fallbackStore, "fallback_push_hit",
                    captureL4SkillNames(uid), endpointVersion);
            }
            case SYNCED -> {
                log.warn("[sandbox-open] skills fallback push synced (origin={}, version={}, bytes={}, costMs={}, store={}, sandbox={})",
                    originReason, endpointVersion, outcome.bytes(), outcome.costMs(), fallbackStore, sandboxId.get());
                yield new FarmDecision(SkillSyncGate.FarmMode.PUSH, fallbackStore, "fallback_push_synced",
                    captureL4SkillNames(uid), endpointVersion);
            }
            case TOO_LARGE -> {
                log.warn("[sandbox-open] skills fallback push skipped, too large (origin={}, version={}, bytes={}, detail={})",
                    originReason, endpointVersion, outcome.bytes(), outcome.detail());
                yield new FarmDecision(SkillSyncGate.FarmMode.NONE, null, "degraded_fallback_too_large", Set.of());
            }
            case THROTTLED -> {
                log.warn("[sandbox-open] skills fallback push throttled (origin={}, version={}, detail={})",
                    originReason, endpointVersion, outcome.detail());
                yield new FarmDecision(SkillSyncGate.FarmMode.NONE, null, "degraded_fallback_throttled", Set.of());
            }
            case FAILED -> {
                log.warn("[sandbox-open] skills fallback push failed (origin={}, version={}, detail={})",
                    originReason, endpointVersion, outcome.detail());
                yield new FarmDecision(SkillSyncGate.FarmMode.NONE, null, "degraded_fallback_failed", Set.of());
            }
        };
    }

    private boolean canFallbackPush(String originReason) {
        if (!OpenSandboxClientOptions.skillsFallbackPushEnabled(options.getSkillsFallbackMode())) {
            return false;
        }
        if ("degraded_version_mismatch".equals(originReason) && !options.isSkillsFallbackOnVersionMismatch()) {
            return false;
        }
        return true;
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
        if (!"farm".equals(result) && !"fallback_push_hit".equals(result) && !"fallback_push_synced".equals(result)) {
            log.warn("[sandbox-open] skills farm decision degraded (result={})", result);
        }
    }

    /** 包技能在 .skills-cache 两层布局中的固定 ns 层（FileSystemSkillRepository 注册 source，HarnessAgentFactory） */
    private static final String SKILL_NS = "oaf-package";

    private List<String> linkCandidates(List<String> endpointSkills, Set<String> disabledNames,
                                        Set<String> l4Names) {
        var candidates = new java.util.TreeSet<String>();
        for (var name : endpointSkills) {
            var rel = SKILL_NS + "/" + name;
            if (!isShellSafeRelPath(rel)) {
                log.warn("[sandbox-open] skills farm link candidate skipped, rel not shell-safe (rel={})", rel);
                continue;
            }
            if (disabledNames.contains(name) || l4Names.contains(name)) {
                continue;
            }
            candidates.add(rel);
        }
        return List.copyOf(candidates);
    }

    private void hydrate(String scope, SkillSyncGate gate, String uid,
                         SkillSyncGate.FarmMode mode, String storeDir, String hydrateMode,
                         String justSyncedVersion) throws Exception {
        SkillBundleService.SkillVersion snapshot;
        SkillBundleService svc = options != null ? options.getSkillBundleService() : null;
        try {
            if (svc == null) {
                throw new IllegalStateException("skill bundle service unwired");
            }
            snapshot = svc.currentVersion();
        } catch (Exception e) {
            log.warn("[sandbox-open] skills farm endpoint snapshot unavailable ({}), converging anyway "
                + "(orphan diff skipped)", e.getMessage());
            snapshot = null;
        }
        boolean endpointHealthy = snapshot != null
            && snapshot.version() != null
            && !snapshot.version().startsWith(SkillBundleService.ERROR_VERSION_PREFIX);
        String fingerprint;
        if (endpointHealthy) {
            String revision = "";
            if (workspaceReader != null && uid != null && !uid.isBlank()) {
                var rev = workspaceReader.readUserSkillRevision(uid);
                revision = rev == null ? "" : rev;
            }
            fingerprint = snapshot.version() + "|" + revision;
        } else {
            fingerprint = null;
        }
        String id = sandboxId.get();
        String podFp = filesystemSpec.scopedSkillCacheFingerprint(id, scope, hydrateMode);
        boolean steady = fingerprint != null && podFp != null && podFp.equals(fingerprint)
            && scope.equals(filesystemSpec.hydratedScopedScope(id));
        if (steady) {
            log.debug("[sandbox-open] skills farm fingerprint unchanged ({}, mode={}), skipping converge",
                scope, hydrateMode);
            return;
        }
        if (mode == SkillSyncGate.FarmMode.PUSH && endpointHealthy
                && snapshot.skills() != null && !snapshot.skills().isEmpty()
                && !snapshot.version().equals(justSyncedVersion)) {
            var outcome = pusher.ensureSynced(
                osbSandbox,
                svc,
                snapshot.version(),
                storeDir,
                options.getSkillsFallbackMaxBytes(),
                options.getSkillsFallbackSemaphore(),
                options.getSkillsFallbackAcquireTimeoutMs()
            );
            if (!outcome.ready()) {
                throw new SandboxException(SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "hydrateSkillsFarmPush", "skills fallback push failed: " + outcome.status()
                    + " (" + outcome.detail() + ")", null);
            }
        }
        var manifest = endpointHealthy ? snapshot.skills() : List.<String>of();
        var disabledNames = disabledSkillNames(
            options.getSkillDisabledChecker(), uid, manifest);
        Set<String> l4Names = captureL4SkillNames(uid);
        if (gate != null) {
            gate.setSkillFarmState(new SkillSyncGate.SkillFarmState(mode, storeDir, l4Names));
        }
        var linkCmd = new StringBuilder();
        var candidates = linkCandidates(manifest, disabledNames, l4Names);
        for (var rel : candidates) {
            if (linkCmd.length() > 0) {
                linkCmd.append(" && ");
            }
            String containerPath = options.getWorkspaceRoot() + "/" + SkillCacheScope.CACHE_DIR + "/" + scope + "/" + rel;
            String parentDir = containerPath.substring(0, containerPath.lastIndexOf('/'));
            String target = storeDir + "/CURRENT/" + OpenSandbox.leaf(rel);
            linkCmd.append("mkdir -p '").append(parentDir).append("'")
                .append(" && rm -rf '").append(containerPath).append("'")
                .append(" && ln -s '").append(target).append("' '").append(containerPath).append("'");
        }
        String pruneCmd = "find " + options.getWorkspaceRoot() + "/" + SkillCacheScope.CACHE_DIR
            + " -mindepth 1 -maxdepth 1 -type d ! -name '" + scope + "' -exec rm -rf {} + ; true";
        String rmCmd = disabledRmCmd(scope, disabledNames, options.getWorkspaceRoot());
        String orphanCmd = fingerprint == null
            ? ""
            : orphanDiffCmd(scope, candidates, options.getWorkspaceRoot());
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
        filesystemSpec.recordScopedSkillCacheFingerprint(id, scope, hydrateMode, fingerprint);
        if (fingerprint != null) {
            filesystemSpec.markHydratedScopedScope(id, scope);
        }
        filesystemSpec.pruneScopedSkillCacheFingerprintsExcept(id, scope);
    }

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

    private static Set<String> disabledSkillNames(SkillDisabledChecker checker, String uid,
                                                  List<String> endpointSkills) {
        if (checker == null) {
            return Set.of();
        }
        var disabled = new LinkedHashSet<String>();
        Set<String> pkgDisabled;
        try {
            pkgDisabled = checker.packageDisabled();
        } catch (Exception e) {
            pkgDisabled = Set.of();
        }
        for (String name : endpointSkills) {
            if (pkgDisabled.contains(name)) {
                disabled.add(name);
                continue;
            }
            try {
                if (checker.isUserDisabled(uid, name)) {
                    disabled.add(name);
                }
            } catch (Exception e) {
                log.warn("[sandbox-open] disabled-check failed for {}: {}", name, e.getMessage());
            }
        }
        return disabled;
    }

    private static String disabledRmCmd(String scope, Set<String> disabledNames, String workspaceRoot) {
        if (disabledNames.isEmpty()) {
            return "";
        }
        var sb = new StringBuilder();
        String scopeRoot = workspaceRoot + "/" + SkillCacheScope.CACHE_DIR + "/" + scope;
        for (var name : disabledNames) {
            String joined = SKILL_NS + "/" + name;
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

    private static boolean isShellSafeRelPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return Arrays.stream(path.split("/"))
            .allMatch(SkillZipSupport::isShellSafeSegment);
    }
}
