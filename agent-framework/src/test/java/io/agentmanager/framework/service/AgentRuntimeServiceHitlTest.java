package io.agentmanager.framework.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.model.OafConfig;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * AgentRuntimeService HITL 测试：permission_ask 事件转发 + 确认上下文落库 + resumeWithConfirm 恢复。
 * 覆盖 hitl-permission-plan.md 6.2.1 / 6.3.1 / 12.5（无状态架构：确认上下文经 ConfirmContextStore
 * 落库跨副本可见，不再有进程内缓存/事件总线）。
 */
class AgentRuntimeServiceHitlTest {

    private HarnessAgent agent;
    private AgentRuntimeService service;
    private ConfirmContextStore confirmContextStore;

    private static final String SID = "t1";

    @BeforeEach
    void setUp() {
        agent = mock(HarnessAgent.class);
        confirmContextStore = mock(ConfirmContextStore.class);
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
    }

    private ToolUseBlock toolUseBlock(String id) {
        return ToolUseBlock.builder().id(id).name("get_weather")
            .input(Map.of("city", "beijing")).build();
    }

    private RequireUserConfirmEvent askEvent(String replyId, ToolUseBlock... calls) {
        return new RequireUserConfirmEvent("evt-1", "source-1", replyId, List.of(calls));
    }

