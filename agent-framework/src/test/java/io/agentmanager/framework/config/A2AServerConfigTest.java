package io.agentmanager.framework.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import javax.sql.DataSource;

import io.agentmanager.framework.model.OafConfig;
import io.agentscope.harness.agent.HarnessAgent;

import org.junit.jupiter.api.Test;

class A2AServerConfigTest {

    private final A2AServerConfig config = new A2AServerConfig();

    @Test
    void a2aServerShouldBuildWithRunner() {
        var agent = mock(HarnessAgent.class);
        var oaf = mock(OafConfig.class);
        var dataSource = mock(DataSource.class);
        when(oaf.name()).thenReturn("agent-a");
        when(oaf.description()).thenReturn("desc");

        var server = config.a2aServer(agent, oaf, dataSource);
        assertNotNull(server);
    }

    @Test
    void a2aServerShouldTolerateNullDescription() {
        var agent = mock(HarnessAgent.class);
        var oaf = mock(OafConfig.class);
        var dataSource = mock(DataSource.class);
        when(oaf.name()).thenReturn("agent-b");
        when(oaf.description()).thenReturn(null);

        var server = config.a2aServer(agent, oaf, dataSource);
        assertNotNull(server);
    }
}
