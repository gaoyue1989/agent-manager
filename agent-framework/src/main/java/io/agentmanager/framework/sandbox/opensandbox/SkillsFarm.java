package io.agentmanager.framework.sandbox.opensandbox;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * 单条 exec 幂等建链、稳态短路记账）。唯一入口 {@link #tryHydrate}：农场未启用或判定
 * 降级返回 false（调用方回落 tar 物化现行为），命中则完成收敛并返回 true。
 */
final class SkillsFarm {

    private static final Logger log = LoggerFactory.getLogger(SkillsFarm.class);

    /** 农场判定打点（D-5）：会话级二值判定结果 Counter，farm 命中率是池漏配/灰度进度的唯一非日志信号 */
    private static final String DECISION_METRIC = "skills.farm.decision";
    /** store 版本目录命名前缀（skills-syncd §4.4 {@code v-<version>}）；版本比对仅做字符串相等（§7.2 SSOT 边界） */
    private static final String STORE_VERSION_DIR_PREFIX = "v-";

    private final OpenSandboxClientOptions options;
    private final Sandbox osbSandbox;
    /** 会话用户 L4 仓储（nullable）；判定源枚举与内容指纹的 revision 分量来源 */
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
     * 判定降级返回 false（调用方走 tar 物化现行为）。收敛 exec 失败原样上抛（上层 CAS
     * 复位，下次触发重试），判定不变。
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

    /** 农场开关（§6.4）：SANDBOX_SKILLS_SHARED_STORE 空 = 农场整体关闭走现行为 */
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
     * 端点不可达 / 版本不一致）→ 本会话整体走原 tar 物化。判定按会话键记忆于 gate：
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
                + "store treated as unusable, session degraded to tar materialization",
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
     * ScopedSkillCacheSupport#disabledSkillDirs} 同源，禁止重写过滤规则）与 L4 同名（规则 1
     * 真实文件保护）不建链；含单引号/空白/{@code ..} 的相对路径跳过（防御同
     * {@link ScopedSkillCacheSupport#disabledRmCmd}）。
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
                        if (!ScopedSkillCacheSupport.isShellSafeRelPath(rel)
                                || disabledDirs.contains(scopeDir.relativize(skillDir))
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
     * （缺失 / 悬空 / 降级会话 tar 物化残留真实文件）统一 rm 后重建（D-2）。禁用判定复用
     * {@link ScopedSkillCacheSupport#disabledSkillDirs}：禁用名不建链且随 exec 清理既有软链
     * （B-2，会话中途禁用生效——用户级禁用经 L4 revision bump 进指纹）；prune 与禁用清理复用
     * tar 路径的容器内清理语义。exec 失败上抛（CAS 复位，下次触发重试），判定不变。
     *
     * <p><b>稳态短路（D-10，设计修订否决原 H2「每次触发收敛」口径）</b>：与 tar 路径 P0-1 同源——
     * 宿主指纹（{@link ScopedSkillCacheSupport#contentFingerprint}，包内源 stat + L4 revision）+
     * sandboxId 代际记账（复用 scoped 指纹表，Pod 级 miss 经跨副本共享层兜底），记账带 farm 模式
     * 分量（D-11：本代降级 tar 物化的记账不满足 farm 稳态——降级↔farm 翻转后代际必须重收敛，把
     * 降级残留真实文件副本替换为软链，D-2/§6.2-4）。指纹未变且本沙箱代已收敛过当前 scope →
     * 容器链接即最新（B-4 间接寻址，版本切换经 CURRENT 即见）→ 跳过收敛 exec。收敛触发面 = 新
     * 沙箱代（记账随新 sandboxId 清零）/ 模式翻转 / 指纹变化 / exec 失败不记账。会话中途容器重启
     * （同 sandboxId resume）软链不重建为已接受盲点——与投影指纹缓存 §3.3 系统性盲点同姿态
     * （同代内 tar 物化/记忆注入同样不重投）；per-call 容器侧标记兜底被否决（观测容器态本身即
     * 1 次 exec RTT）。
     */
    private void hydrate(String scope, Path scopeDir, SkillSyncGate gate) throws Exception {
        String fingerprint = ScopedSkillCacheSupport.contentFingerprint(
            filesystemSpec, workspaceReader, userKey.get(), scopeDir);
        String id = sandboxId.get();
        // 稳态判定（D-10 + 模式分量）：指纹未变 且 本沙箱代以 farm 模式收敛过当前 scope → 容器链接即最新
        String podFp = filesystemSpec.scopedSkillCacheFingerprint(id, scope,
            OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM);
        boolean steady = podFp != null && podFp.equals(fingerprint)
            && scope.equals(filesystemSpec.hydratedScopedScope(id));
        if (steady) {
            log.debug("[sandbox-open] skills farm fingerprint unchanged ({}), skipping converge", scope);
            return;
        }
        var disabledDirs = ScopedSkillCacheSupport.disabledSkillDirs(
            options.getSkillDisabledChecker(), userKey.get(), scopeDir);
        Set<String> l4Names = gate != null && gate.skillFarmState() != null
            ? gate.skillFarmState().l4Names()
            : Set.of();
        var linkCmd = new StringBuilder();
        for (var rel : linkCandidates(scopeDir, disabledDirs, l4Names)) {
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
        String rmCmd = ScopedSkillCacheSupport.disabledRmCmd(scope, disabledDirs, options.getWorkspaceRoot());
        String cleanup = rmCmd.isEmpty() ? pruneCmd : pruneCmd + "; " + rmCmd;
        String command = linkCmd.length() == 0 ? "{ " + cleanup + "; }" : linkCmd + " && { " + cleanup + "; }";
        var execution = osbSandbox.commands().run(command);
        if (execution.getExitCode() != null && execution.getExitCode() != 0) {
            throw new SandboxException(SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                "hydrateSkillsFarm", "skills farm converge failed with exit " + execution.getExitCode(), null);
        }
        // 收敛成功才记账（失败不记账：下次触发重收敛），带 farm 模式分量与 tar 慢路径共用 scoped 指纹表
        filesystemSpec.recordScopedSkillCacheFingerprint(id, scope,
            OpenSandboxFilesystemSpec.HYDRATE_MODE_FARM, fingerprint);
        filesystemSpec.markHydratedScopedScope(id, scope);
    }
}
