package io.agentmanager.framework.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.MsgRole;
import reactor.core.publisher.Mono;

/**
 * UI 交互上下文注入 Hook（MCP Apps 4.7：下次 agent 调用时注入为 system context）。
 *
 * <p>背景：HarnessAgent 拒绝 inputMessages 中 role=SYSTEM 的消息
 * （"Hooks must not inject SYSTEM messages into PreCallEvent.inputMessages"），
 * 官方唯一注入点是 PreCallEvent 的 {@code setSystemMessage / appendSystemContent}，
 * 且 Hook 无 RuntimeContext 可拿 sessionId——因此由 Controller 在用户消息
 * metadata 写入 {@link UiContextStore#METADATA_SESSION_KEY}，本 Hook 读取后
 * 按会话查库注入。metadata 随历史持久化，历史重放时仍注入最新值（符合
 * 「最后一次更新覆盖之前内容」语义）。
 *
 * <p>安全：sessionId 来自 Controller 侧受控路径；读取失败/异常一律跳过注入不阻断调用。
 */
public class UiContextInjectionHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(UiContextInjectionHook.class);

    private final UiContextStore uiContextStore;

    public UiContextInjectionHook(UiContextStore uiContextStore) {
        this.uiContextStore = uiContextStore;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent preCall) {
            injectUiContext(preCall);
        }
        return Mono.just(event);
    }

    /** 读取第一条 user 消息 metadata 中的 sessionId，注入该会话的 ui_context（异常不阻断） */
    private void injectUiContext(PreCallEvent event) {
        try {
            for (var msg : event.getInputMessages()) {
                if (msg.getRole() != MsgRole.USER || msg.getMetadata() == null) {
                    continue;
                }
                var sid = msg.getMetadata().get(UiContextStore.METADATA_SESSION_KEY);
                if (sid == null || sid.toString().isBlank()) {
                    continue;
                }
                var text = UiContextStore.renderInjectText(
                    uiContextStore.findBySession(sid.toString()).orElse(null));
                if (text != null) {
                    event.appendSystemContent(text);
                    log.info("UI context injected for session {}", sid);
                }
                return; // 只处理第一条 user 消息
            }
        } catch (Exception e) {
            log.warn("UI context injection hook skipped: {}", e.getMessage());
        }
    }
}
