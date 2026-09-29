package io.agentmanager.framework.service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import io.agentmanager.framework.config.AgentManagerProperties;
import io.agentmanager.framework.config.OafConfigHolder;
import io.agentmanager.framework.model.OafConfig;
import io.agentmanager.framework.sandbox.opensandbox.OpenSandboxFilesystemSpec;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.subagent.RemoteAskPolicy;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.protocol.RemoteStreamDetail;

/**
 * HarnessAgent 构建工厂：AgentScopeConfig 启动装配与 OafReloadService 整包重建共用。
 *
 * <p>主体自 AgentScopeConfig.harnessAgent(...) 原样提取（行为不变）：
 * 启动路径经 AgentScopeConfig 委托调用保持兼容，reload 路径直接调用本工厂重建 agent。
 * 构建为纯函数式：传入当前配置与依赖，返回全新 HarnessAgent；失败时抛异常、
 * 不触碰任何既有 agent 状态（回滚语义由调用方保证）。
 */
@Service
public class HarnessAgentFactory {
    private static final Logger log = LoggerFactory.getLogger(HarnessAgentFactory.class);

    /**
     * Harness 内置工具注册名白名单（仅权限系统启用时生效，用于生成自带工具 ALLOW 规则）。
     * 来源：agentscope-harness 2.0.0 jar（javap 提取 @Tool name / AgentTool 实现名），
     * 与当前 builder 开关（enablePlanMode / enableSkillManageTool(true)）对齐。
     * 注意：ShellExecuteTool 的 @Tool 注解无显式 name，注册名取方法名 "execute"
     * （AGENTS.md 记载的 shell_execute 已失效）。
     * 构建后会与实际 getToolNames() 差集校验（verifyToolCoverage），SDK 升级漂移时打 ERROR 日志。
     */
    public static final Set<String> BUILT_IN_TOOL_NAMES = Set.of(
        // 文件系统 (FilesystemTool)
        "read_file", "write_file", "edit_file", "list_files", "glob_files", "grep_files",
        // 记忆 (MemorySearchTool / MemoryGetTool / MemorySaveTool)
        "memory_search", "memory_get", "memory_save",
        // 会话 (SessionSearchTool)
        "session_search", "session_list", "session_history",
        // Shell (ShellExecuteTool，方法名 execute)
        "execute",
        // Plan Mode (PlanModeTools)
        "plan_enter", "plan_write", "plan_exit",
        // 技能 (SkillManageTool / ProposeSkillTool)
        "skill_manage", "propose_skill",
        // 子 Agent (AgentSpawnTool)
        "agent_spawn", "agent_send", "agent_list",
        // 异步任务 (TaskTool / WaitAsyncResultsTool)
        "task_list", "task_output", "task_cancel", "wait_async_results",
        // 网络 (WebTool，SDK 2.0.3 实测注册；缺口径会在 DEFAULT 模式触发未覆盖 ASK——
        // order-fulfillment demo 实证，verifyToolCoverage 报 coverage gap)
        "web_fetch", "web_search",
        // 技能加载（HarnessSkillMiddleware 注册的按路径加载工具）
        "load_skill_through_path",
        // 动态子 Agent 生成（未启用时不注册，白名单冗余无害）
        "agent_generate"
    );

    private final AgentManagerProperties props;
    private final WorkspaceInitializer workspaceInitializer;
    private final McpToolRegistrar mcpToolRegistrar;
    private final List<io.agentmanager.framework.tool.CustomTool> customTools;
    private final RemoteConfirmBridge remoteConfirmBridge;

    public HarnessAgentFactory(
        AgentManagerProperties props,
        WorkspaceInitializer workspaceInitializer,
        McpToolRegistrar mcpToolRegistrar,
        List<io.agentmanager.framework.tool.CustomTool> customTools,
        // @Lazy 破 bean 环：Factory → RemoteConfirmBridge → AgentRuntimeService（@Bean）
        // → HarnessAgent（@Bean）→ Factory。代理在首次 spawn 抓取时才解析目标——
        // 发生在 context refresh 之后的运行期，启动期不触发环上的任何创建
        @Lazy RemoteConfirmBridge remoteConfirmBridge
    ) {
        this.props = props;
        this.workspaceInitializer = workspaceInitializer;
        this.mcpToolRegistrar = mcpToolRegistrar;
        this.customTools = customTools;
        this.remoteConfirmBridge = remoteConfirmBridge;
    }

