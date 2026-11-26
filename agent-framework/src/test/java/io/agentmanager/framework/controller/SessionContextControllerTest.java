package io.agentmanager.framework.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.agentmanager.framework.service.SessionAccessGuard;
import io.agentmanager.framework.service.SessionContextStore;
import io.agentmanager.framework.service.SessionContextStore.Entry;
import io.agentmanager.framework.service.SessionContextStore.RemoveOutcome;
import io.agentmanager.framework.service.SessionContextStore.UpsertOutcome;
import io.agentmanager.framework.service.SessionUserStore;

class SessionContextControllerTest {

    private static final String KEY_RULE = "key must match [A-Za-z0-9._-]{1,64}";

    private final SessionContextStore store = mock(SessionContextStore.class);
    private final SessionUserStore sessionUserStore = mock(SessionUserStore.class);
    private MockMvc mockMvc;
    private MockMvc realStoreMvc;

    @BeforeEach
    void setUp() {
        mockMvc = standalone(store);
        realStoreMvc = standalone(new SessionContextStore(mock(DataSource.class), 30_000));
        when(store.maxTotalChars()).thenReturn(30_000);
    }

    private MockMvc standalone(SessionContextStore sessionContextStore) {
        return MockMvcBuilders.standaloneSetup(new SessionContextController(sessionContextStore,
                new SessionAccessGuard(sessionUserStore)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private static String body(String key, String title, String content) {
        return "{\"key\":\"" + key + "\",\"title\":\"" + title + "\",\"content\":\"" + content + "\"}";
    }

    private static Entry entry() {
        var created = LocalDateTime.of(2026, 11, 25, 9, 0);
        var updated = LocalDateTime.of(2026, 11, 25, 11, 0);
        return new Entry("headline", "今日头条", "头条正文", 4, created, updated);
    }

    @Test
    void injectCreatesTheEntryForTheRequester() throws Exception {
        when(store.upsert("s-1", "u-9", "headline", "今日头条", "abc")).thenReturn(
            new UpsertOutcome.Accepted("headline", "今日头条", 3, 3, 30_000, true));

        mockMvc.perform(post("/threads/s-1/context").header("X-User-Id", "u-9")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("headline", "今日头条", "abc")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sessionId").value("s-1"))
            .andExpect(jsonPath("$.key").value("headline"))
            .andExpect(jsonPath("$.title").value("今日头条"))
            .andExpect(jsonPath("$.contentChars").value(3))
            .andExpect(jsonPath("$.totalChars").value(3))
            .andExpect(jsonPath("$.maxTotalChars").value(30_000))
            .andExpect(jsonPath("$.created").value(true))
            .andExpect(jsonPath("$.effectiveFrom").value("next_turn"));
    }

    @Test
    void injectAnswersOkWhenTheKeyAlreadyExists() throws Exception {
        when(store.upsert(eq("s-1"), eq("u-9"), eq("headline"), any(), eq("abc")))
            .thenReturn(new UpsertOutcome.Accepted("headline", "今日头条", 3, 3, 30_000, false));

        mockMvc.perform(post("/threads/s-1/context").header("X-User-Id", "u-9")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("headline", "今日头条", "abc")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(false));
    }

    @Test
    void injectWithoutTheIdentityHeaderActsAsTheAnonymousRequester() throws Exception {
        when(store.upsert(eq("s-1"), eq("debug-user"), eq("k"), isNull(), eq("a")))
            .thenReturn(new UpsertOutcome.Accepted("k", "k", 1, 1, 30_000, true));

        mockMvc.perform(post("/threads/s-1/context")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"k\",\"content\":\"a\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.title").value("k"));
    }

    @Test
    void injectNormalizesTheRequesterTheSameWayChatDoes() throws Exception {
        when(store.upsert(eq("s-1"), eq("u_9"), eq("k"), isNull(), eq("a")))
            .thenReturn(new UpsertOutcome.Accepted("k", "k", 1, 1, 30_000, true));
        when(store.upsert(eq("s-1"), eq("u-" + "x".repeat(62)), eq("k"), isNull(), eq("a")))
            .thenReturn(new UpsertOutcome.Accepted("k", "k", 1, 1, 30_000, true));

        mockMvc.perform(post("/threads/s-1/context").header("X-User-Id", "u:9")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"k\",\"content\":\"a\"}"))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/threads/s-1/context").header("X-User-Id", "u-" + "x".repeat(80))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"k\",\"content\":\"a\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    void injectNeverActsAsThePlaceholderIdentity() throws Exception {
        when(store.upsert(eq("s-1"), eq("debug-user"), eq("k"), isNull(), eq("a")))
            .thenReturn(new UpsertOutcome.Accepted("k", "k", 1, 1, 30_000, true));

        mockMvc.perform(post("/threads/s-1/context").header("X-User-Id", "***")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"k\",\"content\":\"a\"}"))
            .andExpect(status().isCreated());

        verify(store, never()).upsert(anyString(), eq("unknown"), anyString(), any(), anyString());
    }

    @Test
    void injectSanitizesTheSessionIdLikeEveryOtherThreadEndpoint() throws Exception {
        when(store.upsert(eq("s_1"), eq("u-9"), eq("k"), isNull(), eq("a")))
            .thenReturn(new UpsertOutcome.Accepted("k", "k", 1, 1, 30_000, true));

        mockMvc.perform(post("/threads/s:1/context").header("X-User-Id", "u-9")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"k\",\"content\":\"a\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sessionId").value("s_1"));
    }

