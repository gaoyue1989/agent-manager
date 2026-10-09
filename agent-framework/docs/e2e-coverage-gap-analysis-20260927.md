# agent-framework E2E 覆盖缺口分析与补充用例（2026-09-27）

> **报告性质**：本文档为**静态分析与用例设计**产物，汇编自三份上游材料：①近 7 天功能变更清单；②现有 E2E 覆盖盘点（gateSummary + mockCapabilities + 88 行用例矩阵）；③功能域覆盖结论与缺口（含每条用例设计与逐项复核记录）。
> 文中引用的 grep、git show、check-fixtures 实跑、本机 e2e 库 13306 SQL 往返、SDK jar javap 反汇编等验证**均由上游评审环节执行**（各处标注了命令与输出，见第 3/4 节「复核证据」），**本次汇编未重新执行这些检查**。
> **第 4 节全部 25 条用例仅为设计草稿：尚未写入 `agent-framework/e2e/tests/`，亦未执行**（详见 §5.6）。
>
> **落地回填（2026-09-28，bc235c4）**：上述 25 条已落地 **19 条** `test()`——HA1-HA3 + U15、MOD4-MOD7、
> X10/X11（沙箱技能物化/tombstone）、SK2-SK4、MEM1-MEM3、A5-A7，配套 `lib/archive-seed.ts`、
> reset-data.mjs 清场表补 `session_message`、mock /stats 采样四键均已合入；余 6 条（X12/X13 需
> skill-manage-sb 录制件、§4.5 记忆面沙箱 no-op、TR1-TR3 需 OTLP mock 基建）尚未实施。下文
> 「尚未写入 tests/」等表述均为汇编时点状态；第二轮增量比对见
> [e2e-coverage-gap-analysis-20260929.md](e2e-coverage-gap-analysis-20260929.md)。
> 被测物：`agent-framework/`（Java 运行时，:8100）；CI 门禁：`.github/workflows/agent-framework-ci.yml`。

---

## 1. 近 7 天变更清单

共 34 项（含同一 commit 拆分的多个面）。「黑盒可观测面要点」指该变更在 e2e 黑盒（REST/SSE/UI/请求体/mock 断言面）上可感知的内容；「回归风险」为变更清单原文要点。

