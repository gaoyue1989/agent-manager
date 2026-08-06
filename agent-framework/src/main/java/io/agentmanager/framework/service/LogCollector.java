package io.agentmanager.framework.service;

import java.util.List;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ch.qos.logback.classic.Logger;
import jakarta.annotation.PostConstruct;

/**
 * 日志采集器：启动时将 InMemoryLogAppender 挂载到 root logger。
 */
@Component
public class LogCollector {

    private final InMemoryLogAppender appender = new InMemoryLogAppender();

    @PostConstruct
    public void attach() {
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        if (root.getAppender("IN_MEMORY") == null) {
            appender.setName("IN_MEMORY");
            appender.start();
            root.addAppender(appender);
        }
    }

    public List<String> getLogs(String level, int limit) {
        return appender.snapshot(level, limit);
    }
}
