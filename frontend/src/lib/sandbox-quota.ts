// 沙箱配额错误卡与池水位纯逻辑（sandbox-pool-opensandbox-design §6.1/§6.3，U6 单测；
// 模式同 confirm-card.ts：从组件抽出可 node:test 的判定函数）。
// 评审 d5 口径：confirm_holding 会话「停止」必 409（后端 WAITING_CONFIRM 拒绝停止）——
// 两种 state 区分动作：executing → 提供停止按钮并明示「停止后需等 TTL 宽限才可复用」；
// confirm_holding → 引导「等保留窗到期自动释放，或在该会话拒绝该确认」，不给停止按钮。

/** 配额占用明细（§4.3 409 body 的 bindings 元素；SSE error 帧为 turn_running 布尔——§4.6） */
export type QuotaBinding = {
  session_id?: string;
  expires_at?: string;
  /** chat 预检 409 携带 state；SSE error 帧携带 turn_running 布尔 */
  state?: string;
  turn_running?: boolean;
};

/** 配额满结构化载荷（409 预检响应体 / SSE error 帧 code=user_quota_exhausted 共用形态） */
export type QuotaPayload = {
  code: "user_quota_exhausted";
  message?: string;
  quota: number;
  bindings: QuotaBinding[];
};

/** 来源：precheck = chat 预检 409（未建流，输入需保留+乐观气泡回滚）；exec = SSE error 帧（已建流） */
export type QuotaSource = "precheck" | "exec";

export type QuotaError = {
  quotaError: true;
  source: QuotaSource;
  payload: QuotaPayload;
  /** 用户可读摘要（Error 语义兼容：既有 catch 的 e.message 分支可直接消费） */
  message: string;
};

/** 归一位：executing | confirm_holding（SSE 帧的 turn_running 布尔 / 未知态均归 executing——能撞满配额说明全部有执行中迹象） */
export function deriveBindingState(b: QuotaBinding | null | undefined): "executing" | "confirm_holding" {
  if (b?.state === "confirm_holding") return "confirm_holding";
  return "executing";
}

/** 单条占用的展示口径：文案建议 + 是否提供「停止该会话」按钮（d5 两态区分） */
export function bindingAdvice(b: QuotaBinding | null | undefined): {
  state: "executing" | "confirm_holding";
  label: string;
  advice: string;
  canStop: boolean;
} {
  const state = deriveBindingState(b);
  if (state === "confirm_holding") {
    return {
      state,
      label: "确认等待中",
      advice: "等保留窗到期自动释放，或在该会话拒绝该确认（执行等待中无法停止）",
      canStop: false,
    };
  }
  return {
    state,
    label: "执行中",
    advice: "可停止该会话；停止后需等 TTL 宽限期过后才会被复用/释放（非即时）",
    canStop: true,
  };
}

/** 错误卡标题：quota 缺失时退化为通用文案（载荷异常不阻塞渲染） */
export function quotaHeadline(p: QuotaPayload | null | undefined): string {
  if (p && typeof p.quota === "number" && p.quota > 0) {
    return `沙箱配额已满（${p.bindings?.length ?? p.quota}/${p.quota}）且均不可复用`;
  }
  return (p?.message as string) || "沙箱配额已满且均不可复用";
}

/** chat 预检 409 → 配额载荷（其余状态/结构返回 null，走通用报错） */
export function parseQuota409(status: number, body: unknown): QuotaPayload | null {
  if (status !== 409) return null;
  return normalizeQuotaPayload(body);
}

/** SSE error 帧 → 配额载荷（code=user_quota_exhausted；其余返回 null） */
export function quotaFromSseError(ev: unknown): QuotaPayload | null {
  if (!ev || typeof ev !== "object" || (ev as { code?: unknown }).code !== "user_quota_exhausted") return null;
  return normalizeQuotaPayload(ev);
}