| # | 变更 | 功能域 | 性质 | 黑盒可观测面要点 | 回归风险 |
|---|------|--------|------|------------------|----------|
| 1 | 25246e3 模型管理与会话切换 | model | feature | 新增 REST `/models`（与 /threads 同源 :8100）：GET 列表（缺省仅 system+启用，`?all=true` debug 管理视图）/GET 详情/POST（name/modelId/baseUrl 必填，缺省 provider=openai/temperature=0.3/maxTokens=16384/timeout=120/enabled=true）/PATCH（字段缺省=不变，apiKey 空串=清除回落系统密钥）/DELETE/`POST /{id}/test`（真实发 max_tokens=16 completion，成功回 `{id,ok,latency_ms,reply}`，失败 502 model_test_failed）。错误语义：system 模型 PATCH/DELETE→400 system_model_readonly、重名→400 duplicate_name、超长/缺失→400 invalid_config、不存在→404 model_not_found；api_key 明文永不出接口，只回 api_key_masked（前 3+***+尾 4）。`POST /threads/chat` 新增可选 model（缺省不改绑定；`""`/`"system"` 清除绑定；未知/禁用→不启动 turn 直接单帧 SSE error `unknown_model\|model_disabled`；传值写 session_user.model 本 turn 生效）；`PATCH /threads/{sid}` 新增可选 model（非法值 HTTP 400），列表与详情回显 model。LLM 请求体变化（mock 可断言）：绑定托管模型后请求体 model=该模型 model_id（/stats calls[].model 可断言）；绑定模型删/禁运行时 fail-soft 回落系统模型。UI：debug 控制台 `#/models` 模块（列表/新增/编辑/删除/启停/测试连接）+发布助手模型下拉（data-testid="model-select"，接口非 200 整个 picker 不渲染）+侧栏 model 后缀 | 本 commit 时点 /models 无黑盒 e2e 防护（api-models.spec.ts 是后续 7644abb 才补的）；最可能回归是 model 三态语义（缺省/清空/非法 400 边界）或 api_key 掩码被绕过泄漏明文 |
| 2 | 25246e3 会话标题自动生成 | session | feature | 新会话首条 /threads/chat 触发服务端异步标题生成（不阻塞、失败仅 warn），系统模型生成 ≤64 字写 session_user.remark；已有标题不覆盖；GET /threads 的 title 键自此出现自动生成标题。LLM 请求体变化（mock 可断言）：每个新会话首轮多一次无 system 消息调用（前缀「请为下面这条用户消息生成一个简短的会话标题」、maxTokens=64/temperature=0.3）——mock 按「无 system 消息」判 background 合成良性响应（scenario=background-synth），不消耗 fixture，llmStats 可断言 | 标题生成失败静默无报错，回归表现为标题不生成；mock background 判据被改会回放 fixture 偷走主对话调用序，污染「最后一条调用」型断言 |
| 3 | 3dad588 | model | feature | /models POST/PATCH 新增 reasoningEffort（`^[a-z0-9_]{1,16}$`，空串=清除、PATCH 缺省=不变）与 frequencyPenalty（[-2,2] 越界 400；PATCH 缺省=不变，不支持撤销）；provider 收紧枚举 openai/vllm/sglang/glm/deepseek 越值 400。GET /models、详情与 system 只读视图新增 reasoning_effort/frequency_penalty 键（空白归一 null=不下发）。LLM 请求体方言（mock 可断言）：enable_thinking=false 不再对所有 provider 无差别注入 chat_template_kwargs——vllm/sglang 合并同一 chat_template_kwargs、glm 走 `thinking:{type:"disabled"}`+顶层 effort、openai/未知仅顶层一等字段、deepseek 均不下发（applyDialect 收口）；e2e 默认 LLM_PROVIDER=openai 且 LLM_ENABLE_THINKING 默认 false→默认请求体不含 chat_template_kwargs。新增 env LLM_REASONING_EFFORT/LLM_FREQUENCY_PENALTY（缺省不下发）。model_config 存量库自动迁移（缺列 ALTER，Duplicate column 幂等容忍） | 方言分支写错（如 env 大写 "VLLM" 未归一落错分支）会让严格端点（glm/deepseek 官方 API）因多余 thinking 参数 400；mock 不校验未知请求键，方言回归在 e2e 黑盒不可见，仅 ChatModelFactoryTest 方言矩阵单测防护 |
| 4 | 9d7170b | model | feature | debug 控制台 Chat 模块新增 #modelSelect 下拉（"System Default"+GET /models 启用项；接口不可用静默降级仅 console.warn）。Channel 模式：所选非 system 模型随 POST /threads/chat 请求体下发；A2A 模式：发送前先 PATCH /threads/{sid} {model} 落库再发消息（A2A 请求体不认 model；PATCH 失败 toast「模型切换失败」并回滚下拉）。新建会话回落 System Default；切会话按列表 model 回显，绑定模型不在选项时回落 system。e2e 新增 U14：Channel 下发后 pollUntil llmStats 断言 call.model==='e2e-ui-switched'、绑定落库、新建回落/切会话回显/清除绑定、A2A PATCH 路径同样断言 llmStats | U14 依赖 mock llm-server /stats 的 calls[].model 字段（7644abb 引入），字段丢失则用例先红；A2A 是 PATCH-then-send 非原子——PATCH 成功但消息发送失败时会话绑定已变且 UI 不回滚 |
| 5 | 492e905 模型切换引导 | model | feature | release-agent 对话行为变化（经 /threads/chat 或 A2A 可观测）：用户要求换/切模型时，助手说明模型切换在界面侧操作并指引会话界面模型选择入口，不硬拒绝、不编造模型信息；被问当前模型说明自己是 OAF 发布助手、模型由平台配置（AGENTS.md 新增「模型切换引导」节 +7 行，作为系统提示源，整包重建后生效）。REST/SSE/UI/env 零变化；[E2E:xxx] 标记回放机制下提示词变化在 CI 黑盒不可断言 | commit 信息声称的「bench/eval 支持 input.model 直测+未知模型拒绝回归用例」经核验不在本 commit（git diff a51f226 492e905 无 bench 文件；该能力实际由 ca5478d 两小时后落在 feat/eval-flywheel-skeleton 分支），按本提交信息回溯评测能力会扑空；话术本身无自动化防护，回归表现为提示词漂移或未重新打包发布 |
| 6 | 062e01f | history | feature | GET /threads/{sid}/history 新增 query：includeArchived（默认 true）、limit（默认 200，<=0 不分页）、beforeId 游标；响应新增 hasMore/nextBeforeId。归档开启时 messages 为双源合并视图（压缩前归档+agent_state 未归档尾部），每条消息新增 msg_id/name（缺失不写键，旧形态不变）与 origin（"archive"\|"state"）。压缩摘要消息以合成项下发：role=compaction、type=compaction_summary、content=摘要文本。GET /threads/{sid} 详情固定返回首页合并视图（附 hasMore/nextBeforeId）。环境开关 AGENT_HISTORY_ARCHIVE_ENABLED（默认 true）关闭后回退仅 agent_state。DELETE /threads/{sid} 级联删 session_message；定时清理同 7 天保留。UI：debug 控制台 role=compaction 渲染折叠压缩分隔条「上下文已压缩 · 早期消息已归档」 | 合并视图归档集去重/游标回归会让压缩前历史重复或丢页（跨页重复正是该 commit 修过的坑）；单测 ThreadControllerHistoryMergeTest 覆盖合并与翻页，但 e2e 无 compact 场景录制件，压缩后端到端可查性无 CI 门禁守护 |
| 7 | e5d6ff3 | tracing | fix | GET /threads/{sid}/llm-calls：Channel 链路按前端 sid 能查到记录（修复恒 calls:[] 与所有会话串进同一 gw-hash 桶互相可见）；记录键=session_user 反查的规范 sid。OTel span 属性口径变化：agentscope.session.id=规范 sid、agentscope.user.id=真实用户（此前 Channel 链路全进程同值），按会话/用户过滤 trace 恢复有效。e2e 门禁变化：api-core S7 由仅断 200 改为轮询断言 calls 非空。无新增环境开关；session_user 未登记或 DB 不可用 fail-soft 退化为 RuntimeContext 原值（兜底 "global"） | session_user 反查时序或解析优先级回归（sid 先于 peer 顺序）会让 llm-calls 再次恒空或串桶——S7 非空断言可拦 Channel 链路，A2A/直连未登记形态仅单测覆盖 |
| 8 | b119fa9 标题 | session | feature | POST /threads/chat 首条消息后异步触发标题生成（独立 titleGenerationModel Bean，写 session_user.remark=GET /threads 的 title；仅无标题时生成一次、失败不重试不阻断、空结果回退首条消息截断 40 字）。mock 可断言：标题调用请求体为 system+user 两条消息（user 截断 1000 字），无 [E2E] 标记且非 memory extraction——e2e mock 对此类未知无标记调用维持 500，故 e2e 环境标题生成失败仅告警、会话保持 thread_id 展示。PATCH {title} 重命名改走 SessionUserStore.updateRemark（修原自引用子查询 MySQL 1093 重命名失败）。UI：会话列表与顶栏展示 title；AGENT_END 后 4 秒再刷列表取回异步标题；刷新恢复（localStorage 当前会话自动选中、先渲染历史再续传、恢复时从 seq 0 按 replyId 回放当前 turn 全部事件，拿不到才退化续传） | 标题调用失败路径若从 fail-soft 变阻断或重复触发（inFlight 去重失效）会拖慢首条消息或打爆 LLM；replyId 过滤失效会刷新重放产生重复气泡——均单测覆盖、无 e2e 门禁 |
| 9 | b119fa9 工具摘要 | tools | feature | SSE 帧表新增两类服务端合成帧：TOOL_CALL_END 后 tool_call_summary{type,toolCallId,toolName,summary,replyId}、TOOL_RESULT_END 后 tool_result_preview{…preview…}；经 EventBus emitSynthetic 落库，断连续传/刷新回放/多副本订阅同样可得。帧时序契约：合成帧在原始事件之后发射；非 SUCCESS 发「❌ 状态终态文案」帧，成功但结果为空不发帧；HITL 恢复段无重放时补发无参数兜底摘要「执行 工具名」。摘要为服务端单行中文 ≤120 字（write_file→「创建 x.js N行」等，未识别回退工具名+首个标量参数）。UI 双端（debug 控制台+发布助手）呈现；POST /threads/{sid}/confirm 恢复流同样合成两帧 | 摘要帧先于原始事件或 replyId 错误会让前端按 turn 关联的文件卡片失联；delta 累积缓冲上限（2MB）溢出策略回归会让长文件摘要截断——单测覆盖生成器与 tracker，e2e 无摘要帧断言 |
| 10 | 95eba3f SSE | session | fix | SSE /subscribe 空闲退避（A2）：连续空页 XRANGE 轮询 300ms 基频倍增（600→1200→2000 封顶），实测空闲 ~3.3→~0.5 QPS；非空页立即回基频；租约丢失/中断探测过渡期一次性推迟 ~1.7s；AGENT_SSE_TAIL_POLL_MS 语义不变、未新增开关。Redis 持久化失败（append 返回 -1）不再广播 seq=0 帧（A3 门控）：订阅端不再收到断连续传/回放永远补不出来的实时帧，实时与回放视图不再分叉。A1/A5 零行为收口（帧字节一致性 oracle 用例守护；replyId 注入前移落库前，存量行读端兜底；turn 收尾统一 TurnFinalizer）。A4：turn 收尾清理本 turn 三个内存工具桶，异常路径不再跨 turn 泄漏 | 退避误判非空页为空页会推迟终态帧送达，e2e waitTerminal 类断言最先超时；A3 门控回归会在 Redis 故障期重新广播不可回放帧；multi 组 e2e 守护续传路径，退避节奏本身无专项 e2e |
| 11 | 95eba3f debugctx | session | fix | UI：debug 控制台九个模块（config/database/logs/mcp/memory/sandbox/skills/tools/workspace）的 load* 在 await 恢复后先判 ctx 再渲染——快速切页后慢响应返回不再抛 "Cannot read properties of null (reading 'utils')" pageerror，U1 十路由冒烟不再偶发翻车 | 新模块沿用旧写法（await 后直接解引用 ctx）会复现偶发 pageerror；仅被 U1 路由冒烟间接受护，无定向断言 |
| 12 | 95eba3f e2e | e2e-infra | test-infra | mock LLM 判据变化：system 含 "memory extraction assistant" 的调用（记忆提取）判为 background 返回 200 合成响应（scenario=background-synth），此前误判 500→SDK 退避重试帧迟到污染 stats 尾部；真正未知无标记调用（含标题生成）仍 500。api-core F1/F2/M5 改为按 scenario 过滤本测试自己的调用再断言，不再盲取 stats.calls 最后一条。E2E_REPLICA_A/B 缺省回落 .runtime/env.json（本地 run.sh multi 不再把空串送进 fetch） | 后台判据过宽（误吞带标记主对话调用）会偷走 fixture 调用序使 e2e 全组翻车；stats 过滤依赖 scenario 命名稳定，mock 端改名会让 F1/F2/M5 静默取不到（toBeTruthy 可拦） |
| 13 | 7644abb | e2e-infra | test-infra | CI e2e-core 必需门禁项目从 api-core+ui 扩为 api-core+api-models+api-reload+ui。新增黑盒覆盖 /models：CRUD/重名 400/api_key_masked 掩码（响应不含明文）/PATCH 改配置与清空 apiKey/enabled/禁用隐藏+?all=true/系统模型只读 400/404/DELETE（MOD1）；连接测试 200 与坏地址 502 model_test_failed（MOD2）；模型路由全链路：chat 请求体 model、PATCH 绑定后下一轮真实 LLM 请求 model=托管 id（mock /stats calls 新增 model 字段使其可断言）、禁用 400 且原绑定保留、未知 model terminal error unknown_model、删除后回落系统模型（MOD3）。/admin/reload：GET 状态、POST ?scope=mcp 原地重载、scope=auto 指纹分流（仅 MCP 变→mcp；AGENTS.md 变→agent 整包重建且卡片/提示全更新、下一轮 systemContent 可断言新标记）、非法配置 500+note 旧版生效、非法 scope 400（RL1-3，测试直改 .runtime/agent-config 并恢复）。reset-data 清理表新增 model_config | api-models/api-reload 进入 PR 必需门禁，依赖 mock 18081 端口与可写 .runtime/agent-config——环境漂移或 RL 用例改写配置后恢复失败会连坐 e2e-core 整 job 变红。（另：ask 补充说明称 7644abb 为「HITL 恢复流工具摘要」与实际 commit 不符，该描述对应 0069c57，不在本清单） |
| 14 | 63e3ccc 用户技能L4管理面 | skills | feature | 新 REST 端点组 /skills/users：GET 索引 {count,users[],truncated?}（KV 失败 500 index_failed 不降级空列表）、GET /skills/users/{userId}（{userId,skills[],tombstones[]}，非法 400 invalid_user_id）、GET /{userId}/{name}?file=（L4 优先、无覆盖回落包内基线，含 source/hasUserOverride/version/files/userOverrideExists；两侧均无 404）、PUT（空内容 400 empty_content、超限 413 content_too_large；message 按 SANDBOX_ENABLED 分档含「管理面写入栅栏」）、DELETE（无覆盖 404；含 deletedFiles/hasPackageBaseline/tombstone{name,clearHint}，message 告知 tombstone 期间容器内重建不落库）、POST …/sync-from-package（404/413；files[]/skipped[]）。新 GET /debug/user-skills 同源索引。Debug 页 /debug/#/skills 用户技能面板（下拉/列表/编辑/删除/下发，文案分档、截断与 5xx 提示）；ui.spec.ts U-SK1~7 桩化门禁；e2e 探针 SK1 钉路由契约（200/404 not_found/400 invalid_user_id） | 端点契约有 SK1+U-SK1..7 守护，但 userId 恰为 "content" 时 /skills/users/{userId} 被 /skills/{name}/content 吞掉属已知窄边界（仅注释无测试）；未来新增 GET /skills/{name} 映射会静默吞索引路由且 SK1 未必全兜住 |
| 15 | 63e3ccc 沙箱L4回写 | sandbox | fix | 沙箱档（SANDBOX_ENABLED=true）每请求结束回写从仅 MEMORY.md+memory/ 扩展到 skills/**：容器内 skill_manage 写入的用户技能落入 agent_fs L4 KV、管理面 GET 即可见（修复前只存活于容器 TTL、KV 恒空）。可观测仲裁：DELETE 过（.deleted）或 PUT/sync-from-package 写过（.admin-override）的技能，容器旧副本回写时跳过（不复活/不改回），状态经列表 tombstones/adminOverride 与响应 message 显式下发。回写口径（黑盒边界）：只写不删；. / _ 开头元数据段不入 KV；单技能 ≤200 文件、单文件 ≤100KB 超限跳过；SKILL.md 写失败即中止该技能本次回写不留孤儿 | 容器→KV 技能回写链路无 CI e2e（api-sandbox 无此场景，全链路只在手工脚本 e2e/user-skill-admin-e2e.sh，单测用 stub）；mock OpenSandbox 的 listDirectory 路径/type 口径与真实 execd 不一致时回写静默漏写（用户技能丢失）而门禁不红 |
| 16 | 4bb4c26 | memory | feature | 新环境开关 AGENT_MEMORY_ENABLED（agent.harness.memory-enabled，默认 true）：false 时 agent 不注册 memory_* 工具且不装配记忆 hooks（disableMemoryHooks+disableMemoryTools 双关）；memory flush/整合的内部 LLM 调用不再发生（mock 收不到 memory 内部请求、tracing 无 "memory" span）；沙箱档不再注入 KV 的 MEMORY.md/memory/*.md 且不回写（readRuntimeFiles 返回空集、注入链路 no-op）；skills/ 回写不受开关影响；连带失效：AGENT_COMPACTION_FLUSH_BEFORE_COMPACT 在记忆 hooks 关闭时失效（yml 注释明示） | 无任何 e2e 覆盖该开关（e2e 下 grep 无 AGENT_MEMORY_ENABLED/memory-enabled），全靠 AgentScopeConfigTest 单测；false 分支装配回归（hooks/tools 实际未关）或默认 true 路径回归（误读配置记忆恒关）CI 门禁均不红 |
| 17 | d0c3eaa 沙箱物化L4 | sandbox | feature | 沙箱档每个会话实例 acquire 后（SandboxUserKeyMiddleware 主路径）与首次命令执行前（doExec 兜底），把该用户 KV L4 技能覆盖写进容器 /workspace/skills/{name}/{rel}（每实例幂等、fail-soft）：管理面 PUT 与 sync-from-package 在下一 turn 即生效，不再需容器换代。物化仲裁可观测：带 .deleted 技能不再物化；admin-override 栅栏照写（KV 为权威）；只写不删；≤200 文件/≤100KB 超限跳过。REST 提示文案随能力改写（PUT「下一个 turn 开始时投影进容器生效」、DELETE「下一次物化不再写回」、sync「下一个 turn 物化生效」） | 物化链路无 CI e2e（仅 WorkspaceReaderTest 2 个 materialize 单测，mock OpenSandbox 不校验 /workspace/skills 写入）；调用点或幂等闸门回归时管理面写入退回「写入不生效」且门禁不红，只能靠手工脚本 user-skill-admin-e2e.sh 发现 |
| 18 | d0c3eaa available按用户合并 | skills | feature | GET /skills/available 与 GET /skills/parse-refs 新增可选入参 X-User-Id（优先）/?userId=：带 userId 返回全局目录 ∪ 该用户 L4（同名 L4 描述覆盖、被禁用包内技能仍不出现）；不传旧行为。聊天链路黑盒变化：POST /threads/chat 以会话 userId（X-User-Id 头 > body.userId > debug-user）解析 @Skill 引用（L4 独有技能可命中，引用 L4 优先、无覆盖回落包内基线）。mock 可断言：debug 页 @ 候选带 ?userId= 拉取且随 ui.userId 切换重新请求。失败降级口径：L4 合并/读取失败静默回落全局目录（@ 补全不阻断，表现为候选缩水） | 合并结果无 e2e 断言（仅单测）；L4 KV 读取失败静默降级把候选缩水伪装成「无个人技能」；userId 清洗口径与 ChatStreamController 侧 sanitize 不一致会导致同名技能匹配不上 |
| 19 | b0fc8a7 | tools | feature | AGENT_PLUGINS_DIR（缺省 {AGENT_CONFIG_DIR:-/config}/plugins，非法路径回退默认）：目录含 *.jar 时启动早期经 META-INF/services SPI 加载，工具实例单例并入 List<CustomTool> 注入源。GET /tools?includeInternal=true 的 tools 段新增插件工具（internalCount 计入；默认 /tools 不透出）。插件目录 {jar名}/config.yaml 扁平 k-v，${ENV_VAR} 整值替换回调 configure()。fail-soft：坏 jar/坏 SPI/config 失败仅 WARN 跳过；重名仅告警仍注册（Toolkit 覆盖语义），启动日志 "Tool plugins loaded: N plugin(s), M tool(s)"。既有黑盒行为自动生效：OAF reload 整包重建后插件工具仍注册；deniedTools 类粒度可剔除且撤销随 reload 恢复；HITL 白名单共享同一 List<CustomTool>；优雅关闭回调 close()。e2e-infra：plugin-smoke.sh 现场编译示例插件（12 断言），当时未进 CI 门禁 | 插件工具经 BFPP 手工单例并入依赖 Spring 按类型检索 manual singleton 的时序语义，Spring 升级/装配重构会让注入解析不到（启动不报错、工具静默消失）；重名预检基线 FRAMEWORK_CUSTOM_TOOL_NAMES 手工维护，漂移只少一条 WARN——当时主要靠单测 13 例守护 |
| 20 | 7040fe8 | tools | fix | GET /tools?includeInternal=true 新增 sdkInternal[]（{name,category:"sdk",source:"sdk"}，名称排序）与 sdkInternalCount；取运行中 agent 的 Toolkit 实际注册集，减去已上报 MCP/CustomTool 名与 OAF deniedTools；agent 未就绪（null）或枚举异常返回空数组不阻断；默认请求 sdkInternalCount=0；deniedTools 剔除 CustomTool 段不波及 SDK 段（冒烟断言：sdkInternal 含 read_file、sdkInternalCount>=20、默认 0、denied 前后不变） | sdkInternal fail-soft 空数组会让消费方在启动早期误判工具全丢；sdkInternalCount>=20 下限对 SDK feature 开关（memory/plan/子 Agent 等）增减敏感，可能误红 |
| 21 | 7040fe8 评测 | eval | fix | 评测 runner fetch_capabilities 取 /tools?includeInternal=true 的 tools+sdkInternal 并集——requires_tools 可声明 SDK 内置工具（如 read_file）不再被静默 skip；对接旧接口 _BUILTIN_TOOLS 降级兜底。跳过记录新增 kind 分类（capability/env），RCA 报告分节渲染（「⚠️ 能力缺失」单列、「环境未供给（预期分流）」分开，跳过标题改「不计入通过率」）。eval-selftest（非必需）离线自检新增跳过分类断言（capability/env 两条用例全 skip 零网络，校验 kind 与分节文案） | 能力门禁从「只认 MCP+4 个硬编码内置工具」扩为全量可调用集后，误声明 SDK 工具名的用例不再 skip 而是真失败，通过率分母与失败画像变化；RCA markdown 分节文案变更会断下游按旧格式解析的脚本（仅 selftest 自断言守护，job 非必需） |
| 22 | e2392c1 运行时 | tools | feature | 可调用工具集变化（/tools 与发给 LLM 的 tools 数组可断言）：不再注册 check_oaf_package/create_oaf_zip（OafPackageTools 整文件删除）；新增框架通用 @Tool present_url(file_name,url,mime_type?,size?)：登记外部交付物（file_asset storage_type=external，storage_key=URL），返回与 present_file 同构 JSON；同 URL 幂等复用 file_id。环境开关 FILE_EXTERNAL_URL_PREFIXES（逗号分隔前缀白名单）：未配置/空时一律 err unavailable；url 非 http(s) 或不在白名单返回 err。SSE：present_url 与 present_file 同链路合成 file_ready 帧（前端下载卡片零改动）。GET /files/{id} 新增 external 代理下载分支：服务端回源 storage_key 流式转发，实时帧与历史回放统一单一 URL；白名单不命中 403、上游非 2xx/超时 502 | FILE_EXTERNAL_URL_PREFIXES 未随部署下发时 present_url 全链路不可用（工具 err、无下载卡片），打包交付静默降级——e2e 由 start-agent.sh 注入守护（F12/U13），真实集群下发靠人工步骤，无自动化守护 |
| 23 | e2392c1 平台MCP | tools | feature | backend platform-publisher MCP 新增 check_oaf_package(agents_md)→{valid,missing,invalid,present}（校验规则按原 Java 正则平移）与 create_oaf_zip(package_name?,agents_md,extra_files?)（校验+组 zip+直走 PackageService.Upload，返回 packageId/slug/version/warnings/fileCount/size/download_url/hint，上限 20MB）。环境开关 PACKAGE_DOWNLOAD_BASE（缺省集群内 svc 地址）拼 download_url。组包路径安全可观测：绝对路径/..逃逸/非规范化/重复条目直接报错，zip 条目固定 0644。release-agent 包升 1.1.0：流程改 check_oaf_package → create_oaf_zip → present_url → publish_service(packageId)，upload_package 退出；⚠ 发布时序：agent-framework 镜像须先于 backend 镜像 | frontmatter 校验正则 Java→Go 平移而平台既有 Validate 刻意不动，两套规则偏差靠文档记录、演进需人工同步；download_url 基地址配错（业务 Pod 不可达）时 present_url 能登记但代理下载 502，只有真实发布链路能暴露 |
| 24 | e2392c1 e2e | e2e-infra | test-infra | bench mock MCP（server.js）新增两工具 tools/list+tools/call（check_oaf_package 固定 valid=true；create_oaf_zip 固定 packageId=7、download_url 指向自身 /packages/7/download）与 GET /packages/:id/download 返回确定性 zip（PK 魔数）。mock 可断言回放变化：oaf-package 夹具从 2 次调用改 3 段链（末条 user→MCP create_oaf_zip→1 条 tool present_url→2 条 tool→收尾文本），registry calls 2→3，回放时以 BENCH_MCP_BASE 替换占位符。被测进程环境：start-agent.sh 注入 FILE_EXTERNAL_URL_PREFIXES，env-up.sh 透传 BENCH_MCP_PORT。门禁断言升级：api-core F12 增 toolNames 含 present_url（下载走 /files/{id} 代理回源 mock zip）；ui U13 注释同步 | BENCH_MCP_PORT 三处联动（mock-mcp 端口、llm-server 占位符替换、被测进程白名单）——任一环节没传到（如 llm-server 拿不到 env 时 download_url 落默认 18082 与白名单不符），present_url 报 err、F12/U13 连红，易误判为运行时回归而非 mock 装配问题 |
| 25 | 9f71bb1 | e2e-infra | test-infra | llm-server MARKER_MAP 新增 [E2E:oaf:package]→oaf-package 夹具（手工合成 2 次调用），registry 登记 calls=2，受 check-fixtures 校验。api-core F12（进 e2e-core 必需 job）：chat 发标记后断言 SSE file_ready 帧存在且 file_name=e2e-oaf-agent.zip、download_url 匹配 ^/files/{uuid}$ 相对路径→GET /files/{id} 下载体前两字节 PK→history(sid).files 按业务会话回查到该文件（session_id 落网关 gw-hash 即红）。ui U13：下载卡片实时渲染（a[href*="/files/"] 可见+文件名文本）→reload 后按 .thread-item[data-sid] 精确点选原会话再断言卡片回放（不点列表首位，规避记忆提取后台刷 updated_at 的不稳定） | 夹具手工合成且断言钉死 download_url 格式与 file_ready 帧契约——运行时卡片链路有意变更时 F12/U13 会红（属预期门禁）；真正守护缺口是 present_file 主链路 F5/U11 因 SDK edit_file 死循环长期 test.fixme，该夹具刻意不含 edit_file，主链路仍无门禁 |
| 26 | 3a9256a | tracing | feature | OTel chat span 新增 gen_ai.input.messages/gen_ai.output.messages（JSON 文本：输入=全量 messages [{role,content}] 渲染；输出=assistant 正文/思考/tool_calls 累积），单条属性超 8192 字符截断并标注总长。execute_tool span 新增 gen_ai.tool.call.arguments（[{id,name,description,arguments}]）与 gen_ai.tool.call.result（[{id,name,state,output}]，state 取 ToolResultState 枚举名）。环境开关：OTEL_TRACES_EXPORTER=none（无有效 span）时两中间件不做任何序列化，调用链零感知。已声明黑盒边界：取消路径 span 不保证写入内容属性、memory/compaction（TracingModelWrapper）路径暂不覆盖 | 写入/截断逻辑回归最可能是超长 prompt 撑爆 span 属性（trace 后端拒收整条 trace）或中断调用静默缺内容属性——16 个单测守护主路径，但取消路径与 e2e 层 span 内容均无自动化断言 |
| 27 | ca5478d | eval | feature | bench/eval/flywheel.py 四子命令：run（diff 分析→可选 LLM 用例生成→评测→可选 judge→报告+reports/history.jsonl 趋势账本）、verify（上轮失败回归）、selftest（离线零网络）、status；可选能力需 EVAL_LLM_* 环境变量。评测执行走真实 SSE 黑盒面（POST /threads/chat 采集全量事件帧；HITL 续段 POST confirm-stream {"results":[{tool_call_id,confirmed}]}；token 用量取 MODEL_CALL_END）。帧词表契约 config/frame-mapping.json：31 SDK 枚举+9 合成帧；关键断言口径注记——/threads/chat 不发 done、以 AGENT_END 收尾，REQUIRE_USER_CONFIRM 序列化为 permission_ask，terminal_frames=[AGENT_END,done,error,permission_ask]。确定性断言键（零 LLM 可判）：frames{类型:精确数\|>=n}、frame_order、tool_calls.required/forbidden、tool_result{工具:ok→state 判定}、final_text_contains/min_len、error_contains、no_error | 运行时 SSE 词表漂移（新增枚举/改序列化）而 frame-mapping.json 未同步，趋势轨会批量误判 FAIL/SKIP——本 commit 时点无任何 CI 接入（eval-selftest 到 #38 才补），只能人工跑 selftest |
| 28 | 16df029 | eval | feature | flywheel 新增 provision --since / teardown 与 run --provision/--no-teardown/--oaf-base/--plugin-src：按 diff 产出 env_spec→组装 OAF 包（插件 jar 现场编译）→docker 起被测实例 :18100+mock MCP :18082→契约预检（失败退出码 2）。新 mock 服务 bench/eval/mock/mcp_server.py（零依赖）：JSON-RPC initialize/tools/list/tools/call，handler 三类 echo/fail/canned，readOnlyHint 随 allow/ask 翻转，请求流水落 mock-mcp.jsonl。requires_env 门禁语义：标签 plugin:{name}/mock_mcp/mock_mcp:ask/reload/session_model，未供给整条用例 SKIP（reason=「评测环境未供给: [标签]」）。新增 3 条带 requires_env 用例（插件 echo、HITL publish confirm、模型切默认） | diff→env_spec 路径/关键词规则漏匹配新变更类型时会静默少供给环境、对应用例全部 SKIP 假绿；provision/teardown 与 docker 供给链路无 CI 覆盖（#38 仅接离线 selftest），坏了只能本地跑时发现 |
| 29 | fa4b2cd 插件冒烟门禁 | e2e-infra | test-infra | CI 新 job「E2E 工具插件（SPI+三态权限）」（非必需）：framework/workflows 变更触发，mysql/redis services+mvn package+check-fixtures+plugin-smoke.sh，失败取证 artifact。plugin-smoke 黑盒断言面：启动日志注册行（Bootstrapper/工厂/SDK Toolkit）→GET /tools 透出插件工具 echo_query/smoke_config→POST /admin/reload?scope=agent 整包重建后仍注册→deniedTools 剔除后 /tools 收敛（internalCount 下降）、撤销恢复；基础设施端口改由 MYSQL_URL/REDIS_URL 连接串 sed 推导（CI 3306/6379 与本地 3307/16379 同一代码路径） | e2e-plugin 非必需检查，插件 SPI 加载/注册回归只红不挡合并；url_port 的 sed 前缀只匹配带端口连接串，无端口写法会静默回落缺省端口 |
| 30 | fa4b2cd 三态权限黑盒 | tools | test-infra | mock LLM 新增场景：[E2E:plugin:echo] 路由 plugin-echo 录制件，registry 登记 calls=2、variants=["denied"]，受 check-fixtures 校验调用数。REST/SSE 断言链（plugin-smoke）：POST /threads/chat→SSE permission_ask 帧指向 echo_query→GET status state=waiting_confirm→POST confirm-stream {confirmed:true\|false}→GET history 中 tool_calls state=success/denied；config.permission.tools=deny 时无 permission_ask 帧直接 state=denied（check_absent 反向断言） | 挂起断言经变异测试验证敏感（摘掉 ask 规则 4 条转红），但 e2e-plugin 非必需、HITL ask 挂起回归不挡合并；判定依赖 confirm 后 sleep 1 等租约释放，慢机 history 读取可能偶发抢跑 |
| 31 | fa4b2cd tools视图断言 | tools | test-infra | api-core S1 补 /tools?includeInternal 运行时注册集断言：默认视图 internalCount=0（仅 MCP 工具）；includeInternal=true 断 internalCount>0、内置 echo/present_file/present_url 在列、fixture 未声明 frontmatter tools 时全部 declared=false；与 e2e-plugin 侧 declared 随 tools 声明翻转 true 的断言成对锁定端点语义 | /tools 序列化结构变化（internalCount 改名/declared 移位）S1 会误红——属必需检查 e2e-core 会挡合并，但失败语义清晰易排查 |
| 32 | fa4b2cd 评测自检 | e2e-infra | test-infra | CI 新 job「评测自检 (flywheel selftest)」（非必需）：framework/workflows 触发，python 3.11+httpx，跑 flywheel.py selftest（帧映射/检查器/HITL 视图/错误断言/失败分类五段纯函数，<1min 零网络零 LLM）；非必需不挡合并，联机 run/verify 仍不进 CI | selftest 只验纯函数段，帧映射与被测服务真实 SSE 词表的漂移（运行时侧改动）验不出来，趋势轨误判仍只能人工发现 |
| 33 | 4a1d38d | e2e-infra | fix | plugin-smoke.sh 自建 agent-config 补写 console-only logback-e2e.xml 并经 LOGGING_CONFIG=file:... 注入被测实例，消除应用默认 logback 强写 /applog（CI 非 root 不可写）导致的 FileNotFoundException 启动失败；无 REST/SSE 行为变化，断言集不变（27 条全绿） | 修复只落在 plugin-smoke.sh 一处自建配置；今后新增同类自建 agent-config 的脚本再漏 LOGGING_CONFIG 时，CI 非 root 环境会重现 /applog 启动失败（core/multi/sandbox 共用的 start-agent.sh 已有此注入，不受影响） |
| 34 | 6478af0 | e2e-infra | test-infra | 启动语义独立为 e2e/scripts/start-agent.sh <name> <port>（env-up 与用例重建共用；含端口清场与 wait-ready /health 90s），共享变量经环境变量透传。e2e-multi R4：retry 轮检测副本 A pid 失效自动 spawn start-agent.sh 重建并刷新 pid 文件；接管判定预算 35s→60s（租约 TTL 15s/续期 5s 被测语义不变，仅测试预算）。诊断输出面：pollUntil 超时错误携带最后观测值；接管超时抛 pidAlive+双侧 /threads/:sid/status 证据——只改失败信息形态 | retry 自愈依赖 env-up 先行产出的 .runtime/agent-config 与 env.json 布局，文件布局变动会让重建退化为必红；60s 预算遇更慢节点仍可能超时——均为测试自身稳定性风险，不触及被测物行为 |

---

## 2. 现有 E2E 门禁覆盖盘点

### 2.1 门禁分组（gateSummary）

- **必需门禁**：单测 `mvn test`；`e2e-core`（单副本 8100，MySQL+Redis+mock LLM+双 mock MCP+chromium，跑 api-core / api-models / api-reload / ui 四个 Playwright 项目）；`e2e-multi`（8101/8102 双副本+nginx LB+chromium，跑 api-multi / ui-multi / api-multi-kill）；`e2e-sandbox`（追加 mock OpenSandbox:8090，无浏览器，跑 api-sandbox）。
- **非必需**（只告警不挡合并）：`e2e-plugin`（无浏览器，现场编译示例插件独立进程跑 bash 断言）、`eval-selftest`（python 零网络离线）。
- 目录过滤下不相关 job Skipped 视为通过。

### 2.2 mock 能力底座（mockCapabilities）

LLM 为**真实服务录制→回放**：`mock/llm-server.mjs` 取最后一条 user 消息的 `[E2E:xxx]` 标记经 MARKER_MAP 路由 `mock/fixtures/llm/` 录制件，`registry.json` 登记标记/调用数/variants，`check-fixtures.mjs` 本次实跑通过（llm=17 场景+sandbox=1）。机制：slow=plain 夹具逐 chunk 延迟包装、hang 发 2 chunk 挂起、拒绝恢复走 variants.denied、hitl-submit/mcpapp-form 回放期整体重写 tool_call arguments、后台调用（无 system/记忆提取）合成良性响应、缺夹具 500 严格模式（ALLOW_SYNTH 仅本地）。17 场景：plain / remember / recall / tool:echo / tool:time / tool:write / tool:read / file:deliver / oaf:package / tool:mcp_echo / hitl:submit / mcpapp:form / execute / execute:fail / tool:write:sb / tool:read:sb / plugin:echo——**无 compact/压缩场景录制件**（registry 与 MARKER_MAP 均无），新增需开发机重录+登记。

沙箱 mock 为录制件结构回放+本地目录/白名单命令受控执行，/stats 与 /admin/destroy 驱动 X 组断言；UI 技能面板用 page.route 桩化（U-SK1..7），无真实 LLM/沙箱/浏览器外联。

### 2.3 用例矩阵

> 路径说明：spec 位于 `agent-framework/e2e/tests/`，PLUGIN 位于 `agent-framework/e2e/scripts/plugin-smoke.sh`，SELFTEST 位于 `agent-framework/bench/eval/flywheel.py`；行号为盘点时点。共 88 行用例记录。

**e2e-core · api-core 项目（34 行，必需）**

| 用例 | 位置 | 覆盖要点 |
|------|------|----------|
| S1 | api-core.spec.ts:20 | 8 元数据端点 200；/tools 双视图、/mcp 四 server、/skills、agent-card A2A 声明 |
| S2 | api-core.spec.ts:52 | 单次流帧序 session_created→done、seq 单调、文本拼接与 /history 一致 |
| S3 | api-core.spec.ts:74 | 续会话上下文累积：/stats recall 调用 msgCount>2 且含 assistant |
| S4 | api-core.spec.ts:87 | 内置 echo 工具 SUCCESS+摘要/预览合成帧+history state=success |
| S5 | api-core.spec.ts:113 | bench_echo 只读 MCP；X-User-Id header 与 _meta 双通道用户注入实收 |
| S6 | api-core.spec.ts:135 | 空 message 负例 error/400；不存在 sid history/status 兼容 |
| S7 | api-core.spec.ts:150 | 线程列表/详情/PATCH 改名/llm-calls 非空/删除后列表移除 |
| S8 | api-core.spec.ts:182 | history 工具配对：get_current_time success+output，无 pendingConfirm |
| S9 | api-core.spec.ts:196 | 首轮自动生成标题；手工标题后续不被覆盖 |
| F1 | api-core.spec.ts:222 | 文档上传→工作区注入→read_file 读回上传内容 |
| F2 | api-core.spec.ts:242 | 图片上传→image_url base64 内联（/stats hasImageBlock 直证） |
| F4 | api-core.spec.ts:258 | 同名重复上传唯一化冒烟：两次携文件对话均 done |
| F5 | api-core.spec.ts:280 | **fixme**：present_file file_ready+下载（D8 SDK 死循环挂起） |
| F12 | api-core.spec.ts:297 | OAF 打包 create_oaf_zip+present_url+PK zip 下载+history 会话绑定 |
| F6 | api-core.spec.ts:327 | inline 预览规则 png/txt、默认 attachment、RFC5987 中文名 |
| F7 | api-core.spec.ts:349 | 下载负例：非 UUID 400、随机 UUID 404 |
| F8 | api-core.spec.ts:354 | 上传校验矩阵：空文件/危险扩展名/非白名单/错配/超限 413 |
| H1 | api-core.spec.ts:386 | ask 挂起 permission_ask+waiting_confirm+pendingConfirm agent_state |
| H2 | api-core.spec.ts:409 | 批准 confirm-stream：SUCCESS+摘要/预览兜底帧+断连回放可见 |
| H3 | api-core.spec.ts:440 | 拒绝 confirmed=false：工具 state=denied 未执行 |
| H4 | api-core.spec.ts:455 | 重复确认 409/404（confirm_already_consumed 双通道） |
| H5 | api-core.spec.ts:468 | 同步 /confirm 批准，history 终态 success |
| H6 | api-core.spec.ts:483 | ASKING 态新 turn 被拒 error 含 ASKING，挂起保持 |
| H8 | api-core.spec.ts:498 | UI 代理 ask 拦截：403+needsConfirm+toolCalls |
| M1 | api-core.spec.ts:511 | TOOL_CALL_START 携 ui resourceUri/server 元数据 |
| M2/M3 | api-core.spec.ts:524 | UI 资源拉取 mcp-app mimeType+资源列表含 uri |
| M4 | api-core.spec.ts:535 | app_only 工具经卡片代理 confirmed:true 可调 |
| M5 | api-core.spec.ts:543 | ui-context 静默注入 systemContent（Hook 直证） |
| M6 | api-core.spec.ts:561 | /tools 透出 appOnly:true 标记 |
| A1 | api-core.spec.ts:569 | A2A message/send blocking 返回 result 无 error |
| A2 | api-core.spec.ts:582 | A2A message/stream SSE 增量响应 |
| A3/A4 | api-core.spec.ts:600 | tasks/get 已路由（非 Method not found） |
| SK1 | api-core.spec.ts:610 | 技能端点契约 /debug/user-skills、/skills/users、404 not_found、400 |

**e2e-core · api-models 项目（3 行，必需）**

| 用例 | 位置 | 覆盖要点 |
|------|------|----------|
| MOD1 | api-models.spec.ts:52 | 模型 CRUD/密钥掩码/重名 400/禁用过滤/系统模型只读/404 |
| MOD2 | api-models.spec.ts:96 | 连接测试走目标配置；坏 baseUrl 502 model_test_failed |
| MOD3 | api-models.spec.ts:122 | PATCH 切模型影响真实调用；禁用 400；删除回落系统模型 |

**e2e-core · api-reload 项目（3 行，必需）**

| 用例 | 位置 | 覆盖要点 |
|------|------|----------|
| RL1 | api-reload.spec.ts:51 | reload 状态+单 MCP 原地重载+noop+bench_echo 存活 |
| RL2 | api-reload.spec.ts:83 | auto 识别仅 MCP 变化：scope=mcp、agent 不重建 |
| RL3 | api-reload.spec.ts:100 | auto 整包重建更新卡片/提示；非法配置 500 保旧版；无效 scope 400 |

**e2e-core · ui 项目（19 行，必需）**

| 用例 | 位置 | 覆盖要点 |
|------|------|----------|
| U1 | ui.spec.ts:41 | ≥8 模块路由切换渲染非空、无 pageerror |
| U2 | ui.spec.ts:54 | 基础对话流式：发送钮流式禁用/结束恢复 |
| U3 | ui.spec.ts:65 | 新建会话/列表切回 history 回放 |
| U4 | ui.spec.ts:76 | HITL 批准全流程+恢复流工具摘要帧 UI 呈现 |
| U5 | ui.spec.ts:96 | HITL 拒绝：卡片消失、对话继续 |
| U6 | ui.spec.ts:106 | MCP App 卡片 iframe 加载 ui:// 资源 sandbox=allow-scripts |
| U7 | ui.spec.ts:118 | working 态刷新后流自动续传、文本完整 |
| U8 | ui.spec.ts:131 | **fixme**：waiting_confirm 刷新重建确认卡（无头时序挂起） |
| U10 | ui.spec.ts:147 | 附件上传对话：预览条+read_file 回复 |
| U11 | ui.spec.ts:160 | **fixme**：file_ready 下载卡片+回放（D8 同链路挂起） |
| U13 | ui.spec.ts:181 | OAF 下载卡片实时渲染+按 data-sid 回放仍在 |
| U-SK1 | ui.spec.ts:290 | 技能面板沙箱/非沙箱提示文案分档（stub） |
| U-SK2 | ui.spec.ts:303 | 技能编辑 PUT/删除 DELETE/包内下发 POST 路由（stub） |
| U-SK3 | ui.spec.ts:339 | 并发加载不错位：过期响应丢弃、行操作对应用户 |
| U-SK4 | ui.spec.ts:363 | 明细 5xx 不当不存在：失败中止不覆盖（stub） |
| U-SK5 | ui.spec.ts:377 | 索引触顶截断显式提示（stub） |
| U-SK6 | ui.spec.ts:387 | 索引接口失败醒目提示（stub） |
| U-SK7 | ui.spec.ts:395 | tombstone 与管理面写入栅栏提示（stub） |
| U14 | ui.spec.ts:411 | 会话模型切换：下拉/绑定落库/实走托管模型/A2A PATCH 回退 |

**e2e-multi（7 行，必需）**

| 用例 | 位置 | 覆盖要点 |
|------|------|----------|
| R1 | api-multi.spec.ts:19 | completed 全量回放：帧数≥latestSeq+done 收尾 |
| R2 | api-multi.spec.ts:35 | working 断连不杀任务；B 观测尾部事件、seq 无回退 |
| R3 | api-multi.spec.ts:54 | 同会话并发：第二流 waiting 帧互斥后断开 |
| R5 | api-multi.spec.ts:67 | 跨副本并发 confirm：一 200 一 409，工具恰一次终态 |
| R6 | api-multi.spec.ts:89 | B 订阅对账：观测到终态 done |
| R4 | api-multi-kill.spec.ts:59 | SIGKILL 副本 A→B 判 interrupted；新 turn 不死锁 |
| U9 | ui-multi.spec.ts:7 | LB 随机路由页面冒烟+刷新续传 |

**e2e-sandbox · api-sandbox 项目（8 行，必需；缺号 X5）**

| 用例 | 位置 | 覆盖要点 |
|------|------|----------|
| X1 | api-sandbox.spec.ts:29 | 沙箱模式启动：首个对话触发 create，非沙箱功能不回归 |
| X2 | api-sandbox.spec.ts:38 | 白名单命令执行：stdout 含 hello-e2a、exitCode=0 |
| X3 | api-sandbox.spec.ts:54 | **fixme**：沙箱写读跨会话（write 回放不落盘挂起） |
| X4 | api-sandbox.spec.ts:74 | KV 工作区跨会话可见、跨 user 隔离 |
| X6 | api-sandbox.spec.ts:91 | GC 404 降级 create 新沙箱、turn 完成 |
| X8 | api-sandbox.spec.ts:111 | pending 上限 429+用户隔离+X-User-Id header 优先 |
| X9 | api-sandbox.spec.ts:133 | write→present→file_ready→下载 attachment 全链路 |
| X7 | api-sandbox.spec.ts:149 | 命令失败传播：Exit code:2 可见、turn 仍 done |

**e2e-plugin（8 行，非必需）**

| 用例 | 位置 | 覆盖要点 |
|------|------|----------|
| PLUGIN-1 | plugin-smoke.sh:206-211 | 启动注册链路：Bootstrapper/工厂/SDK Toolkit 日志+/tools 透出两工具 |
| PLUGIN-2 | plugin-smoke.sh:215-218 | includeInternal 注册集 internalCount>=2、declared=false、默认不暴露 |
| PLUGIN-3 | plugin-smoke.sh:223-226 | sdkInternal 段：read_file、category=sdk、sdkInternalCount>=20、默认 0 |
| PLUGIN-4 | plugin-smoke.sh:237-238 | frontmatter tools 声明翻转 declared=true 且不剔除 |
| PLUGIN-5 | plugin-smoke.sh:243-245 | OAF reload 整包重建后工厂/Toolkit 重注册、/tools 仍含 |
| PLUGIN-6 | plugin-smoke.sh:252-266 | deniedTools 剔除：internal 收敛、sdkInternal 不波及、撤销恢复 |
| PLUGIN-7 | plugin-smoke.sh:278-303 | 三态权限：ask 挂起批准 success/拒绝 denied/deny 不挂起 |
| PLUGIN-8 | plugin-smoke.sh:308 | 收尾恢复默认配置 reload 后插件工具仍注册 |

**eval-selftest（6 行，非必需）**

| 用例 | 位置 | 覆盖要点 |
|------|------|----------|
| SELFTEST-1 | flywheel.py:394-396 | 帧映射枚举：sdk/synthetic 含 REQUIRE_USER_CONFIRM/permission_ask |
| SELFTEST-2 | flywheel.py:412-418 | build_view：final_output/token/tool/terminal，done 与 AGENT_END 双方言 |
| SELFTEST-3 | flywheel.py:420-438 | 检查器 evaluate 正例全过/反例全挂 |
| SELFTEST-4 | flywheel.py:440-448 | HITL 视图：permission_ask 终态+asks 工具提取 |
| SELFTEST-5 | flywheel.py:450-455 | error_contains 断言+失败分类=配置加载类 |
| SELFTEST-6 | flywheel.py:463-480 | 能力/环境跳过分类 kind 与报告渲染（issue #39） |

---

## 3. 覆盖缺口分析

> 下表「复核结论与证据」为材料三评审记录摘要；其中 grep/实跑/SQL 往返/javap 等验证由上游评审执行，本汇编未复跑。完整证据与复核意见见第 4 节各缺口小节。

| # | 缺口（feature） | 域 | 优先级 | 建议组 | 黑盒可行 | 落地前置 | 复核结论与证据（摘要） |
|---|-----------------|----|--------|--------|----------|----------|------------------------|
| 1 | 压缩后会话历史双源合并与翻页游标（#45）：includeArchived/limit/beforeId、hasMore/nextBeforeId、origin 双源标记、role=compaction 合成项 | history | **high** | core | 可行（HA1/HA2 零前置；HA3/U15 需种子设施） | 无新 LLM 录制件；HA3/U15 需新增 lib/archive-seed.ts 直插 session_message + reset-data.mjs 补清场表 | **缺口证实**。服务端能力源码全确认（ThreadController.java:215-227/509-522/540-549/614-629/629、SessionMessageStore.java:45/51/171-212）；e2e 零断言（grep compaction\|includeArchived\|beforeId\|hasMore 于 tests/、plugin-smoke.sh、e2e-ci-plan.md 均 exit 1）；无 compact 录制件（17 夹具无 compaction）；单测 ThreadControllerHistoryMergeTest 全打 mock store，不拦真实装配（归档 Store 装饰链 AgentScopeConfig.java:245-246、5 形谓词 SQL、write-through 三写路径）。种子 SQL 已在本机 e2e 库 13306 实跑往返验证通过 |
| 2 | /models 采样参数扩展与 LLM 请求体方言（reasoningEffort/frequencyPenalty/provider 枚举，3dad588） | model | **high** | core | 可行（MOD4/MOD5 零前置；MOD6/MOD7 需扩 mock） | MOD6/MOD7 需 llm-server.mjs /stats 增记 sampling 键（无新录制件，registry 不变） | **证实（一处细节修正）**。实现侧校验/三态/回显源码确认（ModelController.java:61-65/117-121/134-135/169-190/264-311/329-332/358-371）；api-models.spec.ts 恰 3 用例，grep reasoning\|frequency\|provider 于 e2e 零命中（exit 1）；400 契约全靠 ModelControllerTest 单测；方言分支黑盒不可见（stats.calls.push 无采样键、回放引擎不校验请求体）；env 大写归一只在 ChatModelFactory.java:88-90 方言层。细节修正：llm-server.mjs:150 还记录 scenario（api-models.spec.ts:141/164 已用），采样键确实缺失，结论不受影响 |
| 3 | 沙箱档用户技能容器→KV 回写与每 turn 物化（63e3ccc/d0c3eaa） | sandbox | medium | sandbox | 可行（X10/X11 零前置；X12/X13 需补录制件） | X12/X13 需 skill-manage-sb 录制件+registry+MARKER_MAP；录制前须验证 X3 同族缺陷不命中 skill_manage 写入链 | **证实**。api-sandbox.spec.ts grep skill 零命中；tests/ 内 PUT/DELETE /skills/users 零命中（SK1 仅 GET 探针、U-SK 组全 stub）；mock OpenSandbox 对 /workspace/skills 无感知；写路径/物化/回写三段链路源码确认存在。X3 同族缺陷（回放 tool_call 不触发沙箱写入，fixme）细化：工具特定、只阻塞容器→KV 方向夹具化，不阻塞管理面→容器物化方向。复核修订已吸收：X11/X13 改同会话同代容器使仲裁断言可达 |
| 4 | /skills/available 与 parse-refs 按用户合并 L4（d0c3eaa） | skills | medium | core | 可行（SK2-4 零前置） | 无 | **证实**。grep skills/available\|parse-refs 于 e2e 全目录+手工脚本+计划文档零命中（EXIT:1）；SK1 自述只钉路由；合并语义（头优先/同名覆盖/静默回落）源码逐行核对无误；「KV 读取失败静默回落」严格分支黑盒不可注入，SK2 钉可达降级面；单测有覆盖（SkillCatalogServiceTest/SkillInjectionServiceTest），缺口严格限于 e2e HTTP 黑盒面。SK4 刻意不钉「同名 L4 覆盖禁用技能」边界（代码与 javadoc 分歧待裁决） |
| 5 | AGENT_MEMORY_ENABLED=false 记忆总开关关断分支（4bb4c26） | memory | medium | core（+sandbox 扩展） | 可行（MEM1-3 零前置；沙箱 no-op 用例需编排扩展） | MEM 组零前置；沙箱 X10 需 env-up sandbox 分支扩第二实例+agent_fs seed 脚本（SDK jar 行编码需逆向） | **证实（本 session 实跑取证）**。精确 grep AGENT_MEMORY_ENABLED\|memory-enabled\|memoryEnabled 于整个 e2e 目录零命中（exit=1）；false 分支三重后果（hooks+tools 双关/内部 LLM 调用消失/沙箱注入回写 no-op）与源码逐条吻合（HarnessAgentFactory.java:236-237/249、WorkspaceReader.java:142-145/785-789 等）；javap 佐证 SDK disableMemoryTools/disableMemoryHooks 存在且记忆提取提示词字面量与 mock 判据吻合 |
| 6 | A2A 链路 llm-calls 记录键（sid 对齐，e5d6ff3 之 A2A 半边） | tracing | low | core | 可行（A5-7 零前置） | 无（复用 plain 夹具） | **证实**。llm-calls 断言仅 api-core.spec.ts:167-175（S7，Channel 面，注释明示）；其余 7 个 spec 与 plugin-smoke.sh 零命中；A1/A2 对 llm-calls 零断言；单测 LlmLoggingMiddlewareTest#shouldPreferSessionIdOnA2ALink 同判据黑盒化。可执行性核实：标记路由包含式正则、check-fixtures 实跑 OK（llm=17 场景 exit=0）、PathSafe 对 sessionIdFor 形态恒等、A5-A7 编号无冲突 |
| 7 | OTel span 内容属性（gen_ai.input/output.messages、tool.call.arguments/result 与 8192 截断，3a9256a） | tracing | low | core | 可行（需先扩基建） | 需 mock/otlp-receiver.mjs + env-up/start-agent OTEL 环境注入 + lib/otlp.ts + 同变更文档同步（当前 e2e 实例不产任何 span） | **证实**。16 单测属实（git show --stat 3a9256a：仅新增两测试文件，6+10=@Test 计数）；e2e 全目录 grep span\|otlp\|gen_ai 零命中（唯一命中为无关的 userIndexTruncated）；机制根因：e2e 未注入任何 OTEL_*，OtelConfig @ConditionalOnProperty 不激活→全组无 span。三用例机制性前提（标记路由不破 20000 字消息、夹具参数、字节码 span 命名、OTLP/HTTP 纯 protobuf 可行性）逐项核实为真 |

---

## 4. 补充用例设计

> **说明**：① 以下草稿为设计产物，**尚未写入 tests/ 亦未执行**；② 每个缺口小节末尾附「复核意见（评审留痕）」——其中指出的必红/致命/假绿类问题**已在上方用例草稿中修订吸收**（对照可见：如 U15 统一 debug-user 身份、MOD5 enable_thinking 断 false、MOD6 唯一 modelId、X10(沙箱)/X11/X13 同会话同代容器、SK2 补 uidB PUT 断言、MEM1 describe 作用域、X10(记忆) 改 files/download 探针、A6 双判据表述、TR 组 describe 化与 TR3 弃正则）；③ 复核中引用的实跑验证均出自上游评审记录。

### 4.1 缺口一：压缩后会话历史双源合并与翻页游标（history，#45）—— 4 条：HA1/HA2/HA3/U15

**缺口描述**：服务端已实现 includeArchived/limit/beforeId 入参与 hasMore/nextBeforeId、origin 双源标记、role=compaction 合成项（ThreadController.java:215-226/614-629），但 e2e 对 compaction|includeArchived|beforeId|hasMore 零断言且无 compact 录制件；合并去重/游标回归会导致压缩前历史重复或丢页，属数据正确性级风险，单测 ThreadControllerHistoryMergeTest 不拦端到端装配（归档 Store 开关、store 装配失败回退）。

**复核证据要点**（上游评审实录）：
- 入参——ThreadController.java:212-227（:215 includeArchived 默认 true、:216 limit 默认 DEFAULT_HISTORY_LIMIT=200（:59）、:217 beforeId）；hasMore/nextBeforeId——:509-522（合并视图附带、state-only 恒 false/null）、游标取数 :540-549（limit+1 判 hasMore、subList(1) 剔最旧、nextBeforeId=本页最旧行 id，ArchivedRow.id 为 long（SessionMessageStore.java:51）→ JSON number）；origin 双源标记——:629（仅合并路径 postProcessMerged 添加，state-only 路径无此键）；role=compaction 合成项——:614-627（name=__compaction_summary__（SessionMessageStore.java:45）→ role=compaction/type=compaction_summary、透传 created_at、不带 origin）。
- e2e 零断言：`grep -rn -i 'compaction\|includeArchived\|beforeId\|hasMore\|archive' agent-framework/e2e/tests/` 零命中（exit 1）；同 pattern 对 e2e/scripts/plugin-smoke.sh 与 docs/e2e-ci-plan.md 亦 exit 1。无 compact 录制件：mock/fixtures/llm/ 共 17 夹具无任何 compaction 场景，registry.json 的 llm 场景表无 compaction 标记。
- 单测不拦端到端装配：ThreadControllerHistoryMergeTest.java 覆盖合并/翻页/回退逻辑（:92/:132/:156/:173/:189/:210），但全部打在 mock SessionMessageStore 上（:53 DataSource mock、:60 store mock）——真实 Spring 装配开关（AgentScopeConfig.java:245-246 仅当 archiveEnabled 时装饰 Backfill(Archive(SandboxAwareMysql)) 链）、真实 5 形谓词 SQL（SessionMessageStore.java:176-184）、真实 write-through（SessionMessageArchiveStateStore.java:56-81 三写路径）与 HTTP 成链无端到端覆盖；精度说明：store 异常回退逻辑本身有 mock 级用例（:210-212）。顺带修复点属实：reset-data.mjs:18-19 TABLES 确无 session_message。
- 种子设施实跑验证：本机 e2e 库 13306 INSERT/SELECT/DELETE 往返通过（三行自增 id 429/430/431 严格升序、created_at DATETIME(3) 字面量 '2026-09-27 10:00:00.000' 原样往返）；实库存在 5 条真实 kind='compaction_summary' 行，msg_data 形态与种子完全同构；探针行已 DELETE 清零。本机 e2e 库 session_message 316 行/69 会话，chat(userId,sessionId) 落库键形如 `e2e-tester:e2e_<run>-<case>`，历史 e2e 轮确有归档行。
- **未跑项**：四条用例的 Playwright 实测未执行（本机 :8100 服务未启动，curl 探活为空）；可执行性结论基于源码逐行核对 + 实库 SQL 往返，非端到端实跑。

#### HA1 未压缩会话 history 合并视图契约与双源一致（origin/msg_id 去重/hasMore 基线）

- **落点**：agent-framework/e2e/tests/api-core.spec.ts（文件尾新增"HA 组：压缩归档历史（#45）"小节，SK 组之后）
- **blocker**：none
- **前置**：
  - e2e-core 环境（env-up + mock LLM :18081）；无需新增录制件，复用 plain 夹具与 [E2E:plain] 标记
  - 归档开关默认开启：application.yml:48-49 `archive-enabled: ${AGENT_HISTORY_ARCHIVE_ENABLED:true}`，start-agent.sh 未覆盖该变量 → 对话 write-through 归档 session_message（SessionMessageArchiveStateStore），合并视图是真默认路径
  - 已实跑取证：本机 e2e 库 session_message 316 行/69 会话，chat(userId,sessionId) 落库键形如 `e2e-tester:e2e_<run>-<case>`，历史 e2e 轮确有归档行
- **步骤**：
  1. sessionIdFor 生成 ha1 会话，依次 POST /threads/chat 两轮 `[E2E:plain](ha1-first-*)`、`[E2E:plain](ha1-second-*)`（userId=e2e-tester），各收敛 done 终态
  2. GET /threads/{sid}/history（默认 includeArchived=true 合并视图），pollUntil 等两轮 user 消息可见（吸收落库时序）
  3. GET /threads/{sid}/history?includeArchived=false（仅 agent_state 视图）
- **断言**：
  - 合并视图 hasMore===false 且 nextBeforeId===null（小会话在默认 200 条内，ThreadController.java:509-522）
  - 每条消息带非空 msg_id 且 origin∈{archive,state}；msg_id 全集唯一（双源去重回归即在此红）
  - 未压缩会话所有消息 origin==='archive'（state 无独有消息，write-through 完整）
  - user 消息在合并视图与 state-only 视图同序同文（双源内容一致，按 ha1- 标记过滤比较）
  - includeArchived=false 视图同样 hasMore===false/nextBeforeId===null，且每条消息不含 origin 键（origin 是合并视图专属契约，仅 ThreadController.java:629 添加）
- **draftCode**：

```typescript
// ---------- HA 组：压缩归档历史（#45，docs/session-history-archive-design.md）----------
// 契约权威：ThreadController#threadHistory（includeArchived/limit/beforeId → hasMore/nextBeforeId、
// origin 双源标记、role=compaction 合成项）。e2e 实例归档默认开启（application.yml
// AGENT_HISTORY_ARCHIVE_ENABLED:true，start-agent.sh 未覆盖）——对话 write-through 归档，
// 合并视图是真默认路径，此前用例对它零断言。

type HistoryMsg = Record<string, unknown>;

/** 带 query 的 history 查询（lib/client.js 的 history 不带参；可下沉为 historyQ） */
async function historyQ(sid: string, query = ''): Promise<Record<string, unknown>> {
  const res = await fetch(`${BASE}/threads/${encodeURIComponent(sid)}/history${query ? `?${query}` : ''}`);
  expect(res.status, 'history HTTP 状态').toBe(200);
  return res.json() as Promise<Record<string, unknown>>;
}

