// 跨集群导出 · 前端纯逻辑（U5，契约 §7.1）：锁定行判定 / 四层预览归类 / marker 校验 /
// 运行时默认 schema 与 PUT 三态组装。不依赖 React/DOM，node:test 直接跑（export-view.test.mjs）。
// schema 与 backend/internal/service/platformconfig/template.go 同源（模板驱动表单，设计 §10）；
// L1 键空间 = 24 模板键（KnownKeys − CHECKPOINT_JDBC_URL，契约 §1.4/D13）+ 8 导出专用键。

// 运行时默认单个字段定义（对齐 backend platformconfig.Field 形状 + category 维度）。
export interface RuntimeDefaultField {
  envKey: string;
  label: string;
  category: string;
  sensitive?: boolean;
  multiline?: boolean;
  placeholder?: string;
  // exportOnly = 模板之外的导出专用键（契约 §1.4 八键）
  exportOnly?: boolean;
}

// L1 全量键空间（32 键）。敏感位取 platformconfig.Lookup(k).Sensitive；
// CHECKPOINT_JDBC_URL 刻意不在列（L3 派生键，进 L1 会把源集群 JDBC URL 明文导出，契约 D13）。
export const RUNTIME_DEFAULT_FIELDS: RuntimeDefaultField[] = [
  // mysql（模板 3 键去掉 CHECKPOINT_JDBC_URL 剩 2 + 导出键 CHECKPOINT_JDBC_BASE）
  { envKey: "CHECKPOINT_JDBC_BASE", label: "Checkpoint 基库 URL", category: "mysql", multiline: true, exportOnly: true,
    placeholder: "jdbc:mysql://mysql.prod.svc:3306/oaf_checkpoint（平台按 {基库}_{short} 派生实际库）" },
  { envKey: "CHECKPOINT_USERNAME", label: "数据库用户名", category: "mysql", placeholder: "oaf" },
  { envKey: "CHECKPOINT_PASSWORD", label: "数据库密码", category: "mysql", sensitive: true },
  // redis
  { envKey: "AGENT_REDIS_URL", label: "Redis URL", category: "redis", sensitive: true,
    placeholder: "redis://redis.prod.svc:6379（AGENT_REDIS_PREFIX 由平台派生 {short}:，不可配置）" },
  { envKey: "AGENT_REDIS_COMMAND_TIMEOUT_MS", label: "命令超时(ms)", category: "redis", placeholder: "2000" },
  { envKey: "AGENT_REDIS_CONNECT_TIMEOUT_MS", label: "建连超时(ms)", category: "redis", placeholder: "2000" },
  // otel
  { envKey: "OTEL_EXPORTER_OTLP_ENDPOINT", label: "OTLP 上报地址", category: "otel", exportOnly: true,
    placeholder: "http://otel-collector.prod.svc:4318" },
  // llm（模板全量 10 键）
  { envKey: "LLM_API_KEY", label: "API Key", category: "llm", sensitive: true, placeholder: "sk-..." },
  { envKey: "LLM_BASE_URL", label: "Base URL", category: "llm", placeholder: "https://api.example.com/v1" },
  { envKey: "LLM_MODEL_ID", label: "模型 ID", category: "llm", placeholder: "glm-4.7" },
  { envKey: "LLM_PROVIDER", label: "推理方言", category: "llm", placeholder: "openai | vllm | sglang | glm | deepseek" },
  { envKey: "LLM_TEMPERATURE", label: "温度", category: "llm", placeholder: "0.3" },
  { envKey: "LLM_MAX_TOKENS", label: "最大 token", category: "llm", placeholder: "16384" },
  { envKey: "LLM_ENABLE_THINKING", label: "思考开关", category: "llm", placeholder: "true | false" },
  { envKey: "LLM_CONTEXT_LENGTH", label: "上下文窗口", category: "llm", placeholder: "≤0 不下发" },
  { envKey: "LLM_REASONING_EFFORT", label: "推理强度", category: "llm", placeholder: "空=不下发" },
  { envKey: "LLM_FREQUENCY_PENALTY", label: "频率惩罚", category: "llm", placeholder: "空=不下发" },
  // sandbox（模板全量 6 键；SANDBOX_ENABLED 刻意排除：包 frontmatter 三层裁决）
  { envKey: "OPENSANDBOX_SERVER_URL", label: "Server 地址", category: "sandbox", placeholder: "host:8090" },
  { envKey: "OPENSANDBOX_API_KEY", label: "API Key", category: "sandbox", sensitive: true },
  { envKey: "SANDBOX_IMAGE", label: "沙箱镜像", category: "sandbox", placeholder: "opensandbox/code-interpreter:v1.1.0" },
  { envKey: "SANDBOX_TIMEOUT_MINUTES", label: "超时(分钟)", category: "sandbox", placeholder: "60" },
  { envKey: "SANDBOX_MEMORY_MB", label: "内存上限(MB)", category: "sandbox", placeholder: "1024" },
  { envKey: "SANDBOX_CPU_COUNT", label: "CPU 核数", category: "sandbox", placeholder: "1" },
  // protocol（模板 3 敏感键；开关类键刻意排除）
  { envKey: "AGENT_PROTOCOL_AUTH_TOKEN", label: "协议 Token", category: "protocol", sensitive: true },
  { envKey: "AGENT_REMOTE_HEADERS_JSON", label: "远程请求头 JSON", category: "protocol", sensitive: true, multiline: true },
  { envKey: "AGENT_A2A_JOB_TOKEN", label: "A2A Job Token", category: "protocol", sensitive: true },
  // registry（导出键）
  { envKey: "REGISTRY_ADDRESS", label: "镜像仓库地址", category: "registry", exportOnly: true, placeholder: "172.20.0.1:5001" },
  { envKey: "REGISTRY_NAMESPACE", label: "仓库命名空间", category: "registry", exportOnly: true, placeholder: "可空" },
  { envKey: "IMAGE_PULL_SECRETS", label: "imagePullSecrets", category: "registry", exportOnly: true, placeholder: "逗号分隔 Secret 名，可空" },
  // routing（导出键）
  { envKey: "ROUTING_MODE", label: "路由模式", category: "routing", exportOnly: true, placeholder: "host | router（缺省 host）" },
  { envKey: "ROUTING_HOST_SUFFIX", label: "域名后缀", category: "routing", exportOnly: true, placeholder: ".prod.example.com（以 . 开头）" },
  // runtime（导出键）
  { envKey: "BASE_IMAGE_WHITELIST", label: "基础镜像白名单", category: "runtime", multiline: true, exportOnly: true,
    placeholder: "ref|Label,ref2|Label2（格式同 AVAILABLE_IMAGES）" },
];

