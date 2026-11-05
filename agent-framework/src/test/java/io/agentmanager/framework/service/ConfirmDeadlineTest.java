package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.agentmanager.framework.controller.AgentEventSseSerializer;
import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;

/**
 * D16 确认硬截止三形态测试（sandbox-pool-design §8 / §10）：
 * <ol>
 *   <li>截止内确认 → 不抛（正常确认路径）；</li>
 *   <li>迟到确认 → {@link AgentRuntimeService.ConfirmDeadlineExpiredException}（410 confirm_expired）；</li>
 *   <li>迟到普通消息 → 同一判定入口（ConfirmDeadlineService.finalizeIfExpired）按拒绝自动收尾；</li>
 *   <li>时钟源双档：confirm 行 created_at 主 / state updated_at 兜底（行被 TTL 清理后）；</li>
 *   <li>deadline 关闭（≤0）→ 现状行为（不判定）；</li>
 *   <li>SSE permission_ask 帧 expires_at 透出与 history pendingConfirm.expires_at。</li>
 * </ol>
 */
class ConfirmDeadlineTest {

    private static final String SID = "t1";

    private HarnessAgent agent;
    private AgentRuntimeService service;
    private ConfirmContextStore confirmContextStore;
    private AgentStateReader agentStateReader;
    private TurnLeaseStore turnLeaseStore;
    private SessionEventBus eventBus;
    private ConfirmDeadlineService deadlineService;

    @BeforeEach
    void setUp() {
        agent = mock(HarnessAgent.class);
        confirmContextStore = mock(ConfirmContextStore.class);
        agentStateReader = mock(AgentStateReader.class);
        turnLeaseStore = mock(TurnLeaseStore.class);
        eventBus = mock(SessionEventBus.class);
        var config = new OafConfig(
            "test-agent", "acme", "test-agent", "1.0.0", "acme/test-agent",
            "Test agent", "@acme", "MIT",
            List.of("test"), "you are a helper.",
            List.of(), List.of(), List.of(), List.of(), List.of(),
            new OafConfig.ModelConfig("openai", "gpt-4", ""),
            new OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
            new OafConfig.MemoryConfig("editable", Map.of()),
            Map.of()
        );
        service = new AgentRuntimeService(config, agent, List.of(), new LLMLogger(), confirmContextStore);
        service.setAgentStateReader(agentStateReader);
        service.setConfirmDeadlineMinutes(10);
        deadlineService = new ConfirmDeadlineService(service, turnLeaseStore, eventBus,
            agentStateReader, confirmContextStore);
    }

    private ToolUseBlock toolUseBlock(String id) {
        return ToolUseBlock.builder().id(id).name("get_weather")
            .input(Map.of("city", "beijing")).build();
    }

    private ConfirmContextStore.PendingConfirm row(Instant createdAt) {
        // 兼容构造（本地行形态）：replyId/toolCalls/createdAt/runtimeSid/runtimeUid
        return new ConfirmContextStore.PendingConfirm(
            "reply-1", List.of(toolUseBlock("call-1")), createdAt, null, null);
    }

    /** 表行在 TTL 内（主时钟源） */
    private void stubFreshRow() {
        when(confirmContextStore.findPending(anyString())).thenReturn(java.util.Optional.of(row(Instant.now())));
    }

    /** 表行已被 TTL 清理（findPending 空）→ state updated_at 兜底时钟源 */
    private void stubRowGoneStateAskedAt(Instant askedAt) {
        when(confirmContextStore.findPending(anyString())).thenReturn(java.util.Optional.empty());
        when(agentStateReader.loadAskingUpdatedAt(anyString(), anyString())).thenReturn(askedAt);
    }

    // ---------------------------------------------------------------- 截止判定三形态

    @Test
    void freshConfirmShouldNotThrow() {
        stubFreshRow();
        assertDoesNotThrow(() -> service.checkConfirmDeadline(SID));
    }

    @Test
    void expiredRowCreatedAtShouldThrow() {
        // D16-① 迟到确认：行 created_at 距今 > deadline → ConfirmDeadlineExpiredException
        when(confirmContextStore.findPending(anyString()))
            .thenReturn(java.util.Optional.of(row(Instant.now().minus(Duration.ofMinutes(11)))));
        var ex = assertThrows(AgentRuntimeService.ConfirmDeadlineExpiredException.class,
            () -> service.checkConfirmDeadline(SID));
        assertTrue(ex.getMessage().startsWith("confirm_expired"));
        assertNotNull(ex.askedAt);
        assertNotNull(ex.deadline);
    }