const userTexts = (h: Record<string, unknown>) =>
  ((h.messages ?? []) as HistoryMsg[]).filter(m => m.role === 'user').map(m => String(m.content));

test('HA1 未压缩会话 history 合并视图契约与双源一致', async () => {
  const sid = sessionIdFor(`ha1-${uniq()}`);
  const first = `ha1-first-${uniq()}`;
  const second = `ha1-second-${uniq()}`;
  for (const arg of [first, second]) {
    const s = chat({ message: `[E2E:plain](${arg})`, userId: U, sessionId: sid });
    await waitTerminal(s);
    expect(s.terminal?.type).toBe('done');
  }
  // 合并视图（includeArchived 默认 true）：等两轮 user 消息都可见（吸收落库时序）
  const merged = await pollUntil(async () => historyQ(sid), h => userTexts(h).length >= 2);
  expect(merged.hasMore).toBe(false);
  expect(merged.nextBeforeId ?? null).toBeNull();
  const msgs = merged.messages as HistoryMsg[];
  expect(msgs.length).toBeGreaterThan(0);
  const seen = new Set<string>();
  for (const m of msgs) {
    expect(String(m.msg_id ?? '')).not.toBe('');
    expect(['archive', 'state'], JSON.stringify(m)).toContain(m.origin);
    seen.add(String(m.msg_id));
  }
  expect(seen.size, '合并视图 msg_id 不得重复（双源去重回归即在此红）').toBe(msgs.length);
  for (const m of msgs) expect(m.origin, '未压缩会话应全部 origin=archive').toBe('archive');
  // 内容双源一致：带标记的 user 消息在两视图同序同文
  const markerOf = (ts: string[]) => ts.filter(t => t.includes('ha1-'));
  expect(markerOf(userTexts(merged))).toEqual([`[E2E:plain](${first})`, `[E2E:plain](${second})`]);
  const stateOnly = await historyQ(sid, 'includeArchived=false');
  expect(stateOnly.hasMore).toBe(false);
  expect(stateOnly.nextBeforeId ?? null).toBeNull();
  expect(markerOf(userTexts(stateOnly))).toEqual(markerOf(userTexts(merged)));
  // origin 是合并视图专属键：state-only 视图不得携带
  for (const m of (stateOnly.messages ?? []) as HistoryMsg[]) expect('origin' in m).toBe(false);
});
```

#### HA2 beforeId 游标全量遍历：不丢页、不重复、游标严格递减、末页收敛（limit=1 走查）

- **落点**：agent-framework/e2e/tests/api-core.spec.ts（HA 组）
- **blocker**：none
- **前置**：
  - 同 HA1 的 e2e-core 环境与归档默认开启；无需新增录制件（两轮 [E2E:plain]）
  - 游标语义（ThreadController.java:540-549）：findPage 取 limit+1 条、hasMore 时 subList(1) 剔最旧、nextBeforeId=本页最旧归档行 id（SessionMessageStore.java:171-212 DESC 取数后翻转为升序）
- **步骤**：
  1. ha2 会话两轮 `[E2E:plain](ha2-a-*)`/`[E2E:plain](ha2-b-*)` 收敛 done
  2. GET /history（默认视图）取全量 msg_id 集合作基准
  3. 循环 GET /history?limit=1（其后带 &beforeId=上一页 nextBeforeId），逐页向后翻直至 hasMore=false（护栏 50 轮）
  4. 附加 GET /history?includeArchived=false&limit=1（state-only 视图忽略分页的契约）
- **断言**：
  - 全量视图 hasMore===false（默认 200 条上限内）
  - 每页至少 1 条；hasMore=true 时 nextBeforeId 为 number 且逐页严格递减（游标必须前进）
  - 末页 hasMore===false 且 nextBeforeId===null
  - 各页 msg_id 依序拼接后：总数 === 全量视图条数 且 集合相等——丢页（游标跳行）与跨页重复（去重失效）任一回归即红
  - includeArchived=false&limit=1 → hasMore===false（分页游标仅合并视图语义）
- **draftCode**：

```typescript
test('HA2 beforeId 游标全量遍历：不丢页、不重复、末页收敛', async () => {
  const sid = sessionIdFor(`ha2-${uniq()}`);
  for (const arg of [`ha2-a-${uniq()}`, `ha2-b-${uniq()}`]) {
    const s = chat({ message: `[E2E:plain](${arg})`, userId: U, sessionId: sid });
    await waitTerminal(s);
    expect(s.terminal?.type).toBe('done');
  }
  const full = await pollUntil(async () => historyQ(sid), h => userTexts(h).length >= 2);
  expect(full.hasMore).toBe(false); // 小会话在默认 200 条内，首页即全量
  const fullIds = ((full.messages ?? []) as HistoryMsg[]).map(m => String(m.msg_id));

  // limit=1 逐页向后翻（游标 = 本页最旧归档行 id），直到末页
  const pageIds: string[] = [];
  let cursor: number | null = null;
  for (let i = 0; i < 50; i++) {
    const page = await historyQ(sid, cursor === null ? 'limit=1' : `limit=1&beforeId=${cursor}`);
    const msgs = (page.messages ?? []) as HistoryMsg[];
    expect(msgs.length, `第 ${i + 1} 页不得为空`).toBeGreaterThanOrEqual(1);
    for (const m of msgs) pageIds.push(String(m.msg_id));
    if (!page.hasMore) {
      expect(page.nextBeforeId ?? null, '末页不得再给游标').toBeNull();
      break;
    }
    expect(typeof page.nextBeforeId).toBe('number');
    const next = Number(page.nextBeforeId);
    if (cursor !== null) expect(next, '游标必须严格递减').toBeLessThan(cursor);
    cursor = next;
  }
  // 全量遍历恰好覆盖首页视图：不丢页（游标跳行）也不重复（跨页去重失效）
  expect(pageIds.length).toBe(fullIds.length);
  expect(new Set(pageIds)).toEqual(new Set(fullIds));
  // 分页游标是合并视图专属：state-only 视图忽略 limit 恒 hasMore=false
  const statePaged = await historyQ(sid, 'includeArchived=false&limit=1');
  expect(statePaged.hasMore).toBe(false);
});
```

#### HA3 压缩摘要分隔条 + 未归档尾部双源合并 + 深翻页只走归档行（直插 session_message 种子）

- **落点**：agent-framework/e2e/tests/api-core.spec.ts（HA 组）；配套新增 agent-framework/e2e/lib/archive-seed.ts（draftCode 内含完整实现）
- **blocker**：需扩 mock
- **前置**：
  - 需扩 mock（种子设施，非 LLM 录制件）：新增 lib/archive-seed.ts 直插 session_message（SQL 形态已在本机 e2e 库 13306 实跑 INSERT/SELECT/DELETE 往返验证通过，自增 id 严格升序）；reset-data.mjs:18-19 TABLES 补 'session_message' 清场（缺口指出的顺带修复）
  - mysql CLI 可达（reset-data.mjs:26 已同款依赖，CI ubuntu 镜像自带）；测试进程能读到 MYSQL_URL/MYSQL_USER/MYSQL_PASS（四 job 均注入，agent-framework-ci.yml:98-100/:162-164/:219-221/:277-279；本地与 env-up.sh:17-19 同默认值）
  - 无需新增 LLM 夹具/标记：[E2E:plain] 按 marker 逐请求路由可无限复用（llm-server.mjs:82-91，S2/S7 先例），check-fixtures.mjs:27 只校验下限不需改 registry；压缩摘要行按真实 SDK Msg 序列化形态直插（实库取证 5 条真实 kind='compaction_summary' 行 msg_data 形如 {"id":"__compaction_summary__:<uuid>","name":"__compaction_summary__","role":"USER",...}，与单测 ThreadControllerHistoryMergeTest MSG_M2_SUMMARY 同构）；未归档尾部由真实 [E2E:plain] turn 提供后删其归档行制造
  - slotKey `{normalizeUser(userId)}:{sid}`（SessionMessageArchiveStateStore.java:193-196）与种子键一致；deleteThread 级联清 session_message（ThreadController.java:277-280）供收尾清理
- **步骤**：
  1. ha3 会话 POST /threads/chat `[E2E:plain](ha3-tail-*)` 收敛 done（真实一轮，state+归档同写）
  2. 调 seedCompactionArchive(`e2e-tester:{sid}`, {sid}, mark)：按 store 同款 5 形谓词 DELETE 本会话真实归档行（制造"state 有、归档无"的未归档尾部）→ 单条多值 INSERT 三行：压缩前 user 行、__compaction_summary__ 摘要行、压缩前 assistant 行（id 相邻升序，已实库验证）
  3. GET /threads/{sid}/history 默认合并视图，校验首屏合并语义
  4. GET /history?limit=2：首页 = 最新 2 条归档行 + state 尾部合入，hasMore=true
  5. GET /history?limit=2&beforeId={nextBeforeId}：深翻页只走归档行
  6. 收尾 DELETE /threads/{sid}（级联 session_message，S7 同款清理）
- **断言**：
  - 首页全量视图：m1(archive) → role=compaction/type=compaction_summary 分隔条（content 含种子摘要文本、msg_id=种子摘要 id、created_at 透传归档行时间戳、且不携带 origin 键）→ m3(archive) 严格按归档 id 升序排列
  - state 尾部消息以 origin='state' 合入基底末尾（位置在 m3 之后、content 含 ha3-tail- 标记）——归档行删除后尾部不被吞
  - 全视图 msg_id 集合唯一；hasMore=false/nextBeforeId=null
  - limit=2 首页：含分隔条（中间页同样渲染）与 origin=state 尾部，不含更早归档行 m1；nextBeforeId 为 number 且指向本页最旧归档行（翻页后恰得 [m1]，已按 SessionMessageStore.java:171-212 DESC 取数翻转 + ThreadController.java:540-549 手工推演）
  - 深翻页：messages 恰为 [m1] 且 origin='archive'，无任何 origin='state' 项/尾部标记（翻页不并 state，ThreadController.java:577-594）；hasMore=false、nextBeforeId=null；首页与深翻页 msg_id 无交集（跨页去重）
- **draftCode**：

```typescript
// 新文件 lib/archive-seed.ts（HA3/U15 共用种子设施；SQL 已在本机 e2e 库 13306 实跑往返验证）
import { execFileSync } from 'node:child_process';

/** e2e 库连接参数：与 reset-data.mjs / env-up.sh:17-19 同源（CI job env 注入；本地同默认值） */
const JDBC = process.env.MYSQL_URL ?? 'jdbc:mysql://127.0.0.1:3306/agent_framework_e2e';
const DB_USER = process.env.MYSQL_USER ?? 'e2e';
const DB_PASS = process.env.MYSQL_PASS ?? 'e2e-pass';

export interface CompactionSeed { m1: string; summaryId: string; m3: string; summaryText: string }