// 分类标题（对齐 backend template.go 分组命名 + 设计 §10 字段分组）
export const CATEGORY_TITLES: Record<string, string> = {
  mysql: "MySQL（checkpoint）",
  redis: "Redis（session_event）",
  otel: "OTel（链路追踪）",
  llm: "LLM 系统模型",
  sandbox: "Sandbox（OpenSandbox）",
  protocol: "Agent Protocol（远程子 agent）",
  registry: "镜像仓库",
  routing: "路由（Ingress）",
  runtime: "运行时",
};

// 工作空间配置管理页页签 → 分类划分（契约 §7.1；每键恰属一个页签，编辑同一份 runtime-defaults）
export const WS_CONFIG_TABS: { id: string; label: string; categories: string[] }[] = [
  { id: "runtime", label: "运行时默认", categories: ["mysql", "redis", "otel", "protocol"] },
  { id: "mcp", label: "MCP 连接器模板", categories: [] },
  { id: "llm", label: "模型参数", categories: ["llm"] },
  { id: "sandbox", label: "沙箱参数", categories: ["sandbox"] },
  { id: "registry", label: "仓库与路由", categories: ["registry", "routing", "runtime"] },
  { id: "api", label: "API", categories: [] },
];

// ---- env 校验口径（与 backend service/env.go ValidateEnv 同源）----
export const ENV_KEY_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;
export const MAX_ENV_KEYS = 64;
export const MAX_ENV_VALUE_BYTES = 32 * 1024;

