package io.agentmanager.framework.service;

import io.agentscope.core.agent.RuntimeContext;

/**
 * 会话/用户标识解析：把 RuntimeContext 的运行时键翻译成**业务规范键**。
 *
 * <p>为什么需要翻译：Channel 链路（/threads/chat 经 ChatUiChannel 网关）下 SDK 网关会
 * 改写 RuntimeContext——sessionId 被替换为按 canonicalKey 派生的 gw-hash
 * （<b>同进程所有 peer 共享，不是任何会话的规范 id</b>），前端 sessionId 落在 userId。
 * 下游若原样记录 sessionId/userId，会把"运行时路由键"当成"业务键"存进可观测数据：
 * <ul>
 *   <li>{@code GET /threads/{sid}/llm-calls} 按前端 sid 查恒为空，且各会话记录串进同一个
 *       gw-hash 桶（issue #44）；</li>
 *   <li>span 的 {@code agentscope.session.id} 全进程同值、{@code agentscope.user.id} 是
 *       peer 而非用户，按会话/用户过滤 trace 失效且跨会话互相可见。</li>
 * </ul>
 *
 * <p>规范键的权威来源是 session_user 表（session_id = 前端/A2A 调用方 sid、user_id = 真实用户；
 * ChatStreamController / A2AController / ConfirmController 在会话开始前 upsert，LLM 调用时必已存在）。
 * 与 {@code McpUserContextMiddleware} 的 session→user 反查同一先例。
 *
 * <p>逐链路结果（sid → canonical session / real user）：
 * <table border="1">
 *   <tr><th>链路</th><th>ctx.sessionId</th><th>ctx.userId</th><th>解析结果</th></tr>
 *   <tr><td>Channel（/threads/chat）</td><td>gw-hash（共享）</td><td>peer = 前端 sid</td>
 *       <td>sid = 前端 sid；user = 该 sid 登记行的 user_id</td></tr>
 *   <tr><td>A2A</td><td>调用方 sid（规范）</td><td>真实用户</td><td>sid 原值；user 原值</td></tr>
 *   <tr><td>direct invoke</td><td>{tenant}__{tid}</td><td>vendorKey</td>
 *       <td>未登记 → 原值（读取侧靠 LLMLogger 前缀归一回退命中）</td></tr>
 * </table>
 *
 * <p><b>每 turn 至多一次解析</b>：结果 memo 进 RuntimeContext（网关每次 run/runStream 新建 ctx，
 * 即 memo 生命周期 = 一个 turn），下游多个中间件共用同一份，不会在事件级热路径上反复查库。
 *
 * <p>DB 不可用时 {@code lookup} 一律返回 null（fail-soft），退化为 RuntimeContext 原值——
 * 可观测性降级但不阻断对话。
 */
public class SessionKeyResolver {

    private final SessionUserStore sessionUserStore;

    public SessionKeyResolver(SessionUserStore sessionUserStore) {
        this.sessionUserStore = sessionUserStore;
    }

    /** 规范会话 id（= session_user.session_id）；无 store 时退化为 RuntimeContext 原值，无法解析时 null */
    public String canonicalSessionId(RuntimeContext ctx) {
        return resolve(ctx).sessionId();
    }

    /** 真实用户 id；Channel 链路下为 session_user 登记行的 user_id（而非网关 peer） */
    public String realUserId(RuntimeContext ctx) {
        return resolve(ctx).userId();
    }

    private Resolved resolve(RuntimeContext ctx) {
        if (ctx == null) {
            return Resolved.EMPTY;
        }
        // memo 键用 Resolved 自身类型（RuntimeContext.put(Class<T>, T) 要求 key/value 同类型，
        // 与 McpUserContextMiddleware 用 McpMeta.class 的写法一致，不会与其他条目撞键）
        if (ctx.get(Resolved.class) != null) {
            return ctx.get(Resolved.class);
        }
        var resolved = compute(ctx);
        ctx.put(Resolved.class, resolved);
        return resolved;
    }

    private Resolved compute(RuntimeContext ctx) {
        var sid = trimToNull(ctx.getSessionId());
        var uid = trimToNull(ctx.getUserId());
        if (sid == null) {
            // 无 sessionId 的形态：userId 已是业务键，保持与旧实现一致
            return new Resolved(uid, uid);
        }
        // 先 sessionId 后 userId（与 SessionModelMiddleware.candidateKeys 同序）：
        // A2A 链路 sessionId 权威，Channel 链路 sessionId 是共享 gw-hash 必然落空再取 peer
        var sidUser = lookup(sid);
        if (sidUser != null) {
            return new Resolved(sid, sidUser);
        }
        if (uid != null) {
            var peerUser = lookup(uid);
            if (peerUser != null) {
                return new Resolved(uid, peerUser);
            }
        }
        return new Resolved(sid, uid);
    }

    /** session_user 命中返回该会话的 user_id；未登记/查库失败返回 null（按"未登记"处理） */
    private String lookup(String key) {
        if (sessionUserStore == null) {
            return null;
        }
        try {
            var userId = sessionUserStore.findUserIdBySession(key);
            return userId == null || userId.isBlank() ? null : userId;
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 一次 turn 内固定的规范键（memo 载体） */
    private record Resolved(String sessionId, String userId) {
        private static final Resolved EMPTY = new Resolved(null, null);
    }
}
