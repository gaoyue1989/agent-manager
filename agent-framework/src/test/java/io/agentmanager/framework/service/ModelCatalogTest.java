package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentmanager.framework.config.AgentManagerProperties;

/**
 * ModelCatalog 单元测试：系统+托管目录、实例缓存/TTL/失效、选择校验、fail-soft 回落。
 * 覆盖 docs/session-model-switch-design.md §3/§4 的目录语义。
 */
class ModelCatalogTest {

    private ModelConfigStore store;
    private ModelCatalog catalog;

    @BeforeEach
    void setUp() {
        store = mock(ModelConfigStore.class);
        catalog = new ModelCatalog(store, props("qwen3-32b"));
    }

    // ===== options =====

    @Test
    void optionsShouldPutSystemModelFirstThenEnabledManaged() {
        when(store.listEnabled()).thenReturn(List.of(cfg("m1", "DeepSeek-V3", true)));

        var options = catalog.options();

        assertEquals(2, options.size());
        var system = options.get(0);
        assertEquals(ModelCatalog.SYSTEM_MODEL_ID, system.id());
        assertTrue(system.isDefault());
        assertEquals("system", system.source());
        assertEquals("qwen3-32b", system.modelId());
        var managed = options.get(1);
        assertEquals("m1", managed.id());
        assertEquals("DeepSeek-V3", managed.name());
        assertFalse(managed.isDefault());
        assertEquals("managed", managed.source());
    }

    @Test
    void optionsShouldIncludeDisabledForManagementView() {
        when(store.listAll()).thenReturn(List.of(
            cfg("on", "On", true), cfg("off", "Off", false)));

        var options = catalog.options(true);

        assertEquals(3, options.size()); // system + 2 managed（含 disabled）
        assertTrue(options.get(2).enabled() == false);
        verify(store, never()).listEnabled();
    }

    // ===== resolve =====

    @Test
    void resolveShouldBuildManagedModelAndCacheIt() {
        when(store.findById("m1")).thenReturn(Optional.of(cfg("m1", "M1", true)));

        var first = catalog.resolve("m1");
        var second = catalog.resolve("m1");

        assertTrue(first.isPresent());
        assertEquals("model-m1", first.get().getModelName());
        assertTrue(second.isPresent());
        // 命中缓存：只查库一次
        verify(store, times(1)).findById("m1");
    }

    @Test
    void resolveShouldReloadAfterTtlExpiry() {
        var zeroTtlCatalog = new ModelCatalog(store, props("qwen3-32b"), 0L);
        when(store.findById("m1")).thenReturn(Optional.of(cfg("m1", "M1", true)));

        zeroTtlCatalog.resolve("m1");
        zeroTtlCatalog.resolve("m1");

        verify(store, times(2)).findById("m1");
    }

    @Test
    void resolveShouldReturnEmptyForSystemSelection() {
        assertTrue(catalog.resolve("").isEmpty());
        assertTrue(catalog.resolve(null).isEmpty());
        assertTrue(catalog.resolve("system").isEmpty());
        verify(store, never()).findById(anyString());
    }

    @Test
    void resolveShouldReturnEmptyForUnknownOrDisabled() {
        when(store.findById("gone")).thenReturn(Optional.empty());
        when(store.findById("off")).thenReturn(Optional.of(cfg("off", "Off", false)));

        assertTrue(catalog.resolve("gone").isEmpty());
        assertTrue(catalog.resolve("off").isEmpty());
    }

    @Test
    void resolveShouldFailSoftWhenStoreThrows() {
        when(store.findById("boom")).thenThrow(new IllegalStateException("db down"));

        assertTrue(catalog.resolve("boom").isEmpty());
    }

    @Test
    void invalidateShouldForceReload() {
        when(store.findById("m1")).thenReturn(Optional.of(cfg("m1", "M1", true)));

        catalog.resolve("m1");
        catalog.invalidate("m1");
        catalog.resolve("m1");

        verify(store, times(2)).findById("m1");
    }

    // ===== validateSelectable =====

    @Test
    void validateSelectableShouldAcceptSystemAndEnabledManaged() {
        assertNull(catalog.validateSelectable(null));
        assertNull(catalog.validateSelectable(""));
        assertNull(catalog.validateSelectable("system"));
        when(store.findById("m1")).thenReturn(Optional.of(cfg("m1", "M1", true)));
        assertNull(catalog.validateSelectable("m1"));
    }

    @Test
    void validateSelectableShouldReportUnknownAndDisabled() {
        when(store.findById("gone")).thenReturn(Optional.empty());
        when(store.findById("off")).thenReturn(Optional.of(cfg("off", "Off", false)));

        assertEquals("unknown_model", catalog.validateSelectable("gone"));
        assertEquals("model_disabled", catalog.validateSelectable("off"));
    }

    // ===== 系统模型 =====

    @Test
    void systemModelShouldBeBuiltLazilyAndExposeModelName() {
        var model = catalog.systemModel();

        assertNotNull(model);
        assertEquals("qwen3-32b", model.getModelName());
        assertEquals("qwen3-32b", catalog.systemModelId());
        assertEquals("system", ModelCatalog.SYSTEM_MODEL_ID);
    }

    @Test
    void isSystemSelectionShouldCoverBlankAndSystemId() {
        assertTrue(ModelCatalog.isSystemSelection(null));
        assertTrue(ModelCatalog.isSystemSelection(""));
        assertTrue(ModelCatalog.isSystemSelection("  "));
        assertTrue(ModelCatalog.isSystemSelection("system"));
        assertFalse(ModelCatalog.isSystemSelection("m1"));
    }

    @Test
    void modelForTestShouldReturnSystemOrAnyManagedConfig() {
        when(store.findById("off")).thenReturn(Optional.of(cfg("off", "Off", false)));

        assertTrue(catalog.modelForTest("system").isPresent());
        // disabled 配置也可测试连接（用于排查连不通的配置）
        assertTrue(catalog.modelForTest("off").isPresent());
        when(store.findById("gone")).thenReturn(Optional.empty());
        assertTrue(catalog.modelForTest("gone").isEmpty());
    }

    // ===== helpers =====

    private static ModelConfigStore.ModelConfig cfg(String id, String name, boolean enabled) {
        return new ModelConfigStore.ModelConfig(id, name, "openai", "model-" + id,
            "http://vllm:1/v1", "sk-managed", 0.3, 8192, 60, false, 0, enabled, null, null);
    }

    private static AgentManagerProperties props(String systemModelId) {
        return new AgentManagerProperties(
            new AgentManagerProperties.LLMConfig("sk-sys", systemModelId, "http://sys:1/v1",
                "openai", 0.3, 16384, 120, false, 0),
            new AgentManagerProperties.ServerConfig("0.0.0.0", 8100),
            new AgentManagerProperties.CheckpointConfig("jdbc:mysql://localhost:3306/cp", "u", "p", "cp"),
            "/config", "",
            new AgentManagerProperties.CleanupConfig(30, 60, 20, 30, 7),
            new AgentManagerProperties.FileConfig(true, 20, 20, "image/*", 5, 15, 50, true, 7,
                "local", "/data/files", "", "", "", "agent-files", ""),
            new AgentManagerProperties.SseConfig(20, 5, 256, 300),
            AgentManagerProperties.HarnessConfig.defaults());
    }
}
