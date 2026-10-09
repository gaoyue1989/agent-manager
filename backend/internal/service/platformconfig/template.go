// Package platformconfig：平台默认配置字段模板（全仓唯一字段定义源）。
// 模板驱动四件事：设置页表单 schema、平台 CM/Secret 拆分渲染、服务 env 敏感路由分类、
// 全部读路径的掩码输出。设计见 docs/design/platform-default-config-secret-design.md §3.2。
package platformconfig

// Field 单个配置字段定义。
type Field struct {
	EnvKey      string `json:"envKey"`                // 注入 Pod 的环境变量名（与 agent-framework 变量名严格一致）
	Label       string `json:"label"`                 // 页面展示名
	Required    bool   `json:"required"`              // 清除该键时拒绝（防误清，非全局完整性检查）
	Sensitive   bool   `json:"sensitive"`             // true → 平台级渲染进 Secret / 服务级路由进服务 Secret；页面掩码
	Multiline   bool   `json:"multiline"`             // JDBC URL 等长值用 textarea
	Placeholder string `json:"placeholder,omitempty"` // 页面占位提示（不作为值）
}

// Group 配置分组（页面 section 与 Secret 范畴）。
type Group struct {
	Name   string  `json:"name"`
	Title  string  `json:"title"`
	Fields []Field `json:"fields"`
}

// template 四组字段。值不设代码内默认，首次由管理员在页面填写；
// Placeholder 仅提示格式。SANDBOX_ENABLED 明确排除：它在 agent-framework 是
// 三层裁决（env 显式存在 > OAF 包 frontmatter > 默认 false），进入平台默认配置
// 会使包级沙箱声明永久失效。
var template = []Group{
	{
		Name: "llm", Title: "LLM 系统模型",
		Fields: []Field{
			{EnvKey: "LLM_API_KEY", Label: "API Key", Required: true, Sensitive: true, Placeholder: "sk-..."},
			{EnvKey: "LLM_BASE_URL", Label: "Base URL", Required: true, Placeholder: "https://api.example.com/v1"},
			{EnvKey: "LLM_MODEL_ID", Label: "模型 ID", Required: true, Placeholder: "glm-4.7"},
			{EnvKey: "LLM_PROVIDER", Label: "推理方言", Placeholder: "openai | vllm | sglang | glm | deepseek"},
			{EnvKey: "LLM_TEMPERATURE", Label: "温度", Placeholder: "0.3"},
			{EnvKey: "LLM_MAX_TOKENS", Label: "最大 token", Placeholder: "16384"},
			{EnvKey: "LLM_ENABLE_THINKING", Label: "思考开关", Placeholder: "true | false"},
			{EnvKey: "LLM_CONTEXT_LENGTH", Label: "上下文窗口", Placeholder: "≤0 不下发"},
			{EnvKey: "LLM_REASONING_EFFORT", Label: "推理强度", Placeholder: "空=不下发"},
			{EnvKey: "LLM_FREQUENCY_PENALTY", Label: "频率惩罚", Placeholder: "空=不下发"},
		},
	},
	{
		Name: "mysql", Title: "MySQL（checkpoint）",
		Fields: []Field{
			{EnvKey: "CHECKPOINT_JDBC_URL", Label: "JDBC URL", Required: true, Multiline: true,
				Placeholder: "jdbc:mysql://oaf-mysql.agent-platform.svc.cluster.local:3306/oaf_checkpoint"},
			{EnvKey: "CHECKPOINT_USERNAME", Label: "用户名", Placeholder: "oaf"},
			{EnvKey: "CHECKPOINT_PASSWORD", Label: "密码", Required: true, Sensitive: true},
		},
	},
	{
		Name: "redis", Title: "Redis（session_event）",
		Fields: []Field{
			{EnvKey: "AGENT_REDIS_URL", Label: "Redis URL", Required: true, Sensitive: true,
				Placeholder: "redis://oaf-redis.agent-platform.svc.cluster.local:6379"},
			{EnvKey: "AGENT_REDIS_COMMAND_TIMEOUT_MS", Label: "命令超时(ms)", Placeholder: "2000"},
			{EnvKey: "AGENT_REDIS_CONNECT_TIMEOUT_MS", Label: "建连超时(ms)", Placeholder: "2000"},
		},
	},
	{
		Name: "sandbox", Title: "Sandbox（OpenSandbox）",
		Fields: []Field{
			{EnvKey: "OPENSANDBOX_SERVER_URL", Label: "Server 地址", Required: true, Placeholder: "host:8090"},
			{EnvKey: "OPENSANDBOX_API_KEY", Label: "API Key", Sensitive: true},
			{EnvKey: "SANDBOX_IMAGE", Label: "沙箱镜像", Placeholder: "opensandbox/code-interpreter:v1.1.0"},
			{EnvKey: "SANDBOX_TIMEOUT_MINUTES", Label: "超时(分钟)", Placeholder: "60"},
			{EnvKey: "SANDBOX_MEMORY_MB", Label: "内存上限(MB)", Placeholder: "1024"},
			{EnvKey: "SANDBOX_CPU_COUNT", Label: "CPU 核数", Placeholder: "1"},
		},
	},
	{
		// Agent Protocol（远程子 agent）敏感键：只收敏感键入模板驱动 Secret 路由
		//（travel-fulfillment 设计 §8 平台端）。AGENT_PROTOCOL_ENABLED/TASK_STORE/
		// TASK_RETENTION_DAYS 是按服务启用的开关与存储选择，不进平台默认配置——
		// 否则发布表单会给全部服务预填"默认启用协议"，扩大 /tasks 暴露面。
		Name: "protocol", Title: "Agent Protocol（远程子 agent）",
		Fields: []Field{
			{EnvKey: "AGENT_PROTOCOL_AUTH_TOKEN", Label: "协议 Token", Sensitive: true,
				Placeholder: "启用协议的服务 /tasks 认证令牌（lead/member 需同值）"},
			{EnvKey: "AGENT_REMOTE_HEADERS_JSON", Label: "远程请求头 JSON", Sensitive: true, Multiline: true,
				Placeholder: `{"X-Agent-Protocol-Token":"..."}`},
			// A2A 幂等 Job 入口认证（Issue #69 下沉 member 侧后的外部互操作凭据，
			// 与 /tasks 协议 token 分属两个信任域）；AGENT_A2A_JOB_ENABLED 等开关键
			// 刻意排除（同上理由：防默认启用扩大 /a2a/jobs 暴露面）
			{EnvKey: "AGENT_A2A_JOB_TOKEN", Label: "A2A Job Token", Sensitive: true,
				Placeholder: "启用 A2A Job 的服务 /a2a/jobs 认证令牌"},
		},
	},
}