function normalizeQuotaPayload(body: unknown): QuotaPayload | null {
  if (!body || typeof body !== "object") return null;
  const b = body as { code?: unknown; quota?: unknown; bindings?: unknown; message?: unknown };
  if (b.code !== "user_quota_exhausted") return null;
  const bindings = Array.isArray(b.bindings)
    ? b.bindings.filter((x): x is QuotaBinding => !!x && typeof x === "object")
    : [];
  return {
    code: "user_quota_exhausted",
    message: typeof b.message === "string" ? b.message : undefined,
    quota: typeof b.quota === "number" ? b.quota : bindings.length,
    bindings,
  };
}

/** 构造配额错误（ChatPane 捕获后按 source 分流：precheck 回滚气泡+还原输入；exec 保留气泡） */
export function makeQuotaError(payload: QuotaPayload, source: QuotaSource): QuotaError {
  return { quotaError: true, source, payload, message: quotaHeadline(payload) };
}

/** ChatPane catch 分支判定 */
export function isQuotaError(e: unknown): e is QuotaError {
  return !!e && typeof e === "object" && (e as { quotaError?: unknown }).quotaError === true;
}

/** 停止会话端点（复用既有 POST /threads/{sid}/stop，仅限本人会话） */
export function stopThreadUrl(agentBase: string, sessionId: string): string {
  return `${agentBase}/threads/${encodeURIComponent(sessionId)}/stop`;
}

// ---- 池水位与容量表单（§6.1 运维侧）----

/** 水位档：red = 池满（allocated 达 poolMax）；yellow = 预热不足（available < bufferMin）；
 *  unknown = status 未 reconcile（d19：REST 读不到时可能缺失）；normal = 正常。红优先于黄。 */
export function poolLevel(pool: {
  capacitySpec: { bufferMin: number; bufferMax: number; poolMin: number; poolMax: number };
  status?: { available?: number; allocated?: number } | null;
}): "red" | "yellow" | "unknown" | "normal" {
  const st = pool.status;
  if (!st || typeof st.available !== "number" || typeof st.allocated !== "number") return "unknown";
  const { bufferMin, poolMax } = pool.capacitySpec;
  if (st.allocated >= poolMax && poolMax > 0) return "red";
  if (st.available < bufferMin) return "yellow";
  return "normal";
}

export const POOL_LEVEL_TEXT: Record<string, string> = {
  red: "池满（allocated 达 poolMax）",
  yellow: "预热不足（available < bufferMin）",
  unknown: "状态未就绪（等待 reconcile）",
  normal: "正常",
};

export type CapacityForm = { bufferMin: string; bufferMax: string; poolMin: string; poolMax: string };

/** 容量表单校验：四个非负整数 + 链 0 ≤ poolMin ≤ bufferMin ≤ bufferMax ≤ poolMax；合法返回 null */
export function validateCapacityForm(f: CapacityForm): string | null {
  const nums: Record<string, number> = {};
  for (const k of ["poolMin", "bufferMin", "bufferMax", "poolMax"] as const) {
    const v = (f[k] ?? "").trim();
    if (v === "" || !/^\d+$/.test(v)) return `容量四值必须为非负整数（${k}）`;
    nums[k] = Number(v);
  }
  const { poolMin, bufferMin, bufferMax, poolMax } = nums;
  if (!(poolMin <= bufferMin && bufferMin <= bufferMax && bufferMax <= poolMax)) {
    return "必须满足 0 ≤ poolMin ≤ bufferMin ≤ bufferMax ≤ poolMax";
  }
  return null;
}

/** 容量表单 → 上游四键（校验通过后调用） */
export function capacityFromForm(f: CapacityForm): { bufferMin: number; bufferMax: number; poolMin: number; poolMax: number } {
  return {
    bufferMin: Number((f.bufferMin ?? "").trim()),
    bufferMax: Number((f.bufferMax ?? "").trim()),
    poolMin: Number((f.poolMin ?? "").trim()),
    poolMax: Number((f.poolMax ?? "").trim()),
  };
}

/** 池名（DNS-1123 label，与后端/上游校验同口径的前端预检） */
export function validatePoolName(name: string): string | null {
  if (!/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/.test(name) || name.length > 63) {
    return "池名须为小写字母数字与 - 的 DNS label（字母数字开头结尾，≤63 字符）";
  }
  return null;
}
