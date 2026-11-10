package io.agentmanager.framework.sandbox.opensandbox;

import java.util.Set;

import io.agentmanager.framework.service.SkillManageService;
import io.agentmanager.framework.service.WorkspaceReader;

/**
 * 生产装配的 {@link SkillDisabledChecker}：L2 走 {@link SkillManageService} 禁用集、
 * L4 走 {@link WorkspaceReader} 用户级 KV 标记。全程 fail-soft
 * （oa-1110-repro-fixes-design §5）：读取失败按「未禁用」处理，投放面不扩大也不中断。
 */
public class ManagedSkillDisabledChecker implements SkillDisabledChecker {

    private final SkillManageService skillManageService;
    private final WorkspaceReader workspaceReader;

    public ManagedSkillDisabledChecker(SkillManageService skillManageService,
                                       WorkspaceReader workspaceReader) {
        this.skillManageService = skillManageService;
        this.workspaceReader = workspaceReader;
    }

    @Override
    public Set<String> packageDisabled() {
        try {
            return skillManageService.getDisabledSet();
        } catch (Exception e) {
            return Set.of();
        }
    }

    @Override
    public boolean isUserDisabled(String userId, String name) {
        if (userId == null || userId.isBlank() || name == null || name.isBlank()) {
            return false;
        }
        try {
            return workspaceReader.isUserSkillDisabled(userId, name);
        } catch (Exception e) {
            return false;
        }
    }
}
