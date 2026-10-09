package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;
import io.agentscope.harness.agent.subagent.RemoteAskPolicy;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.protocol.RemoteStreamDetail;

/**
 * HarnessAgentFactory 远程子 agent 声明映射单测（travel-fulfillment §8 lead-1）：
 * OAF agents[].endpoint → SDK SubagentDeclaration 字段断言，不起 Spring 上下文
 * （与 AgentScopeConfigTest 直调 buildPermissionContext 同例）。
 */
class HarnessAgentFactoryRemoteSubagentTest {

    private HarnessAgentFactory factory(String remoteHeadersJson) {
        return factoryWithSettings(new AgentManagerProperties.AgentProtocolSettings(
            false, "", "", 7, 24, 5, remoteHeadersJson, true, 120, "memory"));
    }

    private HarnessAgentFactory factoryWithSettings(AgentManagerProperties.AgentProtocolSettings settings) {
        var props = new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-test", "gpt-4", "https://api.openai.com/v1", "openai", 0.7, 4096, 120, true, 0, "", null),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/test", "user", "pass", "test"),
            "/test", "",
            new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20, "image/*,text/plain,text/markdown,text/csv,application/pdf", 5, 15, 50, true, 7, "local", "/data/files", "", "", "", "agent-files", ""),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            AgentManagerProperties.HarnessConfig.defaults(),
            settings);
        return new HarnessAgentFactory(props, null, null, List.of(),
            org.mockito.Mockito.mock(RemoteConfirmBridge.class));
    }

    private OafConfig oaf(List<OafConfig.SubAgentConfig> subAgents) {
        return new OafConfig(
            "trip-lead", "acme", "trip-lead", "1.0.0", "acme/trip-lead",
            "行程履约主管", "@acme", "MIT",
            List.of("travel"), "You are a trip lead.",
            List.of(), List.of(), subAgents, List.of(), List.of(),
            null,
            new OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
            null, java.util.Map.of());
    }

    private OafConfig.SubAgentConfig subAgent(String agent, String role, String endpoint) {
        return new OafConfig.SubAgentConfig("internal", agent, "1.0.0", role,
            List.of(), false, endpoint);
    }

    @Test
    void endpointDeclarationShouldMapToRemoteSubagentDeclaration() {
        var oaf = oaf(List.of(
            subAgent("booking", "订票专员", "http://booking.agent-platform.svc.cluster.local:8100"),
            subAgent("researcher", "研究员", "")));

        var declarations = factory(null).buildRemoteSubagentDeclarations(oaf);

        // endpoint 为空者不映射（走本地 subagents/*.md 链路），仅远程声明进入
        assertEquals(1, declarations.size());
        var booking = declarations.get(0);
        assertEquals("booking", booking.getName());
        assertEquals("订票专员", booking.getDescription());
        assertEquals("http://booking.agent-platform.svc.cluster.local:8100", booking.getUrl());
        assertTrue(booking.isRemote());
        assertTrue(booking.isRemoteStreaming());
        assertEquals(RemoteStreamDetail.FULL, booking.getRemoteStreamDetail());
        assertEquals(RemoteAskPolicy.PROPAGATE, booking.getRemoteAskPolicy());
    }

    @Test
    void noEndpointDeclarationsShouldMapToEmptyList() {
        var oaf = oaf(List.of(
            subAgent("alpha", "role-a", ""),
            subAgent("beta", "role-b", null)));

        assertTrue(factory(null).buildRemoteSubagentDeclarations(oaf).isEmpty());
    }

    @Test
    void headersJsonShouldBeParsedAndAttached() {
        var oaf = oaf(List.of(subAgent("booking", "订票专员", "http://booking:8100")));

        var declarations = factory("{\"X-Agent-Protocol-Token\":\"tok-1\",\"X-Tenant\":\"acme\"}")
            .buildRemoteSubagentDeclarations(oaf);

        assertEquals(1, declarations.size());
        assertEquals(Map.of("X-Agent-Protocol-Token", "tok-1", "X-Tenant", "acme"),
            declarations.get(0).getHeaders());
    }

    @Test
    void malformedHeadersJsonShouldBeSkippedNotFail() {
        var oaf = oaf(List.of(subAgent("booking", "订票专员", "http://booking:8100")));

        var declarations = factory("{not-json").buildRemoteSubagentDeclarations(oaf);

        assertEquals(1, declarations.size());
        // SDK 未设置 headers 时 getHeaders() 返回 null（等价无 header）
        var headers = declarations.get(0).getHeaders();
        assertTrue(headers == null || headers.isEmpty());
    }

    @Test
    void nonObjectHeadersJsonShouldBeSkipped() {
        var oaf = oaf(List.of(subAgent("booking", "订票专员", "http://booking:8100")));

        var declarations = factory("[\"X-Token\"]").buildRemoteSubagentDeclarations(oaf);

        assertEquals(1, declarations.size());
        var headers = declarations.get(0).getHeaders();
        assertTrue(headers == null || headers.isEmpty());
    }

    @Test
    void nonStringValueEntriesShouldBeSkipped() {
        var oaf = oaf(List.of(subAgent("booking", "订票专员", "http://booking:8100")));

        var declarations = factory("{\"X-Token\":\"tok-1\",\"retries\":3,\"flag\":null}")
            .buildRemoteSubagentDeclarations(oaf);

        assertEquals(1, declarations.size());
        assertEquals(Map.of("X-Token", "tok-1"), declarations.get(0).getHeaders());
    }

    @Test
    void blankRoleShouldFallBackToAgentNameAsDescription() {
        // SDK build() 校验 description 非空：role 缺失时回落 agent 名，保证构建不失败
        var oaf = oaf(List.of(new OafConfig.SubAgentConfig("internal", "booking", "1.0.0",
            null, List.of(), false, "http://booking:8100")));

        var declarations = factory(null).buildRemoteSubagentDeclarations(oaf);

        assertEquals(1, declarations.size());
        assertEquals("booking", declarations.get(0).getDescription());
    }

    @Test
    void nullAgentProtocolSectionShouldNotFailMapping() {
        // 兼容构造器补 defaults()，但显式传 null 的防御：映射不 NPE、headers 视为空
        var oaf = oaf(List.of(subAgent("booking", "订票专员", "http://booking:8100")));

        var declarations = factoryWithSettings(null).buildRemoteSubagentDeclarations(oaf);

        assertEquals(1, declarations.size());
        assertEquals("booking", declarations.get(0).getName());
        var headers = declarations.get(0).getHeaders();
        assertTrue(headers == null || headers.isEmpty());
    }
}
