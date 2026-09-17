package io.agentmanager.framework.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.agentmanager.framework.service.ConfirmContextStore;
import io.agentmanager.framework.service.LLMLogger;
import io.agentmanager.framework.service.SessionEventStore;
import io.agentmanager.framework.service.SessionUserStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "HITL_MYSQL_IT", matches = "1")
class ThreadHistoryConfirmIT {
    @Test
    void historyShouldRestorePendingByRawIdAndExcludeConsumedOrUnrelatedCalls() throws Exception {
        var dataSource = new MysqlDataSource();
        dataSource.setURL(System.getenv("CHECKPOINT_JDBC_URL"));
        dataSource.setUser(System.getenv("CHECKPOINT_USERNAME"));
        dataSource.setPassword(System.getenv("CHECKPOINT_PASSWORD"));
        var store = new ConfirmContextStore(dataSource);
        var controller = new ThreadController(dataSource, new LLMLogger(), store,
            mock(SessionUserStore.class), mock(SessionEventStore.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var sid = "hitl-it_" + UUID.randomUUID();
        var keys = List.of("test-tenant__" + sid, "test-tenant:" + sid, sid);
        var calls = List.of(Map.<String, Object>of("id", "call-history-it", "name", "unpublish_service",
            "input", Map.of("serviceId", 99999)));
        try {
            for (var key : keys) {
                store.put(key, calls, "reply-history-it", null, null);
                mvc.perform(get("/threads/{sid}/history", sid))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pendingConfirm.tools[0].tool_call_id").value("call-history-it"))
                    .andExpect(jsonPath("$.pendingConfirm.tools[0].input.serviceId").value(99999));
                mvc.perform(get("/threads/{sid}/history", sid.replace("_", "%")))
                    .andExpect(jsonPath("$.pendingConfirm").doesNotExist());
                store.consume(key);
                mvc.perform(get("/threads/{sid}/history", sid))
                    .andExpect(jsonPath("$.pendingConfirm").doesNotExist());
                store.delete(key);
            }
        } finally {
            keys.forEach(store::delete);
        }
    }
}
