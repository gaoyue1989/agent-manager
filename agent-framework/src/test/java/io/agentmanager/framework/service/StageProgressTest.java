package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

class StageProgressTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parserExtractsStagesSummaryAndCleanText() {
        String raw = "<stage>正在查询平台服务列表</stage>\n<summary>已查询平台已发布服务</summary>\n\n当前有 2 个服务。";
        var parsed = StageProgressParser.parse(raw);
        assertEquals(List.of("正在查询平台服务列表"), parsed.stages());
        assertEquals("已查询平台已发布服务", parsed.turnSummary());
        assertEquals("当前有 2 个服务。", parsed.cleanText());
    }

    @Test
    void parserDeduplicatesAdjacentIdenticalStages() {
        String raw = "<stage>正在检查配置</stage><stage>正在检查配置</stage><stage>正在验证结果</stage>";
        var parsed = StageProgressParser.parse(raw);
        assertEquals(List.of("正在检查配置", "正在验证结果"), parsed.stages());
        assertEquals("", parsed.cleanText());
    }

    @Test
    void parserFallbackGroupsToolsByCategoryAndMcpServer() {
        assertEquals("正在调用 platform-publisher 处理请求",
            StageProgressParser.fallbackStageForTools(List.of(
                "mcp__platform-publisher__list_services",
                "mcp__platform-publisher__get_platform_defaults")));
        assertEquals("正在查阅并分析相关文件",
            StageProgressParser.fallbackStageForTools(List.of("read_file", "grep_files")));
        assertEquals("正在修改文件并运行验证命令",
            StageProgressParser.fallbackStageForTools(List.of("edit_file", "execute")));
    }

    @Test
    void trackerSplitsCrossChunkStageAndSummaryTags() throws Exception {
        List<Map<String, Object>> emitted = new ArrayList<>();
        var tracker = new StageProgressTracker("sid-1", "rid-1", (type, payload) -> {
            try {
                emitted.add(MAPPER.readValue(payload, Map.class));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, null);

        String[] chunks = {
            "<sta", "ge>正在查询", "平台服务</st", "age>\n<summ", "ary>已查询平台服务</summary>\n\n正文内容"
        };
        StringBuilder visible = new StringBuilder();
        for (String c : chunks) {
            var ev = tracker.process(new TextBlockDeltaEvent("rid-1", "b1", c));
            if (ev instanceof TextBlockDeltaEvent out) {
                visible.append(out.getDelta());
            }
        }
        tracker.process(new TextBlockEndEvent("rid-1", "b1"));

        assertEquals("正文内容", visible.toString());
        assertEquals(2, emitted.size());
        assertEquals("stage_progress", emitted.get(0).get("type"));
        assertEquals("正在查询平台服务", emitted.get(0).get("text"));
        assertEquals("turn_progress_summary", emitted.get(1).get("type"));
        assertEquals("已查询平台服务", emitted.get(1).get("summary"));
    }

    @Test
    void trackerEmitsFallbackStagePerIterationAndTurnSummaryOnAgentEnd() throws Exception {
        List<Map<String, Object>> emitted = new ArrayList<>();
        var tracker = new StageProgressTracker("sid-1", "rid-1", (type, payload) -> {
            try {
                emitted.add(MAPPER.readValue(payload, Map.class));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, null);

        tracker.process(new ModelCallStartEvent("rid-1"));
        tracker.process(new ToolCallStartEvent("rid-1", "c1", "mcp__platform-publisher__list_services"));
        tracker.process(new ToolCallStartEvent("rid-1", "c2", "mcp__platform-publisher__get_platform_defaults"));
        tracker.process(new ModelCallEndEvent("rid-1", null));
        tracker.process(new AgentEndEvent("rid-1"));

        assertEquals(2, emitted.size());
        assertEquals("stage_progress", emitted.get(0).get("type"));
        assertEquals("正在调用 platform-publisher 处理请求", emitted.get(0).get("text"));
        assertEquals("turn_progress_summary", emitted.get(1).get("type"));
        assertEquals("已调用 platform-publisher 处理请求", emitted.get(1).get("summary"));
    }

    @Test
    void middlewareInjectsInstructionOnlyWhenToolsPresentAndIdempotent() {
        var mw = new StageProgressMiddleware();
        var sys = Msg.builder().role(MsgRole.SYSTEM).textContent("你是助手").build();
        var user = Msg.builder().role(MsgRole.USER).textContent("查服务").build();
        var tool = ToolSchema.builder().name("list_services").description("list").build();

        ReasoningInput[] captured = new ReasoningInput[1];
        mw.onReasoning(null, null, new ReasoningInput(List.of(sys, user), List.of(tool), null), in -> {
            captured[0] = in;
            return Flux.empty();
        }).blockLast();

        assertNotNull(captured[0]);
        assertTrue(captured[0].messages().get(0).getTextContent().contains("<stage>"));

        ReasoningInput[] second = new ReasoningInput[1];
        mw.onReasoning(null, null, captured[0], in -> {
            second[0] = in;
            return Flux.empty();
        }).blockLast();
        assertEquals(captured[0], second[0]);

        ReasoningInput[] noTools = new ReasoningInput[1];
        var rawNoTools = new ReasoningInput(List.of(sys, user), List.of(), null);
        mw.onReasoning(null, null, rawNoTools, in -> {
            noTools[0] = in;
            return Flux.empty();
        }).blockLast();
        assertEquals(rawNoTools, noTools[0]);
    }

    @Test
    void stateDataParserStripsTagsAndPopulatesStagesAndSummary() throws Exception {
        String stateJson = """
            {"context": [
              {"role": "USER", "content": [{"type": "text", "text": "查服务"}]},
              {"role": "ASSISTANT", "content": [
                {"type": "text", "text": "<stage>正在查询平台服务</stage>"},
                {"type": "tool_use", "id": "c1", "name": "mcp__platform-publisher__list_services", "input": {}}
              ]},
              {"role": "ASSISTANT", "content": [
                {"type": "text", "text": "<summary>已查询平台已发布服务</summary>\\n\\n服务列表如下。"}
              ]}
            ]}
            """;
        var arr = StateDataParser.findMessagesArray(stateJson);
        var list = StateDataParser.toRoleContentList(arr);
        assertEquals(3, list.size());
        assertEquals("", list.get(1).get("content"));
        assertEquals(List.of("正在查询平台服务"), list.get(1).get("stages"));
        assertEquals("服务列表如下。", list.get(2).get("content"));
        assertEquals("已查询平台已发布服务", list.get(2).get("turn_summary"));
        assertNull(list.get(0).get("stages"));
    }
}
