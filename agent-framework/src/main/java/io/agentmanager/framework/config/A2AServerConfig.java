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

import io.agentmanager.framework.model.AgentCardNotes;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.service.A2aAgentRefHolder;
import io.agentmanager.framework.service.HarnessAgentRunner;
import io.agentmanager.framework.service.MySqlTaskStore;

@Configuration
public class A2AServerConfig {
    private static final Logger log = LoggerFactory.getLogger(A2AServerConfig.class);

    @Bean
    @DependsOn("harnessAgent")
    public AgentScopeA2aServer a2aServer(A2aAgentRefHolder a2aAgentRefHolder, OafConfig oafConfig,
                                         DataSource dataSource, AgentManagerProperties props) {
        var serverCfg = props.server();
        // 0.0.0.0 是监听通配地址，不能作为对外注册地址；多实例/非标准端口场景
        // 经 SERVER_HOST/SERVER_PORT 注入实际可达地址
        var host = "0.0.0.0".equals(serverCfg.host()) ? "127.0.0.1" : serverCfg.host();
        var card = new ConfigurableAgentCard.Builder()
            .name(oafConfig.name())
            // A2A 通道 HITL 限制声明（ask 工具挂起无法经 A2A 批准，见 AgentCardNotes）
            .description(AgentCardNotes.withA2aLimitation(oafConfig.description()))
            .url(String.format("http://%s:%d", host, serverCfg.port()))
            .build();

        var transportProps = TransportProperties.builder("JSONRPC")
            .path("/")
            .build();

        // runner 经 holder 间接持有 agent（volatile）：OAF reload 整包重建后
        // A2A 链路自动路由到新 agent；进行中的事件流持旧引用跑完不受影响
        var runner = new HarnessAgentRunner(a2aAgentRefHolder);

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
