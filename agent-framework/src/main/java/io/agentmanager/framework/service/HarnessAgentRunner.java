package io.agentmanager.framework.service;

import io.agentscope.core.agent.Event;
import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;

/**
 * 将 HarnessAgent 适配为 A2A Server 的 AgentRunner。
 * 每个 A2A 请求通过 RuntimeContext(userId, sessionId) 路由到共享的 HarnessAgent 实例。
 */
public class HarnessAgentRunner implements AgentRunner {

    private final HarnessAgent agent;
    private final Map<String, String> taskSessionMap = new ConcurrentHashMap<>();

    public HarnessAgentRunner(HarnessAgent agent) {
        this.agent = agent;
    }

    @Override
    public String getAgentName() {
        return agent.getName();
    }

    @Override
    public String getAgentDescription() {
        return agent.getDescription();
    }

    @Override
    public Flux<Event> stream(List<Msg> requestMessages, AgentRequestOptions options) {
        var ctx = io.agentscope.core.agent.RuntimeContext.builder()
            .sessionId(options.getSessionId() != null
                    ? options.getSessionId() : options.getTaskId())
            .userId(options.getUserId() != null ? options.getUserId() : "anonymous")
            .build();

        taskSessionMap.put(options.getTaskId(), options.getSessionId());

        return agent.stream(requestMessages, ctx)
            .doFinally(signal -> taskSessionMap.remove(options.getTaskId()));
    }

    @Override
    public void stop(String taskId) {
        var sessionId = taskSessionMap.get(taskId);
        if (sessionId != null) {
            agent.interrupt();
        }
    }
}
