package io.agentmanager.framework.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.ChatModelFactory;
import io.agentscope.core.model.Model;

/**
 * 模型目录：系统模型（LLM_* 环境变量）+ 托管模型（model_config 表）的统一入口。
 *
 * <p>职责（设计见 docs/session-model-switch-design.md）：
 * <ul>
 *   <li>{@link #options()} —— GET /models 可选模型列表（前端 picker / debug 管理页数据源）；</li>
 *   <li>{@link #validateSelectable(String)} —— 会话切换校验（unknown_model / model_disabled）；</li>
 *   <li>{@link #resolve(String)} —— 会话路由解析模型实例（供 SessionModelMiddleware）；</li>
 *   <li>{@link #systemModel()} —— 系统模型实例（标题生成 / 连接测试）。</li>
 * </ul>
 *
 * <p><b>一致性策略</b>：托管模型实例按 id 惰性构建 + TTL 缓存；本副本 CRUD 后经
 * {@link #invalidate(String)} 立即失效，其他副本 ≤TTL 收敛（配置变更低频，足够）。
 * 会话→模型的映射（session_user.model）不在此缓存，由调用方实时读库保证强一致。
 *
 * <p>会话链路的解析失败（记录被删/禁用/DB 抖动）一律回落默认模型（fail-soft），
 * 不阻断对话；管理接口直接走 {@link ModelConfigStore} 如实报错。
 */
