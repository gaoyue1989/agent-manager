package io.agentmanager.framework.controller;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;

import io.agentmanager.framework.service.SessionEventBus;
import io.agentmanager.framework.service.TurnLeaseGuard;
import reactor.core.publisher.FluxSink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TurnFinalizer 单测（sse-optimization-a1-a5-design §2.4）：钉住 turn 收尾收口后
 * 不得漂移的几条实测教训。
 *
 * <ol>
 *   <li>3 参 endTurn CAS 幂等：两次调用仅一次生效，且返回值暴露「收尾权归属」；</li>
 *   <li>丢锁走 abandon、正常走 closeSession，均先收尾再放锁；</li>
 *   <li>isLost() 必须先于 release() 求值——release 翻转内部状态后时间判据被短路，
 *       次序错了丢锁的 turn 就会误走刷缓冲（closeSession）分支；</li>
 *   <li>stopIfLeaseLost 的终态帧一次性闸门；</li>
 *   <li>abortSetup 四个副作用的固定次序与 error 帧原样透传（两侧工厂字节不等价，
 *       帧构造留在各控制器）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class TurnFinalizerTest {

    private static final String INTERRUPTED_FRAME =
        "{\"type\":\"interrupted\",\"reason\":\"lease_lost\"}";

    @Mock private SessionEventBus eventBus;
    @Mock private TurnLeaseGuard lease;
    @Mock private FluxSink<ServerSentEvent<String>> sink;

    // ===== 3 参 endTurn：CAS 幂等 + 返回值接缝 =====

    @Test
    void endTurnWithGateRunsExactlyOnce() {
        when(lease.isLost()).thenReturn(false);
        var turnEnded = new AtomicBoolean(false);

        assertTrue(TurnFinalizer.endTurn(eventBus, lease, "sid", turnEnded),
            "CAS 首胜应返回 true（收尾权归本次调用）");
        assertFalse(TurnFinalizer.endTurn(eventBus, lease, "sid", turnEnded),
            "重入应返回 false（收尾权已被抢走）");

        verify(eventBus, times(1)).closeSession("sid");
        verify(lease, times(1)).release();
    }

    // ===== 2 参 endTurn：丢锁 abandon / 正常 closeSession，均先收尾再放锁 =====

    @Test
    void endTurnAbandonsFirstWhenLeaseLost() {
        when(lease.isLost()).thenReturn(true);

        TurnFinalizer.endTurn(eventBus, lease, "sid");

        InOrder inOrder = inOrder(eventBus, lease);
        inOrder.verify(eventBus).abandonSession("sid");
        inOrder.verify(lease).release();
        verify(eventBus, never()).closeSession("sid");
    }

    @Test
    void endTurnClosesSessionFirstWhenLeaseHealthy() {
        when(lease.isLost()).thenReturn(false);

        TurnFinalizer.endTurn(eventBus, lease, "sid");

        InOrder inOrder = inOrder(eventBus, lease);
        inOrder.verify(eventBus).closeSession("sid");
        inOrder.verify(lease).release();
        verify(eventBus, never()).abandonSession("sid");
    }

    @Test
    void isLostMustBeEvaluatedBeforeRelease() {
        // 教训注释（TurnFinalizer#endTurn javadoc）：release 之后 released 参与判断、
        // 时间判据被短路。用 Answer 在 release 时翻转 isLost 的返回：若实现先 release
        // 后求值，这里就会误入 closeSession（刷缓冲）分支，本用例直接拦下。
        var released = new AtomicBoolean(false);
        when(lease.isLost()).thenAnswer(inv -> !released.get());
        doAnswer(inv -> {
            released.set(true);
            return null;
        }).when(lease).release();

        TurnFinalizer.endTurn(eventBus, lease, "sid");

        InOrder inOrder = inOrder(lease);
        inOrder.verify(lease).isLost();
        inOrder.verify(lease).release();
        verify(eventBus).abandonSession("sid");
        verify(eventBus, never()).closeSession("sid");
    }

    // ===== stopIfLeaseLost：一次性终态帧闸门 =====

    @Test
    void stopIfLeaseLostEmitsInterruptedFrameExactlyOnce() {
        when(lease.isLost()).thenReturn(true);
        when(lease.tryMarkLostNotified()).thenReturn(true);

        assertTrue(TurnFinalizer.stopIfLeaseLost(eventBus, lease, "sid", sink, "[chat]"),
            "事件已被丢弃，调用方必须直接返回");

        verify(sink).next(argThat(f -> INTERRUPTED_FRAME.equals(f.data())));
        verify(eventBus).abandonSession("sid");
        verify(lease).release();

        // 同一租约的后续事件：闸门已关——只返回 true，不重复发帧/收尾
        when(lease.tryMarkLostNotified()).thenReturn(false);
        assertTrue(TurnFinalizer.stopIfLeaseLost(eventBus, lease, "sid", sink, "[chat]"));
        verify(sink, times(1)).next(any());
        verify(eventBus, times(1)).abandonSession("sid");
        verify(lease, times(1)).release();
    }

    @Test
    void stopIfLeaseLostDoesNothingWhenLeaseHealthy() {
        when(lease.isLost()).thenReturn(false);

        assertFalse(TurnFinalizer.stopIfLeaseLost(eventBus, lease, "sid", sink, "[confirm]"),
            "未丢锁不得拦截事件");
        verifyNoInteractions(sink, eventBus);
        verify(lease, never()).release();
    }

    // ===== interruptedSSE：终态帧字节 =====

    @Test
    void interruptedFrameCarriesExactBytesAndNoId() {
        var frame = TurnFinalizer.interruptedSSE("lease_lost");

        assertEquals(INTERRUPTED_FRAME, frame.data());
        assertNull(frame.id(), "终态帧不落库、不占 seq，不得携带 id 字段");
    }

    // ===== abortSetup：准备段回滚骨架 =====

    @Test
    void abortSetupRunsSideEffectsInFixedOrder() {
        var errorFrame = chatStyleErrorFrame("turn_setup_failed: boom");

        TurnFinalizer.abortSetup(eventBus, lease, sink, "sid", errorFrame);

        InOrder inOrder = inOrder(sink, eventBus, lease);
        inOrder.verify(sink).next(errorFrame);
        inOrder.verify(eventBus).closeSession("sid");
        inOrder.verify(lease).release();
        inOrder.verify(sink).complete();
    }

    @Test
    void abortSetupPassesChatStyleErrorFrameThroughByteIdentical() {
        // chat 侧 errorSSE 是手工字符串拼接，恒定输出 {"type":"error","error":...} 键序；
        // 帧由调用方构造好整帧传入，abortSetup 只透传，不得改写一个字节
        var errorFrame = chatStyleErrorFrame("boom");

        TurnFinalizer.abortSetup(eventBus, lease, sink, "sid", errorFrame);

        verify(sink).next(argThat(f ->
            f == errorFrame && "{\"type\":\"error\",\"error\":\"boom\"}".equals(f.data())));
    }

    @Test
    void abortSetupPassesConfirmStyleErrorFrameThroughByteIdentical() {
        // confirm 侧 errorSSE 走 payload(Map.of(...))：JDK 9+ 不可变 Map 键序受每 JVM
        // 随机 SALT 影响、跨进程非确定——断言与生产同一构造路径现算，不硬编码键序
        var errorFrame = ServerSentEvent.<String>builder()
            .data(AgentEventSseSerializer.payload(Map.of("type", "error", "error", "boom")))
            .build();

        TurnFinalizer.abortSetup(eventBus, lease, sink, "sid", errorFrame);

        verify(sink).next(argThat(f ->
            f == errorFrame && f.data().contains("\"error\":\"boom\"")
                && f.data().contains("\"type\":\"error\"")));
    }

    /** chat 侧 errorSSE 的同式构造（生产实现在 ChatStreamController#errorSSE，私有） */
    private static ServerSentEvent<String> chatStyleErrorFrame(String msg) {
        return ServerSentEvent.<String>builder()
            .data("{\"type\":\"error\",\"error\":" + AgentEventSseSerializer.jsonEsc(msg) + "}")
            .build();
    }
}
