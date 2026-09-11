package io.agentmanager.framework.controller;

import io.agentscope.core.agui.model.RunAgentInput;
import io.agentmanager.framework.service.AguiInterruptStore.AguiInterruptCorruptedException;
import io.agentmanager.framework.service.AguiRunService;
import io.agentmanager.framework.service.AguiRunService.AguiRequestException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * AG-UI 主端点（agui-migration-plan §5.1，Phase 1 新增）。
 *
 * <p>双路由等价：POST /agui/run 与 POST /agui/run/agent/{agentId}/run
 * （PR #2554 后端把 handler 绑定在 {base}/agent/{agentId}/run，CopilotKit 客户端实际
 * 请求路径由 R7 spike 确认后回填 D4 runtimeUrl 取值）。agentId 固定校验 release-agent。
 *
 * <p>响应：正常 → text/event-stream（每帧 data: {AguiEvent JSON}）；
 * 预检失败（输入校验/resume 覆盖率/CAS 消费/interrupt 损坏）→ 4xx/5xx JSON 错误体。
 */
@RestController
public class AguiChatController {

    private final AguiRunService runService;

    public AguiChatController(AguiRunService runService) {
        this.runService = runService;
    }

    /** 主端点（等价双路由）：预检 → SSE 流 */
    @PostMapping(
        value = {"/agui/run", "/agui/run/agent/{agentId}/run"},
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> run(
        @PathVariable(required = false) String agentId,
        @RequestBody RunAgentInput input) {
        var prepared = runService.prepare(input, agentId);
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(runService.stream(prepared));
    }

    /** stop：中断该 thread 的在跑执行段（HarnessAgent.interrupt(RuntimeContext)） */
    @PostMapping("/agui/run/agent/{agentId}/stop/{threadId}")
    public Map<String, Object> stop(
        @PathVariable String agentId, @PathVariable String threadId) {
        return runService.stop(threadId);
    }

    /**
     * connect：CopilotKit 客户端重连在跑流的探测端点（R7 spike 实测本地也会调用）。
     * 本平台为无状态单次流——断连即 agent.interrupt，不存在可重连的运行中流；
     * 恒返回 204（客户端约定：connect 模式收到 204 视为"无流可重连"，干净空转）。
     */
    @PostMapping("/agui/run/agent/{agentId}/connect")
    public ResponseEntity<Void> connect(@PathVariable String agentId) {
        return ResponseEntity.noContent().build();
    }

    /** 预检失败（4xx 语义：输入校验/resume 覆盖率/CAS）→ JSON 错误体 */
    @ExceptionHandler(AguiRequestException.class)
    public ResponseEntity<Map<String, Object>> handleRequestError(AguiRequestException e) {
        return jsonError(e.status(), e.getMessage());
    }

    /** agui_interrupt 元数据损坏（R10 fail-fast）→ 500 JSON 错误体 */
    @ExceptionHandler(AguiInterruptCorruptedException.class)
    public ResponseEntity<Map<String, Object>> handleCorruptInterrupt(AguiInterruptCorruptedException e) {
        return jsonError(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage());
    }

    /** 请求体反序列化失败（threadId 等必填字段缺失/类型不符）→ 统一 detail JSON（对齐预检错误形态） */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(
        org.springframework.http.converter.HttpMessageNotReadableException e) {
        return jsonError(HttpStatus.BAD_REQUEST.value(),
            "invalid RunAgentInput body: " + (e.getMessage() != null ? e.getMessage() : ""));
    }

    private static ResponseEntity<Map<String, Object>> jsonError(int status, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("detail", message != null ? message : "");
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }
}