const esc = (s: string) => s.replace(/\\/g, '\\\\').replace(/'/g, "''");
const like = (s: string) => esc(s.replace(/%/g, ''));

/**
 * 直插“压缩归档”种子（无 compact 录制件的替代路线）：先按 SessionMessageStore.deleteBySession
 * 同款 5 形谓词清掉本会话真实归档行（制造“state 有、归档无”的未归档尾部），再插入压缩前
 * 历史 + __compaction_summary__ 摘要行（msg_data 与 SDK Msg 序列化同形，形态取证自实跑库，
 * 与单测 ThreadControllerHistoryMergeTest 同构）。三行同一 INSERT，id 相邻升序，翻页断言可控。
 */
export function seedCompactionArchive(slotKey: string, sid: string, mark: string, userName: string): CompactionSeed {
  const m = /jdbc:mysql:\/\/([^:/]+):(\d+)\/([^?]+)/.exec(JDBC);
  if (!m) throw new Error(`MYSQL_URL 解析失败: ${JDBC}`);
  const [, host, port, db] = m;
  const run = (sql: string) => execFileSync('mysql',
    ['-h', host, '-P', port, '-u', DB_USER, `-p${DB_PASS}`, db, '--batch', '--skip-column-names', '-e', sql],
    { stdio: ['ignore', 'pipe', 'pipe'] }).toString();
  const m1 = `ha-m1-${mark}`;
  const summaryId = `__compaction_summary__:${mark}`;
  const m3 = `ha-m3-${mark}`;
  const summaryText = `You are in the middle of a conversation that has been summarized. 压缩摘要-${mark}`;
  const row = (msgId: string, kind: string, role: string, json: string) =>
    `('${esc(slotKey)}','${esc(msgId)}','${kind}','${role}',NULL,'${esc(json)}','2026-09-27 10:00:00.000','2026-09-27 10:00:00.000')`;
  run(`DELETE FROM session_message WHERE session_id='${like(sid)}' OR session_id LIKE '${like(sid)}:%'
     OR session_id LIKE '${like(sid)}__%' OR session_id LIKE '%:${like(sid)}' OR session_id LIKE '%__${like(sid)}';
INSERT INTO session_message (session_id, msg_id, kind, role, reply_id, msg_data, created_at, updated_at) VALUES
${row(m1, 'message', 'USER', `{"id":"${m1}","name":"${userName}","role":"USER","content":[{"type":"text","text":"归档-压缩前用户消息-${mark}"}]`)},
${row(summaryId, 'compaction_summary', 'USER', `{"id":"${summaryId}","name":"__compaction_summary__","role":"USER","content":[{"type":"text","text":"${summaryText}"}]`)},
${row(m3, 'message', 'ASSISTANT', `{"id":"${m3}","name":"E2E Test Agent","role":"ASSISTANT","content":[{"type":"text","text":"归档-压缩前助手回复-${mark}"}]}`)};
`);
  return { m1, summaryId, m3, summaryText };
}

// ---------- api-core.spec.ts HA 组追加（imports 增补：deleteThread 已在文件头，另引 seedCompactionArchive） ----------
test('HA3 压缩摘要分隔条 + 未归档尾部双源合并 + 深翻页只走归档行', async () => {
  const sid = sessionIdFor(`ha3-${uniq()}`);
  const mark = uniq();
  const tailArg = `ha3-tail-${mark}`;
  // 1) 真实一轮对话充当“未归档尾部”（write-through 也会归档它，下一步删其归档行）
  const s = chat({ message: `[E2E:plain](${tailArg})`, userId: U, sessionId: sid });
  await waitTerminal(s);
  expect(s.terminal?.type).toBe('done');
  // 2) 种子：清本会话归档行 → 直插压缩前历史 + __compaction_summary__
  const seed = seedCompactionArchive(`${U}:${sid}`, sid, mark, U);

  // 3) 首页合并视图：归档基底升序 + 摘要分隔条 + state 尾部合入基底末尾
  const full = await historyQ(sid);
  const msgs = (full.messages ?? []) as HistoryMsg[];
  const m1i = msgs.findIndex(m => m.msg_id === seed.m1);
  const sum = msgs.find(m => m.msg_id === seed.summaryId);
  const m3i = msgs.findIndex(m => m.msg_id === seed.m3);
  expect(m1i, '压缩前用户消息(归档)缺失').toBeGreaterThanOrEqual(0);
  expect(sum, '压缩摘要分隔条合成项缺失').toBeTruthy();
  expect(m3i, '压缩前助手消息(归档)缺失').toBeGreaterThanOrEqual(0);
  expect(m1i).toBeLessThan(msgs.indexOf(sum!));
  expect(msgs.indexOf(sum!)).toBeLessThan(m3i); // 归档行按 id 升序为时间线基底
  expect(sum!.role).toBe('compaction');
  expect(sum!.type).toBe('compaction_summary');
  expect(String(sum!.content)).toContain(`压缩摘要-${mark}`);
  expect(String(sum!.created_at ?? '')).toContain('2026-09-27 10:00');
  expect('origin' in sum!, '分隔条合成项不标 origin').toBe(false);
  expect(msgs.find(m => m.msg_id === seed.m1)!.origin).toBe('archive');
  expect(msgs.find(m => m.msg_id === seed.m3)!.origin).toBe('archive');
  const tail = msgs.find(m => m.origin === 'state' && String(m.content).includes(tailArg));
  expect(tail, '未归档尾部必须以 origin=state 合入基底末尾').toBeTruthy();
  expect(msgs.indexOf(tail!)).toBeGreaterThan(m3i);
  const ids = msgs.map(m => String(m.msg_id));
  expect(new Set(ids).size, '全视图 msg_id 不得重复').toBe(ids.length);
  expect(full.hasMore).toBe(false);
  expect(full.nextBeforeId ?? null).toBeNull();

  // 4) 翻页（limit=2）：首页 = 最新 2 条归档 + state 尾部；游标指向本页最旧归档行
  const page1 = await historyQ(sid, 'limit=2');
  const p1 = (page1.messages ?? []) as HistoryMsg[];
  expect(page1.hasMore).toBe(true);
  expect(typeof page1.nextBeforeId).toBe('number');
  expect(p1.some(m => m.msg_id === seed.summaryId), '分隔条在中间页同样渲染').toBe(true);
  expect(p1.some(m => m.origin === 'state' && String(m.content).includes(tailArg)),
    '首页(beforeId=null)才合入 state 尾部').toBe(true);
  expect(p1.some(m => m.msg_id === seed.m1), 'limit=2 首页不得包含更早归档行').toBe(false);

  // 5) 深翻页只走归档行：尾部不得再现，游标恰落在 [m1]
  const page2 = await historyQ(sid, `limit=2&beforeId=${Number(page1.nextBeforeId)}`);
  const p2 = (page2.messages ?? []) as HistoryMsg[];
  expect(p2.map(m => String(m.msg_id))).toEqual([seed.m1]);
  expect(p2[0].origin).toBe('archive');
  expect(p2.some(m => 'origin' in m && m.origin === 'state'), '深翻页不得合入 state 消息').toBe(false);
  expect(p2.some(m => String(m.content).includes(tailArg)), '深翻页不得再现尾部').toBe(false);
  expect(page2.hasMore).toBe(false);
  expect(page2.nextBeforeId ?? null).toBeNull();

  await deleteThread(sid).catch(() => undefined); // 级联清理种子归档行（S7 同款）
});
```

#### U15 Debug 页压缩分隔条历史回放（归档合并视图 → details.compaction-divider 渲染）

- **落点**：agent-framework/e2e/tests/ui.spec.ts（U 组末尾，U14 之后；ui 项目，随 e2e-core job 执行）
- **blocker**：需扩 mock
- **前置**：
  - 同 HA3 的种子设施（lib/archive-seed.ts + reset-data.mjs TABLES 补 session_message）；无需新增 LLM 录制件（复用 plain）
  - userId 必须用 'debug-user'（复核修订，原稿用 'e2e-ui' 不可行）：debug 页会话列表按页面当前 userId 服务端过滤——loadThreads 取 state('ui.userId')||'debug-user' 调 getThreads(uid)（chat.js:435-439 → js/api.js:125 拼 ?userId=），ThreadController.java:152 `WHERE su.user_id = ?` 精确匹配；新开页面（Playwright 每用例全新 context，无 localStorage）uid 恒为 'debug-user'（chat.js:126），其余 userId 的会话永不渲染。故 API chat 与种子 slotKey 统一用 'debug-user'（normalizeUser 非空原样透传，SessionMessageArchiveStateStore.java:193-196）
  - 前端渲染链路已存在：static/debug/modules/chat.js:645 `if (m.role === 'compaction') addCompactionDivider(...)`，:692-707 折叠卡 details.compaction-divider / summary 文案"上下文已压缩 · 早期消息已归档"/ .compaction-summary 正文
  - ui 项目 chromium（e2e-core job 已安装）；ui.spec.ts imports 增补 chat/deleteThread（lib/client.js）、waitTerminal（lib/matchers.js）、seedCompactionArchive（lib/archive-seed.js）
- **步骤**：
  1. API 侧与页面身份对齐：userId='debug-user' POST /threads/chat `[E2E:plain](u15-tail-*)` 收敛 done → seedCompactionArchive(`debug-user:{sid}`, …) 直插压缩摘要归档行（5 形谓词清真实归档行制造 state 尾部）
  2. page.goto('/debug/')，新开页面默认身份即 'debug-user'（chat.js:126），等待会话列表渲染后按 `.thread-item[data-sid="{sid}"]` 精确点选（U13 同款：列表首位不可靠）
  3. 等待历史回放渲染出压缩分隔条，点开 summary 展开摘要正文
  4. 收尾 DELETE /threads/{sid} 清场
- **断言**：
  - details.compaction-divider 可见（role=compaction 合成项的 UI 渲染——压缩后会话在页面上"历史可查"的最终交付面）
  - summary 文案含"上下文已压缩"；展开后 .compaction-summary 含种子摘要文本（压缩摘要-{mark}）
  - 未归档尾部消息照常回放：#chatInner 含 u15-tail- 标记文本（合并视图不影响尾部渲染）
  - 全程无 pageerror（U2 同款守卫可选加）
- **draftCode**：

```typescript
// ui.spec.ts imports 增补：
//   import { chat, createApprovalApp, deleteThread, llmReset, llmStats } from '../lib/client.js';
//   import { pollUntil, waitTerminal } from '../lib/matchers.js';
//   import { seedCompactionArchive } from '../lib/archive-seed.js';
// 种子设施与 api-core HA3 共用 lib/archive-seed.ts（无 compact 录制件，直插 __compaction_summary__
// 归档行；真实一轮对话充当 state 尾部——见 HA3 preconditions）。

// 复核修订：不能用文件级 U('e2e-ui')——debug 页会话列表按页面当前 userId 服务端过滤
//（chat.js:435-439 → js/api.js:125 → ThreadController.java:152 WHERE su.user_id = ?），
// 新开页面 uid 恒为 'debug-user'（chat.js:126，Playwright 全新 context 无 localStorage），
// 其余 userId 的会话永不渲染。会话与种子必须同用 'debug-user'（页面默认身份，零额外交互）。
const UI_UID = 'debug-user';

test('U15 压缩分隔条历史回放（归档合并视图渲染）', async ({ page }) => {
  const sid = sessionIdFor(`u15-${uniq()}`);
  const mark = uniq();
  const tailArg = `u15-tail-${mark}`;
  const stream = chat({ message: `[E2E:plain](${tailArg})`, userId: UI_UID, sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  const seed = seedCompactionArchive(`${UI_UID}:${sid}`, sid, mark, UI_UID);

  await page.goto('/debug/');
  // 页面默认身份即 debug-user（chat.js:126），列表会拉到本会话；若未来页面默认身份变化，
  // 兜底手法是先 fill('#uidInput', UI_UID) 触发 input 监听器重拉列表（chat.js:219-225）
  const item = page.locator(`${SEL.threadList} .thread-item[data-sid="${sid}"]`);
  await item.waitFor({ state: 'visible', timeout: 30_000 });
  await item.click();
  // 压缩分隔条：role=compaction 合成项的折叠卡（chat.js addCompactionDivider）
  const divider = page.locator(`${SEL.chatInner} details.compaction-divider`);
  await expect(divider).toBeVisible({ timeout: 60_000 });
  await expect(divider.locator('summary')).toContainText('上下文已压缩');
  await divider.locator('summary').click();
  await expect(page.locator(`${SEL.chatInner} .compaction-summary`)).toContainText(`压缩摘要-${mark}`);
  // 未归档尾部消息照常回放（合并视图不影响尾部渲染）
  await expect(page.locator(SEL.chatInner)).toContainText(tailArg, { timeout: 30_000 });
  await deleteThread(sid).catch(() => undefined);
});
```

**复核意见（评审留痕）**：
- U15（blocker，可执行性）：原稿以 ui.spec.ts 的 U='e2e-ui'（ui.spec.ts:15）经 API chat 建会话并按 `${U}:${sid}` 种子，随后期望在 debug 页会话列表点选 `.thread-item[data-sid="{sid}"]`——但该列表按页面当前 userId 服务端过滤：chat.js:435-439 loadThreads 以 state 'ui.userId'||'debug-user' 调 getThreads(uid)（api.js:125 拼 ?userId=），ThreadController.java:152 命中 `WHERE su.user_id = ?`；新开页面 uid 恒为 'debug-user'（chat.js:126），'e2e-ui' 的会话永远不渲染，item.waitFor 30s 必超时。修法二选一：(a) 会话与种子统一改用 'debug-user'；(b) goto 后先 `page.locator('#uidInput').fill('e2e-ui')` 触发重拉列表。（已采纳 (a)）其余核实结论：HA1/HA2/HA3/U15 的 caseId 无冲突（api-core.spec.ts 现有 S/F/H/M/A/SK 组止于 :634 SK1；ui.spec.ts 现有 U1-U14+U-SK1..7）；落点项目映射正确（api-core/ui → e2e-core，playwright.config.ts:23/28；e2e-core job 有 mysql 服务与 chromium）；mock 能力足够——[E2E:xxx] 按 marker 逐请求路由可无限复用 plain，check-fixtures.mjs:27 只校验下限不需改 registry；归档开关默认开且无覆盖；slotKey 与种子键一致，deleteThread 级联清 session_message；种子 SQL 已实库验证；HA3 翻页数学手工推演通过；分页契约断言与 origin 契约均与源码吻合；HA1/HA2 复用的 helper 均为 api-core.spec.ts 既有 helper（:6-14）。未跑项：四条用例的 Playwright 实测未执行。

### 4.2 缺口二：/models 采样参数扩展与 LLM 请求体方言（model，3dad588）—— 4 条：MOD4/MOD5/MOD6/MOD7

**缺口描述**：ModelController.java:117-118/134-135 已实现新字段校验（enum/[-2,2]/^[a-z0-9_]{1,16}$/、空串清除、PATCH 缺省不变）与回显，但 api-models.spec.ts 仅 MOD1:52/MOD2:96/MOD3:122 三个用例且 grep reasoning|frequency|provider 零命中——400 契约与回显全靠单测；方言分支（chat_template_kwargs 合并/glm thinking/deepseek 不下发）因 mock stats 只记 model/toolCallNames/hasImageBlock（llm-server.mjs:151-156）在黑盒不可见，env 大写未归一落错分支会让严格端点 400 而门禁不红。

**复核证据要点**：
- 实现侧属实：ModelController.java:117-121（POST validateSamplingParams 校验）、134-135（normEffort/frequencyPenalty 落库）、169-173/181-190（PATCH 三态：effort 空串清除/penalty 非空替换/provider 空白保持旧值）、264-311（systemModelView/managedView 回显 reasoning_effort 经 blankToNull）、329-332（400 + error='invalid_config'）、358-371（provider 值域 deepseek/glm/openai/sglang/vllm @64-65、effort ^[a-z0-9_]{1,16}$ 上限 16 @61、penalty [-2.0,2.0]）。
- e2e 缺口属实：api-models.spec.ts 恰好 3 个用例——MOD1@52、MOD2@96、MOD3@122；实跑 `grep -rniE "reasoning|frequency|provider" e2e/tests/ e2e/scripts/plugin-smoke.sh docs/e2e-ci-plan.md` → 零命中（exit 1，无输出）。
- '400 契约与回显全靠单测'属实：ModelControllerTest.java:277-367 覆盖 invalid_config 400、vllm/medium/0.5 落库、PATCH 三态；新字段来自 3dad588，无配套 e2e。
- 方言分支黑盒不可见属实：ChatModelFactory.java:61-124（vllm/sglang kwargs 合并 @92-104、glm @105-113、deepseek 不下发 @114-116、openai/default 顶层 effort @117-122）；mock/llm-server.mjs:149-158 的 stats.calls.push 无任何请求体采样键 → 回放引擎不校验请求体（:202-233 只按标记路由回放），方言落错/缺失在 e2e 全程无信号。
- env 大写不可见机制属实：归一只在方言层 ChatModelFactory.java:88-90（trim+toLowerCase），视图层原样回显（ModelController.java:270 ← ModelCatalog.systemLlmConfig ← application.yml:29 ${LLM_PROVIDER:openai}）→ 'VLLM' 落 default 分支（顶层 effort、无 kwargs）在 REST 断言与 stats 中均不可见。
- 细节修正：缺口陈述称 stats '只记 model/toolCallNames/hasImageBlock'不全——llm-server.mjs:150 还记录 scenario（含 'plain'/'background-synth'），api-models.spec.ts:141/164 已在用 scenario 过滤；但采样键确实缺失，缺口结论不受影响。

#### MOD4 托管模型新采样参数 REST 契约：provider 枚举/reasoningEffort 格式/frequencyPenalty 范围 400 与回显三态（零 LLM 依赖）

- **落点**：agent-framework/e2e/tests/api-models.spec.ts（api-models 项目 → CI e2e-core job，MOD 组直连 REST 风格）
- **blocker**：none
- **前置**：
  - e2e-core 环境照常（env-up 已起 :8100 被测实例与 mock LLM）；本用例全程 POST/PATCH/GET /models 直连，零 LLM 调用
  - 无新增录制件、无 mock 改动（对照 ModelController.java validateSamplingParams/normEffort 的已实现契约）
- **步骤**：
  1. POST /models 提供非法 provider 'azure' → 断 400
  2. POST /models 提供非法 reasoningEffort 'Medium'（含大写）与 17 字符超长值 → 各断 400
  3. POST /models 提供 frequencyPenalty 2.1 与 -2.1 → 各断 400
  4. POST /models 提供 frequencyPenalty=-2（下边界）+ provider='vllm' + reasoningEffort=' high '（带空白）→ 断 200 与回显
  5. POST /models 提供 frequencyPenalty=2（上边界）→ 断 200 与回显
  6. POST /models 不带任何新字段 → 断 200 与默认回显
  7. POST /models 提供 reasoningEffort=''（空串）→ 断 200 且 reasoning_effort=null
  8. PATCH /models/{id} 仅改 name（新字段全缺省）→ 断 200 且三项采样参数不变
  9. PATCH /models/{id} provider=''（空白串）→ 断 200 且 provider 保持旧值
  10. PATCH /models/{id} reasoningEffort='' + frequencyPenalty=2 → 断清除与替换
  11. PATCH /models/{id} 提供非法 reasoningEffort 'HIGH' 与越界 frequencyPenalty 3 → 断 400，随后 GET /models/{id} 断旧值未被部分写入
- **断言**：
  - 非法 provider/effort/penalty 一律 400 且 body.error==='invalid_config'、message 含 'provider must be one of' / 'reasoningEffort must match' / 'frequencyPenalty must be within [-2.0, 2.0]'（错误码契约，与 duplicate_name 同层）
  - frequencyPenalty=±2.0 边界 POST 双侧放行（200 且回显原值），±2.1 双侧拒绝（400）——开闭边界锁定
  - POST 缺省新字段回显 provider==='openai'、reasoning_effort===null、frequency_penalty===null（严格 null 而非缺键或空串）
  - POST reasoningEffort='' 回显 null；reasoningEffort=' high ' 回显 'high'（trim 归一）
  - PATCH 缺省三项 → 回显旧值不变；PATCH provider:'' → provider 不变（空白=保持）
  - PATCH reasoningEffort:'' → null（清除）、frequencyPenalty:2 → 2（替换，不支持撤销）
  - PATCH 非法 effort/penalty → 400 且 GET /models/{id} 确认旧值未被部分写入
- **draftCode**：

```typescript
// 追加进 agent-framework/e2e/tests/api-models.spec.ts（复用文件级 createModel/uniq/managedIds 与 afterEach 清理）

/** 新采样参数 400 契约：invalid_config + message 片段 */
async function expectInvalidConfig(response: { status: () => number; json: () => Promise<Record<string, unknown>> }, messageFragment: string): Promise<void> {
  expect(response.status()).toBe(400);
  const body = await response.json();
  expect(body.error).toBe('invalid_config');
  expect(String(body.message)).toContain(messageFragment);
}

test('MOD4 新采样参数契约：provider 枚举/effort 格式/penalty 范围 400 与回显三态', async ({ request }) => {
  // —— POST 负例：provider 值域（ModelController.validateSamplingParams）——
  await expectInvalidConfig(
    await request.post('/models', { data: { name: `e2e-bad-${uniq()}`, modelId: 'm', baseUrl: `${LLM_MOCK}/v1`, provider: 'azure' } }),
    'provider must be one of',
  );
  // —— POST 负例：reasoningEffort 格式（^[a-z0-9_]{1,16}$，大写与超长均拒）——
  await expectInvalidConfig(
    await request.post('/models', { data: { name: `e2e-bad-${uniq()}`, modelId: 'm', baseUrl: `${LLM_MOCK}/v1`, reasoningEffort: 'Medium' } }),
    'reasoningEffort must match',
  );
  await expectInvalidConfig(
    await request.post('/models', { data: { name: `e2e-bad-${uniq()}`, modelId: 'm', baseUrl: `${LLM_MOCK}/v1`, reasoningEffort: 'x'.repeat(17) } }),
    'reasoningEffort must match',
  );
  // —— POST 负例：frequencyPenalty 开区间越界（[-2.0, 2.0]）——
  await expectInvalidConfig(
    await request.post('/models', { data: { name: `e2e-bad-${uniq()}`, modelId: 'm', baseUrl: `${LLM_MOCK}/v1`, frequencyPenalty: 2.1 } }),
    'frequencyPenalty must be within [-2.0, 2.0]',
  );
  await expectInvalidConfig(
    await request.post('/models', { data: { name: `e2e-bad-${uniq()}`, modelId: 'm', baseUrl: `${LLM_MOCK}/v1`, frequencyPenalty: -2.1 } }),
    'frequencyPenalty must be within [-2.0, 2.0]',
  );

  // —— 边界值 + trim 归一：±2.0 恰好过界（POST 双侧对称）；effort 前后空白被去除 ——
  const edgeLow = await createModel(request, {
    provider: 'vllm', reasoningEffort: ' high ', frequencyPenalty: -2,
  });
  const edgeId = String(edgeLow.id);
  expect(edgeLow.provider).toBe('vllm');
  expect(edgeLow.reasoning_effort).toBe('high');
  expect(edgeLow.frequency_penalty).toBe(-2);

  const edgeHigh = await createModel(request, { frequencyPenalty: 2 });
  expect(edgeHigh.frequency_penalty).toBe(2);

  // —— POST 缺省新字段：provider 落默认 openai、effort/penalty 回显 null（不下发语义）——
  const plain = await createModel(request);
  expect(plain.provider).toBe('openai');
  expect(plain.reasoning_effort).toBeNull();
  expect(plain.frequency_penalty).toBeNull();

  // —— POST 空串 effort：清除语义（normEffort 空白 → null）——
  const blank = await createModel(request, { reasoningEffort: '' });
  expect(blank.reasoning_effort).toBeNull();

  // —— PATCH 三态：缺省=不变；空串=清除；非 null=替换；provider 空白串=保持旧值 ——
  const untouched = await request.patch(`/models/${edgeId}`, {
    data: { name: `e2e-renamed-${uniq()}` },
  });
  expect(untouched.status()).toBe(200);
  const untouchedBody = await untouched.json();
  expect(untouchedBody.provider).toBe('vllm');
  expect(untouchedBody.reasoning_effort).toBe('high');
  expect(untouchedBody.frequency_penalty).toBe(-2);

  const blankProvider = await request.patch(`/models/${edgeId}`, { data: { provider: '' } });
  expect(blankProvider.status()).toBe(200);
  expect((await blankProvider.json()).provider).toBe('vllm');

  const cleared = await request.patch(`/models/${edgeId}`, {
    data: { reasoningEffort: '', frequencyPenalty: 2 },
  });
  expect(cleared.status()).toBe(200);
  const clearedBody = await cleared.json();
  expect(clearedBody.reasoning_effort).toBeNull();
  expect(clearedBody.frequency_penalty).toBe(2);

  // —— PATCH 负例不落库：非法值 400 后 GET 确认旧值未动 ——
  await expectInvalidConfig(
    await request.patch(`/models/${edgeId}`, { data: { reasoningEffort: 'HIGH', frequencyPenalty: 3 } }),
    'reasoningEffort must match',
  );
  const afterReject = await request.get(`/models/${edgeId}`);
  expect(afterReject.status()).toBe(200);
  const afterRejectBody = await afterReject.json();
  expect(afterRejectBody.reasoning_effort).toBeNull();
  expect(afterRejectBody.frequency_penalty).toBe(2);
});
```

#### MOD5 系统模型视图：env 缺省下 provider='openai'/enable_thinking=false 回显与 reasoning_effort/frequency_penalty null 归一（零 LLM 依赖）

- **落点**：agent-framework/e2e/tests/api-models.spec.ts（api-models 项目 → CI e2e-core job，零 LLM 依赖）
- **blocker**：none
- **前置**：
  - e2e-core 环境照常：start-agent.sh 未注入 LLM_PROVIDER/LLM_REASONING_EFFORT/LLM_FREQUENCY_PENALTY（缺省 openai/空/null），也未导出 LLM_ENABLE_THINKING（application.yml:33 缺省 false 生效）；本用例依赖该缺省形状
  - 无新增录制件、无 mock 改动（对照 ModelController.systemModelView/managedView 的 blankToNull 回显与 application.yml ${LLM_*:} 绑定）
- **步骤**：
  1. GET /models/system → 断系统模型只读视图的采样参数缺省形状（含 enable_thinking=false）
  2. GET /models → 断 default_model 与列表中 system 项的 provider 回显（仅 ModelOption 既有字段）
  3. 创建一个托管模型后再次 GET /models → 断列表项出现次数与 source；GET /models/{id} 详情 → 断 read_only 与新采样键回显
- **断言**：
  - GET /models/system → 200 且 provider==='openai'（env 缺省值回显）
  - reasoning_effort===null 且 frequency_penalty===null——严格 null：锁死 blankToNull/空串绑定的归一契约（回归'回显空串/缺键'两种退化）
  - enable_thinking===false：application.yml:33 显式缺省 false（LLM_ENABLE_THINKING 在 e2e 脚本/CI 未导出），非 LLMConfig 注解的 @DefaultValue("true")
  - source==='system'、read_only===true、is_default===true、model_id 与注入的 LLM_MODEL_ID 同源
  - GET /models → default_model==='system'；列表 system 项 provider==='openai'、is_default=true、source==='system'（列表项按 ModelOption 既有字段断，不涉新采样键/read_only）
  - 新建托管模型在重新拉取的列表中 id 恰出现一次且 source==='managed'；read_only===false 断在 GET /models/{id} 详情视图；详情含 reasoning_effort/frequency_penalty 且为 null
- **draftCode**：

```typescript
test('MOD5 系统模型视图：env 缺省下 provider 默认值与采样参数 null 归一', async ({ request }) => {
  // e2e 的 start-agent.sh 只注入 LLM_BASE_URL/LLM_API_KEY/LLM_MODEL_ID；
  // LLM_PROVIDER/LLM_REASONING_EFFORT/LLM_FREQUENCY_PENALTY 未设置 → 走 application.yml 默认；
  // 注意 enable-thinking 缺省为 false（application.yml:33 ${LLM_ENABLE_THINKING:false}，
  // LLM_ENABLE_THINKING 在 e2e 脚本/CI 均未导出，覆盖 LLMConfig 注解里的 @DefaultValue("true")）
  const system = await request.get('/models/system');
  expect(system.status()).toBe(200);
  const sys = await system.json() as Record<string, unknown>;
  expect(sys.provider).toBe('openai');          // LLM_PROVIDER 缺省（yml ${LLM_PROVIDER:openai}）
  expect(sys.reasoning_effort).toBeNull();      // LLM_REASONING_EFFORT 空串 → blankToNull 归一为 null（非 ''）
  expect(sys.frequency_penalty).toBeNull();     // LLM_FREQUENCY_PENALTY 未配置 → Double null
  expect(sys.enable_thinking).toBe(false);      // yml 显式缺省 false（与 LLMConfig @DefaultValue("true") 不同，yml 优先）
  expect(sys.source).toBe('system');
  expect(sys.read_only).toBe(true);
  expect(sys.is_default).toBe(true);
  expect(sys.model_id).toBe('e2e-mock-model');  // 与 start-agent.sh 注入的 LLM_MODEL_ID 同源

  // 列表契约（docs/model-params-design.md:96：GET /models 列表项不扩展，维持 picker 轻量语义）
  // → 列表项只断 ModelOption 既有字段，不涉及 reasoning_effort/frequency_penalty/read_only
  const list = await (await request.get('/models')).json() as {
    default_model: string;
    models: Array<Record<string, unknown>>;
  };
  expect(list.default_model).toBe('system');
  const systemOption = list.models.find(m => m.id === 'system');
  expect(systemOption).toBeTruthy();
  expect(systemOption!.provider).toBe('openai');
  expect(systemOption!.is_default).toBe(true);
  expect(systemOption!.source).toBe('system');

  // 托管项：source/enabled 在列表项可见；read_only 仅存在于详情视图（managedView）
  const created = await createModel(request);
  const createdId = String(created.id);
  const relist = await (await request.get('/models')).json() as { models: Array<Record<string, unknown>> };
  const occurrences = relist.models.filter(m => m.id === createdId);
  expect(occurrences.length).toBe(1);           // 列表中恰好出现一次（不重复、不遗漏）
  expect(occurrences[0].source).toBe('managed');

  const detail = await request.get(`/models/${createdId}`);
  expect(detail.status()).toBe(200);
  const detailBody = await detail.json() as Record<string, unknown>;
  expect(detailBody.read_only).toBe(false);     // read_only 断在详情视图
  expect(detailBody.source).toBe('managed');
  expect(detailBody.reasoning_effort).toBeNull();   // 详情视图含新采样键（POST 缺省 → null）
  expect(detailBody.frequency_penalty).toBeNull();
});
```

#### MOD6 托管模型方言矩阵：vllm/glm/deepseek/openai 四分支下采样参数在 LLM 请求体中的实际落点（复用 plain 夹具）

- **落点**：agent-framework/e2e/tests/api-models.spec.ts（api-models 项目 → CI e2e-core job，对照 docs/model-params-design.md §7 方言矩阵）
- **blocker**：需扩 mock
- **前置**：
  - 【需扩 mock】llm-server.mjs /stats 的 calls[] 增记请求体采样键 sampling: { chat_template_kwargs, thinking, reasoning_effort, frequency_penalty }（插在 llm-server.mjs:149-158 的 stats.calls.push 处，取 reqBody 同名键 ?? null）
  - 复用 mock/fixtures/llm/plain.json 夹具回放（标记 [E2E:plain]），无需新录制件；registry.json 与 scripts/check-fixtures.mjs 不变
  - e2e-core 环境照常（:8100 + mock LLM:18081）；四方言各建唯一 modelId（如 e2e-dialect-vllm-<uniq>，≤128 字符），避免共用默认 'e2e-managed-model' 导致逐轮 find 串扰
- **步骤**：
  1. 对 vllm/glm/deepseek/openai 四个 provider 各 POST /models 建托管模型：唯一 modelId、enableThinking=false、reasoningEffort='high'、frequencyPenalty=0.5
  2. 每个模型 PATCH /threads/{sid} 绑定到独立会话后发 [E2E:plain] 一轮 chat，等终态 done
  3. pollUntil mock GET /stats 出现 scenario==='plain' 且 model===本轮唯一 modelId 的调用
  4. 逐方言断言该调用 sampling 四键的精确形状
- **断言**：
  - provider=vllm：sampling.chat_template_kwargs 深等于 {enable_thinking:false, reasoning_effort:'high'}（Map 合并无覆盖丢失）；顶层 reasoning_effort===null、thinking===null（未落错 openai 分支）；frequency_penalty===0.5
  - provider=glm：sampling.thinking 深等于 {type:'disabled'}；顶层 reasoning_effort==='high'；chat_template_kwargs===null
  - provider=deepseek：chat_template_kwargs/thinking/reasoning_effort 三者全 null（严格端点不收 400 的根源）；frequency_penalty===0.5
  - provider=openai：顶层 reasoning_effort==='high'；chat_template_kwargs===null、thinking===null（D8：enable_thinking=false 不产生附加字段）
  - 每方言 chat 终态 done；stats 中该调用 model===本轮唯一 modelId 字符串（请求体 model 字段）且 scenario==='plain'（过滤标题/记忆 background-synth 调用）
  - 断言用 toEqual 全量匹配 sampling 对象：方言分支多下发的键与少下发的键都判红
- **draftCode**：

```typescript
// 追加进 agent-framework/e2e/tests/api-models.spec.ts
// 【需扩 mock】llm-server.mjs stats.calls.push（现 llm-server.mjs:149-158）增记请求体采样键：
//   sampling: {
//     chat_template_kwargs: reqBody.chat_template_kwargs ?? null,
//     thinking: reqBody.thinking ?? null,
//     reasoning_effort: reqBody.reasoning_effort ?? null,
//     frequency_penalty: reqBody.frequency_penalty ?? null,
//   },
// 复用 mock/fixtures/llm/plain.json 夹具，registry.json 与 check-fixtures.mjs 无需改动（无新录制件）。

test('MOD6 托管模型方言矩阵：采样参数在 LLM 请求体中的实际落点', async ({ request }) => {
  // 四方言统一配置：enableThinking=false + effort + penalty，差异只来自 applyDialect 分支。
  // 每方言用唯一 modelId：stats.calls[].model 记录的是请求体 model 字段 = 配置 modelId
  // （ChatModelFactory.java:39 .modelName(llm.modelId())），非托管配置 UUID——
  // 唯一 modelId 保证逐轮 find 精确命中本轮调用，不与前一方言的调用串扰
  const dialectExpect: Record<string, Record<string, unknown>> = {
    // vllm：合并方言——effort+开关同入一个 chat_template_kwargs，顶层无 effort/thinking
    vllm: { chat_template_kwargs: { enable_thinking: false, reasoning_effort: 'high' }, thinking: null, reasoning_effort: null, frequency_penalty: 0.5 },
    // glm：thinking.type 嵌套对象 + 顶层一等 effort
    glm: { chat_template_kwargs: null, thinking: { type: 'disabled' }, reasoning_effort: 'high', frequency_penalty: 0.5 },
    // deepseek：官方无对应参数——effort/开关均不下发，仅顶层标准项 penalty
    deepseek: { chat_template_kwargs: null, thinking: null, reasoning_effort: null, frequency_penalty: 0.5 },
    // openai：顶层一等 effort，enable_thinking=false 不产生任何附加字段（D8）
    openai: { chat_template_kwargs: null, thinking: null, reasoning_effort: 'high', frequency_penalty: 0.5 },
  };

  for (const [provider, expected] of Object.entries(dialectExpect)) {
    const modelId = `e2e-dialect-${provider}-${uniq()}`;
    const model = await createModel(request, {
      name: `e2e-dialect-${provider}-${uniq()}`,
      modelId,
      provider,
      enableThinking: false,
      reasoningEffort: 'high',
      frequencyPenalty: 0.5,
    });
    const id = String(model.id);
    expect(model.provider).toBe(provider);
    expect(model.model_id).toBe(modelId);

    const sid = `e2e-dialect-${provider}-${uniq()}`;
    sessionIds.push(sid);
    const patched = await patchThread(sid, { model: id });
    expect(patched.status).toBe(200);

    const stream = chat({ message: `[E2E:plain]验证 ${provider} 方言`, userId: USER_ID, sessionId: sid });
    await waitTerminal(stream);
    expect(stream.terminal?.type).toBe('done');

    // 只认主对话调用（scenario='plain'，排除标题/记忆 background-synth），
    // 且按本轮唯一 modelId 过滤（请求体 model 字段，对齐 MOD3 既有匹配方式）
    const call = await pollUntil(
      async () => (await llmStats()).calls.find(c => c.scenario === 'plain' && c.model === modelId),
      c => c !== undefined,
    );
    // 逐方言精确断言请求体落点（toEqual 全量匹配：多下的键与少下的键都算错）
    expect(call.sampling, `provider=${provider} 请求体采样落点`).toEqual(expected);
  }
});
```

#### MOD7 env 路径大写 provider 归一：LLM_PROVIDER=VLLM 侧实例落 vllm 方言分支（chat_template_kwargs）而非 openai 兜底

- **落点**：agent-framework/e2e/tests/api-models.spec.ts（api-models 项目 → CI e2e-core job；spawnSync 编排先例 api-multi-kill.spec.ts:48）
- **blocker**：需扩 mock
- **前置**：
  - 【需扩 mock】同 MOD6：llm-server.mjs /stats calls[] 增记 sampling 请求体键（复用 plain 夹具，无新录制件）
  - scripts/start-agent.sh 无需改动：spawnSync 经 env 透传 LLM_PROVIDER=VLLM / LLM_REASONING_EFFORT=high / LLM_ENABLE_THINKING=false / LLM_FREQUENCY_PENALTY=0.5，nohup java 继承父环境，application.yml ${LLM_*:} 完成绑定
  - 端口 8110 空闲（env-up 只占 8100/8101/8102 与 mock 端口）；jar 已由 env-up 前置构建
  - 启动成功判据为脚本 exit 0（start-agent.sh:48 wait-ready 失败即 exit 1），非 pid 文件存在性（:47 先于 wait-ready 写 pid）；用例 afterAll 探活后 SIGKILL 清场
- **步骤**：
  1. spawnSync('bash', [start-agent.sh, 'model-env', '8110']) 以 env 注入 LLM_PROVIDER='VLLM'（大写）、LLM_REASONING_EFFORT='high'、LLM_ENABLE_THINKING='false'、LLM_FREQUENCY_PENALTY='0.5'，以脚本 exit 0（内含 wait-ready）判就绪
  2. fetch 侧实例 GET /models/system 断视图回显
  3. 经 base=SIDE_BASE 发 [E2E:plain] 一轮 chat，等终态 done（beforeEach 的 llmReset 保证 stats 干净）
  4. pollUntil mock /stats 出现 scenario==='plain' 且 model==='e2e-mock-model' 的调用，断言 sampling 落点
  5. afterAll 探活后 SIGKILL 清场侧实例并删 pid 文件
- **断言**：
  - startDialectInstance 以脚本 exit 0 为成功判据（r.error || r.status!==0 即抛错并携带 stdout/stderr 尾部）——不依赖 pid 文件存在性（pid 文件在 wait-ready 前写入，启动失败时仍残留）
  - 侧实例 GET /models/system → provider==='VLLM'（env 原样回显，视图层不归一）、reasoning_effort==='high'、frequency_penalty===0.5、enable_thinking===false
  - chat 终态 done；stats 中 plain 调用的 sampling 深等于 { chat_template_kwargs:{enable_thinking:false, reasoning_effort:'high'}, thinking:null, reasoning_effort:null, frequency_penalty:0.5 }
  - 关键反例断言：顶层 reasoning_effort===null——若 env 大写未归一落 openai 兜底分支，此处会是 'high' 且无 kwargs（该缺陷在严格端点表现为 400，正是缺口描述的不可见失败）
  - 侧实例 chat 与主实例/MOD6 互不串扰：断言基于 beforeEach 的 llmReset 后 stats，按 model==='e2e-mock-model'（侧实例 system 模型，与主实例同 modelId）+ sampling 键形状锁定方言来源
- **draftCode**：

```typescript
// 追加进 agent-framework/e2e/tests/api-models.spec.ts；文件头需补导入：
//   import { spawnSync } from 'node:child_process';
//   import fs from 'node:fs';
//   import path from 'node:path';
//   import { fileURLToPath } from 'node:url';
// 【需扩 mock】依赖 MOD6 的 llm-server.mjs sampling 扩展（同一段 stats.calls.push 增记）。

const SIDE_PORT = '8110';
const SIDE_BASE = `http://127.0.0.1:${SIDE_PORT}`;
const RUNTIME_DIR = process.env.E2E_RUNTIME_DIR ?? '.runtime';
const SIDE_PID_FILE = path.join(RUNTIME_DIR, 'agent-model-env.pid');
const START_SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), '../scripts/start-agent.sh');

/** 起 env 方言侧实例：LLM_PROVIDER 大写注入（进程编排先例 api-multi-kill spawnSync start-agent.sh） */
function startDialectInstance(): void {
  const r = spawnSync('bash', [START_SCRIPT, 'model-env', SIDE_PORT], {
    encoding: 'utf8',
    timeout: 120_000,
    // nohup java 继承本 env → application.yml ${LLM_*} 绑定；大写 VLLM 是被测点：
    // 归一发生在 ChatModelFactory.applyDialect（trim+toLowerCase），视图层原样回显
    env: {
      ...process.env,
      LLM_PROVIDER: 'VLLM',
      LLM_REASONING_EFFORT: 'high',
      LLM_ENABLE_THINKING: 'false',
      LLM_FREQUENCY_PENALTY: '0.5',
    },
  });
  // 以脚本 exit 0 为成功判据（wait-ready 在脚本内）：pid 文件在 wait-ready 之前就写入
  // （start-agent.sh:47），JVM 起不来时 pid 文件仍存在，不能作为启动成功依据——
  // 对齐 api-multi-kill restoreReplicaA：失败时把 stdout/stderr 尾部一并带出
  if (r.error || r.status !== 0) {
    throw new Error(
      `侧实例启动失败 exit=${r.status}${r.error ? `，error=${r.error.message}` : ''}\n` +
      `stdout: ${(r.stdout ?? '').slice(-400)}\nstderr: ${(r.stderr ?? '').slice(-800)}`,
    );
  }
}

/** 清场：SIGKILL 侧实例（若存活）并移除 pid 文件（与 start-agent.sh 端口清场同语义） */
function stopDialectInstance(): void {
  try {
    const pid = Number(fs.readFileSync(SIDE_PID_FILE, 'utf8').trim());
    if (Number.isInteger(pid) && pid > 0) {
      process.kill(pid, 0);            // 探活：死 pid（启动失败的残留）直接跳过
      process.kill(pid, 'SIGKILL');
    }
  } catch { /* pid 文件缺失或进程已死即已清场 */ }
  fs.rmSync(SIDE_PID_FILE, { force: true });
}

test.afterAll(() => stopDialectInstance());

test('MOD7 env 路径大写 provider 归一：VLLM 注入落 vllm 方言分支而非 openai 兜底', async () => {
  startDialectInstance();

  // ① 视图层：env 值原样回显（大写保留）——归一只发生在方言层，视图与方言各自可断言
  const sys = await (await fetch(`${SIDE_BASE}/models/system`)).json() as Record<string, unknown>;
  expect(sys.provider).toBe('VLLM');
  expect(sys.reasoning_effort).toBe('high');
  expect(sys.frequency_penalty).toBe(0.5);
  expect(sys.enable_thinking).toBe(false);

  // ② 行为层：beforeEach 的 llmReset 已清空 stats；system 模型 modelId=e2e-mock-model，
  //    reset 后本轮 plain 调用只能来自侧实例（MOD6 各托管模型 modelId 唯一，不受影响）
  const sid = `e2e-env-dialect-${uniq()}`;
  sessionIds.push(sid);
  const stream = chat({ message: '[E2E:plain]验证 env 大写 provider 归一', userId: USER_ID, sessionId: sid, base: SIDE_BASE });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');

  const call = await pollUntil(
    async () => (await llmStats()).calls.find(c => c.scenario === 'plain' && c.model === 'e2e-mock-model'),
    c => c !== undefined,
  );
  // 大写 VLLM 命中 vllm 合并方言：kwargs 含 effort+开关；顶层 effort/thinking 均不下发。
  // 若归一失效会落 openai 兜底分支：顶层 reasoning_effort='high' 且无 kwargs——对严格 vLLM 端点即 400，此前门禁不可见
  expect(call.sampling).toEqual({
    chat_template_kwargs: { enable_thinking: false, reasoning_effort: 'high' },
    thinking: null,
    reasoning_effort: null,
    frequency_penalty: 0.5,
  });
});
```

**复核意见（评审留痕，均已修订吸收）**：
- 【MOD5·必红】原稿 expect(sys.enable_thinking).toBe(true) 与实际缺省相反：application.yml:33 为 `enable-thinking: ${LLM_ENABLE_THINKING:false}`，start-agent.sh:36-46 与 env-up.sh/CI workflow 均未导出 LLM_ENABLE_THINKING（grep 零命中）→ 实际回显 false。应断言 toBe(false)。（已改）
- 【MOD5·必红】原稿 expect(managed!.read_only).toBe(false) 断言的字段在列表项上不存在：GET /models 列表项是 ModelOption（ModelCatalog.java:52-60，无 read_only），设计文档明示 'GET /models 列表项不扩展'（docs/model-params-design.md:96）→ read_only 只在详情视图（ModelController.java:284/307）存在，应改为对详情断言。（已改）
- 【MOD6·必红·不可执行】stats 过滤键用错：`c.model === String(modelIdOf(model))` 拿的是托管模型配置 UUID，而 stats.model 记录的是请求体 model 字段 = 配置的 modelId 字符串（ChatModelFactory.java:39 `.modelName(llm.modelId())`；既有用例即按 modelId 匹配——api-models.spec.ts:107/141）→ find 永不命中，pollUntil 30s 超时必红。应改为按 modelId 匹配并删除误导性辅助函数。（已改）
- 【MOD6·必红·轮次串扰】四方言模型共用默认 modelId 'e2e-managed-model' 且 `find()` 只取第一条匹配：第 2/3/4 轮会命中第 1 轮的调用记录，toEqual 必红（或形状巧合时假绿）。需按轮次切片或每方言唯一 modelId。（已改：每方言唯一 modelId）
- 【MOD7·健壮性】启动成功判据弱于所引先例：start-agent.sh:47 在 wait-ready(:48) 之前就写 pid 文件，JVM 启动失败时脚本 exit 1 但留下死 pid 文件。应对齐 api-multi-kill.spec.ts:48-56：校验 r.status===0，并把 r.stdout/r.stderr 尾部带入报错。（已改）
- 【MOD4·断言与代码不一致】assertions 声称 '±2.0 边界放行' 但原代码只测了 -2。补 frequencyPenalty: 2 的放行断言。（已补 edgeHigh）
- 【MOD5·弱断言·可选修】原稿 `expect(listed.find(...)).toBeUndefined()` 断言创建前快照恒 undefined，无回归价值；改为断言 relist 中该 id 仅出现一次。（已改）

### 4.3 缺口三：沙箱档用户技能容器→KV 回写与每 turn 物化（sandbox，63e3ccc/d0c3eaa）—— 4 条：X10/X11/X12/X13

**缺口描述**：WorkspaceSyncService 回写（skills/**、tombstone/admin-override 仲裁）与 WorkspaceReader 物化（管理面写入下一 turn 生效）均无 CI e2e：api-sandbox.spec.ts grep skill 零命中，mock OpenSandbox 不校验 /workspace/skills 写入；回归表现为用户技能静默丢失/复活且门禁全绿，且 X3（:52-54）揭示的"回放 tool_call 不触发沙箱写入"框架缺陷是该链路夹具化的同族阻塞风险。

**复核证据要点**：
- 覆盖面检索（上游实跑）：`grep -n -i skill agent-framework/e2e/tests/api-sandbox.spec.ts` → exit 1；全 tests/ 检索 request.put|request.delete 打 /skills/users → exit 1（唯一触达：api-core.spec.ts:610-633 SK1 仅 GET 契约探针，:607-608 注释显式把完整管理面场景让位给手工集群脚本 e2e/user-skill-admin-e2e.sh；ui.spec.ts:208-280 全部 /skills/users 流量经 page.route 桩化）；materialize/syncBack/tombstone 仅 ui.spec.ts 桩数据（:218-219,:254,:395-404）；plugin-smoke.sh grep -i skill → exit 1。
- mock 能力：sandbox-server.mjs 全文对 /workspace/skills 无感知/校验逻辑，仅 :212（upload）/:218（download）通用记 fileOps 流水，无断言面——『mock 不校验 /workspace/skills 写入』成立。
- X3 缺陷：api-sandbox.spec.ts:52-54 原文即『回放的 write_file tool_call 不触发沙箱文件写入』+ test.fixme(:54)；docs/e2e-ci-plan.md:405 的 X3 规划行恰好 planned 覆盖『WorkspaceSyncService 回写 agent_fs → 重注入』、:671 确认 X3 是未转正用例——被规划覆盖该链路的用例正是沦为 fixme 的那个。细化：该缺陷是工具特定的（X2/X7 证明 execute 回放真实执行），只阻塞『容器内 skill_manage 回写 KV』方向夹具化（X12/X13 类），不阻塞『管理面→容器』物化方向（X10/X11 走 [E2E:plain]）。
- 链路真实存在（源码核实）：UserSkillController.java:184-224（PUT，:206-214 分档文案含『管理面写入栅栏』）、:232-272（DELETE，:254-264 tombstone）；WorkspaceReader.java:715-783（materializeUserSkills，:764-774 写 /workspace/skills/{name}/{rel}）；SandboxUserKeyMiddleware.java:63-65（acquire 后投影）；OpenSandbox.java:109-115（stop() 内同步 syncBack）；WorkspaceSyncService.java:180-183（tombstone 跳过）/:186-189（admin-override 跳过）/:193（无 SKILL.md 不算技能）。
- 未能复现项：X10 precondition 的『javap 反汇编 sandbox SDK jar 实证 FilesystemAdapter.write→/files/upload』本机无法复现（SDK jar 不在本机）；独立佐证：真实服务录制件 mock/fixtures/sandbox/interactions.json 含 14 处 files/upload，sandbox-server.mjs:20 头注与 :196-213 实现同路由。
- 复核修订已吸收：X11/X13 的『同代容器回写仲裁』断言原稿用三个新 sessionId 导致每 turn 全新容器、断言恒真（结构性不可达）——改为三 turn 复用同一 sessionId（IsolationScope.USER（AgentScopeConfig.java:79）+ slot sandbox/user/{agentId}/{userId}，同会话跨 call 经 resume→connector().connect() 重连同一容器（OpenSandboxClient.java:147-151），X6（api-sandbox.spec.ts:91-109）实证 mock 不销毁时 connect 成功不新增 create）；X13 断言强度 honest 划分：tombstone 防复活为强断言（WorkspaceSyncService.java:180-183 是唯一闸门），admin-override 为 KV 终态契约（栅栏分支的载荷性由集群 E9 user-skill-admin-e2e.sh:529-553 钉住，其 :465 自带同款代际边界声明）。
- 其余核实：caseId X10-X13 与既有 X1-X9 无冲突；placement 正确（api-sandbox project → run.sh:21 sandbox 组 → CI e2e-sandbox job，该 job 不装浏览器、四用例纯 request 兼容）；X10/X11 复用 plain 无需新录制件，X12/X13 的 blocker『需补录制件』属实（registry/MARKER_MAP 无 skill-manage-sb，标记正则 llm-server.mjs:82 可接受该标记，check-fixtures.mjs:27 calls>=2 校验属实）；断言字段/错误码全部与源码吻合（action/version=UserSkillService.java:126、source="user" :389、adminOverride/hasUserOverride :101-102/:121-123、deletedFiles 不含标记=WorkspaceReader.java:500、tombstone.clearHint=UserSkillController.java:61-63、100KB 阈值=UserSkillService.java:53/:420、a..b 含..被拒 :160）；lib 依赖属实（pollUntil=matchers.ts:25、chat userId=client.ts:8、sandboxStats=api-sandbox.spec.ts:24-27）；X11 deletedFiles=1 成立（PUT 只写 SKILL.md，listSkillFiles :381 排除 . 元数据段）；X12 固定技能名符合『夹具即契约』且 uid 每次新建+env-up.sh:34-36 每 run 重置 DB，retry 安全；X12 对 X3 同族缺陷的前置验证要求和 execute 白名单退路（mkdir/printf 在 sandbox-server.mjs:34 白名单）充分。

#### X10（沙箱技能）沙箱档管理面 PUT 用户技能 → 响应契约 + 下一个 turn 物化进容器（mock /stats fileOps 证据）

- **落点**：agent-framework/e2e/tests/api-sandbox.spec.ts（X 组追加，api-sandbox project → e2e-sandbox job）
- **blocker**：none
- **前置**：
  - e2e-sandbox 环境（scripts/run.sh sandbox：SANDBOX_ENABLED=true + mock OpenSandbox :8090 + MySQL agent_fs + mock LLM/MCP），复用现有 [E2E:plain] 夹具，无需新录制件
  - mock 已具备物化写入观测：sandbox-server.mjs:196-213 对 /files/upload 记 stats.fileOps{op,path,size}，且 SDK FilesystemAdapter.write 线上路由即 /files/upload（javap 反汇编 com.alibaba.opensandbox:sandbox:1.0.18 实证），materializeUserSkills（WorkspaceReader.java:774）写 /workspace/skills/{name}/SKILL.md 必然留下流水
  - 落地首跑校准：fileOps.path 为 mock 映射后沙箱根路径（断言按 /skills/{name}/SKILL.md 后缀匹配）；若与真实 execd 口径有出入，先手工跑 /root/agent-manager/e2e/user-skill-admin-e2e.sh E8 校准
- **步骤**：
  1. GET /skills/users/{uid}（全新用户）确认真空基线：200、skills=[]、tombstones=[]
  2. PUT /skills/users/{uid}/{skill}（body {content: 含唯一 marker 的 SKILL.md 全文}）
  3. GET /skills/users/{uid}/{skill} 与 GET /skills/users/{uid} 验 KV 写入面（source/userOverride/adminOverride）
  4. 以同 uid、新 sessionId 发 POST /threads/chat [E2E:plain]，waitTerminal 收敛 done（该 turn acquire 后 SandboxUserKeyMiddleware.java:65 触发 materializeUserSkills 投影 L4 进容器）
  5. GET mock:8090/stats，比对 turn 前后 fileOps 中 /{skill}/SKILL.md 条数
  6. 无 LLM 负例补齐（MOD 组风格）：PUT 空内容、PUT 非法技能名 a..b、PUT >100KB 内容
- **断言**：
  - PUT 200 且 action=created、version>0、message 含「管理面写入栅栏」（沙箱档分档文案，UserSkillController.java:210-213）
  - GET 明细 source=user、hasUserOverride=true、content 含写入 marker；列表 skills[].name 含该技能且 adminOverride=true
  - turn done 后 mock /stats fileOps 中 path 含 /skills/{skill}/SKILL.md 的 upload 条目数 > turn 前（物化确证），且其中至少一条 path 以后缀 /skills/{skill}/SKILL.md 结尾
  - 负例：空内容→400 empty_content；非法名→400 invalid_name；>100KB→413 content_too_large（与 api-core SK1 互补，SK1 只覆盖 GET 面）
- **draftCode**：

```typescript
// 追加到 e2e/tests/api-sandbox.spec.ts（X 组）；文件头需补 pollUntil 导入：
// import { waitTerminal, textOf, toolNames, toolResults, pollUntil } from '../lib/matchers.js';
// 复用本 spec 既有 helper：U() / uniq() / sandboxStats() / chat / sessionIdFor / waitTerminal

test('X10 管理面 PUT 用户技能 → 下一 turn 物化进容器（mock fileOps 证据）', async ({ request }) => {
  const uid = U();
  const skill = `e2e-skill-${uniq()}`; // 全程唯一，避免与 L2/.skills-cache 路径串扰
  const marker = `MAT-${uniq()}`;
  const content = `---\nname: ${skill}\ndescription: e2e materialize probe\nversion: 1.0.0\n---\n\n# ${skill}\n\n${marker}\n`;

  // 基线：新用户无 L4、无 tombstone
  const empty = await request.get(`/skills/users/${uid}`);
  expect(empty.status()).toBe(200);
  const emptyBody = await empty.json();
  expect(emptyBody.skills).toEqual([]);
  expect(emptyBody.tombstones).toEqual([]);

  // 管理面 PUT（KV 权威写 + admin-override 栅栏）
  const put = await request.put(`/skills/users/${uid}/${skill}`, { data: { content } });
  expect(put.status()).toBe(200);
  const putBody = await put.json();
  expect(putBody.action).toBe('created');
  expect(Number(putBody.version)).toBeGreaterThan(0);
  expect(String(putBody.message)).toContain('管理面写入栅栏'); // 沙箱档提示契约

  // KV 视图：source=user / adminOverride=true
  const detail = await request.get(`/skills/users/${uid}/${skill}`);
  expect(detail.status()).toBe(200);
  const detailBody = await detail.json();
  expect(detailBody.source).toBe('user');
  expect(detailBody.hasUserOverride).toBe(true);
  expect(String(detailBody.content)).toContain(marker);
  const list = await (await request.get(`/skills/users/${uid}`)).json();
  const entry = (list.skills as Array<Record<string, unknown>>).find(s => s.name === skill);
  expect(entry).toBeTruthy();
  expect(entry!.adminOverride).toBe(true);

  // 下一 turn 物化：acquire 后 SandboxUserKeyMiddleware 把 L4 投影进容器 /workspace/skills
  const skillOps = () => sandboxStats().then(s => s.fileOps.filter(f => String(f.path ?? '').includes(`/${skill}/SKILL.md`)));
  const before = await skillOps();
  const sid = sessionIdFor(`x10-${uniq()}`);
  const stream = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  const ops = await skillOps();
  expect(ops.length, 'L4 技能未物化进容器（/workspace/skills 无写入流水）').toBeGreaterThan(before.length);
  expect(ops.some(f => String(f.path).endsWith(`/skills/${skill}/SKILL.md`))).toBe(true);

  // 负例（无 LLM，MOD 组风格）
  const emptyPut = await request.put(`/skills/users/${uid}/x10-neg`, { data: { content: '  ' } });
  expect(emptyPut.status()).toBe(400);
  expect((await emptyPut.json()).error).toBe('empty_content');
  const badName = await request.put(`/skills/users/${uid}/a..b`, { data: { content } });
  expect(badName.status()).toBe(400);
  expect((await badName.json()).error).toBe('invalid_name');
  const big = await request.put(`/skills/users/${uid}/x10-neg`, { data: { content: 'x'.repeat(100 * 1024 + 1) } });
  expect(big.status()).toBe(413);
  expect((await big.json()).error).toBe('content_too_large');
});
```

#### X11 沙箱档管理面 DELETE → tombstone 防复活（同会话同代容器 syncBack 仲裁，强断言）+ 物化不再投影

- **落点**：agent-framework/e2e/tests/api-sandbox.spec.ts（X 组追加，api-sandbox project → e2e-sandbox job）
- **blocker**：none
- **前置**：
  - 同 X10 环境；无需新录制件（两个 turn 均复用 [E2E:plain]）
  - 同代容器机制（修订采纳复核意见）：IsolationScope.USER（AgentScopeConfig.java:79）+ slot sandbox/user/{agentId}/{userId}（SandboxAwareMysqlAgentStateStore.java:11）→ 同 sessionId 第二个 call 经 resume→connector().connect() 重连同一容器（OpenSandboxClient.java:147-151）；X6（api-sandbox.spec.ts:91-109）实证 mock 不销毁时 connect 成功不新增 create。因此 turn2 必须复用 turn1 的 sessionId，syncBack（OpenSandbox.java:111）才会真实面对 turn1 物化进容器的副本——否则新容器无副本、断言恒真
  - 复核后修正的认知（诚实标注）：resume 每次新建 OpenSandbox 包装（OpenSandboxClient.java:153）→ materialize 每 call 重跑；tombstone 技能在 listUserSkills（无 SKILL.md）与 isUserSkillDeleted（WorkspaceReader.java:740-743）双重跳过 → 容器副本存活；syncBack 侧 WorkspaceSyncService.java:180-183 是防复活唯一闸门 → 本用例 tombstone 断言为载荷断言（坏实现会复活、用例会红）
- **步骤**：
  1. PUT /skills/users/{uid}/{skill} 写入基线技能
  2. turn1：同 uid 新会话 sidA 发 [E2E:plain]，确认物化已发生（mock /stats fileOps 出现 /skills/{skill}/SKILL.md）
  3. DELETE /skills/users/{uid}/{skill}（管理面删除，写 .deleted 标记）
  4. GET /skills/users/{uid} 与 GET 明细验证 KV 即时状态
  5. turn2：复用 sidA（同会话，关键）再发 [E2E:plain]——resume 同代容器：materialize 跳过已删技能、stop() syncBack 面对容器内存活副本
  6. GET /skills/users/{uid} 终态复查 + mock /stats fileOps 复查
- **断言**：
  - DELETE 200 且 deletedFiles=1、tombstone.name={skill}、tombstone.clearHint 含 sync-from-package、message 含「不会被回写落库」（UserSkillController.java:254-264）
  - 删除后列表 skills 不含该技能、tombstones 含该技能；明细 GET 404 not_found（无包内基线）
  - turn2 done 后：fileOps 中 /skills/{skill}/SKILL.md 条目数不增加（tombstone 技能不再物化）；列表 skills 仍不含该技能、tombstones 仍含——同代容器副本存在的前提下 KV 不被回写复活（WorkspaceSyncService.java:180-183 载荷断言：若去掉 tombstone 闸门，syncBack 会把容器副本原样写回 KV，本断言变红）
  - turn1/turn2 终态均 done
- **draftCode**：

```typescript
// 追加到 e2e/tests/api-sandbox.spec.ts（X 组）；复用既有 helper，同 X10 导入
test('X11 管理面 DELETE → tombstone 防复活（同会话同代容器 syncBack 仲裁）+ 物化不再投影', async ({ request }) => {
  const uid = U();
  const skill = `e2e-skill-${uniq()}`;
  const content = `---\nname: ${skill}\ndescription: e2e tombstone probe\nversion: 1.0.0\n---\n\n# ${skill}\n`;

  const put = await request.put(`/skills/users/${uid}/${skill}`, { data: { content } });
  expect(put.status()).toBe(200);

  // turn1：物化基线（容器内出现该技能副本）
  const skillOps = () => sandboxStats().then(s => s.fileOps.filter(f => String(f.path ?? '').includes(`/${skill}/SKILL.md`)));
  const sidA = sessionIdFor(`x11-${uniq()}`); // turn2 复用同一 sid：IsolationScope.USER 下同会话跨 call resume 同代容器
  const w = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidA });
  await waitTerminal(w);
  expect(w.terminal?.type).toBe('done');
  expect((await skillOps()).length).toBeGreaterThanOrEqual(1);

  // 管理面 DELETE：写 tombstone
  const del = await request.delete(`/skills/users/${uid}/${skill}`);
  expect(del.status()).toBe(200);
  const delBody = await del.json();
  expect(delBody.deletedFiles).toBe(1); // PUT 只写 SKILL.md，.deleted 标记不计入（listSkillFiles 排除 . 元数据段）
  expect(delBody.tombstone.name).toBe(skill);
  expect(String(delBody.tombstone.clearHint)).toContain('sync-from-package');
  expect(String(delBody.message)).toContain('不会被回写落库');

  // KV 面立即可见：技能消失、tombstones 列出、明细 404（无包内基线）
  const names = (j: { skills: Array<Record<string, unknown>>; tombstones: Array<Record<string, unknown>> }) => ({
    skills: j.skills.map(s => String(s.name)),
    tombs: j.tombstones.map(t => String(t.name)),
  });
  const after = names(await (await request.get(`/skills/users/${uid}`)).json());
  expect(after.skills).not.toContain(skill);
  expect(after.tombs).toContain(skill);
  const gone = await request.get(`/skills/users/${uid}/${skill}`);
  expect(gone.status()).toBe(404);
  expect((await gone.json()).error).toBe('not_found');

  // turn2：复用 sidA（同会话同代容器）——materialize 跳过已删技能，stop() syncBack 面对容器内存活副本：
  // tombstone 闸门（WorkspaceSyncService syncOneSkill）是 KV 不复活的唯一防线，坏实现会在此变红
  const countBefore = (await skillOps()).length;
  const r = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sidA });
  await waitTerminal(r);
  expect(r.terminal?.type).toBe('done');
  expect((await skillOps()).length, 'tombstone 技能被再次物化进容器').toBe(countBefore);
  const final = names(await (await request.get(`/skills/users/${uid}`)).json());
  expect(final.skills).not.toContain(skill);
  expect(final.tombs).toContain(skill);
});
```

#### X12 沙箱档容器内 skill_manage 建技能 → turn 结束 WorkspaceSyncService 回写 KV（GET /skills/users 可见）

- **落点**：agent-framework/e2e/tests/api-sandbox.spec.ts（X 组追加，api-sandbox project → e2e-sandbox job）
- **blocker**：需补录制件
- **前置**：
  - 需补录制件：mock/fixtures/llm/skill-manage-sb.json + registry.json llm 节登记 {"skill-manage-sb": {"marker": "[E2E:skill:manage:sb]", "calls": 2}} + llm-server.mjs MARKER_MAP 增 'skill:manage:sb': 'skill-manage-sb'（标记正则 llm-server.mjs:82 接受；check-fixtures.mjs:27 校验 calls 达标）
  - 录制口径（scripts/record-llm.mjs，POST /begin {"scenario":"skill-manage-sb"}）：仿 sandbox-write.json 结构——call[0] 回放 skill_manage(action=create) tool_call（技能名 e2e-sbx-skill、SKILL.md 含固定 marker），call[1] 回放文本收尾；prompt 仿 user-skill-admin-e2e.sh:402-414 的 E8 提示词
  - 落地前必须先确认 X3 同族缺陷不命中：api-sandbox.spec.ts:52-54 记录「回放 write_file tool_call 不触发沙箱写入」——录制后先本地实跑本用例，若 skill_manage 写入链同样被吞（无 KV 回写），维持 test.fixme 并先修框架缺陷，或改录 execute 白名单命令写 skills/ 的变体夹具（mkdir/printf 均在 sandbox-server.mjs:34 白名单内）
  - 校准项：syncBack 依赖 mock listDirectory 条目口径（sandbox-server.mjs:225-228 返回 is_dir 布尔、无 type 字段；WorkspaceSyncService.isDirectoryEntry 走兜底递归可兼容）——先手工跑 user-skill-admin-e2e.sh E8 校准后再夹具化
- **步骤**：
  1. 以全新 uid、新 sessionId 发 POST /threads/chat [E2E:skill:manage:sb]，waitTerminal 收敛
  2. 轮询 GET /skills/users/{uid}（pollUntil，30s 上限，容忍 stop() 回写与 hydrate 竞态）直至 skills 出现 e2e-sbx-skill
  3. GET /skills/users/{uid} 取明细断言
- **断言**：
  - 流终态 done 且 TOOL_CALL_START 帧含 skill_manage（夹具 tool_call 轮确实回放并被执行）
  - KV 回写可见：skills[].name 含 e2e-sbx-skill，其 files 数组含 SKILL.md（WorkspaceSyncService.java:193 无 SKILL.md 不算技能）
  - adminOverride=false、tombstones=[]（会话内写入不置管理面栅栏，与 PUT/DELETE 仲裁面区分）
- **draftCode**：

```typescript
// 需补录制件：mock/fixtures/llm/skill-manage-sb.json（标记 [E2E:skill:manage:sb]，registry calls>=2，
// MARKER_MAP 增 'skill:manage:sb' 路由）。录制前先验证 X3 同族缺陷（api-sandbox.spec.ts:52-54）
// 不命中 skill_manage 写入链；若命中则本用例保持 test.fixme 并先修框架。
test('X12 容器内 skill_manage 建技能 → turn 结束回写 KV（GET /skills/users 可见）', async ({ request }) => {
  const uid = U();
  const skill = 'e2e-sbx-skill'; // 与录制件中 skill_manage 参数一致
  const sid = sessionIdFor(`x12-${uniq()}`);
  const stream = chat({ message: `[E2E:skill:manage:sb]`, userId: uid, sessionId: sid });
  await waitTerminal(stream);
  expect(stream.terminal?.type).toBe('done');
  expect(toolNames(stream.frames)).toContain('skill_manage');

  // 回写在 stop() 同步执行（OpenSandbox.java:111），留轮询缓冲防 hydrate 竞态
  const listSkills = async () => {
    const r = await request.get(`/skills/users/${uid}`);
    expect(r.status()).toBe(200);
    return (await r.json()).skills as Array<Record<string, unknown>>;
  };
  await pollUntil(listSkills, skills => skills.some(s => s.name === skill), 30_000);
  const skills = await listSkills();
  const entry = skills.find(s => s.name === skill)!;
  expect((entry.files as string[]).map(String)).toContain('SKILL.md'); // 无 SKILL.md 不算技能
  expect(entry.adminOverride).toBe(false); // 会话内写入不置管理面栅栏
  const list = await (await request.get(`/skills/users/${uid}`)).json();
  expect(list.tombstones).toEqual([]);
});
```

#### X13 沙箱档回写仲裁（同会话同代容器）：tombstone 防复活（强断言）+ admin-override KV 终态契约

- **落点**：agent-framework/e2e/tests/api-sandbox.spec.ts（X 组追加，api-sandbox project → e2e-sandbox job）
- **blocker**：需补录制件
- **前置**：
  - 需补录制件：复用 X12 的 skill-manage-sb 夹具（容器内建技能基线），后续 turn 复用现有 [E2E:plain]
  - 修订采纳复核意见（同代容器可达性）：三个 turn 复用同一 sessionId——IsolationScope.USER（AgentScopeConfig.java:79）+ slot sandbox/user/{agentId}/{userId}（SandboxAwareMysqlAgentStateStore.java:11）下同会话跨 call 经 resume→connector().connect()（OpenSandboxClient.java:147-151）重连同一容器，X6（api-sandbox.spec.ts:91-109）实证 mock 不销毁时 connect 成功；turnA 的 skill_manage 容器副本因此在 turnB/turnC 仍存活
  - 两段断言的强度 honest 划分：① tombstone 防复活为强断言——turnB resume 新建包装（OpenSandboxClient.java:153）重跑 materialize 但已删技能被双重跳过、容器副本存活，WorkspaceSyncService.java:180-183 是唯一闸门，坏实现会复活致红；② admin-override 为 KV 终态契约（非载荷断言）——turnC resume 后 materialize 重跑且栅栏技能「照写」（WorkspaceReader.java:709-710），容器在 syncBack 前已收敛为管理面内容，栅栏闸门（WorkspaceSyncService.java:186-189）在 mock CI 无行为区分度；强变体只能在集群 E9（user-skill-admin-e2e.sh:529-553，其 :465 自带同款代际边界声明）验证
  - 前置确认同 X12：X3 同族缺陷不吞 skill_manage 写入，容器内须真实出现 /workspace/skills/e2e-sbx-skill 副本（间接黑盒证据 = turnA 后 KV 回写可见）
- **步骤**：
  1. turn1：新 uid、会话 sid 发 [E2E:skill:manage:sb] 建技能，pollUntil GET /skills/users/{uid} 出现该技能（回写落库基线，同时证明容器内副本已产生）
  2. DELETE /skills/users/{uid}/{skill} → 200/tombstone
  3. turn2：同会话 sid 发 [E2E:plain]（resume 同代容器，stop() syncBack 面对存活副本）
  4. GET /skills/users/{uid} 断言不复活
  5. PUT /skills/users/{uid}/{skill} 重建（action=created，清 tombstone 置 admin-override）
  6. turn3：同会话 sid 再发 [E2E:plain]；GET 明细与列表取终态
- **断言**：
  - turn1 终态 done 且 KV 出现该技能（syncBack 生效基线）
  - turn2 后：skills 不含该技能且 tombstones 含该技能——同代容器内存活副本未能把已删除技能写回 KV（tombstone 强断言，WorkspaceSyncService.java:180-183）
  - PUT 重建返回 action=created（清删除标记，UserSkillService.java:428）且 message 含「管理面写入栅栏」（:432 置栅栏）
  - turn3 后：明细 content 含管理面新 marker、adminOverride=true、tombstones 不再含该技能——跨 call 后 KV 终态保持管理面内容（admin-override 终态契约；栅栏分支的载荷性由集群 E9 钉住）
  - 三个 turn 终态均 done（仲裁路径不炸流）
- **draftCode**：

```typescript
// 需补录制件：复用 X12 的 skill-manage-sb 夹具（容器内建技能），其余 turn 走 [E2E:plain]。
// 三个 turn 复用同一 sessionId：同会话跨 call resume/connect 同代容器（AgentScopeConfig.java:79
// IsolationScope.USER + OpenSandboxClient.java:147-151 connect），tombstone 段才是载荷断言。
test('X13 回写仲裁：tombstone 防复活（强断言）+ admin-override KV 终态契约（同会话同代容器）', async ({ request }) => {
  const uid = U();
  const skill = 'e2e-sbx-skill'; // 与录制件中 skill_manage 参数一致
  const sid = sessionIdFor(`x13-${uniq()}`); // 三个 turn 共用
  const listBody = async () => (await (await request.get(`/skills/users/${uid}`)).json()) as {
    skills: Array<Record<string, unknown>>;
    tombstones: Array<Record<string, unknown>>;
  };

  // 1) 基线：容器内 skill_manage 建技能 → 回写落 KV（副本同时留在容器内）
  const w = chat({ message: `[E2E:skill:manage:sb]`, userId: uid, sessionId: sid });
  await waitTerminal(w);
  expect(w.terminal?.type).toBe('done');
  await pollUntil(async () => (await listBody()).skills, skills => skills.some(s => s.name === skill), 30_000);

  // 2) 管理面 DELETE → tombstone；同会话再发一条 plain turn 触发 stop() 回写 → 不得复活
  const del = await request.delete(`/skills/users/${uid}/${skill}`);
  expect(del.status()).toBe(200);
  const p = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid });
  await waitTerminal(p);
  expect(p.terminal?.type).toBe('done');
  const l1 = await listBody();
  expect(l1.skills.map(s => String(s.name))).not.toContain(skill); // tombstone 防复活（强断言）
  expect(l1.tombstones.map(t => String(t.name))).toContain(skill);

  // 3) 管理面 PUT 重建（清 tombstone、置 admin-override）→ 同会话再发 plain turn → KV 终态保持管理面内容
  const adminMarker = `FENCE-${uniq()}`;
  const put = await request.put(`/skills/users/${uid}/${skill}`, {
    data: { content: `---\nname: ${skill}\ndescription: admin fence probe\nversion: 2.0.0\n---\n\n${adminMarker}\n` },
  });
  expect(put.status()).toBe(200);
  const putBody = await put.json(); // 单次读取后复用（对齐 api-sandbox.spec.ts:46-47 风格）
  expect(putBody.action).toBe('created'); // 重建即清删除标记（UserSkillService.java:428）
  expect(String(putBody.message)).toContain('管理面写入栅栏');
  const q = chat({ message: `[E2E:plain]`, userId: uid, sessionId: sid });
  await waitTerminal(q);
  expect(q.terminal?.type).toBe('done');
  const detail = await request.get(`/skills/users/${uid}/${skill}`);
  expect(detail.status()).toBe(200);
  expect(String((await detail.json()).content)).toContain(adminMarker); // KV 终态 = 管理面内容
  const l2 = await listBody();
  expect(l2.skills.find(s => s.name === skill)!.adminOverride).toBe(true);
  expect(l2.tombstones.map(t => String(t.name))).not.toContain(skill); // 重建已清 tombstone
});
```

### 4.4 缺口四：/skills/available 与 parse-refs 按用户合并 L4 技能（skills，d0c3eaa）—— 3 条：SK2/SK3/SK4

**缺口描述**：带 X-User-Id/?userId= 时返回全局∪该用户 L4（同名 L4 描述覆盖、禁用不出现）、不传旧行为、L4 读取失败静默回落全局目录——全部无断言（tests grep skills/available|parse-refs 零命中；SK1 于 api-core.spec.ts:612 自述只钉路由）；静默降级可把"候选缩水"伪装成"无个人技能"，@Skill 聊天链路注入同样依赖此合并。

**复核证据要点**：
- 检索（上游实跑）：`grep -rn "skills/available\|parse-refs" agent-framework/e2e/{tests,scripts,mock,lib} agent-framework/docs/e2e-ci-plan.md /root/agent-manager/e2e/` → EXIT:1 零命中；tests/ 内 skills 相关仅 /skills（S1, api-core.spec.ts:21,46-47）与 SK 组（:606-634）。
- SK1 只钉路由：api-core.spec.ts:608 自述'此处只钉住端点路由与响应契约'，实测覆盖 /debug/user-skills+/skills/users 索引+404/400 负例（:610-634），无 /skills/available、无 /skills/parse-refs。
- 端点语义（源码证实）：/skills/available 带 X-User-Id/?userId=：SkillManageController.java:67-74+effectiveUserId:93-95（头优先）；合并：SkillCatalogService.java:179-211——userId blank 回落全局目录 :180-182、L4 无条件覆盖同名描述 :203、合并失败 catch 后仅 log.warn 静默回落全局目录 :205-208；不传旧行为 availableSkills() :169-171。'禁用不出现'仅对无同名 L4 成立：list() 先剔除禁用 :146-149，随后 collectAvailable :196-204 无条件回填 L4 名——同名 L4 时被禁用包内技能反而复现，与其 javadoc :177 存在代码/文档分歧（SK4 已声明不钉此边界）。L4 读取失败静默吞掉：WorkspaceReader.java:326-336（ls 失败按无技能返回空 map）、技能内 glob 失败才抛 :345。'候选缩水伪装成无个人技能'同构：SkillInjectionService.java:211-213 enabledSkillNames 合并失败静默降级。
- @Skill 聊天链路依赖此合并：ChatStreamController.java:366 `skillInjectionService.injectSkillReferences(message, finalUserId)` → SkillInjectionService.java:86 enabledSkillNames(userId)。
- 限定说明：合并语义并非零断言——单测有覆盖（SkillCatalogServiceTest.java:207-228、SkillInjectionServiceTest.java:264-277），随 mvn test job 跑——缺口严格限于四个 e2e job 的 HTTP 黑盒面。
- 评审用例所引代码行号逐一核对无误（SkillManageController.java:89/93-95/170-174、SkillCatalogService.java:146-149/203、UserSkillController.java:248-259、SkillInjectionService.java:38-43/197-216、SkillManageService.java:186-210/391-397、WorkspaceReader.java:332-336/345、env-up.sh:49、playwright.config.ts:11-12/23、ui.spec.ts:226/235、e2e/user-skill-admin-e2e.sh:295-297 action=created+version 非零先例）；SK2-4 案例号无冲突（ui.spec.ts:303/339/363 为 U-SK2/3/4 前缀不同、且是 route stub 的 UI 用例非重复）。

#### SK2 /skills/available 按用户合并 L4：同名描述覆盖、独有补入、X-User-Id 优先、删除回落全局

- **落点**：agent-framework/e2e/tests/api-core.spec.ts（SK 组：用户技能，接在 SK1 之后；api-core project → e2e-core job）
- **blocker**：none
- **前置**：
  - e2e-core 环境：agent-framework :8100 + MySQL（agent_fs KV 存储）+ fixture 目录含 demo-skill（env-up.sh:49 拷贝 fixtures/agent-config），SANDBOX_ENABLED=false
  - 纯 REST 用例，零 LLM 录制件、零 mock 扩展（registry.json/check-fixtures.mjs 不动）；文件级 beforeEach 的 llmReset() 照常执行即可
  - userId 用 ids()+uniq() 运行级唯一：L4 写入 agent_fs KV 跨轮持久，运行级唯一 ID 避免撞历史数据（对齐 F 组 upload 的隔离模式）
  - 边界说明：缺口中的"L4 读取失败静默回落"严格分支黑盒不可注入——WorkspaceReader.listUserSkills 把 KV 枚举失败按"无技能"吞掉（WorkspaceReader.java:332-335），只有技能目录内 glob 失败才抛（:345）；本用例以"无 L4 用户合并视图==全局目录"钉住可达的降级面
  - 负向断言前置：uidB 的 PUT 必须在用例内显式断言成功（200/action=created，契约见 UserSkillController.java:199-215 与 user-skill-admin-e2e.sh:295 先例），否则 Header 优先的 not.toContain(markerB) 失去判别力（复核修订点）
- **步骤**：
  1. GET /skills/available（不传 userId）记为 baseline；GET /skills/available?userId=（空串）对比 baseline
  2. 生成两个运行级唯一用户 uidA/uidB；GET /skills/available?userId={uidB}（B 尚无任何 L4）对比 baseline
  3. PUT /skills/users/{uidA}/demo-skill（body {content: frontmatter description=L4A覆盖-<uniq> 的 SKILL.md}）→ 断言 200/action=created/version>0；同法给 uidA 写独有技能 e2e-l4-<uniq>（description=L4独有-<uniq>）并断言 action=created；给 uidB 写 demo-skill（description=L4B覆盖-<uniq>）同样断言 200/action=created（复核修订：负向断言的判别力前提）
  4. GET /skills/available?userId={uidA} 检查合并视图
  5. GET /skills/available 带 X-User-Id:{uidA} 头 + ?userId={uidB} 查询参数，检查头优先
  6. 再次 GET /skills/available（不传 userId）确认公共视图未混入 L4
  7. DELETE /skills/users/{uidA}/demo-skill → 200；DELETE /skills/users/{uidA}/{e2e-l4-<uniq>} → 200；随后 GET /skills/available?userId={uidA} 观察回落
- **断言**：
  - baseline 为数组且每项键恰为 name+description（SkillCatalogService.toNameDesc 的精简视图契约）；含 demo-skill（fixture 保证）
  - 空串 ?userId= 与不传返回同一视图（effectiveUserId blank → availableSkills()，SkillManageController.java:93-95）；无 L4 的 uidB 视图与 baseline 全等（按 name 排序深比较）——即"无 L4 回落全局目录"可达契约
  - 三次 PUT 全部断言 200 且 action=created（uidA demo-skill 额外断言 version>0）——uidB 写入成功是后续 Header 优先负向断言有判别力的前提（复核修订点）
  - 合并视图：demo-skill.description == L4A覆盖标记（同名 L4 覆盖全局描述，SkillCatalogService.java:203）；e2e-l4-<uniq> 存在且 description == 独有标记（L4 独有补入）
  - X-User-Id 头优先于 ?userId=：返回 uidA 的两个标记而非 uidB 的；响应中不出现 L4B覆盖标记（双向钉死 effectiveUserId 优先级；因 uidB 写入已断言成功，此负向断言不再有假绿窗口）
  - PUT 后重取不传 userId 视图：demo-skill 描述仍为全局原值、全响应不含独有技能名——个人技能不泄漏进公共视图（"不传旧行为"在写入后仍成立）
  - DELETE demo-skill 响应 hasPackageBaseline=true（回落包内基线，UserSkillController.java:248-259）；pollUntil 至合并视图中 demo-skill.description 回落为全局原值（tombstone 后技能已不在 L4 清单）；独有技能删除后从合并视图消失
  - 全程零 LLM 调用、零 chat 请求
- **draftCode**：

```typescript
// 追加在 api-core.spec.ts 的 SK1 之后；仅新增类型导入（并入文件首行 import）：
// import type { APIRequestContext, APIResponse } from '@playwright/test';

