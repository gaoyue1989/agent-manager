package io.agentmanager.framework.sandbox.opensandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;

/**
 * AgentStateStore 装饰器：持久化 AgentState 前把 ASKING tool_use 块的 content 回填为
 * input 的 JSON 字符串（治本写侧补丁，修复 HITL 确认恢复 content=null）。
 *
 * <p>背景：官方 SDK 挂起（HITL ASK）时持久化到 agent_state 的 assistant 消息里，
 * ASKING 块 input 恒为空对象、content 为 {@code "{}"}（或 null）——参数只存在于
 * RequireUserConfirmEvent / confirm_context。一旦 confirm_context 行被 30 分钟 TTL
 * 清理，恢复走 agent_state 路径时重建的块 content=null，到达
 * {@code ToolValidator.validateInput(content, parameters)} 时 networknt readTree 抛
 * {@code argument "content" is null}，工具执行失败。本包装在**落库前**把 ASKING 块
 * 补成 content=input JSON，使恢复路径天然携带完整参数。
 *
 * <p>与 SDK 的兼容性：恢复时 SDK 按块 id 用 ConfirmResult 携带的 ToolUseBlock
 * 原位替换（applyToolUseBlockReplacements），持久化块上多出的 content 不参与匹配，
 * 仅作为校验/展示的参数来源，因此对框架透明。
 */
public class AskingContentBackfillStateStore implements AgentStateStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentStateStore delegate;

    public AskingContentBackfillStateStore(AgentStateStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public void save(String sessionId, String userId, String stateKey, State state) {
        if (state instanceof AgentState agentState) {
            patchAskingContent(agentState);
        }
        delegate.save(sessionId, userId, stateKey, state);
    }

    @Override
    public void save(String sessionId, String userId, String stateKey, List<? extends State> states) {
        if (states != null) {
            for (var state : states) {
                if (state instanceof AgentState agentState) {
                    patchAskingContent(agentState);
                }
            }
        }
        delegate.save(sessionId, userId, stateKey, states);
    }

    @Override
    public <T extends State> java.util.Optional<T> get(String sessionId, String userId, String stateKey, Class<T> type) {
        return delegate.get(sessionId, userId, stateKey, type);
    }

    /**
     * 版本化语义全量透传：ReActAgent 会调用 saveIfVersion/getVersioned（CAS 乐观锁），
     * MysqlAgentStateStore 有真实实现——不能落回接口 default（default saveIfVersion 直接
     * 调 this.save，会破坏版本语义且绕过 delegate）。
     */
    @Override
    public boolean supportsVersioning() {
        return delegate.supportsVersioning();
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String sessionId, String userId, String stateKey, Class<T> type) {
        return delegate.getVersioned(sessionId, userId, stateKey, type);
    }

    @Override
    public long saveIfVersion(String sessionId, String userId, String stateKey, State state, long expectedVersion) {
        if (state instanceof AgentState agentState) {
            patchAskingContent(agentState);
        }
        return delegate.saveIfVersion(sessionId, userId, stateKey, state, expectedVersion);
    }

    @Override
    public <T extends State> List<T> getList(String sessionId, String userId, String stateKey, Class<T> type) {
        return delegate.getList(sessionId, userId, stateKey, type);
    }

    @Override
    public boolean exists(String sessionId, String stateKey) {
        return delegate.exists(sessionId, stateKey);
    }

    @Override
    public void delete(String sessionId, String stateKey) {
        delegate.delete(sessionId, stateKey);
    }

    @Override
    public java.util.Set<String> listSessionIds(String stateKey) {
        return delegate.listSessionIds(stateKey);
    }

    /**
     * 就地修补 state：最后一条 assistant 消息中 ASKING 的 tool_use 块，content 补为 input 的 JSON 字符串。
     *
     * <p>与 SDK 官方变更语义一致（ReActAgent$CallExecution.applyToolUseBlockReplacements）：
     * Msg 的 content 列表不可变，须用 {@code contextMutable().set(idx, msg.withContent(newBlocks))}
     * 原位替换整个消息对象。非 assistant 消息与无缺失的块不动。
     */
    private void patchAskingContent(AgentState agentState) {
        var context = agentState.contextMutable();
        if (context == null || context.isEmpty()) {
            return;
        }
        // SDK 只看最后一条 assistant 消息的 ASKING 块（askingToolCalls 口径），从尾部向前找
        for (int i = context.size() - 1; i >= 0; i--) {
            var msg = context.get(i);
            if (msg == null || msg.getRole() != io.agentscope.core.message.MsgRole.ASSISTANT) {
                continue;
            }
            var blocks = msg.getContent();
            if (blocks == null || blocks.isEmpty() || blocks.stream().noneMatch(this::needsBackfill)) {
                return;
            }
            var patched = new ArrayList<ContentBlock>(blocks.size());
            boolean changed = false;
            for (var block : blocks) {
                if (needsBackfill(block)) {
                    patched.add(backfill((ToolUseBlock) block));
                    changed = true;
                } else {
                    patched.add(block);
                }
            }
            if (changed) {
                // 照抄 SDK 的原位替换语义：contextMutable().set(idx, msg.withContent(...))
                context.set(i, msg.withContent(patched));
            }
            return;   // 只处理最后一条 assistant 消息
        }
    }

    /** ASKING 且 content 缺失（null 或空白串）的 tool_use 块才需要回填 */
    private boolean needsBackfill(ContentBlock block) {
        return block instanceof ToolUseBlock toolUse
            && toolUse.getState() == ToolCallState.ASKING
            && (toolUse.getContent() == null || toolUse.getContent().isBlank());
    }

    /** 生成 content=input JSON 的修补块（input 为空时保持 null——校验层对空参数短路，无需占位） */
    private ToolUseBlock backfill(ToolUseBlock block) {
        Map<String, Object> input = block.getInput();
        if (input == null || input.isEmpty()) {
            return block;
        }
        try {
            return new ToolUseBlock(block.getId(), block.getName(), input,
                MAPPER.writeValueAsString(input), block.getMetadata(), block.getState());
        } catch (Exception e) {
            // 序列化失败保持原块（fail-soft）：读侧 AgentStateReader 仍有 content 兜底
            return block;
        }
    }
}
