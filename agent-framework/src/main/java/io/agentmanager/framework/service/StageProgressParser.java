package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StageProgressParser {

    public static final String STAGE_OPEN = "<stage>";
    public static final String STAGE_CLOSE = "</stage>";
    public static final String SUMMARY_OPEN = "<summary>";
    public static final String SUMMARY_CLOSE = "</summary>";

    private static final int MAX_STAGE_LEN = 60;
    private static final Pattern TAG_PATTERN = Pattern.compile(
        "<(stage|summary)>([\\s\\S]*?)</\\1>", Pattern.CASE_INSENSITIVE);

    private StageProgressParser() {
    }

    public record ParsedContent(String cleanText, List<String> stages, String turnSummary) {
    }

    public static ParsedContent parse(String text) {
        if (text == null || text.isEmpty()) {
            return new ParsedContent("", List.of(), null);
        }
        if (!text.contains("<")) {
            return new ParsedContent(text, List.of(), null);
        }
        Matcher matcher = TAG_PATTERN.matcher(text);
        if (!matcher.find()) {
            return new ParsedContent(text, List.of(), null);
        }
        matcher.reset();
        List<String> stages = new ArrayList<>();
        String summary = null;
        StringBuilder clean = new StringBuilder(text.length());
        int lastEnd = 0;
        while (matcher.find()) {
            clean.append(text, lastEnd, matcher.start());
            String tag = matcher.group(1).toLowerCase();
            String value = normalizeLine(matcher.group(2));
            if (!value.isEmpty()) {
                if ("stage".equals(tag)) {
                    if (stages.isEmpty() || !stages.get(stages.size() - 1).equals(value)) {
                        stages.add(value);
                    }
                } else {
                    summary = value;
                }
            }
            lastEnd = matcher.end();
        }
        clean.append(text, lastEnd, text.length());
        String stripped = clean.toString().replaceFirst("^[\\r\\n]+", "");
        return new ParsedContent(stripped, List.copyOf(stages), summary);
    }

    public static String normalizeLine(String raw) {
        if (raw == null) {
            return "";
        }
        String collapsed = raw.replaceAll("\\s+", " ").trim();
        if (collapsed.length() > MAX_STAGE_LEN) {
            return collapsed.substring(0, MAX_STAGE_LEN) + "…";
        }
        return collapsed;
    }

    public static String fallbackStageForTools(List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return "";
        }
        boolean hasRead = false;
        boolean hasWrite = false;
        boolean hasExec = false;
        boolean hasWeb = false;
        boolean hasMemory = false;
        boolean hasSubagent = false;
        LinkedHashSet<String> mcpServers = new LinkedHashSet<>();
        for (String raw : toolNames) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            if (raw.startsWith("mcp__")) {
                int second = raw.indexOf("__", 5);
                if (second > 5) {
                    mcpServers.add(raw.substring(5, second));
                    continue;
                }
            }
            String name = raw.contains("__") ? raw.substring(raw.lastIndexOf("__") + 2) : raw;
            switch (name) {
                case "read_file", "list_files", "glob_files", "grep_files" -> hasRead = true;
                case "write_file", "edit_file" -> hasWrite = true;
                case "execute" -> hasExec = true;
                case "web_search", "web_fetch" -> hasWeb = true;
                case "memory_search", "memory_get", "memory_save", "session_search" -> hasMemory = true;
                case "agent_spawn", "agent_send", "wait_async_results" -> hasSubagent = true;
                default -> { }
            }
        }
        if (!mcpServers.isEmpty()) {
            String servers = String.join("、", mcpServers);
            return normalizeLine("正在调用 " + servers + " 处理请求");
        }
        if (hasSubagent) {
            return "正在协同子智能体处理任务";
        }
        if (hasWrite && hasExec) {
            return "正在修改文件并运行验证命令";
        }
        if (hasWrite) {
            return "正在更新工作区文件内容";
        }
        if (hasExec) {
            return "正在执行命令并检查结果";
        }
        if (hasRead && hasWeb) {
            return "正在检索资料并核对本地文件";
        }
        if (hasRead) {
            return "正在查阅并分析相关文件";
        }
        if (hasWeb) {
            return "正在检索外部信息";
        }
        if (hasMemory) {
            return "正在检索历史上下文与记忆";
        }
        return "正在执行相关工具操作";
    }

    public static String fallbackTurnSummary(List<String> stages) {
        if (stages == null || stages.isEmpty()) {
            return "";
        }
        String last = toCompletedPhrase(stages.get(stages.size() - 1));
        if (stages.size() == 1) {
            return normalizeLine(last);
        }
        return normalizeLine("已完成 " + stages.size() + " 个阶段：" + last);
    }

    private static String toCompletedPhrase(String phrase) {
        String s = normalizeLine(phrase);
        if (s.startsWith("正在为你")) {
            return "已为你" + s.substring(4);
        }
        if (s.startsWith("正在")) {
            return "已" + s.substring(2);
        }
        if (s.startsWith("已")) {
            return s;
        }
        return "已完成" + s;
    }
}
