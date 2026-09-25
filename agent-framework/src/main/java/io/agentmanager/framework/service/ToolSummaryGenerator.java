package io.agentmanager.framework.service;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工具调用 / 工具结果的「人可读摘要」生成器。
 *
 * <p><b>为什么需要它</b>：SSE 词表里工具相关事件只发结构化原始数据——
 * {@code TOOL_CALL_START} 只有工具名、{@code TOOL_CALL_DELTA} 是 JSON 参数碎片、
 * {@code TOOL_RESULT_TEXT_DELTA} 是结果文本碎片。轻量客户端（发布助手、CLI、钉钉/企微 Channel）
 * 不会去拼 delta、按工具名解析字段，于是只能显示「🔧 write_file 完成」这种没有信息量的行。
 *
 * <p>本类把「拼 buffer → 按工具名提关键参数 → 截摘要」收敛在服务端，
 * 由 {@link io.agentmanager.framework.controller.ChatStreamController} 与
 * {@link io.agentmanager.framework.controller.ConfirmController} 在参数到齐（TOOL_CALL_END）与
 * 结果到齐（TOOL_RESULT_END）时合成 {@code tool_call_summary} / {@code tool_result_preview} 帧下发，
 * 客户端零解析成本即可得到「创建 output/create-ai-ppt.js 408行」这样的行。
 *
 * <p><b>纯静态、无副作用</b>：不持有会话状态（缓冲由调用方管理），便于单测。
 */
public final class ToolSummaryGenerator {

    private ToolSummaryGenerator() {
    }

    /** 摘要单行最大字符数（超出以 … 结尾） */
    static final int MAX_SUMMARY = 120;

    /** 命令 / 路径类参数超过该长度就缩略，避免长命令刷屏 */
    static final int MAX_ARG = 160;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 工具调用摘要：按工具名提取最关键的一个参数。
     *
     * <p>参数键取自 SDK 实际注册名（javap 核对）：
     * 文件类用 {@code path}（read_file/write_file/edit_file/list_files/grep_files/glob_files）、
     * Shell 用 {@code command}、技能加载用 {@code skillId}、记忆用 {@code query}。
     * 键写错只会退化成工具名本身，不会抛异常。
     *
     * @param toolName 工具注册名（Toolkit 裸名，MCP 工具为远端名）
     * @param args     已拼接完成的完整参数（JSON 字符串，解析失败按空参数处理）
     * @return 单行摘要；永不为 null
     */
    public static String callSummary(String toolName, String args) {
        if (toolName == null || toolName.isBlank()) {
            return "调用工具";
        }
        var fields = parse(args);
        String summary = switch (friendlyName(toolName)) {
            case "read_file" -> "读取 " + displayPath(first(fields, "path"));
            case "write_file" -> "创建 " + fileWithLines(fields);
            case "edit_file" -> "编辑 " + displayPath(first(fields, "path"));
            case "list_files" -> "列出目录 " + displayPath(first(fields, "path"));
            case "glob_files" -> "搜索文件 " + firstNonBlank(fields, "?", "pattern");
            case "grep_files" -> "搜索内容 " + firstNonBlank(fields, "?", "pattern");
            case "execute" -> "执行 " + truncate(str(first(fields, "command")), MAX_ARG);
            case "load_skill_through_path" -> "加载技能 " + firstNonBlank(fields, "?", "skillId");
            case "memory_search" -> "回忆 " + truncate(firstNonBlank(fields, "", "query"), MAX_ARG);
            case "memory_save" -> "记住 " + truncate(firstNonBlank(fields, "", "content", "text"), MAX_ARG);
            case "memory_get" -> "读取记忆 " + displayPath(first(fields, "path"));
            case "present_file" -> "交付文件 " + displayPath(first(fields, "file_path", "path"));
            case "session_search" -> "检索会话 " + truncate(firstNonBlank(fields, "", "query"), MAX_ARG);
            case "agent_spawn" -> "启动子 Agent " + firstNonBlank(fields, "?", "task");
            case "task_list", "task_output", "task_cancel", "wait_async_results" -> taskVerb(toolName);
            case "plan_enter", "plan_write", "plan_exit" -> planVerb(toolName);
            default -> null;   // 未识别：退回工具名 + 首个有意义参数
        };
        if (summary == null) {
            summary = fallback(toolName, fields);
        }
        return truncate(collapse(summary), MAX_SUMMARY);
    }

