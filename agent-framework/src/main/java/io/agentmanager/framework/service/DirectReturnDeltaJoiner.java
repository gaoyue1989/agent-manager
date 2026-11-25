package io.agentmanager.framework.service;

import io.agentscope.core.event.AgentEvent;

public final class DirectReturnDeltaJoiner {

    public AgentEvent apply(AgentEvent event) {
        return event;
    }
}
