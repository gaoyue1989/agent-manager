package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * ToolSummaryGenerator 测试：把「🔧 write_file 完成」这类无信息量输出，
 * 变成「创建 output/create-ai-ppt.js 408行」「执行 cd ... && node ...」的关键逻辑。
 *
 * <p>参数键以 SDK 实际注册名为准（javap / sources 核对）：文件类 path、Shell command、技能 skillId。
 */
class ToolSummaryGeneratorTest {

    // ===== 用户示例形态 =====

    @Test
    void writeFileShouldShowPathAndLineCount() {
        var args = "{\"path\":\"output/create-ai-ppt.js\",\"content\":\"line1\\nline2\\nline3\"}";
        assertEquals("创建 output/create-ai-ppt.js 3行", ToolSummaryGenerator.callSummary("write_file", args));
    }

    @Test
    void singleLineFileShouldCountOneLine() {
        var args = "{\"path\":\"a.txt\",\"content\":\"only\\n\"}";
        assertEquals("创建 a.txt 1行", ToolSummaryGenerator.callSummary("write_file", args));
    }

    @Test
    void executeShouldShowCommand() {
        var args = "{\"command\":\"cd /workspace && node output/create-ai-ppt.js\"}";
        assertEquals("执行 cd /workspace && node output/create-ai-ppt.js",
            ToolSummaryGenerator.callSummary("execute", args));
    }

    @Test
    void windowsCommandShouldBePreserved() {
        var args = "{\"command\":\"cd /d C:\\\\ws && .venv\\\\Scripts\\\\python.exe main.py\"}";
        var summary = ToolSummaryGenerator.callSummary("execute", args);
        assertTrue(summary.startsWith("执行 "), summary);
        assertTrue(summary.contains("python.exe"), summary);
    }

    @Test
    void loadSkillShouldShowSkillId() {
        var args = "{\"skillId\":\"create-ppt\",\"path\":\"SKILL.md\"}";
        assertEquals("加载技能 create-ppt", ToolSummaryGenerator.callSummary("load_skill_through_path", args));
    }

    // ===== 其余内置工具 =====

    @Test
    void readAndEditShouldShowPath() {
        assertEquals("读取 output/a.js", ToolSummaryGenerator.callSummary("read_file", "{\"path\":\"output/a.js\"}"));
        assertEquals("编辑 output/a.js", ToolSummaryGenerator.callSummary("edit_file", "{\"path\":\"output/a.js\"}"));
        assertEquals("列出目录 output", ToolSummaryGenerator.callSummary("list_files", "{\"path\":\"output\"}"));
    }

    @Test
    void searchToolsShouldShowPattern() {
        assertEquals("搜索文件 **/*.java", ToolSummaryGenerator.callSummary("glob_files", "{\"pattern\":\"**/*.java\"}"));
        assertEquals("搜索内容 TODO", ToolSummaryGenerator.callSummary("grep_files", "{\"pattern\":\"TODO\"}"));
    }

    @Test
    void memoryToolsShouldShowQuery() {
        assertEquals("回忆 用户偏好", ToolSummaryGenerator.callSummary("memory_search", "{\"query\":\"用户偏好\"}"));
        assertEquals("记住 用户喜欢简洁", ToolSummaryGenerator.callSummary("memory_save", "{\"content\":\"用户喜欢简洁\"}"));
    }

    @Test
    void workspacePrefixShouldBeStripped() {
        // 用户视角不需要看到容器内的绝对路径前缀
        assertEquals("读取 a.js", ToolSummaryGenerator.callSummary("read_file", "{\"path\":\"/workspace/a.js\"}"));
    }

    @Test
    void longPathShouldCollapse() {
        var args = "{\"path\":\"/workspace/very/long/nested/dir/structure/that/keeps/going/and/going/file.js\"}";
        var summary = ToolSummaryGenerator.callSummary("read_file", args);
        assertTrue(summary.startsWith("读取 …/"), summary);
        assertTrue(summary.endsWith("file.js"), summary);
    }

    // ===== 兜底与健壮性 =====

    @Test
    void unknownToolShouldFallBackToNameAndFirstShortArg() {
        // MCP 工具：取末段名 + 首个短标量参数
        var args = "{\"k8sName\":\"my-svc\"}";
        assertEquals("publish_service my-svc",
            ToolSummaryGenerator.callSummary("mcp__oaf__publish_service", args));
    }

    @Test
    void unknownToolWithoutArgsShouldShowFriendlyNameOnly() {
        assertEquals("publish_service", ToolSummaryGenerator.callSummary("mcp__oaf__publish_service", "{}"));
    }

    @Test
    void malformedArgsShouldNotThrow() {
        assertEquals("读取 ?", ToolSummaryGenerator.callSummary("read_file", "{not json"));
        assertEquals("读取 ?", ToolSummaryGenerator.callSummary("read_file", null));
        assertEquals("读取 ?", ToolSummaryGenerator.callSummary("read_file", ""));
    }

    @Test
    void nullToolNameShouldBeSafe() {
        assertEquals("调用工具", ToolSummaryGenerator.callSummary(null, "{}"));
        assertEquals("调用工具", ToolSummaryGenerator.callSummary("", "{}"));
    }

    @Test
    void resumedSummaryShouldUseFriendlyToolNameWithoutArgs() {
        assertEquals("执行 submit_application",
            ToolSummaryGenerator.resumedCallSummary("submit_application"));
        assertEquals("执行 publish_service",
            ToolSummaryGenerator.resumedCallSummary("mcp__oaf__publish_service"));
        assertEquals("调用工具", ToolSummaryGenerator.resumedCallSummary(null));
        assertEquals("调用工具", ToolSummaryGenerator.resumedCallSummary(" "));
    }

