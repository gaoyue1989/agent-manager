package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.middleware.ReasoningInput;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;

class Issue829To840VerificationTest {

    @Test
    void skillZipSupportRejectsShellMetaInCanonicalName830(@TempDir Path tempDir) throws Exception {
        assertFalse(SkillZipSupport.isShellSafeSegment("Q&A"));
        assertFalse(SkillZipSupport.isShellSafeSegment("O'Reilly"));
        assertFalse(SkillZipSupport.isShellSafeSegment("R&D助手"));
        assertFalse(SkillZipSupport.isShellSafeSegment("foo\u00A0bar"));

        Path qaDir = tempDir.resolve("Q_A");
        Files.createDirectories(qaDir);
        Files.writeString(qaDir.resolve("SKILL.md"), "---\nname: Q&A\ndescription: test\n---\n# QA\n");
        assertEquals("Q_A", SkillZipSupport.canonicalSkillName(qaDir));
        assertTrue(SkillZipSupport.isShellSafeSegment(SkillZipSupport.canonicalSkillName(qaDir)));
    }

    @Test
    void onReasoningRestoresSuppressedDirectReturnInReasoningInputMessages832() {
        var ctx = RuntimeContext.builder().sessionId("s-832").userId("u-1").build();
        ctx.put(ActingDenials.SUPPRESSED_DIRECT_RETURN_IDS_KEY, Set.of("tc-direct-1"));
        var errBlock = ToolResultBlock.of("tc-direct-1", "render_chart", TextBlock.builder().text("ok").build())
            .withState(ToolResultState.ERROR);
        var msg = Msg.builder().role(MsgRole.TOOL).content(List.of(errBlock)).build();
        var input = new ReasoningInput(List.of(msg), List.of(), null);

        var mw = new ToolCallObservabilityMiddleware(new SimpleMeterRegistry(), null, null, "test/agent", Set::of);
        var captured = new AtomicReference<ReasoningInput>();
        mw.onReasoning(mock(Agent.class), ctx, input, nextInput -> {
            captured.set(nextInput);
            return Flux.<AgentEvent>empty();
        }).blockLast();

        var passedMsg = captured.get().messages().get(0);
        var passedBlock = (ToolResultBlock) passedMsg.getContent().get(0);
        assertEquals(ToolResultState.SUCCESS, passedBlock.getState());
    }

    @Test
    void sessionKeyResolverStripsSlashNormalizedTenantPrefix833() {
        var resolver = new SessionKeyResolver(null, "internal/fulfillment-lead");
        var ctx = RuntimeContext.builder().sessionId("internal-fulfillment-lead__webui-lead1").userId("u-1").build();
        assertEquals("webui-lead1", resolver.canonicalSessionId(ctx));
    }
}
