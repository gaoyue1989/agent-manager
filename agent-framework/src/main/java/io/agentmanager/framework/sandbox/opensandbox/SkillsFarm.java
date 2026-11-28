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

final class SkillsFarm {

    private static final Logger log = LoggerFactory.getLogger(SkillsFarm.class);
    private static final String DECISION_METRIC = "skills.farm.decision";
    private static final String STORE_VERSION_DIR_PREFIX = "v-";

    private final OpenSandboxClientOptions options;
    private final Sandbox osbSandbox;
    private final WorkspaceReader workspaceReader;
    private final OpenSandboxFilesystemSpec filesystemSpec;
    private final Supplier<String> userKey;
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

    private boolean enabled() {
        return !OpenSandboxClientOptions.skillsSharedStoreOff(
            options != null ? options.getSkillsSharedStore() : null);
    }

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
        if (decision.cacheable()) {
            gate.setSkillFarmState(state);
        }
        recordDecision(decision.result());
        return new AcquireResult(state, decision.syncedVersion());
    }

    private record FarmDecision(SkillSyncGate.FarmMode mode, String storeDir, String result,
                                Set<String> l4Names, String syncedVersion, boolean cacheable) {
        FarmDecision(SkillSyncGate.FarmMode mode, String storeDir, String result, Set<String> l4Names) {
            this(mode, storeDir, result, l4Names, null, true);
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
            return new FarmDecision(SkillSyncGate.FarmMode.NONE, null, "degraded_endpoint_down",
                Set.of(), null, false);
        }
        String endpointVersion = snapshot.version();
        String primaryStore = options.getSkillsSharedStore().trim();
        var store = probeStore(primaryStore);
        if (store.probeFailed()) {
            log.warn("[sandbox-open] skills farm store probe transport failed (store={}, sandbox={})",
                primaryStore, sandboxId.get());
            return new FarmDecision(SkillSyncGate.FarmMode.NONE, null, "degraded_probe_failed",
                Set.of(), null, false);
        }
        if ((STORE_VERSION_DIR_PREFIX + endpointVersion).equals(store.version())) {
            return new FarmDecision(SkillSyncGate.FarmMode.FARM, primaryStore, "farm", captureL4SkillNames(uid));
        }
        String originReason;
        if (store.version() != null) {
            originReason = "degraded_version_mismatch";
        } else if (store.dirPresent()) {
            originReason = "degraded_endpoint_down";
        } else {
            originReason = "degraded_store_missing";
        }
        if (!canFallbackPush(originReason)) {
            if ("degraded_version_mismatch".equals(originReason)) {
                log.error("[sandbox-open] skills farm version hard-check failed (store={}, endpoint={}): "
                    + "store treated as unusable",
                    store.version(), endpointVersion);
            }
            return new FarmDecision(SkillSyncGate.FarmMode.NONE, null, originReason, Set.of());
        }
        String fallbackStore = options.getSkillsFallbackStoreDir() == null
            ? "" : options.getSkillsFallbackStoreDir().trim();
        if (!OpenSandboxClientOptions.isValidFallbackStoreDir(fallbackStore)
                || OpenSandboxClientOptions.fallbackStoreCollidesWithPrimary(fallbackStore, primaryStore)) {
            log.warn("[sandbox-open] skills fallback push disabled due to invalid or colliding store dir (store={}, primary={})",
                fallbackStore, primaryStore);
            return new FarmDecision(SkillSyncGate.FarmMode.NONE, null, "degraded_fallback_failed",
                Set.of(), null, true);
        }
        if (snapshot.skills() != null && snapshot.skills().isEmpty()) {
            return new FarmDecision(SkillSyncGate.FarmMode.PUSH, fallbackStore, "fallback_push_hit",
                captureL4SkillNames(uid), endpointVersion, true);
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
        logPushOutcome(originReason, endpointVersion, fallbackStore, outcome);
        return switch (outcome.status()) {
            case HIT -> new FarmDecision(SkillSyncGate.FarmMode.PUSH, fallbackStore, "fallback_push_hit",
                captureL4SkillNames(uid), endpointVersion, true);
            case SYNCED -> new FarmDecision(SkillSyncGate.FarmMode.PUSH, fallbackStore, "fallback_push_synced",
                captureL4SkillNames(uid), endpointVersion, true);
            case TOO_LARGE -> new FarmDecision(SkillSyncGate.FarmMode.NONE, null, "degraded_fallback_too_large",
                Set.of(), null, true);
            case THROTTLED -> new FarmDecision(SkillSyncGate.FarmMode.NONE, null, "degraded_fallback_throttled",
                Set.of(), null, false);
            case FAILED -> new FarmDecision(SkillSyncGate.FarmMode.NONE, null, "degraded_fallback_failed",
                Set.of(), null, false);
        };
    }

    private void logPushOutcome(String origin, String version, String fallbackStore,
                                SkillBundlePusher.PushOutcome outcome) {
        switch (outcome.status()) {
            case HIT -> log.info(
                "[sandbox-open] skills fallback push store hit (origin={}, version={}, store={}, sandbox={})",
                origin, version, fallbackStore, sandboxId.get());
            case SYNCED -> log.warn(
                "[sandbox-open] skills fallback push synced (origin={}, version={}, bytes={}, costMs={}, store={}, sandbox={})",
                origin, version, outcome.bytes(), outcome.costMs(), fallbackStore, sandboxId.get());
            case TOO_LARGE -> log.warn(
                "[sandbox-open] skills fallback push skipped, too large (origin={}, version={}, bytes={}, detail={})",
                origin, version, outcome.bytes(), outcome.detail());
            case THROTTLED -> log.warn(
                "[sandbox-open] skills fallback push throttled (origin={}, version={}, detail={})",
                origin, version, outcome.detail());
            case FAILED -> log.warn(
                "[sandbox-open] skills fallback push failed (origin={}, version={}, detail={})",
                origin, version, outcome.detail());
        }
    }

    private static String decisionResultFor(SkillBundlePusher.Status status) {
        return switch (status) {
            case HIT -> "fallback_push_hit";
            case SYNCED -> "fallback_push_synced";
            case TOO_LARGE -> "degraded_fallback_too_large";
            case THROTTLED -> "degraded_fallback_throttled";
            case FAILED -> "degraded_fallback_failed";
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

    private record StoreProbe(String version, boolean dirPresent, boolean probeFailed) {}

    private StoreProbe probeStore(String store) {
        try {
            var execution = osbSandbox.commands().run("readlink '" + store + "/CURRENT' && test -d '" + store + "/CURRENT'");
            if (execution == null || execution.getExitCode() == null) {
                return new StoreProbe(null, false, true);
            }
            if (Integer.valueOf(0).equals(execution.getExitCode()) && execution.getError() == null) {
                var stdout = execution.getLogs() != null && execution.getLogs().getStdout() != null
                    ? execution.getLogs().getStdout().stream()
                        .map(msg -> msg.getText())
                        .collect(Collectors.joining("\n"))
                    : "";
                var token = stdout.trim();
                if (!token.isEmpty()) {
                    int slash = token.lastIndexOf('/');
                    return new StoreProbe(slash >= 0 ? token.substring(slash + 1) : token, true, false);
                }
            }
        } catch (Exception e) {
            log.debug("[sandbox-open] skills farm store probe failed: {}", e.getMessage());
            return new StoreProbe(null, false, true);
        }
        try {
            var probe = osbSandbox.commands().run("test -d '" + store + "'");
            if (probe == null || probe.getExitCode() == null) {
                return new StoreProbe(null, false, true);
            }
            return new StoreProbe(null, Integer.valueOf(0).equals(probe.getExitCode()), false);
        } catch (Exception e) {
            log.debug("[sandbox-open] skills farm store dir probe failed: {}", e.getMessage());
            return new StoreProbe(null, false, true);
        }
    }

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

    private void recordDecision(String result) {
        var registry = options != null ? options.getFarmMeterRegistry() : null;
        if (registry != null) {
            registry.counter(DECISION_METRIC, "result", result).increment();
        }
        if (!"farm".equals(result) && !"fallback_push_hit".equals(result) && !"fallback_push_synced".equals(result)) {
            log.warn("[sandbox-open] skills farm decision degraded (result={})", result);
        }
    }

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
        if (mode == SkillSyncGate.FarmMode.PUSH && endpointHealthy && justSyncedVersion == null) {
            String primaryStore = options.getSkillsSharedStore().trim();
            var primaryProbe = probeStore(primaryStore);
            if (!primaryProbe.probeFailed()
                    && (STORE_VERSION_DIR_PREFIX + snapshot.version()).equals(primaryProbe.version())) {
                mode = SkillSyncGate.FarmMode.FARM;
                storeDir = primaryStore;
                hydrateMode = OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM;
                log.info("[sandbox-open] skills farm recovered from PUSH to FARM (store={}, version={}, sandbox={})",
                    primaryStore, snapshot.version(), id);
                recordDecision("farm");
            }
        }
        var manifest = endpointHealthy ? snapshot.skills() : List.<String>of();
        var disabledNames = disabledSkillNames(
            options.getSkillDisabledChecker(), uid, manifest);
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
            String origin = outcome.status() == SkillBundlePusher.Status.HIT ? "reconverge" : "version_bump";
            logPushOutcome(origin, snapshot.version(), storeDir, outcome);
            if (outcome.status() != SkillBundlePusher.Status.HIT) {
                recordDecision(decisionResultFor(outcome.status()));
            }
            if (!outcome.ready()) {
                String pruneCmd = "find " + options.getWorkspaceRoot() + "/" + SkillCacheScope.CACHE_DIR
                    + " -mindepth 1 -maxdepth 1 -type d ! -name '" + scope + "' -exec rm -rf {} + ; true";
                String rmCmd = disabledRmCmd(scope, disabledNames, options.getWorkspaceRoot());
                String partialCleanup = rmCmd.isEmpty() ? pruneCmd : pruneCmd + "; " + rmCmd;
                try {
                    var cleanupExec = osbSandbox.commands().run("{ " + partialCleanup + "; }");
                    if (cleanupExec != null && cleanupExec.getError() == null
                            && Integer.valueOf(0).equals(cleanupExec.getExitCode())) {
                        filesystemSpec.pruneScopedSkillCacheFingerprintsExcept(id, scope);
                    }
                } catch (Exception ignored) {
                    log.debug("[sandbox-open] partial cleanup after push failure ignored: {}", ignored.getMessage());
                }
                return;
            }
        }
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
        String emptyStoreGcCmd = (mode == SkillSyncGate.FarmMode.PUSH && endpointHealthy
                && (snapshot.skills() == null || snapshot.skills().isEmpty()))
            ? emptyFallbackStoreGcCmd(storeDir, snapshot.version())
            : "";
        String cleanup = pruneCmd;
        if (!rmCmd.isEmpty()) {
            cleanup += "; " + rmCmd;
        }
        if (!orphanCmd.isEmpty()) {
            cleanup += "; " + orphanCmd;
        }
        if (!emptyStoreGcCmd.isEmpty()) {
            cleanup += "; " + emptyStoreGcCmd;
        }
        String command = linkCmd.length() == 0 ? "{ " + cleanup + "; }" : linkCmd + " && { " + cleanup + "; }";
        var execution = osbSandbox.commands().run(command);
        if (execution == null || execution.getError() != null
                || !Integer.valueOf(0).equals(execution.getExitCode())) {
            String reason = execution == null
                ? "null execution"
                : (execution.getError() != null
                    ? "error " + execution.getError().getValue()
                    : "exit " + execution.getExitCode());
            throw new SandboxException(SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                "hydrateSkillsFarm", "skills farm converge failed with " + reason, null);
        }
        filesystemSpec.recordScopedSkillCacheFingerprint(id, scope, hydrateMode, fingerprint);
        if (fingerprint != null) {
            filesystemSpec.markHydratedScopedScope(id, scope);
        }
        filesystemSpec.pruneScopedSkillCacheFingerprintsExcept(id, scope);
    }

    private static String emptyFallbackStoreGcCmd(String storeDir, String version) {
        if (!OpenSandboxClientOptions.isValidFallbackStoreDir(storeDir)
                || !SkillZipSupport.isShellSafeSegment(version)) {
            return "";
        }
        String verDir = STORE_VERSION_DIR_PREFIX + version;
        return "if [ -d '" + storeDir + "' ]; then"
            + " fb='" + storeDir + "'; ver='" + verDir + "'; cur=\"$fb/.CURRENT.$$\";"
            + " mkdir -p \"$fb/$ver\";"
            + " if [ -d \"$fb/CURRENT\" ] && [ ! -L \"$fb/CURRENT\" ]; then rm -rf \"$fb/CURRENT\"; fi;"
            + " ln -sfn \"$ver\" \"$cur\";"
            + " mv -Tf \"$cur\" \"$fb/CURRENT\" 2>/dev/null || { rm -f \"$cur\"; ln -sfn \"$ver\" \"$fb/CURRENT\"; };"
            + " active=$(readlink \"$fb/CURRENT\" 2>/dev/null || true); active=${active##*/};"
            + " find \"$fb\" -mindepth 1 -maxdepth 1 -type d -name 'v-*' ! -name \"$ver\" ! -name \"$active\""
            + " -exec rm -rf {} + 2>/dev/null || true; fi";
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
        if (pkgDisabled != null) {
            disabled.addAll(pkgDisabled);
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
