"use client";
// 运维侧 · 沙箱管理（sandbox-pool-opensandbox-design §6.1）：
//   - 顶部 StatCard：池总数 / warm available / allocated / Running 沙箱数（/summary 聚合）
//   - 池卡片区：capacitySpec vs status 水位条（黄 = available < bufferMin 预热不足；
//     红 = allocated 达 poolMax 池满）+ 调整容量（PUT 仅四值）/ 删除（确认对话，终止 warm Pod）/
//     创建池（名称/镜像（预填平台 SANDBOX_IMAGE）/容量四值；评审 d7：recycleStrategy 非
//     上游 POST /v1/pools 字段，契约不收，回收策略取 CRD 默认 Delete）
//   - 沙箱表格：id / state / 服务 / session / expiresAt 倒计时 / createdAt / 销毁（确认对话）
//   - 5s 轮询 + 手动刷新；错误引导：未配置 503 → /settings、配置错误 502、429/5xx 透传
import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { api, type SandboxListPage, type SandboxPool, type SandboxSummary } from "@/lib/api";
import { capacityFromForm, countdown, poolLevel, POOL_LEVEL_TEXT, validateCapacityForm, validatePoolName,
  type CapacityForm } from "@/lib/sandbox-quota";
import { Btn, Card, Note, PageHead, StatCard } from "@/components/ui";

// 沙箱列表每页条数（上游分页契约 page/pageSize，d15；对齐 audit 页同款常量模式）
const PAGE_SIZE = 50;

const LEVEL_STYLE: Record<string, string> = {
  red: "bg-red-500",
  yellow: "bg-amber-400",
  unknown: "bg-gray-200",
  normal: "bg-emerald-500",
};
const LEVEL_TEXT_STYLE: Record<string, string> = {
  red: "text-red-600",
  yellow: "text-amber-600",
  unknown: "text-gray-400",
  normal: "text-emerald-600",
};

const EMPTY_FORM: CapacityForm = { bufferMin: "", bufferMax: "", poolMin: "", poolMax: "" };

