package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AgentState 读取器：从 {@code agent_state} 表取回会话状态 JSON 并交给
 * {@link StateDataParser} 解析。
 *
 * <p>DB 是消息级事实的权威来源（官方 SDK 自动持久化 AgentState.getContext()）：
 * 消息文本、工具调用（含 ToolCallState）、工具结果（含 ToolResultState 与 output）都在其中，
 * 且与会话同寿命（默认 7 天），不受 Redis 事件流 TTL 影响。history 回放与 HITL 确认卡
 * 重建共用本读取器，避免两处各自拼 SQL 导致口径漂移。
 *
 * <p>session 匹配兼容多种 key 形态（见 {@link #loadStateData}）：
 * 前端 peer（{@code webui-xxx}）、带 tenant 前缀的完整 threadId
 * （{@code agentmanager-release-agent__webui-xxx}）、以及 SDK 的
 * {@code userId:canonicalKey} 形式。
 */
@Service
public class AgentStateReader {

    private static final Logger log = LoggerFactory.getLogger(AgentStateReader.class);

    private final DataSource dataSource;

    public AgentStateReader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 读取会话状态 JSON（多条 item_index 时拼成 JSON 数组），供解析器使用。
     *
     * <p>前缀/后缀 LIKE 匹配覆盖 SDK 内部 session_id 形态差异；state_key 过滤
     * {@code agent_state} 以排除 sandbox_state 等无关记录。读失败返回空列表（fail-soft）。
     */
    public List<String> loadFragments(String sessionId) {
        var fragments = new ArrayList<String>();
        if (sessionId == null || sessionId.isBlank()) {
            return fragments;
        }
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT state_data FROM agent_state "
                     + "WHERE (session_id = ? "
                     +   "OR session_id LIKE CONCAT(?, ':%') "
                     +   "OR session_id LIKE CONCAT(?, '__%') "
                     +   "OR session_id LIKE CONCAT('%:', ?) "
                     +   "OR session_id LIKE CONCAT('%__', ?)) "
                     + "AND state_key = 'agent_state' "
                     + "ORDER BY item_index")) {
            stmt.setString(1, sessionId);
            stmt.setString(2, sessionId);
            stmt.setString(3, sessionId);
            stmt.setString(4, sessionId);
            stmt.setString(5, sessionId);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    fragments.add(rs.getString("state_data"));
                }
            }
        } catch (Exception e) {
            log.warn("AgentStateReader: load failed for {}: {}", sessionId, e.getMessage());
        }
        return fragments;
    }

    /**
     * 读取会话状态 JSON 字符串：单条直接返回，多条拼成 JSON 数组。
     *
     * @return 状态 JSON；无记录或读取失败时返回 null
     */
    public String loadStateData(String sessionId) {
        var fragments = loadFragments(sessionId);
        if (fragments.isEmpty()) {
            return null;
        }
        return fragments.size() == 1 ? fragments.get(0) : "[" + String.join(",", fragments) + "]";
    }

    /**
     * 提取待人工确认（HITL）的工具调用 —— 用于 history 重建确认卡片。
     *
     * @return 每个元素形如 {tool_call_id, name, input, reply_id}；无挂起时为空列表
     */
    public List<java.util.Map<String, Object>> findAskingToolCalls(String sessionId) {
        var stateData = loadStateData(sessionId);
        if (stateData == null) {
            return List.of();
        }
        return StateDataParser.extractAskingToolCalls(StateDataParser.findMessagesArray(stateData));
    }

    /**
     * 提取会话的运行时身份（网关 sessionId 与 userId）—— 恢复执行所需的 RuntimeContext 键。
     *
     * @return {session_id, user_id}；无法解析时两项均为空串
     */
    public java.util.Map<String, String> findRuntimeIdentity(String sessionId) {
        return StateDataParser.extractRuntimeIdentity(loadStateData(sessionId));
    }

    /**
     * HITL 挂起快照：一次 DB 读取派生恢复所需的全部要素。
     *
     * @param asking       挂起工具（前端词表形态，含 reply_id）
     * @param toolUseBlocks 按挂起工具 id 重建的 ToolUseBlock（SDK 恢复校验只按 id 匹配，
     *                     见 {@code ReActAgent.applyConfirmResults}，等价实例即可）
     * @param replyId      SDK 持久化在最后一条 assistant 消息 metadata 的关联 id；缺失为空串
     *                     （SDK 恢复时会兜底生成）
     * @param identity     运行时身份 {session_id(gw-hash), user_id(peer)}
     */
    public record AskingSnapshot(
            List<java.util.Map<String, Object>> asking,
            java.util.Map<String, io.agentscope.core.message.ToolUseBlock> toolUseBlocks,
            String replyId,
            java.util.Map<String, String> identity) {
        public boolean isEmpty() {
            return asking.isEmpty();
        }
    }

    /** 空快照（identity 保持两项空串，避免调用方判空 NPE） */
    private static AskingSnapshot emptySnapshot() {
        return new AskingSnapshot(List.of(), java.util.Map.of(), "",
            java.util.Map.of("session_id", "", "user_id", ""));
    }

    /**
     * 按 key 候选顺序读取 state，返回第一份含 ASKING 挂起工具的快照。
     *
     * <p>候选顺序通常为 {@code (fullThreadId, rawPeer)}：agent_state 的 session_id 是
     * {@code peer:canonicalKey} 形态，带 tenant 前缀的 fullThreadId 恒不命中——
     * 落空即换下一候选，<b>state 只读一份</b>，asking/identity/replyId 全部从同一份
     * JSON 派生（修复此前"按 fullThreadId 重查 replyId 恒为空"的缺陷）。
     *
     * @return 命中的快照；所有候选都无挂起工具时返回空快照（{@link AskingSnapshot#isEmpty()}）
     */
    public AskingSnapshot loadAskingSnapshot(String... sessionIds) {
        for (var sid : sessionIds) {
            if (sid == null || sid.isBlank()) {
                continue;
            }
            var stateData = loadStateData(sid);
            if (stateData == null) {
                continue;
            }
            var asking = StateDataParser.extractAskingToolCalls(StateDataParser.findMessagesArray(stateData));
            if (asking.isEmpty()) {
                continue;
            }
            var blocks = new java.util.LinkedHashMap<String, io.agentscope.core.message.ToolUseBlock>();
            for (var call : asking) {
                var id = (String) call.get("tool_call_id");
                if (id == null || id.isEmpty()) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                var input = call.get("input") instanceof java.util.Map<?, ?> raw
                    ? (java.util.Map<String, Object>) raw
                    : java.util.Map.<String, Object>of();
                // content 回填：ToolValidator.validateInput 用块上的 content（String）做 schema 校验，
                // content=null 时 networknt readTree 抛 'argument "content" is null'，恢复执行必然失败
                //（ConfirmContextStore.toToolCalls 已有同款修复，此处对齐 state 路径）。
                // 优先用 SDK 持久化在块上的原始 content；缺失时（老数据）用 input 序列化补齐
                //（空 Map 序列化为 "{}"，与表路径 toContentJson 一致）——恒不落 null：
                // "{}" 在 schema 校验时给出正常缺参报错，模型可自愈重试
                var rawContent = call.get("content") instanceof String s && !s.isBlank() ? s : null;
                var content = rawContent != null ? rawContent : StateDataParser.toJsonString(input);
                blocks.put(id, new io.agentscope.core.message.ToolUseBlock(
                    id, (String) call.getOrDefault("name", ""), input,
                    content, null, io.agentscope.core.message.ToolCallState.ASKING));
            }
            var replyId = asking.stream()
                .map(c -> (String) c.getOrDefault("reply_id", ""))
                .filter(s -> s != null && !s.isBlank())
                .findFirst().orElse("");
            return new AskingSnapshot(asking, blocks, replyId,
                StateDataParser.extractRuntimeIdentity(stateData));
        }
        return emptySnapshot();
    }
}
