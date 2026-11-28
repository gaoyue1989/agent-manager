"use client";
// 工具调用观测面板（共享组件，tool-call-observability-design §四）：
// 用户侧观测大盘（工作空间范围）与运维侧全局页共用同一数据结构 + 展示形态，
// 仅数据获取函数（fetcher）不同。指标来自平台观测栈（backend PromQL 聚合），
// 明细来自各 agent tool_audit_log（backend fan-out 合并）。
import { useCallback, useEffect, useRef, useState } from "react";
import { type ToolCallStatsView, type ToolCallServiceRow, type ToolCallToolRow, type ToolCallTrendPoint } from "@/lib/api";
import { Btn, Card, StatCard } from "@/components/ui";

const RANGES = ["24h", "7d", "30d"];
const RANGE_LABELS: Record<string, string> = { "24h": "近 24 小时", "7d": "近 7 天", "30d": "近 30 天" };
const REFRESH_MS = 10000;

export function ToolCallStatsPanel({ fetcher, testid }: {
  fetcher: (range: string) => Promise<ToolCallStatsView>;
  testid: string;
}) {
  const [range, setRange] = useState("24h");
  const [data, setData] = useState<ToolCallStatsView | null>(null);
  const [error, setError] = useState("");
  const [updatedAt, setUpdatedAt] = useState("");
  const fetcherRef = useRef(fetcher);
  const reqSeqRef = useRef(0);
  const loadRef = useRef<(silent: boolean) => void>(() => {});

  useEffect(() => {
    fetcherRef.current = fetcher;
  }, [fetcher]);

  const load = useCallback(async (silent: boolean, r: string) => {
    const seq = ++reqSeqRef.current;
    if (!silent) setError("");
    try {
      const view = await fetcherRef.current(r);
      if (seq !== reqSeqRef.current) return;
      setData(view);
      setError("");
      setUpdatedAt(new Date().toLocaleTimeString("zh-CN", { hour12: false }));
    } catch (e: any) {
      if (seq !== reqSeqRef.current) return;
      // 静默轮询失败保留旧数据（页面级门控口径，#377/#379）；首载失败展示错误态
      if (!silent) setError(e.message || "加载失败");
    }
  }, []);

  useEffect(() => {
    loadRef.current = (silent: boolean) => { load(silent, range); };
    loadRef.current(false);
    const timer = setInterval(() => loadRef.current(true), REFRESH_MS);
    return () => {
      clearInterval(timer);
      reqSeqRef.current += 1;
    };
  }, [load, range]);

  return (
    <div>
      {/* 窗口档位 + 手动刷新 */}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        {RANGES.map((r) => (
          <button key={r} onClick={() => setRange(r)}
            data-testid={`${testid}-range-${r}`}
            className={`rounded-md border px-3 py-1.5 text-[13px] ${r === range
              ? "border-blue-600 bg-blue-600 text-white"
              : "border-gray-300 bg-white text-gray-600 hover:border-blue-200 hover:text-blue-600"}`}>
            {RANGE_LABELS[r]}
          </button>
        ))}
        <span className="text-xs text-gray-400">
          每 10s 自动刷新{updatedAt && ` · 更新于 ${updatedAt}`}
        </span>
        <div className="ml-auto">
          <Btn onClick={() => loadRef.current(false)} testid={`${testid}-refresh`}>刷新</Btn>
        </div>
      </div>

      {error && (
        <div className="mb-3 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-[13px] text-red-600"
          data-testid={`${testid}-error`}>
          观测数据加载失败：{error}
        </div>
      )}

      {data && (
        <>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <StatCard label="总调用次数" value={fmtNum(data.totals.calls)} />
            <StatCard label="失败次数" value={fmtNum(data.totals.errors)}
              danger={data.totals.errors > 0} />
            <StatCard label="失败率"
              value={`${(data.totals.errorRate * 100).toFixed(1)}%`}
              danger={data.totals.errorRate > 0.1} />
            <StatCard label="涉及工具" value={data.totals.toolKinds}
              sub={`${data.totals.serviceNum} 个 Agent 有调用`} />
          </div>

          <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            <Card title="工具调用排行">
              {data.byTool.length === 0 ? <p className="text-sm text-gray-400">窗口内暂无工具调用</p>
                : <div className="space-y-3">{data.byTool.slice(0, 15).map((r) =>
                    <ToolBar key={r.tool} row={r}
                      max={Math.max(1, ...data.byTool.slice(0, 15).map((x) => x.calls))}
                      testid={`${testid}-tool`} />)}</div>}
            </Card>
            <Card title="调用趋势">
              <TrendChart trend={data.trend} testid={`${testid}-trend`} />
            </Card>
          </div>

          <Card title="Agent 维度统计" className="mt-4">
            {data.byService.length === 0 ? <p className="text-sm text-gray-400">暂无已发布 Agent</p> : (
              <table className="w-full text-sm" data-testid={`${testid}-svc-table`}>
                <thead>
                  <tr className="border-b text-left text-xs text-gray-400">
                    <th className="py-2 font-normal">Agent</th>
                    <th className="py-2 font-normal">状态</th>
                    <th className="py-2 font-normal text-right">调用次数</th>
                    <th className="py-2 font-normal text-right">失败</th>
                    <th className="py-2 font-normal">数据</th>
                  </tr>
                </thead>
                <tbody>
                  {data.byService.map((r) => <ServiceRow key={r.service} row={r} testid={`${testid}-svc`} />)}
                </tbody>
              </table>
            )}
          </Card>

          <Card title="最近调用明细" className="mt-4">
            {data.recent.length === 0 ? <p className="text-sm text-gray-400">暂无明细</p> : (
              <table className="w-full text-sm" data-testid={`${testid}-recent-table`}>
                <thead>
                  <tr className="border-b text-left text-xs text-gray-400">
                    <th className="py-2 font-normal">时间</th>
                    <th className="py-2 font-normal">Agent</th>
                    <th className="py-2 font-normal">工具</th>
                    <th className="py-2 font-normal">会话</th>
                    <th className="py-2 font-normal">结果</th>
                  </tr>
                </thead>
                <tbody>
                  {data.recent.map((r, i) => (
                    <tr key={`${r.service}-${r.at}-${i}`} className="border-b border-gray-50 hover:bg-gray-50"
                      data-testid={`${testid}-recent-row`}>
                      <td className="py-1.5 tabular-nums text-gray-500">{fmtAt(r.at)}</td>
                      <td className="py-1.5">{r.name || r.service}</td>
                      <td className="py-1.5 font-mono text-[12px]">{r.tool}</td>
                      <td className="py-1.5 font-mono text-[12px] text-gray-400" title={r.sessionId}>{short(r.sessionId)}</td>
                      <td className="py-1.5">
                        <span className={`rounded-full px-2 py-0.5 text-[11px] ${r.result === "error"
                          ? "bg-red-50 text-red-600" : r.result === "success" ? "bg-green-50 text-green-600"
                          : "bg-gray-100 text-gray-400"}`}>
                          {r.result === "error" ? "失败" : r.result === "success" ? "成功" : "未落终态"}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
        </>
      )}
    </div>
  );
}

/** 工具排行行：横向水位条（宽度相对榜首，照沙箱池水位条形态，纯 Tailwind） */
function ToolBar({ row, max, testid }: { row: ToolCallToolRow; max: number; testid: string }) {
  return (
    <div data-testid={`${testid}-row-${row.tool}`}>
      <div className="flex items-baseline justify-between text-[13px]">
        <span className="truncate font-mono">{row.tool}</span>
        <span className="tabular-nums text-gray-500">
          {fmtNum(row.calls)} 次
          {row.errors > 0 && <em className="ml-1 not-italic text-red-500">失败 {fmtNum(row.errors)}</em>}
        </span>
      </div>
      <div className="mt-1 h-2 rounded-full bg-gray-100">
        <div className="h-2 rounded-full bg-blue-500" style={{ width: `${(row.calls / max) * 100}%` }} />
      </div>
    </div>
  );
}

/** Agent 维度行 */
function ServiceRow({ row, testid }: { row: ToolCallServiceRow; testid: string }) {
  return (
    <tr className="border-b border-gray-50 hover:bg-gray-50" data-testid={`${testid}-row-${row.service}`}>
      <td className="py-1.5">{row.name || row.service}</td>
      <td className="py-1.5 text-gray-500">{row.status}</td>
      <td className="py-1.5 text-right tabular-nums">{fmtNum(row.calls)}</td>
      <td className={`py-1.5 text-right tabular-nums ${row.errors > 0 ? "text-red-600" : ""}`}>{fmtNum(row.errors)}</td>
      <td className="py-1.5">
        {row.hasData
          ? <span className="text-green-600">已上报</span>
          : <span className="text-gray-400" title="窗口内无指标（离线 / 零调用 / 存量未 republish 未接入抓取）">无数据</span>}
      </td>
    </tr>
  );
}

/** 趋势柱状：蓝=调用，红=失败（底部叠加，零依赖 div 柱状） */
function fmtBucket(p?: ToolCallTrendPoint): string {
  if (!p) return "";
  if (p.timestamp) {
    const d = new Date(p.timestamp);
    if (!Number.isNaN(d.getTime())) {
      const pad = (n: number) => String(n).padStart(2, "0");
      return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
    }
  }
  return p.bucket ? `${p.bucket} UTC` : "";
}

function TrendChart({ trend, testid }: { trend: ToolCallStatsView["trend"]; testid: string }) {
  const max = Math.max(1, ...trend.map((p) => p.calls));
  return (
    <div>
      <div className="flex h-36 items-end gap-1" data-testid={testid}>
        {trend.map((p) => (
          <div key={p.timestamp || p.bucket} className="group relative h-full flex-1"
            title={`${fmtBucket(p)} · 调用 ${fmtNum(p.calls)} · 失败 ${fmtNum(p.errors)}`}>
            <div className="absolute bottom-0 w-full rounded-t bg-blue-500/80"
              style={{ height: `${(p.calls / max) * 100}%` }} />
            <div className="absolute bottom-0 w-full rounded-b bg-red-500/90"
              style={{ height: `${(p.errors / max) * 100}%` }} />
          </div>
        ))}
      </div>
      <div className="mt-1 flex justify-between text-[11px] text-gray-400">
        <span>{fmtBucket(trend[0])}</span>
        <span className="flex items-center gap-3">
          <em className="not-italic"><i className="mr-1 inline-block h-2 w-2 rounded-sm bg-blue-500/80" />调用</em>
          <em className="not-italic"><i className="mr-1 inline-block h-2 w-2 rounded-sm bg-red-500/90" />失败</em>
        </span>
        <span>{fmtBucket(trend[trend.length - 1])}</span>
      </div>
    </div>
  );
}

function fmtNum(n: number): string {
  return Number.isInteger(n) ? String(n) : n.toFixed(1);
}

/** framework created_at（"2026-11-21 10:00:00" / ISO 皆可）→ 本地展示 */
function fmtAt(at: string): string {
  return (at || "").replace("T", " ").substring(0, 19);
}

function short(s: string): string {
  if (!s) return "-";
  return s.length > 14 ? s.substring(0, 14) + "…" : s;
}