    /** 模拟 agent 发出 RequireUserConfirmEvent（invokeStream 链路），并收集事件帧 */
    private List<Map<String, Object>> emitAskThenCollect() {
        var ask = askEvent("reply-1", toolUseBlock("call-1"));
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(ask));
        return service.invokeStream("query weather", SID, "alice").collectList().block();
    }

    /** 构造 store 回读行（模拟 DB 已存该 session 的确认上下文） */
    private ConfirmContextStore.StoredRow stubStoreRow() {
        var row = new ConfirmContextStore.StoredRow(
            List.of(toolUseBlock("call-1")), "reply-1", Instant.now(), null, null);
        when(confirmContextStore.consume(anyString())).thenReturn(row);
        return row;
    }

    // ---------- 6.2.1 invokeStream permission_ask ----------

    @Test
    void invokeStreamShouldEmitPermissionAskWithToolCalls() {
        var events = emitAskThenCollect();

        assertNotNull(events);
        var ask = events.stream().filter(e -> "permission_ask".equals(e.get("type")))
            .findFirst().orElse(null);
        assertNotNull(ask, "should emit permission_ask frame");
        assertEquals("reply-1", ask.get("reply_id"));
        assertEquals("t1", ask.get("task_id"));
        @SuppressWarnings("unchecked")
        var calls = (List<Map<String, Object>>) ask.get("tool_calls");
        assertEquals(1, calls.size());
        assertEquals("call-1", calls.get(0).get("tool_call_id"));
        assertEquals("get_weather", calls.get(0).get("name"));
        assertNotNull(calls.get(0).get("input"));
    }

    // ---------- 6.3.1 确认上下文落库 ----------

    @Test
    void confirmContextShouldBePersistedByAsk() {
        emitAskThenCollect();
        // 落库校验：storeConfirmContext → confirmContextStore.put（session 带 tenant 前缀）
        verify(confirmContextStore).put(eq("acme-test-agent__t1"), anyList(), anyString(), any(), any());
    }

    @Test
    void checkConfirmAvailableShouldFailWhenNoStoredRow() {
        // 无存储行：store 抛 NotFound（mock 需显式 stub，因为 void 默认不抛）
        doThrow(new AgentRuntimeService.ConfirmContextNotFoundException(SID))
            .when(confirmContextStore).checkAvailable(anyString());
        assertThrows(AgentRuntimeService.ConfirmContextNotFoundException.class,
            () -> service.checkConfirmAvailable(SID));
    }

    @Test
    void consumeConfirmContextShouldBeCasConsuming() {
        stubStoreRow();
        var sessionId = "acme-test-agent__" + SID;
        var first = service.consumeConfirmContext(sessionId);
        assertEquals(1, first.toolCalls().size());
    }

    @Test
    void checkConfirmAvailableShouldFailWhenStoreRejects() {
        doThrow(new AgentRuntimeService.ConfirmContextNotFoundException(SID))
            .when(confirmContextStore).checkAvailable(anyString());
        assertThrows(AgentRuntimeService.ConfirmContextNotFoundException.class,
            () -> service.checkConfirmAvailable(SID));
    }

    // ---------- 6.3 resumeWithConfirm（同步） ----------

    @Test
    void resumeWithConfirmShouldPassConfirmResultsAndReturnFinalReply() {
        stubStoreRow();
        var msg = mock(Msg.class);
        when(msg.getTextContent()).thenReturn("done");
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(msg));

        var result = service.resumeWithConfirm(SID, null, List.of(Map.<String, Object>of(
            "tool_call_id", "call-1", "confirmed", true, "accept_rule", false)));

        assertEquals("done", result.get("response"));
        assertEquals(SID, result.get("thread_id"));

        // 校验传给 agent 的消息携带 confirm_results metadata（含原始 ToolUseBlock）
        verify(agent).call(argThat((java.util.List<Msg> msgs) -> {
            if (msgs.isEmpty()) return false;
            var m = msgs.get(0);
            Object raw = m.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
            if (!(raw instanceof List<?> list) || list.size() != 1) return false;
            var cr = (ConfirmResult) list.get(0);
            return cr.isConfirmed()
                && "call-1".equals(cr.getToolCall().getId())
                && "get_weather".equals(cr.getToolCall().getName());
        }), any(RuntimeContext.class));
    }

    @Test
    void resumeWithConfirmShouldSupportReject() {
        stubStoreRow();
        var msg = mock(Msg.class);
        when(msg.getTextContent()).thenReturn("rejected and continue");
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(msg));

        service.resumeWithConfirm(SID, null, List.of(Map.<String, Object>of(
            "tool_call_id", "call-1", "confirmed", false, "accept_rule", false)));

        verify(agent).call(argThat((java.util.List<Msg> msgs) -> {
            var raw = msgs.get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
            if (!(raw instanceof List<?> list) || list.size() != 1) return false;
            var cr = (ConfirmResult) list.get(0);
            return !cr.isConfirmed() && "call-1".equals(cr.getToolCall().getId());
        }), any(RuntimeContext.class));
    }

    @Test
    void resumeWithConfirmShouldApproveOnlyExplicitBooleanTrue() {
        stubStoreRow();
        var reply = mock(Msg.class);
        when(reply.getTextContent()).thenReturn("done");
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(reply));
        var decisions = new Object[] { null, "true", "false", 1, false, true };
        for (var decision : decisions) {
            var result = new java.util.HashMap<String, Object>();
            result.put("tool_call_id", "call-1");
            if (decision != null) result.put("confirmed", decision);
            service.resumeWithConfirm(SID, null, List.of(result));
        }
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<List<Msg>>) (Class<?>) List.class);
        verify(agent, times(decisions.length)).call(captor.capture(), any(RuntimeContext.class));
        for (int i = 0; i < decisions.length; i++) {
            var raw = (List<?>) captor.getAllValues().get(i).get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
            var result = (ConfirmResult) raw.get(0);
            assertEquals(Boolean.TRUE.equals(decisions[i]), result.isConfirmed(), "confirmed=" + decisions[i]);
        }
    }

    @Test
    void resumeWithConfirmShould404WhenNoStoredRow() {
        when(confirmContextStore.consume(anyString()))
            .thenThrow(new AgentRuntimeService.ConfirmContextNotFoundException(SID));
        when(agent.call(anyList(), any(RuntimeContext.class)))
            .thenReturn(Mono.just(mock(Msg.class)));
        assertThrows(AgentRuntimeService.ConfirmContextNotFoundException.class,
            () -> service.resumeWithConfirm(SID, null,
                List.of(Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true))));
    }

    @Test
    void resumeWithConfirmShould409OnDuplicateConfirm() {
        stubStoreRow();
        when(confirmContextStore.consume(anyString()))
            .thenReturn(new ConfirmContextStore.StoredRow(
                List.of(toolUseBlock("call-1")), "reply-1", Instant.now(), null, null))
            .thenThrow(new AgentRuntimeService.ConfirmAlreadyConsumedException(SID));
        var msg = mock(Msg.class);
        when(msg.getTextContent()).thenReturn("ok");
        when(agent.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(msg));
        var body = List.of(Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true));

        service.resumeWithConfirm(SID, null, body);
        assertThrows(AgentRuntimeService.ConfirmAlreadyConsumedException.class,
            () -> service.resumeWithConfirm(SID, null, body));
    }

    @Test
    void resumeWithConfirmShouldRejectUnknownToolCallId() {
        stubStoreRow();
        assertThrows(IllegalArgumentException.class,
            () -> service.resumeWithConfirm(SID, null,
                List.of(Map.<String, Object>of("tool_call_id", "call-nope", "confirmed", true))));
    }

    // ---------- 6.3 resumeWithConfirmStream（流式，无总线扇出） ----------

    @Test
    void resumeWithConfirmStreamShouldEmitDoneAndCompleted() {
        stubStoreRow();
        var end = new io.agentscope.core.event.AgentEndEvent("reply-2");
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.just(end));

        var frames = service.resumeWithConfirmStream(SID, null,
            List.of(Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true))).collectList().block();

        assertNotNull(frames);
        assertTrue(frames.stream().anyMatch(f -> "done".equals(f.get("type"))));
        assertTrue(frames.stream().anyMatch(f -> "completed".equals(f.get("state"))));
    }

    @Test
    void resumeWithConfirmStreamShouldEmitErrorFrameWhenNoStoredRow() {
        when(confirmContextStore.consume(anyString()))
            .thenThrow(new AgentRuntimeService.ConfirmContextNotFoundException(SID));
        var frames = service.resumeWithConfirmStream(SID, null,
            List.of(Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true))).collectList().block();

        assertNotNull(frames);
        assertTrue(frames.stream().anyMatch(f ->
            "error".equals(f.get("type"))
                && String.valueOf(f.get("error")).contains("confirm_context_not_found")));
        assertTrue(frames.stream().anyMatch(f -> "done".equals(f.get("type"))));
    }

    // ---------- D1 修复回归：表内完整参数优先于 state 重建 ----------

    /**
     * 回归（D1）：SDK 落 agent_state 的 asking tool_use.input 恒为空 {}，
     * 若恢复时优先用 state 重建，工具会收到空参数而失败。
     * 本用例让 agentStateReader 返回一个"参数为空"的 state 快照 + 表内提供完整参数，
     * 断言恢复消息 metadata 里的 ConfirmResult 携带的是**表内完整参数**。
     */
    @Test
    void resumeConfirmShouldPreferTableParamsOverEmptyStateInput() {
        var fullParams = new LinkedHashMap<String, Object>();
        fullParams.put("application_id", "APP-0001");
        var tableBlock = new ToolUseBlock("call-1", "submit_application", fullParams, null, null,
            io.agentscope.core.message.ToolCallState.ASKING);
        when(confirmContextStore.findPending(anyString())).thenReturn(
            java.util.Optional.of(new ConfirmContextStore.PendingConfirm(
                "reply-1", List.of(tableBlock), Instant.now())));

        // state 侧：同名工具但参数为空（实测 SDK 行为）
        var stateReader = mock(io.agentmanager.framework.service.AgentStateReader.class);
        var emptyBlock = new ToolUseBlock("call-1", "submit_application",
            Map.of(), null, null, io.agentscope.core.message.ToolCallState.ASKING);
        // asking 列表非空是快照"命中"的判据（isEmpty 只看该列表）
        var askingEntry = new LinkedHashMap<String, Object>();
        askingEntry.put("tool_call_id", "call-1");
        askingEntry.put("name", "submit_application");
        askingEntry.put("input", Map.of());
        when(stateReader.loadAskingSnapshot(any(), any())).thenReturn(
            new io.agentmanager.framework.service.AgentStateReader.AskingSnapshot(
                List.of(askingEntry), Map.of("call-1", emptyBlock), "reply-1",
                Map.of("session_id", "gw-1", "user_id", "alice")));

        // 复用同一 OafConfig 形状的实例（tenantPrefix 用 acme/test-agent → acme-test-agent__）
        var oaf = new OafConfig(
            "test-agent", "acme", "test-agent", "1.0.0", "acme/test-agent",
            "Test agent", "@acme", "MIT",
            List.of("test"), "you are a helper.",
            List.of(), List.of(), List.of(), List.of(), List.of(),
            new OafConfig.ModelConfig("openai", "gpt-4", ""),
            new OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
            new OafConfig.MemoryConfig("editable", Map.of()),
            Map.of()
        );
        var agent2 = mock(HarnessAgent.class);
        var replyMsg = mock(Msg.class);
        when(replyMsg.getTextContent()).thenReturn("ok");
        when(agent2.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(replyMsg));
        var svc = new AgentRuntimeService(oaf, agent2, List.of(), new LLMLogger(), confirmContextStore);
        svc.setAgentStateReader(stateReader);

        svc.resumeWithConfirm(SID, null, List.of(
            Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true)));

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<List<Msg>>) (Class<?>) List.class);
        verify(agent2).call(captor.capture(), any(RuntimeContext.class));
        var raw = (List<?>) captor.getValue().get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        var confirmResult = (ConfirmResult) raw.get(0);
        assertEquals(Map.of("application_id", "APP-0001"), confirmResult.getToolCall().getInput(),
            "恢复执行必须携带表内完整参数，而非 state 的空 input");
    }

    /** state 无挂起 + 表无行 → 404（语义不回归） */
    @Test
    void resumeConfirmShouldStill404WhenBothSourcesEmpty() {
        when(confirmContextStore.findPending(anyString())).thenReturn(java.util.Optional.empty());
        when(confirmContextStore.consume(anyString()))
            .thenThrow(new AgentRuntimeService.ConfirmContextNotFoundException(SID));
        assertThrows(AgentRuntimeService.ConfirmContextNotFoundException.class,
            () -> service.resumeWithConfirm(SID, null,
                List.of(Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true))));
    }

    // ========== content=null 回归修复（fix: Schema validation error: argument "content" is null） ==========

    private static final OafConfig OAF = new OafConfig(
        "test-agent", "acme", "test-agent", "1.0.0", "acme/test-agent",
        "Test agent", "@acme", "MIT",
        List.of("test"), "you are a helper.",
        List.of(), List.of(), List.of(), List.of(), List.of(),
        new OafConfig.ModelConfig("openai", "gpt-4", ""),
        new OafConfig.RuntimeConfig(0.7, 4096, false, "default"),
        new OafConfig.MemoryConfig("editable", Map.of()),
        Map.of()
    );

    /**
     * state 路径回归（线上故障场景：confirm_context 行被 TTL 清理，恢复回落 agent_state）：
     * 快照重建的 ToolUseBlock 必须 content 非空（ToolValidator 用 content 做 schema 校验，
     * null 即抛 'argument "content" is null'）且 input 完好（工具实参来源）。
     * 构造真实 AgentStateReader + 线上实测形态的 state_data（asking 块 input={}、content="{}"），
     * 通过 ConfirmResult 携带的块断言两路参数。
     */
    @Test
    void stateFallbackShouldRebuildBlocksWithNonNullContentAndCompleteInput() throws Exception {
        // 线上 DB 实测形态：SDK 挂起落 state 的 asking 块 input 为空对象、content 为 "{}"
        //（参数只存在于 confirm_context / permission_ask，TTL 清理后仅剩 state）
        var stateData = """
            {"session_id":"gw-3f20f08c5499","user_id":"webui-u1","context":[
               {"role":"USER","content":[{"type":"text","text":"发布服务"}]},
               {"role":"ASSISTANT",
                "content":[{"type":"tool_use","id":"call-1","name":"publish_service",
                  "input":{},"content":"{}","state":"asking"}],
                "metadata":{"agentscope_confirm_request_reply_id":"reply-1"}}
             ]}
            """;
        var dataSource = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        var reader = new AgentStateReader(dataSource) {
            @Override
            public String loadStateData(String sessionId) {
                return stateData;   // 免去真实 DB：注入实测形态的 state JSON
            }
        };

        var agent2 = mock(HarnessAgent.class);
        var replyMsg = mock(Msg.class);
        when(replyMsg.getTextContent()).thenReturn("ok");
        when(agent2.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(replyMsg));
        var svc = new AgentRuntimeService(OAF, agent2, List.of(), new LLMLogger(), confirmContextStore);
        svc.setAgentStateReader(reader);

        // 表无行（TTL 清理）→ 走 state fallback
        when(confirmContextStore.findPending(anyString())).thenReturn(java.util.Optional.empty());

        svc.resumeWithConfirm(SID, null, List.of(
            Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true)));

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<List<Msg>>) (Class<?>) List.class);
        verify(agent2).call(captor.capture(), any(RuntimeContext.class));
        var raw = (List<?>) captor.getValue().get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        var confirmResult = (ConfirmResult) raw.get(0);
        var block = confirmResult.getToolCall();

        // 修复点①：到达 SDK ToolValidator 的 content 必须非 null（原本恒 null → 校验崩溃）
        assertNotNull(block.getContent(), "重建块 content 必须非空，否则 ToolValidator 抛 content is null");
        assertEquals("{}", block.getContent(), "state 块携带原始 content 时原样回填");
        // 修复点②：input 是普通 Map（JsonNode 形态会被 instanceof 判断丢成空 Map）
        assertNotNull(block.getInput());
        assertEquals(java.util.Map.of(), block.getInput(), "input 保持 SDK 落盘形态（空对象）");
    }

    /**
     * state 路径回归（老数据形态：asking 块无 content 字段）：
     * 重建块 content 必须回填为 input 的 JSON 字符串（恒不落 null）。
     */
    @Test
    void stateFallbackShouldBackfillContentFromInputWhenBlockHasNoContent() throws Exception {
        var stateData = """
            {"session_id":"gw-3f20f08c5499","user_id":"webui-u1","context":[
               {"role":"ASSISTANT",
                "content":[{"type":"tool_use","id":"call-1","name":"publish_service",
                  "input":{"packageId":166},"state":"asking"}],
                "metadata":{"agentscope_confirm_request_reply_id":"reply-1"}}
             ]}
            """;
        var dataSource = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        var reader = new AgentStateReader(dataSource) {
            @Override
            public String loadStateData(String sessionId) {
                return stateData;
            }
        };

        var agent2 = mock(HarnessAgent.class);
        var replyMsg = mock(Msg.class);
        when(replyMsg.getTextContent()).thenReturn("ok");
        when(agent2.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(replyMsg));
        var svc = new AgentRuntimeService(OAF, agent2, List.of(), new LLMLogger(), confirmContextStore);
        svc.setAgentStateReader(reader);
        when(confirmContextStore.findPending(anyString())).thenReturn(java.util.Optional.empty());

        svc.resumeWithConfirm(SID, null, List.of(
            Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true)));

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<List<Msg>>) (Class<?>) List.class);
        verify(agent2).call(captor.capture(), any(RuntimeContext.class));
        var raw = (List<?>) captor.getValue().get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        var block = ((ConfirmResult) raw.get(0)).getToolCall();

        assertEquals("{\"packageId\":166}", block.getContent(),
            "块无 content 时必须回填 input 的 JSON 字符串（ToolValidator 校验不落 null）");
        assertEquals(Map.of("packageId", 166), block.getInput(), "input 完好传递（工具实参来源）");
    }

    /**
     * table 路径回归：ConfirmContextStore.toToolCalls 已做 content 回填（9f3fff5），
     * 表行恢复的 ConfirmResult 块 content 必须非空、input 完好——防回归守门。
     */
    @Test
    void tablePathShouldCarryNonNullContentAndCompleteInput() {
        var tableBlock = new ToolUseBlock("call-1", "publish_service",
            Map.of("packageId", 166), null, null, io.agentscope.core.message.ToolCallState.ASKING);
        when(confirmContextStore.findPending(anyString())).thenReturn(
            java.util.Optional.of(new ConfirmContextStore.PendingConfirm(
                "reply-1", List.of(tableBlock), Instant.now())));

        var agent2 = mock(HarnessAgent.class);
        var replyMsg = mock(Msg.class);
        when(replyMsg.getTextContent()).thenReturn("ok");
        when(agent2.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(replyMsg));
        var svc = new AgentRuntimeService(OAF, agent2, List.of(), new LLMLogger(), confirmContextStore);
        // state 侧无挂起（空快照），走表路径
        var stateReader = mock(io.agentmanager.framework.service.AgentStateReader.class);
        when(stateReader.loadAskingSnapshot(any(), any())).thenReturn(
            new io.agentmanager.framework.service.AgentStateReader.AskingSnapshot(
                List.of(), java.util.Map.of(), "",
                java.util.Map.of("session_id", "", "user_id", "")));
        svc.setAgentStateReader(stateReader);

        svc.resumeWithConfirm(SID, null, List.of(
            Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true)));

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<List<Msg>>) (Class<?>) List.class);
        verify(agent2).call(captor.capture(), any(RuntimeContext.class));
        var raw = (List<?>) captor.getValue().get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        var confirmResult = (ConfirmResult) raw.get(0);
        assertEquals(Map.of("packageId", 166), confirmResult.getToolCall().getInput(),
            "表路径携带完整参数（D1 语义不回归）");
    }

    /**
     * 表块 input 为空（反向异常）且 state 同 id 参数完好：enrich 后 content 同步取 state 侧
     * 完整参数 JSON——若保留表侧 "{}" 会误报缺参（参数校验失败），修复见 enrichWithStateInput。
     */
    @Test
    void enrichFromStateShouldSyncContentWithStateParams() {
        var tableBlock = new ToolUseBlock("call-1", "publish_service",
            Map.of(), "{}", null, io.agentscope.core.message.ToolCallState.ASKING);
        when(confirmContextStore.findPending(anyString())).thenReturn(
            java.util.Optional.of(new ConfirmContextStore.PendingConfirm(
                "reply-1", List.of(tableBlock), Instant.now())));

        var stateReader = mock(io.agentmanager.framework.service.AgentStateReader.class);
        var stateBlock = new ToolUseBlock("call-1", "publish_service",
            new LinkedHashMap<>(Map.of("packageId", 166)), "{\"packageId\":166}", null,
            io.agentscope.core.message.ToolCallState.ASKING);
        var askingEntry = new LinkedHashMap<String, Object>();
        askingEntry.put("tool_call_id", "call-1");
        askingEntry.put("name", "publish_service");
        askingEntry.put("input", new LinkedHashMap<>(Map.of("packageId", 166)));
        when(stateReader.loadAskingSnapshot(any(), any())).thenReturn(
            new io.agentmanager.framework.service.AgentStateReader.AskingSnapshot(
                List.of(askingEntry), Map.of("call-1", stateBlock), "reply-1",
                Map.of("session_id", "gw-1", "user_id", "alice")));

        var agent2 = mock(HarnessAgent.class);
        var replyMsg = mock(Msg.class);
        when(replyMsg.getTextContent()).thenReturn("ok");
        when(agent2.call(anyList(), any(RuntimeContext.class))).thenReturn(Mono.just(replyMsg));
        var svc = new AgentRuntimeService(OAF, agent2, List.of(), new LLMLogger(), confirmContextStore);
        svc.setAgentStateReader(stateReader);

        svc.resumeWithConfirm(SID, null, List.of(
            Map.<String, Object>of("tool_call_id", "call-1", "confirmed", true)));

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass((Class<List<Msg>>) (Class<?>) List.class);
        verify(agent2).call(captor.capture(), any(RuntimeContext.class));
        var raw = (List<?>) captor.getValue().get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        var block = ((ConfirmResult) raw.get(0)).getToolCall();
        assertEquals(Map.of("packageId", 166), block.getInput(), "input 取 state 侧完好参数");
        assertEquals("{\"packageId\":166}", block.getContent(), "content 与 state 侧参数保持一致");
    }
}