export default function AdminSandboxesPage() {
  const [summary, setSummary] = useState<SandboxSummary | null>(null);
  const [pools, setPools] = useState<SandboxPool[]>([]);
  const [sandboxes, setSandboxes] = useState<SandboxListPage | null>(null);
  const [stateFilter, setStateFilter] = useState("");
  const [page, setPage] = useState(1);
  const [err, setErr] = useState("");
  const [defaultImage, setDefaultImage] = useState("");
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // 池容量调整表单（打开的池名 + 四值输入 + 错误）
  const [adjusting, setAdjusting] = useState<string>("");
  const [capForm, setCapForm] = useState<CapacityForm>(EMPTY_FORM);
  const [capErr, setCapErr] = useState("");
  // 创建池表单
  const [creating, setCreating] = useState(false);
  const [createForm, setCreateForm] = useState<{ name: string; image: string } & CapacityForm>(
    { name: "", image: "", ...EMPTY_FORM });
  const [createErr, setCreateErr] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async (silent = false) => {
    if (!silent) setErr("");
    try {
      const [s, p, sb] = await Promise.all([
        api.admin.sandboxes.summary(),
        api.admin.sandboxes.listPools(),
        api.admin.sandboxes.listSandboxes(stateFilter, page, PAGE_SIZE),
      ]);
      setSummary(s);
      setPools(p.items ?? []);
      setSandboxes(sb);
      // 空尾页兜底：销毁末页最后一行导致当前页被清空时，自动回退到末页重拉
      // （page 变化重建 load 触发重拉；totalPages=0 时 Math.max 兜底回第 1 页，
      //   回到第 1 页后 page>1 不再成立，无死循环）
      if (sb.items.length === 0 && page > 1) setPage(Math.max(1, sb.pagination.totalPages));
      setErr("");
    } catch (e: any) {
      if (!silent) setErr(e?.message ?? String(e));
    }
  }, [stateFilter, page]);

  useEffect(() => { load(); }, [load]);
  // 5s 静默轮询（失败不打断页面，保留上次数据）
  useEffect(() => {
    timerRef.current = setInterval(() => { load(true); }, 5000);
    return () => { if (timerRef.current) clearInterval(timerRef.current); };
  }, [load]);
  // 创建池表单预填平台默认镜像（SANDBOX_IMAGE）
  useEffect(() => {
    api.getPlatformDefaults()
      .then((v) => setDefaultImage(v.values?.SANDBOX_IMAGE ?? ""))
      .catch(() => setDefaultImage(""));
  }, []);

  const openAdjust = (p: SandboxPool) => {
    setAdjusting(p.name);
    setCapErr("");
    setCapForm({
      bufferMin: String(p.capacitySpec.bufferMin), bufferMax: String(p.capacitySpec.bufferMax),
      poolMin: String(p.capacitySpec.poolMin), poolMax: String(p.capacitySpec.poolMax),
    });
  };

  const submitAdjust = async (name: string) => {
    const verr = validateCapacityForm(capForm);
    if (verr) { setCapErr(verr); return; }
    setBusy(true); setCapErr("");
    try {
      await api.admin.sandboxes.updatePool(name, capacityFromForm(capForm));
      setAdjusting("");
      await load();
    } catch (e: any) {
      setCapErr(e?.message ?? String(e));
    } finally {
      setBusy(false);
    }
  };

  const submitCreate = async () => {
    const nerr = validatePoolName(createForm.name.trim());
    if (nerr) { setCreateErr(nerr); return; }
    const verr = validateCapacityForm(createForm);
    if (verr) { setCreateErr(verr); return; }
    setBusy(true); setCreateErr("");
    try {
      await api.admin.sandboxes.createPool({
        name: createForm.name.trim(),
        image: createForm.image.trim() || undefined,
        capacitySpec: capacityFromForm(createForm),
      });
      setCreating(false);
      setCreateForm({ name: "", image: defaultImage, ...EMPTY_FORM });
      await load();
    } catch (e: any) {
      setCreateErr(e?.message ?? String(e));
    } finally {
      setBusy(false);
    }
  };

  const deletePool = async (p: SandboxPool) => {
    // 确认对话：池删除会终止池管理的 warm Pod（上游 controller 行为，§5.2/§6.1）
    if (!confirm(`删除池 ${p.name}？池内 warm Pod 将被终止，已认领的沙箱不受影响。`)) return;
    setBusy(true);
    try {
      await api.admin.sandboxes.deletePool(p.name);
      await load();
    } catch (e: any) {
      setErr(e?.message ?? String(e));
    } finally {
      setBusy(false);
    }
  };

  const destroySandbox = async (sb: SandboxListPage["items"][number]) => {
    if (!confirm(`销毁沙箱 ${sb.id}？该操作不可恢复。`)) return;
    setBusy(true);
    try {
      await api.admin.sandboxes.deleteSandbox(sb.id);
      await load();
    } catch (e: any) {
      setErr(e?.message ?? String(e));
    } finally {
      setBusy(false);
    }
  };

  // 未配置（503）/配置错误（502）引导：消息含 settings 指引时给入口链接
  const configGuide = /settings|not configured/i.test(err);

  return (
    <div data-testid="sandbox-admin">
      <PageHead title="沙箱管理" sub="OpenSandbox 池水位与沙箱实例治理（代理转发，平台不落状态）"
        actions={
          <button onClick={() => load()} data-testid="sandbox-refresh"
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-[13px] text-gray-600 hover:border-blue-200 hover:text-blue-600">
            刷新
          </button>
        } />
      {err && (
        <div data-testid="sandbox-admin-error" className="mb-3 rounded border border-red-200 bg-red-50 p-2.5 text-xs text-red-700">
          {err}
          {configGuide && (
            <Link href="/admin/settings" className="ml-2 underline">前往 /settings 平台配置</Link>
          )}
        </div>
      )}
      <Note>
        池是 opensandbox 侧的预热资源（bufferMin/Max 驱动 warm Pod 水位）；沙箱 TTL/GC 真相在
        opensandbox 服务端，本页仅代理转发与治理，无平台侧状态。水位条：<b className="text-amber-600">黄</b> = 预热不足（available &lt; bufferMin）、
        <b className="text-red-600">红</b> = 池满（allocated 达 poolMax）。管理操作均记入审计日志。
      </Note>

      <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4">
        <div data-testid="sb-stat-pools"><StatCard label="池总数" value={summary?.pools ?? "—"} /></div>
        <div data-testid="sb-stat-warm"><StatCard label="warm 可用" value={summary?.warmAvailable ?? "—"} /></div>
        <div data-testid="sb-stat-allocated"><StatCard label="已认领" value={summary?.allocated ?? "—"} /></div>
        <div data-testid="sb-stat-running"><StatCard label="Running 沙箱" value={summary?.runningSandboxes ?? "—"} /></div>
      </div>

      {/* 池卡片区 */}
      <Card title="池" className="mb-4">
        <div className="mb-3 flex items-center gap-2">
          <button onClick={() => { setCreating(true); setCreateForm({ name: "", image: defaultImage, ...EMPTY_FORM }); setCreateErr(""); }}
            data-testid="pool-create-open" disabled={busy}
            className="rounded-md bg-blue-600 px-3 py-1.5 text-[13px] text-white hover:bg-blue-700 disabled:opacity-50">
            创建池
          </button>
          <span className="text-xs text-gray-400">{pools.length === 0 ? "暂无池（可在 opensandbox 侧 /v1/pools 或此处创建）" : `${pools.length} 个池`}</span>
        </div>
        {creating && (
          <div data-testid="pool-create-form" className="mb-3 rounded-lg border border-blue-200 bg-blue-50/50 p-3">
            <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
              <label className="text-xs text-gray-600">池名（DNS label）
                <input data-testid="create-name" value={createForm.name} onChange={(e) => setCreateForm({ ...createForm, name: e.target.value })}
                  placeholder="oaf-pool-v2" className="mt-1 w-full rounded border p-1.5 text-[13px]" /></label>
              <label className="text-xs text-gray-600 md:col-span-2">镜像（缺省 = 平台默认 SANDBOX_IMAGE{defaultImage ? `：${defaultImage}` : "（未配置）"}）
                <input data-testid="create-image" value={createForm.image} onChange={(e) => setCreateForm({ ...createForm, image: e.target.value })}
                  placeholder={defaultImage || "opensandbox/code-interpreter:v1.1.0"} className="mt-1 w-full rounded border p-1.5 font-mono text-[12px]" /></label>
              {(["poolMin", "bufferMin", "bufferMax", "poolMax"] as const).map((k) => (
                <label key={k} className="text-xs text-gray-600">{k}
                  <input data-testid={`create-${k}`} value={createForm[k]} onChange={(e) => setCreateForm({ ...createForm, [k]: e.target.value })}
                    className="mt-1 w-full rounded border p-1.5 text-[13px]" /></label>
              ))}
            </div>
            {createErr && <p className="mt-2 text-xs text-red-600" data-testid="create-error">{createErr}</p>}
            <p className="mt-1 text-[11px] text-gray-400">Pod 模板由平台预设（官方 sample 形状）；容量链 0 ≤ poolMin ≤ bufferMin ≤ bufferMax ≤ poolMax</p>
            <div className="mt-2 flex gap-2">
              <button onClick={submitCreate} disabled={busy} data-testid="create-submit"
                className="rounded bg-blue-600 px-3 py-1 text-[13px] text-white hover:bg-blue-700 disabled:opacity-50">创建</button>
              <button onClick={() => setCreating(false)} className="rounded border px-3 py-1 text-[13px] text-gray-600">取消</button>
            </div>
          </div>
        )}
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
          {pools.map((p) => {
            const level = poolLevel(p);
            const st = p.status;
            const pct = st && st.total ? Math.min(100, Math.round(((st.allocated ?? 0) / Math.max(1, st.total)) * 100)) : 0;
            return (
              <div key={p.name} data-testid={`pool-card-${p.name}`} className="rounded-lg border border-gray-200 p-3">
                <div className="flex items-center gap-2">
                  <span className="font-medium">{p.name}</span>
                  <span className={`text-[11px] ${LEVEL_TEXT_STYLE[level]}`} data-testid={`pool-level-${p.name}`}>
                    {POOL_LEVEL_TEXT[level]}
                  </span>
                </div>
                {/* 水位条：allocated 占 total 比例 + 黄红规则（poolLevel） */}
                <div className="mt-2 h-2 w-full rounded-full bg-gray-100">
                  <div className={`h-2 rounded-full ${LEVEL_STYLE[level]}`} style={{ width: `${pct}%` }} />
                </div>
                <p className="mt-1.5 text-[11px] text-gray-500">
                  capacitySpec: poolMin {p.capacitySpec.poolMin} · bufferMin {p.capacitySpec.bufferMin} · bufferMax {p.capacitySpec.bufferMax} · poolMax {p.capacitySpec.poolMax}
                  {st && <> · available <b data-testid={`pool-available-${p.name}`}>{st.available ?? 0}</b> / allocated {st.allocated ?? 0} / total {st.total ?? 0}</>}
                </p>
                {adjusting === p.name ? (
                  <div className="mt-2 rounded border border-blue-200 bg-blue-50/50 p-2" data-testid={`pool-adjust-form-${p.name}`}>
                    <div className="grid grid-cols-4 gap-2">
                      {(["poolMin", "bufferMin", "bufferMax", "poolMax"] as const).map((k) => (
                        <label key={k} className="text-[11px] text-gray-600">{k}
                          <input data-testid={`cap-${k}`} value={capForm[k]} onChange={(e) => setCapForm({ ...capForm, [k]: e.target.value })}
                            className="mt-0.5 w-full rounded border p-1 text-[12px]" /></label>
                      ))}
                    </div>
                    {capErr && <p className="mt-1 text-[11px] text-red-600" data-testid="cap-error">{capErr}</p>}
                    <div className="mt-1.5 flex gap-2">
                      <button onClick={() => submitAdjust(p.name)} disabled={busy} data-testid={`cap-submit-${p.name}`}
                        className="rounded bg-blue-600 px-2.5 py-0.5 text-[12px] text-white disabled:opacity-50">保存容量</button>
                      <button onClick={() => setAdjusting("")} className="rounded border px-2.5 py-0.5 text-[12px] text-gray-600">取消</button>
                    </div>
                  </div>
                ) : (
                  <div className="mt-2 flex gap-2">
                    <button onClick={() => openAdjust(p)} data-testid={`pool-adjust-${p.name}`} disabled={busy}
                      className="rounded border border-gray-300 px-2.5 py-0.5 text-[12px] text-gray-600 hover:border-blue-200 hover:text-blue-600 disabled:opacity-50">
                      调整容量
                    </button>
                    <button onClick={() => deletePool(p)} data-testid={`pool-delete-${p.name}`} disabled={busy}
                      className="rounded border border-red-200 px-2.5 py-0.5 text-[12px] text-red-600 hover:bg-red-50 disabled:opacity-50">
                      删除
                    </button>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </Card>

      {/* 沙箱表格 */}
      <Card title="沙箱实例">
        <div className="mb-2 flex items-center gap-2">
          {/* 切换筛选同时重置页码（React 自动批处理：一次重渲染一次请求） */}
          <select value={stateFilter} onChange={(e) => { setStateFilter(e.target.value); setPage(1); }} data-testid="sandbox-state-filter"
            className="rounded-md border border-gray-300 px-2 py-1 text-[13px]">
            <option value="">全部状态</option>
            {/* 上游状态枚举（release-1.1.0 server/opensandbox_server/api/schema.py:428）：
                Pending/Running/Pausing/Paused/Resuming/Stopping/Terminated/Failed——
                常用三态供快捷筛选，其余经「全部状态」覆盖 */}
            <option value="Running">Running</option>
            <option value="Pending">Pending</option>
            <option value="Paused">Paused</option>
          </select>
          <span className="text-xs text-gray-400">{sandboxes ? `${sandboxes.pagination.totalItems} 个` : ""}</span>
        </div>
        <table className="w-full text-sm" data-testid="sandbox-table">
          <thead>
            <tr className="border-b border-gray-200 bg-gray-50 text-left text-xs text-gray-500">
              <th className="p-2.5">沙箱 ID</th><th className="p-2.5">状态</th><th className="p-2.5">服务</th>
              <th className="p-2.5">会话</th><th className="p-2.5">到期</th><th className="p-2.5">创建时间</th>
              <th className="p-2.5 text-right"></th>
            </tr>
          </thead>
          <tbody>
            {(sandboxes?.items ?? []).map((sb) => (
              <tr key={sb.id} className="border-b border-gray-100 last:border-0" data-testid={`sandbox-row-${sb.id}`}>
                <td className="p-2.5 font-mono text-[12px]">{sb.id}</td>
                <td className="p-2.5">{sb.status?.state ?? "—"}</td>
                <td className="p-2.5">{sb.metadata?.service ?? "—"}</td>
                <td className="p-2.5 font-mono text-[12px]">{sb.metadata?.session_id ?? "—"}</td>
                <td className="p-2.5 text-[12px]" title={sb.expiresAt ?? ""}>{countdown(sb.expiresAt)}</td>
                <td className="p-2.5 text-[12px] text-gray-500">{sb.createdAt?.replace("T", " ").slice(0, 19) ?? "—"}</td>
                <td className="p-2.5 text-right">
                  <button onClick={() => destroySandbox(sb)} data-testid={`sandbox-destroy-${sb.id}`} disabled={busy}
                    className="rounded border border-red-200 px-2 py-0.5 text-[12px] text-red-600 hover:bg-red-50 disabled:opacity-50">
                    销毁
                  </button>
                </td>
              </tr>
            ))}
            {(sandboxes?.items ?? []).length === 0 && (
              <tr><td colSpan={7} className="p-4 text-center text-sm text-gray-400">暂无沙箱</td></tr>
            )}
          </tbody>
        </table>
        {/* 分页 footer（仿 audit 页）：上一页/下一页随上游 pagination 翻页；
            表头计数为 totalItems（全量总数），分页后该数字已真实可导航 */}
        <div className="mt-3 flex items-center justify-end gap-2 text-xs text-gray-500">
          <Btn sm data-testid="sandbox-page-prev" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</Btn>
          <span>{page} / {Math.max(1, sandboxes?.pagination.totalPages ?? 1)}</span>
          <Btn sm data-testid="sandbox-page-next" disabled={!sandboxes?.pagination.hasNextPage} onClick={() => setPage(page + 1)}>下一页</Btn>
        </div>
      </Card>
    </div>
  );
}
