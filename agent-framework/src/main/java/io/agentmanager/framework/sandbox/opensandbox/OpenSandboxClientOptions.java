package io.agentmanager.framework.sandbox.opensandbox;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;

/**
 * OpenSandbox 沙箱配置：Server 地址、镜像、资源限制等。
 * 作为 OpenSandboxClient 的工厂（createClient()）。
 */
public class OpenSandboxClientOptions extends SandboxClientOptions {

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
    /** 软链农场开关（skills-syncd §6.4）：沙箱内共享库路径，空 = 农场关闭走现行为 */
    private String skillsSharedStore;
    /** 端点指纹服务（skills-syncd §7.2 版本硬校验数据源，D-4 短 TTL）；null = 端点不可达 */
    private SkillBundleService skillBundleService;
    /** 农场判定打点（D-5）；null = 不打点 */
    private io.micrometer.core.instrument.MeterRegistry farmMeterRegistry;

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
}
