package io.agentmanager.framework.service;

import java.util.List;
import java.util.function.Function;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

public class PresetAnswerMiddleware implements MiddlewareBase {

    public static final String MODEL_NAME = "preset-answer";

    public record PresetAnswer(String text) {}

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        var preset = ctx == null ? null : ctx.get(PresetAnswer.class);
        if (preset == null) {
            return next.apply(input);
        }
        return next.apply(new ModelCallInput(input.messages(), input.tools(), input.options(),
            new PresetAnswerModel(preset.text())));
    }

    static final class PresetAnswerModel implements Model {

        private final String text;

        PresetAnswerModel(String text) {
            this.text = text;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            return Flux.just(ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .finishReason("stop")
                .build());
        }

        @Override
        public String getModelName() {
            return MODEL_NAME;
        }
    }
}
