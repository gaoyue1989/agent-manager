package io.agentmanager.framework.sandbox.opensandbox;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

/**
 * 沙箱链路追踪包装器：在 OpenSandboxClient 的 create/resume/delete 操作前后创建 OTel span。
 *
 * <p>沙箱操作在 HarnessAgent 的 Mono.using 中执行（middleware 链外），
 * OtelTracingMiddleware 无法覆盖。本类通过装饰器模式补充这部分 trace。
 *
 * <p>注意：仅覆盖 SandboxClient 接口暴露的 create/resume/delete 三个操作；
 * 沙箱 release 路径的 stop 由 SandboxManager 直接调用 sandbox.stop()（绕过本类），
 * 暂无 span（见风险 #9，后续在 WorkspaceSyncService.syncBack() 处埋点补充）。
 *
 * <p>OTEL_TRACES_EXPORTER=none 时 GlobalOpenTelemetry 返回 no-op tracer，零开销。
 */
public class TracingSandboxClient implements SandboxClient<OpenSandboxClientOptions> {

    private static final String INSTRUMENTATION_NAME = "io.agentscope.sandbox";
    private final OpenSandboxClient delegate;

    public TracingSandboxClient(OpenSandboxClient delegate) {
        this.delegate = delegate;
    }

    private Tracer getTracer() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    @Override
    public Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec,
                          OpenSandboxClientOptions options) {
        Span span = getTracer().spanBuilder("sandbox.create")
                .setAttribute("sandbox.image", options.getImage() != null ? options.getImage() : "")
                .startSpan();
        try {
            Sandbox result = delegate.create(workspaceSpec, snapshotSpec, options);
            span.setAttribute("sandbox.id", sandboxIdOf(result.getState()));
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    public Sandbox resume(SandboxState state) {
        Span span = getTracer().spanBuilder("sandbox.resume")
                .setAttribute("sandbox.id", sandboxIdOf(state))
                .startSpan();
        try {
            Sandbox result = delegate.resume(state);
            span.setStatus(StatusCode.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    @Override
    public void delete(Sandbox sandbox) {
        Span span = getTracer().spanBuilder("sandbox.delete")
                .setAttribute("sandbox.id", sandboxIdOf(sandbox.getState()))
                .startSpan();
        try {
            delegate.delete(sandbox);
            span.setStatus(StatusCode.OK);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /** sandboxId 仅存在于 OpenSandboxState（基类 SandboxState 无此字段） */
    private String sandboxIdOf(SandboxState state) {
        if (state instanceof OpenSandboxState osbState && osbState.getSandboxId() != null) {
            return osbState.getSandboxId();
        }
        return "";
    }

    @Override
    public String serializeState(SandboxState state) {
        return delegate.serializeState(state);
    }

    @Override
    public SandboxState deserializeState(String serialized) {
        return delegate.deserializeState(serialized);
    }
}