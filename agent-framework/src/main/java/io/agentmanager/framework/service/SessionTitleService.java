package io.agentmanager.framework.service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;

/**
 * 会话标题自动生成：会话首条用户消息后，异步调用 LLM 生成简短标题，
 * 写入 {@code session_user.remark}（即 {@code GET /threads} 返回的 title 字段）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>仅在会话尚无标题时生成一次（已有 remark 直接跳过），失败不重试、不阻断对话</li>
 *   <li>异步执行（独立守护线程池），不阻塞 {@code POST /threads/chat} 的 SSE 流</li>
 *   <li>同会话并发首条消息用 in-flight 集合去重，避免重复调用 LLM</li>
 * </ul>
 */
@Service
public class SessionTitleService {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleService.class);

    /** 标题最大长度（兼容 session_user.remark 列宽） */
    static final int MAX_TITLE_LEN = 40;

    /** 标题生成最大输出 token（需覆盖推理模型的 thinking 开销，过小会只输出思考、正文为空） */
    private static final int MAX_OUTPUT_TOKENS = 512;

    /** 回退标题（首条消息截断）的最大长度 */
    static final int FALLBACK_TITLE_LEN = 20;

    /** 送入模型的用户消息截断上限，避免长消息浪费 token */
    private static final int MAX_INPUT_LEN = 1000;

    /** 标题生成调用超时 */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(20);

    private static final String SYSTEM_PROMPT =
        "你是一个会话标题生成助手。根据用户的第一条消息，生成一个简洁的标题（中文不超过 20 个字），"
            + "准确概括用户的意图或话题；若用户消息为英文则用英文标题。"
            + "只输出标题本身，不要引号、标点符号、序号或任何解释。";

    private final Model model;
    private final SessionUserStore sessionUserStore;
    private final Executor executor;

    /** 正在生成标题的会话集合（并发去重） */
    private final Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @org.springframework.beans.factory.annotation.Autowired
    public SessionTitleService(@Qualifier("titleGenerationModel") Model model,
                               SessionUserStore sessionUserStore) {
        this(model, sessionUserStore, defaultExecutor());
    }

    /** 测试用构造：注入确定性 Executor */
    SessionTitleService(Model model, SessionUserStore sessionUserStore, Executor executor) {
        this.model = model;
        this.sessionUserStore = sessionUserStore;
        this.executor = executor;
    }

    private static ExecutorService defaultExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                var t = new Thread(r, "session-title-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(2, factory);
    }

    /**
     * 会话首条消息后异步生成标题；已有标题、消息为空或正在生成时直接跳过。
     *
     * @param sessionId 会话 ID
     * @param message   用户原始消息（未注入 Skill 引用）
     * @param userId    用户 ID（仅用于日志）
     */
    public void generateAsync(String sessionId, String message, String userId) {
        if (sessionId == null || sessionId.isBlank()
            || message == null || message.isBlank()) {
            return;
        }
        // 已有标题 → 非首条消息，跳过
        if (!sessionUserStore.findRemark(sessionId).isBlank()) {
            return;
        }
        if (!inFlight.add(sessionId)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    var title = generateTitle(message);
                    if (title == null || title.isBlank()) {
                        // LLM 未产出可用标题（推理模型截断/空回复）→ 回退首条消息截断，
                        // 保证会话列表始终有可读名称，而不是退回无意义 ID
                        title = fallbackTitle(message);
                        log.warn("[title] LLM returned blank, fallback to first message (sid={}): {}",
                            sessionId, title);
                    }
                    if (!title.isBlank()) {
                        sessionUserStore.updateRemark(sessionId, title);
                        log.info("[title] generated for sid={}, userId={}: {}", sessionId, userId, title);
                    }
                } catch (Exception e) {
                    log.warn("[title] generation failed (sid={}): {}", sessionId, e.getMessage());
                } finally {
                    inFlight.remove(sessionId);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(sessionId);
            log.warn("[title] executor rejected task (sid={}): {}", sessionId, e.getMessage());
        }
    }

    /**
     * 调用 LLM 生成标题并清洗（去除包裹引号/尾部标点、截断超长）。
     *
     * @param message 用户消息
     * @return 标题；模型未返回文本时返回 null
     */
    String generateTitle(String message) {
        var input = message.length() > MAX_INPUT_LEN ? message.substring(0, MAX_INPUT_LEN) : message;
        var messages = List.of(
            Msg.builder().role(MsgRole.SYSTEM).textContent(SYSTEM_PROMPT).build(),
            Msg.builder().role(MsgRole.USER).textContent(input).build());
        // maxTokens 需覆盖推理模型的思考开销：过小会导致只输出 thinking、正文为空
        var options = GenerateOptions.builder().temperature(0.3).maxTokens(MAX_OUTPUT_TOKENS).build();

        List<ChatResponse> responses = model.stream(messages, null, options)
            .collectList()
            .block(CALL_TIMEOUT);
        if (responses == null || responses.isEmpty()) {
            return null;
        }
        var sb = new StringBuilder();
        for (var resp : responses) {
            if (resp.getContent() == null) {
                continue;
            }
            for (var block : resp.getContent()) {
                if (block instanceof TextBlock text) {
                    sb.append(text.getText());
                }
            }
        }
        var raw = sb.toString();
        var title = sanitize(raw);
        if (title.isBlank()) {
            log.warn("[title] model produced no usable text (responses={}, rawLen={}, finishReason={})",
                responses.size(), raw.length(),
                responses.get(responses.size() - 1).getFinishReason());
        }
        return title;
    }

    /** 回退标题：取首条消息的首个非空行，压缩空白后截断到安全长度 */
    static String fallbackTitle(String message) {
        if (message == null) {
            return "";
        }
        var s = message.strip();
        if (s.isEmpty()) {
            return "";
        }
        s = s.replaceAll("\\s+", " ").strip();
        int limit = Math.min(MAX_TITLE_LEN, FALLBACK_TITLE_LEN);
        if (s.length() > limit) {
            s = s.substring(0, limit).strip() + "…";
        }
        return s;
    }

    /** 标题清洗：取首个非空行、去包裹引号与尾部标点、截断超长 */
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        // 取首个非空行（模型常在标题前输出空行/换行）
        String s = "";
        for (var line : raw.strip().split("\\R")) {
            var t = line.strip();
            if (!t.isEmpty()) {
                s = t;
                break;
            }
        }
        if (s.isEmpty()) {
            return "";
        }
        // 去掉成对的包裹引号/书名号
        s = s.replaceAll("^[\"'“”‘’《》\\s]+", "").replaceAll("[\"'“”‘’《》\\s]+$", "");
        // 去掉常见的“标题：”前缀
        s = s.replaceAll("^(标题|title|Title)\\s*[:：]\\s*", "").strip();
        // 去掉标题尾部标点
        s = s.replaceAll("[。．.,，!！?？;；:：、]+$", "").strip();
        if (s.length() > MAX_TITLE_LEN) {
            s = s.substring(0, MAX_TITLE_LEN).strip();
        }
        return s;
    }
}