// 平台保留键（backend k8s/objects.go ReservedEnvKeys）+ L3 派生键（契约 §5.4）。
// 保留键用户 env 不可出现；派生键由平台按 short 生成，L2 出现同名即 400。
export const RESERVED_ENV_KEYS = [
  "AGENT_CONFIG_DIR", "AGENT_WORKSPACE_DIR", "SERVER_HOST", "SERVER_PORT", "HOST_NAME",
  "CHECKPOINT_JDBC_URL", "AGENT_REDIS_PREFIX",
];

// 导出参数键（backend exportvalues.go exportMetaKeys 的 7 键）：只驱动 values 结构化输出
// （推送目标/ingress/checkpoint 基库等），一律取 L1（契约 §5.5）。导出页 env 表不可设置——
// 后端 resolveExportLayers 对 L2 命中即 400（评审修复：防绕过 L1 运维锁定改写基础设施输出）。
// 刻意不入 RESERVED_ENV_KEYS：这组键属 L1 键空间（空间配置页可配、锁定行按 L1 展示），非 L3。
export const EXPORT_META_ENV_KEYS = [
  "CHECKPOINT_JDBC_BASE", "REGISTRY_ADDRESS", "REGISTRY_NAMESPACE", "IMAGE_PULL_SECRETS",
  "ROUTING_MODE", "ROUTING_HOST_SUFFIX", "BASE_IMAGE_WHITELIST",
];

/** 锁行判定（契约 §7.1 isLockedRow）：命中运行时默认（lockedKeys）的行锁定不可编辑。 */
export function isLockedRow(key: string, lockedKeys: string[]): boolean {
  return lockedKeys.includes(key);
}

/** 包内只读路径（设计 §9：AGENTS.md / skills 只读项标注）。 */
export function isReadonlyPkgPath(path: string): boolean {
  return path === "AGENTS.md" || path.startsWith("skills/");
}

/** env 键校验：返回错误文案，空串 = 合法。existingKeys 用于行内查重。 */
export function validateEnvKey(key: string, lockedKeys: string[], existingKeys: string[] = []): string {
  if (!key) return "键名不能为空";
  if (!ENV_KEY_RE.test(key)) return `键名格式非法（须匹配 ^[A-Za-z_][A-Za-z0-9_]*$）: ${key}`;
  if (RESERVED_ENV_KEYS.includes(key)) return `${key} 为平台保留/派生键，不可设置`;
  if (EXPORT_META_ENV_KEYS.includes(key)) return `${key} 为工作空间导出参数键（L1），仅运维在「空间配置」配置，导出页不可设置`;
  if (isLockedRow(key, lockedKeys)) return `${key} 来自工作空间运行时默认（L1），导出页锁定——请运维在「空间配置」修改`;
  const dup = existingKeys.filter((k) => k === key).length;
  if (dup > 1) return `键 ${key} 重复`;
  return "";
}

/** env 值校验：单值 32KB 上限（env.go MaxEnvValueBytes）。 */
export function validateEnvValue(key: string, value: string): string {
  if (value.length > MAX_ENV_VALUE_BYTES) return `${key} 值超过 32KB 上限`;
  return "";
}

/** 行数校验：全量 env ≤64 键（含锁定行，env.go MaxEnvKeys）。 */
export function validateEnvRowCount(total: number): string {
  if (total > MAX_ENV_KEYS) return `env 键数量 ${total} 超过上限 ${MAX_ENV_KEYS}`;
  return "";
}

/** marker 格式提示（{包版本}-e{序号}，如 1.2.0-e1）；空串 = 合法。 */
export function markerIssue(marker: string): string {
  if (!marker.trim()) return "请选择发布标记（先在「配置包」区块打发布标记）";
  if (!/^[A-Za-z0-9][A-Za-z0-9._+-]*-e\d+$/.test(marker)) return `标记格式应为 {包版本}-e{序号}: ${marker}`;
  return "";
}

