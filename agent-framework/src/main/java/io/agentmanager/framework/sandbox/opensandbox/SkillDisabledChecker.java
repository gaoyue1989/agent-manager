package io.agentmanager.framework.sandbox.opensandbox;

import java.util.Set;

/**
 * 技能禁用判定（oa-1110-repro-fixes-design §2.1a）：供 scoped {@code .skills-cache} 投放层
 * 过滤已禁用技能目录，堵「load_skill_through_path 已拦截、但容器内文件可被
 * read_file/execute 直读」的旁路。
 *
 * <p>实现必须 fail-soft：任何判定异常按「未禁用/空集」处理，绝不因禁用集读取失败
 * 中断投放或扩大禁用面。
 */
public interface SkillDisabledChecker {

    /** L2 包级禁用名集合（configDir/skills/.skill-states.json）；读取失败返回空集 */
    Set<String> packageDisabled();

    /** 该用户该技能是否被 L4 禁用（/{name}/.disabled 标记）；判定失败返回 false */
    boolean isUserDisabled(String userId, String name);
}
