package io.agentmanager.framework.service;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StateDataParserTest {

    private static final String STATE_DATA = """
        {"session_id":"s1","context":[
           {"id":"m1","role":"USER","content":[{"type":"text","text":"hello"}]},
           {"id":"m2","role":"ASSISTANT",
            "content":[{"type":"thinking","thinking":"internal"},{"type":"text","text":"hi"}]},
           {"id":"m3","role":"TOOL","content":[{"type":"tool_result","content":"result"}]}
         ]}
        """;

    @Test
    void findMessagesArrayShouldFindContext() {
        var arr = StateDataParser.findMessagesArray(STATE_DATA);
        assertNotNull(arr);
        assertTrue(arr.isArray());
        assertEquals(3, arr.size());
    }

    @Test
    void findMessagesArrayShouldReturnNullForBlank() {
        assertNull(StateDataParser.findMessagesArray(null));
        assertNull(StateDataParser.findMessagesArray("  "));
    }

    @Test
    void findMessagesArrayShouldSupportLegacyMessagesField() {
        var arr = StateDataParser.findMessagesArray("{\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}");
        assertNotNull(arr);
        assertEquals(1, arr.size());
    }

    @Test
    void extractContentTextShouldJoinTextBlocksAndSkipThinking() {
        var arr = StateDataParser.findMessagesArray(STATE_DATA);
        var assistant = arr.get(1);
        assertEquals("hi", StateDataParser.extractContentText(assistant));
    }

    @Test
    void extractContentTextShouldHandlePlainString() {
        var arr = StateDataParser.findMessagesArray("{\"context\":[{\"role\":\"user\",\"content\":\"plain\"}]}");
        assertEquals("plain", StateDataParser.extractContentText(arr.get(0)));
    }

    @Test
    void extractContentTextShouldHandlePartsFallback() {
        var arr = StateDataParser.findMessagesArray("{\"context\":[{\"role\":\"user\",\"parts\":[{\"text\":\"p1\"}]}]}");
        assertTrue(StateDataParser.extractContentText(arr.get(0)).contains("p1"));
    }

    @Test
    void extractRoleShouldLowercase() {
        var arr = StateDataParser.findMessagesArray(STATE_DATA);
        assertEquals("user", StateDataParser.extractRole(arr.get(0)));
        assertEquals("assistant", StateDataParser.extractRole(arr.get(1)));
    }

    @Test
    void toRoleContentListShouldSkipToolMessages() {
        var arr = StateDataParser.findMessagesArray(STATE_DATA);
        var list = StateDataParser.toRoleContentList(arr);
        assertEquals(2, list.size());
        assertEquals(Map.of("role", "user", "content", "hello"), list.get(0));
        assertEquals(Map.of("role", "assistant", "content", "hi"), list.get(1));
    }

    @Test
    void toRoleContentListShouldExtractToolCalls() {
        var stateData = """
            {"context":[{"role":"assistant","content":[
                {"type":"text","text":"let me run it"},
                {"type":"tool_use","id":"call_abc","name":"execute",
                 "input":{"command":"python3 -c \\"print(6*7)\\"","timeout":5}}
            ]}]}
            """;
        var arr = StateDataParser.findMessagesArray(stateData);
        var list = StateDataParser.toRoleContentList(arr);

        assertEquals(1, list.size());
        @SuppressWarnings("unchecked")
        var calls = (java.util.List<Map<String, Object>>) list.get(0).get("tool_calls");
        assertNotNull(calls);
        assertEquals(1, calls.size());
        assertEquals("call_abc", calls.get(0).get("id"));
        assertEquals("execute", calls.get(0).get("name"));
        assertTrue(calls.get(0).get("input").toString().contains("python3"));
    }

    @Test
    void toRoleContentListShouldHandleNull() {
        assertTrue(StateDataParser.toRoleContentList(null).isEmpty());
    }

    @Test
    void toRoleContentListShouldHandleEmpty() {
        assertTrue(StateDataParser.toRoleContentList(
            StateDataParser.findMessagesArray("{\"context\":[]}")).isEmpty());
    }
}
