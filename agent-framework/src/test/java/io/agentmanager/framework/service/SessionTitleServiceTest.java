package io.agentmanager.framework.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import reactor.core.publisher.Flux;

/**
 * SessionTitleService 单元测试：系统模型生成标题（清洗/截断/已有标题不覆盖/LLM 异常 fail-soft）。
 */
class SessionTitleServiceTest {

    private static final String SID = "webui-title-1";

    private Model model;
    private SessionUserStore store;
    private SessionTitleService service;

    @BeforeEach
    void setUp() {
        model = mock(Model.class);
        store = mock(SessionUserStore.class);
        service = new SessionTitleService(model, store);
        when(store.findRemarkBySession(SID)).thenReturn("");
    }

    @Test
    void shouldGenerateSanitizedTitleAndWriteRemark() {
        stubLlm("《发布助手测试》\n");

        var title = service.generate(SID, "帮我发布 packageId=3 的配置包");

        assertEquals("发布助手测试", title);
        verify(store).upsertRemark(SID, "发布助手测试");
    }

    @Test
    void shouldNotOverwriteExistingTitle() {
        when(store.findRemarkBySession(SID)).thenReturn("手动命名");

        var title = service.generate(SID, "任意消息");

        assertNull(title);
        verify(model, never()).stream(anyList(), anyList(), any());
        verify(store, never()).upsertRemark(any(), any());
    }

    @Test
    void shouldFailSoftOnLlmError() {
        when(model.stream(anyList(), anyList(), any()))
            .thenReturn(Flux.error(new RuntimeException("llm down")));

        var title = service.generate(SID, "任意消息");

        assertNull(title);
        verify(store, never()).upsertRemark(any(), any());
    }

    @Test
    void shouldSkipWriteWhenOutputBlank() {
        stubLlm("   \n  ");

        var title = service.generate(SID, "任意消息");

        assertNull(title);
        verify(store, never()).upsertRemark(any(), any());
    }

    @Test
    void shouldTruncateOverlongTitle() {
        stubLlm("标".repeat(100));

        var title = service.generate(SID, "任意消息");

        assertEquals(SessionTitleService.TITLE_MAX_CHARS, title.length());
        verify(store).upsertRemark(SID, title);
    }

    @Test
    void asyncShouldIgnoreBlankInputs() {
        service.generateAsync(null, "消息");
        service.generateAsync(SID, "  ");

        verify(model, never()).stream(anyList(), anyList(), any());
    }

    private void stubLlm(String text) {
        when(model.stream(anyList(), anyList(), any())).thenReturn(Flux.just(
            ChatResponse.builder()
                .id("r1")
                .content(List.of(TextBlock.builder().text(text).build()))
                .finishReason("stop")
                .build()));
    }
}
