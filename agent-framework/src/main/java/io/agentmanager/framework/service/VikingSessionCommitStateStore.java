package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
 * <p>落库先行 + 异步提交（issue #418）：delegate.save/saveIfVersion 同步完成后，
 * 新增消息整批投递单线程 worker 异步 append——OpenViking 慢/挂不阻塞也不推迟 agent_state
 * 落库；有界队列（{@link #QUEUE_CAPACITY}）满整批丢弃并告警，绝不阻塞主链路、不无界堆积。
 * OV session 映射键用 slot 复合会话键 {@code slotKey(userId, sessionId)}——A2A 同用户
 * 多会话各写各的 OV session，不再按 userId 串写（issue #418）。
 *
 * <p>复用 {@link SessionMessageArchiveStateStore} 的拦截面（save×2 + saveIfVersion 全拦，
 * AGENT_STATE_KEY 过滤、fail-soft）与 toRecord 同口径的 Msg 判真；新增消息以 msg_id
 * 幂等键去重（Pod 本地 seen 集——重启后丢失会导致同会话消息重放 append，OpenViking
 * 提取器按内容合并，重复成本可控；跨副本各 append 一份同理；去重 claim 在 save 调用方
 * 完成，被丢弃的批次不重试，与队列丢弃策略同语义）。
 *
 * <p>装配位置：Backfill(Archive(Commit(Mysql)))——Commit 在 Backfill 之内拿回填后形态、
 * 在 Archive 之外不干扰归档顺序。provider!=openviking、sessionCommit=false 或
 * memoryEnabled=false 不装配。
 */
public class VikingSessionCommitStateStore implements AgentStateStore {

    private static final Logger log = LoggerFactory.getLogger(VikingSessionCommitStateStore.class);

    /** 单 worker 待提交队列容量：超出即整批丢弃（宁丢消息不拖主链路，OpenViking 提取按内容合并） */
    static final int QUEUE_CAPACITY = 500;
    /** seen 去重集容量护栏：超限整体清空重放 append（宁可重复不可丢消息） */
    private static final int SEEN_CAPACITY = 2000;

    private final AgentStateStore delegate;
    private final VikingMemoryClient client;
    /** slot 键 → 真实用户反查（Channel 链路 slot.userId=前端 sessionId=session_user.session_id） */
    private final SessionUserStore sessionUserStore;
    /** Pod 本地去重集（slotKey → msg ids）；容量护栏超限整体清空（宁可重复不可丢消息）。
     *  实例字段：装饰器每 Pod 单实例，static 会把去重态泄漏到不同装配世代（reload/测试）。 */
    private final Map<String, Set<String>> seenBySlot = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor commitWorker = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY),
        r -> {
            var t = new Thread(r, "viking-session-commit");
            t.setDaemon(true);
            return t;
        });

    /** 一批待 append 消息（同一 save 内的新增消息，user/session 同值） */
    private record PendingAppend(String role, String content) {
    }

    public VikingSessionCommitStateStore(AgentStateStore delegate, VikingMemoryClient client,
                                         SessionUserStore sessionUserStore) {
        this.delegate = delegate;
        this.client = client;
        this.sessionUserStore = sessionUserStore;
    }

    @Override
    public void save(String userId, String sessionId, String stateKey, State state) {
        delegate.save(userId, sessionId, stateKey, state);
        enqueueCommit(userId, sessionId, stateKey, state);
    }

    @Override
    public void save(String userId, String sessionId, String stateKey, List<? extends State> states) {
        delegate.save(userId, sessionId, stateKey, states);
        if (states != null) {
            for (var state : states) {
                enqueueCommit(userId, sessionId, stateKey, state);
            }
        }
    }

    @Override
    public long saveIfVersion(String userId, String sessionId, String stateKey, State state, long expectedVersion) {
        var version = delegate.saveIfVersion(userId, sessionId, stateKey, state, expectedVersion);
        enqueueCommit(userId, sessionId, stateKey, state);
        return version;
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
        commitWorker.shutdown();
        delegate.close();
    }

    // ---- 内部 ----

    /** 过滤出 context 新增消息投递异步 worker（fail-soft：任何异常只告警） */
    private void enqueueCommit(String userId, String sessionId, String stateKey, State state) {
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
            var ids = seenBySlot.computeIfAbsent(slot, k -> ConcurrentHashMap.newKeySet());
            if (ids.size() > SEEN_CAPACITY) {
                ids.clear(); // 容量护栏：长会话超限清空后重放 append（提取器按内容合并，可接受）
            }
            List<PendingAppend> batch = null;
            for (var msg : context) {
                if (msg == null || msg.getId() == null || msg.getId().isBlank() || !ids.add(msg.getId())) {
                    continue;
                }
                var role = msg.getRole() != null ? msg.getRole().name().toLowerCase() : "user";
                var text = msg.getTextContent();
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (batch == null) {
                    batch = new ArrayList<>();
                }
                batch.add(new PendingAppend(role, text));
            }
            if (batch == null) {
                return;
            }
            submit(userId, sessionId, slot, batch);
        } catch (Exception e) {
            log.warn("[viking] session commit enqueue failed (user={}, session={}): {}",
                userId, sessionId, e.getMessage());
        }
    }

    private void submit(String userId, String sessionId, String slot, List<PendingAppend> batch) {
        try {
            commitWorker.execute(() -> appendBatch(userId, sessionId, slot, batch));
        } catch (RejectedExecutionException e) {
            log.warn("[viking] session commit queue full, dropped {} message(s) (slot={})",
                batch.size(), slot);
        }
    }

    /** 异步 append 一批消息（单 worker 保序；realUser 反查每批一次；任何异常只告警） */
    private void appendBatch(String userId, String sessionId, String slot, List<PendingAppend> batch) {
        try {
            String realUser = sessionUserStore.findUserIdBySession(userId);
            if (realUser == null || realUser.isBlank()) {
                realUser = sessionUserStore.findUserIdBySession(sessionId);
            }
            if (realUser == null || realUser.isBlank()) {
                realUser = userId;
            }
            for (var pending : batch) {
                client.appendSessionMessage(realUser, slot, pending.role(), pending.content());
            }
        } catch (Exception e) {
            log.warn("[viking] session commit append failed (slot={}): {}", slot, e.getMessage());
        }
    }
}
