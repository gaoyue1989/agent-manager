package io.agentmanager.framework.sandbox.opensandbox;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话水合门控（设计 ③②′，docs/design/sandbox-skill-sync-optimization-design.md §4.2/§5）：
 * 一个 userKey|sandboxId 组合（= 一个沙箱代内的一路用户会话）已知的技能同步状态。
 *
 * <ul>
 *   <li><b>revision（③ 物化门控）</b>：最近一次物化时读到的 KV {@code /.revision} 值。
 *       再次物化前重读比较：相等 → KV 技能集未变且本沙箱代已投影过 → 跳过全量物化
 *       （实测 36.5ms → 0.30ms）；{@code lastMaterialized=false} 表示本沙箱代还没物化过
 *       （首次必做，保证基线落进容器）。</li>
 *   <li><b>manifest（②′ 回写快照差分）</b>：容器 skills/ 文件相对路径 → 字节大小，
 *       来源为物化实际写入的 WriteEntry 与回写遍历结果（增量并集，只会越齐越准）。
 *       回写时只对与快照不一致的文件读内容，省掉全量 readFile（实测 449ms → ~64ms）。</li>
 * </ul>
 *
 * <p>缓存缺失/字段为 null 的语义一律是"未知 → 走现行为"（全量物化 / 全量读），
 * 正确性不依赖缓存；pod 本地即可（KV 与容器是全局真相）。
 */
public final class SkillSyncGate {

    /** 最近一次物化时的 KV revision 值；null = 尚未记录 */
    private volatile String revision;
    /** 本沙箱代是否至少完整物化过一次（revision 门控的前提：没有基线就没有跳过的资格） */
    private volatile boolean lastMaterialized;
    /**
     * 本沙箱代是否已注入过 KV 运行时文件（MEMORY.md/memory/）。注入从「每 call」收敛到
     * 「每沙箱代」：同代内 turn 结束的回写已把容器记忆同步进 KV，下一 call 的重注入
     * 是等值幂等（实测 66MB L4 命名空间下 memory/ 的 KV 搜索每 call 白付 ≈1.5s）。
     * 代边界（容器换代）gate 随 sandboxId 换新，首 turn 必注入。
     */
    private volatile boolean lastRuntimeInjected;
    /** 容器 skills/{name}/{relPath} → 字节大小（回写差分快照；只增不删，消失条目由回写遍历收敛） */
    private final ConcurrentHashMap<String, Long> manifest = new ConcurrentHashMap<>();
    /**
     * 软链农场会话级二值判定（skills-syncd B-1，§6.2/§6.4）：null = 未判定；farm=false 的
     * 降级会话不中途翻转，恢复以会话为粒度（新 sandboxId 换新 gate 重新判定）。
     * l4Names 为判定时刻的用户 L4 技能名集（同名 L4 优先裁决源），农场会话全程复用。
     */
    private volatile SkillFarmState skillFarmState;

    public SkillFarmState skillFarmState() {
        return skillFarmState;
    }

    public void setSkillFarmState(SkillFarmState state) {
        this.skillFarmState = state;
    }

    /** 农场判定记忆（判定结果 + 同名 L4 优先判定源，均为会话级快照） */
    public record SkillFarmState(boolean farm, java.util.Set<String> l4Names) {
    }

    public String revision() {
        return revision;
    }

    public void setRevision(String revision) {
        this.revision = revision;
    }

    public boolean lastMaterialized() {
        return lastMaterialized;
    }

    public void setLastMaterialized(boolean lastMaterialized) {
        this.lastMaterialized = lastMaterialized;
    }

    public boolean lastRuntimeInjected() {
        return lastRuntimeInjected;
    }

    public void setLastRuntimeInjected(boolean lastRuntimeInjected) {
        this.lastRuntimeInjected = lastRuntimeInjected;
    }

    public Map<String, Long> manifest() {
        return manifest;
    }
}
