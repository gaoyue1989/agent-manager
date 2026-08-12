package io.agentmanager.framework.sandbox.opensandbox;

import io.agentscope.harness.agent.sandbox.SandboxState;

/**
 * OpenSandbox 沙箱状态：记录 sandboxId 用于 resume 恢复。
 * 经 Jackson 序列化持久化到 AgentStateStore（agent_state 表）。
 */
public class OpenSandboxState extends SandboxState {
    private String sandboxId;
    private String sandboxEndpoint;
    private String userId;
    private String image;
    private long createdAt;

    public String getSandboxId() { return sandboxId; }
    public void setSandboxId(String sandboxId) { this.sandboxId = sandboxId; }

    public String getSandboxEndpoint() { return sandboxEndpoint; }
    public void setSandboxEndpoint(String sandboxEndpoint) { this.sandboxEndpoint = sandboxEndpoint; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
