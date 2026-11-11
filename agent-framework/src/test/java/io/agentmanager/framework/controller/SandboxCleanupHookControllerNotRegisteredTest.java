package io.agentmanager.framework.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /debug/sandbox-cleanup 条件注册验证（issue #293）：不设
 * {@code agent.sandbox.lifecycle.e2e-hooks}（application.yml 默认 false）时控制器不装配，
 * POST 得 404——生产路径零无鉴权写端点的回归钉。
 */
@WebMvcTest(SandboxCleanupHookController.class)
class SandboxCleanupHookControllerNotRegisteredTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void endpointShouldBeAbsentWhenHookDisabledByDefault() throws Exception {
        mockMvc.perform(post("/debug/sandbox-cleanup").param("retentionDays", "7"))
            .andExpect(status().isNotFound());
    }
}
