package io.agentmanager.framework.service;

public interface TurnStopNotifier {

    void publish(String sessionId, String replyId);

    default void ensureSubscribed() {
    }
}
