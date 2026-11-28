package io.agentmanager.framework;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.agentmanager.framework.config.AgentA2aJobProperties;
import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.AgentRedisProperties;
import io.agentmanager.framework.config.BrowserProperties;
import io.agentmanager.framework.config.HistoryConfig;
import io.agentmanager.framework.config.StageProgressConfig;
import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.config.SandboxLifecycleConfig;
import io.agentmanager.framework.config.SessionContextConfig;
import io.agentmanager.framework.config.VikingConfig;

@SpringBootApplication
@EnableConfigurationProperties({AgentManagerProperties.class, SandboxConfig.class, SandboxLifecycleConfig.class, AgentRedisProperties.class, HistoryConfig.class, StageProgressConfig.class, AgentA2aJobProperties.class, VikingConfig.class, BrowserProperties.class, SessionContextConfig.class})
@EnableScheduling
public class AgentFrameworkApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentFrameworkApplication.class, args);
    }
}
