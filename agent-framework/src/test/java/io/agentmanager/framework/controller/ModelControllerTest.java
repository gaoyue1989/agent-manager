package io.agentmanager.framework.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.service.ModelCatalog;
import io.agentmanager.framework.service.ModelConfigStore;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import reactor.core.publisher.Flux;

/**
 * ModelController 契约测试（docs/session-model-switch-design.md §4.1）：
 * CRUD 错误词表、api_key 掩码、系统模型只读、连接测试成败路径。
 */
class ModelControllerTest {

    private ModelConfigStore store;
    private ModelCatalog catalog;
    private ModelController controller;

    @BeforeEach
    void setUp() {
        store = mock(ModelConfigStore.class);
        catalog = mock(ModelCatalog.class);
        controller = new ModelController(store, catalog);
    }

    // ===== list / get =====

    @Test
    void listShouldReturnDefaultAndOptions() {
        when(catalog.options(false)).thenReturn(List.of(
            new ModelCatalog.ModelOption("system", "qwen3-32b（系统）", "openai", "qwen3-32b", true, "system", true)));

        var body = controller.listModels(false);

        assertEquals("system", body.get("default_model"));
        assertEquals(1, ((List<?>) body.get("models")).size());
    }

    @Test
    void listShouldIncludeDisabledModelsForManagementView() {
        when(catalog.options(true)).thenReturn(List.of(
            new ModelCatalog.ModelOption("m1", "M1", "openai", "m-1", false, "managed", false)));

        var body = controller.listModels(true);

        assertEquals(1, ((List<?>) body.get("models")).size());
        verify(catalog).options(true);
    }

    @Test
    void getShouldMaskApiKeyForManagedModel() {
        when(store.findById("m1")).thenReturn(Optional.of(cfg("m1", "M1", "sk-1234567890abcd", true)));

        var resp = controller.getModel("m1");

        assertEquals(200, resp.getStatusCode().value());
        var body = resp.getBody();
        assertNotNull(body);
        assertEquals("sk-***abcd", body.get("api_key_masked"));
        assertFalse(body.containsValue("sk-1234567890abcd"));
        assertEquals(false, body.get("is_default"));
    }

    @Test
    void getShouldReturn404ForUnknown() {
        when(store.findById("gone")).thenReturn(Optional.empty());

        var resp = controller.getModel("gone");

        assertEquals(404, resp.getStatusCode().value());
        assertEquals("model_not_found", resp.getBody().get("error"));
    }

    @Test
    void getSystemShouldBeReadOnlyView() {
        when(catalog.systemModelId()).thenReturn("qwen3-32b");
        when(catalog.systemLlmConfig()).thenReturn(llm("sk-system-key-1234"));

        var resp = controller.getModel("system");

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(true, resp.getBody().get("read_only"));
        assertEquals("system", resp.getBody().get("source"));
        assertEquals("sk-***1234", resp.getBody().get("api_key_masked"));
    }

    // ===== create =====

    @Test
    void createShouldRejectMissingRequiredFields() {
        var resp = controller.createModel(new ModelController.UpsertRequest(null, null, "m", null,
            null, null, null, null, null, null, null));

        assertEquals(400, resp.getStatusCode().value());
        assertEquals("invalid_config", resp.getBody().get("error"));
        verify(store, never()).insert(any());
    }

    @Test
    void createShouldRejectDuplicateName() {
        when(store.findByName("M1")).thenReturn(Optional.of(cfg("other", "M1", null, true)));

        var resp = controller.createModel(new ModelController.UpsertRequest("M1", null, "m", "http://x/v1",
            null, null, null, null, null, null, null));

        assertEquals(400, resp.getStatusCode().value());
        assertEquals("duplicate_name", resp.getBody().get("error"));
        verify(store, never()).insert(any());
    }

    @Test
    void createShouldInsertWithDefaultsAndMaskKey() {
        when(store.findByName("M9")).thenReturn(Optional.empty());

        var resp = controller.createModel(new ModelController.UpsertRequest("M9", null, "m9", "http://x/v1",
            "sk-1234567890abcd", null, null, null, null, null, null));

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(resp.getBody().get("id"));
        assertEquals("sk-***abcd", resp.getBody().get("api_key_masked"));
        assertEquals("openai", resp.getBody().get("provider"));
        assertEquals(16384, resp.getBody().get("max_tokens"));
        assertEquals(true, resp.getBody().get("enabled"));

        var saved = org.mockito.ArgumentCaptor.forClass(ModelConfigStore.ModelConfig.class);
        verify(store).insert(saved.capture());
        assertEquals("sk-1234567890abcd", saved.getValue().apiKey());
        verify(catalog).invalidate((String) resp.getBody().get("id"));
    }

    // ===== patch =====

    @Test
    void patchShouldRejectSystemModel() {
        var resp = controller.updateModel("system", new ModelController.UpsertRequest("X", null, null, null,
            null, null, null, null, null, null, null));

        assertEquals(400, resp.getStatusCode().value());
        assertEquals("system_model_readonly", resp.getBody().get("error"));
    }

