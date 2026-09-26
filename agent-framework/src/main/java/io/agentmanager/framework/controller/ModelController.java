package io.agentmanager.framework.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.agentmanager.framework.service.ModelCatalog;
import io.agentmanager.framework.service.ModelConfigStore;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;

/**
 * 模型管理 API（设计见 docs/session-model-switch-design.md §4.1）：
 * 系统模型（LLM_* 环境变量，只读）+ 托管模型（model_config 表，CRUD）统一在此暴露。
 *
 * <ul>
 *   <li>{@code GET /models} —— 可选模型列表（前端 picker / debug 管理页数据源）</li>
 *   <li>{@code GET /models/{id}} —— 详情（api_key 掩码）</li>
 *   <li>{@code POST /models} / {@code PATCH /models/{id}} / {@code DELETE /models/{id}} —— 托管模型 CRUD</li>
 *   <li>{@code POST /models/{id}/test} —— 连接测试（真实发起一次最小 completion）</li>
 * </ul>
 *
 * <p>风格对齐现有 API：请求体 camelCase（record 组件直出）、响应体 snake_case、
 * 错误 = 状态码 + {@code {"error": "<机器码>", "message": "<人读>"}}（同 ConfirmController）。
 */
@RestController
@RequestMapping("/models")
public class ModelController {

    private static final Logger log = LoggerFactory.getLogger(ModelController.class);

    /** 连接测试超时（秒） */
    static final int PROBE_TIMEOUT_SECONDS = 60;
    /** 连接测试输出 token 上限 */
    static final int PROBE_MAX_TOKENS = 16;
    /** 连接测试回显的响应文本上限（字符） */
    static final int PROBE_REPLY_MAX_CHARS = 200;

    /** 字段长度上限（与 model_config DDL 对齐，超限直接 400 而非 DB 报错） */
    private static final int MAX_NAME_LEN = 128;
    private static final int MAX_MODEL_ID_LEN = 128;
    private static final int MAX_BASE_URL_LEN = 512;
    private static final int MAX_API_KEY_LEN = 512;
    private static final int MAX_REASONING_EFFORT_LEN = 16;

    /** provider 方言值域（写入路径严格校验；方言映射见 ChatModelFactory.applyDialect，未知存量值读取时按 openai 兜底） */
    private static final java.util.Set<String> PROVIDER_DIALECTS =
        java.util.Set.of("openai", "vllm", "sglang", "glm", "deepseek");
    /** 错误消息用稳定排序（Set 迭代顺序不确定，同一非法值的提示会漂移） */
    private static final List<String> PROVIDER_DIALECTS_SORTED =
        PROVIDER_DIALECTS.stream().sorted().toList();

    private final ModelConfigStore store;
    private final ModelCatalog catalog;

