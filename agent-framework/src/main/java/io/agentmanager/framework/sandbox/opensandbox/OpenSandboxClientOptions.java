package io.agentmanager.framework.sandbox.opensandbox;

import java.time.Duration;
import java.util.HashMap;
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
    private Map<String, String> resource = Map.of("cpu", "1", "memory", "1024Mi");
    private Map<String, String> environment = new HashMap<>();
    private String workspaceRoot = "/workspace";

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
    public Map<String, String> getResource() { return resource; }
    public Map<String, String> getEnvironment() { return environment; }

    // ---- fluent builder methods ----
    public OpenSandboxClientOptions serverUrl(String serverUrl) { this.serverUrl = serverUrl; return this; }
    public OpenSandboxClientOptions apiKey(String apiKey) { this.apiKey = apiKey; return this; }
    public OpenSandboxClientOptions image(String image) { this.image = image; return this; }
    public OpenSandboxClientOptions timeout(Duration timeout) { this.timeout = timeout; return this; }
    public OpenSandboxClientOptions resource(Map<String, String> resource) { this.resource = resource; return this; }
    public OpenSandboxClientOptions environment(Map<String, String> env) { this.environment = env; return this; }
    public OpenSandboxClientOptions workspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; return this; }
}
