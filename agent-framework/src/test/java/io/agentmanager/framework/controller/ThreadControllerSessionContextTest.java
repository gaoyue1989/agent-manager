package io.agentmanager.framework.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.agentmanager.framework.service.AgentStateReader;
import io.agentmanager.framework.service.ConfirmContextStore;
import io.agentmanager.framework.service.LLMLogger;
import io.agentmanager.framework.service.ModelCatalog;
import io.agentmanager.framework.service.SessionContextStore;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionMessageStore;
import io.agentmanager.framework.service.SessionUserStore;

class ThreadControllerSessionContextTest {

    private final SessionContextStore sessionContextStore = mock(SessionContextStore.class);
    private ThreadController controller;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new RuntimeException("skip db paths"));
        var confirmContextStore = mock(ConfirmContextStore.class);
        when(confirmContextStore.findHeadPending(anyString())).thenReturn(Optional.empty());
        controller = new ThreadController(dataSource, new LLMLogger(), confirmContextStore,
            mock(SessionUserStore.class), mock(SessionEventStore.class), mock(AgentStateReader.class),
            mock(ModelCatalog.class), 8000, true, mock(SessionMessageStore.class));
        ReflectionTestUtils.setField(controller, "sessionContextStore", sessionContextStore);
    }

    private static SessionContextStore.Entry entry() {
        var created = LocalDateTime.of(2026, 11, 25, 9, 0);
        var updated = LocalDateTime.of(2026, 11, 25, 11, 0);
        return new SessionContextStore.Entry("headline", "今日头条", "头条正文", 4, created, updated);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> contextOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("context");
    }

    @Test
    void historyCarriesTheSessionContextBesideMessages() {
        when(sessionContextStore.findBySession("s-1")).thenReturn(List.of(entry()));

        var body = controller.threadHistory("s-1", "u-1", true, 200, null).getBody();

        var context = contextOf(body);
        assertEquals(1, context.size());
        var item = context.get(0);
        assertEquals("headline", item.get("key"));
        assertEquals("今日头条", item.get("title"));
        assertEquals("头条正文", item.get("content"));
        assertEquals(4, item.get("content_chars"));
        assertEquals("2026-11-25T09:00", item.get("created_at"));
        assertEquals("2026-11-25T11:00", item.get("updated_at"));
        assertTrue(body.get("messages") instanceof List);
        assertEquals(false, ((List<?>) body.get("messages")).contains(item));
    }

    @Test
    void historyReportsAnEmptyContextForSessionsWithoutOne() {
        when(sessionContextStore.findBySession("s-1")).thenReturn(List.of());

        var body = controller.threadHistory("s-1", "u-1", true, 200, null).getBody();

        assertTrue(contextOf(body).isEmpty());
    }

    @Test
    void historyKeepsServingMessagesWhenTheContextStoreFails() {
        when(sessionContextStore.findBySession("s-1"))
            .thenThrow(new SessionContextStore.StorageException("down", null));

        var response = controller.threadHistory("s-1", "u-1", true, 200, null);

        assertEquals(200, response.getStatusCode().value());
        assertFalse(response.getBody().containsKey("context"));
        assertTrue(response.getBody().get("messages") instanceof List);
    }

    @Test
    void threadDetailCarriesTheContextToo() {
        when(sessionContextStore.findBySession("s-1")).thenReturn(List.of(entry()));

        var body = controller.getThread("s-1", "u-1").getBody();

        assertEquals("headline", contextOf(body).get(0).get("key"));
    }

    @Test
    void deleteThreadRemovesTheSessionContextWithTheSession() {
        var response = controller.deleteThread("s-1", "u-1");

        assertEquals(200, response.getStatusCode().value());
        verify(sessionContextStore).deleteBySession("s-1");
    }
}
