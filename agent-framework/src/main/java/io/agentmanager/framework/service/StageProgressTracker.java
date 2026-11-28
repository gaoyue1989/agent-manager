package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import io.agentmanager.framework.controller.AgentEventSseSerializer;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;

public final class StageProgressTracker {

    private static final int MAX_TAG_CONTENT_LEN = 160;

    private final String sessionId;
    private final String replyId;
    private final TurnToolSummaryTracker.Emitter emitter;
    private final UnaryOperator<String> nameNormalizer;

    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder tagBuffer = new StringBuilder();
    private final List<String> stages = new ArrayList<>();
    private final List<String> iterTools = new ArrayList<>();

    private String activeTag;
    private boolean stripLeadingNewlines;
    private boolean stageEmittedInIter;
    private boolean summaryEmitted;
    private String lastTextReplyId;
    private String lastTextBlockId;

    public StageProgressTracker(String sessionId, String replyId,
                                TurnToolSummaryTracker.Emitter emitter,
                                UnaryOperator<String> nameNormalizer) {
        this.sessionId = sessionId;
        this.replyId = replyId;
        this.emitter = emitter;
        this.nameNormalizer = nameNormalizer != null ? nameNormalizer : UnaryOperator.identity();
    }

    public AgentEvent process(AgentEvent event) {
        if (event == null || AgentEventSseSerializer.isRemoteForwarded(event)) {
            return event;
        }
        if (event instanceof ModelCallStartEvent) {
            flushPendingAsText();
            stageEmittedInIter = false;
            iterTools.clear();
            return event;
        }
        if (event instanceof ToolCallStartEvent tc) {
            if (tc.getToolCallName() != null && !tc.getToolCallName().isBlank()) {
                iterTools.add(nameNormalizer.apply(tc.getToolCallName()));
            }
            return event;
        }
        if (event instanceof TextBlockDeltaEvent delta) {
            if (isDirectReturn(delta)) {
                return event;
            }
            lastTextReplyId = delta.getReplyId();
            lastTextBlockId = delta.getBlockId();
            String raw = delta.getDelta();
            if (raw == null || raw.isEmpty()) {
                return event;
            }
            if (activeTag == null && pending.isEmpty() && !stripLeadingNewlines && !raw.contains("<")) {
                return event;
            }
            String visible = consumeText(raw);
            if (visible.isEmpty()) {
                return null;
            }
            if (visible.equals(raw)) {
                return event;
            }
            return new TextBlockDeltaEvent(
                delta.getId(), delta.getCreatedAt(), delta.getReplyId(), delta.getBlockId(), visible);
        }
        if (event instanceof TextBlockEndEvent) {
            flushPendingAsText();
            return event;
        }
        if (event instanceof ModelCallEndEvent) {
            flushPendingAsText();
            if (!iterTools.isEmpty() && !stageEmittedInIter) {
                String fallback = StageProgressParser.fallbackStageForTools(iterTools);
                emitStage(fallback);
            }
            iterTools.clear();
            return event;
        }
        if (event instanceof AgentEndEvent) {
            flushPendingAsText();
            if (!summaryEmitted && !stages.isEmpty()) {
                emitSummary(StageProgressParser.fallbackTurnSummary(stages));
            }
            return event;
        }
        return event;
    }

    public List<String> stages() {
        return List.copyOf(stages);
    }