/** 写入用户 L4 技能主文件：description 承载 /available 合并视图的覆盖描述（l4Description 解析 frontmatter） */
async function putL4(request: APIRequestContext, uid: string, name: string, description: string): Promise<APIResponse> {
  const content = `---\nname: ${name}\ndescription: ${description}\n---\n\n# ${name}\n`;
  return request.put(`/skills/users/${encodeURIComponent(uid)}/${encodeURIComponent(name)}`, { data: { content } });
}

/** /available 返回顺序（全局目录序 + L4 追加）不参与契约，深比较前按 name 归一 */
const nameSorted = (arr: unknown) =>
  (arr as Array<Record<string, string>>).slice().sort((a, b) => (a.name < b.name ? -1 : 1));

test('SK2 /skills/available 按用户合并 L4：同名覆盖、独有补入、Header 优先、删除回落', async ({ request }) => {
  // 旧行为基线（不传 userId）：全局启用目录的 name+description 精简视图（SkillCatalogService.availableSkills()）
  const baseline = await (await request.get('/skills/available')).json() as Array<Record<string, string>>;
  expect(Array.isArray(baseline)).toBe(true);
  for (const e of baseline) expect(Object.keys(e).sort()).toEqual(['description', 'name']);
  const baseDemo = baseline.find(e => e.name === 'demo-skill');
  expect(baseDemo, 'fixture 目录应含 demo-skill（fixtures/agent-config/skills）').toBeTruthy();
  // 空串 userId 等价不传（effectiveUserId blank → 全局目录）
  expect(nameSorted(await (await request.get('/skills/available?userId=')).json()))
    .toEqual(nameSorted(baseline));

  const uidA = ids(`sk2-a-${uniq()}`);
  const uidB = ids(`sk2-b-${uniq()}`);
  // 无任何 L4 的用户：合并视图与全局目录全等（合并不得让 @ 候选缩水/变形）。
  // 注：KV 读取失败严格分支黑盒不可注入（WorkspaceReader 把枚举失败按空处理），此处钉其可达降级面
  expect(nameSorted(await (await request.get(`/skills/available?userId=${encodeURIComponent(uidB)}`)).json()))
    .toEqual(nameSorted(baseline));

  const l4Only = `e2e-l4-${uniq()}`;
  const markerA = `L4A覆盖-${uniq()}`;
  const onlyMarker = `L4独有-${uniq()}`;
  const markerB = `L4B覆盖-${uniq()}`;
  const putDemo = await putL4(request, uidA, 'demo-skill', markerA);
  expect(putDemo.status()).toBe(200);
  const putBody = await putDemo.json() as Record<string, unknown>;
  expect(putBody.action).toBe('created');               // 对齐 user-skill-admin-e2e.sh E1 断言
  expect(Number(putBody.version)).toBeGreaterThan(0);   // 返回 KV 版本号
  expect(((await (await putL4(request, uidA, l4Only, onlyMarker)).json()) as Record<string, unknown>).action).toBe('created');
  // uidB 写入必须显式断言成功：否则下方「X-User-Id 优先」的负向断言（hdr 不含 markerB）
  // 在 PUT 失败时自然成立，头部优先级实际未被行使（假绿窗口，SK1 注释所防的静默合入形态）
  const putB = await putL4(request, uidB, 'demo-skill', markerB);
  expect(putB.status()).toBe(200);
  expect(((await putB.json()) as Record<string, unknown>).action).toBe('created');

  // 合并视图：同名 L4 描述覆盖全局 + L4 独有技能补入
  const merged = await (await request.get(`/skills/available?userId=${encodeURIComponent(uidA)}`)).json() as Array<Record<string, string>>;
  expect(merged.find(e => e.name === 'demo-skill')?.description).toBe(markerA);
  expect(merged.find(e => e.name === l4Only)?.description).toBe(onlyMarker);

  // X-User-Id 头优先于 ?userId=（网关注入登录态优先）：返回 A 的视图且不混入 B 的 L4
  const hdr = await (await request.get('/skills/available', { headers: { 'X-User-Id': uidA }, params: { userId: uidB } })).json() as Array<Record<string, string>>;
  expect(hdr.find(e => e.name === 'demo-skill')?.description).toBe(markerA);
  expect(hdr.find(e => e.name === l4Only)?.description).toBe(onlyMarker);
  expect(JSON.stringify(hdr)).not.toContain(markerB);

  // 不传 userId 始终是全局目录：写入后公共视图仍不泄漏个人技能
  const globalAfter = await (await request.get('/skills/available')).json() as Array<Record<string, string>>;
  expect(globalAfter.find(e => e.name === 'demo-skill')?.description).toBe(baseDemo!.description);
  expect(JSON.stringify(globalAfter)).not.toContain(l4Only);

  // 删除回落：demo-skill 删除（有包内基线）→ 合并视图回落全局描述
  const del = await request.delete(`/skills/users/${encodeURIComponent(uidA)}/demo-skill`);
  expect(del.status()).toBe(200);
  expect(((await del.json()) as Record<string, unknown>).hasPackageBaseline).toBe(true);
  await pollUntil(async () => {
    const rows = await (await request.get(`/skills/available?userId=${encodeURIComponent(uidA)}`)).json() as Array<Record<string, string>>;
    return rows.find(e => e.name === 'demo-skill');
  }, e => e?.description === baseDemo!.description, 15_000);
  // 独有技能删除 → 从合并视图消失（无包内基线，该技能对该用户已不可见）
  expect((await request.delete(`/skills/users/${encodeURIComponent(uidA)}/${encodeURIComponent(l4Only)}`)).status()).toBe(200);
  await pollUntil(async () => {
    const rows = await (await request.get(`/skills/available?userId=${encodeURIComponent(uidA)}`)).json() as Array<Record<string, string>>;
    return rows.some(e => e.name === l4Only);
  }, gone => !gone, 15_000);
});
```

#### SK3 /skills/parse-refs 按用户合并解析 @Skill 引用：L4 可命中、去重保序、无 userId 不见 L4、负例不误报

- **落点**：agent-framework/e2e/tests/api-core.spec.ts（SK 组：用户技能，接在 SK2 之后；api-core project → e2e-core job）
- **blocker**：none
- **前置**：
  - e2e-core 环境同 SK2（:8100 + MySQL agent_fs + fixture demo-skill）
  - 纯 REST，零 LLM 录制件/mock 扩展；复用 SK2 段定义的 putL4 helper（追加时只定义一次）
  - @ 引用解析与聊天链路 @Skill 注入同源（SkillInjectionService.enabledSkillNames：全局启用目录 ∪ 用户 L4，SkillInjectionService.java:197-215），本用例即该合并的黑盒门禁
- **步骤**：
  1. 新建运行级唯一用户 uid，PUT 一个 L4 独有技能 e2e-l4-<uniq>（断言 action=created）
  2. GET /skills/parse-refs?message="@demo-skill 和 @{l4Name} 再 @demo-skill"&userId={uid}
  3. GET /skills/parse-refs?message="@{l4Name}"（不传 userId）
  4. GET /skills/parse-refs?message="联系 user@example.com"&userId={uid}（邮箱形态）
  5. GET /skills/parse-refs?message="@no-such-skill-x"&userId={uid}（目录外名称）
  6. GET /skills/parse-refs?message="用@{l4Name}"&userId={uid}（中文紧邻 @，技能名收尾于串尾避免 \u4e00-\u9fff 字符类过度吞噬）
- **断言**：
  - 全部请求 200，响应体恰为 {skills: string[], count}（SkillManageController.java:89）且 count === skills.length
  - 步骤 1 的 PUT 显式断言 action=created——L4 技能写入成功是后续"L4 可命中"正向断言与"无 userId 不见 L4"负向断言的共同前提（对齐 SK2 复核修订的同一原则：预置写入不得丢断言）
  - 步骤 2：skills 严格等于 ['demo-skill', l4Name]——全局与 L4 合并命中、重复引用去重、保持首次出现顺序
  - 步骤 3：{skills: [], count: 0}——不传 userId 时 L4 个人技能不可见（合并依赖生效 userId，缺失即 @ 候选静默缩水的反证面）
  - 步骤 4/5：均 {skills: [], count: 0}——user@example.com 被 (?<![\w]) lookbehind 挡住不误匹配；目录外名称即使匹配 pattern 也被 enabledSkillNames 过滤
  - 步骤 6：skills 严格等于 [l4Name]——中文后紧跟 @ 合法（javadoc 声明"用@pdf处理"用法，SkillInjectionService.java:38-40）
- **draftCode**：

```typescript
// 追加在 SK2 之后；putL4 helper 见 SK2 段（整文件仅定义一次）
test('SK3 /skills/parse-refs 按用户合并解析 @Skill 引用', async ({ request }) => {
  const uid = ids(`sk3-${uniq()}`);
  const l4Name = `e2e-l4-${uniq()}`;
  expect(((await (await putL4(request, uid, l4Name, `L4引用-${uniq()}`)).json()) as Record<string, unknown>).action).toBe('created');

  const refs = async (message: string, userId?: string) => {
    const q = new URLSearchParams({ message });
    if (userId) q.set('userId', userId);
    const res = await request.get(`/skills/parse-refs?${q}`);
    expect(res.status()).toBe(200);
    return await res.json() as { skills: string[]; count: number };
  };

  // 全局技能 + L4 技能合并命中；重复引用去重保序；count 与 skills 一致
  const both = await refs(`@demo-skill 和 @${l4Name} 再 @demo-skill`, uid);
  expect(both.skills).toEqual(['demo-skill', l4Name]);
  expect(both.count).toBe(both.skills.length);

  // 不传 userId：L4 个人技能不可见（@ 注入链路的合并依赖生效 userId）
  expect(await refs(`@${l4Name}`)).toEqual({ skills: [], count: 0 });
  // 邮箱形态不误匹配（(?<![\w]) lookbehind）；目录外名称被 enabledSkillNames 过滤
  expect(await refs('联系 user@example.com', uid)).toEqual({ skills: [], count: 0 });
  expect(await refs('@no-such-skill-x', uid)).toEqual({ skills: [], count: 0 });
  // 中文紧邻 @ 合法（技能名收尾于串尾，避免中文字符类把后续中文吞进名字）
  expect((await refs(`用@${l4Name}`, uid)).skills).toEqual([l4Name]);
});
```

#### SK4 禁用的包内技能不进 /skills/available 与 parse-refs（toggle 后恢复原状）

- **落点**：agent-framework/e2e/tests/api-core.spec.ts（SK 组：用户技能，接在 SK3 之后；api-core project → e2e-core job）
- **blocker**：none
- **前置**：
  - e2e-core 环境同 SK2；fixture 仅保证 demo-skill 一个包内技能，禁用场景以其为对象
  - demo-skill 处于启用态——用例内先读 /skills/manage 自愈（上轮异常残留禁用态则先拨回），避免重试/残留放大
  - api-core 单 worker 串行（playwright.config.ts workers=1 / fullyParallel:false）且本用例位于 S1 之后：临时禁用不影响同文件先行用例；后续 project 安全（ui.spec.ts:226,235 对 /skills/**、/skills/manage 全部 page.route stub）
  - toggle 状态落运行时配置目录 .skill-states.json（SkillManageService.java:391-397），getDisabledSet 每次实时读文件（:208-210），拨回即恢复
  - 刻意不钉"同名 L4 覆盖禁用技能"边界：代码在禁用过滤后无条件回填 L4 名（SkillCatalogService.java:196-204），与 availableSkills javadoc "被禁用的包内技能仍不出现"存在分歧，待行为裁决前不门禁化
- **步骤**：
  1. GET /skills/manage，找到 demo-skill；若 enabled===false 则 PUT /skills/demo-skill/toggle 拨回（前置自愈）
  2. 生成运行级唯一用户 uid（不写任何 L4，保持纯全局视图）；PUT /skills/demo-skill/toggle → 断言响应 enabled=false
  3. GET /skills/available（不传 userId）
  4. GET /skills/available?userId={uid}
  5. GET /skills/parse-refs?message="@demo-skill 演示一下"&userId={uid}
  6. finally：PUT /skills/demo-skill/toggle 恢复启用；随后 GET /skills/manage 与 GET /skills/available 复核
- **断言**：
  - toggle 关断响应 enabled=false（SkillManageController.java:170-174）
  - 步骤 3/4：两个视图均不含 demo-skill——list() 过滤禁用（SkillCatalogService.java:146-149），该用户无同名 L4 不得复活
  - 步骤 5：{skills: [], count: 0}——@ 解析与 available 同源走 enabledSkillNames，禁用技能不再命中
  - 恢复后：/skills/manage 中 demo-skill enabled===true；/skills/available 重新包含 demo-skill（不污染 S1 等依赖全局目录的用例与后续 project）
- **draftCode**：

```typescript
// 追加在 SK3 之后
test('SK4 禁用的包内技能不进 /available 与 parse-refs（恢复原状）', async ({ request }) => {
  // 前置自愈：上轮异常残留禁用态则先拨回（toggle 对称，.skill-states.json 实时生效）
  const st0 = (await (await request.get('/skills/manage')).json() as Array<Record<string, unknown>>)
    .find(s => s.name === 'demo-skill');
  expect(st0, 'fixture 目录应含 demo-skill').toBeTruthy();
  if (st0!.enabled === false) await request.put('/skills/demo-skill/toggle');

  const uid = ids(`sk4-${uniq()}`); // 纯全局视图用户（无 L4 覆盖）
  expect((((await (await request.put('/skills/demo-skill/toggle')).json()) as Record<string, unknown>)).enabled).toBe(false);
  try {
    // 无 userId 视图剔除
    let rows = await (await request.get('/skills/available')).json() as Array<Record<string, string>>;
    expect(rows.some(e => e.name === 'demo-skill')).toBe(false);
    // 带 userId 合并视图同样剔除（该用户无同名 L4 → 不得复活）
    rows = await (await request.get(`/skills/available?userId=${encodeURIComponent(uid)}`)).json() as Array<Record<string, string>>;
    expect(rows.some(e => e.name === 'demo-skill')).toBe(false);
    // @ 引用解析同源（enabledSkillNames）：禁用技能不再命中
    const refs = await (await request.get(`/skills/parse-refs?${new URLSearchParams({ message: '@demo-skill 演示一下', userId: uid })}`)).json() as { skills: string[]; count: number };
    expect(refs).toEqual({ skills: [], count: 0 });
  } finally {
    await request.put('/skills/demo-skill/toggle'); // 恢复启用，不污染后续用例/project
  }
  const st1 = (await (await request.get('/skills/manage')).json() as Array<Record<string, unknown>>)
    .find(s => s.name === 'demo-skill');
  expect(st1!.enabled).toBe(true);
  const rows = await (await request.get('/skills/available')).json() as Array<Record<string, string>>;
  expect(rows.some(e => e.name === 'demo-skill')).toBe(true);
});
```

**复核意见（评审留痕，已修订吸收）**：
- 【SK2·假绿窗口】原稿给 uidB 写 demo-skill（markerB）的 PUT 响应未做任何断言：'X-User-Id 优先'的负向断言 expect(JSON.stringify(hdr)).not.toContain(markerB) 只有在 uidB 写入确实成功时才有判别力——PUT B 若 500，hdr 视图自然不含 markerB，测试照绿而头部优先级实际未被行使（恰是 SK1 注释所防的'静默合入'形态）。修法一行：与 uidA 同法断言 putB.status()===200 或 action='created'。（已改）

### 4.5 缺口五：AGENT_MEMORY_ENABLED=false 记忆总开关关断分支（memory，4bb4c26）—— 4 条：MEM1/MEM2/MEM3/X10（记忆面）

> **编号冲突提示**：材料三「沙箱技能回写」缺口（§4.3）与「记忆关断」缺口（本节）各自将首条用例编号为 **X10**，且两者均落 `api-sandbox.spec.ts` X 组（各自基于既有 X1-X9 判定"X10 未占用"）——两缺口用例同时落地时必然撞号。**落地时须为本节记忆面用例重编号（建议顺延为 X14 起）**，见 §5.5。

**缺口描述**：false 时 hooks+tools 双关、记忆内部 LLM 调用消失、沙箱 MEMORY 注入/回写 no-op（AgentScopeConfig.java:126 harness.memoryEnabled()），但 e2e tests/scripts/mock/config 精确 grep AGENT_MEMORY_ENABLED|memory-enabled|memoryEnabled 零命中（exit=1）；若 false 分支装配回归（实际未关）或默认 true 误读为关，CI 门禁均不红。

**复核证据要点**（上游实跑取证）：
- grep 零命中：`grep -rnE 'AGENT_MEMORY_ENABLED|memory-enabled|memoryEnabled' agent-framework/e2e/tests/` → exit=1；同 pattern 对 e2e/scripts/plugin-smoke.sh → exit=1、docs/e2e-ci-plan.md → exit=1；扩大到整个 agent-framework/e2e/（*.ts/*.mjs/*.sh/*.json/*.py/*.yml/*.yaml/*.xml/*.md，排除 node_modules）→ exit=1。源码命中仅在 src/main：application.yml:111 `memory-enabled: ${AGENT_MEMORY_ENABLED:true}`、AgentScopeConfig.java:126、HarnessAgentFactory.java:223/236-237/249、WorkspaceReader.java:65/76/142-145、WorkspaceSyncService.java:71-74、AgentManagerProperties.java:216-217、InternalToolRegistry.java:83。
- false 分支语义核实：HarnessAgentFactory.java:236-237 else 分支 `builder.disableMemoryHooks().disableMemoryTools(); log.info("Memory fully disabled (agent.harness.memory-enabled=false)")`；:249 `.flushBeforeCompact(harness.memoryEnabled() && harness.compactionFlushBeforeCompact())`（:244-248 注释明确 SDK 2.0.3 压缩前 flush 不经 disableMemoryHooks，须置 false）；WorkspaceReader.java:142-145 memoryEnabled=false 时 readRuntimeFiles 返回 Map.of()、:785-789 injectToSandbox 空集早退；OpenSandbox.java:234-249 injectRuntimeFilesIfNeeded；WorkspaceSyncService.java:71-74 回写侧 isMemoryEnabled() 门控。缺口陈述的三重后果逐条与源码吻合。
- SDK 侧佐证：javap 检查 /root/.m2/repository/io/agentscope/agentscope-harness/2.0.3/agentscope-harness-2.0.3.jar：HarnessAgent$Builder 存在 disableMemoryTools()/disableMemoryHooks() 且 build 路径读取；常量池含字面量 "You are a memory extraction assistant. ..."——mock/llm-server.mjs:146-147 的 background-synth 判据正是钉住该真实调用签名。
- 门禁不可见性核实：CI 四个 e2e job 各自 `./scripts/run.sh <group>`（run.sh:19-21）；由于 tests/scripts/mock/config 对该开关零引用，无论 false 分支装配回归还是默认 true 被误读为关，四个 Playwright 项目、check-fixtures.mjs、plugin-smoke.sh 均无断言面 → CI 门禁不红。
- 用例可行性底座核实：client.ts:5-16 ChatOpts.base、:46 history、:103-108 llmStats（按 BASE hostname:18081 寻址共享 mock）、:110-113 llmReset；playwright.config.ts:12 workers=1、:23 api-core testMatch；run.sh:19-21；env-up.sh:55-62 渲染 logback-e2e.xml CONSOLE INFO（HarnessAgentFactory:237 的 log.info 会落盘）；start-agent.sh:31-34 端口清场、:36-46 内联 env 白名单透传、:46 日志重定向 .runtime/logs/agent-memoff.log、:47 pid 文件、:48 wait-ready 90s；env-down.sh:8 glob agent-*.pid 兜底；api-multi-kill.spec.ts:6-9/:18-32/:46-57 先例；e2e-core job 预构建 jar；e2e-sandbox job 无浏览器与 X10 纯 API 断言兼容；registry.json plain calls:1、execute calls:2，check-fixtures 无运行时调用数耦合；api-core.spec.ts:16 文件级 beforeEach llmReset；SK 组为 api-core 末组（:606-634），MEM1-3 编号未占用；sandbox-server.mjs:146 /stats、:147 /reset（只清统计，KV 不动）、:196-213 files/upload 计入 fileOps；SessionTitleService.java:55-59 标题 system 提示为 '会话标题生成助手...'（不含 memory extraction assistant）→ 标题调用归 scenario=plain，MEM2 判据成立。

#### MEM1 记忆关断实例 /tools 契约：sdkInternal 无 memory_* 三件，默认实例对照组在（双向回归门禁）

- **落点**：agent-framework/e2e/tests/api-core.spec.ts（新增 test.describe('MEM 记忆关断') 组，追加在 SK 组之后，beforeAll/afterAll 收进 describe；api-core project 已在 run.sh core --project 列表内，无需改 playwright.config/CI）
- **blocker**：none
- **前置**：
  - e2e-core 环境已就绪（run.sh core：MySQL/Redis/mock LLM/bench+approval MCP，jar 已由 job 预构建 mvn -DskipTests package）
  - describe 级 beforeAll（修订：不放文件顶层——文件级会在 S1 之前拉起第二 JVM 全程驻留，且 spawn 失败会炸整个 api-core project）经 scripts/start-agent.sh memoff <port> 以 AGENT_MEMORY_ENABLED=false 拉起独立实例（进程模式仿 api-multi-kill.spec.ts:46-57 restoreReplicaA；端口默认 BASE+10=8110，start-agent.sh:31-34 自带端口清场，.runtime 隔离经 agent-memoff.pid/独立日志）
  - 无需新录制件：本用例不走 LLM
  - start-agent.sh:36-46 内联 env 为白名单覆盖、其余变量原样透传子进程 → spawnSync env 展开 AGENT_MEMORY_ENABLED=false 即命中 application.yml:111 memory-enabled:${AGENT_MEMORY_ENABLED:true}
- **步骤**：
  1. GET http://127.0.0.1:8110/tools?includeInternal=true（关断实例）
  2. GET http://127.0.0.1:8110/tools（默认视图）
  3. GET {BASE}/tools?includeInternal=true（默认实例对照组，经 playwright request baseURL）
- **断言**：
  - 【容错前置】先断言响应体 sdkInternal 为数组——fail-soft 时端点返回空列表（InternalToolRegistry.java:110-113 catch 返回 List.of()），避免 TypeError 掩盖『/tools 端点异常』与『工具仍注册』两种故障的区分
  - 关断实例 sdkInternal（条目形如 {name,category:'sdk',source:'sdk'}，InternalToolRegistry.java:105-108）的 name 集不含 memory_search/memory_get/memory_save（HarnessAgentFactory.java:52 白名单名）——出现任一即 false 分支装配回归（确定性拦截点）
  - 关断实例 sdkInternal 仍含 read_file——关记忆不误伤文件系统内置工具（排除『全量误关』假绿）
  - 关断实例默认视图仍含 bench_echo（MCP 业务工具不受记忆开关影响）
  - 对照组（默认实例，AGENT_MEMORY_ENABLED 缺省 true）sdkInternal 必须含全部三个 memory_*——缺失即『默认 true 误读为关』或 sdkInternal 暴露通道回归（对照缺失时关断侧的『不含』无意义）
- **draftCode**：

```typescript
// api-core.spec.ts 顶部 import 合并新增（仿 api-multi-kill.spec.ts:6-9）：
// import fs from 'node:fs';
// import path from 'node:path';
// import { spawnSync } from 'node:child_process';
// import { fileURLToPath } from 'node:url';

