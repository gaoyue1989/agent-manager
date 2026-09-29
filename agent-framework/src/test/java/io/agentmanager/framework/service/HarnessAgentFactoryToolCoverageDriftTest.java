package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.agentmanager.framework.service.HarnessAgentFactory;

/**
 * 内建工具白名单防漂移测试（PR #62 遗留 P1-3，F18/F24）：
 * web_fetch/web_search 曾因 SDK 实际注册而白名单缺失，DEFAULT 模式触发未覆盖 ASK。
 * 本测试把"javap 从 jar 提取"自动化——扫描 classpath 上全部 io.agentscope.** 的
 * {@code @Tool} 注解工具名，断言每个名字都在 BUILT_IN_TOOL_NAMES 白名单或显式
 * 未启用清单内。SDK 升级引入新内建工具时本测试即红，强制显式决策进哪个集合，
 * 而不是等运行期 verifyToolCoverage 打 ERROR 才发现。
 *
 * <p>口径说明：扫描只覆盖 {@code @Tool} 注解声明的工具（agent_spawn/plan_* 等以
 * 程序化注册的工具不在其列，是既有白名单的超集方向）；类仅加载不初始化，签名
 * 引用缺失类型的类跳过（防御可选依赖）。
 */
class HarnessAgentFactoryToolCoverageDriftTest {

    /**
     * SDK 存在但当前 builder 未启用的工具（白名单冗余无害的另一半口径，与
     * BUILT_IN_TOOL_NAMES 中 agent_generate 同理）：按工具族分组，新增条目必须归组注明。
     * 判定依据：当前 builder 配置下运行期 verifyToolCoverage 从未报这些名字的 coverage gap
     * （F24 实证缺口只有 web_fetch/web_search/load_skill_through_path，均已入白名单）。
     */
    private static final Set<String> NOT_ENABLED_SDK_TOOLS = Set.of(
        // —— 单点工具 ——
        // io.agentscope.harness.agent.tool.ArtifactDeliveryTool（工件投递，工厂未启用）
        "deliver_artifact",
        // io.agentscope.core.rag.KnowledgeRetrievalTools（RAG 知识检索，工厂未启用）
        "retrieve_knowledge",
        // io.agentscope.core.tool.builtin.TodoTools（待办清单，工厂未启用）
        "todo_write",
        // —— team 多 Agent 协作工具族（team 扩展，工厂未启用）——
        "approvePlan", "assignTask", "broadcastMessage", "claimTask", "completeTask",
        "completeTeam", "createTask", "failTask", "listClaimableTasks", "listMembers",
        "listMessages", "listTasks", "rejectPlan", "sendMessage", "shutdownMember",
        "spawnMember", "submitPlan", "team", "unclaimTask",
        // —— openai 多模态工具族（媒体生成/转写，工厂未启用）——
        "openai_audio_to_text", "openai_image_to_text", "openai_text_to_audio", "openai_text_to_image",
        // —— SDK 旧版文件工具族（harness 用 FilesystemTool 命名，此族不注册）——
        "insert_text_file", "list_directory", "view_text_file", "write_text_file",
        // —— SDK 旧版记忆工具族（harness 用 memory_* 命名，此族不注册）——
        "recordToMemory", "retrieveFromMemory"
    );

    /** 扫描 classpath 上 agentscope SDK jar 的全部 @Tool 工具名（注册名规则：注解 name，空则方法名） */
    private static Set<String> scanSdkToolNames() {
        var names = new TreeSet<String>();
        for (var entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            var path = entry;
            if (path.isBlank()) {
                continue;
            }
            // 兼容 manifest-jar/argfile 形态：CodeSource 兜底定位
            if (!path.endsWith(".jar") && !path.endsWith(".jar/")) {
                continue;
            }
            try (var jar = new java.util.jar.JarFile(path.endsWith(".jar/") ? path.substring(0, path.length() - 1) : path)) {
                for (var e : Collections.list(jar.entries())) {
                    var n = e.getName();
                    if (!n.startsWith("io/agentscope/") || !n.endsWith(".class") || n.contains("$")) {
                        continue;
                    }
                    var clsName = n.replace('/', '.').substring(0, n.length() - 6);
                    Class<?> c;
                    try {
                        c = Class.forName(clsName, false,
                            HarnessAgentFactoryToolCoverageDriftTest.class.getClassLoader());
                    } catch (Throwable t) {
                        continue; // 可选依赖缺失等加载失败：跳过该类
                    }
                    Method[] methods;
                    try {
                        methods = c.getDeclaredMethods();
                    } catch (Throwable t) {
                        continue;
                    }
                    for (var m : methods) {
                        var tool = m.getAnnotation(io.agentscope.core.tool.Tool.class);
                        if (tool == null) {
                            continue;
                        }
                        names.add(tool.name().isBlank() ? m.getName() : tool.name());
                    }
                }
            } catch (Exception e) {
                // 非 jar/损坏条目：跳过（单测环境 classpath 由 surefire 保证含 SDK jar）
            }
        }
        return names;
    }

    @Test
    void everySdkToolAnnotationMustBeCoveredByWhitelistOrExplicitExclusion() {
        var scanned = scanSdkToolNames();

        // 前提守卫：扫描必须命中已知工具（防 classpath 形态变化导致空转误绿）
        assertTrue(scanned.contains("read_file") && scanned.contains("execute"),
            "tool scan found nothing useful (got " + scanned + ") — classpath form changed?");

        var unknown = new TreeSet<String>(scanned);
        unknown.removeAll(HarnessAgentFactory.BUILT_IN_TOOL_NAMES);
        unknown.removeAll(NOT_ENABLED_SDK_TOOLS);

        assertTrue(unknown.isEmpty(),
            "SDK 内建工具漂移：@Tool 工具 " + unknown + " 既不在 "
                + "HarnessAgentFactory.BUILT_IN_TOOL_NAMES 也不在未启用清单。"
                + "若该工具会被当前 builder 注册（构建后 verifyToolCoverage 会报 gap），"
                + "加入 BUILT_IN_TOOL_NAMES 生成 ALLOW 规则；否则加入本测试 NOT_ENABLED_SDK_TOOLS 并注明原因。"
                + "scanned=" + scanned);
    }

    /** 白名单与未启用清单不得重叠：重叠意味着同一名字两处语义冲突 */
    @Test
    void whitelistAndExclusionSetsMustNotOverlap() {
        var overlap = new TreeSet<String>(HarnessAgentFactory.BUILT_IN_TOOL_NAMES);
        overlap.retainAll(NOT_ENABLED_SDK_TOOLS);
        assertEquals(List.of(), List.copyOf(overlap), "whitelist 与未启用清单重叠: " + overlap);
    }
}
