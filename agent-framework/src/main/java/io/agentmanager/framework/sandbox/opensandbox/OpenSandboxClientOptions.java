package io.agentmanager.framework.sandbox.opensandbox;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;

/**
 * OpenSandbox 沙箱配置：Server 地址、镜像、资源限制等。
 * 作为 OpenSandboxClient 的工厂（createClient()）。
 */
public class OpenSandboxClientOptions extends SandboxClientOptions {

    /**
     * 软链农场总闸显式关闸值（issue #392）：平台 env 写路径空串=删除（publish.go dropEmptyEnv
     * 三路兜底，「置空」送不出 CM），{@code off} 是平台表单可送达的关闸形态；空串语义保留给
     * 直改 CM/Deployment 的部署形态，两者行为一致（启动期一条 WARN + 包技能不投放）
     */
    public static final String SKILLS_SHARED_STORE_OFF = "off";
    public static final String SKILLS_FALLBACK_MODE_OFF = "off";
    public static final String SKILLS_FALLBACK_MODE_PUSH = "push";
    public static final String DEFAULT_SKILLS_FALLBACK_STORE_DIR = "/tmp/.skills-store";
    public static final long DEFAULT_SKILLS_FALLBACK_MAX_BYTES = 64L * 1024 * 1024;
    public static final int DEFAULT_SKILLS_FALLBACK_MAX_CONCURRENCY = 4;
    public static final long DEFAULT_SKILLS_FALLBACK_ACQUIRE_TIMEOUT_MS = 5000L;

    private static final Pattern SAFE_ABS_PATH = Pattern.compile("^/[A-Za-z0-9._/\\-]+$");

    /**
     * 农场总闸关闸判定（§6.4，单一事实源）：null/空白或 {@code off}（忽略大小写与首尾空白，
     * 对齐表单人工输入与 SkillsFarm 下游既有的 store trim 语义）= 关闸。SkillsFarm.enabled()
     * 与 AgentScopeConfig 启动 WARN 共用本谓词，禁在两处重复字面量
     */
    public static boolean skillsSharedStoreOff(String store) {
        return store == null || store.isBlank()
            || SKILLS_SHARED_STORE_OFF.equalsIgnoreCase(store.trim());
    }

    public static boolean skillsFallbackPushEnabled(String mode) {
        return mode != null && SKILLS_FALLBACK_MODE_PUSH.equalsIgnoreCase(mode.trim());
    }

