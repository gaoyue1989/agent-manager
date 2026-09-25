package io.agentmanager.framework.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import javax.sql.DataSource;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.A2aAgentRefHolder;
import io.agentscope.harness.agent.HarnessAgent;

import org.junit.jupiter.api.Test;

class A2AServerConfigTest {

    private final A2AServerConfig config = new A2AServerConfig();

    private static AgentManagerProperties propsForTest() {
        var props = mock(AgentManagerProperties.class);
        when(props.server()).thenReturn(new AgentManagerProperties.ServerConfig("0.0.0.0", 8100));
        return props;
    }

    @Test
    void a2aServerShouldBuildWithRunner() {
        var agentMock = mock(HarnessAgent.class);
        var holder = new A2aAgentRefHolder(new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public HarnessAgent getIfAvailable() {
                return agentMock;
            }

            @Override
            public HarnessAgent getObject(Object... args) {
                return agentMock;
            }

            @Override
            public HarnessAgent getObject() {
                return agentMock;
            }

            @Override
            public HarnessAgent getIfUnique() {
                return agentMock;
            }
        });
        var oaf = mock(OafConfig.class);
        var dataSource = mock(DataSource.class);
        when(oaf.name()).thenReturn("agent-a");
        when(oaf.description()).thenReturn("desc");

        var server = config.a2aServer(holder, oaf, dataSource, propsForTest());
        assertNotNull(server);
    }

    @Test
    void a2aServerShouldTolerateNullDescription() {
        var agentMock = mock(HarnessAgent.class);
        var holder = new A2aAgentRefHolder(new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public HarnessAgent getIfAvailable() {
                return agentMock;
            }

            @Override
            public HarnessAgent getObject(Object... args) {
                return agentMock;
            }

            @Override
            public HarnessAgent getObject() {
                return agentMock;
            }

            @Override
            public HarnessAgent getIfUnique() {
                return agentMock;
            }
        });
        var oaf = mock(OafConfig.class);
        var dataSource = mock(DataSource.class);
        when(oaf.name()).thenReturn("agent-b");
        when(oaf.description()).thenReturn(null);

        var server = config.a2aServer(holder, oaf, dataSource, propsForTest());
        assertNotNull(server);
    }
}