// ---------- MEM 组：记忆总开关关断分支（AGENT_MEMORY_ENABLED=false） ----------
// 覆盖缺口：e2e tests/scripts/mock/config 对 AGENT_MEMORY_ENABLED 零命中（grep exit=1）。
// 整组包进 describe：文件级 beforeAll 会在 S1 之前拉起第二 JVM 且 spawn 失败炸全文件——
// describe 级 hooks（workers=1 串行）恰在 SK1 之后、MEM1 之前触发，爆炸半径限于本组。
test.describe('MEM 记忆关断', () => {
  const runtimeDir = process.env.E2E_RUNTIME_DIR ?? '.runtime';
  const MEM_PORT = process.env.E2E_MEMOFF_PORT ?? String(Number(new URL(BASE).port) + 10);
  const MEM_BASE = `http://127.0.0.1:${MEM_PORT}`;
  const START_SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), '../scripts/start-agent.sh');
  const MEMORY_TOOLS = ['memory_search', 'memory_get', 'memory_save']; // HarnessAgentFactory.java:52

  let memoffPid: number | null = null;

  test.beforeAll(async () => {
    // start-agent.sh 内联 env 白名单（LLM_BASE_URL 等，:36-46）之外原样透传子进程：
    // AGENT_MEMORY_ENABLED=false 经 spawnSync env 命中 application.yml:111 relaxed binding
    const r = spawnSync('bash', [START_SCRIPT, 'memoff', MEM_PORT], {
      encoding: 'utf8', timeout: 150_000,
      env: { ...process.env, AGENT_MEMORY_ENABLED: 'false' },
    });
    if (r.status !== 0) {
      throw new Error(`memory-off 实例启动失败 exit=${r.status}\nstdout: ${(r.stdout ?? '').slice(-400)}\nstderr: ${(r.stderr ?? '').slice(-800)}`);
    }
    try {
      const pid = Number(fs.readFileSync(path.join(runtimeDir, 'agent-memoff.pid'), 'utf8').trim()); // start-agent.sh:47
      if (Number.isInteger(pid) && pid > 0) memoffPid = pid;
    } catch { /* pid 文件缺失交由用例内请求失败暴露 */ }
  });

  test.afterAll(async () => {
    if (memoffPid !== null) { try { process.kill(memoffPid); } catch { /* 已退出 */ } }
  });

  test('MEM1 记忆关断实例 /tools 契约：sdkInternal 无 memory_*，默认实例对照组在', async ({ request }) => {
    const off = await (await fetch(`${MEM_BASE}/tools?includeInternal=true`)).json() as {
      sdkInternal?: Array<{ name: string }>;
    };
    // fail-soft 容错：sdkInternal 枚举异常时返回空列表（InternalToolRegistry.java:110-113）——
    // 先断言在字段，避免 TypeError 掩盖『端点异常』与『工具仍注册』两种故障的区分
    expect(Array.isArray(off.sdkInternal), `关断实例 /tools sdkInternal 段异常：${JSON.stringify(off).slice(0, 200)}`).toBe(true);
    const offSdk = (off.sdkInternal ?? []).map(t => t.name);
    for (const name of MEMORY_TOOLS) {
      expect(offSdk, `${name} 在关断实例仍注册（disableMemoryTools 未生效/false 分支装配回归）`).not.toContain(name);
    }
    expect(offSdk, '关记忆不得误伤文件系统内置工具').toContain('read_file');
    const plainView = await (await fetch(`${MEM_BASE}/tools`)).json();
    expect(JSON.stringify(plainView), '默认视图 MCP 工具不受记忆开关影响').toContain('bench_echo');

    // 对照组：默认实例（AGENT_MEMORY_ENABLED 缺省 true）必须暴露 memory_* ——防两向回归：
    // 对照缺失=默认被误读为关；关断实例出现=false 分支未生效
    const base = await request.get('/tools?includeInternal=true');
    expect(base.status()).toBe(200);
    const baseBody = await base.json() as { sdkInternal?: Array<{ name: string }> };
    expect(Array.isArray(baseBody.sdkInternal), '默认实例 /tools sdkInternal 段异常（fail-soft 空列表）').toBe(true);
    const baseSdk = (baseBody.sdkInternal ?? []).map(t => t.name);
    for (const name of MEMORY_TOOLS) {
      expect(baseSdk, `默认实例缺少 ${name}（对照组失效：默认值或 sdkInternal 暴露通道回归）`).toContain(name);
    }
  });
```

#### MEM2 关断实例 [E2E:plain] 对话正常，llmStats 窗口零 background-synth 记忆提取调用（卫生级信号）

- **落点**：agent-framework/e2e/tests/api-core.spec.ts（MEM describe 组第 2 条；沿用文件级 test.beforeEach llmReset，api-core.spec.ts:16，不重复声明）
- **blocker**：none
- **前置**：
  - MEM1 的 describe 级 beforeAll 已拉起 AGENT_MEMORY_ENABLED=false 独立实例（workers=1 串行下恰在 MEM1 前触发，同组共享语义成立）
  - mock LLM 与两个被测实例共用（llmStats 只按 hostname:18081 寻址，client.ts:103-108）；plain 场景已登记 registry.json（marker [E2E:plain]，calls:1），check-fixtures.mjs 只校验注册表↔夹具一致性、无运行时调用数耦合 → 无需新录制件
  - 判据现成：llm-server.mjs:146-147 无 system 消息或 system 含 'memory extraction assistant' → scenario=background-synth（记忆提取必带 system，MemoryFlushMiddleware）；标题生成调用自带 '会话标题生成助手' system（SessionTitleService.java:55-59）→ 归入 plain，不污染零断言
- **步骤**：
  1. POST /threads/chat（base=关断实例）报文 message=[E2E:plain]、userId=e2e-memoff、sessionId=mem2-<uniq>，收 SSE 流
  2. GET 关断实例 /threads/{sid}/history
  3. GET mock LLM /stats（llmStats()）
- **断言**：
  - 流收敛终态 done；首帧 session_created；TEXT_BLOCK_DELTA 拼接长度 >2（对话链路在关断实例上完整可用，排除关断把 agent 关坏的假阴性）
  - history HTTP 200（S2 同款 _http 容忍）
  - stats 窗口内存在 scenario='plain' 的调用——主对话确实到达 mock（防 spawn 环境错配静默假绿）
  - stats 窗口内 scenario='background-synth' 调用数为 0——⚠本条是卫生级信号而非确定性拦截：(a) flush 走 throttled 触发（默认 10 分钟节流，application.yml:112 AGENT_MEMORY_FLUSH_THROTTLE_MINUTES），即便 false 分支回归（hooks 未关），秒级窗口内大概率不产生 background-synth（空转通过）；确定性拦截在 MEM1（工具注册）与 MEM3（分支日志）。(b) llmStats 为共享 mock 全局窗口且记录不含来源实例字段（llm-server.mjs:149-158），BASE 实例异步 flush 理论上可落窗误红（概率低：10min 节流 vs 秒级窗口）
  - 已知边界（照实声明）：压缩前 flush 子分支（HarnessAgentFactory.java:249 flushBeforeCompact 门控）依赖 compact 场景录制件、当前没有——不在射程内，由 MEM1 工具注册契约兜底（同回归源）
- **draftCode**：

```typescript
  // （MEM describe 组内，接 MEM1）
  test('MEM2 关断实例 [E2E:plain] 对话正常且无记忆提取后台 LLM 调用（卫生级）', async () => {
    const sid = sessionIdFor(`mem2-${uniq()}`);
    const stream = chat({ message: `[E2E:plain]`, userId: 'e2e-memoff', sessionId: sid, base: MEM_BASE });
    await waitTerminal(stream);
    expect(stream.terminal?.type).toBe('done');
    expect(stream.frames.map(f => f.type)[0]).toBe('session_created');
    expect(textOf(stream.frames).length).toBeGreaterThan(2);
    const h = await history(sid, MEM_BASE);
    expect(h._http === undefined || h._http === 200).toBe(true);

    // 文件级 beforeEach llmReset（api-core.spec.ts:16）给出干净窗口；mock 为两实例共享、全局窗口
    const stats = await llmStats();
    expect(stats.calls.some(c => c.scenario === 'plain'), '主对话未到达 mock（实例/路由错配）').toBe(true);
    // 记忆提取判据（llm-server.mjs:146-147）：无 system 或 system 含 'memory extraction assistant'
    // → background-synth。标题生成自带 system（SessionTitleService.java:55-59）归入 plain，不误伤。
    // ⚠卫生级信号：flush 走 throttled 触发（默认 10 分钟节流，application.yml:112），
    // 即便 false 分支回归，秒级窗口内大概率也不产生 background-synth（空转通过）；
    // 确定性拦截在 MEM1（工具注册）与 MEM3（分支日志）。共享窗口无来源实例字段
    // （llm-server.mjs:149-158），BASE 异步 flush 理论可落窗误红（10min 节流 vs 秒级窗口，概率低）。
    const bg = stats.calls.filter(c => c.scenario === 'background-synth');
    expect(bg, `窗口内出现 background-synth（若源于本实例即 hooks 未关，亦可能为共享窗口污染，见注释）：${JSON.stringify(bg).slice(0, 300)}`).toHaveLength(0);
    // 边界：压缩前 flush 子分支（HarnessAgentFactory.java:249）需 compact 录制件方可触发，当前无件。
  });