// Template 返回全部分组（只读遍历用，调用方不得修改）。
func Template() []Group { return template }

// Lookup 返回键的字段定义；不存在返回 nil。
func Lookup(envKey string) *Field {
	for _, g := range template {
		for i := range g.Fields {
			if g.Fields[i].EnvKey == envKey {
				return &g.Fields[i]
			}
		}
	}
	return nil
}

// KnownKeys 返回模板全部键集合（平台配置 PUT 未知键校验）。
func KnownKeys() map[string]bool {
	out := map[string]bool{}
	for _, g := range template {
		for _, f := range g.Fields {
			out[f.EnvKey] = true
		}
	}
	return out
}

// SensitiveKeys 返回敏感键集合（服务 env 路由分类依据；静态，与键当前是否有值无关）。
func SensitiveKeys() map[string]bool {
	out := map[string]bool{}
	for _, g := range template {
		for _, f := range g.Fields {
			if f.Sensitive {
				out[f.EnvKey] = true
			}
		}
	}
	return out
}

// Split 按 Sensitive 把键值对拆为非敏感/敏感两份；extraSecret 中指定的任意键强制归敏感份
// （publish/update-env 的 secretKeys 逃生门）。拆分后两份互斥，非敏感份用于 CM/env_json，
// 敏感份用于 Secret/env_secret_json。
func Split(values map[string]string, extraSecret map[string]bool) (plain, secret map[string]string) {
	plain = map[string]string{}
	secret = map[string]string{}
	for k, v := range values {
		if SensitiveKeys()[k] || extraSecret[k] {
			secret[k] = v
		} else {
			plain[k] = v
		}
	}
	return plain, secret
}
