package io.agentmanager.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * OpenViking 记忆供给配置（openviking-memory-integration-design §11.1，全部经服务 env
 * 由平台发布链路注入，见 backend applyOpenVikingEnv；用户不可直设的保留键不在本配置面）。
 *
 * <p>记忆供给切换（§11.1 矩阵）：{@code AGENT_MEMORY_PROVIDER=local}（默认，SDK 文件
 * 记忆现状）/ {@code openviking}（记忆全链路走 OpenViking：首推理 search(context) 注入 +
 * memory_search/get/save 工具桥接 + 提取提交）。切换 = update_env + rollout，无运行时热切。
 *
 * <p>注册于 {@code AgentFrameworkApplication} 的 {@code @EnableConfigurationProperties}；
 * provider=openviking 但 url/account 缺失时 {@link #enabled()}=false 按 local 处理
 * （启动 warn，不 fail-fast——记忆是增强能力，配置不完整降级为无 OpenViking）。
 */
@ConfigurationProperties(prefix = "agent.viking")
public record VikingConfig(
    /** 记忆供给（AGENT_MEMORY_PROVIDER）：local | openviking */
    @DefaultValue("local") String memoryProvider,
    /** OpenViking 数据面地址（OPENVIKING_URL），如 http://openviking-svc:1933 */
    @DefaultValue("") String url,
    /** 本服务 account（OPENVIKING_ACCOUNT，与 k8s 名同源派生） */
    @DefaultValue("") String account,
    /** 服务身份 ADMIN key（OVK_ADMIN_KEY，服务 Secret；MCP 共享上下文连接 + lazy 开户） */
    @DefaultValue("") String adminKey,
    /** per-user key 推导种子（OVK_USER_SEED，服务 Secret） */
    @DefaultValue("") String userSeed,
    /**
     * 会话归档+自动提取（OVK_SESSION_COMMIT，默认 false）：true = 每 turn 把新增消息
     * append 到 OpenViking session（auto_commit_policy 服务端调度提取）；false = 逐 turn
     * remember() 提交。二者互斥（§11.1）。
     */
    @DefaultValue("false") boolean sessionCommit,
    /**
     * 服务级共享上下文 MCP 注入（OVK_MCP_SHARED_CONTEXT，默认 false）：true = 构建期
     * 向 toolkit 注入合成 MCP server openviking（服务身份连接），暴露 find/search/read/
     * rule_search 等服务级工具（account 共享 resources/skills；per-user 记忆不走 MCP）。
     */
    @DefaultValue("false") boolean mcpSharedContext,
    /** 首推理检索超时（毫秒）：超时降级为无记忆继续（fail-soft，§4.6 缓解②） */
    @DefaultValue("2000") int searchTimeoutMs,
    /** 首推理注入结果的会话内缓存秒数（同用户多 turn 复用，摊薄热路径延迟） */
    @DefaultValue("300") int injectCacheTtlSeconds
) {

    public static final String MEMORY_PROVIDER_OPENVIKING = "openviking";

    /** OpenViking 记忆供给是否生效：provider=openviking 且 url/account/adminKey/seed 齐备 */
    public boolean enabled() {
        return MEMORY_PROVIDER_OPENVIKING.equals(memoryProvider)
            && !url.isBlank() && !account.isBlank()
            && !adminKey.isBlank() && !userSeed.isBlank();
    }
}
