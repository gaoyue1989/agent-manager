package io.agentmanager.framework.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import io.agentmanager.framework.service.FileAssetStore;
import io.agentmanager.framework.service.storage.FileStorage;

/** OAF 部署包校验工具测试（check_oaf_package） */
class OafPackageToolsTest {

    private final OafPackageTools tools = new OafPackageTools();

    private static final String VALID_MD = """
        ---
        name: "weather-agent"
        vendorKey: "acme"
        agentKey: "weather-agent"
        version: "1.0.0"
        description: "天气查询助手"
        author: "@acme"
        license: "MIT"
        ---
        # Weather Agent
        正文内容
        """;

    @Test
    void validPackagePasses() {
        var result = tools.checkOafPackage(VALID_MD);
        assertTrue(result.contains("\"valid\":true"), "合法包应通过: " + result);
    }

    @Test
    void missingRequiredFieldsReported() {
        var md = """
            ---
            name: "weather-agent"
            version: "1.0.0"
            ---
            body
            """;
        var result = tools.checkOafPackage(md);
        assertFalse(result.contains("\"valid\":true"), "缺字段应不通过: " + result);
        assertTrue(result.contains("vendorKey"), "应指出缺失 vendorKey: " + result);
        assertTrue(result.contains("agentKey"), "应指出缺失 agentKey: " + result);
        assertTrue(result.contains("license"), "应指出缺失 license: " + result);
    }

    @Test
    void invalidKebabCaseReported() {
        var md = VALID_MD.replace("vendorKey: \"acme\"", "vendorKey: \"Acme Corp\"");
        var result = tools.checkOafPackage(md);
        assertFalse(result.contains("\"valid\":true"), "非 kebab-case 应不通过: " + result);
        assertTrue(result.contains("kebab-case"), "应提示 kebab-case: " + result);
    }

    @Test
    void invalidSemverReported() {
        var md = VALID_MD.replace("version: \"1.0.0\"", "version: \"v1\"");
        var result = tools.checkOafPackage(md);
        assertFalse(result.contains("\"valid\":true"), "非 semver 应不通过: " + result);
        assertTrue(result.contains("semver"), "应提示 semver: " + result);
    }

    @Test
    void noFrontmatterReported() {
        var result = tools.checkOafPackage("# 只有正文没有 frontmatter");
        assertFalse(result.contains("\"valid\":true"), "无 frontmatter 应不通过: " + result);
        assertTrue(result.contains("frontmatter"), "应提示 frontmatter 缺失: " + result);
    }

    @Test
    void blankInputReported() {
        var result = tools.checkOafPackage("  ");
        assertFalse(result.contains("\"valid\":true"), "空内容应不通过: " + result);
    }

    @Test
    void createOafZipRejectsInvalidFrontmatter() {
        var store = org.mockito.Mockito.mock(FileAssetStore.class);
        var storage = org.mockito.Mockito.mock(FileStorage.class);
        var wired = new OafPackageTools(store, storage,
            io.agentmanager.framework.controller.FileControllerTest.testProps());
        var result = wired.createOafZip("bad.zip", "---\nname: x\n---\nbody", null);
        assertTrue(result.contains("校验未通过"), "缺字段应拒绝打包: " + result);
    }

    @Test
    void createOafZipBuildsZipAndRegisters() throws Exception {
        var store = org.mockito.Mockito.mock(FileAssetStore.class);
        var storage = org.mockito.Mockito.mock(FileStorage.class);
        var wired = new OafPackageTools(store, storage,
            io.agentmanager.framework.controller.FileControllerTest.testProps());
        var result = wired.createOafZip("weather-agent.zip",
            "---\nname: weather-agent\nvendorKey: acme\nagentKey: weather-agent\nversion: 1.0.0\n"
                + "description: 天气助手\nauthor: @acme\nlicense: MIT\n---\n正文",
            "[{\"path\":\"skills/help.md\",\"content\":\"# Help\"}]");
        assertTrue(result.contains("\"file_id\""), "应打包登记成功: " + result);
        assertTrue(result.contains("application/zip"), "mime 应为 zip: " + result);
        assertTrue(result.contains("content_base64"), "应返回 base64: " + result);
        org.mockito.Mockito.verify(storage).write(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.eq("application/zip"));
        org.mockito.Mockito.verify(store).insert(org.mockito.ArgumentMatchers.any(FileAssetStore.FileAsset.class));
        // 解包验证内容
        var b64 = java.util.regex.Matcher.quoteReplacement(result);
        var m = java.util.regex.Pattern.compile("\"content_base64\":\"([^\"]+)\"").matcher(result);
        assertTrue(m.find(), "应能提取 base64");
        var bytes = java.util.Base64.getDecoder().decode(m.group(1));
        try (var zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            var names = new java.util.ArrayList<String>();
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                names.add(entry.getName());
            }
            assertTrue(names.contains("AGENTS.md"), "zip 应含 AGENTS.md: " + names);
            assertTrue(names.contains("skills/help.md"), "zip 应含附加文件: " + names);
        }
    }
}