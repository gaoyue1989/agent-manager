package io.agentmanager.framework.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.model.OafConfig.ModelConfig;
import io.agentmanager.framework.model.OafConfig.McpServerConfig;
import io.agentmanager.framework.model.OafConfig.MemoryConfig;
import io.agentmanager.framework.model.OafConfig.RuntimeConfig;
import io.agentmanager.framework.model.OafConfig.SkillConfig;
import io.agentmanager.framework.model.OafConfig.SubAgentConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SandboxRuntimeTest {

    private static SandboxConfig config(boolean bound) {
        return new SandboxConfig(bound, "img", 60, 1024, 1, List.of("/entry.sh"),
            java.time.Duration.ofMillis(100), true, true, 900, null);
    }

    private static OafConfig oafWithPackage(Boolean packageEnabled) {
        // rawFrontmatter 携带 config.sandbox.enabled（与 OAF frontmatter 路径一致）
        var fm = new java.util.HashMap<String, Object>();
        if (packageEnabled != null) {
            fm.put("config", Map.of("sandbox", Map.of("enabled", packageEnabled)));
        }
        return new OafConfig("n", "v", "a", "1.0.0", "s", "d", "auth", "MIT", List.of(),
            "sp", List.<SkillConfig>of(), List.<McpServerConfig>of(), List.<SubAgentConfig>of(),
            List.of(), List.of(), new ModelConfig("openai", "m", null),
            new RuntimeConfig(0.7, 4096, false, "default"), null, fm);
    }

    @Test
    void envExplicitShouldWinOverPackageDeclaration() {
        var env = new MockEnvironment().withProperty("SANDBOX_ENABLED", "false");
        // 包声明 true，但 env 显式 false → env 赢（部署级显式意愿优先）
        var runtime = new SandboxRuntime(config(true), oafWithPackage(true), env);
        assertEquals(false, runtime.enabled());
    }

    @Test
    void envExplicitTrueShouldWinOverPackageFalse() {
        var env = new MockEnvironment().withProperty("SANDBOX_ENABLED", "true");
        var runtime = new SandboxRuntime(config(false), oafWithPackage(false), env);
        assertEquals(true, runtime.enabled());
    }

    @Test
    void packageDeclarationShouldApplyWhenEnvAbsent() {
        // 未设置 SANDBOX_ENABLED（MockEnvironment 无该属性）→ 包声明 true 生效（yml 绑定默认 false）
        var runtime = new SandboxRuntime(config(false), oafWithPackage(true), new MockEnvironment());
        assertEquals(true, runtime.enabled());
    }

    @Test
    void packageFalseShouldApplyWhenEnvAbsent() {
        var runtime = new SandboxRuntime(config(true), oafWithPackage(false), new MockEnvironment());
        assertEquals(false, runtime.enabled());
    }

    @Test
    void ymlBoundValueShouldApplyWhenNeitherEnvNorPackageDeclared() {
        var runtime = new SandboxRuntime(config(false), oafWithPackage(null), new MockEnvironment());
        assertEquals(false, runtime.enabled());
    }

    @Test
    void projectionAndGuardShouldPassThroughConfig() {
        var runtime = new SandboxRuntime(config(false), oafWithPackage(null), new MockEnvironment());
        assertEquals(true, runtime.projectionEnabled());
        assertEquals(true, runtime.guardEnabled());
        assertEquals(900, runtime.guardLeaseSeconds());
    }

    @Test
    void forcedConstructorShouldBypassResolution() {
        var runtime = new SandboxRuntime(config(false), true);
        assertEquals(true, runtime.enabled());
    }
}
