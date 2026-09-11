package io.agentmanager.framework;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.AguiProperties;
import io.agentmanager.framework.config.SandboxConfig;

@SpringBootApplication
@EnableConfigurationProperties({AgentManagerProperties.class, SandboxConfig.class, AguiProperties.class})
@EnableScheduling
public class AgentFrameworkApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentFrameworkApplication.class, args);
    }
}