    private String consumeText(String chunk) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < chunk.length()) {
            if (activeTag == null) {
                pending.append(chunk.charAt(i++));
                String p = pending.toString();
                if (p.endsWith(StageProgressParser.STAGE_OPEN)) {
                    appendVisible(out, p.substring(0, p.length() - StageProgressParser.STAGE_OPEN.length()));
                    pending.setLength(0);
                    activeTag = "stage";
                    tagBuffer.setLength(0);
                } else if (p.endsWith(StageProgressParser.SUMMARY_OPEN)) {
                    appendVisible(out, p.substring(0, p.length() - StageProgressParser.SUMMARY_OPEN.length()));
                    pending.setLength(0);
                    activeTag = "summary";
                    tagBuffer.setLength(0);
                } else {
                    int keep = trailingPrefixLength(p);
                    if (p.length() > keep) {
                        appendVisible(out, p.substring(0, p.length() - keep));
                        pending.delete(0, p.length() - keep);
                    }
                }
            } else {
                tagBuffer.append(chunk.charAt(i++));
                String closeTag = "stage".equals(activeTag)
                    ? StageProgressParser.STAGE_CLOSE
                    : StageProgressParser.SUMMARY_CLOSE;
                String content = tagBuffer.toString();
                if (content.endsWith(closeTag)) {
                    String inner = content.substring(0, content.length() - closeTag.length());
                    if ("stage".equals(activeTag)) {
                        emitStage(inner);
                    } else {
                        emitSummary(inner);
                    }
                    activeTag = null;
                    tagBuffer.setLength(0);
                    stripLeadingNewlines = true;
                } else if (tagBuffer.length() > MAX_TAG_CONTENT_LEN) {
                    String openTag = "stage".equals(activeTag)
                        ? StageProgressParser.STAGE_OPEN
                        : StageProgressParser.SUMMARY_OPEN;
                    appendVisible(out, openTag + content);
                    activeTag = null;
                    tagBuffer.setLength(0);
                }
            }
        }
        return out.toString();
    }

    private void appendVisible(StringBuilder out, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (stripLeadingNewlines) {
            int idx = 0;
            while (idx < text.length() && (text.charAt(idx) == '\n' || text.charAt(idx) == '\r')) {
                idx++;
            }
            if (idx == text.length()) {
                return;
            }
            stripLeadingNewlines = false;
            out.append(text, idx, text.length());
            return;
        }
        out.append(text);
    }

    private void flushPendingAsText() {
        if (activeTag != null) {
            String inner = tagBuffer.toString();
            if ("stage".equals(activeTag)) {
                emitStage(inner);
            } else {
                emitSummary(inner);
            }
            activeTag = null;
            tagBuffer.setLength(0);
        }
        if (!pending.isEmpty()) {
            StringBuilder out = new StringBuilder();
            appendVisible(out, pending.toString());
            pending.setLength(0);
            if (!out.isEmpty()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", "TEXT_BLOCK_DELTA");
                payload.put("session_id", sessionId);
                payload.put("delta", out.toString());
                if (lastTextReplyId != null) {
                    payload.put("replyId", lastTextReplyId);
                }
                if (lastTextBlockId != null) {
                    payload.put("blockId", lastTextBlockId);
                }
                emitter.emit("TEXT_BLOCK_DELTA", AgentEventSseSerializer.payload(payload));
            }
        }
    }

    private void emitStage(String rawText) {
        String normalized = StageProgressParser.normalizeLine(rawText);
        if (normalized.isEmpty()) {
            return;
        }
        stageEmittedInIter = true;
        if (!stages.isEmpty() && stages.get(stages.size() - 1).equals(normalized)) {
            return;
        }
        stages.add(normalized);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "stage_progress");
        payload.put("stageIndex", stages.size());
        payload.put("text", normalized);
        payload.put("replyId", replyId);
        emitter.emit("stage_progress", AgentEventSseSerializer.payload(payload));
    }

    private void emitSummary(String rawSummary) {
        String normalized = StageProgressParser.normalizeLine(rawSummary);
        if (normalized.isEmpty()) {
            return;
        }
        summaryEmitted = true;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "turn_progress_summary");
        payload.put("summary", normalized);
        payload.put("stageCount", stages.size());
        payload.put("replyId", replyId);
        emitter.emit("turn_progress_summary", AgentEventSseSerializer.payload(payload));
    }

    private static boolean isDirectReturn(TextBlockDeltaEvent delta) {
        Map<String, Object> meta = delta.getMetadata();
        return meta != null && io.agentscope.core.message.GenerateReason.TOOL_RETURN_DIRECT.name()
            .equals(meta.get(AgentEvent.METADATA_GENERATE_REASON));
    }

    private static int trailingPrefixLength(String s) {
        int max = Math.min(s.length(), Math.max(
            StageProgressParser.STAGE_OPEN.length(),
            StageProgressParser.SUMMARY_OPEN.length()) - 1);
        for (int len = max; len >= 1; len--) {
            String tail = s.substring(s.length() - len);
            if (StageProgressParser.STAGE_OPEN.startsWith(tail)
                    || StageProgressParser.SUMMARY_OPEN.startsWith(tail)) {
                return len;
            }
        }
        return 0;
    }
}