    /**
     * 恢复执行段（HITL confirm 后）的兜底调用摘要。
     *
     * <p>SDK 恢复流只重发 {@code TOOL_RESULT_*}，不再重发原 {@code TOOL_CALL_*} 参数事件；
     * 此时无法从参数中提取路径/命令，但前端仍需要一帧可展示的调用标题，故固定为「执行 工具名」。
     *
     * @param toolName 工具注册名（MCP 展示名取末段）
     * @return 单行摘要；永不为 null
     */
    static String resumedCallSummary(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "调用工具";
        }
        return truncate("执行 " + friendlyName(toolName), MAX_SUMMARY);
    }

    /**
     * 工具结果预览：取结果首行（或首个非空行），便于「输出 …」形式展示。
     *
     * <p>非 SUCCESS 时优先展示终态（失败/拒绝/中断），因为它比结果文本更值得让用户知道。
     *
     * @param toolName 工具注册名（预留扩展：未来按工具定制结果预览）
     * @param resultText 已拼接完成的结果文本
     * @param state      {@code ToolResultState} 名（SUCCESS/ERROR/INTERRUPTED/DENIED/RUNNING）
     * @return 预览文本；无有效内容时返回 {@code null}（调用方据此跳过发帧）
     */
    @SuppressWarnings("unused")
    public static String resultPreview(String toolName, String resultText, String state) {
        if (state != null && !state.isBlank() && !"SUCCESS".equalsIgnoreCase(state)) {
            return "❌ " + stateLabel(state);
        }
        if (resultText == null || resultText.isBlank()) {
            return null;
        }
        String line = firstMeaningfulLine(resultText);
        return line == null ? null : truncate(collapse(line), MAX_SUMMARY);
    }

    // ===== 内部实现 =====

    /** 未识别工具：工具名 + 首个短标量参数，力求仍然有信息量 */
    private static String fallback(String toolName, Map<String, Object> fields) {
        var hint = firstScalar(fields);
        if (hint == null || hint.isBlank()) {
            return friendlyName(toolName);
        }
        return friendlyName(toolName) + " " + truncate(hint, MAX_ARG);
    }

    /** 写入文件：路径 + 行数（用户示例形态「创建 create-ai-ppt.js 408行」） */
    private static String fileWithLines(Map<String, Object> fields) {
        var path = displayPath(first(fields, "path"));
        var content = str(first(fields, "content"));
        if (content.isEmpty()) {
            return path;
        }
        return path + " " + countLines(content) + "行";
    }

    private static String taskVerb(String toolName) {
        return switch (toolName) {
            case "task_list" -> "查看异步任务";
            case "task_output" -> "读取任务输出";
            case "task_cancel" -> "取消任务";
            default -> "等待异步结果";
        };
    }

    private static String planVerb(String toolName) {
        return switch (toolName) {
            case "plan_enter" -> "进入计划模式";
            case "plan_write" -> "编写计划";
            default -> "退出计划模式";
        };
    }

    /** MCP 工具展示名取末段；内置工具原样返回 */
    private static String friendlyName(String toolName) {
        if (toolName.contains("__")) {
            return toolName.substring(toolName.lastIndexOf("__") + 2);
        }
        return toolName;
    }

    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            var node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            var map = new java.util.LinkedHashMap<String, Object>();
            var it = node.fields();
            while (it.hasNext()) {
                var e = it.next();
                map.put(e.getKey(), scalar(e.getValue()));
            }
            return map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** JSON 节点降级为字符串 / 数字布尔的字符串形；容器类型返回紧凑 JSON */
    private static Object scalar(com.fasterxml.jackson.databind.JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isValueNode()) return n.asText();
        return n.toString();
    }

    private static String first(Map<String, Object> fields, String... keys) {
        for (var k : keys) {
            var v = fields.get(k);
            if (v != null) return String.valueOf(v);
        }
        return null;
    }

    private static String firstNonBlank(Map<String, Object> fields, String defaultVal, String... keys) {
        for (var k : keys) {
            var v = fields.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        return defaultVal;
    }

    /** 第一个短标量值：用于未识别工具兜底（跳过超长 body/content，避免刷屏） */
    private static String firstScalar(Map<String, Object> fields) {
        String best = null;
        for (var v : fields.values()) {
            if (v == null) continue;
            var s = String.valueOf(v);
            if (s.isBlank()) continue;
            if (s.length() <= MAX_ARG && (best == null || s.length() < best.length())) {
                best = s;
            }
        }
        return best;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    /** 工作区绝对路径对用户无意义，去掉前缀；过长的中间目录折叠为 …/末两级 */
    private static String displayPath(String path) {
        var p = str(path).replace('\\', '/');
        if (p.isBlank()) return "?";
        if (p.startsWith("/workspace/")) p = p.substring("/workspace/".length());
        else if (p.startsWith("/")) p = p.substring(1);
        if (p.length() <= 60) return p;
        var parts = p.split("/");
        return "…/" + (parts.length >= 2 ? parts[parts.length - 2] + "/" + parts[parts.length - 1]
            : parts[parts.length - 1]);
    }

    private static int countLines(String content) {
        if (content.isEmpty()) return 0;
        long lines = content.lines().count();
        // 末尾换行不计行数（"a\n" 是 1 行而非 2 行）
        if (content.endsWith("\n")) lines--;
        return (int) Math.max(lines, 1);
    }

    /** 首个非空行（跳过结果里常见的前导空行 / 分隔线） */
    private static String firstMeaningfulLine(String text) {
        for (var line : text.lines().toList()) {
            var t = line.trim();
            if (!t.isBlank()) return t;
        }
        return null;
    }

    private static String stateLabel(String state) {
        return switch (state.toUpperCase()) {
            case "ERROR" -> "执行失败";
            case "INTERRUPTED" -> "已中断";
            case "DENIED" -> "已被拒绝";
            case "RUNNING" -> "执行中";
            default -> state;
        };
    }

    /** 摘要按单行展示：内部换行 / 连续空白压缩为一个空格 */
    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "?";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
