package io.agentmanager.framework.agui;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentmanager.framework.service.UiContextStore;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * AG-UI 路径 system prompt 注入 middleware（agui-migration-plan Phase 1 要点 6）。
 *
 * <p>背景：UiContextInjectionHook 从用户消息 metadata 读会话 key，而 AG-UI 路径消息由内置
 * converter 构建、不携带 metadata，通路断裂；且 PreCallEvent 不暴露 RuntimeContext。
 * onSystemPrompt(agent, ctx, prompt) 是唯一同时具备「会话上下文 + 注入点」的 middleware 钩子
 * （HookEvent javadoc 禁止 inputMessages 注入 SYSTEM，AgentBase 对 ReActAgent 硬校验抛
 * IllegalStateException——本类一律走 system prompt 通路规避）。
 *
 * <p>两个职责（同一载体）：
 * <ol>
 *   <li>UiContext 注入：controller 把 {@link #RUNTIME_UI_CONTEXT_SESSION_KEY}（=threadId）写入
 *       RuntimeContext，middleware 按会话查 ui_context 表追加（对齐 4.7 语义）。</li>
 *   <li>R2 resume 防循环指引：adapter 将 input.resume 注入 RuntimeContext（agui.resume key），
 *       非空即判定 resume run，追加中文执行指引（等效旧 buildResumeMsg，弥补 adapter 生成的
 *       ConfirmResult 消息仅含 approved/denied 文本）。</li>
 * </ol>
 *
 * <p>无相应 RuntimeContext key 时 no-op——本 middleware 注册在共享 HarnessAgent bean 上，
 * （原 UiContextInjectionHook 经 PreCallEvent 注入，随旧链路退役，本 middleware 为唯一通路）
 * （hook 依赖消息 metadata、本类依赖 RuntimeContext key），共存期无双重注入。
 */
public class OafAguiMiddleware implements MiddlewareBase {

    /** RuntimeContext key：AG-UI 路径 controller 注入的 UiContext 会话 key（=threadId） */
    public static final String RUNTIME_UI_CONTEXT_SESSION_KEY = "uiContextSessionId";

    /** resume run 追加的执行指引（R2：adapter ConfirmResult 仅 approved/denied 文本，缺防循环指令） */
    private static final String RESUME_GUIDANCE =
        "\n\n[人工确认恢复] 上轮存在挂起的工具调用并已由人工确认恢复：被批准的调用将由系统立即执行，"
            + "执行成功后直接向用户汇报结果并结束流程，不得再次调用同一工具；"
            + "被拒绝的调用不得执行，直接向用户说明拒绝原因。";

    private final UiContextStore uiContextStore;

    public OafAguiMiddleware(UiContextStore uiContextStore) {
        this.uiContextStore = uiContextStore;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        if (ctx == null) {
            return Mono.just(currentPrompt == null ? "" : currentPrompt);
        }
        try {
            var prompt = currentPrompt == null ? "" : currentPrompt;
            prompt = appendUiContext(ctx, prompt);
            prompt = appendResumeGuidance(ctx, prompt);
            return Mono.just(prompt);
        } catch (Exception e) {
            // 注入失败不阻断调用（对齐 UiContextInjectionHook 容错语义）
            return Mono.just(currentPrompt == null ? "" : currentPrompt);
        }
    }

    /** UiContext 注入（异常不阻断） */
    private String appendUiContext(RuntimeContext ctx, String prompt) {
        var sessionId = ctx.get(RUNTIME_UI_CONTEXT_SESSION_KEY);
        if (!(sessionId instanceof String sid) || sid.isBlank()) {
            return prompt;
        }
        var text = UiContextStore.renderInjectText(
            uiContextStore.findBySession(sid).orElse(null));
        return text == null ? prompt : prompt + text;
    }

    /** resume run 防循环指引（adapter 注入 agui.resume 非空时） */
    private String appendResumeGuidance(RuntimeContext ctx, String prompt) {
        var resume = ctx.get(AguiAgentAdapter.RUNTIME_CONTEXT_RESUME_KEY);
        if (resume instanceof List<?> list && !list.isEmpty()) {
            return prompt + RESUME_GUIDANCE;
        }
        return prompt;
    }
}