```

#### MEM3 关断分支装配日志直证：实例日志含 'Memory fully disabled'（else 分支唯一出口）

- **落点**：agent-framework/e2e/tests/api-core.spec.ts（MEM describe 组第 3 条）
- **blocker**：none
- **前置**：
  - MEM1 的 describe 级 beforeAll 已拉起关断实例；日志由 start-agent.sh:46 重定向至 .runtime/logs/agent-memoff.log（logback-e2e 控制台 appender，env-up.sh:55-62 渲染）
  - agent 为 @Bean 启动即装配（AgentScopeConfig.java:352 → HarnessAgentFactory.build），/health 就绪（start-agent.sh:48 wait-ready）时日志已落盘，无需任何对话触发
  - 进程级读取 .runtime 产物已有先例（api-multi-kill.spec.ts:18-32 pid 文件），非黑盒越界
- **步骤**：
  1. GET 关断实例 /health（实例存活自证）
  2. 读 .runtime/logs/agent-memoff.log 全文
- **断言**：
  - GET /health 状态 200
  - 日志包含 'Memory fully disabled'——该行仅在 memoryEnabled=false 的 else 分支打印（HarnessAgentFactory.java:237），出现即 disableMemoryHooks().disableMemoryTools() 双关代码路径真实执行；与 MEM1 形成装配级双证据（防 SDK 工具名漂移导致 MEM1 失真时仍能定位到分支语义）
- **draftCode**：

```typescript
  // （MEM describe 组内，接 MEM2）
  test('MEM3 关断分支装配日志直证（disableMemoryHooks/disableMemoryTools 分支执行）', async () => {
    const health = await fetch(`${MEM_BASE}/health`);
    expect(health.status).toBe(200);
    // 该日志行是 memoryEnabled=false else 分支的唯一可观测出口（HarnessAgentFactory.java:237），
    // agent @Bean 启动即装配（AgentScopeConfig.java:352），/health 就绪时必已落盘；
    // 日志经 start-agent.sh:46 重定向 + env-up.sh:55-62 渲染的控制台 logback 落盘
    const log = fs.readFileSync(path.join(runtimeDir, 'logs', 'agent-memoff.log'), 'utf8');
    expect(log).toContain('Memory fully disabled');
  });
});
```

#### X10（记忆面，落地时建议重编号为 X14）沙箱档记忆关断 no-op：预置用户 MEMORY.md 不注入容器（对照组经 files/download 探针可见注入）

- **落点**：agent-framework/e2e/tests/api-sandbox.spec.ts（X 组追加；需 e2e-sandbox job/env-up 编排扩展）
- **blocker**：需扩 mock（修正说明：sandbox-server.mjs 零改动——/stats(:146)、/reset(:147)、proxy files/download(:215-221) 均已具备；实际前置 = env-up.sh sandbox 分支扩第二实例（:118-121 现单实例）+ agent_fs seed 脚本（SDK jar 行编码需逆向），且 seed 必须在 env-up 之后执行）
- **前置**：
  - 【编排扩展，mock 零改动】env-up.sh sandbox 分支现只起一个实例（:104-111 起 mock、:118-121 core|sandbox 单 start_jar）——需扩第二实例：SANDBOX_ENABLED=true + AGENT_MEMORY_ENABLED=false + 独立端口（建议 BASE+10），env 透传语义同 MEM 组；run.sh sandbox 分支与 workflow e2e-sandbox job 同步。sandbox-server.mjs 本体不需要任何扩展：/stats(:146)、/reset(:147)、proxy files/download(:215-221) 均已具备，且无容量语义（每次 create 现建目录 :156-163）
  - 【数据预置通道】seed 脚本：向 agent_fs 预置该用户 KV 运行时文件 MEMORY.md（键 agents/{agent}/users/{uid}/MEMORY.md）——行编码来自 SDK jar 内 io.agentscope.harness.agent.DistributedStore（WorkspaceReader.java:14 import，仓库内无源码），需从 jar 反编译确认列名/序列化形态后实现（仿 reset-data.mjs 直连 MySQL 模式）
  - 【时序关键】seed 必须在 env-up 之后执行：env-up 第 -1 步 reset-data.mjs:18 的 TABLES 含 agent_fs 会被 DROP，先 seed 会被清掉——建议 seed 挂在用例 beforeAll 或 playwright 阶段独立步骤
  - 【观测通道（复核修正）】注入走『上传 staging(/tmp/workspace.tar.b64) + base64 -d | tar 解包』管道：录制件 14/14 个 files/upload 的 multipart 元数据 path 均为 /tmp/workspace.tar.b64（mock/fixtures/sandbox/interactions.json，上游实测提取），MEMORY.md 由 tar 命令落盘、只出现在 stats.commands——stats.fileOps 只记 upload/download 且 upload path 是 staging（sandbox-server.mjs:211-212/217-218）→ 注入探针必须用 files/download?path=/workspace/MEMORY.md（录制件原生即有该探针，对应回写链路读取）
  - 无 LLM 录制件需求：对话用 [E2E:execute]（registry 已登记 execute，calls:2）
- **步骤**：
  1. env-up 之后执行 seed：agent_fs 预置 uid 的 MEMORY.md（内容含探针串 e2a-memory-seed）
  2. 对照组：默认沙箱实例（memory 开）以 uid 新会话对话 [E2E:execute]（首次 exec 触发注入，OpenSandbox.java:234-258）→ 收敛终态
  3. 对照探针：sandboxStats().creates 取最后一个 id（workers=1 串行下即本会话沙箱，sandbox-server.mjs:156-163 每次 create 即记），GET {SANDBOX_MOCK}/v1/sandboxes/{id}/proxy/44772/files/download?path=/workspace/MEMORY.md（proxy 不校验端口 :183，sandboxPath 剥 /workspace 前缀 :40-44）
  4. POST sandbox mock /reset（清 creates 井，隔离下一个探针的 last-create 归属）
  5. 关断实例：同 uid 新会话对话 [E2E:execute] → 收敛终态
  6. 关断探针：同款 download 请求
- **断言**：
  - 对照组 turn 收敛 done
  - 对照探针 200 且 body 含 e2a-memory-seed——证明 seed 有效且注入通道可观测（对照组缺失在此红，负断言不失义）
  - 关断实例 turn 收敛 done 且 execute 工具被调用（关断不影响沙箱执行主链路）
  - 关断探针 404（容器内无 MEMORY.md = 注入 no-op；若 memoryEnabled 门控回归，readRuntimeFiles 非空 → injectToSandbox（WorkspaceReader.java:785-799）走同一 tar 管道落盘 → 探针转 200，本断言具备拦截力）
  - 两侧同 uid、workers=1 + /reset 保证探针 create 归属正确——唯一变量是 memoryEnabled
- **draftCode**：

```typescript
// api-sandbox.spec.ts 追加（X 组）。⚠骨架：依赖 preconditions 的编排扩展与 seed 脚本，落地前不可直接运行。
// 观测通道（复核修正）：注入走 staging+tar 管道，MEMORY.md 不出现在 fileOps——探针用 files/download。

/** 会话对应 sandbox id：workers=1 串行下最后一个 create 即本会话（sandbox-server.mjs:156-163） */
async function lastSandboxId(): Promise<string> {
  const s = await sandboxStats();
  return s.creates[s.creates.length - 1]?.id ?? '';
}

/** 注入探针：proxy 不校验端口（:183），sandboxPath 剥 /workspace 前缀（:40-44）；缺文件 404（:215-221） */
async function downloadMemoryProbe(sandboxId: string): Promise<{ status: number; body: string }> {
  const r = await fetch(`${SANDBOX_MOCK}/v1/sandboxes/${sandboxId}/proxy/44772/files/download?path=${encodeURIComponent('/workspace/MEMORY.md')}`);
  return { status: r.status, body: r.status === 200 ? await r.text() : '' };
}

test('X10 沙箱档记忆关断 no-op：预置 MEMORY.md 不注入容器（对照组注入可见）', async () => {
  const uid = `e2a-sbx-memoff-${uniq()}`;
  const MEMOFF_SANDBOX_BASE = process.env.E2E_MEMOFF_SANDBOX ?? 'http://127.0.0.1:8110'; // 编排扩展注入

  // ① seed（必须在 env-up 之后：reset-data.mjs:18 DROP agent_fs）：
  //    向 agent_fs 写 agents/{agent}/users/{uid}/MEMORY.md，内容含 e2a-memory-seed
  //    —— 需 seed 脚本（DistributedStore 行编码自 SDK jar 逆向，见 preconditions）
  await seedUserMemory(uid, 'e2a-memory-seed');

  // ② 对照组：默认实例（memory 开）同用户对话，首次 exec 触发注入（OpenSandbox.java:234-258）
  const sidA = sessionIdFor(`x10a-${uniq()}`);
  const ctrl = chat({ message: `[E2E:execute]`, userId: uid, sessionId: sidA });
  await waitTerminal(ctrl);
  expect(ctrl.terminal?.type).toBe('done');
  const ctrlProbe = await downloadMemoryProbe(await lastSandboxId());
  expect(ctrlProbe.status, '对照组 MEMORY.md 不可下载（seed 失效或注入通道断裂，负断言失义）').toBe(200);
  expect(ctrlProbe.body).toContain('e2a-memory-seed');

  // ③ 关断实例：同 userId（唯一变量 = memoryEnabled）；先 /reset 清 creates 井
  await fetch(`${SANDBOX_MOCK}/reset`, { method: 'POST' });
  const sidB = sessionIdFor(`x10b-${uniq()}`);
  const off = chat({ message: `[E2E:execute]`, userId: uid, sessionId: sidB, base: MEMOFF_SANDBOX_BASE });
  await waitTerminal(off);
  expect(off.terminal?.type).toBe('done');
  expect(toolNames(off.frames)).toContain('execute'); // 关断不影响沙箱执行主链路
  const offProbe = await downloadMemoryProbe(await lastSandboxId());
  expect(offProbe.status, '关断实例沙箱内可读到 MEMORY.md（WorkspaceReader memoryEnabled 门控回归）').toBe(404);
});

// 需补的 seed 脚本骨架（scripts/seed-memory.mjs，直连 MySQL 仿 reset-data.mjs 模式）：
//   INSERT INTO agent_fs（键=agents/{agent}/users/{uid}/MEMORY.md，值=<SDK DistributedStore 行编码>）
//   行编码（列名/序列化形态）仓库内无源码，需从 agentscope-harness jar 反编译确认后再实现。
```

**复核意见（评审留痕，均已修订吸收）**：
- 【X10·致命】原稿对照断言观测通道错位，健康系统上必红：fileOps 中不会出现 path 含 MEMORY.md 的条目。证据：真实录制协议 14/14 个 files/upload 的 metadata path 均为 staging 文件 /tmp/workspace.tar.b64（mock/fixtures/sandbox/interactions.json），sandbox-server.mjs:75-76 注释明示 SDK WriteEntry 走 '上传 staging + base64 -d | tar 解包' 管道；MEMORY.md 是被 tar 命令解包落盘的，只出现在 stats.commands。修复：改为 /files/download 探针（对照侧 200 含 seed、关断侧 404）。（已改）
- 【X10·致命】原稿负断言对目标回归是盲的：即使 memoryEnabled 门控回归（实际未关），注入同样走 tar staging → fileOps 形态与关断时完全一致 → 负断言恒假绿。同上改 download 探针才具备拦截力。（已改）
- 【X10·中】原稿 blocker 标注 '需扩 mock' 与事实不符：sandbox-server.mjs 无容量语义、/stats /reset /files/download 均已具备，mock 不需要任何扩展。真正前置是 (a) env-up.sh sandbox 分支扩第二实例；(b) seed 脚本（DistributedStore/agent_fs 行编码需从 SDK jar 逆向）；另缺关键时序：reset-data.mjs:18 会 DROP agent_fs，seed 必须在 env-up 之后执行。（已改）
- 【MEM1·重要】原稿 beforeAll/afterAll 放在文件顶层而非 describe 内：文件级 beforeAll 在 S1 之前执行，memoff 实例会在项目开头额外花 ~30-90s 全程驻留，且文件级 beforeAll 抛错会把该文件全部用例标失败。建议包进 test.describe 并将 hooks 收进 describe。（已改）
- 【MEM2·中】background-synth==0 的灵敏度与污染面未如实声明：(a) 若 false 分支回归，flush 走 throttled 触发（默认 10 分钟节流），秒级窗口内大概率不产生 background-synth → 负断言大概率空转通过；(b) llmStats 是共享 mock 的全局窗口且调用记录不含来源实例字段，BASE 实例的异步 flush 理论上可落窗造成误红（概率低）。建议明确：本条是卫生级信号，确定性拦截在 MEM1 与 MEM3。（已改）
- 【MEM1·轻微】原稿 off.sdkInternal 未容错：fail-soft 时 sdkInternal 可能为空数组（InternalToolRegistry.java:110-113），`.map` 会以 TypeError 失败掩盖两种故障的区分。建议先断言 Array.isArray。（已改）
- 【MEM3/X10·轻微】行号引用小幅漂移（不影响可执行性，落码时修正）：'env-up.sh:52-59 渲染 logback' 实为 :55-62；'agent_fs（reset-data.mjs:17）' 实为 :18。其余引用逐一核实无误。

### 4.6 缺口六：A2A 链路 llm-calls 记录键（sid 对齐，e5d6ff3 之 A2A 半边）（tracing）—— 3 条：A5/A6/A7

**缺口描述**：e5d6ff3 修复覆盖 Channel/A2A 两链路，但 S7 的非空断言只打 Channel（api-core.spec.ts:167-175 注释明示），A1/A2（:569-594）对 llm-calls 零断言——A2A 记录键反查回归（恒空/串桶）在 A2A 面门禁不可见，仅单测覆盖。

**复核证据要点**：
- 全量检索（上游实跑）：`grep -rn "llm-calls\|llmCalls\|llm_calls" agent-framework/e2e/tests/*.spec.ts agent-framework/e2e/scripts/plugin-smoke.sh agent-framework/e2e/lib/` 仅命中 api-core.spec.ts:167,168,173——其余 7 个 spec 与 plugin-smoke.sh 均零命中；docs/e2e-ci-plan.md 仅 :342（S7 行）提及 llm-calls，§5.8 A 组表（:431-440）A1-A4 断言列均无 llm-calls。
- S7 非空断言只打 Channel：:152 经 chat()（Channel 链路）造记录，:167-176 断言，:169-171 注释明示'Channel 链路 ctx.sessionId 是全进程共享的网关 gw-hash…这里必须断到非空'（e5d6ff3 新增，git show e5d6ff3 确认 diff 仅此一处 e2e 改动）。
- A1（:569-580）只断 200/result/error，A2（:582-598）只断 200 + text.length>10，对 llm-calls 零断言。
- '仅单测覆盖'成立：LlmLoggingMiddlewareTest.java:189 shouldPreferSessionIdOnA2ALink（mock SessionUserStore 验证 A2A 链路记录键优先 sessionId）。
- 端点与语义核实：ThreadController.java:354-363 GET /threads/{sessionId}/llm-calls 无存在性校验、回显 session_id；A2AController.java:38,55 POST "/" 透传 message/send|stream，:197-226 recordSessionUser 先按 metadata sessionId upsert session_user（PathSafe.sanitize），:174-189 convertToSse→ServerSentEvent；SessionKeyResolver.java:23-31 链路表（:28 A2A 行=调用方 sid 原值）；HarnessAgentFactory.java:146,175 两中间件装配对 A2A/Channel 共用。
- 用例可执行性核实：mock 标记路由为包含式正则（mock/llm-server.mjs:80-85），`[E2E:plain] A6A-xxx` 仍路由 plain；registry.json llm.plain={marker:'[E2E:plain]',calls:1}，check-fixtures 实跑 `node scripts/check-fixtures.mjs` → 'OK（llm=17 场景，sandbox=1 文件）' exit=0，calls:1 是录制件最少调用数（check-fixtures.mjs:27 为 >= 比较），新增用例不触红；e2e-core job 有 mysql/redis 服务（agent-framework-ci.yml:78-96）并先跑 check-fixtures 再 `./scripts/run.sh core`；a2a helper 在 lib/client.ts:94，pollUntil(lib/matchers.ts:25) 默认 30s/1s；sessionIdFor（lib/env.ts:41）='e2e_<runId>-<case>' 仅含字母数字下划线连字符，PathSafe.sanitize（PathSafe.java:15-16,30-38）对之为恒等；LLMLogger.java:27,28 call_id 'call-' 前缀/timestamp 毫秒；LlmLoggingMiddleware.java:63-66 usage 三键恒写（null→0）、:98-110 记录 {role,content}、:79-91 解析不出时落 'global'。A5/A6/A7 编号与 a5-/a6-/a7- 会话前缀全库无冲突；三用例仅用 request fixture，放 api-core（e2e-core job）正确。未实跑用例本身（本机 :8100 无运行实例）；实跑的检查为上述 grep/git show/check-fixtures。

#### A5 A2A message/stream 后 llm-calls 按 metadata sessionId 反查非空（记录键=调用方 sid + 记录契约）

- **落点**：agent-framework/e2e/tests/api-core.spec.ts（A 组：A3/A4 之后、SK 组之前）
- **blocker**：none
- **前置**：
  - e2e-core job 环境就绪（env-up.sh：agent :8100 + MySQL + Redis + mock LLM:18081 + 双 mock MCP；A2AController 的 session_user upsert 依赖 MySQL）
  - 复用 plain 夹具：mock/fixtures/registry.json llm.plain（marker [E2E:plain]，calls:1）已登记，无新增录制件/mock 扩展（check-fixtures 实跑 OK：llm=17 场景）
  - 服务端契约依据（静态核对，本机 :8100/:18081 无运行实例、未实跑用例）：SessionKeyResolver.java:28 A2A 链路 ctx.sessionId 即调用方规范 sid；A2AController.java:221-226 先按 metadata sessionId upsert session_user；ThreadController.java:354-361 回显 session_id
- **步骤**：
  1. POST / 发 JSON-RPC message/stream：message.parts=[{kind:'text',text:'[E2E:plain]'}]，message.metadata={userId:'e2e-tester', sessionId: sessionIdFor('a5-<uniq>')}（A2 同款请求形态，A2AController normalize 后 blocking=true）
  2. 读完整 SSE 响应体作为完成 barrier（blocking 流读完即任务终态，ModelCallEndEvent 落记录先于流关闭）
  3. GET /threads/{sid}/llm-calls 轮询直至 calls 非空
- **断言**：
  - POST / → 200，SSE 体含 'data:' 行（A2AController.convertToSse → ServerSentEvent）
  - llm-calls → 200；body.session_id === 查询 sid（记录键口径=规范会话 id，docs/api.md llm-calls 节）
  - calls.length ≥ 1；calls[0].call_id 含 'call-' 前缀；calls[0].timestamp > 0
  - calls[0].request.messages 存在 role='user' 且 content 含 '[E2E:plain]'（A2A 文本 part 原样进模型输入；记录的是输入消息，不含 assistant 输出）
  - calls[0].response.usage 的 input_tokens/output_tokens/total_tokens 三键存在且为数值（LlmLoggingMiddleware.java:63-66 恒写三键，值可为 0，不断言 >0 防 usage 缺失 flake）
  - 回归语义（红路=恒空→超时）：若 A2A 记录键解析回归到共享桶（gw-hash/"global"）或其它不可按 sid 反查的键，按 sid 精确查询与末段归一回退（LLMLogger.java:43-49）均未命中 → 恒返回 [] → pollUntil 超时红，错误消息携带最后观测 calls:[]，与单测 LlmLoggingMiddlewareTest#shouldPreferSessionIdOnA2ALink 同判据黑盒化
- **draftCode**：

```typescript
// A5（e5d6ff3 回归门禁·A2A 面）：S7 只钉了 Channel 链路的 llm-calls 反查，
// A2A 链路此前零断言——SessionKeyResolver 在 A2A 下应把记录落在调用方 metadata.sessionId
//（SessionKeyResolver.java 链路表；单测 LlmLoggingMiddlewareTest#shouldPreferSessionIdOnA2ALink），
// 解析回归（记录落共享桶/别的键 → 按 sid 查询恒空）在门禁不可见。复用 plain 夹具，无 mock 扩展。
test('A5 A2A message/stream 后 llm-calls 按 metadata sessionId 反查非空', async ({ request }) => {
  const sid = sessionIdFor(`a5-${uniq()}`);
  const res = await request.post('/', {
    data: {
      jsonrpc: '2.0', id: 5, method: 'message/stream',
      params: {
        message: {
          kind: 'message', messageId: crypto.randomUUID(), role: 'user', blocking: true,
          parts: [{ kind: 'text', text: `[E2E:plain]` }],
          metadata: { userId: U, sessionId: sid },
        },
      },
    },
  });
  expect(res.status()).toBe(200);
  const text = await res.text(); // blocking：流读完即任务终态（A2 同款完成 barrier）
  expect(text).toContain('data:'); // SSE 形态（A2AController.convertToSse → ServerSentEvent）

  type LlmCallsBody = {
    session_id: string;
    calls: Array<{
      call_id: string; timestamp: number;
      request: { messages: Array<{ role: string; content: string }> };
      response: { usage: Record<string, number> };
    }>;
  };
  const body = await pollUntil(
    async () => (await request.get(`/threads/${sid}/llm-calls`)).json() as LlmCallsBody,
    b => (b.calls?.length ?? 0) > 0,
  );
  // 回显查询键：记录键口径 = 规范会话 id（docs/api.md llm-calls 节）
  expect(body.session_id).toBe(sid);
  const call = body.calls[0];
  expect(String(call.call_id)).toContain('call-');
  expect(Number(call.timestamp)).toBeGreaterThan(0);
  // 记录的是模型输入消息：A2A 文本 part 原样进 user 消息
  expect(call.request.messages.some(m => m.role === 'user' && m.content.includes('[E2E:plain]'))).toBe(true);
  // usage 契约（LlmLoggingMiddleware：三键恒在，数值可为 0）
  for (const k of ['input_tokens', 'output_tokens', 'total_tokens']) {
    expect(Number(call.response.usage[k]), `usage.${k}`).toBeGreaterThanOrEqual(0);
  }
});
```

#### A6 双 A2A 会话（message/send）llm-calls 互查不串桶（数据正确性）

- **落点**：agent-framework/e2e/tests/api-core.spec.ts（A 组：A3/A4 之后、SK 组之前）
- **blocker**：none
- **前置**：
  - e2e-core job 环境就绪（同 A5；message/send 走 A1 同款面，a2a helper lib/client.ts:94）
  - 复用 plain 夹具（registry.json llm.plain calls:1），无新增录制件/mock 扩展
  - 双判据成立前提：两 sid 末段（a6a-/a6b- + run 内 uniq）互异——① 共享桶/错键回归下按 sid 查询的末段归一回退（LLMLogger.java:43-49、54-60）不命中任何共享键，恒空、pollUntil 超时红；② 串写形态下负向交叉 token 断言不被归一回退误伤；PathSafe.sanitize 对 sessionIdFor 形态（字母数字下划线连字符）为恒等变换，session_user 登记键与查询键一致
- **步骤**：
  1. 构造 run 内唯一 token：tokenA=`A6A-<uniq>`、tokenB=`A6B-<uniq>`，两个独立 sid：sessionIdFor('a6a-<uniq>')、sessionIdFor('a6b-<uniq>')
  2. 顺序 POST / JSON-RPC message/send（blocking）各一次：text=`[E2E:plain] <token>`，metadata={userId, sessionId}（标记决定 mock 路由，token 仅作记录内容指纹）
  3. 两轮完成后分别 GET /threads/{sidA}/llm-calls、/threads/{sidB}/llm-calls 轮询至各自非空（判据①在此承担：不可反查的回归形态红在超时）
  4. 交叉断言两侧记录内容归属（判据②：串写进仍可按 sid 反查到的桶的形态红在此）
- **断言**：
  - 两次 message/send → 200 且 json.error 为 undefined（A1 同款基线）
  - 判据①（共享桶/错键形态的红路）：sidA、sidB 各自 llm-calls → 200 且 calls 非空——若记录退化到共享桶（gw-hash/"global"/退化为 userId 派生键）或互换/错路由到对方会话键，按 sid 精确查询与末段归一回退（LLMLogger.java:43-49）均未命中，恒返回 []，callsOf 的 pollUntil 超时红（错误消息含最后观测 calls:[]，排障入口即此）
  - bodyA.session_id === sidA、bodyB.session_id === sidB（回显查询键）
  - 判据②（串写进可反查桶形态的红路）：JSON.stringify(bodyA) 含 tokenA 且不含 tokenB；bodyB 含 tokenB 且不含 tokenA——双写、归一末段碰撞、回退命中对方桶等串写回归下第 2 条非空收敛已通过，红在此交叉 token 断言
- **draftCode**：

```typescript
// A6（e5d6ff3 串桶回归·A2A 面，数据正确性）：两个 A2A 会话（message/send，A1 同款面）各带
// run 内唯一 token，互查双方记录。两道判据对应两类回归形态：
// ① 记录退化到共享桶（gw-hash/"global"）或互换/错路由到对方会话键——按 sid 精确查询与
//    末段归一回退（LLMLogger.java:43-49）均未命中，GET 恒返回 []，红在 callsOf 的
//    pollUntil 超时（超时消息携带最后观测 calls:[]）；
// ② 串写进仍可按 sid 反查到的桶（双写/归一末段碰撞/回退命中对方桶）——非空收敛通过，
//    红在交叉 token 断言（not.toContain 对方 token）。
// 两 sid 末段（a6a-/a6b- + uniq）互异（LLMLogger.java:54-60 归一规则）：既保证①不发生
// 回退误命中，也保证②的负向断言不被回退误伤。
test('A6 双 A2A 会话 llm-calls 互查不串桶', async ({ request }) => {
  const sidA = sessionIdFor(`a6a-${uniq()}`);
  const sidB = sessionIdFor(`a6b-${uniq()}`);
  const tokenA = `A6A-${uniq()}`;
  const tokenB = `A6B-${uniq()}`;
  const send = (sid: string, token: string) => a2a('message/send', {
    message: {
      kind: 'message', messageId: crypto.randomUUID(), role: 'user', blocking: true,
      parts: [{ kind: 'text', text: `[E2E:plain] ${token}` }],
      metadata: { userId: U, sessionId: sid },
    },
  });
  for (const [sid, token] of [[sidA, tokenA], [sidB, tokenB]] as const) {
    const r = await send(sid, token);
    expect(r.status).toBe(200);
    expect(r.json.error).toBeUndefined();
  }
  const callsOf = (sid: string) => pollUntil(
    async () => (await request.get(`/threads/${sid}/llm-calls`)).json() as { session_id: string; calls: unknown[] },
    b => (b.calls?.length ?? 0) > 0,
  );
  const bodyA = await callsOf(sidA); // 判据①：共享桶/错键形态在此超时红
  const bodyB = await callsOf(sidB);
  expect(bodyA.session_id).toBe(sidA);
  expect(bodyB.session_id).toBe(sidB);
  expect(JSON.stringify(bodyA)).toContain(tokenA);
  expect(JSON.stringify(bodyA), 'B 的内容落进了 A 的桶').not.toContain(tokenB); // 判据②
  expect(JSON.stringify(bodyB)).toContain(tokenB);
  expect(JSON.stringify(bodyB), 'A 的内容落进了 B 的桶').not.toContain(tokenA); // 判据②
});
```

#### A7 llm-calls 未知会话恒空（查询契约负例，无 LLM 依赖）

- **落点**：agent-framework/e2e/tests/api-core.spec.ts（A 组：A3/A4 之后、SK 组之前）
- **blocker**：none
- **前置**：
  - e2e-core job 环境就绪即可，不发起对话、不依赖任何 LLM 夹具（MOD 组风格的直接 HTTP 契约探针）
  - 静态依据：ThreadController.java:354-361 无会话存在性校验，未知 sid 固定 200 + {session_id 回显, calls:[]}（LLMLogger.getCalls 未命中返回 List.of()）
  - ghost sid 取 sessionIdFor('a7-<uniq>')：run 内唯一末段，不会被 LLMLogger 归一回退（末段匹配，LLMLogger.java:43-49）误命中任何已有桶
- **步骤**：
  1. GET /threads/{sessionIdFor('a7-<uniq>')}/llm-calls（该会话从未运行过）
  2. 解析 JSON 响应体
- **断言**：
  - HTTP 200（端点契约：未知会话不是 404/500）
  - body.session_id === 查询的 ghost sid（回显而非倾倒其它会话键）
  - body.calls 深等于 []——给 A5/A6 的"非空"锚定意义：若 getCalls 退化为全局倾倒/串桶返回，在此红
- **draftCode**：

```typescript
// A7（查询契约负例·无 LLM，MOD 组风格）：未运行过的会话查 llm-calls 恒空——为 A5/A6 的
// "非空"锚定意义：端点不得对任意 sid 全局倾倒记录（LLMLogger.getCalls 退化为全量返回时在此红）。
// ThreadController 无会话存在性校验，未知 sid 固定 200 + 空数组（ThreadController.java:354）。
test('A7 llm-calls 未知会话恒空（查询契约）', async ({ request }) => {
  const ghost = sessionIdFor(`a7-${uniq()}`);
  const res = await request.get(`/threads/${ghost}/llm-calls`);
  expect(res.status()).toBe(200);
  const body = await res.json() as { session_id: string; calls: unknown[] };
  expect(body.session_id).toBe(ghost);
  expect(body.calls).toEqual([]);
});
```

**复核意见（评审留痕，已修订吸收）**：
- A6 原稿的串桶红路描述与实际判据不符：声称'若记录键解析退化到共享桶，任一侧会同时含两个 token 即红'。实际路径：LLMLogger.getCalls 未命中时按末段归一匹配（LLMLogger.java:44-49、54-60），共享桶键与 a6a-/a6b- sid 的归一末段互不相等，共享桶回归下 GET 恒返回 []，红在 pollUntil 超时而非交叉 token；交叉 token 断言只在'记录串写到对方会话键'这一形态触发。用例检测力不受损（两类回归都变红），但该注释会合入 spec 并把排障者引向错误的失败形态（此仓库 spec 注释即设计文档，如 S7 api-core.spec.ts:169-171），已改为双判据表述：共享桶→按 sid 恒空、pollUntil 超时红；串写到对方会话键→交叉 token 断言红。（已改）

### 4.7 缺口七：OTel span 内容属性（gen_ai.input/output.messages、tool.call.arguments/result 与 8192 截断，3a9256a）（tracing）—— 3 条：TR1/TR2/TR3

**缺口描述**：3a9256a 的 chat/execute_tool span 内容属性与 8192 截断只有 16 个单测，e2e 层无任何 span 断言；超长 prompt 撑爆属性或取消路径静默缺属性均不可见。

**复核证据要点**：
- 16 单测属实：git show --stat 3a9256a → 仅新增 ModelIoTracingMiddlewareTest.java 与 ToolCallTracingMiddlewareTest.java；grep -c '@Test|@ParameterizedTest' 得 6 + 10 = 16（ModelIoTracingMiddlewareTest.java:64,108,122,140,153,165 逐一核对）。
- e2e 层零 span 断言：grep -rn -iE 'span|otlp|otel|gen_ai|truncat' 全部 8 个 spec、plugin-smoke.sh、run.sh、e2e-ci-plan.md、lib/*.ts、mock/llm-server.mjs、mock/sandbox-server.mjs、playwright.config.ts → 唯一命中是 ui.spec.ts:214,215,233,378,381 的 userIndexTruncated（用户索引下拉截断，与 span 截断无关）。
- 机制根因：grep 'OTEL|otlp' e2e/scripts 零命中；start-agent.sh:36-46 java 启动环境无任何 OTEL_*；application.yml:129-136 otel.traces.exporter 默认 none；OtelConfig.java:21 @ConditionalOnProperty(otel.traces.exporter=otlp) 不激活 → e2e 实例不产 span、不导出，内容属性/8192 截断/取消路径静默缺属性在 e2e 全部不可观测。结论成立。
- 可执行性核心前提全部核实（上游评审）：标记路由不破——llm-server.mjs:82 标记正则对 '[E2E:plain]'+20000 X 仍命中；ChatStreamController.java:240-242 服务端只校验 message 非空、无长度上限，20011 字符可入。夹具与断言对齐——registry tool-echo calls=2、plain calls=1；tool-echo.json call[0] 参数增量拼出 {"text":"端到端回显内容"}、call[1] 正文 'echo 工具已成功回显内容："端到端回显内容"。'；textOf 与 ModelIoTracingMiddleware.java:89-92 同一事件源；BusinessTools.java:34-40；ToolResultState.SUCCESS.getValue()='success' 小写（agentscope-core-2.0.3.jar javap 字节码实查）；span 命名 chat <model>/execute_tool <name>（同 jar OtelTracingMiddleware.class javap：gen_ai.operation.name + tracing-design.md §二表）。黑盒可见面——ModelIoTracingMiddleware.java:81-84 属性写入、:116-131 renderInput 全量 messages、:134-159 renderBlocks 的 [tool_call X]/[tool_result X] 标注、:200-201 截断格式 '...(truncated, total N chars)'；ToolCallTracingMiddleware.java:60-61/:107-111/:150-169/:172-184；SSE TOOL_CALL_START 带 toolCallId（AgentEventSseSerializer.java:65-66）。会话/用户键——ChatStreamController.java:282 turn 前 upsert；SessionKeyResolver.java:71-91 Channel 链路 sid=gw-hash 落空→peer(前端 sid) 命中→(前端 sid, 真实 user)；FrameworkTracingMiddleware.java:87-98 写键——TR1 正是 e5d6ff3 的 e2e 直证。无杂散 chat span——后台标题走 TracingModelWrapper('title')（AgentScopeConfig.java:328-332）、memory/compaction 同（HarnessAgentFactory.java:120,225），TracingModelWrapper.java:54-57 无 gen_ai.input.messages 属性 → spansOf 前缀+属性双条件干净排除。OTLP 方案可行——OtelConfig.java:31-32 OtlpHttpSpanExporter endpoint+'/v1/traces'（纯 HTTP protobuf，无需 gRPC/容器）；application.yml:128-136 环境变量映射；OTLP 激活连带 HttpTracingFilter 产生 http span（前缀过滤排除）；BatchSpanProcessor 默认 5s 批量导出，pollUntil（默认 30s/1s）覆盖；e2e/package.json 仅 @playwright/test，手写线格式解码不引新依赖。编排模式先例——env-up.sh:64-74 mock 拉起+pid+wait-ready、:143 env.json 写 mock 地址；lib/env.ts:30 环境变量回退、lib/client.ts:103-113 llmStats；S5 api-core.spec.ts:126-132 'mock 侧实收'+pollUntil 先例。placement/冲突——api-core project 仅匹配 api-core.spec.ts → 只进 e2e-core job；现有组无 TR、grep 'TR1|TR2|TR3|otlp|14318' e2e 全目录零命中；风格贴合现有 spec。评审结论：三个用例机制性前提全部核实为真，断言与源码/夹具/字节码一致；3 个需修正问题（beforeEach 作用域/TR3 正则/文档同步）已修订吸收。

#### TR1 chat span 内容属性：gen_ai.input/output.messages 全量直证 + 规范会话键（e5d6ff3）

- **落点**：agent-framework/e2e/tests/api-core.spec.ts 末尾新增「TR 组：OTel span 内容属性」describe 块（api-core project → CI e2e-core job，run.sh/playwright.config 无需改）；同一变更内同步 docs：docs/e2e-ci-plan.md §2.3 job 表（:106）措辞补 TR + §5 新增 5.10 TR 小节 + api-core.spec.ts:2 头注补 TR 列名
- **blocker**：需扩 mock：三用例共用同一组前置——mock/otlp-receiver.mjs（含 OTLP protobuf 最小线格式解码）、env-up.sh/start-agent.sh 的 OTEL 环境注入、lib/otlp.ts helper，以及同变更内的文档同步（e2e-ci-plan.md §2.3 job 表 :106 / §5 新增 TR 小节 / api-core.spec.ts:2 头注）。无需新 LLM 录制件，check-fixtures.mjs 与 registry.json 不感知；beforeEach 已按评审修订收进 describe 作用域，receiver 缺席只红 TR 组。draftCode 为可运行风格骨架，import 的 lib/otlp.js 随 mock 一并落地后即可跑
- **前置**：
  - 新增 mock/otlp-receiver.mjs（node http，无新 npm 依赖）：监听 :14318；POST /v1/traces 收 OTLP/HTTP protobuf（application/x-protobuf，OtelConfig.java:31-32 用 OtlpHttpSpanExporter 且拼 /v1/traces——故无需容器/无需 gRPC），最小线格式解码 ExportTraceServiceRequest→ResourceSpans→ScopeSpans→Span 后累积内存；GET /spans 返回 {count,spans:[{name,traceId,spanId,parentSpanId,attributes}]}（attributes 为扁平 key→string|number|boolean map，bytes→hex）；POST /reset；GET /health
  - env-up.sh 拉起 receiver 并 wait-ready，env.json 写 otlpMock、export E2E_OTLP_MOCK（llm mock 同款模式）；start-agent.sh 注入 OTEL_TRACES_EXPORTER=otlp 与 OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:14318（Spring relaxed binding → OtelConfig @ConditionalOnProperty，OtelConfig.java:21,26）——当前 e2e 未注入任何 OTEL_*（start-agent.sh:36-46），这是全组用例的前置缺口
  - 新增 lib/otlp.ts：OTLP 常量（E2E_OTLP_MOCK ?? http://127.0.0.1:14318）、otlpReset()、otlpSpans()、OtlpSpan 类型（仿 lib/client.ts llmStats）
  - 作用域约束（评审修订）：otlpReset 的 beforeEach 收进 TR 组自己的 test.describe 内（仓库先例 api-reload.spec.ts:27 describe.serial / api-multi.spec.ts:17 describe.configure；api-core.spec.ts:16 的文件级 llmReset 钩子保持全文件唯一，不得并列第二个文件级 beforeEach），保证 receiver 缺席只红 TR 组
  - 文档同步（评审修订，同变更内）：docs/e2e-ci-plan.md §2.3 job 表（:106）措辞 S/F/H/M/A → S/F/H/M/A/TR；§5 测试矩阵新增 5.10 TR 小节（SK 组已在 api-core.spec.ts:606-634 落地而 §2.3/§5 均未登记是前车之鉴）；api-core.spec.ts:2 头注补 TR 列名
  - 无需新 LLM 录制件：复用 tool-echo 夹具（registry.json tool-echo calls=2）
  - OTLP 激活会连带注册 HttpTracingFilter（HttpTracingFilter.java:27）产生 http server span，断言按 name 前缀+属性过滤不受干扰；BatchSpanProcessor 默认 5s 批量导出，断言一律 pollUntil
- **步骤**：
  1. POST /threads/chat（SSE）：message='[E2E:tool:echo](tr1-<uniq>)'、sessionId=sessionIdFor('tr1-<uniq>')、userId=e2e-tester，收流至终态
  2. 轮询 GET http://127.0.0.1:14318/spans（receiver 观测面，S5 的 BENCH_MCP/last-call 同款 mock 侧实收模式），直至该 sessionId 下 name 以 'chat ' 开头且带 gen_ai.output.messages 属性的 span 数 ≥2
- **断言**：
  - 收流终态 stream.terminal.type === 'done'
  - receiver 中 agentscope.session.id === sid 且带 gen_ai.output.messages 的 'chat ' 前缀 span ≥2（tool-echo 夹具两次模型调用，registry.json tool-echo calls=2）
  - 每个 chat span 均带 agentscope.session.id === sid 且 agentscope.user.id === 'e2e-tester'（FrameworkTracingMiddleware.java:87-98 + SessionKeyResolver：Channel 链路必须解析为业务 sid 而非全进程共享 gw-hash——e5d6ff3 span 半边的 e2e 直证）
  - 首轮 span（output 含 tool_calls）：gen_ai.input.messages 可 JSON.parse 为数组，含 role='system' 且 content 非空项、role='user' 且 content 含 '[E2E:tool:echo]' 项（全量 messages 直出）；gen_ai.output.messages[0].role='assistant' 且 tool_calls[0].name='echo'
  - 终轮 span：input 属性含 '[tool_call echo]' 与 '[tool_result echo]' 标注（renderBlocks 契约）；output[0].content 非空且被 SSE textOf(stream.frames) 包含（span 出参与 SSE 正文同源）
  - 两个 span 的 input/output 属性均不含 '(truncated, total '（短会话不误截断，截断契约归 TR3）
- **draftCode**：

```typescript
// 追加到 agent-framework/e2e/tests/api-core.spec.ts 末尾（imports 并入文件头：
//   import { otlpReset, otlpSpans, type OtlpSpan } from '../lib/otlp.js';）
// ---------- TR 组：OTel span 内容属性（3a9256a 补录 / e5d6ff3 会话键同源的 e2e 直证） ----------
// 作用域说明（评审修订）：otlpReset 的 beforeEach 必须收在 describe 内——文件级 beforeEach 会
// 累积到 S/F/H/M/A/SK 全部既有用例（api-core.spec.ts:16 全文件唯一共享钩子是仓库惯例），
// receiver 未启动时（本地直跑 playwright / 旧版 env-up）只能让 TR 组红，不能拖垮整个 api-core。
// 观测面 = mock/otlp-receiver.mjs（OTLP/HTTP protobuf 最小解码），与 S5 的 BENCH_MCP/last-call
// 同为"mock 侧实收"断言模式（api-core.spec.ts:127-132 先例）。编排前置（blocker=需扩 mock）：
//   - mock/otlp-receiver.mjs：POST /v1/traces 累积 span（无新 npm 依赖的线格式解码）；
//     GET /spans → {count,spans:[{name,traceId,spanId,parentSpanId,attributes}]}；POST /reset；GET /health
//   - env-up.sh 拉起 receiver（:14318）+ start-agent.sh 注入 OTEL_TRACES_EXPORTER=otlp /
//     OTEL_EXPORTER_OTLP_ENDPOINT（OtelConfig.java:21 @ConditionalOnProperty 激活条件；
//     当前 e2e 实例 start-agent.sh:36-46 未注入任何 OTEL_* → 全组无 span，本组用例不可跑）
//   - lib/otlp.ts：otlpReset/otlpSpans + OtlpSpan 类型（llmStats 同款观测 helper 先例）
// 文档同步（同一变更内）：docs/e2e-ci-plan.md §2.3 job 表（:106）措辞补 TR、§5 新增 TR 小节、
// api-core.spec.ts:2 头注补 TR 列名——SK 组已落地而 §2.3/§5 未登记是前车之鉴。
// 无需新 LLM 录制件：复用 tool-echo / plain 夹具，registry.json 与 check-fixtures.mjs 不感知。

test.describe('OTel span 内容属性（TR 组）', () => {
  test.beforeEach(async () => { await otlpReset(); });

  /** 轮询该会话下 name 前缀匹配且带目标属性的 span（BatchSpanProcessor 默认 5s 批量导出，必须轮询） */
  async function spansOf(sid: string, namePrefix: string, attrKey: string, min = 1): Promise<OtlpSpan[]> {
    return pollUntil<OtlpSpan[]>(
      async () => (await otlpSpans()).spans.filter(s =>
        s.name.startsWith(namePrefix)
        && String(s.attributes['agentscope.session.id'] ?? '') === sid
        && s.attributes[attrKey] !== undefined),
      spans => spans.length >= min,
    );
  }

  test('TR1 chat span 内容属性：input/output messages 全量直证 + 规范会话键', async () => {
    const sid = sessionIdFor(`tr1-${uniq()}`);
    const stream = chat({ message: `[E2E:tool:echo](tr1-${uniq()})`, userId: U, sessionId: sid });
    await waitTerminal(stream);
    expect(stream.terminal?.type).toBe('done');

    // tool-echo 夹具 2 次模型调用 → 2 个 chat span（SDK 命名 "chat <model>"，tracing-design.md §二 已核实字节码）
    const chatSpans = await spansOf(sid, 'chat ', 'gen_ai.output.messages', 2);
    // 会话/用户键同源（e5d6ff3）：业务 sid 而非全进程共享的网关 gw-hash、真实 userId 而非 peer
    expect(chatSpans.every(s => s.attributes['agentscope.session.id'] === sid)).toBe(true);
    expect(chatSpans.every(s => s.attributes['agentscope.user.id'] === U)).toBe(true);

    const inputOf = (s: OtlpSpan) => JSON.parse(String(s.attributes['gen_ai.input.messages'])) as Array<{ role: string; content: string }>;
    const outputOf = (s: OtlpSpan) => JSON.parse(String(s.attributes['gen_ai.output.messages'])) as
      Array<{ role: string; content: string; tool_calls?: Array<{ name: string; arguments: string }> }>;

    // 首轮：全量输入（system+user），user 正文携带场景标记；输出为工具调用增量
    const first = chatSpans.find(s => (outputOf(s)[0]?.tool_calls?.length ?? 0) > 0);
    expect(first, '首轮 chat span 缺 tool_calls 输出').toBeTruthy();
    expect(inputOf(first!).some(m => m.role === 'system' && m.content.length > 0)).toBe(true);
    expect(inputOf(first!).some(m => m.role === 'user' && m.content.includes('[E2E:tool:echo]'))).toBe(true);
    expect(outputOf(first!)[0].role).toBe('assistant');
    expect(outputOf(first!)[0].tool_calls![0].name).toBe('echo');

    // 终轮：输入渲染含工具调用/结果标注（renderBlocks 契约），输出为正文且与 SSE 同源
    const last = chatSpans.find(s => s !== first)!;
    const lastInput = String(last.attributes['gen_ai.input.messages']);
    expect(lastInput).toContain('[tool_call echo]');
    expect(lastInput).toContain('[tool_result echo]');
    const lastOutput = outputOf(last)[0];
    expect(lastOutput.content.length).toBeGreaterThan(0);
    expect(textOf(stream.frames)).toContain(lastOutput.content);
    // 短会话不应误截断（截断契约见 TR3）
    expect(String(last.attributes['gen_ai.output.messages'])).not.toContain('(truncated, total ');
  });

  // TR2 / TR3 同落于此 describe 内（见各自 draftCode），describe 闭合括号在 TR3 之后
});
```

#### TR2 execute_tool span 内容属性：gen_ai.tool.call.arguments/result 结构 + 与 SSE toolCallId 同源

- **落点**：agent-framework/e2e/tests/api-core.spec.ts TR 组 describe 内、TR1 之后（api-core project → CI e2e-core job）；文档同步同 TR1
- **blocker**：需扩 mock：同 TR1（mock/otlp-receiver.mjs + OTEL 环境注入 + lib/otlp.ts + 同变更文档同步）。无需新 LLM 录制件
- **前置**：
  - 同 TR1：mock/otlp-receiver.mjs（OTLP/HTTP protobuf 解码 + GET /spans + POST /reset，:14318）
  - 同 TR1：env-up.sh 拉起 receiver + start-agent.sh 注入 OTEL_TRACES_EXPORTER=otlp / OTEL_EXPORTER_OTLP_ENDPOINT
  - 同 TR1：lib/otlp.ts（otlpReset/otlpSpans/OtlpSpan）；共享 helper spansOf 与 otlpReset beforeEach 均收在 TR 组 describe 作用域内
  - 文档同步：同 TR1
  - 复用 tool-echo 夹具（无需新录制件）；echo 工具为内置 @Tool bean（BusinessTools.java:34-40，description="Echo back the input text. Useful for testing tool invocation."，返回 "echo: " + text）
- **步骤**：
  1. POST /threads/chat（SSE）：message='[E2E:tool:echo](tr2-<uniq>)'、sessionId=sessionIdFor('tr2-<uniq>')、userId=e2e-tester，收流至终态并确认 SSE 出现 toolName='echo' 的 TOOL_CALL_START 帧
  2. 轮询 GET /spans 直至该 sessionId 下 name 以 'execute_tool ' 开头且带 gen_ai.tool.call.arguments 属性的 span 出现
- **断言**：
  - stream 终态 done，且 toolNames(stream.frames) 含 'echo'
  - execute_tool span 存在：name 前缀 'execute_tool '（OtelTracingMiddleware 字节码实查 makeConcatWithConstants('execute_tool ' + toolName)），agentscope.session.id === sid
  - gen_ai.tool.call.arguments 反序列化后为长度 1 数组：[0].name='echo'；[0].arguments 反序列化 === {text:'端到端回显内容'}（tool-echo 夹具固定实参）；[0].description 含 'Echo back'（BusinessTools.java:35 @Tool description——ToolUseBlock 本身不携带描述，此断言即 ToolSchema 按名缓存补齐直证，ToolCallTracingMiddleware.java:73-86）
  - gen_ai.tool.call.result 反序列化后为长度 1 数组：[0].name='echo'；[0].state='success'（ToolResultState.SUCCESS.getValue() 小写，字节码实查，与 /history 口径一致）；[0].output 含 'echo: 端到端回显内容'（BusinessTools.java:40）
  - 同批归并：results[0].id === args[0].id
  - 跨面同源：args[0].id === SSE TOOL_CALL_START(toolName=echo) 帧的 toolCallId（span 属性与 SSE 帧出自同一 tool call id）
- **draftCode**：

```typescript
// 落在 TR1 草稿的 test.describe('OTel span 内容属性（TR 组）') 内、TR1 之后（共享 beforeEach/spansOf 见 TR1）
  test('TR2 execute_tool span 内容属性：arguments/result 结构 + 与 SSE toolCallId 同源', async () => {
    const sid = sessionIdFor(`tr2-${uniq()}`);
    const stream = chat({ message: `[E2E:tool:echo](tr2-${uniq()})`, userId: U, sessionId: sid });
    await waitTerminal(stream);
    expect(toolNames(stream.frames)).toContain('echo');

    const span = (await spansOf(sid, 'execute_tool ', 'gen_ai.tool.call.arguments'))[0];
    expect(String(span.attributes['agentscope.session.id'])).toBe(sid);

    // 入参 [{id,name,description,arguments}]：描述由 onModelCall 的 ToolSchema 按名补齐（ToolUseBlock 不携带）
    const args = JSON.parse(String(span.attributes['gen_ai.tool.call.arguments'])) as
      Array<{ id: string; name: string; description: string | null; arguments: string }>;
    expect(args).toHaveLength(1);
    expect(args[0].name).toBe('echo');
    expect(JSON.parse(args[0].arguments)).toEqual({ text: '端到端回显内容' }); // tool-echo 夹具固定实参
    expect(String(args[0].description)).toContain('Echo back'); // BusinessTools.echo @Tool description

    // 出参 [{id,name,state,output}]：同批归并同一 toolCallId；state 为 ToolResultState.getValue() 小写口径
    const results = JSON.parse(String(span.attributes['gen_ai.tool.call.result'])) as
      Array<{ id: string; name: string; state: string; output: string }>;
    expect(results).toHaveLength(1);
    expect(results[0].name).toBe('echo');
    expect(results[0].state).toBe('success');
    expect(results[0].output).toContain('echo: 端到端回显内容'); // BusinessTools.echo 返回 "echo: " + text
    expect(results[0].id).toBe(args[0].id);

    // 与 SSE 同源：TOOL_CALL_START.toolCallId === span 属性 id
    const sse = stream.frames.find(f => f.type === 'TOOL_CALL_START' && f.toolName === 'echo') as Record<string, unknown> | undefined;
    expect(String(sse?.toolCallId ?? '')).not.toBe('');
    expect(args[0].id).toBe(sse!.toolCallId);
  });