    public ModelController(ModelConfigStore store, ModelCatalog catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    /** 可选模型列表（系统模型 + 启用的托管模型） */
    /**
     * 可选模型列表（系统模型 + 托管模型）。
     *
     * @param all true = 含禁用项（debug 管理视图）；缺省仅返回启用项（会话 picker 语义）
     */
    @GetMapping
    public Map<String, Object> listModels(
            @RequestParam(value = "all", required = false, defaultValue = "false") boolean all) {
        return Map.of(
            "default_model", ModelCatalog.SYSTEM_MODEL_ID,
            "models", catalog.options(all));
    }

    /** 模型详情（托管模型含掩码 key；系统模型只读展示） */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getModel(@PathVariable String id) {
        if (ModelCatalog.isSystemSelection(id)) {
            return ResponseEntity.ok(systemModelView());
        }
        return store.findById(id)
            .map(cfg -> ResponseEntity.ok(managedView(cfg)))
            .orElseGet(() -> notFound(id));
    }

    /** 新增托管模型（name/modelId/baseUrl 必填） */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createModel(@RequestBody UpsertRequest body) {
        var name = trim(body.name());
        var modelId = trim(body.modelId());
        var baseUrl = trim(body.baseUrl());
        var provider = trim(body.provider());
        if (name.isEmpty() || modelId.isEmpty() || baseUrl.isEmpty()) {
            return invalid("name, modelId and baseUrl are required");
        }
        var lengthError = validateLengths(name, modelId, baseUrl, body.apiKey());
        if (lengthError != null) {
            return invalid(lengthError);
        }
        var paramError = validateSamplingParams(provider, body.reasoningEffort(),
            body.frequencyPenalty());
        if (paramError != null) {
            return invalid(paramError);
        }
        if (store.findByName(name).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "duplicate_name", "message", "model name already exists: " + name));
        }
        var cfg = new ModelConfigStore.ModelConfig(
            UUID.randomUUID().toString(), name,
            provider.isEmpty() ? "openai" : provider,
            modelId, baseUrl, normKey(body.apiKey()),
            defaultIfNull(body.temperature(), 0.3),
            defaultIfNull(body.maxTokens(), 16384),
            defaultIfNull(body.timeoutSeconds(), 120),
            defaultIfNull(body.enableThinking(), false),
            normEffort(body.reasoningEffort()),
            body.frequencyPenalty(),
            defaultIfNull(body.contextLength(), 0),
            defaultIfNull(body.enabled(), true),
            null, null);
        store.insert(cfg);
        catalog.invalidate(cfg.id());
        return ResponseEntity.ok(managedView(cfg));
    }

    /**
     * 更新托管模型（字段缺省=不变；apiKey 传空串=清除、回落系统密钥）。
     * 系统模型 → 400 system_model_readonly。
     */
    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateModel(@PathVariable String id,
                                                           @RequestBody UpsertRequest body) {
        if (ModelCatalog.isSystemSelection(id)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "system_model_readonly",
                "message", "system model is configured via LLM_* env vars and cannot be modified"));
        }
        var existing = store.findById(id);
        if (existing.isEmpty()) {
            return notFound(id);
        }
        var old = existing.get();

        var name = body.name() != null && !body.name().isBlank() ? body.name().trim() : old.name();
        var modelId = body.modelId() != null && !body.modelId().isBlank() ? body.modelId().trim() : old.modelId();
        var baseUrl = body.baseUrl() != null && !body.baseUrl().isBlank() ? body.baseUrl().trim() : old.baseUrl();
        var lengthError = validateLengths(name, modelId, baseUrl, body.apiKey());
        if (lengthError != null) {
            return invalid(lengthError);
        }
        var paramError = validateSamplingParams(body.provider(), body.reasoningEffort(),
            body.frequencyPenalty());
        if (paramError != null) {
            return invalid(paramError);
        }
        // 改名唯一性：命中其他配置即冲突
        if (!name.equals(old.name()) && store.findByName(name).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "duplicate_name", "message", "model name already exists: " + name));
        }
        // apiKey 三态：缺省=不变；空串=清除；非空=替换
        var apiKey = body.apiKey() == null ? old.apiKey() : normKey(body.apiKey());
        // reasoningEffort 三态：缺省=不变；空串=清除（null，不下发）；非空=替换（已通过格式校验）
        var reasoningEffort = body.reasoningEffort() == null
            ? old.reasoningEffort() : normEffort(body.reasoningEffort());
        // frequencyPenalty 二态：缺省=不变；非 null=替换（不支持撤销下发，见设计文档 D4）
        var frequencyPenalty = body.frequencyPenalty() == null
            ? old.frequencyPenalty() : body.frequencyPenalty();

        var cfg = new ModelConfigStore.ModelConfig(
            old.id(), name,
            body.provider() != null && !body.provider().isBlank() ? body.provider().trim() : old.provider(),
            modelId, baseUrl, apiKey,
            body.temperature() != null ? body.temperature() : old.temperature(),
            body.maxTokens() != null ? body.maxTokens() : old.maxTokens(),
            body.timeoutSeconds() != null ? body.timeoutSeconds() : old.timeoutSeconds(),
            body.enableThinking() != null ? body.enableThinking() : old.enableThinking(),
            reasoningEffort,
            frequencyPenalty,
            body.contextLength() != null ? body.contextLength() : old.contextLength(),
            body.enabled() != null ? body.enabled() : old.enabled(),
            old.createdAt(), old.updatedAt());
        store.update(cfg);
        catalog.invalidate(cfg.id());
        return ResponseEntity.ok(managedView(cfg));
    }

    /**
     * 删除托管模型：直接删行（决策#4）。引用它的会话在下次模型调用时回落默认模型，
     * 后续新请求可随时切换到其他模型。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteModel(@PathVariable String id) {
        if (ModelCatalog.isSystemSelection(id)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "system_model_readonly",
                "message", "system model is configured via LLM_* env vars and cannot be deleted"));
        }
        if (!store.deleteById(id)) {
            return notFound(id);
        }
        catalog.invalidate(id);
        return ResponseEntity.ok(Map.of("id", id, "deleted", true));
    }

    /** 连接测试：真实发起一次最小 completion（max_tokens=16），返回时延与响应摘要 */
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testModel(@PathVariable String id) {
        var model = catalog.modelForTest(id);
        if (model.isEmpty()) {
            return notFound(id);
        }
        var start = System.nanoTime();
        try {
            var msg = new UserMessage("user", "ping");
            var options = GenerateOptions.builder()
                .temperature(0.0)
                .maxTokens(PROBE_MAX_TOKENS)
                .build();
            var responses = model.get().stream(List.of(msg), List.of(), options)
                .collectList()
                .block(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS));
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            var reply = truncate(extractText(responses), PROBE_REPLY_MAX_CHARS);
            var result = new LinkedHashMap<String, Object>();
            result.put("id", id);
            result.put("ok", true);
            result.put("latency_ms", latencyMs);
            result.put("reply", reply);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("model test failed (id={}, {}ms): {}", id, latencyMs, e.getMessage());
            var result = new LinkedHashMap<String, Object>();
            result.put("id", id);
            result.put("ok", false);
            result.put("latency_ms", latencyMs);
            result.put("error", "model_test_failed");
            result.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return ResponseEntity.status(502).body(result);
        }
    }

    // ===== 视图/校验辅助 =====

    /** 系统模型视图（只读；api_key 掩码） */
    private Map<String, Object> systemModelView() {
        var llm = catalog.systemLlmConfig();
        var m = new LinkedHashMap<String, Object>();
        m.put("id", ModelCatalog.SYSTEM_MODEL_ID);
        m.put("name", catalog.systemModelId() + "（系统）");
        m.put("provider", llm.provider());
        m.put("model_id", llm.modelId());
        m.put("base_url", llm.baseUrl());
        m.put("api_key_masked", maskKey(llm.apiKey()));
        m.put("temperature", llm.temperature());
        m.put("max_tokens", llm.maxTokens());
        m.put("timeout_seconds", llm.timeout());
        m.put("enable_thinking", llm.enableThinking());
        m.put("reasoning_effort", blankToNull(llm.reasoningEffort()));
        m.put("frequency_penalty", llm.frequencyPenalty());
        m.put("context_length", llm.contextLength());
        m.put("enabled", true);
        m.put("is_default", true);
        m.put("source", "system");
        m.put("read_only", true);
        return m;
    }

    /** 托管模型视图（api_key 掩码；明文不出接口） */
    private Map<String, Object> managedView(ModelConfigStore.ModelConfig cfg) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", cfg.id());
        m.put("name", cfg.name());
        m.put("provider", cfg.provider());
        m.put("model_id", cfg.modelId());
        m.put("base_url", cfg.baseUrl());
        m.put("api_key_masked", maskKey(cfg.apiKey()));
        m.put("temperature", cfg.temperature());
        m.put("max_tokens", cfg.maxTokens());
        m.put("timeout_seconds", cfg.timeoutSeconds());
        m.put("enable_thinking", cfg.enableThinking());
        m.put("reasoning_effort", blankToNull(cfg.reasoningEffort()));
        m.put("frequency_penalty", cfg.frequencyPenalty());
        m.put("context_length", cfg.contextLength());
        m.put("enabled", cfg.enabled());
        m.put("is_default", false);
        m.put("source", "managed");
        m.put("read_only", false);
        m.put("created_at", cfg.createdAt() != null ? cfg.createdAt().toString() : "");
        m.put("updated_at", cfg.updatedAt() != null ? cfg.updatedAt().toString() : "");
        return m;
    }

    /** api_key 掩码：保留前缀 3 位 + 尾 4 位；过短只留 *** */
    static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.length() <= 8) {
            return "***";
        }
        return key.substring(0, 3) + "***" + key.substring(key.length() - 4);
    }

    private static ResponseEntity<Map<String, Object>> notFound(String id) {
        return ResponseEntity.status(404).body(Map.of(
            "error", "model_not_found", "message", "model not found: " + id));
    }

    private static ResponseEntity<Map<String, Object>> invalid(String message) {
        return ResponseEntity.badRequest().body(Map.of(
            "error", "invalid_config", "message", message));
    }

    /** 长度边界校验（与 DDL 对齐，超限 400 而非落库报错） */
    private static String validateLengths(String name, String modelId, String baseUrl, String apiKey) {
        if (name.length() > MAX_NAME_LEN) {
            return "name exceeds " + MAX_NAME_LEN + " chars";
        }
        if (modelId.length() > MAX_MODEL_ID_LEN) {
            return "modelId exceeds " + MAX_MODEL_ID_LEN + " chars";
        }
        if (baseUrl.length() > MAX_BASE_URL_LEN) {
            return "baseUrl exceeds " + MAX_BASE_URL_LEN + " chars";
        }
        if (apiKey != null && apiKey.length() > MAX_API_KEY_LEN) {
            return "apiKey exceeds " + MAX_API_KEY_LEN + " chars";
        }
        return null;
    }

    /**
     * 采样参数校验（POST 全量 / PATCH 仅校验本次传入项）：
     * provider 方言值域、reasoningEffort 格式（小写字母数字下划线，值集因模型而异不做白名单）、
     * frequencyPenalty 范围（OpenAI 语义 [-2.0, 2.0]）。
     *
     * @return null = 通过；非 null = 400 message
     */
    private static String validateSamplingParams(String provider, String reasoningEffort,
                                                 Double frequencyPenalty) {
        if (provider != null && !provider.isBlank() && !PROVIDER_DIALECTS.contains(provider.trim())) {
            return "provider must be one of " + PROVIDER_DIALECTS_SORTED + ": " + provider.trim();
        }
        if (reasoningEffort != null && !reasoningEffort.isBlank()
            && !reasoningEffort.trim().matches("^[a-z0-9_]{1," + MAX_REASONING_EFFORT_LEN + "}$")) {
            return "reasoningEffort must match ^[a-z0-9_]{1," + MAX_REASONING_EFFORT_LEN + "}$";
        }
        if (frequencyPenalty != null && (frequencyPenalty < -2.0 || frequencyPenalty > 2.0)) {
            return "frequencyPenalty must be within [-2.0, 2.0]";
        }
        return null;
    }

    /** reasoningEffort 归一：空白 → null（NULL = 不下发）；其余去空白（写入前已过格式校验） */
    private static String normEffort(String reasoningEffort) {
        if (reasoningEffort == null) {
            return null;
        }
        var trimmed = reasoningEffort.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 空串归 null（视图层：NULL = 不下发） */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 空白归一：null/空白 → null（DB 存 NULL = 回落系统密钥） */
    private static String normKey(String apiKey) {
        if (apiKey == null) {
            return null;
        }
        var trimmed = apiKey.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trim(String s) {
        return s != null ? s.trim() : "";
    }

    private static <T> T defaultIfNull(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static String extractText(List<io.agentscope.core.model.ChatResponse> responses) {
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

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        var collapsed = text.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= maxChars ? collapsed : collapsed.substring(0, maxChars);
    }

    /**
     * 新增/更新请求体（camelCase，与 ChatRequest 同风格）：
     * 新增时 name/modelId/baseUrl 必填；PATCH 时字段缺省=不变，apiKey 传空串=清除回落系统密钥，
     * reasoningEffort 传空串=清除（NULL，不下发）。
     */
    public record UpsertRequest(
        String name,
        String provider,
        String modelId,
        String baseUrl,
        String apiKey,
        Double temperature,
        Integer maxTokens,
        Integer timeoutSeconds,
        Boolean enableThinking,
        Integer contextLength,
        Boolean enabled,
        /** 推理强度：null=不变（PATCH）/不下发（POST）；空串=清除；非空经格式校验后替换 */
        String reasoningEffort,
        /** 频率惩罚：null=不变/不下发；非 null 经范围校验后替换（不可撤销下发，见设计文档 D4） */
        Double frequencyPenalty
    ) {
        /** 兼容旧调用（既有测试 11 参签名）：新采样参数缺省 = 不下发/不变 */
        public UpsertRequest(String name, String provider, String modelId, String baseUrl,
                             String apiKey, Double temperature, Integer maxTokens,
                             Integer timeoutSeconds, Boolean enableThinking, Integer contextLength,
                             Boolean enabled) {
            this(name, provider, modelId, baseUrl, apiKey, temperature, maxTokens, timeoutSeconds,
                enableThinking, contextLength, enabled, null, null);
        }
    }
}
