package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.RuntimeContext;

/**
 * {@link SkillCacheScope} 单测：复刻 agentscope 2.0.3 MarketplaceStager.scopeSegment 的
 * 字节级规则（供 scoped .skills-cache 投影定位当前会话目录）。
 */
class SkillCacheScopeTest {

    @Test
    void sanitizeKeepsSafeIdentity() {
        assertEquals("lbxxgn_mv7gcssd", SkillCacheScope.sanitize("lbxxgn_mv7gcssd"));
        assertEquals("e2e-abc_123.xy", SkillCacheScope.sanitize("e2e-abc_123.xy"));
    }

    @Test
    void blankMapsToShared() {
        assertEquals(SkillCacheScope.SHARED_SCOPE, SkillCacheScope.sanitize(null));
        assertEquals(SkillCacheScope.SHARED_SCOPE, SkillCacheScope.sanitize(""));
        assertEquals(SkillCacheScope.SHARED_SCOPE, SkillCacheScope.sanitize("   "));
    }

    @Test
    void unsafeCharsGoToHashedForm() {
        // 清洗后与原文不一致 → 截断 + '-' + sha256 前 12 位
        var s = SkillCacheScope.sanitize("a/b");
        assertTrue(s.startsWith("a_b-"), s);
        assertNotEquals("a_b", s);
    }

    @Test
    void literalSharedIsNotReusedAsDir() {
        // 原文恰为 "_shared"：按 SDK 规则走 hashed 形式，避免与真正 shared 桶混淆
        var s = SkillCacheScope.sanitize(SkillCacheScope.SHARED_SCOPE);
        assertNotEquals(SkillCacheScope.SHARED_SCOPE, s);
        assertTrue(s.startsWith(SkillCacheScope.SHARED_SCOPE + "-"), s);
    }

    @Test
    void trailingDotGoesToHashedForm() {
        var s = SkillCacheScope.sanitize("abc.");
        assertTrue(s.startsWith("abc."), s);
        assertTrue(s.length() > "abc.".length(), s);
    }

    @Test
    void longValueTruncatedToMaxWithHash() {
        var s = SkillCacheScope.sanitize("x".repeat(80));
        assertEquals(64, s.length(), s);
        assertTrue(s.contains("-"), s);
    }

    @Test
    void scopeSegmentPrefersUserIdThenSessionId() {
        assertEquals("u1", SkillCacheScope.scopeSegment(
            RuntimeContext.builder().userId("u1").sessionId("s1").build()));
        assertEquals("s1", SkillCacheScope.scopeSegment(
            RuntimeContext.builder().sessionId("s1").build()));
        assertEquals(SkillCacheScope.SHARED_SCOPE, SkillCacheScope.scopeSegment(
            RuntimeContext.builder().build()));
    }

    @Test
    void scopeSegmentNullWhenCtxNull() {
        // ctx 缺失 = 身份未知 → null（调用方跳过本轮、下次重试），区别于空身份的 _shared
        assertNull(SkillCacheScope.scopeSegment(null));
    }
}