/** 四层预览归类（契约 §7.1）：L3 派生/保留键 > L1 运行时默认（锁定） > L2 用户覆盖。 */
export function classifyEnvLayer(key: string, lockedKeys: string[]): "L1" | "L2" | "L3" {
  if (RESERVED_ENV_KEYS.includes(key)) return "L3";
  if (isLockedRow(key, lockedKeys)) return "L1";
  return "L2";
}

/** 镜像白名单解析（config.go parseImages 同口径："ref|Label,ref2|Label2"，空段跳过）。 */
export function parseImageWhitelist(raw: string): { image: string; label: string }[] {
  const out: { image: string; label: string }[] = [];
  for (const part of raw.split(",")) {
    const trimmed = part.trim();
    if (!trimmed) continue;
    const bar = trimmed.indexOf("|");
    const image = bar >= 0 ? trimmed.slice(0, bar).trim() : trimmed;
    const label = bar >= 0 ? trimmed.slice(bar + 1).trim() : "";
    if (image) out.push({ image, label });
  }
  return out;
}

const DNS1123_LABEL_RE = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/;

/** ROUTING_HOST_SUFFIX 客户端预检（config.go:150-172 简化口径）；空串 = 合法（host 模式可无后缀）。 */
export function routingSuffixIssue(suffix: string): string {
  if (!suffix) return "";
  if (!suffix.startsWith(".")) return "域名后缀必须以 . 开头";
  const trimmed = suffix.slice(1);
  if (!trimmed) return "域名后缀不能只有 .";
  for (const seg of trimmed.split(".")) {
    if (!DNS1123_LABEL_RE.test(seg) || seg.length > 63) return `域名后缀段非法: ${seg}`;
  }
  return "";
}

// 运行时默认单条视图（与 api.ts RuntimeDefaultEntry 同构；后端敏感键 value 恒缺省、hasValue 标记已配置）
export interface RuntimeDefaultEntry { value?: string; hasValue?: boolean; sensitive: boolean; category: string; }

/** 服务端视角「该键已配置」：hasValue 优先（敏感键无 value 字段），无 hasValue 的旧视图回退 value。 */
export function entryConfigured(e?: RuntimeDefaultEntry): boolean {
  if (!e) return false;
  return e.hasValue === true || (e.value ?? "") !== "";
}

/**
 * 运行时默认 PUT 三态组装（契约 U1：出现非空 = 设置、空串 = 删除、缺失 = 不变）。
 * - 非敏感键：全量上送输入值（空串 = 删除），clearing 收集「原有值 → 将清空」的键供确认提示；
 * - 敏感键：仅上送非空新输入（留空 = 不变，与平台默认配置页同口径）。
 */
export function buildRuntimeDefaultPayload(
  fields: RuntimeDefaultField[],
  plain: Record<string, string>,
  secret: Record<string, string>,
  existing: Record<string, RuntimeDefaultEntry>,
): { values: Record<string, string>; clearing: string[] } {
  const values: Record<string, string> = {};
  const clearing: string[] = [];
  for (const f of fields) {
    if (f.sensitive) {
      const next = (secret[f.envKey] ?? "").trim();
      if (next) values[f.envKey] = next; // 留空 = 不变（缺失语义）
    } else {
      const next = plain[f.envKey] ?? "";
      values[f.envKey] = next;
      if (entryConfigured(existing[f.envKey]) && next === "") clearing.push(f.envKey);
    }
  }
  return { values, clearing };
}

/** 按分类分组并保序（工作空间配置管理页分组表单用）。 */
export function groupFieldsByCategory(fields: RuntimeDefaultField[]): { category: string; title: string; fields: RuntimeDefaultField[] }[] {
  const order: string[] = [];
  const byCat = new Map<string, RuntimeDefaultField[]>();
  for (const f of fields) {
    if (!byCat.has(f.category)) { byCat.set(f.category, []); order.push(f.category); }
    byCat.get(f.category)!.push(f);
  }
  return order.map((category) => ({ category, title: CATEGORY_TITLES[category] ?? category, fields: byCat.get(category)! }));
}
