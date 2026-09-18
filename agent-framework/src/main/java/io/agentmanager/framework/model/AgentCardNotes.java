package io.agentmanager.framework.model;

/**
 * Agent 注册卡公共声明（/.well-known/agent-card.json 与 A2AServerConfig 卡片共用）。
 *
 * <p>背景：HITL（permission ask）改造后，A2A (message/send) 通道的 ask 工具挂起态
 * 只存在于 harness checkpoint，不落平台 confirm_context（putConfirmContext 仅覆盖
 * Channel/threads 链路），A2A 客户端没有任何批准途径，且同会话后续请求会持续
 * 报 IllegalStateException。对外声明 A2A 通道不支持 ask 工具，变更类操作引导至
 * 平台 HTTP threads 链路。
 */
public final class AgentCardNotes {

    private AgentCardNotes() {}

    public static final String A2A_CHANNEL_LIMITATION =
        "【A2A 通道限制】message/send 不支持需人工确认（ask）的变更类工具"
        + "（publish_service/update_service_env/republish_service/unpublish_service/delete_service）："
        + "调用会挂起且无法经 A2A 批准，同会话后续请求也会失败。"
        + "变更类操作请使用平台 HTTP 链路：POST /threads/chat + POST /threads/{sessionId}/confirm-stream。";

    /** 拼接卡片描述（description 可为 null，与 A2AServerConfig 现有空值处理保持一致） */
    public static String withA2aLimitation(String description) {
        var base = description != null ? description : "";
        return base.isEmpty() ? A2A_CHANNEL_LIMITATION : base + " " + A2A_CHANNEL_LIMITATION;
    }
}
