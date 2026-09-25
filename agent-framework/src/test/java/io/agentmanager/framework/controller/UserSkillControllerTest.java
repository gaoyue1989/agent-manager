package io.agentmanager.framework.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.agentmanager.framework.config.SandboxConfig;
import io.agentmanager.framework.service.SandboxRuntime;
import io.agentmanager.framework.service.UserSkillService;
import io.agentmanager.framework.service.UserSkillService.SyncOutcome;
import io.agentmanager.framework.service.UserSkillService.UserSkillContent;
import io.agentmanager.framework.service.UserSkillService.UserSkillIndex;
import io.agentmanager.framework.service.UserSkillService.UserSkillSummary;
import io.agentmanager.framework.service.UserSkillService.UserSkillUser;
import io.agentmanager.framework.service.UserSkillService.WriteOutcome;

/**
 * UserSkillController 契约测试（MockMvc standalone）：校验状态码与响应体字段，
 * 覆盖 400（非法 userId/name/file）、404（两侧都没有 / 无个人覆盖）、413（内容超限）、
 * 500（KV 写/读失败必须显式上报）与成功路径；含沙箱档（SANDBOX_ENABLED=true）的成功提示文案。
 */
class UserSkillControllerTest {

    private static final String USER = "e2e-u-1";
    private static final String NAME = "demo-a";

    private MockMvc mvc;
    private UserSkillService service;

    @BeforeEach
    void setUp() {
        service = mock(UserSkillService.class);
        mvc = MockMvcBuilders.standaloneSetup(new UserSkillController(service, sandboxRuntime(false))).build();
    }

    /** 沙箱档控制器：成功提示必须说明“只写 KV、不会回注容器” */
    private MockMvc sandboxMvc() {
        return MockMvcBuilders.standaloneSetup(new UserSkillController(service, sandboxRuntime(true))).build();
    }

    private static SandboxRuntime sandboxRuntime(boolean enabled) {
        return new SandboxRuntime(new SandboxConfig(enabled, "img", 60, 1024, 1, List.of("/entry.sh"),
            java.time.Duration.ofMillis(100), true, true, 900, null), enabled);
    }

    // ---------- 用户索引 ----------

