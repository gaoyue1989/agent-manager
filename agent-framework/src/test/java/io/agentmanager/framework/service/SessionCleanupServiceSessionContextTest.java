package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.sandbox.opensandbox.SandboxBindingStore;
import io.agentmanager.framework.service.storage.FileStorage;

class SessionCleanupServiceSessionContextTest {

    private final SessionUserStore sessionUserStore = mock(SessionUserStore.class);
    private final SessionContextStore sessionContextStore = mock(SessionContextStore.class);
    private final SandboxBindingStore sandboxBindingStore = mock(SandboxBindingStore.class);

    private SessionCleanupService service() {
        var service = new SessionCleanupService(mock(DataSource.class), mock(SessionManager.class),
            mock(TurnLeaseStore.class), mock(ConfirmContextStore.class), mock(ToolAuditStore.class),
            sessionUserStore, new A2aAgentRefHolder(null), mock(AgentManagerProperties.class),
            mock(FileStorage.class), mock(RemoteTaskRegistryStore.class), mock(TurnIntentStore.class));
        ReflectionTestUtils.setField(service, "sessionContextStore", sessionContextStore);
        ReflectionTestUtils.setField(service, "sandboxBindingStore", sandboxBindingStore);
        return service;
    }

    @Test
    void cleanupRemovesOrphanedContextOnlyAfterExpiredSessionsAreGone() {
        service().cleanup();

        InOrder order = inOrder(sessionUserStore, sessionContextStore);
        order.verify(sessionUserStore).deleteBefore(any());
        order.verify(sessionContextStore).deleteOrphans();
    }

    @Test
    void aFailingOrphanSweepDoesNotAbortTheRemainingCleanupSteps() {
        when(sessionContextStore.deleteOrphans())
            .thenThrow(new SessionContextStore.StorageException("down", null));

        assertDoesNotThrow(() -> service().cleanup());

        InOrder order = inOrder(sessionContextStore, sandboxBindingStore);
        order.verify(sessionContextStore).deleteOrphans();
        order.verify(sandboxBindingStore).cleanupExpired(any());
    }
}
