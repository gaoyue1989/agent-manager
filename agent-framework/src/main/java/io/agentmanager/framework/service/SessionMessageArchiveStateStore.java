package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;

/**
 * AgentStateStore 装饰器：AgentState 落库时把 context 消息 write-through 归档到
 * session_message 消息轨（压缩后历史可查，设计文档 docs/session-history-archive-design.md）。
 *
 * <p>为什么需要：压缩（CompactionMiddleware）就地 clear+addAll 替换上下文，随后 agent_state
 * 快照整体覆盖写——压缩前的消息从该表物理消失。本装饰器在**每次 save**（而非压缩时）把消息
 * 逐条归档（唯一键 (session_id, msg_id) 幂等，内容按「文本取长、状态取新」合并），
 * 压缩发生时原文已在库中。
 *
 * <p>装配位置须在 {@code AskingContentBackfillStateStore} **之内**（Backfill 先原位 patch
 * 再下传，内层看到的才是回填后的最终形态）：
 * {@code Backfill(Archive(SandboxAwareMysql))}。归档先于 delegate 执行但完全 fail-soft，
 * 任何归档异常只告警，绝不影响 agent_state 落库与对话主链路。
 *
 * <p>拦截全部三条写路径（save×2 + saveIfVersion——2.0.3 版本化 MySQL 的主写入口是
 * saveIfVersion，仅拦 save 会漏归档），其余方法原样透传。
 */
public class SessionMessageArchiveStateStore implements AgentStateStore {

    private static final Logger log = LoggerFactory.getLogger(SessionMessageArchiveStateStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** ReActAgent 持久化 AgentState 的固定 stateKey（AgentStateReader 同款过滤口径） */
    static final String AGENT_STATE_KEY = "agent_state";

    /** 空 userId 的归一值（与 MysqlAgentStateStore.normalizeUser / slotId 字节码一致） */
    static final String ANON_USER = "__anon__";

    private final AgentStateStore delegate;
    private final SessionMessageStore sessionMessageStore;

    public SessionMessageArchiveStateStore(AgentStateStore delegate, SessionMessageStore sessionMessageStore) {
        this.delegate = delegate;
        this.sessionMessageStore = sessionMessageStore;
    }

    @Override
    public void save(String userId, String sessionId, String stateKey, State state) {
        archive(userId, sessionId, stateKey, state);
        delegate.save(userId, sessionId, stateKey, state);
    }

    @Override
    public void save(String userId, String sessionId, String stateKey, List<? extends State> states) {
        if (states != null) {
            for (var state : states) {
                archive(userId, sessionId, stateKey, state);
            }
        }
        delegate.save(userId, sessionId, stateKey, states);
    }

    /**
     * 版本化 CAS 写入：ReActAgent.persistAgentStateCas 的主写路径，同样归档。
     * 与 AskingContentBackfillStateStore 同理，须显式覆写——接口 default 会绕过 delegate
     * 破坏版本语义。CAS 失败（返回 UNVERSIONED 不落库）也照常归档：被竞争掉的消息
     * 仍是真实存在过的消息，归档保留无害且更完整。
     */
    @Override
    public long saveIfVersion(String userId, String sessionId, String stateKey, State state, long expectedVersion) {
        archive(userId, sessionId, stateKey, state);
        return delegate.saveIfVersion(userId, sessionId, stateKey, state, expectedVersion);
    }

    @Override
    public boolean supportsVersioning() {
        return delegate.supportsVersioning();
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String stateKey, Class<T> type) {
        return delegate.getVersioned(userId, sessionId, stateKey, type);
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String stateKey, Class<T> type) {
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

    /**
     * 归档一个 AgentState 的 context 消息（fail-soft：任何异常只告警）。
     *
     * <p>msg_id 取 {@code Msg.getId()}（SDK 构建时自动 UUID，缺失则跳过该条——
     * 无幂等键的行无法安全重放）；reply_id **不盖戳**：实测 {@code AgentState.getReplyId()}
     * 在 ChatUI 链路是会话级而非逐轮值（16 轮全部同一 id，见设计文档 §15），盖戳会破坏
     * 前端按 reply_id 关联 turn 的机制——reply_id 由查询侧 Redis 顺序回填（既有语义）。
     * 摘要消息按 name 识别为 compaction_summary。
     */
    void archive(String userId, String sessionId, String stateKey, State state) {
        if (sessionMessageStore == null || !AGENT_STATE_KEY.equals(stateKey)) {
            return;
        }
        // slotId 的入参校验语义与 SDK 一致：sessionId 空白交给 delegate 抛，归档直接跳过
        if (sessionId == null || sessionId.isBlank() || !(state instanceof AgentState agentState)) {
            return;
        }
        try {
            var context = agentState.getContext();
            if (context == null || context.isEmpty()) {
                return;
            }
            var records = new ArrayList<SessionMessageStore.MessageRecord>(context.size());
            for (var msg : context) {
                var record = toRecord(msg);
                if (record != null) {
                    records.add(record);
                }
            }
            if (!records.isEmpty()) {
                sessionMessageStore.archiveBatch(slotKey(userId, sessionId), records);
            }
        } catch (Exception e) {
            log.warn("SessionMessageArchiveStateStore: archive failed (user={}, session={}): {}",
                userId, sessionId, e.getMessage());
        }
    }

    /** Msg → 归档记录；无 msg_id 或序列化失败返回 null（跳过，见 archive 注释） */
    static SessionMessageStore.MessageRecord toRecord(Msg msg) {
        if (msg == null || msg.getId() == null || msg.getId().isBlank()) {
            return null;
        }
        String json;
        try {
            json = MAPPER.writeValueAsString(msg);
        } catch (Exception e) {
            log.warn("SessionMessageArchiveStateStore: serialize failed for msg {}: {}",
                msg.getId(), e.getMessage());
            return null;
        }
        var kind = SessionMessageStore.SUMMARY_MESSAGE_NAME.equals(msg.getName())
            ? SessionMessageStore.KIND_COMPACTION_SUMMARY
            : SessionMessageStore.KIND_MESSAGE;
        var role = msg.getRole() != null ? msg.getRole().name() : null;
        return new SessionMessageStore.MessageRecord(msg.getId(), kind, role, null, json);
    }

    /**
     * slot 复合键，与 agent_state.session_id 列同值：
     * {@code normalizeUser(userId) + ":" + sessionId}（MysqlAgentStateStore.slotId 字节码 +
     * ThreadController 注释双证据；normalizeUser 空白归一 __anon__）。同键存储保证 history
     * 查询用与 AgentStateReader 相同的 LIKE 谓词即可命中两表。
     */
    static String slotKey(String userId, String sessionId) {
        var user = (userId == null || userId.isBlank()) ? ANON_USER : userId;
        return user + ":" + sessionId;
    }
}
