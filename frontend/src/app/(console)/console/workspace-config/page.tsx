"use client";
// 用户侧 · 工作空间配置管理页（跨集群导出 U5，契约 §7.1 / 设计 §10）：
// 运维侧统一入口维护导出所需的全部运行时默认值（per-workspace，导出页预填与锁定来源）。
// 页签 = 运行时默认 / MCP 连接器模板(soon) / 模型参数 / 沙箱参数 / 仓库与路由 / API(soon)；
// 运行时默认 GET 掩码视图 + PUT 三态保存（非空=设置、空串=删除、缺失=不变，敏感键留空=不变）。
// 权限：GET = workspace 成员；PUT = owner/admin（后端 requireWsRole，前端同口径禁用输入）。
import { Suspense, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { api, type RuntimeDefaultEntry } from "@/lib/api";
import { useConsoleWs, useConsoleWsRole } from "@/lib/useMe";
import { Btn, Card, ComingSoon, Note, PageHead } from "@/components/ui";
import {
  buildRuntimeDefaultPayload, entryConfigured, groupFieldsByCategory,
  routingSuffixIssue, RUNTIME_DEFAULT_FIELDS, WS_CONFIG_TABS,
} from "@/lib/export-view";

function WorkspaceConfigInner() {
  const ws = useConsoleWs();
  const role = useConsoleWsRole();
  const params = useSearchParams();
  const tab = params.get("tab") ?? "runtime";
  const writable = role === "owner" || role === "admin";

  const [entries, setEntries] = useState<Record<string, RuntimeDefaultEntry>>({});
  const [plain, setPlain] = useState<Record<string, string>>({}); // 非敏感键输入（空串 = 删除）
  const [secret, setSecret] = useState<Record<string, string>>({}); // 敏感键新输入（空 = 不变）
  const [msg, setMsg] = useState("");
  const [saving, setSaving] = useState(false);
  // loadedFor：当前 ws 视图已加载完成时 = 该 ws id；null = 未加载/加载中/加载失败。
  // 保存前置条件（issue #229）：视图未就绪绝不能以空视图为事实源组装三态负载（空串 = 删除会静默清掉已配置键）
  const [loadedFor, setLoadedFor] = useState<number | null>(null);
  const [reloadTick, setReloadTick] = useState(0); // 保存成功后触发重载；加载失败后「重新加载」重试入口

  // 单一加载 effect（deps [ws, reloadTick]）：起始先置 loadedFor=null，切换/重载间隙即禁保存，
  // 关闭「切换工作空间间隙把旧空间配置复制进新空间」的第二触发面；alive 标志丢弃切换后晚到的旧 ws
  // 响应（先例 agents/[id] PodTable、export 页轮询）。起始不清 msg、成功时清，与原 load() 语义一致
  useEffect(() => {
    if (!ws) return;
    let alive = true;
    setLoadedFor(null);
    const run = async () => {
      try {
        const data = await api.workspaceConfig.runtimeDefaults(ws);
        if (!alive) return;
        setEntries(data ?? {});
        // 非敏感键以服务端当前值预填；敏感键保持空输入（留空 = 不变）
        const p: Record<string, string> = {};
        for (const f of RUNTIME_DEFAULT_FIELDS) {
          if (!f.sensitive) p[f.envKey] = data?.[f.envKey]?.value ?? "";
        }
        setPlain(p);
        setSecret({});
        setLoadedFor(ws);
        setMsg("");
      } catch (e: any) { if (alive) setMsg(`加载失败: ${e.message}`); }
    };
    run();
    return () => { alive = false; };
  }, [ws, reloadTick]);

  // 当前页签涵盖的字段，按分类分组（每键恰属一个页签，见 WS_CONFIG_TABS）
  const tabDef = WS_CONFIG_TABS.find((t) => t.id === tab) ?? WS_CONFIG_TABS[0];
  const tabFields = useMemo(
    () => RUNTIME_DEFAULT_FIELDS.filter((f) => tabDef.categories.includes(f.category)),
    [tabDef]);
  const groups = useMemo(() => groupFieldsByCategory(tabFields), [tabFields]);

  // 路由后缀客户端预检（routing 分类专用，config.go:150-172 简化口径）
  const suffixError = tabFields.some((f) => f.envKey === "ROUTING_HOST_SUFFIX")
    ? routingSuffixIssue((plain["ROUTING_HOST_SUFFIX"] ?? "").trim())
    : "";

  const save = async () => {
    // 纵深防御（独立于按钮禁用）：视图未就绪时拒绝提交——空视图会让 buildRuntimeDefaultPayload
    // 把全部非敏感键按「空串 = 删除」组装进负载，静默清掉同页签全部已配置默认值（issue #229）
    if (loadedFor === null || loadedFor !== ws) {
      setMsg("保存失败：配置尚未加载完成，已阻止提交（空视图会按三态语义删除已配置键）");
      return;
    }
    if (suffixError) { setMsg(`保存失败：${suffixError}`); return; }
    // 三态组装（仅当前页签字段；未涵盖键缺失 = 不变）+「有值 → 清空」确认
    const { values, clearing } = buildRuntimeDefaultPayload(tabFields, plain, secret, entries);
    if (clearing.length > 0 && !confirm(`将清空已配置的默认值：${clearing.join("、")}（空串 = 删除，导出页将不再继承这些键）。确定？`)) return;
    setSaving(true);
    try {
      await api.workspaceConfig.updateRuntimeDefaults(ws, values);
      setMsg("已保存。默认值作为导出页预填与 values L1 层来源；不影响任何已发布服务");
      setReloadTick((t) => t + 1); // 触发加载 effect 重载（成功后清 msg，与原 await load() 表现一致）
    } catch (e: any) { setMsg(`保存失败: ${e.message}`); }
    finally { setSaving(false); }
  };

  if (!ws) return null;
  return (
    <div data-testid="ws-config-page" className="space-y-4">
      <PageHead
        title="空间配置"
        sub={<>工作空间 #{ws} 运行时默认配置（per-workspace，面向生产导出）。<b>导出发布页全部继承本页且默认值在导出页锁定</b>——单次导出不能悄悄改基线，要改只能在此修改。</>} />

      {/* 页签（URL query 同步，刷新保位；先例 agents/[id] 详情页） */}
      <div className="mb-4 flex gap-0.5 overflow-x-auto border-b border-gray-200" data-testid="ws-config-tabs">
        {WS_CONFIG_TABS.map((t) => {
          const soon = t.categories.length === 0;
          return (
            <Link key={t.id} href={`/console/workspace-config?tab=${t.id}`} data-testid={`ws-config-tab-${t.id}`}
              className={`whitespace-nowrap border-b-2 px-3.5 py-2 text-[13px] ${tab === t.id ? "border-blue-600 font-medium text-blue-600" : "border-transparent text-gray-500 hover:text-gray-900"}`}>
              {t.label}{soon && <i className="ml-1 rounded border border-gray-200 px-1 text-[10px] not-italic text-gray-400">规划中</i>}
            </Link>
          );
        })}
      </div>

      {msg && <div data-testid="ws-config-msg" className="rounded border border-amber-200 bg-amber-50 p-2 text-xs text-amber-700">{msg}</div>}
      {!writable && <Note tone="warn">member 角色只读（owner/admin 可修改；后端 PUT 同样 403）。</Note>}

      {tabDef.categories.length === 0 ? (
        <ComingSoon icon="🔌" title={`${tabDef.label}（v1 占位）`}
          desc="契约 §9-D4：MCP 连接器模板 / API 页签 v1 不落地，导出所需键空间经运行时默认单端点承载，v2 再拆端点。" />
      ) : (
        <>
          <Note>三态保存语义：出现非空 = 设置、清空非敏感键 = 删除（需确认）、敏感键留空 = 保持不变。敏感键仅掩码展示，值以服务端为准。</Note>
          {groups.map((g) => (
            <Card key={g.category} title={g.title}>
              <div className="grid grid-cols-1 gap-3">
                {g.fields.map((f) => {
                  // hasValue 优先（敏感键 value 恒缺省，仅 hasValue 标记已配置），旧视图回退 value
                  const had = entryConfigured(entries[f.envKey]);
                  return (
                    <label key={f.envKey} className="grid grid-cols-[200px_1fr] items-start gap-3 text-sm">
                      <span className="pt-1.5 text-gray-700">
                        {f.label}
                        {f.sensitive && <span className="ml-1 rounded border bg-gray-100 px-1 text-[10px] text-gray-500">🔒 Secret</span>}
                        <code className="block text-[10px] text-gray-400">{f.envKey}</code>
                      </span>
                      {f.sensitive ? (
                        <input type="password" autoComplete="new-password" disabled={!writable}
                          value={secret[f.envKey] ?? ""}
                          onChange={(e) => setSecret({ ...secret, [f.envKey]: e.target.value })}
                          placeholder={had ? "已配置（掩码），留空保持不变" : "未配置"}
                          data-testid={`ws-config-field-${f.envKey}`}
                          className="w-full rounded border border-gray-300 p-1.5 disabled:bg-gray-100" />
                      ) : f.multiline ? (
                        <textarea rows={2} disabled={!writable}
                          value={plain[f.envKey] ?? ""}
                          onChange={(e) => setPlain({ ...plain, [f.envKey]: e.target.value })}
                          placeholder={f.placeholder || (had ? "" : "未配置（留空 = 不下发该键）")}
                          data-testid={`ws-config-field-${f.envKey}`}
                          className="w-full rounded border border-gray-300 p-1.5 font-mono text-xs disabled:bg-gray-100" />
                      ) : (
                        <input disabled={!writable}
                          value={plain[f.envKey] ?? ""}
                          onChange={(e) => setPlain({ ...plain, [f.envKey]: e.target.value })}
                          placeholder={f.placeholder || (had ? "" : "未配置（留空 = 不下发该键）")}
                          data-testid={`ws-config-field-${f.envKey}`}
                          className="w-full rounded border border-gray-300 p-1.5 disabled:bg-gray-100" />
                      )}
                    </label>
                  );
                })}
              </div>
            </Card>
          ))}
          {suffixError && <p className="text-xs text-red-600">ROUTING_HOST_SUFFIX：{suffixError}</p>}
          {writable && (
            <div className="flex items-center gap-3">
              <Btn kind="pri" disabled={saving || loadedFor !== ws} onClick={save} testid="ws-config-save">
                {saving ? "保存中…" : loadedFor !== ws ? "加载中…" : `保存（${tabDef.label}）`}
              </Btn>
              {/* 加载失败/在途禁保存后的自恢复入口（否则瞬时 GET 失败只能导航离开再回来重挂载恢复） */}
              {loadedFor === null && <Btn onClick={() => setReloadTick((t) => t + 1)} testid="ws-config-reload">重新加载</Btn>}
              <span className="text-xs text-gray-400">变更记 UpdatedBy 留痕；导出页对默认值只读。</span>
            </div>
          )}
          <p className="text-xs text-gray-400">
            与平台默认配置（运维侧 /settings，面向测试集群发布表单预填、全服务共用、不入导出产物）的区别：本页 per-workspace、面向生产导出，是导出页锁定基线。
          </p>
        </>
      )}
    </div>
  );
}

export default function WorkspaceConfigPage() {
  return (
    <Suspense fallback={<p className="text-sm text-gray-400">加载中…</p>}>
      <WorkspaceConfigInner />
    </Suspense>
  );
}
