"use client";
// 平台默认配置：全部业务 agent 共享的 redis/mysql/llm/sandbox 兜底配置。
// 敏感键（模板 Sensitive）存 K8s Secret、页面掩码（留空=保持不变）；非敏感键存 ConfigMap。
// 保存后需"滚动重启运行中服务"才对存量服务生效（env 为启动期绑定）。
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

  const save = async (restart: boolean) => {
    if (!view) return;
    // 清除非敏感必填键需确认（后端同样校验清除必填键直接 400）
    const clearing = (view.groups ?? []).flatMap((g) => g.fields)
      .filter((f: PlatformConfigField) => !f.sensitive && f.required && !(plain[f.envKey] ?? "").trim());
    if (clearing.length > 0 && !confirm(`将清空必填配置：${clearing.map((f) => f.label).join("、")}，确定？`)) return;
    setSaving(true);
    setMsg("");
    try {
      await api.updatePlatformConfig(buildValues()!);
      if (restart) {
        if (!confirm("将滚动重启全部运行中的服务（配置重启后才生效），确定？")) {
          setMsg("已保存配置，未重启服务");
          await load();
          return;
        }
        const res = await api.applyRestartPlatformConfig();
        setMsg(`已保存并触发重启：${res.restarted.length} 个服务` +
          (res.skipped.length ? `，跳过 ${res.skipped.length} 个（${res.skipped.map((s) => s.name || s.id).join("、")}）` : ""));
      } else {
        setMsg("已保存。新发布服务自动生效；运行中服务需滚动重启后生效");
      }
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
          全部业务 agent 共享的兜底配置；服务可在发布/详情页按需覆盖非敏感项。
          带锁标记的字段存于 K8s Secret（保存后不回显）；修改后需滚动重启运行中服务才生效。
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

      <div className="space-x-2">
        <button onClick={() => save(false)} disabled={saving} data-testid="cfg-save"
          className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white rounded px-5 py-2 text-sm">保存</button>
        <button onClick={() => save(true)} disabled={saving} data-testid="cfg-save-restart"
          className="border border-blue-600 text-blue-600 hover:bg-blue-50 disabled:opacity-50 rounded px-5 py-2 text-sm">
          保存并滚动重启运行中服务
        </button>
      </div>
    </div>
  );
}
