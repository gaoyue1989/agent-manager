package io.agentmanager.framework.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import io.agentscope.core.a2a.server.AgentScopeA2aServer;
import io.agentscope.core.a2a.server.card.ConfigurableAgentCard;
import io.agentscope.core.a2a.server.transport.TransportProperties;

import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.HarnessAgentRunner;
import io.agentmanager.framework.service.MySqlTaskStore;
import io.agentscope.harness.agent.HarnessAgent;

@Configuration
public class A2AServerConfig {
    private static final Logger log = LoggerFactory.getLogger(A2AServerConfig.class);

    @Bean
    @DependsOn("harnessAgent")
    public AgentScopeA2aServer a2aServer(HarnessAgent harnessAgent, OafConfig oafConfig,
                                         DataSource dataSource) {
        var card = new ConfigurableAgentCard.Builder()
            .name(oafConfig.name())
            .description(oafConfig.description() != null ? oafConfig.description() : "")
            .url("http://localhost:8100")
            .build();

        var transportProps = TransportProperties.builder("JSONRPC")
            .path("/")
            .build();

        var runner = new HarnessAgentRunner(harnessAgent);

        var server = AgentScopeA2aServer.builder(runner)
            .agentCard(card)
            .withTransport(transportProps)
            .taskStore(new MySqlTaskStore(dataSource))
            .build();

        server.postEndpointReady();

        log.info("A2A server configured with JSON-RPC transport (HarnessAgentRunner + MySqlTaskStore)");
        return server;
    }
}
