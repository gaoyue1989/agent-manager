package io.agentmanager.framework.sandbox.opensandbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import io.agentscope.core.agent.RuntimeContext;

/**
 * SDK 技能缓存（{@code .skills-cache}）的 scope 目录名计算（与 agentscope HarnessSkillMiddleware
 * / MarketplaceStager 语义对齐）。
 *
 * <p>背景：SDK {@code HarnessSkillMiddleware} 每轮推理按隔离 scope 把市场技能物化到宿主
 * {@code <wsRoot>/.skills-cache/<scopeSegment>/<source-ns>/<skill>/}，其中 scopeSegment 由
 * {@code scopeKeyFor}（IsolationScope.USER 档 → {@code ctx.userId}，为空回落 {@code sessionId}）
 * 再经 {@code MarketplaceStager.scopeSegment} 清洗得到。本框架沙箱投影需据此只投放<b>当前
 * 会话</b>的 scope 目录，避免把宿主上所有历史用户的缓存都灌进容器。
 *
 * <p>{@link #scopeSegment(String)} 复刻 MarketplaceStager.scopeSegment 的字节级规则：
 * 空/blank → {@code _shared}；清洗后与原文一致且非 {@code _shared}、长度 ≤64、不以 {@code .}
 * 结尾 → 原样；否则截断 + {@code -} + sha256(原文) 前 12 位。
 */
public final class SkillCacheScope {

    /** SDK 技能缓存根目录名（MarketplaceStager.CACHE_DIR）。 */
    public static final String CACHE_DIR = ".skills-cache";

    /** 无 scope 时的共享目录名（MarketplaceStager.SHARED_SCOPE / GLOBAL_NAMESPACE）。 */
    public static final String SHARED_SCOPE = "_shared";

    private SkillCacheScope() {}

    /**
     * 计算当前调用生效的 scopeSegment（对齐 HarnessSkillMiddleware.scopeKeyFor + MarketplaceStager.scopeSegment）。
     *
     * @param ctx 本轮 RuntimeContext（可能为 null）
     * @return scope 目录名；{@code ctx} 为 null（身份未知）返回 null，调用方应跳过本轮、下次重试
     */
    public static String scopeSegment(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        return sanitize(rawScopeKey(ctx));
    }

    /** scopeKeyFor（IsolationScope.USER）：userId 优先，为空回落 sessionId；均为空返回 null。 */
    private static String rawScopeKey(RuntimeContext ctx) {
        var uid = trimToNull(ctx.getUserId());
        if (uid != null) {
            return uid;
        }
        return trimToNull(ctx.getSessionId());
    }

    /** 复刻 MarketplaceStager.scopeSegment：把原始 scope 键转成目录安全段。 */
    public static String sanitize(String scope) {
        if (scope == null || scope.isBlank()) {
            return SHARED_SCOPE;
        }
        String cleaned = scope.replaceAll("[^A-Za-z0-9._-]", "_");
        boolean simple = cleaned.equals(scope)
                && !cleaned.equals(SHARED_SCOPE)
                && cleaned.length() <= 64
                && !cleaned.endsWith(".");
        if (simple) {
            return cleaned;
        }
        String hash = sha256Hex(scope).substring(0, 12);
        int max = Math.min(cleaned.length(), 64 - hash.length() - 1);
        return cleaned.substring(0, Math.max(max, 0)) + "-" + hash;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
