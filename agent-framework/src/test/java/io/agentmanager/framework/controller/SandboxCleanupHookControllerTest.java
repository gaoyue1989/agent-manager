package io.agentmanager.framework.controller;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.agentmanager.framework.sandbox.opensandbox.SandboxBindingStore;
import io.agentmanager.framework.sandbox.opensandbox.SandboxLifecycleManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /debug/sandbox-cleanup 测试钩子（issue #293）：条件注册 + retentionDays 区间校验 + fail-soft。
 *
 * <p>slice A（本类）：{@code agent.sandbox.lifecycle.e2e-hooks=true} → 控制器已注册，
 * 覆盖 ① retentionDays 超界 400（IllegalArgumentException 经 GlobalExceptionHandler 转
 * bad_request）与 ③ lifecycle 在位时清理计数回显；② lifecycle 未装配（provider null）
 * 返回 enabled=false 与 GET /debug/sandbox-bindings 同口径，因 @MockBean 注册的
 * SandboxLifecycleManager 在本 slice 上下文常驻、provider 不可能为 null，
 * 以直调用例覆盖（不走 MockMvc，断言方法返回体）。
 */
@WebMvcTest(SandboxCleanupHookController.class)
@TestPropertySource(properties = "agent.sandbox.lifecycle.e2e-hooks=true")
class SandboxCleanupHookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SandboxLifecycleManager lifecycleManager;

    // ---------- ① retentionDays 区间校验（1~365，超界 400 bad_request） ----------

    @Test
    void retentionDaysZeroShouldReturn400() throws Exception {
        mockMvc.perform(post("/debug/sandbox-cleanup").param("retentionDays", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void retentionDaysOverMaxShouldReturn400() throws Exception {
        mockMvc.perform(post("/debug/sandbox-cleanup").param("retentionDays", "366"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void retentionDaysNegativeShouldReturn400() throws Exception {
        mockMvc.perform(post("/debug/sandbox-cleanup").param("retentionDays", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("bad_request"));
    }

    // ---------- ③ lifecycle 在位：清理执行 + 计数回显 ----------

    @Test
    void cleanupShouldReturnCountsWhenLifecyclePresent() throws Exception {
        var store = Mockito.mock(SandboxBindingStore.class);
        when(store.cleanupExpired(Mockito.any(java.time.Instant.class))).thenReturn(3);
        when(store.cleanupEmptyAnchors(Mockito.any(java.time.Instant.class))).thenReturn(2);
        when(lifecycleManager.bindingStore()).thenReturn(store);

        mockMvc.perform(post("/debug/sandbox-cleanup").param("retentionDays", "7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.retention_days").value(7))
            .andExpect(jsonPath("$.bindings_deleted").value(3))
            .andExpect(jsonPath("$.anchors_deleted").value(2));
    }

    // ---------- ② lifecycle 未装配（provider null）：200 enabled=false（直调，见类注释） ----------

    @Test
    @SuppressWarnings("unchecked")
    void cleanupShouldReturnDisabledWhenLifecycleMissing() {
        ObjectProvider<SandboxLifecycleManager> emptyProvider =
            (ObjectProvider<SandboxLifecycleManager>) Mockito.mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);

        var result = new SandboxCleanupHookController(emptyProvider).sandboxCleanup(7);

        assertEquals(Map.of("enabled", false), result);
    }
}