    /**
     * 构建全新 HarnessAgent。
     *
     * @param oafConfig       本次构建使用的 OAF 配置（reload 时为新解析实例）
     * @param distributedStore 共享存储（启动与 reload 用同一实例）
     * @param llmLogger       LLM 调用记录
     * @param uiContextStore  UI 上下文存储
     * @param sessionUserStore 会话用户存储
     * @param sandboxSpec     沙箱 spec（未启用时 null，走 RemoteFilesystemSpec）
     */
    public HarnessAgent build(
        OafConfig oafConfig,
        DistributedStore distributedStore,
        LLMLogger llmLogger,
        UiContextStore uiContextStore,
        SessionUserStore sessionUserStore,
        io.agentmanager.framework.service.ModelCatalog modelCatalog,
        @Autowired(required = false) OpenSandboxFilesystemSpec sandboxSpec
    ) {
        var llm = props.llm();
        var harness = props.harness() != null ? props.harness() : AgentManagerProperties.HarnessConfig.defaults();

        try {
            var workspacePath = workspaceInitializer.initialize(
                Path.of(props.resolvedWorkspaceBaseDir()), oafConfig);

            var model = buildChatModel(llm, harness);

            // P0: 包装主 model，400 错误时打印请求体 JSON 诊断（排查 Higress 网关注入问题）
            var loggingModel = new io.agentmanager.framework.service.RequestBodyLoggingModelWrapper(model);

            // P1: 包装 compaction 内部 LLM 调用追踪
            // 不设置 .model() 时 harness 回退使用主 model（无 trace），设置包装后行为不变且带 span
            // （memoryModel 的构造移入下方 memoryEnabled 分支：记忆关闭时不构造）
            var compactionModel = new io.agentmanager.framework.service.TracingModelWrapper(loggingModel, "compaction");

            // 自定义 Toolkit：注册自定义工具 + MCP 工具（Harness 工具由框架自动注册）
            var toolkit = new io.agentscope.core.tool.Toolkit();
            // 可见性控制（5.2）：deniedTools 命中的自定义工具不注册（类粒度，任一 @Tool 命中即整体跳过）
            var customToolNames = new HashSet<String>();
            for (var tool : customTools) {
                var names = toolToolNames(tool);
                if (oafConfig.hasDeniedTools()
                        && names.stream().anyMatch(oafConfig.deniedTools()::contains)) {
                    log.info("Custom tool(s) {} excluded by deniedTools", names);
                    continue;
                }
                toolkit.registerTool(tool);
                customToolNames.addAll(names);
                log.info("Custom tool registered: {}", names);
            }
            mcpToolRegistrar.registerAll(toolkit, oafConfig);

            // HITL 权限上下文装配（MCP-only）：仅 MCP tools 规则或 require_confirmation 存在时启用
            var permCfg = mcpToolRegistrar.collectPermissionRules(oafConfig);
            var permissionContext = buildPermissionContext(oafConfig, permCfg, customToolNames);

            // 业务规范键解析（RuntimeContext → 规范会话/用户）：Channel 链路下 ctx.sessionId
            // 是全进程共享的网关 gw-hash、ctx.userId 是 peer，不翻译会让 LLM 记录与 span
            // 属性都记成运行时路由键——前者按 sid 查不到（issue #44），后者按会话过滤失效
            var sessionKeyResolver = new SessionKeyResolver(sessionUserStore);

            var builder = HarnessAgent.builder()
                .name(oafConfig.name())
                .sysPrompt(oafConfig.systemPrompt())
                .model(loggingModel)
                .toolkit(toolkit)
                // ReAct 推理最大轮次：SDK 默认 10 轮不足以支撑"生成 OAF 部署包"等
                // 长流程（撰写→校验→修正→打包→登记→汇报），默认放宽至 20 轮（AGENT_REACT_MAX_ITERS 可调）
                .maxIters(harness.maxIters())
                // 注意：会话模型路由必须最先注册（最外层）——先替换 model 再进链，
                // 下游 LLM 记录/span/实际调用看到的都是会话生效模型
                .middleware(new io.agentmanager.framework.service.SessionModelMiddleware(
                    sessionUserStore, modelCatalog))
                // OTel 链路追踪（SDK 内置，创建 span，order=1 默认值）
                .middleware(new io.agentscope.core.tracing.OtelTracingMiddleware())
                // 模型实际输入/输出补录到 chat span（gen_ai.input/output.messages）
                .middleware(new io.agentmanager.framework.service.ModelIoTracingMiddleware())
                // 工具调用名称/描述/入参/出参补录到 execute_tool span
                .middleware(new io.agentmanager.framework.service.ToolCallTracingMiddleware())
                // 框架级属性补充（userId/sessionId/tenant，order=0，覆盖 onAgent/onModelCall/onActing）
                .middleware(new io.agentmanager.framework.service.FrameworkTracingMiddleware(
                    oafConfig.slug(), sessionKeyResolver))
                // ReAct 推理轮次 span（order=0，覆盖 onReasoning）
                .middleware(new io.agentmanager.framework.service.ReasoningTracingMiddleware())
                // LLM 调用记录（debug 页面，order=1，默认值，保留）
                // 记录键用规范 sid：Channel 链路下 ctx.sessionId 是全进程共享的网关 gw-hash，
                // 直接用它会让所有会话的记录串到同一个桶、且按 /threads/{sid}/llm-calls
                // 永远查不到（issue #44）
                .middleware(new LlmLoggingMiddleware(llmLogger, sessionKeyResolver))
                // ToolUseBlock 完整性校验（vLLM/Qwen3 流式输出畸形 tool call 防御）
                .middleware(new ToolCallValidationMiddleware())
                // 远程子 agent 动态 DENY（travel-fulfillment F16/§6.1）：协议任务携带的
                // context.deny_rules 按工具名拦截执行；非协议轮次无该 RuntimeContext 属性，
                // 零开销直通
                .middleware(new io.agentmanager.framework.service.protocol.ProtocolDenyRulesMiddleware())
                // MCP 用户上下文注入（唯一注入点）：把生效 userId 写入 McpMeta，
                // 供 MCP 工具调用走 _meta / userHeaders 双通道（Channel 链路按 session 反查真实 userId）
                .middleware(new io.agentmanager.framework.mcp.McpUserContextMiddleware(sessionUserStore))
                // 远程子 agent 身份规范化（F10，travel-fulfillment §8 lead-4）：Channel 链路
                // ctx.userId 是网关 peer，agent_spawn 组装 RemoteSubmitContext 直取
                // ctx.getUserId()——仅在纯 spawn 轮次的 acting 作用域内经 session_user
                // 反查写回规范 userId、轮次终止即恢复（PR #62 门禁教训：onAgent 全程改写
                // 会破坏 SDK HITL 挂起/恢复的 ctx 键一致性）。只写 userId 不写 sessionId
                // （设计 §5.6）。置于 McpUserContextMiddleware 之后：后者按 peer 直接反查
                // 写入 McpMeta 的生产行为保持不变
                .middleware(new RemoteUserIdMiddleware(sessionUserStore))
                // agent_spawn 结果抓取（travel-fulfillment §5.5）：RemoteConfirmBridge 在途任务
                // 登记的唯一入口——解析 agent_spawn 结果文本的 task_id 连同入参交桥登记
                // (session → endpoint, taskId)；缺此中间件则快照轮询（F15 唯一确认源）永不生效，
                // 远程确认卡永不落库。复用上方 sessionKeyResolver 实例（turn 级 memo 共享，
                // 与追踪中间件对原始 ctx 的解析结果一致）；抓取/登记全程 fail-soft 不影响父流
                .middleware(new RemoteSpawnCaptureMiddleware(remoteConfirmBridge, sessionKeyResolver))
                // 远程 spawn 强制同步等待（§16 实测发现）：SDK 远程 spawn 恒异步受理，收割
                // 全靠模型自觉不可靠——注入 SDK force_sync 属性让 spawn 阻塞等子任务完成、
                // 结果确定性回流。仅作用于纯 spawn 轮次（混编/ask 轮次跳过），可经
                // AGENT_REMOTE_SPAWN_SYNC_WAIT=false 关闭
                .middleware(new RemoteSpawnForceSyncMiddleware(
                    props.agentProtocol() == null || props.agentProtocol().remoteSpawnSyncWait(),
                    props.agentProtocol() != null ? props.agentProtocol().remoteSpawnSyncWaitSeconds() : 120))
                // 空完成恢复（思维模型 thinking 耗尽 max_tokens 后只产出 ThinkingBlock 无实际输出时自动重试）
                .hook(new EmptyCompletionRecoveryHook())
                // UI 交互上下文注入（4.7）：PreCall 时按会话 metadata 注入 ui_context（失败不阻断）
                .hook(new UiContextInjectionHook(uiContextStore))
                .workspace(workspacePath)
                .distributedStore(distributedStore);

            // 远程子 agent 声明（travel-fulfillment B1，§8 lead-1）：OAF agents[].endpoint
            // 非空者注册为 SDK 远程声明（经 Agent Protocol 调度子服务 /tasks）；endpoint
            // 为空者不在此注册，维持 workspace subagents/*.md 本地链路（同一包可混用）。
            // headers 统一来自 AGENT_REMOTE_HEADERS_JSON（服务间认证 token 等，值走 env 不进包）。
            var remoteSubagents = buildRemoteSubagentDeclarations(oafConfig);
            if (!remoteSubagents.isEmpty()) {
                builder.subagents(remoteSubagents);
            }

            // OAF 包内技能目录注册为市场层（skill 四层优先级 L2）：
            // HarnessSkillMiddleware 每轮推理重扫目录（mtime+size 短路），PVC 上
            // /config/skills 原位变化无需重启即可在下轮生效（动态加载）。
            // writeable=false 只读分发：skill_manage/skill 目录写回被仓库层拒绝（PVC 只读）。
            var oafSkillsDir = Path.of(props.configDir()).resolve("skills");
            if (java.nio.file.Files.isDirectory(oafSkillsDir)) {
                builder.skillRepository(new io.agentscope.core.skill.repository.FileSystemSkillRepository(
                    oafSkillsDir, false, "oaf-package"));
                log.info("OAF skill repository registered (dynamic L2): {}", oafSkillsDir);
            } else {
                log.info("OAF skills dir not found, dynamic skill repository skipped: {}", oafSkillsDir);
            }

            // 沙箱模式：OpenSandboxFilesystemSpec（SANDBOX_ENABLED=true 时注入）
            // 默认模式：RemoteFilesystemSpec（共享存储，不提供 Shell）
            if (sandboxSpec != null) {
                builder.filesystem(sandboxSpec);
                // 请求级 userId 注入：框架内部 exec 不带 RuntimeContext（实测），
                // middleware 在调用链上把 userId 注入沙箱供 stop() 回写
                if (sandboxSpec.getUserKeyMiddleware() != null) {
                    builder.middleware(sandboxSpec.getUserKeyMiddleware());
                }
            } else {
                builder.filesystem(new RemoteFilesystemSpec()
                    .isolationScope(IsolationScope.USER));
            }

            // HITL 权限上下文（MCP-only，未启用时跳过装配保持零侵入）
            if (permissionContext != null) {
                builder.permissionContext(permissionContext);
            }

            // 记忆装配分支（AGENT_MEMORY_ENABLED 可调）：false 时不仅要跳过 .memory(...)，
            // 还须显式关闭记忆 hooks 与 memory_* 工具——只去掉 .memory(...) 不算"完全关闭"，
            // SDK 仍会以内置默认装配记忆钩子/工具（disableMemoryHooks + disableMemoryTools 双关）
            if (harness.memoryEnabled()) {
                // P1: 包装 memory 内部 LLM 调用追踪（flush + consolidation LLM 调用 span）
                var memoryModel = new io.agentmanager.framework.service.TracingModelWrapper(loggingModel, "memory");
                builder
                    // 记忆管理（AGENT_MEMORY_* 可调）
                    .memory(MemoryConfig.builder()
                        .flushTrigger(MemoryConfig.FlushTrigger.throttled(
                            Duration.ofMinutes(harness.memoryFlushThrottleMinutes())))
                        .consolidationMaxTokens(harness.memoryConsolidationMaxTokens())
                        .consolidationMinGap(Duration.ofMinutes(harness.memoryConsolidationMinGapMinutes()))
                        .model(memoryModel)            // ← 包装后的 model（flush + consolidation LLM 调用 span）
                        .build());
            } else {
                builder.disableMemoryHooks().disableMemoryTools();
                log.info("Memory fully disabled (agent.harness.memory-enabled=false)");
            }

            var agent = builder
                // 上下文压缩（AGENT_COMPACTION_* 可调）
                .compaction(CompactionConfig.builder()
                    .triggerMessages(harness.compactionTriggerMessages())
                    .keepMessages(harness.compactionKeepMessages())
                    // 记忆总开关关闭时强制不刷写：SDK 2.0.3 的压缩前 flush 走 CompactionMiddleware
                    // 内部自建的 MemoryFlushManager（仅判 CompactionConfig.isFlushBeforeCompact()），
                    // 不经 disableMemoryHooks —— 不在此处置 false，压缩阈值触发仍会发起记忆抽取
                    // LLM 调用并写 MEMORY.md/memory/，违反"完全关闭"
                    .flushBeforeCompact(harness.memoryEnabled() && harness.compactionFlushBeforeCompact())
                    .offloadBeforeCompact(harness.compactionOffloadBeforeCompact())
                    .model(compactionModel)        // ← 新增：包装后的 model（compaction LLM 调用 span）
                    .build())
                // 大工具结果卸载
                .toolResultEviction(ToolResultEvictionConfig.defaults())
                // Plan Mode
                .enablePlanMode()
                // 技能自学习
                .enableSkillManageTool(true)
                .build();

            // 权限覆盖校验（仅启用权限系统时）：内置白名单 vs 实际注册集，SDK 升级漂移时 ERROR 提示
            if (permissionContext != null) {
                verifyToolCoverage(agent, oafConfig, customToolNames, permCfg.mcpNames());
            }

            log.info("HarnessAgent created: {} (model: {}, workspace: {})",
                oafConfig.name(), llm.modelId(), workspacePath);
            return agent;
        } catch (Exception e) {
            log.error("Failed to create AgentScope agent: {}", e.getMessage(), e);
            throw new RuntimeException("Agent creation failed", e);
        }
    }

