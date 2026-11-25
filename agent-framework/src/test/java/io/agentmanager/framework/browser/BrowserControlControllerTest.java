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
    private BrowserViewTicketStore ticketStore;
    private BrowserControlController controller;

    @BeforeEach
    void setUp() {
        control = mock(BrowserControlService.class);
        sessionUserStore = mock(SessionUserStore.class);
        chatStreamController = mock(ChatStreamController.class);
        ticketStore = mock(BrowserViewTicketStore.class);
        controller = new BrowserControlController(control, sessionUserStore, chatStreamController, ticketStore);
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
    void unclaimedSessionShouldGetNotFound() {
        when(sessionUserStore.findUserIdBySession("unclaimed")).thenReturn(null);

        var e = assertThrows(ResponseStatusException.class,
            () -> controller.state("unclaimed", "someone-else"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(control, never()).state(anyString(), anyString());
    }

    @Test
    void placeholderSessionShouldGetNotFound() {
        when(sessionUserStore.findUserIdBySession("placeholder"))
            .thenReturn(SessionUserStore.PLACEHOLDER_USER);

        var e = assertThrows(ResponseStatusException.class,
            () -> controller.state("placeholder", "someone-else"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(control, never()).state(anyString(), anyString());
    }

    @Test
    void unclaimedSessionShouldNotBeTakeoverAble() {
        when(sessionUserStore.findUserIdBySession("unclaimed")).thenReturn(null);

        var e = assertThrows(ResponseStatusException.class,
            () -> controller.takeover("unclaimed", "someone-else", null));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(control, never()).takeover(anyString(), anyString(), any(), anyString());
    }

    @Test
    void unclaimedSessionShouldNotBeReleasable() {
        when(sessionUserStore.findUserIdBySession("unclaimed")).thenReturn(null);

        var e = assertThrows(ResponseStatusException.class,
            () -> controller.release("unclaimed", "someone-else", null, new HttpHeaders()));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(control, never()).release(anyString());
        verify(chatStreamController, never()).chat(any(), any(), any(), any());
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
    void vncTicketShouldBeIssuedOnlyToSessionOwner() {
        when(ticketStore.issue(SESSION, USER)).thenReturn("ticket-1");
        when(ticketStore.ttlSeconds()).thenReturn(60L);

        var response = controller.vncTicket(SESSION, USER);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ticket-1", response.getBody().get("ticket"));
        assertEquals(60L, response.getBody().get("expires_in_seconds"));
    }

    @Test
    void vncTicketShouldBeNotFoundForForeignUser() {
        var e = assertThrows(ResponseStatusException.class,
            () -> controller.vncTicket(SESSION, "someone-else"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(ticketStore, never()).issue(anyString(), anyString());
    }

    @Test
    void vncTicketShouldReportStoreUnavailable() {
        when(ticketStore.issue(SESSION, USER))
            .thenThrow(new BrowserControlUnavailableException("down", new IllegalStateException("down")));

        var response = controller.vncTicket(SESSION, USER);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("view_ticket_unavailable", response.getBody().get("error"));
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

    @Test
    void releaseErrorFramesMustBeValidJson() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        when(control.release(SESSION)).thenReturn(false);
        var notHeld = controller.release(SESSION, USER, null, new HttpHeaders()).collectList().block();
        assertEquals(1, notHeld.size());
        var tree1 = mapper.readTree(notHeld.get(0).data());
        assertEquals("error", tree1.get("type").asText());
        assertTrue(tree1.get("error").asText().contains("not_taken_over"));

        when(control.release(SESSION))
            .thenThrow(new BrowserControlUnavailableException("redis down", new IllegalStateException("down")));
        var unavailable = controller.release(SESSION, USER, null, new HttpHeaders()).collectList().block();
        assertEquals(1, unavailable.size());
        var tree2 = mapper.readTree(unavailable.get(0).data());
        assertEquals("error", tree2.get("type").asText());
        assertTrue(tree2.get("error").asText().contains("control_store_unavailable"));
    }

    @Test
    void releaseShouldRestoreControlLockWhenChatThrowsOrReturnsTurnInProgress() {
        var prev = new BrowserControlService.ControlView("HUMAN", "login", USER, "user", "2026-11-25T00:00:00Z", 600);
        when(control.control(SESSION)).thenReturn(prev);
        when(control.release(SESSION)).thenReturn(true);
        when(chatStreamController.chat(any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("quota full"));

        assertThrows(IllegalStateException.class,
            () -> controller.release(SESSION, USER, null, new HttpHeaders()));
        verify(control).restore(SESSION, prev);

        org.mockito.Mockito.reset(control, chatStreamController);
        when(control.control(SESSION)).thenReturn(prev);
        when(control.release(SESSION)).thenReturn(true);
        when(chatStreamController.chat(any(), any(), any(), any()))
            .thenReturn(Flux.just(ServerSentEvent.<String>builder()
                .event("error").data("{\"type\":\"error\",\"error\":\"turn_in_progress\"}").build()));

        controller.release(SESSION, USER, null, new HttpHeaders()).collectList().block();
        verify(control).restore(SESSION, prev);
    }

    @Test
    void releaseMappingShouldNotPinProducesTextEventStream() throws Exception {
        var method = BrowserControlController.class.getMethod("release",
            String.class, String.class, String.class, HttpHeaders.class);
        var mapping = method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
        assertEquals(0, mapping.produces().length);
    }

    @Test
    void releaseShouldRestoreControlLockAfterWaitingFrameAndNullEventErrorSse() {
        var prev = new BrowserControlService.ControlView("HUMAN", "login", USER, "user", "2026-11-25T00:00:00Z", 600);
        when(control.control(SESSION)).thenReturn(prev);
        when(control.release(SESSION)).thenReturn(true);
        when(chatStreamController.chat(any(), any(), any(), any()))
            .thenReturn(Flux.just(
                ServerSentEvent.<String>builder().data("{\"type\":\"waiting\",\"session_id\":\"" + SESSION + "\"}").build(),
                ServerSentEvent.<String>builder().data("{\"type\":\"error\",\"error\":\"turn_in_progress\"}").build()));

        controller.release(SESSION, USER, null, new HttpHeaders()).collectList().block();
        verify(control).restore(SESSION, prev);

        org.mockito.Mockito.reset(control, chatStreamController);
        when(control.control(SESSION)).thenReturn(prev);
        when(control.release(SESSION)).thenReturn(true);
        when(chatStreamController.chat(any(), any(), any(), any()))
            .thenReturn(Flux.just(
                ServerSentEvent.<String>builder().data("{\"type\":\"error\",\"error\":\"turn_pending_confirm\"}").build()));

        controller.release(SESSION, USER, null, new HttpHeaders()).collectList().block();
        verify(control).restore(SESSION, prev);
    }

    @Test
    void release404And409ShouldNegotiateJsonEvenWithAcceptEventStream() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(new io.agentmanager.framework.controller.GlobalExceptionHandler())
            .build();
        when(sessionUserStore.findUserIdBySession("missing-sid")).thenReturn(null);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/threads/missing-sid/browser/release")
                .header("X-User-Id", USER)
                .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON));

        when(control.control(SESSION)).thenReturn(
            new BrowserControlService.ControlView("HUMAN", "login", USER, "user", "2026-11-25T00:00:00Z", 600));
        when(control.release(SESSION)).thenReturn(true);
        when(chatStreamController.chat(any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("conflict"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/threads/" + SESSION + "/browser/release")
                .header("X-User-Id", USER)
                .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON));
    }
}