```

#### TR3 8192 截断契约：超长 prompt 截断保留前缀并标注原始总长（MAX_CONTENT_CHARS 黑盒直证）

- **落点**：agent-framework/e2e/tests/api-core.spec.ts TR 组 describe 内、TR2 之后（describe 于此闭合；api-core project → CI e2e-core job）；文档同步同 TR1
- **blocker**：需扩 mock：同 TR1（mock/otlp-receiver.mjs + OTEL 环境注入 + lib/otlp.ts + 同变更文档同步）。无需新 LLM 录制件。另注：缺口提到的「取消路径静默缺属性」未设计用例——该行为是文档化的已知边界（ModelIoTracingMiddleware.java:44-45：外层 Otel 先 end span，本层 setAttribute 被 SDK 静默丢弃），e2e 断言其缺失只会把缺陷钉成契约。截断断言已按评审改为字符串解析，规避正则誊写转义风险
- **前置**：
  - 同 TR1：mock/otlp-receiver.mjs、OTEL 环境注入、lib/otlp.ts、文档同步
  - 复用 plain 夹具（无需新录制件）；标题生成等后台调用走 TracingModelWrapper（span 名 memory/compaction、无 gen_ai.input.messages 属性），spansOf 的 name 前缀 + 属性存在双条件可干净排除，不误选 span
- **步骤**：
  1. POST /threads/chat（SSE）：message='[E2E:plain]' + 'X'.repeat(20000)（共 20011 字符超长 prompt）、sessionId=sessionIdFor('tr3-<uniq>')、userId=e2e-tester，收流至终态
  2. 轮询 GET /spans 直至该 sessionId 下 name 以 'chat ' 开头且带 gen_ai.input.messages 属性的 span 出现
- **断言**：
  - stream 终态 done（plain 夹具回放固定文本，超长尾巴不影响完成）
  - chat span 的 gen_ai.input.messages 属性长度 > 8192；slice(8192) 之后的尾部 tail 满足 startsWith('...(truncated, total ') 且 endsWith(' chars)')（固定格式，ModelIoTracingMiddleware.java:200-201）
  - tail 去掉固定前后缀后可 Number 解析为整数且 > 20000（system(≈1.3K)+user 原始渲染总长被如实记录，未静默丢信息）
  - 属性前 8192 字符内含 '[E2E:plain]'（user 消息紧随 system 之后，前缀保留可核）
  - gen_ai.output.messages 不含 '(truncated, total '（plain 短输出不被误截断——截断只对超长那条生效）
- **draftCode**：

```typescript
// 落在 TR1 草稿的 test.describe('OTel span 内容属性（TR 组）') 内、TR2 之后，describe 于此闭合（共享 beforeEach/spansOf 见 TR1）
  test('TR3 8192 截断契约：超长 prompt 截断保留前缀并标注原始总长', async () => {
    const sid = sessionIdFor(`tr3-${uniq()}`);
    // 超长尾巴随 user 消息进入模型输入；mock 按标记路由（llm-server.mjs route 正则），
    // plain 夹具回放固定文本，与尾巴长度无关
    const stream = chat({ message: `[E2E:plain]${'X'.repeat(20_000)}`, userId: U, sessionId: sid });
    await waitTerminal(stream);
    expect(stream.terminal?.type).toBe('done');

    const span = (await spansOf(sid, 'chat ', 'gen_ai.input.messages'))[0];
    const input = String(span.attributes['gen_ai.input.messages']);
    // 精确契约（ModelIoTracingMiddleware.java:200-201）：固定格式 '...(truncated, total N chars)'。
    // 有意不用正则（评审修订）：上一版草稿的正则经传输层转义变形后誊写即恒红，
    // startsWith/endsWith + Number 解析零转义风险
    expect(input.length).toBeGreaterThan(8192);
    const tail = input.slice(8192);
    const MARK = '...(truncated, total ';
    expect(tail.startsWith(MARK), `截断标记前缀不符：…${tail}`).toBe(true);
    expect(tail.endsWith(' chars)'), '截断标记收尾不符').toBe(true);
    const total = Number(tail.slice(MARK.length, -' chars)'.length));
    expect(Number.isInteger(total) && total > 20_000).toBe(true); // 原始渲染总长被记录
    expect(input.slice(0, 8192)).toContain('[E2E:plain]');
    // 短输出不误截断
    expect(String(span.attributes['gen_ai.output.messages'])).not.toContain('(truncated, total ');
  });
});
```

**复核意见（评审留痕，均已修订吸收）**：
- TR1 原稿在 api-core.spec.ts 末尾新增第二个顶层 test.beforeEach(otlpReset)：Playwright 的 beforeEach 按文件累积、对 S/F/H/M/A/SK 全部现有用例生效——receiver 未启动时整个 api-core 项目全红而非仅 TR 组。本仓惯例是单文件单一共享钩子。已改为 describe 限定 TR 组作用域。（已改）
- TR3 截断正则的转义需在落地时核对：原稿正则经传输层转义变形后誊写即恒红（写错即用例恒红）。已改为 startsWith/endsWith + Number 解析，零转义风险。（已改）
- placement 声称'无需改 run.sh/playwright.config'对执行成立，但组级权威清单在 docs/e2e-ci-plan.md：§2.3 job 表与 §5 场景矩阵，且 api-core.spec.ts:2 头注自称 'e2e-ci-plan §5'。TR 组落地应同一变更内补 §5 小节与 job 表措辞，否则文档继续欠账（先例：SK 组已落地而 §5/§2.3 均未登记）。（已纳入前置）

---

## 5. 落地路径建议

### 5.1 直接可落（15 条，blocker=none）

| 用例 | 缺口 | 组/项目 | 说明 |
|------|------|---------|------|
| HA1、HA2 | history 双源合并 | api-core（e2e-core） | 复用 plain 夹具与既有 helper，零前置；归档默认开启即真默认路径 |
| MOD4、MOD5 | model 采样参数 | api-models（e2e-core） | 纯 REST 直连，零 LLM 依赖 |
| X10、X11（§4.3 沙箱技能） | 沙箱技能物化/tombstone | api-sandbox（e2e-sandbox） | 复用 plain 夹具与 mock fileOps 观测；建议首跑前按前置做一次 mock 口径校准（user-skill-admin-e2e.sh E8） |
| SK2、SK3、SK4 | skills 按用户合并 | api-core（e2e-core） | 纯 REST，零录制件零 mock 扩展 |
| MEM1、MEM2、MEM3 | memory 关断分支 | api-core（e2e-core） | describe 级 spawn 独立实例；无需录制件 |
| A5、A6、A7 | A2A llm-calls | api-core（e2e-core） | 复用 plain 夹具与 a2a helper |

> 上述草稿均已吸收对应复核修订点，落码时按 §4 各 draftCode 誊写即可；特别注意 MEM 组 describe 化（spawn 失败爆炸半径限于本组）与 SK2 的 uidB PUT 断言。

### 5.2 需补 LLM 录制件（2 条）

- **X12、X13（§4.3）**：需在开发机重录 `skill-manage-sb` 场景——mock/fixtures/llm/skill-manage-sb.json + registry.json 登记（marker `[E2E:skill:manage:sb]`、calls>=2）+ llm-server.mjs MARKER_MAP 增路由；check-fixtures.mjs 按 ≥calls 校验。录制口径仿 sandbox-write.json（call[0]=skill_manage create tool_call、call[1]=文本收尾，prompt 仿 user-skill-admin-e2e.sh:402-414 的 E8）。
- **录制前必做**：先验证 X3 同族缺陷（api-sandbox.spec.ts:52-54 回放 tool_call 不触发沙箱写入）不命中 skill_manage 写入链；若命中，保持 test.fixme 并先修框架缺陷，或退路改录 execute 白名单命令写 skills/ 的变体夹具（mkdir/printf 在 sandbox-server.mjs:34 白名单内）。另需先手工跑 E8 校准 mock listDirectory 口径。

### 5.3 需扩 mock / 测试基建（8 条）

| 用例 | 基建内容 |
|------|----------|
| HA3、U15 | 新增 lib/archive-seed.ts 直插 session_message（种子 SQL 已在上游本机 e2e 库 13306 实跑往返验证）；reset-data.mjs:18-19 TABLES 补 'session_message' 清场；无新 LLM 录制件（plain 可无限复用） |
| MOD6、MOD7 | llm-server.mjs /stats calls[] 增记 sampling 四键（chat_template_kwargs/thinking/reasoning_effort/frequency_penalty）；复用 plain 夹具，registry.json 与 check-fixtures.mjs 不变 |
| X10（§4.5 记忆面，建议重编号 X14） | env-up.sh sandbox 分支扩第二实例（SANDBOX_ENABLED=true + AGENT_MEMORY_ENABLED=false + 独立端口）+ scripts/seed-memory.mjs（agent_fs 行编码需从 agentscope-harness jar 逆向确认）；seed 必须在 env-up 之后执行（reset-data 会 DROP agent_fs）；sandbox-server.mjs 本体零改动 |
| TR1、TR2、TR3 | 新增 mock/otlp-receiver.mjs（:14318，OTLP/HTTP protobuf 最小线格式解码 + GET /spans + POST /reset）；env-up.sh/start-agent.sh 注入 OTEL_TRACES_EXPORTER=otlp 与 OTEL_EXPORTER_OTLP_ENDPOINT（当前 e2e 实例不产任何 span，这是全组前置缺口）；新增 lib/otlp.ts；同一变更内同步 docs/e2e-ci-plan.md §2.3 job 表与 §5 TR 小节、api-core.spec.ts:2 头注 |

### 5.4 不建议门禁化 / 暂无黑盒射程

- **492e905 release-agent 模型切换引导话术**：无 REST/SSE/请求体面，[E2E:xxx] 回放机制原理上不可断言，只能发布后人工对话验证；且其 commit 信息声称的 bench/eval input.model 能力实际在 ca5478d（另一分支），按旧提交信息回溯会扑空。
- **present_file 主链路（F5/U11）**：因 SDK edit_file countOccurrences 死循环长期 test.fixme（api-core.spec.ts:280、ui.spec.ts:160）；file_ready 合成链已由 X9/F12 侧护。同理 U8（无头时序挂起）、X3（回放 tool_call 不落盘）待框架缺陷修复后转正——docs/e2e-ci-plan.md:405/:671 已有规划。
- **取消路径 span 内容属性缺失**：文档化已知边界（ModelIoTracingMiddleware.java:44-45），e2e 断言其缺失只会把缺陷钉成契约——TR 组不设计该用例（见 TR3 blocker 附注）。
- **依赖 mock 扩展后才可见的面**：3dad588 方言下发（无 stats sampling 扩展前黑盒不可见，MOD6/MOD7 即为补齐方案）；A2A/直连未登记形态的 llm-calls（A5-A7 已覆盖 A2A 面，直连未登记形态仍仅单测）。
- **仅间接受护、暂无定向设计的面**：SSE 空闲退避节奏与 A3 seq=0 门控（multi 组续传间接受护）、debug ctx 陈旧守卫（U1 间接受护）、S9 标题 background 判据稳定性、llm-calls 兜底 "global" 形态。

### 5.5 编号与卫生事项

- **caseId 撞号**：材料三「沙箱技能回写」（§4.3）与「记忆关断」（§4.5）两缺口均将首条用例编为 **X10** 且同落 api-sandbox.spec.ts X 组。**已在落地时消解**：沙箱技能缺口占用 X10/X11（api-sandbox.spec.ts:169/:237），记忆面用例至今未落地；其后 f4db8ca 又占用 X15（Channel 链路 per-user 物化，:308），故记忆面用例若落地须从 **X16** 起编号，避免同 spec 内 caseId 冲突。
- **reset-data.mjs 清场缺口**：TABLES（:18-19）缺 session_message（ThreadController.java:277 已有级联删除语义），归档行跨运行残留属卫生隐患——**已随 HA3/U15 落地补齐**（现 TABLES 含 `session_message`）。
- **既有用例缺号**：sandbox 无 X5、ui 无 U12，盘点如实记录（材料二矩阵）。

### 5.6 性质声明

**本次为静态分析与用例设计：全部 25 条用例仅停留在本文档草稿，尚未写入 `agent-framework/e2e/tests/`，亦未执行。** 文中引用的 grep、git show、check-fixtures 实跑（OK：llm=17 场景，sandbox=1 文件）、本机 e2e 库 13306 SQL 往返、SDK jar javap 反汇编等验证均为**上游评审环节所做**（材料三复核记录，各处已标注命令与输出），本次汇编未复跑；Playwright 用例的端到端实测在上游亦未执行（本机 :8100 未启动，结论基于源码逐行核对 + 实库 SQL 往返，各缺口小节已标注"未跑项"）。落地时应按第 4 节各用例前置逐条核对环境后实施，并优先吸收各缺口「复核意见」中的修订与边界声明。

> **状态回填（2026-09-28）**：上文「尚未写入」已过时——其中 **19 条已随 bc235c4 落地**（HA1-HA3+U15、MOD4-MOD7、X10/X11、SK2-SK4、MEM1-MEM3、A5-A7 及配套基建），余 6 条（X12/X13、§4.5 记忆面沙箱 no-op、TR1-TR3）待录制件/OTLP mock 基建，见文首「落地回填」。
