package io.agentscope.harness.agent.skill.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import io.agentmanager.framework.sandbox.opensandbox.SkillCacheScope;

/**
 * <b>classpath 遮蔽类</b>（受控二进制分叉，见 docs/design/sdk-stager-shadow-design-261114.md）：
 * 与 SDK jar 同全限定名，Spring Boot 类加载顺序（BOOT-INF/classes 优先于 lib）保证运行期加载
 * 本版本（含嵌套类型——中间件按二进制名解析，一并落入本类）。变更语义：{@link #stage}
 * <b>不写宿主盘</b>、纯数据合成 StageResult——
 * <ul>
 *   <li>分类逐字复刻原实现：{@code repo instanceof WorkspaceSkillRepository} → {@code
 *       WorkspaceNative}（L4 原生路径不经过 .skills-cache）；其余（市场层仓库）→ {@code
 *       Cached(scopeSegment, sourceNamespace, skillName)}，路径与 agent-framework 软链农场
 *       建链同构（farm manifest 源 = 端点 version 响应 skills 名单，见 SkillBundleService）</li>
 *   <li>消除宿主 per-user 包技能写盘（66MB/用户×副本，SSOT §1.2-1 乘法项）与每轮文件级比对</li>
 *   <li>孤儿 GC/invalidate 随写盘一并退役（no-op）</li>
 * </ul>
 *
 * <p><b>维护协议</b>：分叉面冻结为仅本类（含嵌套类型）；SDK 版本升级必须重 derive（常量/签名
 * 断言见 {@code MarketplaceStagerShadowTest}，遮蔽生效由 {@link #SHADOW_MARKER} 启动自检兜底）；
 * 上游若提供 stager 注入口/官方 seam，删除本类切官方 API（本类合成行为即官方 seam 的参考实现）。
 */
public class MarketplaceStager {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceStager.class);

    /** 遮蔽自检标记：SDK 原类无此字段——运行期读取失败即遮蔽失效（jar 原版被加载），启动 fail-fast */
    public static final String SHADOW_MARKER = "agent-framework";

    public static final String CACHE_DIR = ".skills-cache";
    public static final String GLOBAL_NAMESPACE = "_global";
    public static final Duration DEFAULT_ORPHAN_GRACE = Duration.ofMinutes(30);
    public static final String SHARED_SCOPE = "_shared";

    /** 原类私有常量（scopeSegment 截断上界），本类 scope 段计算同参 */
    private static final int MAX_SCOPE_SEGMENT = 64;

    /** 原构造签名保留（Builder 硬编码调用）；遮蔽后仅作记账上下文，不参与任何盘面操作 */
    private final Path workspaceRoot;
    private final Duration orphanGrace;

    public MarketplaceStager(Path workspaceRoot) {
        this(workspaceRoot, DEFAULT_ORPHAN_GRACE);
    }

    public MarketplaceStager(Path workspaceRoot, Duration orphanGrace) {
        this.workspaceRoot = workspaceRoot;
        this.orphanGrace = orphanGrace;
    }

    /** 原类公开方法：遮蔽后 no-op（无宿主缓存可失效） */
    public void invalidateAll() {
    }

    public Map<String, StageResult> stage(List<RepoBound> bounds, Map<AgentSkillRepository, String> sourceNamespaces) {
        return stage(bounds, sourceNamespaces, null);
    }

    /**
     * 合成 stage（原分类逐字复刻、零写盘）：workspace 仓库 → WorkspaceNative（L4 原生）；
     * 其余 → Cached(scopeSegment, sourceNamespace, skillName)。{@code scope} 为空按原语义落
     * {@link #SHARED_SCOPE}。路径渲染由 ShellPathPolicy 基于 record 字段推导（探针 §2.3-②：
     * 与 farm 建链路径同构）。
     */
    public Map<String, StageResult> stage(List<RepoBound> bounds, Map<AgentSkillRepository, String> sourceNamespaces,
                                          String scope) {
        var out = new HashMap<String, StageResult>(bounds == null ? 0 : bounds.size());
        if (bounds == null) {
            return out;
        }
        String scopeSegment = scopeSegment(scope);
        for (var bound : bounds) {
            var repo = bound.repo();
            var name = bound.skill().getName();
            if (repo instanceof WorkspaceSkillRepository) {
                out.put(name, new StageResult.WorkspaceNative());
                continue;
            }
            var ns = sourceNamespaces != null
                ? sourceNamespaces.getOrDefault(repo, GLOBAL_NAMESPACE) : GLOBAL_NAMESPACE;
            out.put(name, new StageResult.Cached(scopeSegment, ns, name));
        }
        return out;
    }

    /** 原逻辑逐字复刻（IdentityHashMap 身份键；source 重复按出现序 {@code <source>-<n>} 消歧） */
    public static Map<AgentSkillRepository, String> resolveSourceNamespaces(List<AgentSkillRepository> repos) {
        var out = new IdentityHashMap<AgentSkillRepository, String>();
        if (repos == null || repos.isEmpty()) {
            return out;
        }
        var counts = new HashMap<String, Integer>();
        for (var repo : repos) {
            counts.merge(effectiveSource(repo), 1, Integer::sum);
        }
        var seen = new HashMap<String, Integer>();
        for (int i = 0; i < repos.size(); i++) {
            var repo = repos.get(i);
            var source = effectiveSource(repo);
            int count = counts.getOrDefault(source, 1);
            if (count == 1) {
                out.put(repo, source);
                continue;
            }
            int occurrence = seen.merge(source, 1, Integer::sum);
            String ns = source + "-" + occurrence;
            out.put(repo, ns);
            if (count > 1) {
                log.warn("Skill repository source '{}' is used by {} repositories; disambiguating as '{}' "
                    + "for repo at index {} ({})", source, count, ns, i, repo.getClass().getSimpleName());
            }
        }
        return out;
    }

    private static String effectiveSource(AgentSkillRepository repo) {
        var source = repo.getSource();
        if (source == null || source.isBlank()) {
            return GLOBAL_NAMESPACE;
        }
        return source;
    }

    private static String scopeSegment(String scope) {
        return SkillCacheScope.sanitize(scope);
    }

    /** StageResult 契约（嵌套类型随本类遮蔽；NONE 供中间件空态判定） */
    public interface StageResult {
        StageResult NONE = new None();

        record None() implements StageResult {
        }

        record WorkspaceNative() implements StageResult {
        }

        /** 组件名即中间件渲染契约（ShellPathPolicy.resolve → joinCache(scope, ns, name)） */
        record Cached(String scopeSegment, String sourceNamespace, String skillName) implements StageResult {
        }
    }

    /** 仓库绑定（组件名 skill/repo 即中间件消费契约） */
    public record RepoBound(AgentSkill skill, AgentSkillRepository repo) {
    }
}
