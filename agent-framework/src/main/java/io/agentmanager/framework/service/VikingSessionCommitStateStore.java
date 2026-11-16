package io.agentmanager.framework.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;

/**
 * OpenViking 会话归档装饰器（openviking-memory-integration-design §11.1 OVK_SESSION_COMMIT）：
 * agent_state 落库时把 context 中的**新增**消息 append 到 OpenViking session
 * （提取由服务端 auto_commit_policy 调度），替代逐 turn remember() 提交路径。
 *
 * <p>复用 {@link SessionMessageArchiveStateStore} 的拦截面（save×2 + saveIfVersion 全拦，
 * AGENT_STATE_KEY 过滤、fail-soft）与 toRecord 同口径的 Msg 判真；新增消息以 msg_id
 * 幂等键去重（Pod 本地 seen 集——重启后丢失会导致同会话消息重放 append，OpenViking
 * 提取器按内容合并，重复成本可控；跨副本各 append 一份同理）。
 *
 * <p>装配位置：Backfill(Archive(Commit(Mysql)))——Commit 在 Backfill 之内拿回填后形态、
 * 在 Archive 之外不干扰归档顺序。provider!=openviking 或 sessionCommit=false 不装配。
 */
public class VikingSessionCommitStateStore implements AgentStateStore {

    private static final Logger log = LoggerFactory.getLogger(VikingSessionCommitStateStore.class);

    private final AgentStateStore delegate;
    private final VikingMemoryClient client;

    public VikingSessionCommitStateStore(AgentStateStore delegate, VikingMemoryClient client) {
        this.delegate = delegate;
        this.client = client;
    }

    @Override
    public void save(String userId, String sessionId, String stateKey, State state) {
        commit(userId, sessionId, stateKey, state);
        delegate.save(userId, sessionId, stateKey, state);
    }

    @Override
    public void save(String userId, String sessionId, String stateKey, List<? extends State> states) {
        if (states != null) {
            for (var state : states) {
                commit(userId, sessionId, stateKey, state);
            }
        }
        delegate.save(userId, sessionId, stateKey, states);
    }

    @Override
    public long saveIfVersion(String userId, String sessionId, String stateKey, State state, long expectedVersion) {
        commit(userId, sessionId, stateKey, state);
        return delegate.saveIfVersion(userId, sessionId, stateKey, state, expectedVersion);
    }

    // ---- 其余方法原样透传（与 SessionMessageArchiveStateStore 同理）----

    @Override
    public <T extends State> java.util.Optional<T> get(String userId, String sessionId, String stateKey, Class<T> type) {
        return delegate.get(userId, sessionId, stateKey, type);
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String stateKey, Class<T> type) {
        return delegate.getList(userId, sessionId, stateKey, type);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId, String stateKey) {
        delegate.delete(userId, sessionId, stateKey);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(userId);
    }

    @Override
    public void close() {
        delegate.close();
    }

    // ---- 内部 ----

    private static final Map<String, java.util.Collection<String>> SEEN = new ConcurrentHashMap<>();

    /** 把 context 新增消息 append 到 OpenViking session（fail-soft：任何异常只告警） */
    private void commit(String userId, String sessionId, String stateKey, State state) {
        try {
            if (!SessionMessageArchiveStateStore.AGENT_STATE_KEY.equals(stateKey)
                    || sessionId == null || sessionId.isBlank()
                    || !(state instanceof AgentState agentState)) {
                return;
            }
            var context = agentState.getContext();
            if (context == null || context.isEmpty() || userId == null || userId.isBlank()) {
                return;
            }
            var slot = SessionMessageArchiveStateStore.slotKey(userId, sessionId);
            var ids = SEEN.computeIfAbsent(slot, k -> ConcurrentHashMap.newKeySet());
            if (ids.size() > 2000) {
                ids.clear(); // 容量护栏：长会话超限清空后重放 append（提取器按内容合并，可接受）
            }
            for (var msg : context) {
                if (msg == null || msg.getId() == null || msg.getId().isBlank() || !ids.add(msg.getId())) {
                    continue;
                }
                var role = msg.getRole() != null ? msg.getRole().name().toLowerCase() : "user";
                var text = msg.getTextContent();
                if (text == null || text.isBlank()) {
                    continue;
                }
                client.appendSessionMessage(userId, sessionId, role, text);
            }
        } catch (Exception e) {
            log.warn("[viking] session commit append failed (user={}, session={}): {}",
                userId, sessionId, e.getMessage());
        }
    }
}