    @Test
    void expiredStateUpdatedAtFallbackShouldThrow() {
        // D16 时钟源双档：行被 TTL 清理 → state updated_at 兜底（V1 基线核实该列 ON UPDATE CURRENT_TIMESTAMP）
        stubRowGoneStateAskedAt(Instant.now().minus(Duration.ofMinutes(20)));
        assertThrows(AgentRuntimeService.ConfirmDeadlineExpiredException.class,
            () -> service.checkConfirmDeadline(SID));
    }

    @Test
    void undeterminableClockShouldPassThrough() {
        // 双源都不可判定 → 保守放行（宁可晚收尾不可误作废）
        when(confirmContextStore.findPending(anyString())).thenReturn(java.util.Optional.empty());
        when(agentStateReader.loadAskingUpdatedAt(anyString(), anyString())).thenReturn(null);
        assertDoesNotThrow(() -> service.checkConfirmDeadline(SID));
    }

    @Test
    void deadlineDisabledShouldSkipCheck() {
        service.setConfirmDeadlineMinutes(0); // 现状行为（测试/显式关闭）
        when(confirmContextStore.findPending(anyString()))
            .thenReturn(java.util.Optional.of(row(Instant.now().minus(Duration.ofDays(1)))));
        assertDoesNotThrow(() -> service.checkConfirmDeadline(SID));
    }

    @Test
    void confirmShouldPassWhenPoolEnabledButSessionNeverBorrowed() {
        // D16-④ + 缺陷②回归锁定（sandbox-pool-design）：池开启 + 会话从未经池借出
        // （s: 无映射——fail-open 容器/池刚开启/未知映射）→ 确认预检续约放行（200 流程），绝不误 410
        var redis = mock(io.lettuce.core.cluster.api.sync.RedisClusterCommands.class);
        var facade = mock(io.agentmanager.framework.redis.RedisConnectionFacade.class);
        when(facade.key(anyString())).thenAnswer(inv -> "pfx:" + inv.getArgument(0));
        when(facade.sync()).thenReturn(redis);
        // 真实 TOUCH_PROBE_LUA 对无 s: 映射的会话返回空串（HGET s:{sid} = nil → return ''）
        when(redis.eval(anyString(), any(io.lettuce.core.ScriptOutputType.class),
            any(String[].class), any(String[].class))).thenReturn("");
        var sandboxConfig = new io.agentmanager.framework.config.SandboxConfig(true, "img", 60, 1024, 1,
            List.of("/entry.sh"), Duration.ofMillis(100), true, true, 900, true, true, true, true, 500, 30,
            new io.agentmanager.framework.config.SandboxConfig.OpenSandboxConfig("server:8090", "k"));
        var pool = new io.agentmanager.framework.sandbox.opensandbox.SandboxPoolManager(facade,
            new io.agentmanager.framework.config.SandboxConfig.PoolConfig(true, 2, 8, 120, 600, 60, 2),
            10, sandboxConfig, null);
        assertDoesNotThrow(() -> pool.touchSessionOrThrow(SID), "未借出会话的确认预检必须放行");
        // deadline 判定与池 touch 是确认入口的两道独立预检：池放行后 deadline 内确认照常通过
        stubFreshRow();
        assertDoesNotThrow(() -> service.checkConfirmDeadline(SID));
    }

    // ---------------------------------------------------------------- 自动收尾（finalizeByDeny）

