package io.agentmanager.framework;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.agentmanager.framework.config.AgentManagerProperties;

@SpringBootApplication
@EnableConfigurationProperties(AgentManagerProperties.class)
@EnableScheduling
public class AgentFrameworkApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentFrameworkApplication.class, args);
    }
}