    @Test
    void listUsersShouldReturnIndex() throws Exception {
        when(service.listUsers()).thenReturn(new UserSkillIndex(
            List.of(new UserSkillUser(USER, 2, "2026-09-23 10:00:00")), false));

        mvc.perform(get("/skills/users"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.users[0].userId").value(USER))
            .andExpect(jsonPath("$.users[0].skillCount").value(2))
            .andExpect(jsonPath("$.truncated").doesNotExist());
    }

    /** 索引触顶必须显式暴露 truncated（不静默返回子集） */
    @Test
    void listUsersShouldExposeTruncatedFlag() throws Exception {
        when(service.listUsers()).thenReturn(new UserSkillIndex(List.of(), true));

        mvc.perform(get("/skills/users"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.truncated").value(true));
    }

    /** 索引查询失败（DB/SQL 不可用）→ 500：不得降级成 200 + 空列表（那看起来像“没有用户有个人技能”） */
    @Test
    void listUsersShouldReturn500WhenIndexQueryFails() throws Exception {
        doThrow(new IllegalStateException("用户技能索引查询失败: db down")).when(service).listUsers();

        mvc.perform(get("/skills/users"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("index_failed"));
    }

    // ---------- 列表 / 读取 ----------

    @Test
    void listSkillsShouldRejectInvalidUserId() throws Exception {
        mvc.perform(get("/skills/users/.hidden"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_user_id"));
    }

    @Test
    void listSkillsShouldReturnSummaries() throws Exception {
        when(service.listSkills(USER)).thenReturn(List.of(
            new UserSkillSummary(NAME, List.of("SKILL.md"), 12L, 3L, true, true)));
        when(service.listTombstones(USER)).thenReturn(List.of(
            new UserSkillService.UserSkillTombstone("gone-skill", "2026-09-23 12:00:00")));

        mvc.perform(get("/skills/users/" + USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(USER))
            .andExpect(jsonPath("$.skills[0].name").value(NAME))
            .andExpect(jsonPath("$.skills[0].hasPackageBaseline").value(true))
            .andExpect(jsonPath("$.skills[0].adminOverride").value(true))
            .andExpect(jsonPath("$.skills[0].version").value(3))
            // 已删除但标记仍在的技能必须单独暴露（列表里看不到它，但回写会跳过同名技能）
            .andExpect(jsonPath("$.tombstones[0].name").value("gone-skill"))
            .andExpect(jsonPath("$.tombstones[0].deletedAt").value("2026-09-23 12:00:00"));
    }

    @Test
    void getSkillShouldReturnUserOverrideView() throws Exception {
        when(service.readSkill(USER, NAME, null)).thenReturn(Optional.of(
            new UserSkillContent(USER, NAME, "# override", "user", true, 4L, List.of("SKILL.md"), true)));

        mvc.perform(get("/skills/users/" + USER + "/" + NAME))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.file").value("SKILL.md"))
            .andExpect(jsonPath("$.source").value("user"))
            .andExpect(jsonPath("$.hasUserOverride").value(true))
            .andExpect(jsonPath("$.userOverrideExists").value(true))
            .andExpect(jsonPath("$.content").value("# override"))
            .andExpect(jsonPath("$.version").value(4));
    }

    /** 资源文件读取：$.file 回显请求的相对路径，内容原样透传 */
    @Test
    void getSkillShouldReturnResourceFileView() throws Exception {
        when(service.readSkill(USER, NAME, "scripts/hello.sh")).thenReturn(Optional.of(
            new UserSkillContent(USER, NAME, "echo hi", "user", true, 5L,
                List.of("SKILL.md", "scripts/hello.sh"), true)));

        mvc.perform(get("/skills/users/" + USER + "/" + NAME).param("file", "scripts/hello.sh"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.file").value("scripts/hello.sh"))
            .andExpect(jsonPath("$.content").value("echo hi"))
            .andExpect(jsonPath("$.files[1]").value("scripts/hello.sh"));
    }

    /** 包内基线回落视图：source/hasUserOverride/version 与 files 同源 */
    @Test
    void getSkillShouldReturnPackageBaselineView() throws Exception {
        when(service.readSkill(USER, NAME, null)).thenReturn(Optional.of(
            new UserSkillContent(USER, NAME, "# package", "package", false, 0L,
                List.of("SKILL.md", "scripts/hello.sh"), false)));

        mvc.perform(get("/skills/users/" + USER + "/" + NAME))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.file").value("SKILL.md"))
            .andExpect(jsonPath("$.source").value("package"))
            .andExpect(jsonPath("$.hasUserOverride").value(false))
            .andExpect(jsonPath("$.userOverrideExists").value(false))
            .andExpect(jsonPath("$.version").value(0))
            .andExpect(jsonPath("$.content").value("# package"))
            .andExpect(jsonPath("$.files[1]").value("scripts/hello.sh"));
    }

    /** KV 读取失败 → 500（不能把“存储不可用”静默报成 404） */
    @Test
    void getSkillShouldReturn500WhenKvReadFails() throws Exception {
        doThrow(new IllegalStateException("KV 不可用")).when(service).readSkill(USER, NAME, null);

        mvc.perform(get("/skills/users/" + USER + "/" + NAME))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("read_failed"));
    }

    /** 列表枚举失败 → 500（不能返回“该用户没有个人技能”的降级结果） */
    @Test
    void listSkillsShouldReturn500WhenEnumerationFails() throws Exception {
        doThrow(new IllegalStateException("枚举技能文件失败")).when(service).listSkills(USER);

        mvc.perform(get("/skills/users/" + USER))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("list_failed"));
    }

    @Test
    void getSkillShouldReturn404WhenNeitherSideExists() throws Exception {
        when(service.readSkill(USER, NAME, null)).thenReturn(Optional.empty());

        mvc.perform(get("/skills/users/" + USER + "/" + NAME))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void getSkillShouldRejectInvalidNameAndFilePath() throws Exception {
        mvc.perform(get("/skills/users/" + USER + "/a..b"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_name"));

        mvc.perform(get("/skills/users/" + USER + "/" + NAME).param("file", "../escape.md"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_file_path"));
    }

    // ---------- 写入 ----------

    @Test
    void putSkillShouldReturnCreatedAction() throws Exception {
        when(service.writeSkill(USER, NAME, "# new")).thenReturn(new WriteOutcome("created", 1L));

        mvc.perform(put("/skills/users/" + USER + "/" + NAME)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"# new\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.action").value("created"))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("下轮会话生效")));
    }

    /** 沙箱档：成功提示不得再承诺“下轮会话生效”（管理面写入不会回注容器），且要说明写侧栅栏的代价 */
    @Test
    void putSkillShouldWarnInSandboxMode() throws Exception {
        when(service.writeSkill(USER, NAME, "# new")).thenReturn(new WriteOutcome("created", 1L));

        sandboxMvc().perform(put("/skills/users/" + USER + "/" + NAME)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"# new\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("SANDBOX_ENABLED=true"),
                org.hamcrest.Matchers.containsString("管理面写入栅栏"),
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("下轮会话生效")))));
    }

    @Test
    void putSkillShouldRejectEmptyContentAndBadIdentifiers() throws Exception {
        mvc.perform(put("/skills/users/" + USER + "/" + NAME)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("empty_content"));

        mvc.perform(put("/skills/users/.bad/" + NAME)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_user_id"));
    }

    @Test
    void putSkillShouldReturn413WhenContentTooLarge() throws Exception {
        doThrow(new UserSkillService.ContentTooLargeException("SKILL.md 内容超过 100KB 限制"))
            .when(service).writeSkill(USER, NAME, "big");

        mvc.perform(put("/skills/users/" + USER + "/" + NAME)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"big\"}"))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.error").value("content_too_large"));
    }

    @Test
    void putSkillShouldReturn500WhenKvWriteFails() throws Exception {
        doThrow(new IllegalStateException("KV 不可用")).when(service).writeSkill(USER, NAME, "x");

        mvc.perform(put("/skills/users/" + USER + "/" + NAME)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"x\"}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("write_failed"));
    }

    // ---------- 删除 ----------

    @Test
    void deleteSkillShouldReturnDeletedFileCount() throws Exception {
        when(service.deleteSkill(USER, NAME)).thenReturn(Optional.of(2));
        when(service.hasPackageBaseline(NAME)).thenReturn(true);

        mvc.perform(delete("/skills/users/" + USER + "/" + NAME))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deletedFiles").value(2))
            .andExpect(jsonPath("$.hasPackageBaseline").value(true))
            // 删除标记无 TTL：后果与清除方式必须随响应下发（否则“重建了却不落库”无从解释）
            .andExpect(jsonPath("$.tombstone.name").value(NAME))
            .andExpect(jsonPath("$.tombstone.clearHint").value(org.hamcrest.Matchers.containsString("清除标记")))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不会被回写落库")))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("回落包内基线")));
    }

    /** 包内无同名技能：提示必须说清“技能已消失”，不能一律说“回落包内基线” */
    @Test
    void deleteSkillShouldReportSkillGoneWhenNoPackageBaseline() throws Exception {
        when(service.deleteSkill(USER, NAME)).thenReturn(Optional.of(1));
        when(service.hasPackageBaseline(NAME)).thenReturn(false);

        mvc.perform(delete("/skills/users/" + USER + "/" + NAME))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasPackageBaseline").value(false))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("该技能已消失")));
    }

    /** 沙箱档删除提示：说明容器内副本在换代前仍可见 */
    @Test
    void deleteSkillShouldWarnInSandboxMode() throws Exception {
        when(service.deleteSkill(USER, NAME)).thenReturn(Optional.of(1));
        when(service.hasPackageBaseline(NAME)).thenReturn(true);

        sandboxMvc().perform(delete("/skills/users/" + USER + "/" + NAME))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("删除标记"),
                org.hamcrest.Matchers.containsString("容器换代"))));
    }

    @Test
    void deleteSkillShouldReturn404WhenNoOverride() throws Exception {
        when(service.deleteSkill(USER, NAME)).thenReturn(Optional.empty());

        mvc.perform(delete("/skills/users/" + USER + "/" + NAME))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void deleteSkillShouldReturn400ForInvalidIdentifierAnd500ForFailure() throws Exception {
        mvc.perform(delete("/skills/users/" + USER + "/a..b"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_name"));

        doThrow(new IllegalArgumentException("非法")).when(service).deleteSkill(USER, NAME);
        mvc.perform(delete("/skills/users/" + USER + "/" + NAME))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));

        // 同一方法二次打桩必须用 doThrow（when(...).thenThrow 会重放上一桩的异常）
        doThrow(new IllegalStateException("KV 不可用")).when(service).deleteSkill(USER, NAME);
        mvc.perform(delete("/skills/users/" + USER + "/" + NAME))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("delete_failed"));
    }

    // ---------- 从包内下发 ----------

    @Test
    void syncFromPackageShouldReturnFiles() throws Exception {
        when(service.syncFromPackage(USER, NAME))
            .thenReturn(Optional.of(new SyncOutcome(List.of("SKILL.md", "scripts/hello.sh"), List.of())));

        mvc.perform(post("/skills/users/" + USER + "/" + NAME + "/sync-from-package"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.files[0]").value("SKILL.md"))
            .andExpect(jsonPath("$.files[1]").value("scripts/hello.sh"))
            .andExpect(jsonPath("$.skipped").isEmpty());
    }

    /** 非 UTF-8/二进制文件被显式跳过时必须出现在 skipped 字段（不能静默变形下发） */
    @Test
    void syncFromPackageShouldReportSkippedFiles() throws Exception {
        when(service.syncFromPackage(USER, NAME))
            .thenReturn(Optional.of(new SyncOutcome(List.of("SKILL.md"), List.of("logo.png"))));

        mvc.perform(post("/skills/users/" + USER + "/" + NAME + "/sync-from-package"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.skipped[0]").value("logo.png"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("logo.png")));
    }

    @Test
    void syncFromPackageShouldReturn404WhenPackageSkillMissing() throws Exception {
        when(service.syncFromPackage(USER, NAME)).thenReturn(Optional.empty());

        mvc.perform(post("/skills/users/" + USER + "/" + NAME + "/sync-from-package"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void syncFromPackageShouldMapBadIdentifierAndFailureStatus() throws Exception {
        doThrow(new IllegalArgumentException("缺少 SKILL.md")).when(service).syncFromPackage(USER, NAME);
        mvc.perform(post("/skills/users/" + USER + "/" + NAME + "/sync-from-package"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("sync_failed"));

        doThrow(new UserSkillService.ContentTooLargeException("文件超限"))
            .when(service).syncFromPackage(USER, NAME);
        mvc.perform(post("/skills/users/" + USER + "/" + NAME + "/sync-from-package"))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.error").value("content_too_large"));

        doThrow(new IllegalStateException("KV 不可用")).when(service).syncFromPackage(USER, NAME);
        mvc.perform(post("/skills/users/" + USER + "/" + NAME + "/sync-from-package"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("sync_failed"));
    }

    // ---------- 路由消歧 ----------

    /**
     * {@code /skills/users/{userId}} 与 {@code /skills/{name}/content} 在 userId 恰为 "content" 时
     * 同时匹配，由更具体的后者命中（SkillManageController 的包内技能内容视图）。
     * 单个控制器 standaloneSetup 无法验证该行为，故同时注册两个控制器把消歧口径钉进 CI。
     */
    @Test
    void contentPathShouldHitPackageSkillControllerNotUserSkillIndex() throws Exception {
        var manageService = mock(io.agentmanager.framework.service.SkillManageService.class);
        when(manageService.readSkillContent("users"))
            .thenThrow(new IllegalArgumentException("Skill 'users' 的 SKILL.md 不存在"));
        var bothMvc = MockMvcBuilders.standaloneSetup(
            new UserSkillController(service, sandboxRuntime(false)),
            new SkillManageController(manageService,
                mock(io.agentmanager.framework.service.SkillCatalogService.class),
                mock(io.agentmanager.framework.service.SkillInjectionService.class))).build();

        bothMvc.perform(get("/skills/users/content"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("not_found"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("users")));

        // 不得落到用户技能索引上（那会把 userId="content" 当用户处理）
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).listSkills("content");
    }
}
