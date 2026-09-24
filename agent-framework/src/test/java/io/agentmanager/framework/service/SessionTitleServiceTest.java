package io.agentmanager.framework.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话标题生成服务测试（LLM 标题生成 + 清洗 + 已存在跳过）。
 */
class SessionTitleServiceTest {

    private Model model;
    private SessionUserStore sessionUserStore;
    private SessionTitleService service;

    @BeforeEach
    void setUp() {
        model = mock(Model.class);
        sessionUserStore = mock(SessionUserStore.class);
        // 同步 Executor：异步路径在测试中即时执行，断言确定
        service = new SessionTitleService(model, sessionUserStore, Runnable::run);
    }

    private void stubModelText(String text) {
        List<ContentBlock> blocks = List.of(TextBlock.builder().text(text).build());
        when(model.stream(any(), any(), any()))
            .thenReturn(Flux.just(ChatResponse.builder().content(blocks).build()));
    }

    @Test
    void generateAsyncShouldPersistGeneratedTitle() {
        when(sessionUserStore.findRemark("sid-1")).thenReturn("");
        stubModelText("如何部署智能体");

        service.generateAsync("sid-1", "我想知道怎么把智能体部署到k8s集群", "alice");

        verify(sessionUserStore).updateRemark("sid-1", "如何部署智能体");
    }

    @Test
    void generateAsyncShouldSkipWhenTitleAlreadyExists() {
        when(sessionUserStore.findRemark("sid-2")).thenReturn("已有标题");

        service.generateAsync("sid-2", "第二条消息", "alice");

        verify(model, never()).stream(any(), any(), any());
        verify(sessionUserStore, never()).updateRemark(anyString(), anyString());
    }

    @Test
    void generateAsyncShouldSkipBlankMessage() {
        service.generateAsync("sid-3", "   ", "alice");

        verify(sessionUserStore, never()).findRemark(anyString());
        verify(model, never()).stream(any(), any(), any());
    }

    @Test
    void generateAsyncShouldFallbackToFirstMessageWhenModelReturnsNoText() {
        when(sessionUserStore.findRemark("sid-4")).thenReturn("");
        when(model.stream(any(), any(), any())).thenReturn(Flux.empty());

        service.generateAsync("sid-4", "hello world", "alice");

        verify(sessionUserStore).updateRemark("sid-4", "hello world");
    }

    @Test
    void generateTitleShouldReturnNullWhenModelReturnsEmpty() {
        when(model.stream(any(), any(), any())).thenReturn(Flux.empty());
        assertNull(service.generateTitle("hello"));
    }

    @Test
    void generateTitleShouldSanitizeModelOutput() {
        stubModelText("\"部署流程。\"\n多余的说明");
        assertEquals("部署流程", service.generateTitle("怎么部署"));
    }

    @Test
    void sanitizeShouldStripQuotesPunctuationAndTruncate() {
        assertEquals("如何部署", SessionTitleService.sanitize("  “如何部署？”  "));
        assertEquals("", SessionTitleService.sanitize(null));
        var longTitle = "一".repeat(80);
        assertEquals(SessionTitleService.MAX_TITLE_LEN,
            SessionTitleService.sanitize(longTitle).length());
    }

    @Test
    void sanitizeShouldPickFirstNonBlankLineAndStripPrefix() {
        // 模型常在标题前输出空行
        assertEquals("部署流程", SessionTitleService.sanitize("\n\n部署流程。\n说明"));
        // 常见“标题：”前缀被去除
        assertEquals("部署流程", SessionTitleService.sanitize("标题：部署流程"));
        // 只有标点/引号时视为空
        assertEquals("", SessionTitleService.sanitize("\"\""));
    }

    @Test
    void fallbackTitleShouldCollapseWhitespaceAndTruncate() {
        assertEquals("你好 世界", SessionTitleService.fallbackTitle("  你好\n\t世界  "));
        var longMsg = "请帮我检查一下这段代码是否存在并发安全问题";
        var t = SessionTitleService.fallbackTitle(longMsg);
        assertEquals(SessionTitleService.FALLBACK_TITLE_LEN + 1, t.length());
        assertTrue(t.endsWith("…"));
        assertEquals("", SessionTitleService.fallbackTitle("   "));
    }
}
