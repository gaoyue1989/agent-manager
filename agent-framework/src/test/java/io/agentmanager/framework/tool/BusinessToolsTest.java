package io.agentmanager.framework.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BusinessToolsTest {

    private final BusinessTools tools = new BusinessTools();

    @Test
    void echoShouldReturnPrefixedText() {
        assertEquals("echo: hello", tools.echo("hello"));
        assertEquals("echo: ", tools.echo(""));
    }

    @Test
    void echoShouldPreserveWhitespace() {
        assertEquals("echo: a b c", tools.echo("a b c"));
    }

    @Test
    void getCurrentTimeShouldReturnIsoFormat() {
        var time = tools.getCurrentTime("Asia/Shanghai");
        // ISO_LOCAL_DATE_TIME 可能含纳秒: 2026-08-06T15:52:58 或 2026-08-06T15:52:58.123456789
        assertTrue(time.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?$"),
            "Unexpected format: " + time);
    }

    @Test
    void getCurrentTimeShouldSupportUtc() {
        var time = tools.getCurrentTime("UTC");
        assertTrue(time.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?$"));
    }

    @Test
    void getCurrentTimeShouldThrowOnInvalidTimezone() {
        assertThrows(java.time.zone.ZoneRulesException.class,
            () -> tools.getCurrentTime("Invalid/Zone"));
    }
}
