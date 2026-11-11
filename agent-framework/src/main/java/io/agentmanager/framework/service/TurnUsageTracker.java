package io.agentmanager.framework.service;

/**
 * 单个执行段（turn）内模型调用用量的累积器（chat-trace-observability-design §2.3）。
 *
 * <p>每个 {@code MODEL_CALL_END} 累加一次（ReAct 多轮 = 多次模型调用），turn 收尾时由
 * {@code TurnFinalizer#emitTurnFinished} 合成 {@code turn_finished} 汇总帧，客户端无需
 * 自己逐帧累加即可拿到「本轮一共用了多少 token」。
 *
 * <p><b>诚实口径</b>：单次 {@code usage} 为 null 的调用（provider 不回用量）按 0 计入
 * 总量、但 {@code model_calls} 独立计数——total 为 0 时据此区分「无模型调用」与
 * 「provider 不回用量」，不伪造数据。
 *
 * <p><b>线程/生命周期</b>：非线程安全，由每个 turn 的订阅回调单线程驱动；实例随 turn
 * 创建（与 {@link TurnToolSummaryTracker} 同级）、是 turn 闭包局部对象，不落任何存储
 * （架构原则二：正确性状态不上共享存储，本对象纯可丢弃累积器）。turn 结束即丢弃。
 */
public final class TurnUsageTracker {

    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private int modelCalls;

    /** MODEL_CALL_END 摄入：usage 为 null 计调用次数、不计总量 */
    public void onModelCallEnd(io.agentscope.core.event.ModelCallEndEvent e) {
        modelCalls++;
        var usage = e.getUsage();
        if (usage == null) {
            return;
        }
        inputTokens += usage.getInputTokens();
        outputTokens += usage.getOutputTokens();
        totalTokens += usage.getTotalTokens();
    }

    /** 本轮输入 token 求和（provider 逐次上报值累加） */
    public long inputTokens() {
        return inputTokens;
    }

    /** 本轮输出 token 求和 */
    public long outputTokens() {
        return outputTokens;
    }

    /** 本轮总 token 求和（provider 报告的 total 逐次累加） */
    public long totalTokens() {
        return totalTokens;
    }

    /** 本轮模型调用次数（含 usage 缺失的调用） */
    public int modelCalls() {
        return modelCalls;
    }
}
