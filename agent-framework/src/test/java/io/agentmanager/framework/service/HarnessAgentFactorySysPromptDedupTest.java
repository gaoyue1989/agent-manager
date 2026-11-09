package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.model.OafConfig;

/**
 * system prompt AGENTS.md 去重（first-llm-latency 缺口②）：
 * SDK WorkspaceContextMiddleware 会把 workspace/AGENTS.md 注入 {@code <loaded_context>}——
 * sysPrompt 保留全文则正文进两遍。仅当 workspace/AGENTS.md 存在时保留首行标题；
 * AGENTS.md 缺失 / 单行正文时原样返回（防 persona 丢失）。
 */
class HarnessAgentFactorySysPromptDedupTest {

    private static HarnessAgentFactory factoryWithWorkspace(Path baseDir) {
        var props = mock(AgentManagerProperties.class);
        when(props.resolvedWorkspaceBaseDir()).thenReturn(baseDir.toString());
        return new HarnessAgentFactory(props, mock(WorkspaceInitializer.class),
            mock(McpToolRegistrar.class), List.of(), mock(RemoteConfirmBridge.class),
            null, null, mock(SessionToolScopeService.class));
    }

    private static OafConfig oafWithPrompt(String prompt) {
        return new OafConfig("agent", null, null, null, null, null, null, null, null,
            prompt, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void keepsFirstLineOnlyWhenWorkspaceAgentsMdExists(@TempDir Path base) throws Exception {
        var ws = base.resolve(".agentscope").resolve("workspace");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("AGENTS.md"), "---\nname: agent\n---\n\n正文与 sysPrompt 重复");
        var factory = factoryWithWorkspace(base);

        var result = factory.deduplicatedSysPrompt(oafWithPrompt("标题行\n正文A\n正文B"));

        assertEquals("标题行", result, "AGENTS.md 存在时只保留首行（其余经 loaded_context 注入）");
    }

    @Test
    void keepsFullPromptWhenAgentsMdMissing(@TempDir Path base) {
        var factory = factoryWithWorkspace(base);
        var prompt = "标题\n正文A\n正文B";

        assertEquals(prompt, factory.deduplicatedSysPrompt(oafWithPrompt(prompt)),
            "AGENTS.md 缺失时不得去重（否则 persona 丢失）");
    }

    @Test
    void keepsSingleLinePromptAsIs(@TempDir Path base) throws Exception {
        var ws = base.resolve(".agentscope").resolve("workspace");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("AGENTS.md"), "x");
        var factory = factoryWithWorkspace(base);

        assertEquals("单行", factory.deduplicatedSysPrompt(oafWithPrompt("单行")),
            "单行正文没有可去重的重复段，原样返回");
    }

    @Test
    void nullAndBlankPassThrough(@TempDir Path base) {
        var factory = factoryWithWorkspace(base);
        assertEquals(null, factory.deduplicatedSysPrompt(oafWithPrompt(null)));
        assertEquals("", factory.deduplicatedSysPrompt(oafWithPrompt("")));
    }
}
