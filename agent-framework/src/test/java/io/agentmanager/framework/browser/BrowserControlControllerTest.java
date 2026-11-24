package io.agentmanager.framework.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;

import io.agentmanager.framework.controller.ChatStreamController;
import io.agentmanager.framework.service.SessionUserStore;
import reactor.core.publisher.Flux;

class BrowserControlControllerTest {

    private static final String SESSION = "sess-1";
    private static final String USER = "u-1";

    private BrowserControlService control;
    private SessionUserStore sessionUserStore;
    private ChatStreamController chatStreamController;
    private BrowserControlController controller;

    @BeforeEach
    void setUp() {
        control = mock(BrowserControlService.class);
        sessionUserStore = mock(SessionUserStore.class);
        chatStreamController = mock(ChatStreamController.class);
        controller = new BrowserControlController(control, sessionUserStore, chatStreamController);
        when(sessionUserStore.findUserIdBySession(SESSION)).thenReturn(USER);
    }

    @Test
    void stateShouldReturnServiceViewForOwner() {
        when(control.state(USER, SESSION)).thenReturn(Map.of("control", Map.of("owner", "AGENT")));

        var response = controller.state(SESSION, USER);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("AGENT",
            ((Map<?, ?>) response.getBody().get("control")).get("owner"));
    }

    @Test
    void foreignUserShouldGetNotFound() {
        var e = assertThrows(ResponseStatusException.class,
            () -> controller.state(SESSION, "someone-else"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(control, never()).state(anyString(), anyString());
    }

    @Test
    void takeoverShouldReturnControlAndStopOutcome() {
        when(control.takeover(SESSION, USER, "need login", BrowserControlService.TRIGGER_USER))
            .thenReturn(new BrowserControlService.TakeoverResult(
                new BrowserControlService.ControlView("HUMAN", "need login", USER, "user", "t", 3600),
                "REQUESTED"));

        var response = controller.takeover(SESSION, USER, new BrowserControlController.TakeoverRequest("need login"));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("REQUESTED", response.getBody().get("stop_outcome"));
        var view = (BrowserControlService.ControlView) response.getBody().get("control");
        assertEquals("HUMAN", view.owner());
    }

    @Test
    void takeoverShouldReportStoreUnavailable() {
        when(control.takeover(anyString(), anyString(), any(), anyString()))
            .thenThrow(new BrowserControlUnavailableException("down", new IllegalStateException("down")));

        var response = controller.takeover(SESSION, USER, null);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("control_store_unavailable", response.getBody().get("error"));
    }

    @Test
    void releaseWithoutHumanControlShouldEmitErrorFrame() {
        when(control.release(SESSION)).thenReturn(false);

        var frames = controller.release(SESSION, USER, null, new HttpHeaders()).collectList().block();
        assertEquals(1, frames.size());
        assertTrue(frames.get(0).data().contains("not_taken_over"));
        verify(chatStreamController, never()).chat(any(), any(), any(), any());
    }

    @Test
    void releaseShouldResumeThroughChatPipeline() {
        when(control.release(SESSION)).thenReturn(true);
        var captured = new AtomicReference<ChatStreamController.ChatRequest>();
        when(chatStreamController.chat(any(), any(), any(), any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return Flux.just(ServerSentEvent.<String>builder().data("{\"type\":\"done\"}").build());
        });

        var frames = controller.release(SESSION, USER, "/agent/demo", new HttpHeaders()).collectList().block();

        assertEquals(1, frames.size());
        assertEquals("{\"type\":\"done\"}", frames.get(0).data());
        var request = captured.get();
        assertEquals(SESSION, request.sessionId());
        assertEquals(USER, request.userId());
        assertEquals(BrowserControlController.RESUME_MESSAGE, request.message());
    }

    @Test
    void releaseShouldFailClosedWhenStoreUnavailable() {
        when(control.release(SESSION))
            .thenThrow(new BrowserControlUnavailableException("down", new IllegalStateException("down")));

        var frames = controller.release(SESSION, USER, null, new HttpHeaders()).collectList().block();
        assertEquals(1, frames.size());
        assertTrue(frames.get(0).data().contains("control_store_unavailable"));
        verify(chatStreamController, never()).chat(any(), any(), any(), any());
    }

    @Test
    void releaseShouldPassForwardedPrefixIntoChatPipeline() {
        when(control.release(SESSION)).thenReturn(true);
        when(chatStreamController.chat(any(), any(), eq("/agent/demo"), any()))
            .thenReturn(Flux.empty());

        var frames = controller.release(SESSION, USER, "/agent/demo", new HttpHeaders())
            .collectList().block();
        assertEquals(List.of(), frames);
        verify(chatStreamController).chat(any(), eq(USER), eq("/agent/demo"), any());
    }
}
