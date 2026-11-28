package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import reactor.core.publisher.Flux;

public class StageProgressMiddleware implements MiddlewareBase {

    static final String MARKER = "<stage>";

    private static final String INSTRUCTION = "\n\n[执行阶段播报规范]\n"
        + "1. 当你需要调用一个或多个工具来推进新阶段时，在发起工具调用前，先单独输出一行 "
        + "<stage>8到20字的阶段描述</stage>（与用户语言一致，概括本阶段业务目标，不要出现工具名或代码参数）。"
        + "同一轮并行调用多个工具只输出 1 条 <stage>，同阶段小幅重试不重复输出。\n"
        + "2. 如果本轮执行过工具调用，在开始写最终答复正文之前，先单独输出一行 "
        + "<summary>10到24字的完成态总摘要</summary>（如「已查询平台已发布服务与默认配置」）。\n"
        + "3. 纯问答且不调用工具时，不要输出 <stage> 或 <summary>。";

    private final boolean enabled;

    public StageProgressMiddleware() {
        this(true);
    }

    public StageProgressMiddleware(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!enabled || input == null || input.tools() == null || input.tools().isEmpty()) {
            return next.apply(input);
        }
        List<Msg> messages = input.messages();
        if (messages == null || hasInstruction(messages)) {
            return next.apply(input);
        }
        List<Msg> updated = new ArrayList<>(messages.size() + 1);
        boolean injected = false;
        for (Msg m : messages) {
            if (!injected && m != null && m.getRole() == MsgRole.SYSTEM) {
                updated.add(appendToSystem(m));
                injected = true;
            } else {
                updated.add(m);
            }
        }
        if (!injected) {
            updated.add(0, Msg.builder().role(MsgRole.SYSTEM).textContent(INSTRUCTION.trim()).build());
        }
        return next.apply(new ReasoningInput(List.copyOf(updated), input.tools(), input.options()));
    }

    private static boolean hasInstruction(List<Msg> messages) {
        for (Msg m : messages) {
            if (m == null || m.getRole() != MsgRole.SYSTEM || m.getContent() == null) {
                continue;
            }
            for (ContentBlock block : m.getContent()) {
                if (block instanceof TextBlock tb && tb.getText() != null && tb.getText().contains(MARKER)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Msg appendToSystem(Msg sys) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (sys.getContent() != null) {
            blocks.addAll(sys.getContent());
        }
        blocks.add(TextBlock.builder().text(INSTRUCTION).build());
        return Msg.builder()
            .id(sys.getId())
            .name(sys.getName())
            .role(MsgRole.SYSTEM)
            .content(blocks)
            .metadata(sys.getMetadata())
            .build();
    }
}
