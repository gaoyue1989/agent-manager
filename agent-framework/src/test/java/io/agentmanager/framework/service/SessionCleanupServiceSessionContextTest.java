package io.agentmanager.framework.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.service.storage.FileStorage;

class SessionCleanupServiceSessionContextTest {

    @Test
    void cleanupRemovesOrphanedContextOnlyAfterExpiredSessionsAreGone() {
        var sessionUserStore = mock(SessionUserStore.class);
        var sessionContextStore = mock(SessionContextStore.class);
        var service = new SessionCleanupService(mock(DataSource.class), mock(SessionManager.class),
            mock(TurnLeaseStore.class), mock(ConfirmContextStore.class), mock(ToolAuditStore.class),
            sessionUserStore, new A2aAgentRefHolder(null), mock(AgentManagerProperties.class),
            mock(FileStorage.class), mock(RemoteTaskRegistryStore.class), mock(TurnIntentStore.class));
        ReflectionTestUtils.setField(service, "sessionContextStore", sessionContextStore);

        service.cleanup();

        InOrder order = inOrder(sessionUserStore, sessionContextStore);
        order.verify(sessionUserStore).deleteBefore(any());
        order.verify(sessionContextStore).deleteOrphans();
    }
}
