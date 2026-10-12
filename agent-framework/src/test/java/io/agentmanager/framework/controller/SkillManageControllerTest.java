package io.agentmanager.framework.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.agentmanager.framework.service.SkillCatalogService;
import io.agentmanager.framework.service.SkillInjectionService;
import io.agentmanager.framework.service.SkillManageService;

/**
 * SkillManageController 契约测试（MockMvc standalone）：聚焦 Skill 下载端点
 * {@code GET /skills/{name}/download} 的 200（zip + attachment 头）、400（非法名）、
 * 404（技能目录不存在）、500（导出 IO 失败）。
 */
class SkillManageControllerTest {

    private MockMvc mvc;
    private SkillManageService manageService;

    @BeforeEach
    void setUp() {
        manageService = mock(SkillManageService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SkillManageController(
            manageService, mock(SkillCatalogService.class), mock(SkillInjectionService.class))).build();
    }

    @Test
    void downloadShouldReturnZipWithAttachmentHeaders() throws Exception {
        var zip = new byte[] {80, 75, 3, 4};
        when(manageService.exportSkillZip("demo")).thenReturn(Optional.of(zip));

        mvc.perform(get("/skills/demo/download"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/zip"))
            .andExpect(header().string("Content-Disposition",
                "attachment; filename*=UTF-8''demo.zip"))
            .andExpect(content().bytes(zip));
    }

    @Test
    void downloadShouldReturn404WhenSkillDirMissing() throws Exception {
        when(manageService.exportSkillZip("ghost")).thenReturn(Optional.empty());

        mvc.perform(get("/skills/ghost/download"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void downloadShouldRejectInvalidName() throws Exception {
        mvc.perform(get("/skills/bad..name/download"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_name"));
    }

    @Test
    void downloadShouldReturn500OnExportFailure() throws Exception {
        when(manageService.exportSkillZip("boom")).thenThrow(new IOException("disk"));

        mvc.perform(get("/skills/boom/download"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("download_failed"));
    }
}
