package io.agentmanager.framework.service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentmanager.framework.service.PresetAnswerMiddleware.PresetAnswer;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetAnswerMiddlewareTest {

    private static final String ANSWER = "今天共有三条要闻：……";

    private static final class FailingModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            calls.incrementAndGet();
            return Flux.error(new IllegalStateException("model must not be called"));
        }

        @Override
        public String getModelName() {
            return "preset-answer-test-model";
        }
    }

    private static final class SessionModelRouting implements MiddlewareBase {
        private final Model model;

        SessionModelRouting(Model model) {
            this.model = model;
        }

        @Override
        public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                            Function<ModelCallInput, Flux<AgentEvent>> next) {
            return next.apply(new ModelCallInput(input.messages(), input.tools(), input.options(),
                model));
        }
    }

    private static ReActAgent agent(FailingModel sessionModel) {
        return ReActAgent.builder()
            .name("preset-answer-agent")
            .model(new FailingModel())
            .middleware(new SessionModelRouting(sessionModel))
            .middleware(new PresetAnswerMiddleware())
            .build();
    }

    private static RuntimeContext context(String sessionId, String presetAnswer) {
        var builder = RuntimeContext.builder().sessionId(sessionId).userId("alice");
        if (presetAnswer != null) {
            builder.put(PresetAnswer.class, new PresetAnswer(presetAnswer));
        }
        return builder.build();
    }

    private static Msg question() {
        return Msg.builder().role(MsgRole.USER).name("alice").textContent("今日头条有哪些？").build();
    }

    @Test
    void presetAnswerShouldBecomeTheAssistantMessageWithoutReachingTheSessionModel() {
        var sessionModel = new FailingModel();
        var agent = agent(sessionModel);
        var ctx = context("s-preset", ANSWER);

        var result = agent.call(List.of(question()), ctx).block(Duration.ofSeconds(10));

        assertNotNull(result);
        assertEquals(MsgRole.ASSISTANT, result.getRole());
        assertEquals(ANSWER, result.getTextContent());
        assertEquals(ANSWER, assertInstanceOf(TextBlock.class, result.getContent().get(0)).getText());
        assertFalse(result.getId() == null || result.getId().isBlank(),
            "归档以消息 id 为幂等键，预置答案必须带 id");
        assertEquals(0, sessionModel.calls.get(), "预置轮不得调用会话模型");
        var state = agent.getAgentState(ctx).getContext();
        assertEquals(List.of("USER", "ASSISTANT"), state.stream().map(m -> m.getRole().name()).toList(),
            "会话状态应为一问一答");
        assertEquals(result.getId(), state.get(1).getId());
    }

    @Test
    void presetAnswerShouldStreamThroughTheModelCallLifecycle() {
        var sessionModel = new FailingModel();
        var agent = agent(sessionModel);

        var events = agent.streamEvents(List.of(question()), context("s-preset-stream", ANSWER))
            .collectList().block(Duration.ofSeconds(10));

        assertNotNull(events);
        var types = events.stream().map(AgentEvent::getType).toList();
        assertEquals(AgentEventType.AGENT_START, types.get(0));
        assertEquals(AgentEventType.AGENT_END, types.get(types.size() - 1));
        for (var expected : List.of(AgentEventType.MODEL_CALL_START, AgentEventType.TEXT_BLOCK_START,
                AgentEventType.TEXT_BLOCK_DELTA, AgentEventType.TEXT_BLOCK_END,
                AgentEventType.MODEL_CALL_END)) {
            assertTrue(types.contains(expected), "事件流应含 " + expected + "：" + types);
        }
        var streamed = events.stream()
            .filter(TextBlockDeltaEvent.class::isInstance)
            .map(TextBlockDeltaEvent.class::cast)
            .map(TextBlockDeltaEvent::getDelta)
            .reduce("", String::concat);
        assertEquals(ANSWER, streamed);
        assertEquals(0, sessionModel.calls.get());
    }

    @Test
    void turnWithoutPresetAnswerShouldReachTheSessionModel() {
        var sessionModel = new FailingModel();
        var agent = agent(sessionModel);

        StepVerifier.create(agent.call(List.of(question()), context("s-normal", null)))
            .expectErrorSatisfies(e -> assertTrue(e.getMessage().contains("model must not be called")))
            .verify(Duration.ofSeconds(10));

        assertEquals(1, sessionModel.calls.get(), "普通轮次应照常到达会话模型");
    }

    @Test
    void onModelCallShouldHandTheSameInputAndStreamToTheCallerWithoutPresetAnswer() {
        var middleware = new PresetAnswerMiddleware();
        var input = new ModelCallInput(List.of(question()), List.of(), null, new FailingModel());
        var downstream = Flux.<AgentEvent>empty();
        var received = new AtomicInteger();

        var out = middleware.onModelCall(null, context("s-passthrough", null), input, in -> {
            received.incrementAndGet();
            assertSame(input, in);
            return downstream;
        });

        assertSame(downstream, out);
        assertEquals(1, received.get());
    }

    @Test
    void onModelCallShouldSwapOnlyTheModelWhenPresetAnswerIsPresent() {
        var middleware = new PresetAnswerMiddleware();
        var sessionModel = new FailingModel();
        var input = new ModelCallInput(List.of(question()), List.of(), null, sessionModel);
        var downstream = Flux.<AgentEvent>empty();

        var out = middleware.onModelCall(null, context("s-swap", ANSWER), input, in -> {
            assertInstanceOf(PresetAnswerMiddleware.PresetAnswerModel.class, in.model());
            assertSame(input.messages(), in.messages());
            assertSame(input.tools(), in.tools());
            assertSame(input.options(), in.options());
            return downstream;
        });

        assertSame(downstream, out);
        assertEquals(0, sessionModel.calls.get());
    }

    @Test
    void presetAnswerRejectsBlankText() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new PresetAnswerMiddleware.PresetAnswer(null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new PresetAnswerMiddleware.PresetAnswer("   "));
    }
}
