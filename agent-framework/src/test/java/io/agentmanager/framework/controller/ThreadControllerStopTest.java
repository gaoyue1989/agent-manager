package io.agentmanager.framework.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.agentmanager.framework.config.TurnStopSettings;
import io.agentmanager.framework.service.AgentStateReader;
import io.agentmanager.framework.service.ConfirmContextStore;
import io.agentmanager.framework.service.LLMLogger;
import io.agentmanager.framework.service.ModelCatalog;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.service.TurnStopService;

/**
 * POST /threads/{sid}/stop 的响应契约与 fail-closed 分流。
 */
class ThreadControllerStopTest {

    private SessionUserStore sessionUserStore;
    private ThreadController controller;
    private TurnStopService stopService;
    private TurnStopSettings settings;

    @BeforeEach
    void setUp() {
        sessionUserStore = mock(SessionUserStore.class);
        stopService = mock(TurnStopService.class);
        settings = new TurnStopSettings(true, 1000, 15000);
        controller = new ThreadController(
            mock(DataSource.class), new LLMLogger(), mock(ConfirmContextStore.class),
            sessionUserStore, mock(SessionEventStore.class), mock(ModelCatalog.class));
        ReflectionTestUtils.setField(controller, "turnStopService", stopService);
        ReflectionTestUtils.setField(controller, "turnStopSettings", settings);
    }

    @Test
    void requestedShouldReturn202WithReplyId() {
        when(stopService.request("s1", "u1"))
            .thenReturn(TurnStopService.Result.of(TurnStopService.Outcome.REQUESTED, "rid-1"));

        var resp = controller.stopThread("s1", "u1");

        assertEquals(202, resp.getStatusCode().value());
        assertEquals("rid-1", resp.getBody().get("reply_id"));
        assertEquals("stopping", resp.getBody().get("state"));
    }

    @Test
    void nothingToStopShouldReturn404() {
        when(stopService.request("s1", "u1"))
            .thenReturn(TurnStopService.Result.of(TurnStopService.Outcome.NOTHING_TO_STOP, null));

        var resp = controller.stopThread("s1", "u1");

        assertEquals(404, resp.getStatusCode().value());
        assertEquals("nothing_to_stop", resp.getBody().get("error"));
    }

    @Test
    void waitingConfirmShouldReturn409WithHint() {
        when(stopService.request("s1", "u1"))
            .thenReturn(TurnStopService.Result.of(TurnStopService.Outcome.WAITING_CONFIRM, null));

        var resp = controller.stopThread("s1", "u1");

        assertEquals(409, resp.getStatusCode().value());
        assertEquals("turn_waiting_confirm", resp.getBody().get("error"));
    }

    @Test
    void turnNotReadyShouldBeRetryable() {
        when(stopService.request("s1", "u1"))
            .thenReturn(TurnStopService.Result.of(TurnStopService.Outcome.TURN_NOT_READY, null));

        var resp = controller.stopThread("s1", "u1");

        assertEquals(409, resp.getStatusCode().value());
        assertEquals("turn_not_ready", resp.getBody().get("error"));
        assertEquals(true, resp.getBody().get("retryable"));
    }

    @Test
    void unavailableShouldReturn503() {
        when(stopService.request("s1", "u1"))
            .thenReturn(TurnStopService.Result.of(TurnStopService.Outcome.UNAVAILABLE, null));

        var resp = controller.stopThread("s1", "u1");

        assertEquals(503, resp.getStatusCode().value());
        assertEquals("turn_stop_unavailable", resp.getBody().get("error"));
    }

    @Test
    void disabledDeploymentShouldReturn503() {
        ReflectionTestUtils.setField(controller, "turnStopSettings",
            new TurnStopSettings(false, 1000, 15000));

        var resp = controller.stopThread("s1", "u1");

        assertEquals(503, resp.getStatusCode().value());
        assertEquals("turn_stop_unavailable", resp.getBody().get("error"));
    }

    @Test
    void missingServiceShouldReturn503() {
        ReflectionTestUtils.setField(controller, "turnStopService", null);

        var resp = controller.stopThread("s1", "u1");

        assertEquals(503, resp.getStatusCode().value());
    }

    @Test
    void foreignSessionShouldBe404() {
        when(sessionUserStore.findUserIdBySession("s1")).thenReturn("owner");

        var resp = controller.stopThread("s1", "intruder");

        assertEquals(404, resp.getStatusCode().value());
        assertTrue(resp.getBody().get("message").toString().contains("not found"));
    }
}
