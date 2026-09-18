package io.agentmanager.framework.service;

import io.agentscope.core.a2a.server.executor.runner.AgentRequestOptions;
import io.agentscope.core.a2a.server.executor.runner.AgentRunner;
import io.agentscope.core.event.AgentEvent;
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

    /**
     * 2.0.3 起 {@code AgentRunner} 的抽象方法由 {@code stream()} 改为 {@code streamEvents()}，
     * 事件类型从粗粒度 {@code Event} 换成细粒度 {@link AgentEvent}（与 /threads/chat 链路同源）。
     * 这里直接透传 HarnessAgent 的事件流，保持 A2A 与平台链路的事件词表一致。
     */
    @Override
    public Flux<AgentEvent> streamEvents(List<Msg> requestMessages, AgentRequestOptions options) {
        // ★ Windows 路径安全化：SDK 把 sessionId/userId 当目录名用
        var rawSid = options.getSessionId() != null
                ? options.getSessionId() : options.getTaskId();
        var ctx = io.agentscope.core.agent.RuntimeContext.builder()
            .sessionId(io.agentmanager.framework.util.PathSafe.sanitize(rawSid))
            .userId(io.agentmanager.framework.util.PathSafe.sanitize(
                options.getUserId() != null ? options.getUserId() : "anonymous"))
            .build();

        taskSessionMap.put(options.getTaskId(), options.getSessionId());

        return agent.streamEvents(requestMessages, ctx)
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
