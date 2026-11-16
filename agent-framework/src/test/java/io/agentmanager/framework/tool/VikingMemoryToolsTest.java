package io.agentmanager.framework.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.agentmanager.framework.config.VikingConfig;
import io.agentmanager.framework.service.SessionKeyResolver;
import io.agentmanager.framework.service.SessionUserStore;
import io.agentmanager.framework.service.VikingMemoryClient;
import io.agentscope.core.agent.RuntimeContext;

/**
 * VikingMemoryTools 单测：memory_search/get/save 三工具调用路径（此前零执行——
 * 工具方法参数/客户端调用/兜底文案均未验证，覆盖审计补齐）。
 */
class VikingMemoryToolsTest {

    private final VikingMemoryClient client = Mockito.mock(VikingMemoryClient.class);
    private final SessionUserStore store = Mockito.mock(SessionUserStore.class);
    private final SessionKeyResolver resolver = new SessionKeyResolver(store);
    private final VikingConfig cfg = new VikingConfig("openviking", "http://ov:1933",
        "oaf-svc", "ak", "seed", false, false, 2000, 300);

    private VikingMemoryTools tools() {
        return new VikingMemoryTools(client, resolver, cfg);
    }

    @Test
    void memorySearchReturnsClientText() {
        Mockito.when(client.searchContext("u-1", "发布流程")).thenReturn("- 发布三步:上传/配置/确认");
        var out = tools().memorySearch("发布流程", ctxWith("u-1"));
        assertEquals("- 发布三步:上传/配置/确认", out);
        Mockito.verify(client).searchContext("u-1", "发布流程");
    }

    @Test
    void memorySearchFallbackOnEmpty() {
        Mockito.when(client.searchContext(Mockito.anyString(), Mockito.anyString())).thenReturn(null);
        assertEquals("No matching memory found.", tools().memorySearch("q", ctxWith("u-1")));
    }

    @Test
    void memorySaveSubmitsRemember() {
        tools().memorySave("用户偏好 Rust", ctxWith("u-1"));
        Mockito.verify(client).remember(Mockito.eq("u-1"), Mockito.argThat(messages -> {
            if (messages == null || messages.size() != 1) {
                return false;
            }
            var entry = messages.get(0);
            return "user".equals(entry.getKey())
                && entry.getValue().contains("Rust");
        }));
    }

    @Test
    void memoryGetReadsBack() {
        Mockito.when(client.searchContext("u-1", "recently saved memories preferences facts"))
            .thenReturn("saved content");
        assertEquals("saved content", tools().memoryGet(ctxWith("u-1")));
    }

    private RuntimeContext ctxWith(String userId) {
        return RuntimeContext.builder().userId(userId).build();
    }
}