    @Test
    void injectHidesAForeignSession() throws Exception {
        when(store.upsert(any(), any(), any(), any(), any())).thenReturn(new UpsertOutcome.NotOwned());

        mockMvc.perform(post("/threads/s-1/context").header("X-User-Id", "u-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("headline", "t", "abc")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("thread_not_found"));
    }

    @Test
    void injectReportsAnExhaustedBudgetWith413() throws Exception {
        when(store.upsert(any(), any(), any(), any(), any()))
            .thenReturn(new UpsertOutcome.BudgetExceeded(5_000, 31_000, 30_000));

        mockMvc.perform(post("/threads/s-1/context").header("X-User-Id", "u-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("headline", "t", "abc")))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.error").value("context_budget_exceeded"))
            .andExpect(jsonPath("$.contentChars").value(5_000))
            .andExpect(jsonPath("$.totalChars").value(31_000))
            .andExpect(jsonPath("$.maxTotalChars").value(30_000));
    }

    @Test
    void injectTurnsInvalidInputInto400ThroughTheRealStore() throws Exception {
        realStoreMvc.perform(post("/threads/s-1/context").header("X-User-Id", "u-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("bad key!", "t", "abc")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("bad_request"))
            .andExpect(jsonPath("$.message").value(KEY_RULE));

        realStoreMvc.perform(post("/threads/s-1/context").header("X-User-Id", "u-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"k\",\"title\":\"第一行\\n\\n## Subagents\",\"content\":\"a\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("title must be a single line"));

        realStoreMvc.perform(post("/threads/s-1/context").header("X-User-Id", "u-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"k\",\"content\":\"   \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void injectSurfacesStorageUnavailableAs503() throws Exception {
        when(store.upsert(any(), any(), any(), any(), any()))
            .thenThrow(new SessionContextStore.StorageException("down", null));

        mockMvc.perform(post("/threads/s-1/context").header("X-User-Id", "u-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("headline", "t", "abc")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("storage_unavailable"));
    }

    @Test
    void listReturnsEntriesWithoutContentByDefault() throws Exception {
        when(store.ownerOf("s-1")).thenReturn("u-1");
        when(store.findBySession("s-1")).thenReturn(List.of(entry()));

        mockMvc.perform(get("/threads/s-1/context").header("X-User-Id", "u-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value("s-1"))
            .andExpect(jsonPath("$.totalChars").value(4))
            .andExpect(jsonPath("$.maxTotalChars").value(30_000))
            .andExpect(jsonPath("$.entries.length()").value(1))
            .andExpect(jsonPath("$.entries[0].key").value("headline"))
            .andExpect(jsonPath("$.entries[0].title").value("今日头条"))
            .andExpect(jsonPath("$.entries[0].contentChars").value(4))
            .andExpect(jsonPath("$.entries[0].createdAt").value("2026-11-25T09:00"))
            .andExpect(jsonPath("$.entries[0].updatedAt").value("2026-11-25T11:00"))
            .andExpect(jsonPath("$.entries[0].content").doesNotExist());
    }

    @Test
    void listIncludesContentOnlyWhenAsked() throws Exception {
        when(store.findBySession("s-1")).thenReturn(List.of(entry()));

        mockMvc.perform(get("/threads/s-1/context?includeContent=true").header("X-User-Id", "u-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[0].content").value("头条正文"));
    }

    @Test
    void listHidesAForeignSession() throws Exception {
        when(store.ownerOf("s-1")).thenReturn("u-2");

        mockMvc.perform(get("/threads/s-1/context").header("X-User-Id", "u-1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("thread_not_found"));

        verify(store, never()).findBySession(any());
    }

    @Test
    void listFailsClosedWhenOwnershipCannotBeRead() throws Exception {
        when(store.ownerOf("s-1")).thenThrow(new SessionContextStore.StorageException("down", null));

        mockMvc.perform(get("/threads/s-1/context?includeContent=true").header("X-User-Id", "u-1"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("storage_unavailable"));

        verify(store, never()).findBySession(any());
    }

    @Test
    void removeDeletesTheNamedEntryForItsOwner() throws Exception {
        when(store.remove("s-1", "u-1", "headline")).thenReturn(new RemoveOutcome.Deleted("headline"));

        mockMvc.perform(delete("/threads/s-1/context/headline").header("X-User-Id", "u-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value("s-1"))
            .andExpect(jsonPath("$.key").value("headline"))
            .andExpect(jsonPath("$.deleted").value(true));
    }

    @Test
    void removeReportsAnUnknownKeyAs404() throws Exception {
        when(store.remove("s-1", "u-1", "missing")).thenReturn(new RemoveOutcome.NotFound("missing"));

        mockMvc.perform(delete("/threads/s-1/context/missing").header("X-User-Id", "u-1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("context_not_found"));
    }

    @Test
    void removeHidesAForeignSession() throws Exception {
        when(store.remove("s-1", "u-1", "headline")).thenReturn(new RemoveOutcome.NotOwned());

        mockMvc.perform(delete("/threads/s-1/context/headline").header("X-User-Id", "u-1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("thread_not_found"));
    }

    @Test
    void removeNormalizesTheRequesterBeforeDecidingOwnership() throws Exception {
        when(store.remove("s-1", "u_1", "headline")).thenReturn(new RemoveOutcome.Deleted("headline"));

        mockMvc.perform(delete("/threads/s-1/context/headline").header("X-User-Id", "u:1"))
            .andExpect(status().isOk());

        verify(store, never()).remove(anyString(), eq("u:1"), anyString());
    }

    @Test
    void listShouldRejectSessionIdMatchingExistingUserIdWith404() throws Exception {
        when(store.existsAsUserId("alice")).thenReturn(true);

        mockMvc.perform(get("/threads/alice/context").header("X-User-Id", "mallory"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("thread_not_found"));

        verify(store, never()).findBySession(any());
    }

    @Test
    void listShouldAllowSessionIdMatchingRequesterOwnUserId() throws Exception {
        when(store.existsAsUserId("alice")).thenReturn(true);
        when(store.ownerOf("alice")).thenReturn("alice");
        when(store.findBySession("alice")).thenReturn(List.of(entry()));

        mockMvc.perform(get("/threads/alice/context").header("X-User-Id", "alice"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value("alice"));
    }

    @Test
    void listShouldRejectReservedNamespacesWith404_632() throws Exception {
        for (String sid : List.of("u-1001", "U-1001", "debug-user", "gw-0123456789ab")) {
            mockMvc.perform(get("/threads/" + sid + "/context").header("X-User-Id", "u-2002"))
                .andExpect(status().isNotFound());
        }
    }

    @Test
    void injectRejectsEntryLimitExceededWith413() throws Exception {
        when(store.upsert("s-1", "u-1", "k51", "t51", "body"))
            .thenReturn(new UpsertOutcome.EntryLimitExceeded(51, 50));

        mockMvc.perform(post("/threads/s-1/context")
                .header("X-User-Id", "u-1")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"key\":\"k51\",\"title\":\"t51\",\"content\":\"body\"}"))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.error").value("context_entry_limit_exceeded"))
            .andExpect(jsonPath("$.count").value(51))
            .andExpect(jsonPath("$.maxEntries").value(50));
    }
}
