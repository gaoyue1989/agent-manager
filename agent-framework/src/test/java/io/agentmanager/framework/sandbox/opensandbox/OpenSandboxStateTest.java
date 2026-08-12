package io.agentmanager.framework.sandbox.opensandbox;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * 沙箱状态序列化兼容性验证（待确认项 #9）：
 * OpenSandboxState 经 Jackson 序列化 → 持久化到 agent_state 表 → 反序列化恢复。
 * 验证 round-trip 完整性与父类字段（workspaceSpec/snapshot/workspaceRootReady）保留。
 */
class OpenSandboxStateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripShouldPreserveAllFields() throws Exception {
        var state = new OpenSandboxState();
        state.setSandboxId("81aa9874-0f7c-4d6d-8f26-8b85f5daf08d");
        state.setSandboxEndpoint("192.168.31.155:52051/proxy/44772");
        state.setUserId("user-1");
        state.setImage("opensandbox/code-interpreter:v1.1.0");
        state.setCreatedAt(1723456789000L);
        state.setSessionId("tenant:thread-1");
        state.setWorkspaceRootReady(true);

        var json = mapper.writeValueAsString(state);
        var restored = mapper.readValue(json, OpenSandboxState.class);

        assertEquals(state.getSandboxId(), restored.getSandboxId());
        assertEquals(state.getSandboxEndpoint(), restored.getSandboxEndpoint());
        assertEquals(state.getUserId(), restored.getUserId());
        assertEquals(state.getImage(), restored.getImage());
        assertEquals(state.getCreatedAt(), restored.getCreatedAt());
        assertEquals("tenant:thread-1", restored.getSessionId());
        assertTrue(restored.isWorkspaceRootReady());
    }

    @Test
    void serializedJsonShouldContainKeyFields() throws Exception {
        var state = new OpenSandboxState();
        state.setSandboxId("sandbox-abc");
        state.setUserId("user-9");

        var json = mapper.writeValueAsString(state);

        assertTrue(json.contains("sandboxId"));
        assertTrue(json.contains("sandbox-abc"));
        assertTrue(json.contains("userId"));
        assertTrue(json.contains("user-9"));
    }

    @Test
    void deserializeUnknownFieldsShouldNotFail() throws Exception {
        // 兼容性：SandboxState 多态序列化（@JsonTypeInfo Id.NAME），type=类简单名；
        // workspaceSpec 在 JSON 中以 manifest 字段表示（SandboxState 注解映射）
        var json = """
            {"type":"OpenSandboxState","sessionId":null,"snapshot":null,
             "workspaceProjectionHash":null,"workspaceRootReady":false,
             "sandboxId":"sb-1","userId":"u","image":"img:1","createdAt":123,
             "manifest":{"root":"/workspace","entries":{},"environment":{}}}
            """;

        var restored = mapper.readValue(json, OpenSandboxState.class);

        assertEquals("sb-1", restored.getSandboxId());
        assertFalse(restored.isWorkspaceRootReady());
        // workspaceSpec 经 manifest 字段恢复
        assertEquals("/workspace", restored.getWorkspaceSpec().getRoot());
    }

    @Test
    void roundTripShouldPreserveWorkspaceSpecAsManifest() throws Exception {
        var state = new OpenSandboxState();
        var ws = new io.agentscope.harness.agent.sandbox.WorkspaceSpec();
        ws.setRoot("/workspace");
        state.setWorkspaceSpec(ws);

        var json = mapper.writeValueAsString(state);
        var restored = mapper.readValue(json, OpenSandboxState.class);

        assertEquals("/workspace", restored.getWorkspaceSpec().getRoot());
    }

    @Test
    void serializedJsonShouldContainTypeDiscriminator() throws Exception {
        var json = mapper.writeValueAsString(new OpenSandboxState());

        assertTrue(json.contains("\"type\":\"OpenSandboxState\""));
    }

    @Test
    void malformedJsonShouldThrow() {
        assertThrows(Exception.class, () -> mapper.readValue("{invalid", OpenSandboxState.class));
    }
}
