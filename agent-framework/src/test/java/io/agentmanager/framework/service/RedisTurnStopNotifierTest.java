package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.TurnStopSettings;

class RedisTurnStopNotifierTest {

    @Test
    void buildRawChannelIsolatesAgentsOnSharedRedis() {
        assertEquals("turn-stop:notify:release-agent",
            RedisTurnStopNotifier.buildRawChannel("release-agent"));
        assertEquals("turn-stop:notify:browser_takeover",
            RedisTurnStopNotifier.buildRawChannel("browser/takeover"));
        assertEquals("turn-stop:notify",
            RedisTurnStopNotifier.buildRawChannel("  "));
        assertEquals("turn-stop:notify",
            RedisTurnStopNotifier.buildRawChannel(null));
    }

    @Test
    void onMessageDecodesPayloadAndNotifiesWatcher() {
        var watcher = mock(TurnStopWatcher.class);
        var notifier = new RedisTurnStopNotifier(null, watcher,
            new TurnStopSettings(true, 1000, 15000), "release-agent");

        String payload = RedisTurnStopNotifier.encodePayload("tenant:sid-1", "reply-uuid-1");
        notifier.onMessage("turn-stop:notify:release-agent", payload);

        verify(watcher).notifyLocal("tenant:sid-1", "reply-uuid-1");
    }

    @Test
    void publishWithoutFacadeIsNoOp() {
        var watcher = mock(TurnStopWatcher.class);
        var notifier = new RedisTurnStopNotifier(null, watcher,
            new TurnStopSettings(true, 1000, 15000), "release-agent");

        notifier.publish("sid-1", "rid-1");
        notifier.ensureSubscribed();
        assertTrue(notifier.rawChannel().endsWith("release-agent"));
    }
}
