package io.agentmanager.framework.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UI 交互上下文注入 Hook 测试（MCP Apps 4.7）。
 * 验证：PreCallEvent 阶段从 user 消息 metadata 读取会话 key → 查库 →
 * appendSystemContent 注入；无 key / 无内容 / 异常时安全跳过。
 */
class UiContextInjectionHookTest {

    private UiContextStore store;
    private UiContextInjectionHook hook;
    private Agent agent;

    @BeforeEach
    void setUp() {
        store = mock(UiContextStore.class);
        hook = new UiContextInjectionHook(store);
        agent = mock(Agent.class);
    }

    private Msg userMsg(String text, Map<String, Object> metadata) {
        var b = Msg.builder().role(MsgRole.USER).textContent(text);
        if (metadata != null) {
            b.metadata(metadata);
        }
        return b.build();
    }

    @Test
    void shouldInjectUiContextFromUserMessageMetadata() {
        when(store.findBySession("acme-agent:debug-user:th1"))
            .thenReturn(Optional.of(new UiContextStore.UiContext("clock: 12:00", null, null)));

        var event = new PreCallEvent(agent, List.of(
            userMsg("hello", Map.of(UiContextStore.METADATA_SESSION_KEY, "acme-agent:debug-user:th1"))));

        hook.onEvent(event).block();

        verify(store).findBySession("acme-agent:debug-user:th1");
        assertNotNull(event.getSystemMessage(), "注入后应设置 system message");
        assertTrue(event.getSystemMessage().getTextContent().contains("clock: 12:00"),
            "system message 应包含 UI 上下文内容");
    }

    @Test
    void shouldSkipWhenNoContextRecord() {
        when(store.findBySession(anyString())).thenReturn(Optional.empty());

        var event = new PreCallEvent(agent, List.of(
            userMsg("hello", Map.of(UiContextStore.METADATA_SESSION_KEY, "acme-agent:debug-user:th2"))));

        hook.onEvent(event).block();

        verify(store).findBySession("acme-agent:debug-user:th2");
        assertNull(event.getSystemMessage(), "无 UI 上下文记录时不注入");
    }

    @Test
    void shouldSkipWithoutSessionKeyMetadata() {
        var event = new PreCallEvent(agent, List.of(userMsg("hello", null)));

        hook.onEvent(event).block();

        verify(store, never()).findBySession(anyString());
        assertFalse(event.getSystemMessage() != null, "无 session key 的普通聊天不注入");
    }

    @Test
    void shouldNotBreakCallOnStoreError() {
        when(store.findBySession(anyString()))
            .thenThrow(new IllegalStateException("db down"));

        var event = new PreCallEvent(agent, List.of(
            userMsg("hello", Map.of(UiContextStore.METADATA_SESSION_KEY, "acme-agent:debug-user:th4"))));

        hook.onEvent(event).block();
        // 不抛异常即通过：注入失败不阻断 agent 调用
    }
}