    @Test
    void finalizeByDenyShouldResumeWithAllFalseResults() {
        // D16 收尾 = 复用 reject→DENY 分支：全 false 结果走现有恢复流
        when(confirmContextStore.findPending(anyString())).thenReturn(java.util.Optional.empty());
        var asking = new java.util.LinkedHashMap<String, Object>();
        asking.put("tool_call_id", "call-1");
        asking.put("name", "get_weather");
        asking.put("input", Map.of());
        when(agentStateReader.loadAskingSnapshot(anyString(), anyString()))
            .thenReturn(new AgentStateReader.AskingSnapshot(
                List.of(asking), Map.of("call-1", toolUseBlock("call-1")), "reply-1",
                Map.of("session_id", "", "user_id", "")));
        when(agentStateReader.loadAskingUpdatedAt(anyString(), anyString()))
            .thenReturn(Instant.now().minus(Duration.ofMinutes(30)));
        when(turnLeaseStore.acquire(anyString(), any(Duration.class))).thenReturn("tok-1");
        when(agent.streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
            .thenReturn(Flux.empty());

        assertTrue(deadlineService.finalizeByDeny(SID));
        // 恢复消息携带全 false confirm_results（模型看到工具被拒、turn 正常收尾）
        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass((Class<List<Msg>>) (Class<?>) List.class);
        verify(agent).streamEvents(captor.capture(), any(io.agentscope.core.agent.RuntimeContext.class));
        var msgs = captor.getValue();
        assertEquals(1, msgs.size());
        var results = (List<?>) msgs.get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertNotNull(results);
        assertEquals(1, results.size());
        var cr = (io.agentscope.core.event.ConfirmResult) results.get(0);
        assertFalse(cr.isConfirmed());
    }

    @Test
    void finalizeByDenyShouldReturnFalseWhenLeaseBusy() {
        // 抢租约失败（另一副本收尾/对话进行中）→ 不收尾（挂起将由那边解除）
        when(turnLeaseStore.acquire(anyString(), any(Duration.class))).thenReturn(null);
        assertFalse(deadlineService.finalizeByDeny(SID));
        verify(agent, never()).streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class));
    }

    @Test
    void finalizeByDenyShouldReturnFalseWhenNoPendingTools() {
        // 无挂起项（state 快照空 + 表行空）→ 无需收尾
        when(agentStateReader.loadAskingSnapshot(anyString(), anyString()))
            .thenReturn(new AgentStateReader.AskingSnapshot(
                List.of(), Map.of(), "", Map.of("session_id", "", "user_id", "")));
        when(confirmContextStore.findPending(anyString())).thenReturn(java.util.Optional.empty());
        assertFalse(deadlineService.finalizeByDeny(SID));
    }

    @Test
    void finalizeIfExpiredShouldNotFinalizeWhenFresh() {
        // 截止内（未超时）→ finalizeIfExpired 返回 false，正常确认路径继续
        stubFreshRow();
        assertFalse(deadlineService.finalizeIfExpired(SID));
        verify(agent, never()).streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class));
    }

    @Test
    void finalizeIfExpiredShouldFinalizeWhenExpired() {
        // D16-② 迟到普通消息：chat 入口预检发现超截止 → 懒收尾后放行
        stubRowGoneStateAskedAt(Instant.now().minus(Duration.ofMinutes(30)));
        var asking = new java.util.LinkedHashMap<String, Object>();
        asking.put("tool_call_id", "call-1");
        asking.put("name", "get_weather");
        asking.put("input", Map.of());
        when(agentStateReader.loadAskingSnapshot(anyString(), anyString()))
            .thenReturn(new AgentStateReader.AskingSnapshot(
                List.of(asking), Map.of("call-1", toolUseBlock("call-1")), "reply-1",
                Map.of("session_id", "", "user_id", "")));
        when(turnLeaseStore.acquire(anyString(), any(Duration.class))).thenReturn("tok-1");
        when(agent.streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
            .thenReturn(Flux.empty());
        assertTrue(deadlineService.finalizeIfExpired(SID));
    }

    // ---------------------------------------------------------------- expires_at 透出

    @Test
    void permissionAskPayloadShouldCarryExpiresAtWhenEnabled() throws Exception {
        AgentEventSseSerializer.configureConfirmDeadline(10);
        var ask = new io.agentscope.core.event.RequireUserConfirmEvent(
            "evt-1", "src-1", "reply-1", List.of(toolUseBlock("call-1")));
        var json = AgentEventSseSerializer.payload(ask);
        assertTrue(json.contains("expires_at"), "启用截止时 permission_ask 帧应带 expires_at");
        // 可解析为合法时刻
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        @SuppressWarnings("unchecked")
        var m = (java.util.Map<String, Object>) mapper.readValue(json, java.util.Map.class);
        var expiresAt = Instant.parse((String) m.get("expires_at"));
        assertTrue(expiresAt.isAfter(Instant.now().minusSeconds(5)));
        AgentEventSseSerializer.configureConfirmDeadline(0);
    }

    @Test
    void permissionAskPayloadShouldOmitExpiresAtWhenDisabled() {
        AgentEventSseSerializer.configureConfirmDeadline(0);
        var ask = new io.agentscope.core.event.RequireUserConfirmEvent(
            "evt-1", "src-1", "reply-1", List.of(toolUseBlock("call-1")));
        assertFalse(AgentEventSseSerializer.payload(ask).contains("expires_at"),
            "未启用截止时不带该字段（存量前端行为零变化）");
    }

    @Test
    void findPendingConfirmShouldExposeExpiresAt() {
        var now = Instant.now();
        var p = new ConfirmContextStore.PendingConfirm(
            null, "local", "reply-1", List.of(toolUseBlock("call-1")), now, null, null, null);
        when(confirmContextStore.findHeadPending(anyString()))
            .thenReturn(java.util.Optional.of(p));
        var payload = service.findPendingConfirm(SID);
        assertNotNull(payload);
        assertTrue(payload.containsKey("expires_at"), "history pendingConfirm 应带 expires_at（前端历史回放倒计时）");
        var expected = now.plusSeconds(600);
        var actual = Instant.parse((String) payload.get("expires_at"));
        assertTrue(Math.abs(Duration.between(expected, actual).toSeconds()) <= 1);
    }
}