    /** ChatModel 装配统一走 {@link io.agentmanager.framework.config.ChatModelFactory}（托管/系统模型共用同一口径）；public 供 config 包单测断言上下文窗口透传 */
    public io.agentscope.extensions.model.openai.OpenAIChatModel buildChatModel(
        AgentManagerProperties.LLMConfig llm,
        AgentManagerProperties.HarnessConfig harness) {
        return io.agentmanager.framework.config.ChatModelFactory.build(llm, harness);
    }

    /** remoteHeadersJson 解析用（ObjectMapper 线程安全，复用实例） */
    private static final com.fasterxml.jackson.databind.ObjectMapper REMOTE_HEADERS_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 构造远程子 agent 声明（travel-fulfillment-agent-protocol-design §8 lead-1，B1 Agent Protocol）：
     * OAF agents[].endpoint 非空者映射为 SDK SubagentDeclaration——name=agent、
     * description=role、url=endpoint、remoteStreaming=true（子事件全文回流父 SSE）、
     * remoteStreamDetail=FULL、remoteAskPolicy=PROPAGATE（M0 F15 实测父流不转发确认
     * 事件，确认以 RemoteConfirmBridge 快照轮询为唯一来源，PROPAGATE 保留为语义声明）。
     * headers 解析自 AgentManagerProperties 的 agentProtocol().remoteHeadersJson
     * （agent.agent-protocol.remote-headers-json ← AGENT_REMOTE_HEADERS_JSON：JSON 对象、
     * 值须为字符串）——解析失败记 warn 跳过而非启动失败（子服务侧 AuthFilter 对
     * 无/错 token 兜底 401）。
     * endpoint 为空者不映射，维持 workspace subagents/*.md 本地链路（可混用）。
     *
     * <p>public：供单测断言声明映射（与 {@link #buildPermissionContext} 同例）。
     */
    public java.util.List<SubagentDeclaration> buildRemoteSubagentDeclarations(OafConfig oafConfig) {
        var declarations = new java.util.ArrayList<SubagentDeclaration>();
        for (var agent : oafConfig.subAgents()) {
            var endpoint = agent.endpoint();
            if (endpoint == null || endpoint.isBlank()) {
                continue; // 本地 subagent：走 WorkspaceInitializer 生成 subagents/*.md
            }
            var declared = SubagentDeclaration.builder()
                .name(agent.agent())
                // SDK build() 校验 description 非空（blank 直接拒绝构建）：role 缺失时回落
                // agent 名兜底，路由语义弱化但不阻断装配（描述质量影响委派路由，设计 §13.6）
                .description(agent.role() != null && !agent.role().isBlank()
                    ? agent.role() : agent.agent())
                .url(endpoint)
                .remoteStreaming(true)
                .remoteStreamDetail(RemoteStreamDetail.FULL)
                .remoteAskPolicy(RemoteAskPolicy.PROPAGATE);
            var headers = parseRemoteHeaders();
            if (!headers.isEmpty()) {
                declared.headers(headers);
            }
            declarations.add(declared.build());
            log.info("Remote subagent declared: {} -> {}", agent.agent(), endpoint);
        }
        return declarations;
    }

