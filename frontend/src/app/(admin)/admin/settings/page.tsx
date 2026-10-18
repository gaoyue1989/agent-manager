"use client";
// 平台默认配置：作为「发布新服务 / 编辑 env」时的表单默认填入（R3 语义）。
// 修改默认配置不影响任何已发布服务——服务只携带自己发布/编辑时显式确认的 env；
// 敏感键（模板 Sensitive）在展示视图掩码，经发布页/详情页填入后落服务 Secret。
// 设计见 docs/design/platform-default-config-secret-design.md
import { useCallback, useEffect, useState } from "react";
import { api, PlatformConfigField, PlatformConfigView } from "@/lib/api";

export default function SettingsPage() {
  const [view, setView] = useState<PlatformConfigView | null>(null);
  const [plain, setPlain] = useState<Record<string, string>>({}); // 非敏感键当前输入
  const [secret, setSecret] = useState<Record<string, string>>({}); // 敏感键新输入（空 = 不变）
  const [msg, setMsg] = useState("");
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      const v = await api.getPlatformConfig();
      setView(v);
      const p: Record<string, string> = {};
      for (const g of v.groups) for (const f of g.fields) if (!f.sensitive) p[f.envKey] = f.value ?? "";
      setPlain(p);
    } catch (e: any) {
      setMsg(`加载失败: ${e.message}`);
    }
  }, []);
  useEffect(() => { load(); }, [load]);

  // 组装保存值：非敏感键全量上送（空串=清除，需确认）；敏感键仅上送非空新输入（留空=不变）
  const buildValues = (): Record<string, string> | null => {
    const values: Record<string, string> = {};
    for (const g of view?.groups ?? []) {
      for (const f of g.fields) {
        if (f.sensitive) {
          if (secret[f.envKey]?.trim()) values[f.envKey] = secret[f.envKey].trim();
        } else {
          values[f.envKey] = plain[f.envKey] ?? "";
        }
      }
    }
    return values;
  };

  const save = async () => {
    if (!view) return;
    // 清除非敏感必填键需确认（后端同样校验清除必填键直接 400）
    const clearing = (view.groups ?? []).flatMap((g) => g.fields)
      .filter((f: PlatformConfigField) => !f.sensitive && f.required && !(plain[f.envKey] ?? "").trim());
    if (clearing.length > 0 && !confirm(`将清空必填配置：${clearing.map((f) => f.label).join("、")}，确定？`)) return;
    setSaving(true);
    setMsg("");
    try {
      await api.updatePlatformConfig(buildValues()!);
      setMsg("已保存。默认值将在下次发布新服务或编辑 env 填入时提供；不影响任何已发布服务");
      setSecret({});
      await load();
    } catch (e: any) {
      setMsg(`保存失败: ${e.message}`);
    } finally {
      setSaving(false);
    }
  };

  if (!view) return <p className="text-gray-500" data-testid="cfg-page">{msg || "加载中…"}</p>;

  return (
    <div data-testid="cfg-page" className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold">平台默认配置</h1>
        <p className="text-xs text-gray-500 mt-1">
          作为「发布新服务 / 编辑环境变量」时的表单默认填入模板（带锁字段经填入后存入服务 Secret）。
          修改默认配置<b>不影响任何已发布服务</b>，只影响之后的填入内容。
          {view.updatedAt && <span className="ml-2">最近更新：{new Date(view.updatedAt).toLocaleString()}</span>}
        </p>
      </div>
      {msg && <div data-testid="cfg-msg" className="text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded p-2">{msg}</div>}

      {view.groups.map((g) => (
        <section key={g.name} data-testid={`cfg-group-${g.name}`} className="bg-white border rounded p-4">
          <h2 className="font-medium mb-3">{g.title}</h2>
          <div className="grid grid-cols-1 gap-3">
            {g.fields.map((f) => (
              <label key={f.envKey} className="text-sm grid grid-cols-[180px_1fr] items-start gap-3">
                <span className="pt-1.5 text-gray-700">
                  {f.label}
                  {f.required && <span className="text-red-500 ml-0.5">*</span>}
                  {f.sensitive && <span className="ml-1 text-[10px] px-1 rounded bg-gray-100 border text-gray-500">🔒 Secret</span>}
                  <code className="block text-[10px] text-gray-400">{f.envKey}</code>
                </span>
                {f.sensitive ? (
                  <input
                    type="password" autoComplete="new-password"
                    value={secret[f.envKey] ?? ""}
                    onChange={(e) => setSecret({ ...secret, [f.envKey]: e.target.value })}
                    placeholder={f.hasValue ? "已配置，留空保持不变" : "未配置"}
                    data-testid={`cfg-field-${f.envKey}`}
                    className="border rounded p-1.5 w-full" />
                ) : (
                  f.multiline ? (
                    <textarea
                      value={plain[f.envKey] ?? ""}
                      onChange={(e) => setPlain({ ...plain, [f.envKey]: e.target.value })}
                      placeholder={f.placeholder || (f.hasValue ? "" : "未配置")}
                      rows={2} data-testid={`cfg-field-${f.envKey}`}
                      className="border rounded p-1.5 w-full font-mono text-xs" />
                  ) : (
                    <input
                      value={plain[f.envKey] ?? ""}
                      onChange={(e) => setPlain({ ...plain, [f.envKey]: e.target.value })}
                      placeholder={f.placeholder || (f.hasValue ? "" : "未配置")}
                      data-testid={`cfg-field-${f.envKey}`}
                      className="border rounded p-1.5 w-full" />
                  )
                )}
              </label>
            ))}
          </div>
        </section>
      ))}

      <button onClick={save} disabled={saving} data-testid="cfg-save"
        className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white rounded px-5 py-2 text-sm">保存</button>
    </div>
  );
}
