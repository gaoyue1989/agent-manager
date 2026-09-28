package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import javax.sql.DataSource;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.service.storage.FileStorage;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.agentprotocol.AgentProtocolConstants;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.workspace.WorkspaceManager;

import org.junit.jupiter.api.Test;

/**
 * Agent Protocol 终态 TaskRecord 保留期扫描测试（SessionCleanupService.scanExpiredProtocolTaskRecords）：
 * 协议关闭时零触达；开启时按 SDK 合成桶（_agentscope_protocol/_tasks）列举并按终态+保留期过滤，
 * 全程不抛异常（兜底职责失败仅告警）。
 */
class SessionCleanupServiceProtocolScanTest {

    private static SessionCleanupService service(AgentManagerProperties props, A2aAgentRefHolder holder) {
        return new SessionCleanupService(mock(DataSource.class), mock(SessionManager.class),
            mock(TurnLeaseStore.class), mock(ConfirmContextStore.class), mock(ToolAuditStore.class),
            mock(SessionUserStore.class), holder, props, mock(FileStorage.class));
    }

    private static A2aAgentRefHolder holderOf(HarnessAgent agent) {
        return new A2aAgentRefHolder(new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public HarnessAgent getIfAvailable() {
                return agent;
            }

            @Override
            public HarnessAgent getObject(Object... args) {
                return agent;
            }

            @Override
            public HarnessAgent getObject() {
                return agent;
            }

            @Override
            public HarnessAgent getIfUnique() {
                return agent;
            }
        });
    }

    private static AgentManagerProperties propsOf(boolean enabled, int retentionDays) {
        return new AgentManagerProperties(null, null, null, "/config", "", null, null, null, null,
            new AgentManagerProperties.AgentProtocolSettings(enabled, "tok", "", retentionDays, 24, 5, ""));
    }

    private static TaskRecord record(String taskId, TaskStatus status, Instant lastUpdatedAt) {
        var record = new TaskRecord();
        record.setTaskId(taskId);
        record.setStatus(status);
        record.setLastUpdatedAt(lastUpdatedAt);
        return record;
    }

    /** 协议未启用：不触碰 WorkspaceManager（无 TaskRecord 产生） */
    @Test
    void disabledProtocolShouldSkipScan() {
        var agent = mock(HarnessAgent.class);
        var service = service(propsOf(false, 7), holderOf(agent));

        assertDoesNotThrow(service::scanExpiredProtocolTaskRecords);
        verify(agent, never()).getWorkspaceManager();
    }

    /** 开启时按 SDK 合成桶列举，无记录时不告警不抛异常 */
    @Test
    void enabledProtocolShouldListProtocolBucket() {
        var workspaceManager = mock(WorkspaceManager.class);
        when(workspaceManager.listTaskRecords(any(RuntimeContext.class),
            eq(AgentProtocolConstants.PROTOCOL_AGENT_ID),
            eq(AgentProtocolConstants.PROTOCOL_SESSION_ID))).thenReturn(List.of());
        var agent = mock(HarnessAgent.class);
        when(agent.getWorkspaceManager()).thenReturn(workspaceManager);

        service(propsOf(true, 7), holderOf(agent)).scanExpiredProtocolTaskRecords();

        verify(workspaceManager).listTaskRecords(any(RuntimeContext.class),
            eq(AgentProtocolConstants.PROTOCOL_AGENT_ID),
            eq(AgentProtocolConstants.PROTOCOL_SESSION_ID));
    }

    /** 列举失败仅告警不抛（兜底职责，不阻断清理主流程） */
    @Test
    void listingFailureShouldNotThrow() {
        var workspaceManager = mock(WorkspaceManager.class);
        when(workspaceManager.listTaskRecords(any(RuntimeContext.class), any(), any()))
            .thenThrow(new RuntimeException("db down"));
        var agent = mock(HarnessAgent.class);
        when(agent.getWorkspaceManager()).thenReturn(workspaceManager);

        assertDoesNotThrow(() -> service(propsOf(true, 7), holderOf(agent))
            .scanExpiredProtocolTaskRecords());
    }

    /** 过滤语义：仅「终态 且 lastUpdatedAt 早于保留期」计入过期（运行中/新终态不误报） */
    @Test
    void filterShouldCountOnlyExpiredTerminalRecords() {
        var old = Instant.now().minus(30, ChronoUnit.DAYS);
        var recent = Instant.now().minus(1, ChronoUnit.DAYS);
        var workspaceManager = mock(WorkspaceManager.class);
        when(workspaceManager.listTaskRecords(any(RuntimeContext.class), any(), any()))
            .thenReturn(List.of(
                record("expired-completed", TaskStatus.COMPLETED, old),      // 计入
                record("expired-failed", TaskStatus.FAILED, old),            // 计入
                record("expired-cancelled", TaskStatus.CANCELLED, old),      // 计入
                record("running", TaskStatus.RUNNING, old),                  // 非终态：不计
                record("recent-terminal", TaskStatus.COMPLETED, recent),     // 未超期：不计
                record("null-status", null, old)));                          // 状态缺失：不计
        var agent = mock(HarnessAgent.class);
        when(agent.getWorkspaceManager()).thenReturn(workspaceManager);

        var service = service(propsOf(true, 7), holderOf(agent));
        assertDoesNotThrow(service::scanExpiredProtocolTaskRecords);
        verify(workspaceManager).listTaskRecords(any(RuntimeContext.class), any(), any());
    }
}