@Service
public class ModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalog.class);

    /** 系统模型（默认对话模型）的保留 id：会话 model 字段传 "system"（或空串）等价于清除覆盖 */
    public static final String SYSTEM_MODEL_ID = "system";

    /** 托管模型实例缓存 TTL（多副本配置变更收敛窗口，毫秒） */
    static final long DEFAULT_CACHE_TTL_MILLIS = 30_000L;

    private static final String SOURCE_SYSTEM = "system";
    private static final String SOURCE_MANAGED = "managed";

    /**
     * 可选模型项（GET /models 返回；不暴露 apiKey 等连接细节）。
     * 字段名显式转 snake_case：响应体风格与本工程其余接口一致（会话/文件接口均为 snake_case）。
     */
    public record ModelOption(
        String id,
        String name,
        String provider,
        @com.fasterxml.jackson.annotation.JsonProperty("model_id") String modelId,
        @com.fasterxml.jackson.annotation.JsonProperty("is_default") boolean isDefault,
        String source,
        boolean enabled
    ) {}

    private final ModelConfigStore store;
    private final AgentManagerProperties.LLMConfig systemLlm;
    private final AgentManagerProperties.HarnessConfig harness;
    private final long cacheTtlMillis;

    /** id → 已构建实例（含加载时间，TTL 过期后重查库） */
    private final ConcurrentHashMap<String, CachedModel> cache = new ConcurrentHashMap<>();

    private volatile Model systemModel;

    private record CachedModel(Model model, long loadedAtMillis) {}

    /**
     * Spring 装配入口（必须显式 @Autowired：本类另有测试用包级构造，
     * 多构造器且无标注时 Spring 会回落到无参构造并抛 NoSuchMethodException）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ModelCatalog(ModelConfigStore store, AgentManagerProperties props) {
        this(store, props, DEFAULT_CACHE_TTL_MILLIS);
    }

    /** 测试可指定 TTL（生产用 {@link #DEFAULT_CACHE_TTL_MILLIS}） */
    ModelCatalog(ModelConfigStore store, AgentManagerProperties props, long cacheTtlMillis) {
        this.store = store;
        this.systemLlm = props.llm() != null ? props.llm()
            : new AgentManagerProperties.LLMConfig("", "", "", "openai", 0.7, 4096, 120, true, 0, "", null);
        this.harness = props.harness() != null ? props.harness()
            : AgentManagerProperties.HarnessConfig.defaults();
        this.cacheTtlMillis = cacheTtlMillis;
        log.info("ModelCatalog ready: system model='{}', cache TTL={}ms", systemModelId(), cacheTtlMillis);
    }

    /** 系统模型名（LLM_MODEL_ID；未配置为空串） */
    public String systemModelId() {
        return systemLlm.modelId() != null ? systemLlm.modelId() : "";
    }

    /** 系统模型原始配置（管理视图展示用；api_key 由调用方掩码，不得原样输出） */
    public AgentManagerProperties.LLMConfig systemLlmConfig() {
        return systemLlm;
    }

    /** 会话 model 字段是否等价于"默认（系统）模型"：空串 / "system" */
    public static boolean isSystemSelection(String modelId) {
        return modelId == null || modelId.isBlank() || SYSTEM_MODEL_ID.equals(modelId.trim());
    }

    /**
     * 系统模型实例（惰性构建，进程内单例）。
     * 用途：会话标题生成（SessionTitleService）与 /models/{id}/test 的 system 项。
     */
    public Model systemModel() {
        var current = systemModel;
        if (current == null) {
            synchronized (this) {
                if (systemModel == null) {
                    systemModel = new RequestBodyLoggingModelWrapper(
                        ChatModelFactory.build(systemLlm, harness));
                }
                current = systemModel;
            }
        }
        return current;
    }

    /** 会话可选模型列表：系统模型（默认，首位）+ 启用的托管模型 */
    public List<ModelOption> options() {
        return options(false);
    }

    /**
     * 模型列表。
     *
     * @param includeDisabled true = 含禁用的托管模型（debug 管理视图，需能看到并恢复它们）；
     *                        false = 仅启用项（会话 picker 数据源）
     */
    public List<ModelOption> options(boolean includeDisabled) {
        var list = new ArrayList<ModelOption>();
        list.add(new ModelOption(SYSTEM_MODEL_ID, systemModelId() + "（系统）", systemLlm.provider(),
            systemModelId(), true, SOURCE_SYSTEM, true));
        for (var cfg : includeDisabled ? store.listAll() : store.listEnabled()) {
            list.add(new ModelOption(cfg.id(), cfg.name(), cfg.provider(), cfg.modelId(),
                false, SOURCE_MANAGED, cfg.enabled()));
        }
        return list;
    }

    /**
     * 校验会话模型选择（会话切换接口共用）。
     *
     * @return null = 通过；"unknown_model" / "model_disabled" = 拒绝原因
     */
    public String validateSelectable(String modelId) {
        if (isSystemSelection(modelId)) {
            return null;
        }
        var cfg = store.findById(modelId.trim());
        if (cfg.isEmpty()) {
            return "unknown_model";
        }
        return cfg.get().enabled() ? null : "model_disabled";
    }

    /**
     * 解析会话绑定的模型实例（SessionModelMiddleware 调用）。
     * 未设置/系统选择/未知/已禁用 → 空，调用方回落默认模型。
     * 查库异常同样回落（fail-soft：配置表抖动不阻断对话）。
     */
    public Optional<Model> resolve(String modelId) {
        var id = modelId == null ? "" : modelId.trim();
        if (isSystemSelection(id)) {
            return Optional.empty();
        }
        var now = System.currentTimeMillis();
        var cached = cache.get(id);
        if (cached != null && now - cached.loadedAtMillis() < cacheTtlMillis) {
            return Optional.of(cached.model());
        }
        try {
            var cfg = store.findById(id);
            if (cfg.isEmpty() || !cfg.get().enabled()) {
                cache.remove(id);
                return Optional.empty();
            }
            var model = build(cfg.get());
            cache.put(id, new CachedModel(model, now));
            return Optional.of(model);
        } catch (Exception e) {
            log.warn("ModelCatalog: resolve failed for '{}', fall back to default model: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    /** 连接测试用实例：system 或任意托管配置（不校验 enabled——用于验证连不通的配置） */
    public Optional<Model> modelForTest(String modelId) {
        if (isSystemSelection(modelId)) {
            return Optional.of(systemModel());
        }
        return store.findById(modelId.trim()).map(this::build);
    }

    /** CRUD 后失效本副本缓存（其他副本由 TTL 收敛） */
    public void invalidate(String modelId) {
        if (modelId != null && !modelId.isBlank()) {
            cache.remove(modelId.trim());
        }
    }

    /** 托管配置 → Model 实例（api_key 留空回落系统密钥） */
    private Model build(ModelConfigStore.ModelConfig cfg) {
        var apiKey = cfg.apiKey() != null && !cfg.apiKey().isBlank()
            ? cfg.apiKey() : systemLlm.apiKey();
        var llm = new AgentManagerProperties.LLMConfig(
            apiKey,
            cfg.modelId(),
            cfg.baseUrl(),
            cfg.provider() != null && !cfg.provider().isBlank() ? cfg.provider() : "openai",
            cfg.temperature(),
            cfg.maxTokens(),
            cfg.timeoutSeconds(),
            cfg.enableThinking(),
            cfg.contextLength(),
            cfg.reasoningEffort(),
            cfg.frequencyPenalty()
        );
        return new RequestBodyLoggingModelWrapper(ChatModelFactory.build(llm, harness));
    }
}