    /**
     * 解析 agent.agent-protocol.remote-headers-json（AGENT_REMOTE_HEADERS_JSON）为
     * header 映射：空白/缺失返回空 map；JSON 非法或非对象、值非字符串时记 warn 跳过
     * 对应内容（fail-soft，不阻断 agent 构建）。
     */
    private java.util.Map<String, String> parseRemoteHeaders() {
        var protocol = props.agentProtocol();
        var json = protocol != null ? protocol.remoteHeadersJson() : null;
        if (json == null || json.isBlank()) {
            return java.util.Map.of();
        }
        com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = REMOTE_HEADERS_MAPPER.readTree(json);
        } catch (Exception e) {
            log.warn("agent.remote-headers-json parse failed, remote headers skipped: {}", e.getMessage());
            return java.util.Map.of();
        }
        if (node == null || !node.isObject()) {
            log.warn("agent.remote-headers-json is not a JSON object, remote headers skipped");
            return java.util.Map.of();
        }
        var headers = new java.util.LinkedHashMap<String, String>();
        node.fields().forEachRemaining(entry -> {
            var value = entry.getValue();
            if (value == null || value.isNull()) {
                log.warn("agent.remote-headers-json entry '{}' is null, skipped", entry.getKey());
                return;
            }
            if (!value.isTextual()) {
                log.warn("agent.remote-headers-json entry '{}' is not a string, skipped", entry.getKey());
                return;
            }
            headers.put(entry.getKey(), value.asText());
        });
        return headers;
    }

    /**
     * 装配 HITL 权限上下文（MCP + 自定义/内置工具，见 docs/hitl-permission-plan.md 6.1）：
     * 1. 仅当存在 MCP tools 显式规则、require_confirmation=true 或 frontmatter
     *    config.permission.tools 声明时启用（未配置返回 null，零侵入）
     * 2. 自定义/内置工具：frontmatter 显式声明走对应 allow/ask/deny 规则；
     *    未声明默认 ALLOW（覆盖 DEFAULT mode 兜底 ASK，保持既有行为）
     * 3. MCP 工具：显式规则 + 未声明兜底（require_confirmation → ask，否则 allow）；
     *    与 MCP 裸名冲突时以 MCP 规则为准
     *
     * 规则匹配为精确工具名映射（PermissionEngine.rulesFor = map.get(name)，无通配符）。
     * 内置工具名单无法在 build 前运行时枚举（内置工具注册发生在 Builder.build() 内部），
     * 使用 BUILT_IN_TOOL_NAMES 静态白名单（javap 从 jar 提取验证）+ verifyToolCoverage 构建后校验。
     *
     * <p>public：供 config 包的单测（AgentScopeConfigTest）断言规则装配结果。
     */
    public io.agentscope.core.permission.PermissionContextState buildPermissionContext(
            OafConfig oafConfig,
            McpToolRegistrar.PermissionRuleResult permCfg,
            Set<String> customToolNames) {
        var requireAll = oafConfig.runtimeConfig().requireConfirmation();
        var customRules = oafConfig.runtimeConfig().permissionTools();
        var hasCustomRules = customRules != null && !customRules.isEmpty();
        if (permCfg.tools().isEmpty() && !requireAll && !hasCustomRules) {
            return null;
        }

        var pb = io.agentscope.core.permission.PermissionContextState.builder()
            .mode(permCfg.mode());

        // ① 自带工具（内置白名单 + 本次注册的自定义 @Tool）：显式声明优先，未声明自动放行
        var builtinNames = new HashSet<String>();
        builtinNames.addAll(BUILT_IN_TOOL_NAMES);
        builtinNames.addAll(customToolNames);
        for (var toolName : builtinNames) {
            if (permCfg.mcpNames().contains(toolName)) {
                continue; // 与 MCP 重名时以 MCP 规则为准
            }
            var declared = hasCustomRules ? customRules.get(toolName) : null;
            if (declared == null) {
                pb.addAllowRule(toolName,
                    new io.agentscope.core.permission.PermissionRule(
                        toolName, null,
                        io.agentscope.core.permission.PermissionBehavior.ALLOW, "builtinAutoAllow"));
                continue;
            }
            var rule = new io.agentscope.core.permission.PermissionRule(
                toolName, null,
                io.agentscope.core.permission.PermissionBehavior.valueOf(declared.toUpperCase()),
                "frontmatter");
            switch (declared) {
                case "allow" -> pb.addAllowRule(toolName, rule);
                case "ask" -> pb.addAskRule(toolName, rule);
                case "deny" -> pb.addDenyRule(toolName, rule);
            }
        }

        // ② MCP 工具：显式规则 + 兜底（未声明：require_confirmation=true → ask，否则 allow）
        for (var name : permCfg.mcpNames()) {
            var behavior = permCfg.tools().getOrDefault(name, requireAll ? "ask" : "allow");
            var rule = new io.agentscope.core.permission.PermissionRule(
                name, null,
                io.agentscope.core.permission.PermissionBehavior.valueOf(behavior.toUpperCase()),
                "projectSettings");
            switch (behavior) {
                case "allow" -> pb.addAllowRule(name, rule);
                case "ask" -> pb.addAskRule(name, rule);
                case "deny" -> pb.addDenyRule(name, rule);
            }
        }

        // ③ 声明了未注册工具（deniedTools 排除/名字写错）→ 告警忽略，规则不生效
        if (hasCustomRules) {
            var known = new HashSet<String>();
            known.addAll(builtinNames);
            known.addAll(permCfg.mcpNames());
            for (var name : customRules.keySet()) {
                if (!known.contains(name)) {
                    log.warn("config.permission.tools declares '{}' but no such tool is registered "
                        + "(deniedTools filtered or typo), rule ignored", name);
                }
            }
        }

        log.info("Permission system enabled (MCP + custom): mode={}, mcpRules={}, customRules={}",
            permCfg.mode(), permCfg.tools().size(), hasCustomRules ? customRules.size() : 0);
        return pb.build();
    }

    /** 反射提取 @Tool 注册名集合（注解无 name 时取方法名，与 Toolkit.registerTool 派生规则一致） */
    private static Set<String> toolToolNames(Object tool) {
        var names = new LinkedHashSet<String>();
        for (var method : tool.getClass().getMethods()) {
            var ann = method.getAnnotation(io.agentscope.core.tool.Tool.class);
            if (ann != null) {
                names.add(ann.name().isBlank() ? method.getName() : ann.name());
            }
        }
        return names;
    }

    /**
     * 构建后校验权限覆盖：实际注册工具集 vs 白名单（内置 + 自定义 + MCP）。
     * 未覆盖工具在 DEFAULT mode 下会触发 ASK（自带工具应放行）——SDK 升级、
     * builder 开关变化导致内置名漂移时打 ERROR 日志提示更新 BUILT_IN_TOOL_NAMES。
     * deniedTools 由 Harness tools.json 侧隐藏，不计入风险。
     */
    private void verifyToolCoverage(HarnessAgent agent, OafConfig oafConfig,
                                    Set<String> customToolNames, Set<String> mcpNames) {
        var covered = new HashSet<String>();
        covered.addAll(BUILT_IN_TOOL_NAMES);
        covered.addAll(customToolNames);
        covered.addAll(mcpNames);

        var actual = new TreeSet<>(agent.getToolkit().getToolNames());
        var uncovered = new TreeSet<>(actual);
        uncovered.removeAll(covered);
        if (oafConfig.deniedTools() != null) {
            uncovered.removeAll(oafConfig.deniedTools());
        }
        if (!uncovered.isEmpty()) {
            log.error("Permission coverage gap: tools {} are NOT covered by ALLOW/ASK/DENY rules "
                    + "and will trigger ASK in DEFAULT mode. Harness built-in tool names changed after "
                    + "SDK upgrade? Update HarnessAgentFactory.BUILT_IN_TOOL_NAMES or declare "
                    + "permissions.tools in mcp-configs/{server}/config.yaml. Actual tools: {}",
                uncovered, actual);
        } else {
            log.info("Permission coverage verified: {} actual tools, {} with rules",
                actual.size(), covered.size());
        }
    }
}
