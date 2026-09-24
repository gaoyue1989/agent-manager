package io.agentmanager.framework.service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;

/**
 * 会话标题生成（**系统模型** = LLM_* 环境变量模型）。
 *
 * <p>新会话首条用户消息后异步生成简短标题，写入 session_user.remark（GET /threads 的 title）。
 * 与会话可切换模型解耦：无论会话选了什么模型，标题/记忆压缩固定走系统模型。
 *
 * <p>约束：
 * <ul>
 *   <li>fire-and-forget：调用方立即返回，失败仅告警，绝不影响对话；</li>
 *   <li>已有标题（手动重命名或已生成）不覆盖；</li>
 *   <li>单线程守护 executor 串行执行，标题生成本身低频。</li>
 * </ul>
 */
public class SessionTitleService {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleService.class);

    /** 标题最大字符数（session_user.remark 为 VARCHAR(512)，留足余量） */
    static final int TITLE_MAX_CHARS = 64;
    /** 送入模型的首条消息最大字符数（标题语义不需要全文） */
    static final int INPUT_MAX_CHARS = 500;
    /** 标题输出 token 上限（中文 20 字量级足够） */
    static final int TITLE_MAX_TOKENS = 64;
    /** 单次生成超时（秒） */
    static final int TIMEOUT_SECONDS = 60;

    private static final String PROMPT_PREFIX =
        "请为下面这条用户消息生成一个简短的会话标题：不超过 20 个字，"
            + "只输出标题本身（不要引号、不要句末标点、不要换行）。\n\n用户消息：\n";

    private final Model model;
    private final SessionUserStore sessionUserStore;
    private final ExecutorService executor;

    public SessionTitleService(Model model, SessionUserStore sessionUserStore) {
        this.model = model;
        this.sessionUserStore = sessionUserStore;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "session-title");
            t.setDaemon(true);
            return t;
        });
    }

    /** 异步生成会话标题（fire-and-forget，方法立即返回） */
    public void generateAsync(String sessionId, String firstUserMessage) {
        if (sessionId == null || sessionId.isBlank()
                || firstUserMessage == null || firstUserMessage.isBlank()) {
            return;
        }
        executor.submit(() -> {
            try {
                generate(sessionId, firstUserMessage);
            } catch (Exception e) {
                log.warn("session title generation failed (sid={}): {}", sessionId, e.getMessage());
            }
        });
    }

    /**
     * 同步生成（测试直调）：已有标题跳过；生成后清洗/截断写入 remark。
     *
     * @return 写入的标题；未生成（已有标题/输出为空）返回 null
     */
    String generate(String sessionId, String firstUserMessage) {
        var existing = sessionUserStore.findRemarkBySession(sessionId);
        if (existing != null && !existing.isBlank()) {
            return null; // 手动重命名或历史生成优先，不覆盖
        }
        var prompt = PROMPT_PREFIX + truncate(firstUserMessage, INPUT_MAX_CHARS);
        var msg = new UserMessage("user", prompt);
        // 只覆盖 maxTokens/temperature：其余采样参数走模型 configuredOptions 合并（含 enable_thinking）
        var options = GenerateOptions.builder()
            .temperature(0.3)
            .maxTokens(TITLE_MAX_TOKENS)
            .build();
        List<ChatResponse> responses;
        try {
            responses = model.stream(List.of(msg), List.of(), options)
                .collectList()
                .block(Duration.ofSeconds(TIMEOUT_SECONDS));
        } catch (Exception e) {
            log.warn("session title LLM call failed (sid={}): {}", sessionId, e.getMessage());
            return null;
        }
        var title = sanitize(extractText(responses));
        if (title.isEmpty()) {
            log.warn("session title empty, skip write (sid={})", sessionId);
            return null;
        }
        sessionUserStore.upsertRemark(sessionId, title);
        log.info("session title generated (sid={}): {}", sessionId, title);
        return title;
    }

    /** 拼接流式响应中的文本块 */
    private static String extractText(List<ChatResponse> responses) {
        if (responses == null) {
            return "";
        }
        var sb = new StringBuilder();
        for (var resp : responses) {
            if (resp == null || resp.getContent() == null) {
                continue;
            }
            for (var block : resp.getContent()) {
                if (block instanceof TextBlock text && text.getText() != null) {
                    sb.append(text.getText());
                }
            }
        }
        return sb.toString();
    }

    /** 清洗：折叠空白、去包裹引号/书名号、截断到上限 */
    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        var title = raw.replaceAll("\\s+", " ").trim();
        // 模型偶尔带引号/书名号包裹（“标题”、《标题》）——统一剥掉
        title = title.replaceAll("^[\"'“”‘’《》]+", "").replaceAll("[\"'“”‘’《》]+$", "").trim();
        if (title.length() > TITLE_MAX_CHARS) {
            title = title.substring(0, TITLE_MAX_CHARS);
        }
        return title;
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
