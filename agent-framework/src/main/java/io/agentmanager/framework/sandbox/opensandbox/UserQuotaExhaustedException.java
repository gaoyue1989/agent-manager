package io.agentmanager.framework.sandbox.opensandbox;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 单用户沙箱配额满且全部占用均有「执行中迹象」时抛出（sandbox-pool-opensandbox-design §4.2 ⑤ / §4.6）。
 *
 * <p>两个消费面，载荷同构（评审轮二 P0：前端 ChatPane 配额错误卡两入口）：
 * <ul>
 *   <li><b>chat 预检</b>：{@code ChatStreamController} 抛出 → {@code GlobalExceptionHandler}
 *       转 HTTP 409，body = {@link #payload409()}（bindings 带 {@code state}：
 *       executing | confirm_holding，前端据此区分「等保留窗/可停止」两种建议）；</li>
 *   <li><b>exec 时撞配额</b>（A2A / 预检放行后竞态兜底）：执行流错误回调检测本类型
 *       → SSE error 帧 = {@link #sseFrameJson()}（bindings 带 {@code turn_running} 布尔）。</li>
 * </ul>
 */
public class UserQuotaExhaustedException extends RuntimeException {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 配额上限（payload 展示用） */
    private final int quota;
    /** 占用明细（异常构造时快照，含 state 判定所需信息） */
    private final List<BindingView> bindings;

    /**
     * 占用明细行（§4.3 409 body 的 bindings 元素）。
     *
     * @param sessionId           占用会话（A2A 链路为真实用户 id）
     * @param expiresAt           绑定行 expires_at（服务端绝对 TTL 缓存，ISO-8601）
     * @param turnRunning         双事实源 busy（turn_lease 持有 OR updated_at 新鲜）
     * @param confirmHolding      该会话存在未决 confirm_context 行（保留窗语义，优先于 turnRunning 展示）
     * @param confirmHoldExpiresAt confirmHolding 时的保留窗截止（pending.created_at + hold 窗口；可空）
     */
    public record BindingView(String sessionId, String expiresAt, boolean turnRunning,
                              boolean confirmHolding, String confirmHoldExpiresAt) {
        /** §4.3 state 字段值：确认等待中优先（前端按态给不同操作建议） */
        public String state() {
            return confirmHolding ? "confirm_holding" : "executing";
        }
    }

    public UserQuotaExhaustedException(int quota, List<BindingView> bindings) {
        super("沙箱配额已满（" + bindings.size() + "/" + quota + "）且均不可复用");
        this.quota = quota;
        this.bindings = bindings;
    }

    public int quota() {
        return quota;
    }

    public List<BindingView> bindings() {
        return bindings;
    }

    /**
     * chat 预检 409 响应体（§4.3）：{@code {code, message, quota,
     * bindings:[{session_id, expires_at, state, confirm_hold_expires_at}]}}。
     * 组装失败降级为仅含 code 的最小载荷（不阻塞错误响应）。
     */
    public Map<String, Object> payload409() {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("code", "user_quota_exhausted");
        m.put("message", getMessage());
        m.put("quota", quota);
        m.put("bindings", bindings.stream().map(b -> {
            var bMap = new java.util.LinkedHashMap<String, Object>();
            bMap.put("session_id", b.sessionId());
            bMap.put("expires_at", b.expiresAt());
            bMap.put("state", b.state());
            if (b.confirmHoldExpiresAt() != null) {
                bMap.put("confirm_hold_expires_at", b.confirmHoldExpiresAt());
            }
            return bMap;
        }).toList());
        return m;
    }

    /**
     * SSE error 帧载荷字符串（§4.6）：{@code {"type":"error","code":"user_quota_exhausted",
     * "quota":N,"message":...,"bindings":[{session_id, expires_at, turn_running}]}}。
     * type=error 保证既有前端错误分支路由；code 供 quotaFromSseError 识别（§6.3）。
     */
    public String sseFrameJson() {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("type", "error");
        m.put("code", "user_quota_exhausted");
        m.put("quota", quota);
        m.put("message", getMessage());
        m.put("bindings", bindings.stream().map(b -> Map.<String, Object>of(
            "session_id", b.sessionId() == null ? "" : b.sessionId(),
            "expires_at", b.expiresAt() == null ? "" : b.expiresAt(),
            "turn_running", b.turnRunning())).toList());
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            // JSON 组装失败兜底：退化为纯文本 error 帧（配额错误卡退化为通用报错，可用性优先）
            try {
                return "{\"type\":\"error\",\"error\":" + MAPPER.writeValueAsString(getMessage()) + "}";
            } catch (Exception ignored) {
                return "{\"type\":\"error\",\"error\":\"user_quota_exhausted\"}";
            }
        }
    }
}