    public static boolean isValidFallbackStoreDir(String dir) {
        if (dir == null || dir.isBlank()) {
            return false;
        }
        String trimmed = dir.trim();
        if (!trimmed.startsWith("/")
                || trimmed.endsWith("/")
                || !SAFE_ABS_PATH.matcher(trimmed).matches()) {
            return false;
        }
        String[] parts = trimmed.substring(1).split("/", -1);
        if (parts.length < 2) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                return false;
            }
        }
        return true;
    }

    public static String normalizeStorePath(String dir) {
        if (dir == null) {
            return "";
        }
        String trimmed = dir.trim();
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static boolean fallbackStoreCollidesWithPrimary(String fallbackDir, String primaryDir) {
        String fb = normalizeStorePath(fallbackDir);
        String primary = normalizeStorePath(primaryDir);
        if (fb.isEmpty() || primary.isEmpty() || SKILLS_SHARED_STORE_OFF.equalsIgnoreCase(primary)) {
            return false;
        }
        return fb.equals(primary)
            || fb.startsWith(primary + "/")
            || primary.startsWith(fb + "/");
    }

    private String serverUrl;
    private String apiKey;
    private String image = "opensandbox/code-interpreter:v1.1.0";
    private Duration timeout = Duration.ofMinutes(60);
    /** 建箱/管理面 HTTP 请求超时（oa-1110-repro-fixes-design §2.3b）；null = 走 SDK 默认 */
    private Duration requestTimeout;
    private List<String> entrypoint = List.of("/opt/code-interpreter/code-interpreter.sh");
    private Map<String, String> resource = Map.of("cpu", "1", "memory", "1024Mi");
    private Map<String, String> environment = new HashMap<>();
    private String workspaceRoot = "/workspace";
    /** 技能禁用判定（oa-1110-repro-fixes-design §2.1a）：scoped .skills-cache 投放过滤；null = 不过滤 */
    private SkillDisabledChecker skillDisabledChecker;
    /** 软链农场开关（skills-syncd §6.4）：沙箱内共享库路径，缺省 /skills-store；关闸形态见 {@link #skillsSharedStoreOff} */
    private String skillsSharedStore;
    /** 端点指纹服务（skills-syncd §7.2 版本硬校验数据源，D-4 短 TTL）；null = 端点不可达 */
    private SkillBundleService skillBundleService;
    /** 农场判定打点（D-5）；null = 不打点 */
    private io.micrometer.core.instrument.MeterRegistry farmMeterRegistry;
    private boolean skillRevisionGateEnabled = true;
    private boolean syncSnapshotDiffEnabled = true;
    private String skillsFallbackMode = SKILLS_FALLBACK_MODE_OFF;
    private String skillsFallbackStoreDir = DEFAULT_SKILLS_FALLBACK_STORE_DIR;
    private long skillsFallbackMaxBytes = DEFAULT_SKILLS_FALLBACK_MAX_BYTES;
    private int skillsFallbackMaxConcurrency = DEFAULT_SKILLS_FALLBACK_MAX_CONCURRENCY;
    private long skillsFallbackAcquireTimeoutMs = DEFAULT_SKILLS_FALLBACK_ACQUIRE_TIMEOUT_MS;
    private boolean skillsFallbackOnVersionMismatch = true;
    private Semaphore skillsFallbackSemaphore;

    @Override
    public String getType() {
        return "opensandbox";
    }

    @Override
    public SandboxClient<?> createClient() {
        return new OpenSandboxClient(this);
    }

    @Override
    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    // ---- getters ----
    public String getServerUrl() { return serverUrl; }
    public String getApiKey() { return apiKey; }
    public String getImage() { return image; }
    public Duration getTimeout() { return timeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public List<String> getEntrypoint() { return entrypoint; }
    public Map<String, String> getResource() { return resource; }
    public Map<String, String> getEnvironment() { return environment; }
    public SkillDisabledChecker getSkillDisabledChecker() { return skillDisabledChecker; }
    public String getSkillsSharedStore() { return skillsSharedStore; }
    public SkillBundleService getSkillBundleService() { return skillBundleService; }
    public io.micrometer.core.instrument.MeterRegistry getFarmMeterRegistry() { return farmMeterRegistry; }
    public boolean isSkillRevisionGateEnabled() { return skillRevisionGateEnabled; }
    public boolean isSyncSnapshotDiffEnabled() { return syncSnapshotDiffEnabled; }
    public String getSkillsFallbackMode() { return skillsFallbackMode; }
    public String getSkillsFallbackStoreDir() { return skillsFallbackStoreDir; }
    public long getSkillsFallbackMaxBytes() { return skillsFallbackMaxBytes; }
    public int getSkillsFallbackMaxConcurrency() { return skillsFallbackMaxConcurrency; }
    public long getSkillsFallbackAcquireTimeoutMs() { return skillsFallbackAcquireTimeoutMs; }
    public boolean isSkillsFallbackOnVersionMismatch() { return skillsFallbackOnVersionMismatch; }
    public Semaphore getSkillsFallbackSemaphore() { return skillsFallbackSemaphore; }

    // ---- fluent builder methods ----
    public OpenSandboxClientOptions serverUrl(String serverUrl) { this.serverUrl = serverUrl; return this; }
    public OpenSandboxClientOptions apiKey(String apiKey) { this.apiKey = apiKey; return this; }
    public OpenSandboxClientOptions image(String image) { this.image = image; return this; }
    public OpenSandboxClientOptions timeout(Duration timeout) { this.timeout = timeout; return this; }
    public OpenSandboxClientOptions requestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; return this; }
    public OpenSandboxClientOptions entrypoint(List<String> entrypoint) { this.entrypoint = entrypoint; return this; }
    public OpenSandboxClientOptions resource(Map<String, String> resource) { this.resource = resource; return this; }
    public OpenSandboxClientOptions environment(Map<String, String> env) { this.environment = env; return this; }
    public OpenSandboxClientOptions workspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; return this; }
    public OpenSandboxClientOptions skillDisabledChecker(SkillDisabledChecker checker) { this.skillDisabledChecker = checker; return this; }
    public OpenSandboxClientOptions skillsSharedStore(String store) { this.skillsSharedStore = store; return this; }
    public OpenSandboxClientOptions skillBundleService(SkillBundleService service) { this.skillBundleService = service; return this; }
    public OpenSandboxClientOptions farmMeterRegistry(io.micrometer.core.instrument.MeterRegistry registry) { this.farmMeterRegistry = registry; return this; }
    public OpenSandboxClientOptions skillRevisionGateEnabled(boolean enabled) { this.skillRevisionGateEnabled = enabled; return this; }
    public OpenSandboxClientOptions syncSnapshotDiffEnabled(boolean enabled) { this.syncSnapshotDiffEnabled = enabled; return this; }
    public OpenSandboxClientOptions skillsFallbackMode(String mode) { this.skillsFallbackMode = mode; return this; }
    public OpenSandboxClientOptions skillsFallbackStoreDir(String dir) { this.skillsFallbackStoreDir = dir; return this; }
    public OpenSandboxClientOptions skillsFallbackMaxBytes(long maxBytes) { this.skillsFallbackMaxBytes = maxBytes; return this; }
    public OpenSandboxClientOptions skillsFallbackMaxConcurrency(int maxConcurrency) { this.skillsFallbackMaxConcurrency = maxConcurrency; return this; }
    public OpenSandboxClientOptions skillsFallbackAcquireTimeoutMs(long timeoutMs) { this.skillsFallbackAcquireTimeoutMs = timeoutMs; return this; }
    public OpenSandboxClientOptions skillsFallbackOnVersionMismatch(boolean enabled) { this.skillsFallbackOnVersionMismatch = enabled; return this; }
    public OpenSandboxClientOptions skillsFallbackSemaphore(Semaphore semaphore) { this.skillsFallbackSemaphore = semaphore; return this; }
}