    @Test
    void overlongCommandShouldBeTruncated() {
        var cmd = "x".repeat(500);
        var summary = ToolSummaryGenerator.callSummary("execute", "{\"command\":\"" + cmd + "\"}");
        assertTrue(summary.endsWith("…"), summary);
        assertTrue(summary.length() <= ToolSummaryGenerator.MAX_SUMMARY + 1, summary);
    }

    @Test
    void summaryShouldAlwaysBeSingleLine() {
        // 命令里含换行也不能破坏「一行一条」的展示约定
        var args = "{\"command\":\"echo a\\necho b\\necho c\"}";
        var summary = ToolSummaryGenerator.callSummary("execute", args);
        assertFalse(summary.contains("\n"), summary);
    }

    // ===== 结果预览 =====

    @Test
    void successResultShouldShowFirstMeaningfulLine() {
        var preview = ToolSummaryGenerator.resultPreview("execute",
            "\n\nPPT 生成成功。现在进行质量检查——内容提取和结构验证：\n更多…", "SUCCESS");
        assertEquals("PPT 生成成功。现在进行质量检查——内容提取和结构验证：", preview);
    }

    @Test
    void nonSuccessStateShouldShowTerminalState() {
        assertEquals("❌ 执行失败", ToolSummaryGenerator.resultPreview("execute", "boom", "ERROR"));
        assertEquals("❌ 已被拒绝", ToolSummaryGenerator.resultPreview("write_file", "", "DENIED"));
        assertEquals("❌ 已中断", ToolSummaryGenerator.resultPreview("execute", "partial", "INTERRUPTED"));
    }

    @Test
    void emptySuccessResultShouldYieldNoPreview() {
        assertNull(ToolSummaryGenerator.resultPreview("write_file", "", "SUCCESS"));
        assertNull(ToolSummaryGenerator.resultPreview("write_file", "   \n  ", "SUCCESS"));
        assertNull(ToolSummaryGenerator.resultPreview("write_file", null, "SUCCESS"));
    }

    @Test
    void longResultShouldBeTruncated() {
        var preview = ToolSummaryGenerator.resultPreview("execute", "y".repeat(500), "SUCCESS");
        assertTrue(preview.endsWith("…"), preview);
        assertTrue(preview.length() <= ToolSummaryGenerator.MAX_SUMMARY + 1, preview);
    }

    @Test
    void nullStateShouldBeTreatedAsSuccess() {
        // 事件未携带 state 时按成功处理，避免误标失败
        assertEquals("ok", ToolSummaryGenerator.resultPreview("execute", "ok", null));
    }

    // ===== agent_spawn（远程子 agent 委派，order-fulfillment demo 语义） =====

    @Test
    void agentSpawnCallSummaryShouldHighlightAgentAndTask() {
        var args = "{\"agent_id\":\"order-agent\",\"task\":\"查询订单 O-1002 的事实\",\"timeout_seconds\":60}";
        assertEquals("远程委派 order-agent：查询订单 O-1002 的事实",
            ToolSummaryGenerator.callSummary("agent_spawn", args));
    }

    @Test
    void agentSpawnCallSummaryWithoutAgentIdShouldFallback() {
        assertEquals("启动子 Agent 查询订单",
            ToolSummaryGenerator.callSummary("agent_spawn", "{\"task\":\"查询订单\"}"));
    }

    @Test
    void agentSpawnOkResultShouldParseJsonQuotedReply() {
        // 远端结果以 JSON 字符串字面量回流（首尾带引号、\n 转义）：解包后按行解析取 reply 首行
        var result = "\"agent_key: agent:order-agent:uuid\\nagent_id: order-agent\\n"
            + "session_id: sub-1\\nstatus: ok\\nreply:\\n订单 O-1002 的事实如下：\\nSKU=SKU-9H\"";
        assertEquals("order-agent 返回：订单 O-1002 的事实如下：",
            ToolSummaryGenerator.resultPreview("agent_spawn", result, "SUCCESS"));
    }

    @Test
    void agentSpawnAcceptedResultShouldShowTaskId() {
        var result = "\"status: accepted\\ntask_id: task_123\\nagent_id: logistics-agent\"";
        assertEquals("logistics-agent 已受理后台任务 task_123，等待收割结果",
            ToolSummaryGenerator.resultPreview("agent_spawn", result, "SUCCESS"));
    }

    @Test
    void agentSpawnTimeoutResultShouldShowTerminal() {
        var result = "\"status: timeout\\ntask_id: task_9\\nagent_id: order-agent\"";
        assertEquals("⏱ order-agent 等待超时（任务 task_9）",
            ToolSummaryGenerator.resultPreview("agent_spawn", result, "SUCCESS"));
    }

    @Test
    void agentSpawnErrorResultShouldShowError() {
        var result = "\"status: error\\nagent_id: after-sales-agent\\nerror: L3 校验失败\"";
        assertEquals("❌ after-sales-agent 执行失败：L3 校验失败",
            ToolSummaryGenerator.resultPreview("agent_spawn", result, "SUCCESS"));
    }

    @Test
    void agentSpawnUnparsableResultShouldFallbackToFirstLine() {
        // 非 status 键值形态：退回通用首行预览
        assertEquals("plain text", ToolSummaryGenerator.resultPreview("agent_spawn", "plain text", "SUCCESS"));
    }
}
