package io.agentmanager.framework.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BrowserPropertiesTest {

    @EnableConfigurationProperties(BrowserProperties.class)
    static class EnableBrowser {}

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner().withUserConfiguration(EnableBrowser.class);

    @Test
    void everyLeafFallsBackToDefaultWhenSectionAbsent() {
        runner.run(ctx -> {
            var props = ctx.getBean(BrowserProperties.class);
            assertNotNull(props);
            assertFalse(props.enabled());
            assertEquals(60, props.takeoverTtlMinutes());
            assertEquals(6080, props.viewPort());
            assertEquals("/websockify", props.viewPath());
            assertEquals(60, props.vncTicketTtlSeconds());
            assertEquals(List.of(), props.originPatterns());
        });
    }

    @Test
    void emptyOriginPatternPropertyFallsBackToSameOrigin() {
        runner.withPropertyValues("agent.browser.allowed-origin-patterns=").run(ctx -> {
            var props = ctx.getBean(BrowserProperties.class);
            assertEquals(List.of(), props.originPatterns());
        });
    }

    @Test
    void explicitValuesOverrideDefaults() {
        runner.withPropertyValues(
                "agent.browser.vnc-ticket-ttl-seconds=30",
                "agent.browser.allowed-origin-patterns=http://demo.example:5188, https://console.example")
            .run(ctx -> {
                var props = ctx.getBean(BrowserProperties.class);
                assertEquals(30, props.vncTicketTtlSeconds());
                assertEquals(List.of("http://demo.example:5188", "https://console.example"),
                    props.originPatterns());
            });
    }
}