    @Test
    void patchShouldReturn404ForUnknown() {
        when(store.findById("gone")).thenReturn(Optional.empty());

        var resp = controller.updateModel("gone", new ModelController.UpsertRequest("X", null, null, null,
            null, null, null, null, null, null, null));

        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void patchShouldKeepApiKeyWhenAbsentAndClearWhenBlank() {
        when(store.findById("m1")).thenReturn(Optional.of(cfg("m1", "M1", "sk-keep-me-1234", true)));

        // apiKey 缺省 = 不变
        controller.updateModel("m1", new ModelController.UpsertRequest(null, null, null, null,
            null, null, null, null, null, null, null));
        var keep = org.mockito.ArgumentCaptor.forClass(ModelConfigStore.ModelConfig.class);
        verify(store, org.mockito.Mockito.times(1)).update(keep.capture());
        assertEquals("sk-keep-me-1234", keep.getValue().apiKey());

        // apiKey 空串 = 清除（DB NULL = 回落系统密钥）
        controller.updateModel("m1", new ModelController.UpsertRequest(null, null, null, null,
            "", null, null, null, null, null, null));
        var clear = org.mockito.ArgumentCaptor.forClass(ModelConfigStore.ModelConfig.class);
        verify(store, org.mockito.Mockito.times(2)).update(clear.capture());
        assertNull(clear.getAllValues().get(1).apiKey());
    }

    @Test
    void patchShouldRejectDuplicateNameOnRename() {
        when(store.findById("m1")).thenReturn(Optional.of(cfg("m1", "OldName", null, true)));
        when(store.findByName("NewName")).thenReturn(Optional.of(cfg("m2", "NewName", null, true)));

        var resp = controller.updateModel("m1", new ModelController.UpsertRequest("NewName", null, null, null,
            null, null, null, null, null, null, null));

        assertEquals(400, resp.getStatusCode().value());
        assertEquals("duplicate_name", resp.getBody().get("error"));
        verify(store, never()).update(any());
    }

    // ===== delete =====

    @Test
    void deleteShouldRejectSystemModel() {
        var resp = controller.deleteModel("system");

        assertEquals(400, resp.getStatusCode().value());
        assertEquals("system_model_readonly", resp.getBody().get("error"));
    }

    @Test
    void deleteShouldReturn404WhenAbsent() {
        when(store.deleteById("gone")).thenReturn(false);

        var resp = controller.deleteModel("gone");

        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void deleteShouldInvalidateCatalogCache() {
        when(store.deleteById("m1")).thenReturn(true);

        var resp = controller.deleteModel("m1");

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(true, resp.getBody().get("deleted"));
        verify(catalog).invalidate("m1");
    }

    // ===== test（连接测试） =====

    @Test
    void testShouldReturn404WhenModelMissing() {
        when(catalog.modelForTest("gone")).thenReturn(Optional.empty());

        var resp = controller.testModel("gone");

        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void testShouldReportOkWithReply() {
        var model = mock(Model.class);
        when(model.stream(anyList(), anyList(), any())).thenReturn(Flux.just(
            ChatResponse.builder().id("r1")
                .content(List.of(TextBlock.builder().text("pong").build()))
                .finishReason("stop").build()));
        when(catalog.modelForTest("m1")).thenReturn(Optional.of(model));

        var resp = controller.testModel("m1");

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(true, resp.getBody().get("ok"));
        assertEquals("pong", resp.getBody().get("reply"));
        assertNotNull(resp.getBody().get("latency_ms"));
    }

    @Test
    void testShouldReportFailureWith502() {
        var model = mock(Model.class);
        when(model.stream(anyList(), anyList(), any()))
            .thenReturn(Flux.error(new RuntimeException("connection refused")));
        when(catalog.modelForTest("m1")).thenReturn(Optional.of(model));

        var resp = controller.testModel("m1");

        assertEquals(502, resp.getStatusCode().value());
        assertEquals("model_test_failed", resp.getBody().get("error"));
        assertEquals(false, resp.getBody().get("ok"));
    }

    // ===== 掩码 =====

    @Test
    void maskKeyShouldKeepHeadTailAndHideMiddle() {
        assertNull(ModelController.maskKey(null));
        assertNull(ModelController.maskKey("  "));
        assertEquals("***", ModelController.maskKey("short"));
        assertEquals("sk-***abcd", ModelController.maskKey("sk-1234567890abcd"));
        assertTrue(ModelController.maskKey("sk-abcdef123456").startsWith("sk-"));
    }

    // ===== helpers =====

    private static ModelConfigStore.ModelConfig cfg(String id, String name, String apiKey, boolean enabled) {
        return new ModelConfigStore.ModelConfig(id, name, "openai", "model-" + id,
            "http://vllm:1/v1", apiKey, 0.3, 8192, 60, false, 0, enabled,
            Instant.parse("2026-09-24T00:00:00Z"), Instant.parse("2026-09-24T00:00:00Z"));
    }

    private static AgentManagerProperties.LLMConfig llm(String apiKey) {
        return new AgentManagerProperties.LLMConfig(apiKey, "qwen3-32b", "http://sys:1/v1",
            "openai", 0.3, 16384, 120, false, 0);
    }
}
